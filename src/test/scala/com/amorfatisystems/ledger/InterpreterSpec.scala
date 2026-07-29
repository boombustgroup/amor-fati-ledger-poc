package com.amorfatisystems.ledger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class InterpreterSpec extends AnyFlatSpec with Matchers:

  "applyFlow" should "preserve total wealth" in {
    val balances = Map[AccountId, Long](AccountId(0) -> 100000L, AccountId(1) -> 50000L, AccountId(2) -> 30000L)
    val flow     = Flow(from = AccountId(0), to = AccountId(1), amount = 25000L, mechanism = MechanismId(0))
    val result   = Interpreter.applyFlow(balances, flow)

    Interpreter.totalWealth(result) shouldBe Interpreter.totalWealth(balances)
  }

  it should "debit from and credit to" in {
    val balances = Map[AccountId, Long](AccountId(0) -> 100000L, AccountId(1) -> 50000L)
    val flow     = Flow(from = AccountId(0), to = AccountId(1), amount = 25000L, mechanism = MechanismId(0))
    val result   = Interpreter.applyFlow(balances, flow)

    result(AccountId(0)) shouldBe 75000L
    result(AccountId(1)) shouldBe 75000L
  }

  it should "not affect other accounts (frame condition)" in {
    val balances = Map[AccountId, Long](AccountId(0) -> 100000L, AccountId(1) -> 50000L, AccountId(2) -> 30000L)
    val flow     = Flow(from = AccountId(0), to = AccountId(1), amount = 10000L, mechanism = MechanismId(0))
    val result   = Interpreter.applyFlow(balances, flow)

    result(AccountId(2)) shouldBe 30000L
  }

  it should "handle missing accounts (default to zero)" in {
    val balances = Map.empty[AccountId, Long]
    val flow     = Flow(from = AccountId(0), to = AccountId(1), amount = 10000L, mechanism = MechanismId(0))
    val result   = Interpreter.applyFlow(balances, flow)

    result(AccountId(0)) shouldBe -10000L
    result(AccountId(1)) shouldBe 10000L
    Interpreter.totalWealth(result) shouldBe 0L
  }

  "applyAll" should "preserve total wealth across multiple flows" in {
    val balances = Map[AccountId, Long](AccountId(0) -> 1000000L, AccountId(1) -> 500000L, AccountId(2) -> 300000L)
    val flows = Vector(
      Flow(AccountId(0), AccountId(1), 100000L, MechanismId(0)),
      Flow(AccountId(1), AccountId(2), 50000L, MechanismId(1)),
      Flow(AccountId(2), AccountId(0), 25000L, MechanismId(2))
    )
    val result = Interpreter.applyAll(balances, flows)

    Interpreter.totalWealth(result) shouldBe Interpreter.totalWealth(balances)
  }

  "canApplyFlow" should "reject debits that would underflow Long" in {
    val balances = Map[AccountId, Long](AccountId(0) -> Long.MinValue, AccountId(1) -> 0L)
    val flow     = Flow(from = AccountId(0), to = AccountId(1), amount = 1L, mechanism = MechanismId(0))

    Interpreter.canApplyFlow(balances, flow) shouldBe false
  }

  it should "reject credits that would overflow Long" in {
    val balances = Map[AccountId, Long](AccountId(0) -> 0L, AccountId(1) -> Long.MaxValue)
    val flow     = Flow(from = AccountId(0), to = AccountId(1), amount = 1L, mechanism = MechanismId(0))

    Interpreter.canApplyFlow(balances, flow) shouldBe false
  }

  "applyCheckedFlow" should "return a Left instead of overflowing runtime Long bounds" in {
    val balances = Map[AccountId, Long](AccountId(0) -> Long.MinValue, AccountId(1) -> 0L)
    val flow     = Flow(from = AccountId(0), to = AccountId(1), amount = 1L, mechanism = MechanismId(0))

    Interpreter.applyCheckedFlow(balances, flow).isLeft shouldBe true
  }

  "applyCheckedAll" should "stop on the first overflow-unsafe step in a flow sequence" in {
    val balances = Map[AccountId, Long](AccountId(0) -> Long.MinValue, AccountId(1) -> 0L, AccountId(2) -> 0L)
    val flows = Vector(
      Flow(AccountId(1), AccountId(2), 1L, MechanismId(0)),
      Flow(AccountId(0), AccountId(1), 1L, MechanismId(1))
    )

    Interpreter.applyCheckedAll(balances, flows).isLeft shouldBe true
  }

  "Flow" should "reject self-transfers" in {
    an[IllegalArgumentException] should be thrownBy {
      Flow(from = AccountId(0), to = AccountId(0), amount = 100L, mechanism = MechanismId(0))
    }
  }

  it should "reject negative amounts" in {
    an[IllegalArgumentException] should be thrownBy {
      Flow(from = AccountId(0), to = AccountId(1), amount = -100L, mechanism = MechanismId(0))
    }
  }
