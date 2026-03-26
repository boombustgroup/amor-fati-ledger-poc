package com.boombustgroup.ledger

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

/** Test-only bridge between runtime `Int/Long` semantics and the `BigInt` reference-model shape used in Stainless.
  *
  * This is not a formal proof, but it narrows the trust gap by checking that runtime flow application matches the mathematically simpler
  * pointwise debit/credit model under non-overflow inputs.
  */
class InterpreterVerifiedBridgeSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks:

  private case class BigIntFlowRef(from: BigInt, to: BigInt, amount: BigInt)

  private def applyBigIntFlow(balances: Map[BigInt, BigInt], flow: BigIntFlowRef): Map[BigInt, BigInt] =
    val currentFrom = balances.getOrElse(flow.from, BigInt(0))
    val currentTo   = balances.getOrElse(flow.to, BigInt(0))
    balances
      .updated(flow.from, currentFrom - flow.amount)
      .updated(flow.to, currentTo + flow.amount)

  private def applyBigIntAll(balances: Map[BigInt, BigInt], flows: Vector[BigIntFlowRef]): Map[BigInt, BigInt] =
    flows.foldLeft(balances)(applyBigIntFlow)

  private def embedBalances(balances: Map[Int, Long]): Map[BigInt, BigInt] =
    balances.iterator.map { case (k, v) => BigInt(k) -> BigInt(v) }.toMap

  private def projectBalances(balances: Map[BigInt, BigInt]): Map[Int, Long] =
    balances.iterator.map { case (k, v) => k.toInt -> v.toLong }.toMap

  private val genAccountId = Gen.choose(0, 99)
  private val genAmount    = Gen.choose(1L, 1000000000L)
  private val genBalance   = Gen.choose(-1000000000L, 1000000000L)

  private val genFlow = for
    from   <- genAccountId
    to     <- genAccountId.suchThat(_ != from)
    amount <- genAmount
  yield Flow(from, to, amount, mechanism = 0)

  private val genFlows = Gen.listOfN(10, genFlow).map(_.toVector)

  private val genBalances = for
    n        <- Gen.choose(2, 20)
    accounts <- Gen.listOfN(n, Gen.zip(genAccountId, genBalance))
  yield accounts.toMap

  private def canApplyAllWithoutOverflow(balances: Map[Int, Long], flows: Vector[Flow]): Boolean =
    flows
      .foldLeft(Option(balances)) { (stateOpt, flow) =>
        stateOpt.flatMap { state =>
          val fromBalance = state.getOrElse(flow.from, 0L)
          val toBalance   = state.getOrElse(flow.to, 0L)
          val safe =
            fromBalance >= Long.MinValue + flow.amount &&
              toBalance <= Long.MaxValue - flow.amount

          if safe then Some(Interpreter.applyFlow(state, flow)) else None
        }
      }
      .isDefined

  "Interpreter.applyFlow" should "match the embedded BigInt reference model for non-overflow inputs" in {
    forAll(genBalances, genFlow) { (balances, flow) =>
      whenever(
        balances.getOrElse(flow.from, 0L) >= Long.MinValue + flow.amount &&
          balances.getOrElse(flow.to, 0L) <= Long.MaxValue - flow.amount
      ) {
        val runtimeResult = Interpreter.applyFlow(balances, flow)
        val bigIntResult = applyBigIntFlow(
          embedBalances(balances),
          BigIntFlowRef(BigInt(flow.from), BigInt(flow.to), BigInt(flow.amount))
        )

        runtimeResult shouldBe projectBalances(bigIntResult)
      }
    }
  }

  "Interpreter.applyAll" should "match the embedded BigInt reference model for non-overflow sequences" in {
    forAll(genBalances, genFlows) { (balances, flows) =>
      whenever(canApplyAllWithoutOverflow(balances, flows)) {
        val runtimeResult = Interpreter.applyAll(balances, flows)
        val bigIntResult = applyBigIntAll(
          embedBalances(balances),
          flows.map(flow => BigIntFlowRef(BigInt(flow.from), BigInt(flow.to), BigInt(flow.amount)))
        )

        runtimeResult shouldBe projectBalances(bigIntResult)
      }
    }
  }
