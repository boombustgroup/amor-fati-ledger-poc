package com.boombustgroup.ledger

/** Canonical pure model for production floor-based residual distribution.
  *
  * This keeps the exact production semantics in one executable place: floor-per-share prefix for all but the last recipient, then a final
  * residual plug so the result sums exactly to `total`.
  */
object DistributeModel:

  def distribute(total: Long, shares: Vector[Long]): Vector[Long] =
    require(shares.nonEmpty, "Cannot distribute to zero recipients")
    require(total >= 0L, "Total must be non-negative")
    require(shares.forall(_ >= 0L), "Shares must be non-negative")

    val shareSum = shares.foldLeft(0L)(_ + _)
    require(shareSum > 0L, "Share sum must be positive")

    val shareSumBigInt = BigInt(shareSum)
    val prefix = shares.init.map { share =>
      ((BigInt(total) * BigInt(share)) / shareSumBigInt).toLong
    }
    val allocated = prefix.foldLeft(0L)(_ + _)
    prefix :+ (total - allocated)
