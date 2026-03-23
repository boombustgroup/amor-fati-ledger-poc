package com.boombustgroup.ledger

import stainless.lang._
import stainless.collection._
import stainless.annotation._

/** Stainless-verified flow interpreter core.
  *
  * Post-conditions verified by Z3 via `stainless --solvers=smt-z3`. Properties: flow conservation, frame condition, commutativity,
  * distribution exactness.
  */
object Verified:

  case class VFlow(from: BigInt, to: BigInt, amount: BigInt)

  def validFlow(f: VFlow): Boolean = f.from != f.to

  def allValid(flows: List[VFlow]): Boolean = flows match
    case Nil()       => true
    case Cons(f, tl) => validFlow(f) && allValid(tl)

  // --- Property 1+2: Flow conservation + Frame condition ---

  def applyFlow(balances: Map[BigInt, BigInt], flow: VFlow): Map[BigInt, BigInt] = {
    require(validFlow(flow))
    val currentFrom = balances.getOrElse(flow.from, BigInt(0))
    val currentTo   = balances.getOrElse(flow.to, BigInt(0))
    balances
      .updated(flow.from, currentFrom - flow.amount)
      .updated(flow.to, currentTo + flow.amount)
  } ensuring { res =>
    (res.getOrElse(flow.from, BigInt(0)) + res.getOrElse(flow.to, BigInt(0)) ==
      balances.getOrElse(flow.from, BigInt(0)) + balances.getOrElse(flow.to, BigInt(0))) &&
    forall((k: BigInt) =>
      (k != flow.from && k != flow.to) ==>
        (res.getOrElse(k, BigInt(0)) == balances.getOrElse(k, BigInt(0)))
    )
  }

  // --- Property 3: Sequential application ---

  def applyFlowList(balances: Map[BigInt, BigInt], flows: List[VFlow]): Map[BigInt, BigInt] = {
    require(allValid(flows))
    flows match
      case Nil()         => balances
      case Cons(f, rest) => applyFlowList(applyFlow(balances, f), rest)
  }

  // --- Property 4: Distribution exactness ---

  def distribute2(total: BigInt, share1: BigInt): (BigInt, BigInt) = {
    require(share1 >= BigInt(0) && share1 <= total && total >= BigInt(0))
    (share1, total - share1)
  } ensuring { res =>
    res._1 + res._2 == total
  }

  def distribute3(total: BigInt, part1: BigInt, part2: BigInt): (BigInt, BigInt, BigInt) = {
    require(part1 >= BigInt(0) && part2 >= BigInt(0) && part1 + part2 <= total && total >= BigInt(0))
    (part1, part2, total - part1 - part2)
  } ensuring { res =>
    res._1 + res._2 + res._3 == total
  }

  // --- Property 5: Commutativity of disjoint flows ---

  def commutativity(
      balances: Map[BigInt, BigInt],
      f1: VFlow,
      f2: VFlow
  ): Unit = {
    require(validFlow(f1) && validFlow(f2))
    require(f1.from != f2.from && f1.from != f2.to && f1.to != f2.from && f1.to != f2.to)
  } ensuring { _ =>
    applyFlow(applyFlow(balances, f1), f2) == applyFlow(applyFlow(balances, f2), f1)
  }
