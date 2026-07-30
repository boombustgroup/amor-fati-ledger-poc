package com.amorfatisystems.ledger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LedgerStateSpec extends AnyFlatSpec with Matchers:
  private val A = AccountId(1)
  private val B = AccountId(2)
  private val X = CurrencyId(1)
  private val M = MechanismId(1)
  private val P = PeriodId(202507L)

  private val topology = LedgerTopology
    .validate(Map(A -> AccountMetadata(X, maxBalance = Some(100L)), B -> AccountMetadata(X, maxBalance = Some(100L))))
    .toOption
    .get

  "LedgerState" should "execute atomically and expose input/output evidence versions" in {
    val state  = LedgerState.initial(topology, Map(A -> 50L, B -> 0L), preparedCapacity = 3).toOption.get
    val result = LedgerStateExecutor.execute(state, Transfer(A, B, 20L, M, P), expectedVersion = 0L)

    result.map(_._1.balances) shouldBe Right(Map(A -> 30L, B -> 20L))
    result.map(_._1.version) shouldBe Right(1L)
    result.map(_._2.inputVersion) shouldBe Right(0L)
    result.map(_._2.outputVersion) shouldBe Right(1L)
    state.version shouldBe 0L
    state.balances shouldBe Map(A -> 50L)
  }

  it should "reject stale versions without changing the input state" in {
    val state = LedgerState.initial(topology, Map(A -> 50L, B -> 0L)).toOption.get

    val rejection = LedgerStateExecutor.execute(state, Transfer(A, B, 20L, M, P), expectedVersion = 7L).left.toOption.get
    rejection.reason shouldBe ExecutionRejectionReason.VersionMismatch
    rejection.snapshotVersion shouldBe 0L
    state.version shouldBe 0L
    state.balances shouldBe Map(A -> 50L)
  }

  it should "treat an empty sequence as a version-preserving no-op" in {
    val state = LedgerState.initial(topology, Map(A -> 50L, B -> 0L)).toOption.get

    val result = LedgerStateExecutor.executeSequence(state, Vector.empty, expectedVersion = 0L)
    result.map(_._1.version) shouldBe Right(0L)
    result.map(_._2.inputVersion) shouldBe Right(0L)
    result.map(_._2.outputVersion) shouldBe Right(0L)
  }

  it should "preserve the state when a sequence fails" in {
    val state     = LedgerState.initial(topology, Map(A -> 50L, B -> 0L)).toOption.get
    val transfers = Vector(Transfer(A, B, 20L, M, P), Transfer(A, B, 90L, M, P))

    LedgerStateExecutor.executeSequence(state, transfers, expectedVersion = 0L).isLeft shouldBe true
    state.version shouldBe 0L
    state.balances shouldBe Map(A -> 50L)
  }

  it should "enforce prepared capacity for lifecycle creation" in {
    val state = LedgerState.initial(topology, Map(A -> 50L, B -> 0L), preparedCapacity = 2).toOption.get

    LedgerStateLifecycle.create(state, AccountId(3), AccountMetadata(X), 0L).isLeft shouldBe true
  }

  it should "version lifecycle transitions" in {
    val state   = LedgerState.initial(topology, Map(A -> 0L, B -> 0L), preparedCapacity = 3).toOption.get
    val created = LedgerStateLifecycle.create(state, AccountId(3), AccountMetadata(X), 0L).toOption.get

    created.version shouldBe 1L
    created.topology.accounts.contains(AccountId(3)) shouldBe true
    LedgerStateLifecycle.close(created, AccountId(3)).map(_.version) shouldBe Right(2L)
  }

  "DenseLedgerBackend" should "match the immutable state semantics and aggregate evidence" in {
    val state   = LedgerState.initial(topology, Map(A -> 50L, B -> 0L)).toOption.get
    val backend = DenseLedgerBackend.prepare(state)
    val result = backend.execute(
      Vector(Transfer(A, B, 10L, M, P), Transfer(A, B, 5L, M, P)),
      expectedVersion = 0L,
      mode = ExecutionEvidenceMode.AggregatedByMechanism
    )

    backend.snapshot.balances shouldBe Map(A -> 35L, B -> 15L)
    result.map(_.asInstanceOf[AggregatedEvidence].groups.head.amount) shouldBe Right(15L)
    backend.version shouldBe 1L
  }

  it should "discard staged dense changes on rejection" in {
    val state   = LedgerState.initial(topology, Map(A -> 50L, B -> 0L)).toOption.get
    val backend = DenseLedgerBackend.prepare(state)

    backend.execute(Vector(Transfer(A, B, 20L, M, P), Transfer(A, B, 90L, M, P)), 0L).isLeft shouldBe true
    backend.version shouldBe 0L
    backend.snapshot.balances shouldBe Map(A -> 50L)
  }

  it should "match reference execution across deterministic randomized batches" in {
    val random    = new scala.util.Random(17L)
    var reference = LedgerState.initial(topology, Map(A -> 50L, B -> 0L), preparedCapacity = 3).toOption.get
    val dense     = DenseLedgerBackend.prepare(reference)
    (0 until 20).foreach { _ =>
      val amount   = random.nextInt(2).toLong
      val transfer = Transfer(A, B, amount, M, P)
      val expected = LedgerStateExecutor.execute(reference, transfer, reference.version).toOption.get
      val actual   = dense.execute(Vector(transfer), dense.version).toOption.get
      dense.snapshot.balances shouldBe expected._1.balances
      dense.snapshot.version shouldBe expected._1.version
      actual.inputVersion shouldBe expected._2.inputVersion
      actual.outputVersion shouldBe expected._2.outputVersion
      actual.debitTotal shouldBe expected._2.debitTotal
      reference = expected._1
    }
  }

  it should "reject a stale dense snapshot" in {
    val state = LedgerState.initial(topology, Map(A -> 50L, B -> 0L)).toOption.get
    val dense = DenseLedgerBackend.prepare(state)

    dense.execute(Vector(Transfer(A, B, 1L, M, P)), expectedVersion = 9L).left.toOption.get.reason shouldBe
      ExecutionRejectionReason.VersionMismatch
  }

  it should "preserve zero-balance normalization across reference and dense execution" in {
    val state    = LedgerState.initial(topology, Map(A -> 50L, B -> 0L)).toOption.get
    val dense    = DenseLedgerBackend.prepare(state)
    val transfer = Transfer(A, B, 50L, M, P)

    val expected = LedgerStateExecutor.execute(state, transfer, 0L).toOption.get
    val actual   = dense.execute(Vector(transfer), 0L).toOption.get
    dense.snapshot.balances shouldBe expected._1.balances
    dense.snapshot.balances shouldBe Map(B -> 50L)
  }

  it should "report typed rejection reasons for bounds, permissions, and unknown accounts" in {
    val boundedTopology = LedgerTopology
      .validate(
        Map(A -> AccountMetadata(X, minBalance = Some(0L), maxBalance = Some(100L)), B -> AccountMetadata(X, maxBalance = Some(100L)))
      )
      .toOption
      .get
    val state  = LedgerState.initial(boundedTopology, Map(A -> 50L, B -> 0L)).toOption.get
    val bounds = LedgerStateExecutor.execute(state, Transfer(A, B, 60L, M, P), 0L).left.toOption.get
    bounds.reason shouldBe ExecutionRejectionReason.Bounds

    val deniedTopology = LedgerTopology
      .validate(Map(A -> AccountMetadata(X, canDebit = false), B -> AccountMetadata(X)))
      .toOption
      .get
    val deniedState = LedgerState.initial(deniedTopology, Map(A -> 50L, B -> 0L)).toOption.get
    LedgerStateExecutor.execute(deniedState, Transfer(A, B, 1L, M, P), 0L).left.toOption.get.reason shouldBe
      ExecutionRejectionReason.PermissionDenied

    val unknown = LedgerStateExecutor.execute(state, Transfer(AccountId(99), B, 1L, M, P), 0L).left.toOption.get
    unknown.reason shouldBe ExecutionRejectionReason.LifecycleViolation
  }
