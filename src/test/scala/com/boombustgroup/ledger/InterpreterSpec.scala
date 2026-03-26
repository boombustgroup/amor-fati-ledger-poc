package com.boombustgroup.ledger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class InterpreterSpec extends AnyFlatSpec with Matchers:

  "applyFlow" should "preserve total wealth" in {
    val balances = Map(0 -> 100000L, 1 -> 50000L, 2 -> 30000L)
    val flow     = Flow(from = 0, to = 1, amount = 25000L, mechanism = 0)
    val result   = Interpreter.applyFlow(balances, flow)

    Interpreter.totalWealth(result) shouldBe Interpreter.totalWealth(balances)
  }

  it should "debit from and credit to" in {
    val balances = Map(0 -> 100000L, 1 -> 50000L)
    val flow     = Flow(from = 0, to = 1, amount = 25000L, mechanism = 0)
    val result   = Interpreter.applyFlow(balances, flow)

    result(0) shouldBe 75000L
    result(1) shouldBe 75000L
  }

  it should "not affect other accounts (frame condition)" in {
    val balances = Map(0 -> 100000L, 1 -> 50000L, 2 -> 30000L)
    val flow     = Flow(from = 0, to = 1, amount = 10000L, mechanism = 0)
    val result   = Interpreter.applyFlow(balances, flow)

    result(2) shouldBe 30000L
  }

  it should "handle missing accounts (default to zero)" in {
    val balances = Map.empty[Int, Long]
    val flow     = Flow(from = 0, to = 1, amount = 10000L, mechanism = 0)
    val result   = Interpreter.applyFlow(balances, flow)

    result(0) shouldBe -10000L
    result(1) shouldBe 10000L
    Interpreter.totalWealth(result) shouldBe 0L
  }

  "applyAll" should "preserve total wealth across multiple flows" in {
    val balances = Map(0 -> 1000000L, 1 -> 500000L, 2 -> 300000L)
    val flows = Vector(
      Flow(0, 1, 100000L, 0),
      Flow(1, 2, 50000L, 1),
      Flow(2, 0, 25000L, 2)
    )
    val result = Interpreter.applyAll(balances, flows)

    Interpreter.totalWealth(result) shouldBe Interpreter.totalWealth(balances)
  }

  "canApplyFlow" should "reject debits that would underflow Long" in {
    val balances = Map(0 -> Long.MinValue, 1 -> 0L)
    val flow     = Flow(from = 0, to = 1, amount = 1L, mechanism = 0)

    Interpreter.canApplyFlow(balances, flow) shouldBe false
  }

  it should "reject credits that would overflow Long" in {
    val balances = Map(0 -> 0L, 1 -> Long.MaxValue)
    val flow     = Flow(from = 0, to = 1, amount = 1L, mechanism = 0)

    Interpreter.canApplyFlow(balances, flow) shouldBe false
  }

  "applyCheckedFlow" should "return a Left instead of overflowing runtime Long bounds" in {
    val balances = Map(0 -> Long.MinValue, 1 -> 0L)
    val flow     = Flow(from = 0, to = 1, amount = 1L, mechanism = 0)

    Interpreter.applyCheckedFlow(balances, flow).isLeft shouldBe true
  }

  "applyCheckedAll" should "stop on the first overflow-unsafe step in a flow sequence" in {
    val balances = Map(0 -> Long.MinValue, 1 -> 0L, 2 -> 0L)
    val flows = Vector(
      Flow(1, 2, 1L, 0),
      Flow(0, 1, 1L, 1)
    )

    Interpreter.applyCheckedAll(balances, flows).isLeft shouldBe true
  }

  "Flow" should "reject self-transfers" in {
    an[IllegalArgumentException] should be thrownBy {
      Flow(from = 0, to = 0, amount = 100L, mechanism = 0)
    }
  }

  it should "reject negative amounts" in {
    an[IllegalArgumentException] should be thrownBy {
      Flow(from = 0, to = 1, amount = -100L, mechanism = 0)
    }
  }
