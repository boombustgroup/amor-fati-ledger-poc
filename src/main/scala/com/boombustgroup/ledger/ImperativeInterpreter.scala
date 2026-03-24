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

  /** Apply a single batched flow. O(N) where N = amounts.length. */
  def applyBatch(state: MutableWorldState, batch: BatchedFlow): Unit =
    val fromStore = state.getBalances(batch.from, batch.asset)
    val toStore   = state.getBalances(batch.to, batch.asset)
    val amounts   = batch.amounts
    val targets   = batch.targetIndices
    var i         = 0
    while i < amounts.length do
      val amount = amounts(i)
      if amount != 0L then
        fromStore(i) -= amount
        toStore(targets(i)) += amount
      i += 1

  /** Apply a sequence of batched flows. */
  def applyAll(state: MutableWorldState, flows: Vector[BatchedFlow]): Unit =
    var i = 0
    while i < flows.length do
      applyBatch(state, flows(i))
      i += 1
