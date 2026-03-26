package com.boombustgroup.ledger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.scalacheck.Gen

class DistributeSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks:

  private def floorPrefix(total: Long, shares: Array[Long]): Array[Long] =
    val shareSum = shares.sum
    shares.init.map(share => ((BigInt(total) * BigInt(share)) / BigInt(shareSum)).toLong)

  "distribute" should "always sum to total (residual plugging)" in {
    val genTotal  = Gen.choose(1L, 10000000000L)
    val genShares = Gen.listOfN(7, Gen.choose(1L, 10000L)).map(_.toArray)
    forAll(genTotal, genShares) { (total, shares) =>
      val result = Distribute.distribute(total, shares)
      result.sum shouldBe total
    }
  }

  it should "handle equal shares" in {
    val result = Distribute.distribute(10000L, Array(1L, 1L, 1L))
    result.sum shouldBe 10000L
  }

  it should "handle single recipient" in {
    val result = Distribute.distribute(12345L, Array(10000L))
    result(0) shouldBe 12345L
  }

  it should "handle large number of recipients" in {
    val shares = Array.fill(100)(100L)
    val result = Distribute.distribute(999999L, shares)
    result.sum shouldBe 999999L
  }

  it should "handle extreme share imbalance" in {
    val shares = Array(9999L, 1L)
    val result = Distribute.distribute(1000000L, shares)
    result.sum shouldBe 1000000L
    result(0) should be > result(1)
  }

  it should "produce non-negative results for proportional shares" in {
    val genTotal  = Gen.choose(1L, 10000000000L)
    val genShares = Gen.listOfN(7, Gen.choose(1L, 10000L)).map(_.toArray)
    forAll(genTotal, genShares) { (total, shares) =>
      whenever(total >= 0) {
        val result = Distribute.distribute(total, shares)
        result.sum shouldBe total
        result.forall(_ >= 0L) shouldBe true
      }
    }
  }

  it should "not produce negative residuals for small equal shares" in {
    val result = Distribute.distribute(2L, Array(1L, 1L, 1L, 1L))
    result.sum shouldBe 2L
    result.forall(_ >= 0L) shouldBe true
  }

  it should "match the pure reference model" in {
    val genTotal  = Gen.choose(1L, 10000000000L)
    val genSize   = Gen.choose(1, 20)
    val genShares = genSize.flatMap(n => Gen.listOfN(n, Gen.choose(1L, 10000L)).map(_.toArray))
    forAll(genTotal, genShares) { (total, shares) =>
      val result    = Distribute.distribute(total, shares)
      val reference = DistributeReference.distribute(total, shares.toList).toArray
      result shouldBe reference
    }
  }

  it should "preserve the floor-with-residual shape proved in Stainless" in {
    val genTotal  = Gen.choose(1L, 10000000000L)
    val genSize   = Gen.choose(1, 20)
    val genShares = genSize.flatMap(n => Gen.listOfN(n, Gen.choose(1L, 10000L)).map(_.toArray))
    forAll(genTotal, genShares) { (total, shares) =>
      val result = Distribute.distribute(total, shares)

      result.length shouldBe shares.length
      result.init.sameElements(floorPrefix(total, shares)) shouldBe true
      result.last shouldBe total - result.init.sum
      result.forall(_ >= 0L) shouldBe true
    }
  }

  it should "delegate production and reference adapters to the same pure model" in {
    val genTotal  = Gen.choose(1L, 10000000000L)
    val genSize   = Gen.choose(1, 20)
    val genShares = genSize.flatMap(n => Gen.listOfN(n, Gen.choose(1L, 10000L)).map(_.toArray))
    forAll(genTotal, genShares) { (total, shares) =>
      val model = DistributeModel.distribute(total, shares.toVector)

      Distribute.distribute(total, shares).toVector shouldBe model
      DistributeReference.distribute(total, shares.toList) shouldBe model.toList
    }
  }
