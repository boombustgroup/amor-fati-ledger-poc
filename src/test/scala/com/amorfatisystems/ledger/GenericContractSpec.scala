package com.amorfatisystems.ledger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GenericContractSpec extends AnyFlatSpec with Matchers:
  private val A = AccountId(1)
  private val B = AccountId(2)
  private val X = CurrencyId(10)
  private val Y = CurrencyId(20)
  private val I = InstrumentId(3)
  private val M = MechanismId(4)
  private val P = PeriodId(202507L)

  private def topology(currencyA: CurrencyId = X, currencyB: CurrencyId = X): LedgerTopology =
    LedgerTopology.validate(
      Map(
        A -> AccountMetadata(currencyA, Some(I), minBalance = Some(-100L), maxBalance = Some(100L)),
        B -> AccountMetadata(currencyB, Some(I), minBalance = Some(-100L), maxBalance = Some(100L))
      )
    ).toOption.get

  "LedgerTopology" should "reject invalid balance bounds" in {
    LedgerTopology.validate(Map(A -> AccountMetadata(X, minBalance = Some(10L), maxBalance = Some(1L)))).isLeft shouldBe true
  }

  it should "reject an empty topology" in {
    LedgerTopology.validate(Map.empty).isLeft shouldBe true
  }

  it should "reject unknown accounts" in {
    topology().metadata(AccountId(99)).isLeft shouldBe true
  }

  "TransferExecutor" should "execute a valid transfer with evidence" in {
    val transfer = Transfer(A, B, 25L, M, P)
    val result = TransferExecutor.execute(topology(), Map(A -> 50L, B -> 0L), transfer, 7L)
    result.map(_._1) shouldBe Right(Map(A -> 25L, B -> 25L))
    result.map(_._2.snapshotVersion) shouldBe Right(7L)
    result.map(_._2.debitTotal) shouldBe Right(25L)
  }

  it should "reject currency mismatch, permissions, and bounds" in {
    val transfer = Transfer(A, B, 25L, M, P)
    TransferExecutor.execute(topology(X, Y), Map(A -> 50L, B -> 0L), transfer, 1L).isLeft shouldBe true
    val denied = LedgerTopology.validate(Map(A -> AccountMetadata(X, canDebit = false), B -> AccountMetadata(X))).toOption.get
    TransferExecutor.execute(denied, Map(A -> 50L, B -> 0L), transfer, 1L).isLeft shouldBe true
    TransferExecutor.execute(topology(), Map(A -> 100L, B -> 0L), Transfer(A, B, 150L, M, P), 1L).isLeft shouldBe true
  }

  it should "reject source and target Long overflow" in {
    val wide = LedgerTopology.validate(Map(A -> AccountMetadata(X), B -> AccountMetadata(X))).toOption.get
    TransferExecutor.execute(wide, Map(A -> Long.MinValue, B -> 0L), Transfer(A, B, 1L, M, P), 1L).isLeft shouldBe true
    TransferExecutor.execute(wide, Map(A -> 0L, B -> Long.MaxValue), Transfer(A, B, 1L, M, P), 1L).isLeft shouldBe true
  }

  it should "execute sequences atomically and preserve evidence totals" in {
    val transfers = Vector(Transfer(A, B, 10L, M, P), Transfer(B, A, 5L, M, P))
    val result = TransferExecutor.executeSequence(topology(), Map(A -> 50L, B -> 0L), transfers, 9L)
    result.map(_._1) shouldBe Right(Map(A -> 45L, B -> 5L))
    result.map(_._2.debitTotal) shouldBe Right(15L)
    val initial = Map(A -> 50L, B -> 0L)
    TransferExecutor.executeSequence(topology(), initial, Vector(Transfer(A, B, 150L, M, P), Transfer(B, A, 1L, M, P)), 9L).isLeft shouldBe true
    initial shouldBe Map(A -> 50L, B -> 0L)
  }

  it should "reject evidence total overflow without partial result" in {
    val wide = LedgerTopology.validate(Map(A -> AccountMetadata(X), B -> AccountMetadata(X))).toOption.get
    val transfers = Vector(Transfer(A, B, Long.MaxValue, M, P), Transfer(B, A, Long.MaxValue, M, P))
    TransferExecutor.executeSequence(wide, Map(A -> Long.MaxValue, B -> 0L), transfers, 9L).isLeft shouldBe true
  }

  it should "match the pure reference interpreter for a valid transfer" in {
    val transfer = Transfer(A, B, 25L, M, P)
    val balances = Map(A -> 50L, B -> 0L)
    val executed = TransferExecutor.execute(topology(), balances, transfer, 3L).map(_._1)
    val reference = Interpreter.applyFlow(balances, Flow(A, B, 25L, M))
    executed shouldBe Right(reference)
  }

  "AccountLifecycle" should "create and close accounts with initial balances" in {
    val base = topology()
    val C = AccountId(3)
    val created = AccountLifecycle.createWithInitialBalance(base, Map.empty, C, AccountMetadata(X), 10L)
    created.map(_._2(C)) shouldBe Right(10L)
    AccountLifecycle.create(base, A, AccountMetadata(X)).isLeft shouldBe true
    AccountLifecycle.close(base, Map.empty, AccountId(99)).isLeft shouldBe true
    AccountLifecycle.close(created.toOption.get._1, Map(C -> 10L), C).isLeft shouldBe true
    AccountLifecycle.close(created.toOption.get._1, Map(C -> 0L), C).isRight shouldBe true
  }
