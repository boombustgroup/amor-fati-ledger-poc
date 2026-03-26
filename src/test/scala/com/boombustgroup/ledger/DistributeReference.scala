package com.boombustgroup.ledger

/** Pure reference model for production floor-based residual distribution.
  *
  * This is the canonical pure model of [[Distribute.distribute]]: same floor-per-share prefix, same last-element residual plug, but without
  * arrays or mutation. Production code is checked against this executable model so that the runtime implementation and the Stainless proof
  * shape can be discussed against one simpler reference semantics.
  */
object DistributeReference:

  def distribute(total: Long, shares: List[Long]): List[Long] =
    require(shares.nonEmpty, "Cannot distribute to zero recipients")
    require(total >= 0L, "Total must be non-negative")
    require(shares.forall(_ >= 0L), "Shares must be non-negative")
    val shareSum = shares.foldLeft(0L)(_ + _)
    require(shareSum > 0L, "Share sum must be positive")

    val shareSumBigInt = BigInt(shareSum)
    val prefix         = shares.init.map(share => ((BigInt(total) * BigInt(share)) / shareSumBigInt).toLong)
    val allocated      = prefix.foldLeft(0L)(_ + _)
    prefix :+ (total - allocated)
