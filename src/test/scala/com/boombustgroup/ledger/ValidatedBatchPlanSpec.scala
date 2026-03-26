package com.boombustgroup.ledger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ValidatedBatchPlanSpec extends AnyFlatSpec with Matchers:

  private val HH    = EntitySector.Households
  private val Banks = EntitySector.Banks
  private val Asset = AssetType.DemandDeposit

  "ValidatedBatchPlan" should "accept a sequence that stays executable across intermediate states" in {
    val state = new MutableWorldState(Map(HH -> 2, Banks -> 1))
    state.getBalances(HH, Asset)(0) = 100L
    state.getBalances(HH, Asset)(1) = 50L

    val batches = Vector(
      BatchedFlow.Scatter(HH, Banks, Array(10L, 0L), Array(0, 0), Asset, MechanismId(1)),
      BatchedFlow.Scatter(HH, Banks, Array(0L, 20L), Array(0, 0), Asset, MechanismId(2))
    )

    val plan = ValidatedBatchPlan.fromState(state, batches)
    plan.isRight shouldBe true

    ImperativeInterpreter.applyValidatedPlan(state, plan.toOption.get)
    state.balance(HH, Asset, 0) shouldBe 90L
    state.balance(HH, Asset, 1) shouldBe 30L
    state.balance(Banks, Asset, 0) shouldBe 30L
  }

  it should "reject a sequence that becomes overflow-unsafe after an earlier batch" in {
    val state = new MutableWorldState(Map(HH -> 1, Banks -> 1))
    state.getBalances(HH, Asset)(0) = 5L
    state.getBalances(Banks, Asset)(0) = Long.MaxValue - 5L

    val batches = Vector(
      BatchedFlow.Scatter(HH, Banks, Array(5L), Array(0), Asset, MechanismId(1)),
      BatchedFlow.Scatter(HH, Banks, Array(1L), Array(0), Asset, MechanismId(2))
    )

    ValidatedBatchPlan.fromState(state, batches).isLeft shouldBe true
  }

  "ImperativeInterpreter.planAndApplyAll" should "use the validated batch-plan path as a preferred safe entrypoint" in {
    val state = new MutableWorldState(Map(HH -> 2, Banks -> 1))
    state.getBalances(HH, Asset)(0) = 100L
    state.getBalances(HH, Asset)(1) = 50L

    val batches = Vector(
      BatchedFlow.Scatter(HH, Banks, Array(10L, 0L), Array(0, 0), Asset, MechanismId(1)),
      BatchedFlow.Scatter(HH, Banks, Array(0L, 20L), Array(0, 0), Asset, MechanismId(2))
    )

    ImperativeInterpreter.planAndApplyAll(state, batches) shouldBe Right(())
    state.balance(HH, Asset, 0) shouldBe 90L
    state.balance(HH, Asset, 1) shouldBe 30L
    state.balance(Banks, Asset, 0) shouldBe 30L
  }

  it should "return a Left when validation fails before executing the sequence" in {
    val state = new MutableWorldState(Map(HH -> 1, Banks -> 1))
    state.getBalances(HH, Asset)(0) = 5L
    state.getBalances(Banks, Asset)(0) = Long.MaxValue - 5L

    val batches = Vector(
      BatchedFlow.Scatter(HH, Banks, Array(5L), Array(0), Asset, MechanismId(1)),
      BatchedFlow.Scatter(HH, Banks, Array(1L), Array(0), Asset, MechanismId(2))
    )

    ImperativeInterpreter.planAndApplyAll(state, batches).isLeft shouldBe true
    state.balance(HH, Asset, 0) shouldBe 5L
    state.balance(Banks, Asset, 0) shouldBe Long.MaxValue - 5L
  }
