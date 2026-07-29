package com.amorfatisystems.ledger

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

  private val NumGroupA          = 20
  private val NumGroupB       = 5
  private val GroupA             = AccountPartitionId(1)
  private val GroupB          = AccountPartitionId(3)
  private val GroupC          = AccountPartitionId(7)
  private val Asset          = InstrumentId(1)
  private val HhGroupBSizes   = Map(GroupA -> NumGroupA, GroupB -> NumGroupB)
  private val HhGroupBOffsets = Map(GroupA -> 0, GroupB -> NumGroupA)

  // --- Scatter (N:M) tests ---

  private val genScatterFlow = for
    amounts <- Gen.listOfN(NumGroupA, Gen.choose(0L, 1000000L)).map(_.toArray)
    targets <- Gen.listOfN(NumGroupA, Gen.choose(0, NumGroupB - 1)).map(_.toArray)
  yield BatchedFlow.Scatter(GroupA, GroupB, amounts, targets, Asset, MechanismId(1))

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
      val flows      = scatterToFlows(batch, 0, NumGroupA)
      val pureResult = Interpreter.applyAll(Map.empty[Int, Long], flows)
      val refResult  = RuntimeInterpreterReference.applyBatch(HhGroupBSizes, Map.empty, batch)
      val refFlat    = RuntimeInterpreterReference.snapshotToFlatMap(refResult, HhGroupBOffsets, Asset)

      val state = new MutableWorldState(HhGroupBSizes)
      ImperativeInterpreter.applyBatch(state, batch)

      state.snapshot shouldBe refResult
      refFlat shouldBe pureResult
      (0 until NumGroupA).foreach(i => state.balance(GroupA, Asset, i) shouldBe pureResult.getOrElse(i, 0L))
      (0 until NumGroupB).foreach(i => state.balance(GroupB, Asset, i) shouldBe pureResult.getOrElse(NumGroupA + i, 0L))
    }
  }

  it should "preserve total wealth" in {
    forAll(genScatterFlow) { batch =>
      val state = new MutableWorldState(Map(GroupA -> NumGroupA, GroupB -> NumGroupB))
      (0 until NumGroupA).foreach(i => state.setBalance(GroupA, Asset, i, 1000000L) shouldBe Right(()))
      val before = state.totalForAsset(Asset)

      ImperativeInterpreter.applyBatch(state, batch)

      state.totalForAsset(Asset) shouldBe before
    }
  }

  it should "produce identical results for multiple sequential batches" in {
    val genBatches = Gen.listOfN(5, genScatterFlow).map(_.toVector)
    forAll(genBatches) { batches =>
      val allFlows   = batches.flatMap(b => scatterToFlows(b, 0, NumGroupA))
      val pureResult = Interpreter.applyAll(Map.empty[Int, Long], allFlows)
      val refResult  = RuntimeInterpreterReference.applyAll(HhGroupBSizes, Map.empty, batches)
      val refFlat    = RuntimeInterpreterReference.snapshotToFlatMap(refResult, HhGroupBOffsets, Asset)

      val state = new MutableWorldState(HhGroupBSizes)
      ImperativeInterpreter.applyAll(state, batches)

      state.snapshot shouldBe refResult
      refFlat shouldBe pureResult
      (0 until NumGroupA).foreach(i => state.balance(GroupA, Asset, i) shouldBe pureResult.getOrElse(i, 0L))
      (0 until NumGroupB).foreach(i => state.balance(GroupB, Asset, i) shouldBe pureResult.getOrElse(NumGroupA + i, 0L))
    }
  }

  it should "reject scatter batches with invalid sender dimension" in {
    val batch = BatchedFlow.Scatter(
      GroupA,
      GroupB,
      Array.fill(NumGroupA - 1)(100L),
      Array.fill(NumGroupA - 1)(0),
      Asset,
      MechanismId(1)
    )

    val state = new MutableWorldState(Map(GroupA -> NumGroupA, GroupB -> NumGroupB))
    an[IllegalArgumentException] should be thrownBy ImperativeInterpreter.applyBatch(state, batch)
  }

  it should "reject scatter batches with out-of-bounds target indices" in {
    val targets = Array.fill(NumGroupA)(0)
    targets(NumGroupA - 1) = NumGroupB
    val batch = BatchedFlow.Scatter(
      GroupA,
      GroupB,
      Array.fill(NumGroupA)(100L),
      targets,
      Asset,
      MechanismId(1)
    )

    val state = new MutableWorldState(Map(GroupA -> NumGroupA, GroupB -> NumGroupB))
    an[IllegalArgumentException] should be thrownBy ImperativeInterpreter.applyBatch(state, batch)
  }

  it should "reject scatter batches that would underflow the sender balance" in {
    val amounts = Array.fill(NumGroupA)(0L)
    val targets = Array.fill(NumGroupA)(0)
    amounts(0) = 1L
    val batch = BatchedFlow.Scatter(
      GroupA,
      GroupB,
      amounts,
      targets,
      Asset,
      MechanismId(1)
    )

    val state = new MutableWorldState(Map(GroupA -> NumGroupA, GroupB -> NumGroupB))
    state.setBalance(GroupA, Asset, 0, Long.MinValue) shouldBe Right(())

    val refState = Map((GroupA, Asset, 0) -> Long.MinValue)
    ImperativeInterpreter.canApplyBatch(state, batch) shouldBe false
    RuntimeInterpreterReference.canApplyBatch(HhGroupBSizes, refState, batch) shouldBe false
    ImperativeInterpreter.applyCheckedBatch(state, batch).isLeft shouldBe true
    an[IllegalArgumentException] should be thrownBy ImperativeInterpreter.applyBatch(state, batch)
  }

  it should "reject scatter batches that would overflow the target balance" in {
    val amounts = Array.fill(NumGroupA)(0L)
    val targets = Array.fill(NumGroupA)(0)
    amounts(0) = 1L
    val batch = BatchedFlow.Scatter(
      GroupA,
      GroupB,
      amounts,
      targets,
      Asset,
      MechanismId(1)
    )

    val state = new MutableWorldState(Map(GroupA -> NumGroupA, GroupB -> NumGroupB))
    state.setBalance(GroupB, Asset, 0, Long.MaxValue) shouldBe Right(())

    val refState = Map((GroupB, Asset, 0) -> Long.MaxValue)
    ImperativeInterpreter.canApplyBatch(state, batch) shouldBe false
    RuntimeInterpreterReference.canApplyBatch(HhGroupBSizes, refState, batch) shouldBe false
    ImperativeInterpreter.applyCheckedBatch(state, batch).isLeft shouldBe true
    an[IllegalArgumentException] should be thrownBy ImperativeInterpreter.applyBatch(state, batch)
  }

  // --- Broadcast (1:N) tests ---

  private val NumGroupC       = 7
  private val SourceIndex       = 0
  private val GroupCOff       = NumGroupA + NumGroupB // offset in flat ID space
  private val HhGroupCSizes   = Map(GroupA -> NumGroupA, GroupC -> NumGroupC)
  private val HhGroupCOffsets = Map(GroupA -> 0, GroupC -> GroupCOff)

  private val genBroadcastFlow = for
    amounts <- Gen.listOfN(NumGroupA, Gen.choose(0L, 100000L)).map(_.toArray)
    targets = (0 until NumGroupA).toArray // identity: each GroupA gets their own amount
  yield BatchedFlow.Broadcast(GroupC, SourceIndex, GroupA, amounts, targets, Asset, MechanismId(2))

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
      val flows      = broadcastToFlows(batch, GroupCOff, 0)
      val pureResult = Interpreter.applyAll(Map.empty[Int, Long], flows)
      val refResult  = RuntimeInterpreterReference.applyBatch(HhGroupCSizes, Map.empty, batch)
      val refFlat    = RuntimeInterpreterReference.snapshotToFlatMap(refResult, HhGroupCOffsets, Asset)

      val state = new MutableWorldState(HhGroupCSizes)
      ImperativeInterpreter.applyBatch(state, batch)

      state.snapshot shouldBe refResult
      refFlat shouldBe pureResult
      (0 until NumGroupA).foreach(i => state.balance(GroupA, Asset, i) shouldBe pureResult.getOrElse(i, 0L))
      state.balance(GroupC, Asset, SourceIndex) shouldBe pureResult.getOrElse(GroupCOff + SourceIndex, 0L)
    }
  }

  it should "preserve total wealth" in {
    forAll(genBroadcastFlow) { batch =>
      val state = new MutableWorldState(Map(GroupA -> NumGroupA, GroupC -> NumGroupC))
      state.setBalance(GroupC, Asset, SourceIndex, 100000000L) shouldBe Right(()) // generic source budget
      val before = state.totalForAsset(Asset)

      ImperativeInterpreter.applyBatch(state, batch)

      state.totalForAsset(Asset) shouldBe before
    }
  }

  it should "debit sender exactly once (totalDebit aggregation)" in {
    val amounts = Array(10000L, 20000L, 30000L)
    val targets = Array(0, 1, 2)
    val batch   = BatchedFlow.Broadcast(GroupC, SourceIndex, GroupA, amounts, targets, Asset, MechanismId(2))

    val state = new MutableWorldState(Map(GroupA -> 3, GroupC -> NumGroupC))
    ImperativeInterpreter.applyBatch(state, batch)

    state.balance(GroupC, Asset, SourceIndex) shouldBe -60000L
    state.balance(GroupA, Asset, 0) shouldBe 10000L
    state.balance(GroupA, Asset, 1) shouldBe 20000L
    state.balance(GroupA, Asset, 2) shouldBe 30000L
  }

  it should "produce identical results for multiple sequential broadcast batches" in {
    val genBatches = Gen.listOfN(5, genBroadcastFlow).map(_.toVector)
    forAll(genBatches) { batches =>
      val allFlows   = batches.flatMap(b => broadcastToFlows(b, GroupCOff, 0))
      val pureResult = Interpreter.applyAll(Map.empty[Int, Long], allFlows)
      val refResult  = RuntimeInterpreterReference.applyAll(HhGroupCSizes, Map.empty, batches)
      val refFlat    = RuntimeInterpreterReference.snapshotToFlatMap(refResult, HhGroupCOffsets, Asset)

      val state = new MutableWorldState(HhGroupCSizes)
      ImperativeInterpreter.applyAll(state, batches)

      state.snapshot shouldBe refResult
      refFlat shouldBe pureResult
      (0 until NumGroupA).foreach(i => state.balance(GroupA, Asset, i) shouldBe pureResult.getOrElse(i, 0L))
      state.balance(GroupC, Asset, SourceIndex) shouldBe pureResult.getOrElse(GroupCOff + SourceIndex, 0L)
    }
  }

  it should "reject broadcast batches with invalid sender index" in {
    val batch = BatchedFlow.Broadcast(
      GroupC,
      NumGroupC,
      GroupA,
      Array.fill(NumGroupA)(100L),
      (0 until NumGroupA).toArray,
      Asset,
      MechanismId(2)
    )

    val state = new MutableWorldState(Map(GroupA -> NumGroupA, GroupC -> NumGroupC))
    an[IllegalArgumentException] should be thrownBy ImperativeInterpreter.applyBatch(state, batch)
  }

  it should "reject broadcast batches with out-of-bounds target indices" in {
    val targets = (0 until NumGroupA).toArray
    targets(0) = NumGroupA
    val batch = BatchedFlow.Broadcast(
      GroupC,
      SourceIndex,
      GroupA,
      Array.fill(NumGroupA)(100L),
      targets,
      Asset,
      MechanismId(2)
    )

    val state = new MutableWorldState(Map(GroupA -> NumGroupA, GroupC -> NumGroupC))
    an[IllegalArgumentException] should be thrownBy ImperativeInterpreter.applyBatch(state, batch)
  }

  it should "reject broadcast batches whose aggregated debit would overflow Long" in {
    val batch = BatchedFlow.Broadcast(
      GroupC,
      SourceIndex,
      GroupA,
      Array(Long.MaxValue, 1L),
      Array(0, 1),
      Asset,
      MechanismId(2)
    )

    val state    = new MutableWorldState(Map(GroupA -> NumGroupA, GroupC -> NumGroupC))
    val refState = Map.empty[(AccountPartitionId, InstrumentId, Int), Long]

    ImperativeInterpreter.canApplyBatch(state, batch) shouldBe false
    RuntimeInterpreterReference.canApplyBatch(HhGroupCSizes, refState, batch) shouldBe false
    ImperativeInterpreter.applyCheckedBatch(state, batch).isLeft shouldBe true
    an[IllegalArgumentException] should be thrownBy ImperativeInterpreter.applyBatch(state, batch)
  }
