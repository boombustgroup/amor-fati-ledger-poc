package com.boombustgroup.ledger

/** A batch of monetary flows from one sector to another.
  *
  * Two variants for the two fundamental flow patterns in SFC-ABM:
  *   - Scatter (N:M): iterate over senders — HH→Bank, Firm→Gov (tax)
  *   - Broadcast (1:N): iterate over receivers — Gov→HH (transfers), ZUS→HH (pensions)
  */
sealed trait BatchedFlow:
  def from: EntitySector
  def to: EntitySector
  def asset: AssetType
  def mechanism: Mechanism

object BatchedFlow:

  /** N:M flow — amounts indexed by sender. Each sender pays their assigned target.
    *
    * amounts(42) = how much sender #42 pays. targetIndices(42) = which receiver gets it. amounts.length == sectorSize(from).
    */
  case class Scatter(
      from: EntitySector,
      to: EntitySector,
      amounts: Array[Long],
      targetIndices: Array[Int],
      asset: AssetType,
      mechanism: Mechanism
  ) extends BatchedFlow:
    require(amounts.length == targetIndices.length, s"amounts.length=${amounts.length} != targetIndices.length=${targetIndices.length}")

  /** 1:N flow — amounts indexed by receiver. Single sender pays all.
    *
    * amounts(42) = how much receiver #42 gets. fromIndex = which sender pays. amounts.length == number of receivers (may be <
    * sectorSize(to) if sparse). totalDebit is aggregated in one shot — avoids cache thrashing on fromStore.
    */
  case class Broadcast(
      from: EntitySector,
      fromIndex: Int,
      to: EntitySector,
      amounts: Array[Long],
      targetIndices: Array[Int],
      asset: AssetType,
      mechanism: Mechanism
  ) extends BatchedFlow:
    require(amounts.length == targetIndices.length, s"amounts.length=${amounts.length} != targetIndices.length=${targetIndices.length}")
