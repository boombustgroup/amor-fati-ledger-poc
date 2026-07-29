package com.amorfatisystems.ledger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ValidatedBatchPlanSpec extends AnyFlatSpec with Matchers:

  private val GroupA    = AccountGroupId(1)
  private val GroupB = AccountGroupId(3)
  private val Asset = InstrumentId(1)

  "ValidatedBatchPlan" should "accept a sequence that stays executable across intermediate states" in {
    val state = new MutableWorldState(Map(GroupA -> 2, GroupB -> 1))
    state.setBalance(GroupA, Asset, 0, 100L) shouldBe Right(())
    state.setBalance(GroupA, Asset, 1, 50L) shouldBe Right(())

    val batches = Vector(
      BatchedFlow.Scatter(GroupA, GroupB, Array(10L, 0L), Array(0, 0), Asset, MechanismId(1)),
      BatchedFlow.Scatter(GroupA, GroupB, Array(0L, 20L), Array(0, 0), Asset, MechanismId(2))
    )

    val plan = ValidatedBatchPlan.fromState(state, batches)
    plan.isRight shouldBe true

    ImperativeInterpreter.applyValidatedPlan(state, plan.toOption.get)
    state.balance(GroupA, Asset, 0) shouldBe 90L
    state.balance(GroupA, Asset, 1) shouldBe 30L
    state.balance(GroupB, Asset, 0) shouldBe 30L
  }

  it should "reject a sequence that becomes overflow-unsafe after an earlier batch" in {
    val state = new MutableWorldState(Map(GroupA -> 1, GroupB -> 1))
    state.setBalance(GroupA, Asset, 0, 5L) shouldBe Right(())
    state.setBalance(GroupB, Asset, 0, Long.MaxValue - 5L) shouldBe Right(())

    val batches = Vector(
      BatchedFlow.Scatter(GroupA, GroupB, Array(5L), Array(0), Asset, MechanismId(1)),
      BatchedFlow.Scatter(GroupA, GroupB, Array(1L), Array(0), Asset, MechanismId(2))
    )

    ValidatedBatchPlan.fromState(state, batches).isLeft shouldBe true
  }

  "ImperativeInterpreter.planAndApplyAll" should "use the validated batch-plan path as a preferred safe entrypoint" in {
    val state = new MutableWorldState(Map(GroupA -> 2, GroupB -> 1))
    state.setBalance(GroupA, Asset, 0, 100L) shouldBe Right(())
    state.setBalance(GroupA, Asset, 1, 50L) shouldBe Right(())

    val batches = Vector(
      BatchedFlow.Scatter(GroupA, GroupB, Array(10L, 0L), Array(0, 0), Asset, MechanismId(1)),
      BatchedFlow.Scatter(GroupA, GroupB, Array(0L, 20L), Array(0, 0), Asset, MechanismId(2))
    )

    ImperativeInterpreter.planAndApplyAll(state, batches) shouldBe Right(())
    state.balance(GroupA, Asset, 0) shouldBe 90L
    state.balance(GroupA, Asset, 1) shouldBe 30L
    state.balance(GroupB, Asset, 0) shouldBe 30L
  }

  it should "return a Left when validation fails before executing the sequence" in {
    val state = new MutableWorldState(Map(GroupA -> 1, GroupB -> 1))
    state.setBalance(GroupA, Asset, 0, 5L) shouldBe Right(())
    state.setBalance(GroupB, Asset, 0, Long.MaxValue - 5L) shouldBe Right(())

    val batches = Vector(
      BatchedFlow.Scatter(GroupA, GroupB, Array(5L), Array(0), Asset, MechanismId(1)),
      BatchedFlow.Scatter(GroupA, GroupB, Array(1L), Array(0), Asset, MechanismId(2))
    )

    ImperativeInterpreter.planAndApplyAll(state, batches).isLeft shouldBe true
    state.balance(GroupA, Asset, 0) shouldBe 5L
    state.balance(GroupB, Asset, 0) shouldBe Long.MaxValue - 5L
  }
