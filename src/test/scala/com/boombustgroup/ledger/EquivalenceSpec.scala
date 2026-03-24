package com.boombustgroup.ledger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.scalacheck.Gen

/** Equivalence test: pure Map-based interpreter == imperative Array-based interpreter.
  *
  * If these produce identical results bit-for-bit (Long), the imperative shell "inherits" the formal proof from Verified.scala
  * (Stainless/Z3). This is the bridge between mathematical certainty and production performance.
  */
class EquivalenceSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks:

  private val NumHH    = 20
  private val NumBanks = 5
  private val HH       = EntitySector.Households
  private val Banks    = EntitySector.Banks
  private val Funds    = EntitySector.Funds
  private val Asset    = AssetType.DemandDeposit

  // --- Scatter (N:M) tests ---

  private val genScatterFlow = for
    amounts <- Gen.listOfN(NumHH, Gen.choose(0L, 1000000L)).map(_.toArray)
    targets <- Gen.listOfN(NumHH, Gen.choose(0, NumBanks - 1)).map(_.toArray)
  yield BatchedFlow.Scatter(HH, Banks, amounts, targets, Asset, Mechanism.HhConsumption)

  private def scatterToFlows(batch: BatchedFlow.Scatter, fromOff: Int, toOff: Int): Vector[Flow] =
    batch.amounts.indices.flatMap { i =>
      val amount = batch.amounts(i)
      val fromId = fromOff + i
      val toId   = toOff + batch.targetIndices(i)
      if amount != 0L && fromId != toId then Some(Flow(fromId, toId, amount, batch.mechanism.ordinal))
      else None
    }.toVector

  "Scatter" should "produce identical results to pure Interpreter" in {
    forAll(genScatterFlow) { batch =>
      val flows      = scatterToFlows(batch, 0, NumHH)
      val pureResult = Interpreter.applyAll(Map.empty[Int, Long], flows)

      val state = new MutableWorldState(Map(HH -> NumHH, Banks -> NumBanks))
      ImperativeInterpreter.applyBatch(state, batch)

      (0 until NumHH).foreach(i => state.balance(HH, Asset, i) shouldBe pureResult.getOrElse(i, 0L))
      (0 until NumBanks).foreach(i => state.balance(Banks, Asset, i) shouldBe pureResult.getOrElse(NumHH + i, 0L))
    }
  }

  it should "preserve total wealth" in {
    forAll(genScatterFlow) { batch =>
      val state   = new MutableWorldState(Map(HH -> NumHH, Banks -> NumBanks))
      val fromArr = state.getBalances(HH, Asset)
      (0 until NumHH).foreach(i => fromArr(i) = 1000000L)
      val before = state.totalForAsset(Asset)

      ImperativeInterpreter.applyBatch(state, batch)

      state.totalForAsset(Asset) shouldBe before
    }
  }

  it should "produce identical results for multiple sequential batches" in {
    val genBatches = Gen.listOfN(5, genScatterFlow).map(_.toVector)
    forAll(genBatches) { batches =>
      val allFlows   = batches.flatMap(b => scatterToFlows(b, 0, NumHH))
      val pureResult = Interpreter.applyAll(Map.empty[Int, Long], allFlows)

      val state = new MutableWorldState(Map(HH -> NumHH, Banks -> NumBanks))
      ImperativeInterpreter.applyAll(state, batches)

      (0 until NumHH).foreach(i => state.balance(HH, Asset, i) shouldBe pureResult.getOrElse(i, 0L))
      (0 until NumBanks).foreach(i => state.balance(Banks, Asset, i) shouldBe pureResult.getOrElse(NumHH + i, 0L))
    }
  }

  // --- Broadcast (1:N) tests ---

  private val NumFunds = 7
  private val ZusIndex = 0
  private val FundsOff = NumHH + NumBanks // offset in flat ID space

  private val genBroadcastFlow = for
    amounts <- Gen.listOfN(NumHH, Gen.choose(0L, 100000L)).map(_.toArray)
    targets = (0 until NumHH).toArray // identity: each HH gets their own amount
  yield BatchedFlow.Broadcast(Funds, ZusIndex, HH, amounts, targets, Asset, Mechanism.ZusPension)

  private def broadcastToFlows(batch: BatchedFlow.Broadcast, fromOff: Int, toOff: Int): Vector[Flow] =
    batch.amounts.indices.flatMap { i =>
      val amount = batch.amounts(i)
      val fromId = fromOff + batch.fromIndex
      val toId   = toOff + batch.targetIndices(i)
      if amount != 0L && fromId != toId then Some(Flow(fromId, toId, amount, batch.mechanism.ordinal))
      else None
    }.toVector

  "Broadcast" should "produce identical results to pure Interpreter" in {
    forAll(genBroadcastFlow) { batch =>
      val flows      = broadcastToFlows(batch, FundsOff, 0)
      val pureResult = Interpreter.applyAll(Map.empty[Int, Long], flows)

      val state = new MutableWorldState(Map(HH -> NumHH, Funds -> NumFunds))
      ImperativeInterpreter.applyBatch(state, batch)

      (0 until NumHH).foreach(i => state.balance(HH, Asset, i) shouldBe pureResult.getOrElse(i, 0L))
      state.balance(Funds, Asset, ZusIndex) shouldBe pureResult.getOrElse(FundsOff + ZusIndex, 0L)
    }
  }

  it should "preserve total wealth" in {
    forAll(genBroadcastFlow) { batch =>
      val state  = new MutableWorldState(Map(HH -> NumHH, Funds -> NumFunds))
      val zusArr = state.getBalances(Funds, Asset)
      zusArr(ZusIndex) = 100000000L // ZUS has 10K PLN budget
      val before = state.totalForAsset(Asset)

      ImperativeInterpreter.applyBatch(state, batch)

      state.totalForAsset(Asset) shouldBe before
    }
  }

  it should "debit sender exactly once (totalDebit aggregation)" in {
    val amounts = Array(10000L, 20000L, 30000L)
    val targets = Array(0, 1, 2)
    val batch   = BatchedFlow.Broadcast(Funds, ZusIndex, HH, amounts, targets, Asset, Mechanism.ZusPension)

    val state = new MutableWorldState(Map(HH -> 3, Funds -> NumFunds))
    ImperativeInterpreter.applyBatch(state, batch)

    state.balance(Funds, Asset, ZusIndex) shouldBe -60000L
    state.balance(HH, Asset, 0) shouldBe 10000L
    state.balance(HH, Asset, 1) shouldBe 20000L
    state.balance(HH, Asset, 2) shouldBe 30000L
  }
