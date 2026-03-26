package com.boombustgroup.ledger

/** Pure reference model for the imperative runtime interpreter.
  *
  * This mirrors [[ImperativeInterpreter]] batch semantics on an immutable keyed state: `(sector, asset, index) -> balance`.
  */
object RuntimeInterpreterReference:

  type BalanceKey   = (EntitySector, AssetType, Int)
  type BalanceState = Map[BalanceKey, Long]

  private def key(sector: EntitySector, asset: AssetType, index: Int): BalanceKey =
    (sector, asset, index)

  def snapshotToFlatMap(
      snapshot: BalanceState,
      sectorOffsets: Map[EntitySector, Int],
      asset: AssetType
  ): Map[Int, Long] =
    snapshot.collect {
      case ((sector, a, index), balance) if a == asset =>
        (sectorOffsets(sector) + index) -> balance
    }

  private def update(state: BalanceState, sector: EntitySector, asset: AssetType, index: Int, delta: Long): BalanceState =
    val k       = key(sector, asset, index)
    val updated = state.getOrElse(k, 0L) + delta
    if updated == 0L then state - k else state.updated(k, updated)

  def canApplyBatch(sectorSizes: Map[EntitySector, Int], state: BalanceState, batch: BatchedFlow): Boolean =
    BatchExecutionContract.canApplyBatch(
      sector => sectorSizes.getOrElse(sector, 1),
      (sector, asset, index) => state.getOrElse(key(sector, asset, index), 0L),
      batch
    )

  def applyBatch(sectorSizes: Map[EntitySector, Int], state: BalanceState, batch: BatchedFlow): BalanceState =
    BatchExecutionContract.requireValidBatch(
      sector => sectorSizes.getOrElse(sector, 1),
      (sector, asset, index) => state.getOrElse(key(sector, asset, index), 0L),
      batch
    )
    BatchDeltaSemantics.plan(batch) match
      case BatchDeltaSemantics.ScatterPlan(from, to, asset, deltas) =>
        deltas.foldLeft(state) { (acc, delta) =>
          update(
            update(acc, from, asset, delta.senderIndex, -delta.amount),
            to,
            asset,
            delta.targetIndex,
            delta.amount
          )
        }

      case BatchDeltaSemantics.BroadcastPlan(from, to, asset, fromIdx, totalDebit, credits) =>
        val afterCredits = credits.foldLeft(state) { (acc, credit) =>
          update(acc, to, asset, credit.targetIndex, credit.amount)
        }
        update(afterCredits, from, asset, fromIdx, -totalDebit)

  def applyAll(sectorSizes: Map[EntitySector, Int], state: BalanceState, flows: Vector[BatchedFlow]): BalanceState =
    flows.foldLeft(state)((acc, batch) => applyBatch(sectorSizes, acc, batch))
