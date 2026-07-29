package com.amorfatisystems.ledger

/** A batch of monetary flows from one sector to another.
  *
  * Two variants for the two fundamental flow patterns in SFC-ABM:
  *   - Scatter (N:M): iterate over senders — HH→Bank, Firm→Gov (tax)
  *   - Broadcast (1:N): iterate over receivers — Government→population (transfers), payer→population (pensions)
  */
sealed trait BatchedFlow:
  def from: AccountGroupId
  def to: AccountGroupId
  def asset: InstrumentId
  def mechanism: MechanismId

object BatchedFlow:

  /** N:M flow — amounts indexed by sender. Each sender pays their assigned target.
    *
    * amounts(42) = how much sender #42 pays. targetIndices(42) = which receiver gets it. amounts.length == sectorSize(from).
    */
  case class Scatter(
      from: AccountGroupId,
      to: AccountGroupId,
      amounts: Array[Long],
      targetIndices: Array[Int],
      asset: InstrumentId,
      mechanism: MechanismId
  ) extends BatchedFlow:
    require(amounts.length == targetIndices.length, s"amounts.length=${amounts.length} != targetIndices.length=${targetIndices.length}")

  /** 1:N flow — amounts indexed by receiver. Single sender pays all.
    *
    * amounts(42) = how much receiver #42 gets. fromIndex = which sender pays. amounts.length == number of receivers (may be <
    * sectorSize(to) if sparse). totalDebit is aggregated in one shot — avoids cache thrashing on fromStore.
    */
  case class Broadcast(
      from: AccountGroupId,
      fromIndex: Int,
      to: AccountGroupId,
      amounts: Array[Long],
      targetIndices: Array[Int],
      asset: InstrumentId,
      mechanism: MechanismId
  ) extends BatchedFlow:
    require(amounts.length == targetIndices.length, s"amounts.length=${amounts.length} != targetIndices.length=${targetIndices.length}")
