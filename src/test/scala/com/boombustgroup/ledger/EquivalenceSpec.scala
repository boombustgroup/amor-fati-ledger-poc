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

  private val NumAgentsFrom = 20
  private val NumAgentsTo   = 5
  private val SectorFrom    = EntitySector.Households
  private val SectorTo      = EntitySector.Banks
  private val Asset         = AssetType.DemandDeposit

  private val genAmount      = Gen.choose(0L, 1000000L)
  private val genTargetIndex = Gen.choose(0, NumAgentsTo - 1)

  private val genBatchedFlow = for
    amounts <- Gen.listOfN(NumAgentsFrom, genAmount).map(_.toArray)
    targets <- Gen.listOfN(NumAgentsFrom, genTargetIndex).map(_.toArray)
  yield BatchedFlow(SectorFrom, SectorTo, amounts, targets, Asset, Mechanism.HhConsumption)

  private val genInitBalance = Gen.choose(-10000000L, 10000000L)

  /** Convert a BatchedFlow to individual Flow objects for the pure interpreter. */
  private def toIndividualFlows(batch: BatchedFlow, fromOffset: Int, toOffset: Int): Vector[Flow] =
    batch.amounts.indices.flatMap { i =>
      val amount = batch.amounts(i)
      val fromId = fromOffset + i
      val toId   = toOffset + batch.targetIndices(i)
      if amount != 0L && fromId != toId then Some(Flow(fromId, toId, amount, batch.mechanism.ordinal))
      else None
    }.toVector

  "ImperativeInterpreter" should "produce identical results to pure Interpreter for single batch" in {
    forAll(genBatchedFlow) { batch =>
      // Pure path: Map-based
      val fromOffset  = 0
      val toOffset    = NumAgentsFrom // banks start after households in flat ID space
      val initialPure = Map.empty[Int, Long]
      val flows       = toIndividualFlows(batch, fromOffset, toOffset)
      val pureResult  = Interpreter.applyAll(initialPure, flows)

      // Imperative path: Array-based
      val state = new MutableWorldState(Map(SectorFrom -> NumAgentsFrom, SectorTo -> NumAgentsTo))
      ImperativeInterpreter.applyBatch(state, batch)

      // Compare: every from-sector balance must match
      (0 until NumAgentsFrom).foreach { i =>
        state.balance(SectorFrom, Asset, i) shouldBe pureResult.getOrElse(fromOffset + i, 0L)
      }
      // Compare: every to-sector balance must match
      (0 until NumAgentsTo).foreach { i =>
        state.balance(SectorTo, Asset, i) shouldBe pureResult.getOrElse(toOffset + i, 0L)
      }
    }
  }

  it should "produce identical results for multiple sequential batches" in {
    val genBatches = Gen.listOfN(5, genBatchedFlow).map(_.toVector)
    forAll(genBatches) { batches =>
      // Pure path
      val fromOffset = 0
      val toOffset   = NumAgentsFrom
      val allFlows   = batches.flatMap(b => toIndividualFlows(b, fromOffset, toOffset))
      val pureResult = Interpreter.applyAll(Map.empty[Int, Long], allFlows)

      // Imperative path
      val state = new MutableWorldState(Map(SectorFrom -> NumAgentsFrom, SectorTo -> NumAgentsTo))
      ImperativeInterpreter.applyAll(state, batches)

      // Compare
      (0 until NumAgentsFrom).foreach { i =>
        state.balance(SectorFrom, Asset, i) shouldBe pureResult.getOrElse(fromOffset + i, 0L)
      }
      (0 until NumAgentsTo).foreach { i =>
        state.balance(SectorTo, Asset, i) shouldBe pureResult.getOrElse(toOffset + i, 0L)
      }
    }
  }

  it should "preserve total wealth (conservation)" in {
    forAll(genBatchedFlow) { batch =>
      val state = new MutableWorldState(Map(SectorFrom -> NumAgentsFrom, SectorTo -> NumAgentsTo))

      // Set some initial balances
      val fromArr = state.getBalances(SectorFrom, Asset)
      (0 until NumAgentsFrom).foreach(i => fromArr(i) = 1000000L)
      val totalBefore = state.totalForAsset(Asset)

      ImperativeInterpreter.applyBatch(state, batch)
      val totalAfter = state.totalForAsset(Asset)

      totalAfter shouldBe totalBefore
    }
  }
