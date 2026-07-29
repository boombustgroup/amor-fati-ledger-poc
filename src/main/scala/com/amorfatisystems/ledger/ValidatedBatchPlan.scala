package com.amorfatisystems.ledger

/** A caller-facing validated batch sequence.
  *
  * Constructing this wrapper proves, by executable checks, that every batch in the sequence is shape-valid and Long-safe when applied in
  * order to the current mutable world state snapshot.
  */
final case class ValidatedBatchPlan private (batches: Vector[BatchedFlow], snapshotVersion: Long)

object ValidatedBatchPlan:

  def fromState(state: MutableWorldState, batches: Vector[BatchedFlow]): Either[String, ValidatedBatchPlan] =
    val scratch = new MutableWorldStateSnapshot(state)
    batches
      .foldLeft[Either[String, Unit]](Right(())) { (acc, batch) =>
        acc.flatMap { _ =>
          BatchExecutionContract
            .validateBatch(
              scratch.partitionSize,
              (partition, asset, index) => scratch.balance(partition, asset, index),
              batch
            )
            .map { _ =>
              scratch.apply(batch)
              ()
            }
        }
      }
      .map(_ => ValidatedBatchPlan(batches, state.snapshotVersion))

  private final class MutableWorldStateSnapshot(state: MutableWorldState):
    private val partitionSizes = Map.from(state.partitionSizesView)
    private var balances    = state.snapshot

    private def update(partition: AccountPartitionId, asset: InstrumentId, index: Int, delta: Long): Unit =
      val key     = (partition, asset, index)
      val updated = balances.getOrElse(key, 0L) + delta
      balances =
        if updated == 0L then balances - key
        else balances.updated(key, updated)

    def partitionSize(partition: AccountPartitionId): Int =
      partitionSizes.getOrElse(partition, 1)

    def balance(partition: AccountPartitionId, asset: InstrumentId, index: Int): Long =
      balances.getOrElse((partition, asset, index), 0L)

    def apply(batch: BatchedFlow): Unit =
      batch match
        case BatchedFlow.Scatter(from, to, amounts, targets, asset, _) =>
          var i = 0
          while i < amounts.length do
            val amount = amounts(i)
            if amount != 0L then
              update(from, asset, i, -amount)
              update(to, asset, targets(i), amount)
            i += 1

        case BatchedFlow.Broadcast(from, fromIdx, to, amounts, targets, asset, _) =>
          var i          = 0
          var totalDebit = 0L
          while i < amounts.length do
            val amount = amounts(i)
            if amount != 0L then
              totalDebit += amount
              update(to, asset, targets(i), amount)
            i += 1
          update(from, asset, fromIdx, -totalDebit)
