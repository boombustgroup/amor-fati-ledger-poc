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

  private val NumHH          = 20
  private val NumBanks       = 5
  private val HH             = EntitySector.Households
  private val Banks          = EntitySector.Banks
  private val Funds          = EntitySector.Funds
  private val Asset          = AssetType.DemandDeposit
  private val HhBanksSizes   = Map(HH -> NumHH, Banks -> NumBanks)
  private val HhBanksOffsets = Map(HH -> 0, Banks -> NumHH)

  // --- Scatter (N:M) tests ---

  private val genScatterFlow = for
    amounts <- Gen.listOfN(NumHH, Gen.choose(0L, 1000000L)).map(_.toArray)
    targets <- Gen.listOfN(NumHH, Gen.choose(0, NumBanks - 1)).map(_.toArray)
  yield BatchedFlow.Scatter(HH, Banks, amounts, targets, Asset, MechanismId(1))

  private def scatterToFlows(batch: BatchedFlow.Scatter, fromOff: Int, toOff: Int): Vector[Flow] =
    batch.amounts.indices.flatMap { i =>
      val amount = batch.amounts(i)
      val fromId = fromOff + i
      val toId   = toOff + batch.targetIndices(i)
      if amount != 0L && fromId != toId then Some(Flow(fromId, toId, amount, batch.mechanism.toInt))
      else None
    }.toVector

  "Scatter" should "produce identical results to pure Interpreter" in {
    forAll(genScatterFlow) { batch =>
      val flows      = scatterToFlows(batch, 0, NumHH)
      val pureResult = Interpreter.applyAll(Map.empty[Int, Long], flows)
      val refResult  = RuntimeInterpreterReference.applyBatch(HhBanksSizes, Map.empty, batch)
      val refFlat    = RuntimeInterpreterReference.snapshotToFlatMap(refResult, HhBanksOffsets, Asset)

      val state = new MutableWorldState(HhBanksSizes)
      ImperativeInterpreter.applyBatch(state, batch)

      state.snapshot shouldBe refResult
      refFlat shouldBe pureResult
      (0 until NumHH).foreach(i => state.balance(HH, Asset, i) shouldBe pureResult.getOrElse(i, 0L))
      (0 until NumBanks).foreach(i => state.balance(Banks, Asset, i) shouldBe pureResult.getOrElse(NumHH + i, 0L))
    }
  }

  it should "preserve total wealth" in {
    forAll(genScatterFlow) { batch =>
      val state = new MutableWorldState(Map(HH -> NumHH, Banks -> NumBanks))
      (0 until NumHH).foreach(i => state.setBalance(HH, Asset, i, 1000000L) shouldBe Right(()))
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
      val refResult  = RuntimeInterpreterReference.applyAll(HhBanksSizes, Map.empty, batches)
      val refFlat    = RuntimeInterpreterReference.snapshotToFlatMap(refResult, HhBanksOffsets, Asset)

      val state = new MutableWorldState(HhBanksSizes)
      ImperativeInterpreter.applyAll(state, batches)

      state.snapshot shouldBe refResult
      refFlat shouldBe pureResult
      (0 until NumHH).foreach(i => state.balance(HH, Asset, i) shouldBe pureResult.getOrElse(i, 0L))
      (0 until NumBanks).foreach(i => state.balance(Banks, Asset, i) shouldBe pureResult.getOrElse(NumHH + i, 0L))
    }
  }

  it should "reject scatter batches with invalid sender dimension" in {
    val batch = BatchedFlow.Scatter(
      HH,
      Banks,
      Array.fill(NumHH - 1)(100L),
      Array.fill(NumHH - 1)(0),
      Asset,
      MechanismId(1)
    )

    val state = new MutableWorldState(Map(HH -> NumHH, Banks -> NumBanks))
    an[IllegalArgumentException] should be thrownBy ImperativeInterpreter.applyBatch(state, batch)
  }

  it should "reject scatter batches with out-of-bounds target indices" in {
    val targets = Array.fill(NumHH)(0)
    targets(NumHH - 1) = NumBanks
    val batch = BatchedFlow.Scatter(
      HH,
      Banks,
      Array.fill(NumHH)(100L),
      targets,
      Asset,
      MechanismId(1)
    )

    val state = new MutableWorldState(Map(HH -> NumHH, Banks -> NumBanks))
    an[IllegalArgumentException] should be thrownBy ImperativeInterpreter.applyBatch(state, batch)
  }

  it should "reject scatter batches that would underflow the sender balance" in {
    val amounts = Array.fill(NumHH)(0L)
    val targets = Array.fill(NumHH)(0)
    amounts(0) = 1L
    val batch = BatchedFlow.Scatter(
      HH,
      Banks,
      amounts,
      targets,
      Asset,
      MechanismId(1)
    )

    val state = new MutableWorldState(Map(HH -> NumHH, Banks -> NumBanks))
    state.setBalance(HH, Asset, 0, Long.MinValue) shouldBe Right(())

    val refState = Map((HH, Asset, 0) -> Long.MinValue)
    ImperativeInterpreter.canApplyBatch(state, batch) shouldBe false
    RuntimeInterpreterReference.canApplyBatch(HhBanksSizes, refState, batch) shouldBe false
    ImperativeInterpreter.applyCheckedBatch(state, batch).isLeft shouldBe true
    an[IllegalArgumentException] should be thrownBy ImperativeInterpreter.applyBatch(state, batch)
  }

  it should "reject scatter batches that would overflow the target balance" in {
    val amounts = Array.fill(NumHH)(0L)
    val targets = Array.fill(NumHH)(0)
    amounts(0) = 1L
    val batch = BatchedFlow.Scatter(
      HH,
      Banks,
      amounts,
      targets,
      Asset,
      MechanismId(1)
    )

    val state = new MutableWorldState(Map(HH -> NumHH, Banks -> NumBanks))
    state.setBalance(Banks, Asset, 0, Long.MaxValue) shouldBe Right(())

    val refState = Map((Banks, Asset, 0) -> Long.MaxValue)
    ImperativeInterpreter.canApplyBatch(state, batch) shouldBe false
    RuntimeInterpreterReference.canApplyBatch(HhBanksSizes, refState, batch) shouldBe false
    ImperativeInterpreter.applyCheckedBatch(state, batch).isLeft shouldBe true
    an[IllegalArgumentException] should be thrownBy ImperativeInterpreter.applyBatch(state, batch)
  }

  // --- Broadcast (1:N) tests ---

  private val NumFunds       = 7
  private val ZusIndex       = 0
  private val FundsOff       = NumHH + NumBanks // offset in flat ID space
  private val HhFundsSizes   = Map(HH -> NumHH, Funds -> NumFunds)
  private val HhFundsOffsets = Map(HH -> 0, Funds -> FundsOff)

  private val genBroadcastFlow = for
    amounts <- Gen.listOfN(NumHH, Gen.choose(0L, 100000L)).map(_.toArray)
    targets = (0 until NumHH).toArray // identity: each HH gets their own amount
  yield BatchedFlow.Broadcast(Funds, ZusIndex, HH, amounts, targets, Asset, MechanismId(2))

  private def broadcastToFlows(batch: BatchedFlow.Broadcast, fromOff: Int, toOff: Int): Vector[Flow] =
    batch.amounts.indices.flatMap { i =>
      val amount = batch.amounts(i)
      val fromId = fromOff + batch.fromIndex
      val toId   = toOff + batch.targetIndices(i)
      if amount != 0L && fromId != toId then Some(Flow(fromId, toId, amount, batch.mechanism.toInt))
      else None
    }.toVector

  "Broadcast" should "produce identical results to pure Interpreter" in {
    forAll(genBroadcastFlow) { batch =>
      val flows      = broadcastToFlows(batch, FundsOff, 0)
      val pureResult = Interpreter.applyAll(Map.empty[Int, Long], flows)
      val refResult  = RuntimeInterpreterReference.applyBatch(HhFundsSizes, Map.empty, batch)
      val refFlat    = RuntimeInterpreterReference.snapshotToFlatMap(refResult, HhFundsOffsets, Asset)

      val state = new MutableWorldState(HhFundsSizes)
      ImperativeInterpreter.applyBatch(state, batch)

      state.snapshot shouldBe refResult
      refFlat shouldBe pureResult
      (0 until NumHH).foreach(i => state.balance(HH, Asset, i) shouldBe pureResult.getOrElse(i, 0L))
      state.balance(Funds, Asset, ZusIndex) shouldBe pureResult.getOrElse(FundsOff + ZusIndex, 0L)
    }
  }

  it should "preserve total wealth" in {
    forAll(genBroadcastFlow) { batch =>
      val state = new MutableWorldState(Map(HH -> NumHH, Funds -> NumFunds))
      state.setBalance(Funds, Asset, ZusIndex, 100000000L) shouldBe Right(()) // ZUS has 10K PLN budget
      val before = state.totalForAsset(Asset)

      ImperativeInterpreter.applyBatch(state, batch)

      state.totalForAsset(Asset) shouldBe before
    }
  }

  it should "debit sender exactly once (totalDebit aggregation)" in {
    val amounts = Array(10000L, 20000L, 30000L)
    val targets = Array(0, 1, 2)
    val batch   = BatchedFlow.Broadcast(Funds, ZusIndex, HH, amounts, targets, Asset, MechanismId(2))

    val state = new MutableWorldState(Map(HH -> 3, Funds -> NumFunds))
    ImperativeInterpreter.applyBatch(state, batch)

    state.balance(Funds, Asset, ZusIndex) shouldBe -60000L
    state.balance(HH, Asset, 0) shouldBe 10000L
    state.balance(HH, Asset, 1) shouldBe 20000L
    state.balance(HH, Asset, 2) shouldBe 30000L
  }

  it should "produce identical results for multiple sequential broadcast batches" in {
    val genBatches = Gen.listOfN(5, genBroadcastFlow).map(_.toVector)
    forAll(genBatches) { batches =>
      val allFlows   = batches.flatMap(b => broadcastToFlows(b, FundsOff, 0))
      val pureResult = Interpreter.applyAll(Map.empty[Int, Long], allFlows)
      val refResult  = RuntimeInterpreterReference.applyAll(HhFundsSizes, Map.empty, batches)
      val refFlat    = RuntimeInterpreterReference.snapshotToFlatMap(refResult, HhFundsOffsets, Asset)

      val state = new MutableWorldState(HhFundsSizes)
      ImperativeInterpreter.applyAll(state, batches)

      state.snapshot shouldBe refResult
      refFlat shouldBe pureResult
      (0 until NumHH).foreach(i => state.balance(HH, Asset, i) shouldBe pureResult.getOrElse(i, 0L))
      state.balance(Funds, Asset, ZusIndex) shouldBe pureResult.getOrElse(FundsOff + ZusIndex, 0L)
    }
  }

  it should "reject broadcast batches with invalid sender index" in {
    val batch = BatchedFlow.Broadcast(
      Funds,
      NumFunds,
      HH,
      Array.fill(NumHH)(100L),
      (0 until NumHH).toArray,
      Asset,
      MechanismId(2)
    )

    val state = new MutableWorldState(Map(HH -> NumHH, Funds -> NumFunds))
    an[IllegalArgumentException] should be thrownBy ImperativeInterpreter.applyBatch(state, batch)
  }

  it should "reject broadcast batches with out-of-bounds target indices" in {
    val targets = (0 until NumHH).toArray
    targets(0) = NumHH
    val batch = BatchedFlow.Broadcast(
      Funds,
      ZusIndex,
      HH,
      Array.fill(NumHH)(100L),
      targets,
      Asset,
      MechanismId(2)
    )

    val state = new MutableWorldState(Map(HH -> NumHH, Funds -> NumFunds))
    an[IllegalArgumentException] should be thrownBy ImperativeInterpreter.applyBatch(state, batch)
  }

  it should "reject broadcast batches whose aggregated debit would overflow Long" in {
    val batch = BatchedFlow.Broadcast(
      Funds,
      ZusIndex,
      HH,
      Array(Long.MaxValue, 1L),
      Array(0, 1),
      Asset,
      MechanismId(2)
    )

    val state    = new MutableWorldState(Map(HH -> NumHH, Funds -> NumFunds))
    val refState = Map.empty[(EntitySector, AssetType, Int), Long]

    ImperativeInterpreter.canApplyBatch(state, batch) shouldBe false
    RuntimeInterpreterReference.canApplyBatch(HhFundsSizes, refState, batch) shouldBe false
    ImperativeInterpreter.applyCheckedBatch(state, batch).isLeft shouldBe true
    an[IllegalArgumentException] should be thrownBy ImperativeInterpreter.applyBatch(state, batch)
  }
