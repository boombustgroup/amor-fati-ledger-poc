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

  val runtimeMinBalance: BigInt = BigInt("-9223372036854775808")
  val runtimeMaxBalance: BigInt = BigInt("9223372036854775807")

  case class VFlow(from: BigInt, to: BigInt, amount: BigInt)
  case class RuntimeFlow(from: Int, to: Int, amount: Long)
  case class RuntimeBoundedFlow(from: BigInt, to: BigInt, amount: BigInt)

  def validFlow(f: VFlow): Boolean = f.from != f.to
  def validRuntimeFlow(f: RuntimeFlow): Boolean = f.from != f.to && f.amount >= 0L
  def validRuntimeBoundedFlow(f: RuntimeBoundedFlow): Boolean =
    f.from != f.to && f.amount >= BigInt(0) && f.amount <= runtimeMaxBalance

  def allValid(flows: List[VFlow]): Boolean = flows match
    case Nil()       => true
    case Cons(f, tl) => validFlow(f) && allValid(tl)

  def allValidRuntime(flows: List[RuntimeFlow]): Boolean = flows match
    case Nil()       => true
    case Cons(f, tl) => validRuntimeFlow(f) && allValidRuntime(tl)

  def allValidRuntimeBounded(flows: List[RuntimeBoundedFlow]): Boolean = flows match
    case Nil()       => true
    case Cons(f, tl) => validRuntimeBoundedFlow(f) && allValidRuntimeBounded(tl)

  def canApplyRuntimeFlow(balances: Map[Int, Long], flow: RuntimeFlow): Boolean =
    validRuntimeFlow(flow) &&
      balances.getOrElse(flow.from, 0L) >= Long.MinValue + flow.amount &&
      balances.getOrElse(flow.to, 0L) <= Long.MaxValue - flow.amount

  def canApplyRuntimeFlowList(balances: Map[Int, Long], flows: List[RuntimeFlow]): Boolean = flows match
    case Nil() => true
    case Cons(f, rest) =>
      canApplyRuntimeFlow(balances, f) &&
        canApplyRuntimeFlowList(applyRuntimeFlow(balances, f), rest)

  def canApplyRuntimeBoundedFlow(balances: Map[BigInt, BigInt], flow: RuntimeBoundedFlow): Boolean =
    validRuntimeBoundedFlow(flow) &&
      balances.getOrElse(flow.from, BigInt(0)) >= runtimeMinBalance + flow.amount &&
      balances.getOrElse(flow.to, BigInt(0)) <= runtimeMaxBalance - flow.amount

  def canApplyRuntimeBoundedFlowList(
      balances: Map[BigInt, BigInt],
      flows: List[RuntimeBoundedFlow]
  ): Boolean = flows match
    case Nil() => true
    case Cons(f, rest) =>
      canApplyRuntimeBoundedFlow(balances, f) &&
        canApplyRuntimeBoundedFlowList(applyRuntimeBoundedFlow(balances, f), rest)

  def toVFlow(flow: RuntimeBoundedFlow): VFlow =
    VFlow(flow.from, flow.to, flow.amount)

  def toVFlowList(flows: List[RuntimeBoundedFlow]): List[VFlow] = flows match
    case Nil()       => Nil()
    case Cons(f, tl) => Cons(toVFlow(f), toVFlowList(tl))

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

  def applyRuntimeFlow(balances: Map[Int, Long], flow: RuntimeFlow): Map[Int, Long] = {
    require(validRuntimeFlow(flow))
    val currentFrom = balances.getOrElse(flow.from, 0L)
    val currentTo   = balances.getOrElse(flow.to, 0L)
    require(currentFrom >= Long.MinValue + flow.amount)
    require(currentTo <= Long.MaxValue - flow.amount)
    balances
      .updated(flow.from, currentFrom - flow.amount)
      .updated(flow.to, currentTo + flow.amount)
  } ensuring { res =>
    (res.getOrElse(flow.from, 0L) == balances.getOrElse(flow.from, 0L) - flow.amount) &&
    (res.getOrElse(flow.to, 0L) == balances.getOrElse(flow.to, 0L) + flow.amount) &&
    forall((k: Int) =>
      (k != flow.from && k != flow.to) ==>
        (res.getOrElse(k, 0L) == balances.getOrElse(k, 0L))
    )
  }

  def applyRuntimeBoundedFlow(
      balances: Map[BigInt, BigInt],
      flow: RuntimeBoundedFlow
  ): Map[BigInt, BigInt] = {
    require(validRuntimeBoundedFlow(flow))
    val currentFrom = balances.getOrElse(flow.from, BigInt(0))
    val currentTo   = balances.getOrElse(flow.to, BigInt(0))
    require(currentFrom >= runtimeMinBalance + flow.amount)
    require(currentTo <= runtimeMaxBalance - flow.amount)
    balances
      .updated(flow.from, currentFrom - flow.amount)
      .updated(flow.to, currentTo + flow.amount)
  } ensuring { res =>
    (res.getOrElse(flow.from, BigInt(0)) == balances.getOrElse(flow.from, BigInt(0)) - flow.amount) &&
    (res.getOrElse(flow.to, BigInt(0)) == balances.getOrElse(flow.to, BigInt(0)) + flow.amount) &&
    forall((k: BigInt) =>
      (k != flow.from && k != flow.to) ==>
        (res.getOrElse(k, BigInt(0)) == balances.getOrElse(k, BigInt(0)))
    )
  }

  def runtimeBoundedRefinesApplyFlow(
      balances: Map[BigInt, BigInt],
      flow: RuntimeBoundedFlow
  ): Unit = {
    require(validRuntimeBoundedFlow(flow))
    require(balances.getOrElse(flow.from, BigInt(0)) >= runtimeMinBalance + flow.amount)
    require(balances.getOrElse(flow.to, BigInt(0)) <= runtimeMaxBalance - flow.amount)
  } ensuring { _ =>
    applyRuntimeBoundedFlow(balances, flow) ==
      applyFlow(balances, VFlow(flow.from, flow.to, flow.amount))
  }

  def commutativityRuntime(
      balances: Map[Int, Long],
      f1: RuntimeFlow,
      f2: RuntimeFlow
  ): Unit = {
    require(validRuntimeFlow(f1) && validRuntimeFlow(f2))
    require(f1.from != f2.from && f1.from != f2.to && f1.to != f2.from && f1.to != f2.to)

    val b1From = balances.getOrElse(f1.from, 0L)
    val b1To   = balances.getOrElse(f1.to, 0L)
    val b2From = balances.getOrElse(f2.from, 0L)
    val b2To   = balances.getOrElse(f2.to, 0L)

    require(b1From >= Long.MinValue + f1.amount)
    require(b1To <= Long.MaxValue - f1.amount)
    require(b2From >= Long.MinValue + f2.amount)
    require(b2To <= Long.MaxValue - f2.amount)

    val afterF1 = applyRuntimeFlow(balances, f1)
    val afterF2 = applyRuntimeFlow(balances, f2)

    assert(afterF1.getOrElse(f2.from, 0L) == b2From)
    assert(afterF1.getOrElse(f2.to, 0L) == b2To)
    assert(afterF2.getOrElse(f1.from, 0L) == b1From)
    assert(afterF2.getOrElse(f1.to, 0L) == b1To)

    val order12 = applyRuntimeFlow(afterF1, f2)
    val order21 = applyRuntimeFlow(afterF2, f1)

    assert(order12.getOrElse(f1.from, 0L) == b1From - f1.amount)
    assert(order21.getOrElse(f1.from, 0L) == b1From - f1.amount)
    assert(order12.getOrElse(f1.to, 0L) == b1To + f1.amount)
    assert(order21.getOrElse(f1.to, 0L) == b1To + f1.amount)
    assert(order12.getOrElse(f2.from, 0L) == b2From - f2.amount)
    assert(order21.getOrElse(f2.from, 0L) == b2From - f2.amount)
    assert(order12.getOrElse(f2.to, 0L) == b2To + f2.amount)
    assert(order21.getOrElse(f2.to, 0L) == b2To + f2.amount)
    assert(
      forall((k: Int) =>
        (k != f1.from && k != f1.to && k != f2.from && k != f2.to) ==>
          (order12.getOrElse(k, 0L) == balances.getOrElse(k, 0L))
      )
    )
    assert(
      forall((k: Int) =>
        (k != f1.from && k != f1.to && k != f2.from && k != f2.to) ==>
          (order21.getOrElse(k, 0L) == balances.getOrElse(k, 0L))
      )
    )
  } ensuring { _ =>
    applyRuntimeFlow(applyRuntimeFlow(balances, f1), f2) ==
      applyRuntimeFlow(applyRuntimeFlow(balances, f2), f1)
  }

  // --- Property 3: Sequential application ---

  def applyFlowList(balances: Map[BigInt, BigInt], flows: List[VFlow]): Map[BigInt, BigInt] = {
    require(allValid(flows))
    flows match
      case Nil()         => balances
      case Cons(f, rest) => applyFlowList(applyFlow(balances, f), rest)
  }

  def applyRuntimeFlowList(balances: Map[Int, Long], flows: List[RuntimeFlow]): Map[Int, Long] = {
    require(canApplyRuntimeFlowList(balances, flows))
    flows match
      case Nil()         => balances
      case Cons(f, rest) => applyRuntimeFlowList(applyRuntimeFlow(balances, f), rest)
  }

  def applyRuntimeBoundedFlowList(
      balances: Map[BigInt, BigInt],
      flows: List[RuntimeBoundedFlow]
  ): Map[BigInt, BigInt] = {
    require(canApplyRuntimeBoundedFlowList(balances, flows))
    flows match
      case Nil()         => balances
      case Cons(f, rest) => applyRuntimeBoundedFlowList(applyRuntimeBoundedFlow(balances, f), rest)
  }

  def canApplyRuntimeBoundedFlowListProducesValidVFlows(
      balances: Map[BigInt, BigInt],
      flows: List[RuntimeBoundedFlow]
  ): Unit = {
    require(canApplyRuntimeBoundedFlowList(balances, flows))
    flows match
      case Nil() =>
      case Cons(f, rest) =>
        canApplyRuntimeBoundedFlowListProducesValidVFlows(applyRuntimeBoundedFlow(balances, f), rest)
  } ensuring { _ =>
    allValid(toVFlowList(flows))
  }

  def runtimeBoundedFlowListRefinesApplyFlowList(
      balances: Map[BigInt, BigInt],
      flows: List[RuntimeBoundedFlow]
  ): Unit = {
    require(canApplyRuntimeBoundedFlowList(balances, flows))
    canApplyRuntimeBoundedFlowListProducesValidVFlows(balances, flows)
    flows match
      case Nil() =>
      case Cons(f, rest) =>
        runtimeBoundedRefinesApplyFlow(balances, f)
        runtimeBoundedFlowListRefinesApplyFlowList(applyRuntimeBoundedFlow(balances, f), rest)
  } ensuring { _ =>
    applyRuntimeBoundedFlowList(balances, flows) ==
      applyFlowList(balances, toVFlowList(flows))
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

  def listSum(xs: List[BigInt]): BigInt = xs match
    case Nil()       => BigInt(0)
    case Cons(x, tl) => x + listSum(tl)

  def allNonNegative(xs: List[BigInt]): Boolean = xs match
    case Nil()       => true
    case Cons(x, tl) => x >= BigInt(0) && allNonNegative(tl)

  def append(xs: List[BigInt], x: BigInt): List[BigInt] = {
    xs match
      case Nil()       => Cons(x, Nil())
      case Cons(h, tl) => Cons(h, append(tl, x))
  } ensuring { res =>
    listSum(res) == listSum(xs) + x &&
    ((allNonNegative(xs) && x >= BigInt(0)) ==> allNonNegative(res))
  }

  def distributeN(total: BigInt, parts: List[BigInt]): List[BigInt] = {
    require(total >= BigInt(0))
    require(allNonNegative(parts))
    require(listSum(parts) <= total)
    append(parts, total - listSum(parts))
  } ensuring { res =>
    listSum(res) == total &&
    allNonNegative(res)
  }

  def scaleList(factor: BigInt, xs: List[BigInt]): List[BigInt] = {
    require(factor >= BigInt(0))
    xs match
      case Nil()       => Nil()
      case Cons(x, tl) => Cons(factor * x, scaleList(factor, tl))
  } ensuring { res =>
    listSum(res) == factor * listSum(xs) &&
    (allNonNegative(xs) ==> allNonNegative(res))
  }

  def distributeProportionalExact(total: BigInt, shares: List[BigInt]): List[BigInt] = {
    require(total >= BigInt(0))
    require(shares != Nil())
    require(allNonNegative(shares))
    require(listSum(shares) > BigInt(0))
    require(total % listSum(shares) == BigInt(0))
    val unit = total / listSum(shares)
    scaleList(unit, shares)
  } ensuring { res =>
    listSum(res) == total &&
    allNonNegative(res)
  }

  def distributeProportionalUnitResidual(total: BigInt, shares: List[BigInt]): List[BigInt] = {
    require(total >= BigInt(0))
    require(shares != Nil())
    require(allNonNegative(shares))
    require(listSum(shares) > BigInt(0))
    val shareSum = listSum(shares)
    val unit     = total / shareSum
    val residual = total % shareSum
    distributeN(total, scaleList(unit, shares))
  } ensuring { res =>
    listSum(res) == total &&
    allNonNegative(res)
  }

  def floorPartNonNegative(total: BigInt, share: BigInt, shareSum: BigInt): Unit = {
    require(total >= BigInt(0))
    require(share >= BigInt(0))
    require(shareSum > BigInt(0))
  } ensuring { _ =>
    (total * share) / shareSum >= BigInt(0)
  }

  def floorDivAddUpper(a: BigInt, b: BigInt, d: BigInt): Unit = {
    require(a >= BigInt(0))
    require(b >= BigInt(0))
    require(d > BigInt(0))
  } ensuring { _ =>
    a / d + b / d <= (a + b) / d
  }

  def listSumNonNegative(xs: List[BigInt]): Unit = {
    require(allNonNegative(xs))
    xs match {
      case Nil() =>
      case Cons(_, tail) =>
        listSumNonNegative(tail)
    }
  } ensuring { _ =>
    listSum(xs) >= BigInt(0)
  }

  def mulNonNegative(a: BigInt, b: BigInt): Unit = {
    require(a >= BigInt(0))
    require(b >= BigInt(0))
  } ensuring { _ =>
    a * b >= BigInt(0)
  }

  def mulDivSelf(a: BigInt, d: BigInt): Unit = {
    require(a >= BigInt(0))
    require(d > BigInt(0))
  } ensuring { _ =>
    (a * d) / d == a
  }

  def mapFloorShares(total: BigInt, shareSum: BigInt, shares: List[BigInt]): List[BigInt] = {
    require(total >= BigInt(0))
    require(shareSum > BigInt(0))
    require(allNonNegative(shares))
    shares match {
      case Nil() =>
        Nil[BigInt]()
      case Cons(head, tail) =>
        floorPartNonNegative(total, head, shareSum)
        Cons((total * head) / shareSum, mapFloorShares(total, shareSum, tail))
    }
  } ensuring { res =>
    allNonNegative(res)
  }

  def mapFloorSharesSumUpper(total: BigInt, shareSum: BigInt, shares: List[BigInt]): Unit = {
    require(total >= BigInt(0))
    require(shareSum > BigInt(0))
    require(allNonNegative(shares))
    shares match {
      case Nil() =>
      case Cons(head, tail) =>
        mapFloorSharesSumUpper(total, shareSum, tail)
        listSumNonNegative(tail)
        mulNonNegative(total, listSum(tail))
        floorDivAddUpper(total * head, total * listSum(tail), shareSum)
    }
  } ensuring { _ =>
    listSum(mapFloorShares(total, shareSum, shares)) <= (total * listSum(shares)) / shareSum
  }

  def floorScaledSharesFold(total: BigInt, shareSum: BigInt, shares: List[BigInt]): List[BigInt] = {
    require(total >= BigInt(0))
    require(shareSum > BigInt(0))
    require(allNonNegative(shares))
    require(shareSum == listSum(shares))
    mapFloorShares(total, shareSum, shares)
  } ensuring { res =>
    allNonNegative(res)
  }

  def distributeProportionalFloorResidual(total: BigInt, shares: List[BigInt]): List[BigInt] = {
    require(total >= BigInt(0))
    require(shares != Nil())
    require(allNonNegative(shares))
    require(listSum(shares) > BigInt(0))
    val shareSum = listSum(shares)
    mapFloorSharesSumUpper(total, shareSum, shares)
    mulDivSelf(total, shareSum)
    val floored = mapFloorShares(total, shareSum, shares)
    assert(listSum(floored) <= total)
    distributeN(total, floored)
  } ensuring { res =>
    listSum(res) == total &&
    allNonNegative(res)
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
