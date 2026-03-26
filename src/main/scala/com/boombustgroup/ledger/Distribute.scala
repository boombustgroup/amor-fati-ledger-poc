package com.boombustgroup.ledger

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
    *   amount to distribute (scale 10^4)
    * @param shares
    *   proportional weights (scale 10^4, i.e. 5000 = 50%)
    * @return
    *   array of amounts summing to exactly `total`
    */
  def distribute(total: Long, shares: Array[Long]): Array[Long] =
    DistributeModel.distribute(total, shares.toVector).toArray
