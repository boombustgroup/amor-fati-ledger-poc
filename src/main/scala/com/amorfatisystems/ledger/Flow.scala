package com.amorfatisystems.ledger

/** A single monetary flow between two accounts.
  *
  * The fundamental unit of SFC accounting. Every flow debits `from` and credits `to` by the same `amount` — double-entry by construction.
  *
  * `amount` is Long-based (scale-neutral integer ledger units) for exact additive arithmetic. No floating-point accumulation errors.
  */
case class Flow(
    from: AccountId,
    to: AccountId,
    amount: Long,  // monetary amount (scale-neutral integer ledger units)
    mechanism: MechanismId
):
  require(from != to, s"Self-transfer: from=$from == to=$to")
  require(amount >= 0, s"Negative flow: $amount. Reverse from/to instead.")
