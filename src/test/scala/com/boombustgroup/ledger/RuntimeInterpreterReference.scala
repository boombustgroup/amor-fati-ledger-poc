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
    batch match
      case BatchedFlow.Scatter(from, to, amounts, targets, asset, _) =>
        amounts.indices.foldLeft(state) { (acc, i) =>
          val amount = amounts(i)
          if amount == 0L then acc
          else
            update(
              update(acc, from, asset, i, -amount),
              to,
              asset,
              targets(i),
              amount
            )
        }

      case BatchedFlow.Broadcast(from, fromIdx, to, amounts, targets, asset, _) =>
        val afterCredits = amounts.indices.foldLeft(state) { (acc, i) =>
          val amount = amounts(i)
          if amount == 0L then acc
          else update(acc, to, asset, targets(i), amount)
        }
        val totalDebit = amounts.foldLeft(0L)(_ + _)
        update(afterCredits, from, asset, fromIdx, -totalDebit)

  def applyAll(sectorSizes: Map[EntitySector, Int], state: BalanceState, flows: Vector[BatchedFlow]): BalanceState =
    flows.foldLeft(state)((acc, batch) => applyBatch(sectorSizes, acc, batch))
