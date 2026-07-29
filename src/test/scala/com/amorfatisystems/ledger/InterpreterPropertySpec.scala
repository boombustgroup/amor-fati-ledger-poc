package com.amorfatisystems.ledger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.scalacheck.Gen

class InterpreterPropertySpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks:

  private val genAccountId = Gen.choose(0, 99).map(AccountId(_))
  private val genAmount    = Gen.choose(1L, 1000000000L) // generic ledger units
  private val genBalance   = Gen.choose(-1000000000L, 1000000000L)

  private val genFlow = for
    from   <- genAccountId
    to     <- genAccountId.suchThat(_ != from)
    amount <- genAmount
  yield Flow(from, to, amount, mechanism = MechanismId(0))

  private val genBalances = for
    n        <- Gen.choose(2, 20)
    accounts <- Gen.listOfN(n, Gen.zip(genAccountId, genBalance))
  yield accounts.map { case (k, v) => k -> v }.toMap

  "applyFlow" should "always preserve total wealth (flow conservation)" in {
    forAll(genBalances, genFlow) { (balances, flow) =>
      val result = Interpreter.applyFlow(balances, flow)
      Interpreter.totalWealth(result) shouldBe Interpreter.totalWealth(balances)
    }
  }

  it should "satisfy frame condition (other accounts unchanged)" in {
    forAll(genBalances, genFlow) { (balances, flow) =>
      val result = Interpreter.applyFlow(balances, flow)
      balances.foreach { (k, v) =>
        if k != flow.from && k != flow.to then result.getOrElse(k, 0L) shouldBe v
      }
    }
  }

  "applyAll" should "preserve total wealth for any flow sequence" in {
    val genFlows = Gen.listOfN(50, genFlow).map(_.toVector)
    forAll(genBalances, genFlows) { (balances, flows) =>
      val result = Interpreter.applyAll(balances, flows)
      Interpreter.totalWealth(result) shouldBe Interpreter.totalWealth(balances)
    }
  }

  it should "be equivalent to sequential single-flow application" in {
    val genFlows = Gen.listOfN(10, genFlow).map(_.toVector)
    forAll(genBalances, genFlows) { (balances, flows) =>
      val batchResult = Interpreter.applyAll(balances, flows)
      val seqResult   = flows.foldLeft(balances)(Interpreter.applyFlow)
      batchResult shouldBe seqResult
    }
  }

  "independent flows" should "commute (order doesn't matter for disjoint accounts)" in {
    forAll(genBalances, genAmount, genAmount) { (balances, amt1, amt2) =>
      whenever(balances.size >= 4) {
        val keys = balances.keys.toVector.sortBy(AccountId.value).take(4)
        val f1   = Flow(keys(0), keys(1), amt1, MechanismId(0))
        val f2   = Flow(keys(2), keys(3), amt2, MechanismId(1))

        val order1 = Interpreter.applyFlow(Interpreter.applyFlow(balances, f1), f2)
        val order2 = Interpreter.applyFlow(Interpreter.applyFlow(balances, f2), f1)
        order1 shouldBe order2
      }
    }
  }
