package com.boombustgroup.ledger

/** Shared executable batch semantics.
  *
  * This isolates "what balances must change" from "how the mutable runtime applies those changes". It is intentionally pure and much closer
  * to a refinement target than the raw array-mutation loops in the imperative interpreter.
  */
object BatchDeltaSemantics:

  final case class ScatterDelta(senderIndex: Int, targetIndex: Int, amount: Long)
  final case class BroadcastCredit(targetIndex: Int, amount: Long)

  sealed trait BatchPlan:
    def from: EntitySector
    def to: EntitySector
    def asset: AssetType

  final case class ScatterPlan(
      from: EntitySector,
      to: EntitySector,
      asset: AssetType,
      deltas: Vector[ScatterDelta]
  ) extends BatchPlan

  final case class BroadcastPlan(
      from: EntitySector,
      to: EntitySector,
      asset: AssetType,
      fromIndex: Int,
      totalDebit: Long,
      credits: Vector[BroadcastCredit]
  ) extends BatchPlan

  def plan(batch: BatchedFlow): BatchPlan = batch match
    case BatchedFlow.Scatter(from, to, amounts, targets, asset, _) =>
      ScatterPlan(
        from,
        to,
        asset,
        amounts.indices.collect {
          case i if amounts(i) != 0L => ScatterDelta(i, targets(i), amounts(i))
        }.toVector
      )

    case BatchedFlow.Broadcast(from, fromIdx, to, amounts, targets, asset, _) =>
      val credits = amounts.indices.collect {
        case i if amounts(i) != 0L => BroadcastCredit(targets(i), amounts(i))
      }.toVector
      BroadcastPlan(
        from,
        to,
        asset,
        fromIdx,
        credits.foldLeft(0L)(_ + _.amount),
        credits
      )
