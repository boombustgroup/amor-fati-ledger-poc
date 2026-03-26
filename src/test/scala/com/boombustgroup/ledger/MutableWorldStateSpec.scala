package com.boombustgroup.ledger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MutableWorldStateSpec extends AnyFlatSpec with Matchers:

  private val HH    = EntitySector.Households
  private val Banks = EntitySector.Banks
  private val Asset = AssetType.DemandDeposit
  private val Loan  = AssetType.FirmLoan

  "MutableWorldState" should "reuse the same backing array for the same sector and asset" in {
    val state  = new MutableWorldState(Map(HH -> 3, Banks -> 2))
    val first  = state.getBalances(HH, Asset)
    val second = state.getBalances(HH, Asset)

    state.setBalance(HH, Asset, 0, 123L) shouldBe Right(())

    second should be theSameInstanceAs first
    state.balance(HH, Asset, 0) shouldBe 123L
  }

  it should "keep separate backing arrays for different sector or asset keys" in {
    val state       = new MutableWorldState(Map(HH -> 3, Banks -> 2))
    val hhDeposits  = state.getBalances(HH, Asset)
    val bankDeposit = state.getBalances(Banks, Asset)
    val hhLoans     = state.getBalances(HH, Loan)

    state.setBalance(HH, Asset, 0, 10L) shouldBe Right(())
    state.setBalance(Banks, Asset, 0, 20L) shouldBe Right(())
    state.setBalance(HH, Loan, 0, 30L) shouldBe Right(())

    hhDeposits should not be theSameInstanceAs(bankDeposit)
    hhDeposits should not be theSameInstanceAs(hhLoans)
    state.balance(HH, Asset, 0) shouldBe 10L
    state.balance(Banks, Asset, 0) shouldBe 20L
    state.balance(HH, Loan, 0) shouldBe 30L
  }

  it should "return zero for missing balances" in {
    val state = new MutableWorldState(Map(HH -> 3))

    state.balance(HH, Asset, 0) shouldBe 0L
  }

  it should "return None for invalid balanceOption lookups" in {
    val state = new MutableWorldState(Map(HH -> 3))

    state.balanceOption(HH, Asset, 99) shouldBe None
  }

  it should "omit zero entries from snapshots" in {
    val state = new MutableWorldState(Map(HH -> 3, Banks -> 2))
    state.setBalance(HH, Asset, 0, 11L) shouldBe Right(())
    state.setBalance(HH, Asset, 1, 0L) shouldBe Right(())
    state.setBalance(Banks, Asset, 1, -11L) shouldBe Right(())

    state.snapshot shouldBe Map(
      (HH, Asset, 0)    -> 11L,
      (Banks, Asset, 1) -> -11L
    )
  }

  it should "sum totals only for the requested asset type" in {
    val state = new MutableWorldState(Map(HH -> 3, Banks -> 2))
    state.setBalance(HH, Asset, 0, 100L) shouldBe Right(())
    state.setBalance(HH, Asset, 1, -40L) shouldBe Right(())
    state.setBalance(Banks, Asset, 0, -60L) shouldBe Right(())
    state.setBalance(HH, Loan, 0, 999L) shouldBe Right(())

    state.totalForAsset(Asset) shouldBe 0L
    state.totalForAsset(Loan) shouldBe 999L
  }

  it should "default unknown sector sizes to 1" in {
    val state = new MutableWorldState(Map(HH -> 3))

    state.sectorSize(Banks) shouldBe 1
    state.getBalances(Banks, Asset).length shouldBe 1
  }

  it should "reject out-of-bounds writes through setBalance" in {
    val state = new MutableWorldState(Map(HH -> 3))

    state.setBalance(HH, Asset, 3, 1L).isLeft shouldBe true
    state.balanceOption(HH, Asset, 3) shouldBe None
  }

  it should "apply checked delta updates through adjustBalance" in {
    val state = new MutableWorldState(Map(HH -> 3))

    state.setBalance(HH, Asset, 0, 10L) shouldBe Right(())
    state.adjustBalance(HH, Asset, 0, 5L) shouldBe Right(())
    state.adjustBalance(HH, Asset, 0, -3L) shouldBe Right(())

    state.balance(HH, Asset, 0) shouldBe 12L
  }

  it should "reject out-of-bounds delta updates through adjustBalance" in {
    val state = new MutableWorldState(Map(HH -> 3))

    state.adjustBalance(HH, Asset, 9, 1L).isLeft shouldBe true
  }

  it should "reject overflow and underflow in adjustBalance" in {
    val state = new MutableWorldState(Map(HH -> 3))

    state.setBalance(HH, Asset, 0, Long.MaxValue) shouldBe Right(())
    state.adjustBalance(HH, Asset, 0, 1L).isLeft shouldBe true

    state.setBalance(HH, Asset, 1, Long.MinValue) shouldBe Right(())
    state.adjustBalance(HH, Asset, 1, -1L).isLeft shouldBe true
  }
