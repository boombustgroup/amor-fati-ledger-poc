package com.boombustgroup.ledger

/** Imperative flow interpreter — the fast production path.
  *
  * Applies BatchedFlows to MutableWorldState via Array[Long] scatter-writes. Cache-friendly: streaming read from amounts + targetIndices,
  * scattered write to target sector (7 banks fit in L1).
  *
  * Correctness guarantee: equivalence test proves this produces identical results to the pure Interpreter (which is formally verified by
  * Stainless/Z3). See EquivalenceSpec.
  */
object ImperativeInterpreter:

  private def validateBatch(state: MutableWorldState, batch: BatchedFlow): Unit =
    BatchExecutionContract.requireValidBatch(
      state.sectorSize,
      (sector, asset, index) => state.balance(sector, asset, index),
      batch
    )

  def canApplyBatch(state: MutableWorldState, batch: BatchedFlow): Boolean =
    BatchExecutionContract.canApplyBatch(
      state.sectorSize,
      (sector, asset, index) => state.balance(sector, asset, index),
      batch
    )

  def applyCheckedBatch(state: MutableWorldState, batch: BatchedFlow): Either[String, Unit] =
    BatchExecutionContract
      .validateBatch(
        state.sectorSize,
        (sector, asset, index) => state.balance(sector, asset, index),
        batch
      )
      .map(_ => applyBatch(state, batch))

  def applyCheckedAll(state: MutableWorldState, flows: Vector[BatchedFlow]): Either[String, Unit] =
    flows.foldLeft[Either[String, Unit]](Right(())) { (acc, batch) =>
      acc.flatMap(_ => applyCheckedBatch(state, batch))
    }

  /** Execute a batch sequence that has already been validated against the current state snapshot. */
  def applyValidatedPlan(state: MutableWorldState, plan: ValidatedBatchPlan): Unit =
    applyAll(state, plan.batches)

  /** Preferred high-level entrypoint: validate the batch sequence against the current state snapshot, then execute it. */
  def planAndApplyAll(state: MutableWorldState, flows: Vector[BatchedFlow]): Either[String, Unit] =
    ValidatedBatchPlan.fromState(state, flows).map(plan => applyValidatedPlan(state, plan))

  /** Apply a single batched flow. O(N) where N = amounts.length. */
  def applyBatch(state: MutableWorldState, batch: BatchedFlow): Unit =
    validateBatch(state, batch)
    batch match
      case BatchedFlow.Scatter(from, to, amounts, targets, asset, _) =>
        val fromStore = state.getBalances(from, asset)
        val toStore   = state.getBalances(to, asset)
        var i         = 0
        while i < amounts.length do
          val amount = amounts(i)
          if amount != 0L then
            fromStore(i) -= amount
            toStore(targets(i)) += amount
          i += 1

      case BatchedFlow.Broadcast(from, fromIdx, to, amounts, targets, asset, _) =>
        val fromStore  = state.getBalances(from, asset)
        val toStore    = state.getBalances(to, asset)
        var i          = 0
        var totalDebit = 0L
        while i < amounts.length do
          val amount = amounts(i)
          if amount != 0L then
            totalDebit += amount
            toStore(targets(i)) += amount
          i += 1
        fromStore(fromIdx) -= totalDebit

  /** Apply a sequence of batched flows.
    *
    * Low-level runtime entrypoint. Prefer `planAndApplyAll` or `applyValidatedPlan` when callers want the validated batch-plan contract.
    */
  def applyAll(state: MutableWorldState, flows: Vector[BatchedFlow]): Unit =
    var i = 0
    while i < flows.length do
      applyBatch(state, flows(i))
      i += 1
