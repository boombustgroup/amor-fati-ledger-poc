package com.amorfatisystems.ledger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MutableWorldStateSpec extends AnyFlatSpec with Matchers:

  private val GroupA    = AccountPartitionId(1)
  private val GroupB = AccountPartitionId(3)
  private val Asset = InstrumentId(1)
  private val Loan  = InstrumentId(3)

  "MutableWorldState" should "reuse the same backing array for the same partition and asset" in {
    val state  = new MutableWorldState(Map(GroupA -> 3, GroupB -> 2))
    val first  = state.getBalances(GroupA, Asset)
    val second = state.getBalances(GroupA, Asset)

    state.setBalance(GroupA, Asset, 0, 123L) shouldBe Right(())

    second should be theSameInstanceAs first
    state.balance(GroupA, Asset, 0) shouldBe 123L
  }

  it should "keep separate backing arrays for different partition or asset keys" in {
    val state       = new MutableWorldState(Map(GroupA -> 3, GroupB -> 2))
    val hhDeposits  = state.getBalances(GroupA, Asset)
    val bankDeposit = state.getBalances(GroupB, Asset)
    val hhLoans     = state.getBalances(GroupA, Loan)

    state.setBalance(GroupA, Asset, 0, 10L) shouldBe Right(())
    state.setBalance(GroupB, Asset, 0, 20L) shouldBe Right(())
    state.setBalance(GroupA, Loan, 0, 30L) shouldBe Right(())

    hhDeposits should not be theSameInstanceAs(bankDeposit)
    hhDeposits should not be theSameInstanceAs(hhLoans)
    state.balance(GroupA, Asset, 0) shouldBe 10L
    state.balance(GroupB, Asset, 0) shouldBe 20L
    state.balance(GroupA, Loan, 0) shouldBe 30L
  }

  it should "return zero for missing balances" in {
    val state = new MutableWorldState(Map(GroupA -> 3))

    state.balance(GroupA, Asset, 0) shouldBe 0L
  }

  it should "return None for invalid balanceOption lookups" in {
    val state = new MutableWorldState(Map(GroupA -> 3))

    state.balanceOption(GroupA, Asset, 99) shouldBe None
  }

  it should "omit zero entries from snapshots" in {
    val state = new MutableWorldState(Map(GroupA -> 3, GroupB -> 2))
    state.setBalance(GroupA, Asset, 0, 11L) shouldBe Right(())
    state.setBalance(GroupA, Asset, 1, 0L) shouldBe Right(())
    state.setBalance(GroupB, Asset, 1, -11L) shouldBe Right(())

    state.snapshot shouldBe Map(
      (GroupA, Asset, 0)    -> 11L,
      (GroupB, Asset, 1) -> -11L
    )
  }

  it should "sum totals only for the requested asset type" in {
    val state = new MutableWorldState(Map(GroupA -> 3, GroupB -> 2))
    state.setBalance(GroupA, Asset, 0, 100L) shouldBe Right(())
    state.setBalance(GroupA, Asset, 1, -40L) shouldBe Right(())
    state.setBalance(GroupB, Asset, 0, -60L) shouldBe Right(())
    state.setBalance(GroupA, Loan, 0, 999L) shouldBe Right(())

    state.totalForAsset(Asset) shouldBe 0L
    state.totalForAsset(Loan) shouldBe 999L
  }

  it should "reject unknown partition sizes instead of inventing a slot" in {
    val state = new MutableWorldState(Map(GroupA -> 3))

    state.partitionSize(GroupB) shouldBe 0
    state.getBalances(GroupB, Asset).length shouldBe 0
  }

  it should "reject out-of-bounds writes through setBalance" in {
    val state = new MutableWorldState(Map(GroupA -> 3))

    state.setBalance(GroupA, Asset, 3, 1L).isLeft shouldBe true
    state.balanceOption(GroupA, Asset, 3) shouldBe None
  }

  it should "apply checked delta updates through adjustBalance" in {
    val state = new MutableWorldState(Map(GroupA -> 3))

    state.setBalance(GroupA, Asset, 0, 10L) shouldBe Right(())
    state.adjustBalance(GroupA, Asset, 0, 5L) shouldBe Right(())
    state.adjustBalance(GroupA, Asset, 0, -3L) shouldBe Right(())

    state.balance(GroupA, Asset, 0) shouldBe 12L
  }

  it should "reject out-of-bounds delta updates through adjustBalance" in {
    val state = new MutableWorldState(Map(GroupA -> 3))

    state.adjustBalance(GroupA, Asset, 9, 1L).isLeft shouldBe true
  }

  it should "reject overflow and underflow in adjustBalance" in {
    val state = new MutableWorldState(Map(GroupA -> 3))

    state.setBalance(GroupA, Asset, 0, Long.MaxValue) shouldBe Right(())
    state.adjustBalance(GroupA, Asset, 0, 1L).isLeft shouldBe true

    state.setBalance(GroupA, Asset, 1, Long.MinValue) shouldBe Right(())
    state.adjustBalance(GroupA, Asset, 1, -1L).isLeft shouldBe true
  }
