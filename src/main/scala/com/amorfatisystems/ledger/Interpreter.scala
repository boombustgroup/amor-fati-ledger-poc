package com.amorfatisystems.ledger

/** Pure functional flow interpreter — verified core.
  *
  * Every operation preserves the SFC invariant: total system wealth is constant. Stainless/Z3 verifies this via pointwise conservation +
  * frame condition.
  *
  * This is the pure reference specification. Runtime callers use the public TransferExecutor contract; this interpreter remains internal to
  * the proof and property-test bridge.
  */
object Interpreter:

  /** Explicit anti-overflow contract for the pure runtime interpreter.
    *
    * The raw `applyFlow` implementation intentionally stays small and executable. Callers that want a runtime safety check instead of an
    * ambient assumption should use `canApplyFlow` / `applyCheckedFlow`.
    */
  def canApplyFlow(balances: Map[AccountId, Long], flow: Flow): Boolean =
    val currentFrom = balances.getOrElse(flow.from, 0L)
    val currentTo   = balances.getOrElse(flow.to, 0L)
    currentFrom >= Long.MinValue + flow.amount &&
    currentTo <= Long.MaxValue - flow.amount

  /** Sequence-level anti-overflow contract matching the Stainless `canApplyRuntimeFlowList` shape. */
  def canApplyAll(balances: Map[AccountId, Long], flows: Vector[Flow]): Boolean =
    flows
      .foldLeft(Option(balances)) { (stateOpt, flow) =>
        stateOpt.flatMap { state =>
          if canApplyFlow(state, flow) then Some(applyFlow(state, flow)) else None
        }
      }
      .isDefined

  def applyCheckedFlow(balances: Map[AccountId, Long], flow: Flow): Either[String, Map[AccountId, Long]] =
    Either.cond(
      canApplyFlow(balances, flow),
      applyFlow(balances, flow),
      s"Flow would overflow runtime Long bounds: from=${flow.from}, to=${flow.to}, amount=${flow.amount}"
    )

  def applyCheckedAll(balances: Map[AccountId, Long], flows: Vector[Flow]): Either[String, Map[AccountId, Long]] =
    flows.foldLeft[Either[String, Map[AccountId, Long]]](Right(balances)) { (stateEither, flow) =>
      stateEither.flatMap(state => applyCheckedFlow(state, flow))
    }

  /** Apply a single flow to a balance map. Double-entry: debit from, credit to.
    *
    * Assumes the caller already enforced `canApplyFlow` if runtime overflow safety is required.
    *
    * Mathematical post-conditions (verified by Stainless under the same anti-overflow contract):
    *   1. balances(from) + balances(to) is unchanged (flow conservation)
    *   2. All other accounts are unchanged (frame condition)
    */
  def applyFlow(balances: Map[AccountId, Long], flow: Flow): Map[AccountId, Long] =
    val currentFrom = balances.getOrElse(flow.from, 0L)
    val currentTo   = balances.getOrElse(flow.to, 0L)
    balances
      .updated(flow.from, currentFrom - flow.amount)
      .updated(flow.to, currentTo + flow.amount)

  /** Apply a list of flows sequentially.
    *
    * Assumes the caller already enforced `canApplyAll` if runtime overflow safety is required.
    */
  def applyAll(balances: Map[AccountId, Long], flows: Vector[Flow]): Map[AccountId, Long] =
    flows.foldLeft(balances)(applyFlow)

  /** Total system wealth: sum of all balances. */
  def totalWealth(balances: Map[AccountId, Long]): Long =
    balances.values.sum
