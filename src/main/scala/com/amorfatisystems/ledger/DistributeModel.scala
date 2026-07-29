package com.amorfatisystems.ledger

/** Canonical pure model for production floor-based residual distribution.
  *
  * This keeps the exact production semantics in one executable place: floor-per-share prefix for all but the last recipient, then a final
  * residual plug so the result sums exactly to `total`.
  */
object DistributeModel:

  def canDistribute(total: Long, shares: Vector[Long]): Boolean =
    total >= 0L &&
      shares.nonEmpty &&
      shares.forall(_ >= 0L) &&
      shares.foldLeft(BigInt(0))((acc, share) => acc + BigInt(share)) > BigInt(0)

  def distributeChecked(total: Long, shares: Vector[Long]): Either[String, Vector[Long]] =
    if !canDistribute(total, shares) then Left("Distribution inputs must be non-empty, non-negative, and have positive share sum")
    else Right(distribute(total, shares))

  def distribute(total: Long, shares: Vector[Long]): Vector[Long] =
    require(shares.nonEmpty, "Cannot distribute to zero recipients")
    require(total >= 0L, "Total must be non-negative")
    require(shares.forall(_ >= 0L), "Shares must be non-negative")

    val shareSumBigInt = shares.foldLeft(BigInt(0))((acc, share) => acc + BigInt(share))
    require(shareSumBigInt > BigInt(0), "Share sum must be positive")
    val prefix = shares.init.map { share =>
      ((BigInt(total) * BigInt(share)) / shareSumBigInt).toLong
    }
    val allocated = prefix.foldLeft(BigInt(0))((acc, amount) => acc + BigInt(amount))
    prefix :+ (BigInt(total) - allocated).toLong
