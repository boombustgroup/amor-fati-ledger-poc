package com.amorfatisystems.ledger

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

/** Test-only bridge between the shared pure distribution model and the Stainless floor-with-residual list shape.
  *
  * This is not a formal Stainless proof over the production `Array[Long]` implementation. It narrows the gap by checking that the shared
  * executable model in main code follows the same BigInt list shape that `Verified.scala` proves.
  */
class DistributeVerifiedBridgeSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks:

  private def bigIntFloorPrefix(total: Long, shares: List[Long]): List[BigInt] =
    val denom = shares.foldLeft(BigInt(0))((acc, share) => acc + BigInt(share))
    shares.init.map(share => (BigInt(total) * BigInt(share)) / denom)

  private def verifiedShape(total: Long, shares: List[Long]): List[BigInt] =
    val prefix = bigIntFloorPrefix(total, shares)
    prefix :+ (BigInt(total) - prefix.foldLeft(BigInt(0))(_ + _))

  "DistributeModel.distribute" should "match the Verified floor-with-residual list shape" in {
    val genTotal  = Gen.choose(1L, 10000000000L)
    val genSize   = Gen.choose(1, 20)
    val genShares = genSize.flatMap(n => Gen.listOfN(n, Gen.choose(1L, 10000L)))

    forAll(genTotal, genShares) { (total, shares) =>
      val model = DistributeModel.distribute(total, shares.toVector).map(BigInt(_)).toList
      model shouldBe verifiedShape(total, shares)
    }
  }

  it should "match the Stainless floor-prefix and residual decomposition for singleton inputs" in {
    val total  = 12345L
    val shares = List(10000L)

    DistributeModel.distribute(total, shares.toVector).map(BigInt(_)).toList shouldBe verifiedShape(total, shares)
  }

  it should "keep the reference list projection aligned with the shared pure model" in {
    val genTotal  = Gen.choose(1L, 10000000000L)
    val genSize   = Gen.choose(1, 20)
    val genShares = genSize.flatMap(n => Gen.listOfN(n, Gen.choose(1L, 10000L)))

    forAll(genTotal, genShares) { (total, shares) =>
      DistributeReference.distribute(total, shares) shouldBe DistributeModel.distribute(total, shares.toVector).toList
    }
  }
