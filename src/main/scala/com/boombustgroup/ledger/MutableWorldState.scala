package com.boombustgroup.ledger

import scala.collection.mutable

/** Array-based mutable world state for imperative interpreter.
  *
  * Data-Oriented Design: each (EntitySector, AssetType) pair maps to one Array[Long]. The interpreter does streaming reads + scattered
  * writes — 7 banks fit in L1 cache, 100K households are sequential.
  *
  * This is the imperative shell. The verified core (Verified.scala) uses immutable Map. Equivalence tests prove they produce identical
  * results bit-for-bit.
  *
  * Important runtime boundary:
  *   - raw backing arrays are intentionally exposed only to internal `ledger` package code
  *   - public callers should prefer checked helpers like `balanceOption`, `setBalance`, and `adjustBalance`
  *   - `BatchExecutionContract` remains the main batched-flow guardrail for sequence execution
  */
class MutableWorldState(private val sectorSizes: Map[EntitySector, Int]):

  private val stores: mutable.Map[(EntitySector, AssetType), Array[Long]] = mutable.Map.empty

  private def hasValidIndex(sector: EntitySector, index: Int): Boolean =
    index >= 0 && index < sectorSize(sector)

  /** Low-level internal access to the backing array for a (sector, asset) pair. */
  private[ledger] def getBalances(sector: EntitySector, asset: AssetType): Array[Long] =
    stores.getOrElseUpdate((sector, asset), new Array[Long](sectorSize(sector)))

  /** Checked read of a single balance. */
  def balance(sector: EntitySector, asset: AssetType, index: Int): Long =
    require(hasValidIndex(sector, index), s"Index $index out of bounds for sectorSize($sector)=${sectorSize(sector)}")
    stores.get((sector, asset)).map(_(index)).getOrElse(0L)

  /** Safe read that returns `None` instead of throwing for an invalid index. */
  def balanceOption(sector: EntitySector, asset: AssetType, index: Int): Option[Long] =
    Option.when(hasValidIndex(sector, index))(stores.get((sector, asset)).map(_(index)).getOrElse(0L))

  /** Checked write of an absolute balance value. */
  def setBalance(sector: EntitySector, asset: AssetType, index: Int, value: Long): Either[String, Unit] =
    if !hasValidIndex(sector, index) then Left(s"Index $index out of bounds for sectorSize($sector)=${sectorSize(sector)}")
    else
      getBalances(sector, asset)(index) = value
      Right(())

  /** Checked delta update that rejects out-of-bounds writes and Long overflow. */
  def adjustBalance(sector: EntitySector, asset: AssetType, index: Int, delta: Long): Either[String, Unit] =
    if !hasValidIndex(sector, index) then Left(s"Index $index out of bounds for sectorSize($sector)=${sectorSize(sector)}")
    else
      val store   = getBalances(sector, asset)
      val current = store(index)
      val updated = BigInt(current) + BigInt(delta)
      if updated > BigInt(Long.MaxValue) then Left(s"Balance update would overflow Long at index $index: current=$current delta=$delta")
      else if updated < BigInt(Long.MinValue) then
        Left(s"Balance update would underflow Long at index $index: current=$current delta=$delta")
      else
        store(index) = updated.toLong
        Right(())

  /** Number of agents in a sector. */
  def sectorSize(sector: EntitySector): Int =
    sectorSizes.getOrElse(sector, 1)

  /** Read-only view of configured sector sizes for validated planning. */
  def sectorSizesView: Map[EntitySector, Int] =
    sectorSizes

  /** Snapshot all balances as immutable Map (for equivalence testing). */
  def snapshot: Map[(EntitySector, AssetType, Int), Long] =
    stores.flatMap { case ((sector, asset), arr) =>
      arr.indices.filter(i => arr(i) != 0L).map(i => (sector, asset, i) -> arr(i))
    }.toMap

  /** Total across all accounts for a given asset type. */
  def totalForAsset(asset: AssetType): Long =
    stores.collect { case ((_, a), arr) if a == asset => arr.sum }.sum
