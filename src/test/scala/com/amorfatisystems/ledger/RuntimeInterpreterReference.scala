package com.amorfatisystems.ledger

/** Pure reference model for the imperative runtime interpreter.
  *
  * This mirrors [[ImperativeInterpreter]] batch semantics on an immutable keyed state: `(partition, asset, index) -> balance`.
  */
object RuntimeInterpreterReference:

  type BalanceKey   = (AccountPartitionId, InstrumentId, Int)
  type BalanceState = Map[BalanceKey, Long]

  private def key(partition: AccountPartitionId, asset: InstrumentId, index: Int): BalanceKey =
    (partition, asset, index)

  def snapshotToFlatMap(
      snapshot: BalanceState,
      partitionOffsets: Map[AccountPartitionId, Int],
      asset: InstrumentId
  ): Map[Int, Long] =
    snapshot.collect {
      case ((partition, a, index), balance) if a == asset =>
        (partitionOffsets(partition) + index) -> balance
    }

  private def update(state: BalanceState, partition: AccountPartitionId, asset: InstrumentId, index: Int, delta: Long): BalanceState =
    val k       = key(partition, asset, index)
    val updated = state.getOrElse(k, 0L) + delta
    if updated == 0L then state - k else state.updated(k, updated)

  def canApplyBatch(partitionSizes: Map[AccountPartitionId, Int], state: BalanceState, batch: BatchedFlow): Boolean =
    BatchExecutionContract.canApplyBatch(
      partition => partitionSizes.getOrElse(partition, 1),
      (partition, asset, index) => state.getOrElse(key(partition, asset, index), 0L),
      batch
    )

  def applyBatch(partitionSizes: Map[AccountPartitionId, Int], state: BalanceState, batch: BatchedFlow): BalanceState =
    BatchExecutionContract.requireValidBatch(
      partition => partitionSizes.getOrElse(partition, 1),
      (partition, asset, index) => state.getOrElse(key(partition, asset, index), 0L),
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

  def applyAll(partitionSizes: Map[AccountPartitionId, Int], state: BalanceState, flows: Vector[BatchedFlow]): BalanceState =
    flows.foldLeft(state)((acc, batch) => applyBatch(partitionSizes, acc, batch))
