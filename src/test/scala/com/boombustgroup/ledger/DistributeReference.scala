package com.boombustgroup.ledger

/** Pure reference model for production floor-based residual distribution.
  *
  * This is the canonical pure model of [[Distribute.distribute]]: same floor-per-share prefix, same last-element residual plug, but without
  * arrays or mutation. Production code is checked against this executable model so that the runtime implementation and the Stainless proof
  * shape can be discussed against one simpler reference semantics.
  */
object DistributeReference:

  def distribute(total: Long, shares: List[Long]): List[Long] =
    DistributeModel.distribute(total, shares.toVector).toList
