package com.boombustgroup.ledger

import scala.collection.mutable

/** Array-based mutable world state for imperative interpreter.
  *
  * Data-Oriented Design: each (EntitySector, AssetType) pair maps to one Array[Long]. The interpreter does streaming reads + scattered
  * writes — 7 banks fit in L1 cache, 100K households are sequential.
  *
  * This is the imperative shell. The verified core (Verified.scala) uses immutable Map. Equivalence tests prove they produce identical
  * results bit-for-bit.
  */
class MutableWorldState(private val sectorSizes: Map[EntitySector, Int]):

  private val stores: mutable.Map[(EntitySector, AssetType), Array[Long]] = mutable.Map.empty

  /** Get or create the balance array for a (sector, asset) pair. */
  def getBalances(sector: EntitySector, asset: AssetType): Array[Long] =
    stores.getOrElseUpdate((sector, asset), new Array[Long](sectorSize(sector)))

  /** Read a single balance. */
  def balance(sector: EntitySector, asset: AssetType, index: Int): Long =
    stores.get((sector, asset)).map(_(index)).getOrElse(0L)

  /** Number of agents in a sector. */
  def sectorSize(sector: EntitySector): Int =
    sectorSizes.getOrElse(sector, 1)

  /** Snapshot all balances as immutable Map (for equivalence testing). */
  def snapshot: Map[(EntitySector, AssetType, Int), Long] =
    stores.flatMap { case ((sector, asset), arr) =>
      arr.indices.filter(i => arr(i) != 0L).map(i => (sector, asset, i) -> arr(i))
    }.toMap

  /** Total across all accounts for a given asset type. */
  def totalForAsset(asset: AssetType): Long =
    stores.collect { case ((_, a), arr) if a == asset => arr.sum }.sum
