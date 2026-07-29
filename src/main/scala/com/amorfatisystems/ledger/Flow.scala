package com.amorfatisystems.ledger

/** Internal reference-model flow used by the Stainless bridge.
  *
  * The fundamental unit of SFC accounting. Every flow debits `from` and credits `to` by the same `amount` — double-entry by construction.
  *
  * `amount` is Long-based (scale-neutral integer ledger units) for exact additive arithmetic. No floating-point accumulation errors.
  */
private[ledger] case class Flow(
    from: AccountId,
    to: AccountId,
    amount: Long, // monetary amount (scale-neutral integer ledger units)
    mechanism: MechanismId
):
  require(from != to, s"Self-transfer: from=$from == to=$to")
  require(amount >= 0, s"Negative flow: $amount. Reverse from/to instead.")
