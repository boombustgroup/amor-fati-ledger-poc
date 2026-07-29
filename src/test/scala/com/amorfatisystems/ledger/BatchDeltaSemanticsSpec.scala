package com.amorfatisystems.ledger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BatchDeltaSemanticsSpec extends AnyFlatSpec with Matchers:

  private val GroupA    = AccountGroupId(1)
  private val GroupB = AccountGroupId(3)
  private val Asset = InstrumentId(1)

  "BatchDeltaSemantics.plan" should "extract explicit scatter deltas for non-zero entries only" in {
    val batch = BatchedFlow.Scatter(
      GroupA,
      GroupB,
      Array(10L, 0L, 7L),
      Array(1, 0, 2),
      Asset,
      MechanismId(1)
    )

    BatchDeltaSemantics.plan(batch) shouldBe BatchDeltaSemantics.ScatterPlan(
      GroupA,
      GroupB,
      Asset,
      Vector(
        BatchDeltaSemantics.ScatterDelta(0, 1, 10L),
        BatchDeltaSemantics.ScatterDelta(2, 2, 7L)
      )
    )
  }

  it should "extract explicit broadcast credits plus one aggregate debit" in {
    val batch = BatchedFlow.Broadcast(
      GroupB,
      1,
      GroupA,
      Array(0L, 20L, 30L),
      Array(0, 1, 2),
      Asset,
      MechanismId(2)
    )

    BatchDeltaSemantics.plan(batch) shouldBe BatchDeltaSemantics.BroadcastPlan(
      GroupB,
      GroupA,
      Asset,
      1,
      50L,
      Vector(
        BatchDeltaSemantics.BroadcastCredit(1, 20L),
        BatchDeltaSemantics.BroadcastCredit(2, 30L)
      )
    )
  }
