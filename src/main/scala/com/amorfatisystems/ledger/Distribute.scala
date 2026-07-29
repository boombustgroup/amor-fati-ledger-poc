package com.amorfatisystems.ledger

/** Proportional distribution with floor-based residual plugging.
  *
  * Distributes `total` across N recipients according to `shares`. All but the last recipient get their floored proportional allocation. The
  * last recipient absorbs the residual, guaranteeing: distribute(total, shares).sum == total
  *
  * This is exact by construction (Long addition). No tolerance needed.
  */
object Distribute:

  /** Distribute total across shares. Last element gets residual.
    *
    * @param total
    *   amount to distribute in caller-defined integer ledger units
    * @param shares
    *   proportional integer weights
    * @return
    *   array of amounts summing to exactly `total`
    */
  def distribute(total: Long, shares: Array[Long]): Array[Long] =
    DistributeModel.distribute(total, shares.toVector).toArray
