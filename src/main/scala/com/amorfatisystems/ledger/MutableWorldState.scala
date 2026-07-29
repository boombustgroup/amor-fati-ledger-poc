package com.amorfatisystems.ledger

import scala.collection.mutable

/** Array-based mutable world state for imperative interpreter.
  *
  * Data-Oriented Design: each (AccountPartitionId, InstrumentId) pair maps to one Array[Long]. The interpreter does streaming reads +
  * scattered writes over caller-defined account partitions.
  *
  * This is the imperative shell. The verified core (Verified.scala) uses immutable Map. Equivalence tests prove they produce identical
  * results bit-for-bit.
  *
  * Important runtime boundary:
  *   - raw backing arrays are intentionally exposed only to internal `ledger` package code
  *   - public callers should prefer checked helpers like `balanceOption`, `setBalance`, and `adjustBalance`
  *   - callers build validated `Transfer` operations before mutating state
  */
private[ledger] class MutableWorldState(private val partitionSizes: Map[AccountPartitionId, Int]):

  private val stores: mutable.Map[(AccountPartitionId, InstrumentId), Array[Long]] = mutable.Map.empty
  private var version: Long                                                        = 0L

  /** Monotonic snapshot stamp used to reject stale validated plans. */
  def snapshotVersion: Long = version

  private[ledger] def markCommitted(): Unit = version = Math.addExact(version, 1L)

  private def hasValidIndex(partition: AccountPartitionId, index: Int): Boolean =
    index >= 0 && index < partitionSize(partition)

  /** Low-level internal access to the backing array for a (partition, asset) pair. */
  private[ledger] def getBalances(partition: AccountPartitionId, asset: InstrumentId): Array[Long] =
    stores.getOrElseUpdate((partition, asset), new Array[Long](partitionSize(partition)))

  /** Checked read of a single balance. */
  def balance(partition: AccountPartitionId, asset: InstrumentId, index: Int): Long =
    require(hasValidIndex(partition, index), s"Index $index out of bounds for partitionSize($partition)=${partitionSize(partition)}")
    stores.get((partition, asset)).map(_(index)).getOrElse(0L)

  /** Safe read that returns `None` instead of throwing for an invalid index. */
  def balanceOption(partition: AccountPartitionId, asset: InstrumentId, index: Int): Option[Long] =
    Option.when(hasValidIndex(partition, index))(stores.get((partition, asset)).map(_(index)).getOrElse(0L))

  /** Checked write of an absolute balance value. */
  def setBalance(partition: AccountPartitionId, asset: InstrumentId, index: Int, value: Long): Either[String, Unit] =
    if !hasValidIndex(partition, index) then Left(s"Index $index out of bounds for partitionSize($partition)=${partitionSize(partition)}")
    else
      getBalances(partition, asset)(index) = value
      Right(())

  /** Checked delta update that rejects out-of-bounds writes and Long overflow. */
  def adjustBalance(partition: AccountPartitionId, asset: InstrumentId, index: Int, delta: Long): Either[String, Unit] =
    if !hasValidIndex(partition, index) then Left(s"Index $index out of bounds for partitionSize($partition)=${partitionSize(partition)}")
    else
      val store   = getBalances(partition, asset)
      val current = store(index)
      val updated = BigInt(current) + BigInt(delta)
      if updated > BigInt(Long.MaxValue) then Left(s"Balance update would overflow Long at index $index: current=$current delta=$delta")
      else if updated < BigInt(Long.MinValue) then
        Left(s"Balance update would underflow Long at index $index: current=$current delta=$delta")
      else
        store(index) = updated.toLong
        Right(())

  /** Number of agents in a partition. */
  def partitionSize(partition: AccountPartitionId): Int =
    partitionSizes.getOrElse(partition, 0)

  /** Read-only view of configured partition sizes for validated planning. */
  def partitionSizesView: Map[AccountPartitionId, Int] =
    partitionSizes

  /** Snapshot all balances as immutable Map (for equivalence testing). */
  def snapshot: Map[(AccountPartitionId, InstrumentId, Int), Long] =
    stores.flatMap { case ((partition, asset), arr) =>
      arr.indices.filter(i => arr(i) != 0L).map(i => (partition, asset, i) -> arr(i))
    }.toMap

  /** Total across all accounts for a given asset type. */
  def totalForAsset(asset: InstrumentId): Long =
    stores.collect { case ((_, a), arr) if a == asset => arr.sum }.sum
