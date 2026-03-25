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
    require(shares.nonEmpty, "Cannot distribute to zero recipients")
    require(total >= 0L, "Total must be non-negative")
    val n        = shares.length
    val results  = new Array[Long](n)
    val shareSum = shares.sum
    require(shares.forall(_ >= 0L), "Shares must be non-negative")
    require(shareSum > 0, "Share sum must be positive")
    val shareSumBigInt = BigInt(shareSum)
    var allocated      = 0L
    var i              = 0
    while i < n - 1 do
      results(i) = ((BigInt(total) * BigInt(shares(i))) / shareSumBigInt).toLong
      allocated += results(i)
      i += 1
    results(n - 1) = total - allocated
    results
