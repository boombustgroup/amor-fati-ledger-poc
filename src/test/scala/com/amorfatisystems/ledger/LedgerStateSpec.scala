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
    state.balances shouldBe Map(A -> 50L, B -> 0L)
  }

  it should "reject stale versions without changing the input state" in {
    val state = LedgerState.initial(topology, Map(A -> 50L, B -> 0L)).toOption.get

    val rejection = LedgerStateExecutor.execute(state, Transfer(A, B, 20L, M, P), expectedVersion = 7L).left.toOption.get
    rejection.reason shouldBe ExecutionRejectionReason.VersionMismatch
    rejection.snapshotVersion shouldBe 0L
    state.version shouldBe 0L
    state.balances shouldBe Map(A -> 50L, B -> 0L)
  }

  it should "advance once for a successful no-op sequence" in {
    val state = LedgerState.initial(topology, Map(A -> 50L, B -> 0L)).toOption.get

    val result = LedgerStateExecutor.executeSequence(state, Vector.empty, expectedVersion = 0L)
    result.map(_._1.version) shouldBe Right(1L)
    result.map(_._2.inputVersion) shouldBe Right(0L)
    result.map(_._2.outputVersion) shouldBe Right(1L)
  }

  it should "preserve the state when a sequence fails" in {
    val state     = LedgerState.initial(topology, Map(A -> 50L, B -> 0L)).toOption.get
    val transfers = Vector(Transfer(A, B, 20L, M, P), Transfer(A, B, 90L, M, P))

    LedgerStateExecutor.executeSequence(state, transfers, expectedVersion = 0L).isLeft shouldBe true
    state.version shouldBe 0L
    state.balances shouldBe Map(A -> 50L, B -> 0L)
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

    result.map(_._1.balances) shouldBe Right(Map(A -> 35L, B -> 15L))
    result.map(_._2.toOption.get.head.amount) shouldBe Right(15L)
    backend.version shouldBe 1L
  }

  it should "discard staged dense changes on rejection" in {
    val state   = LedgerState.initial(topology, Map(A -> 50L, B -> 0L)).toOption.get
    val backend = DenseLedgerBackend.prepare(state)

    backend.execute(Vector(Transfer(A, B, 20L, M, P), Transfer(A, B, 90L, M, P)), 0L).isLeft shouldBe true
    backend.version shouldBe 0L
    backend.snapshot.balances shouldBe Map(A -> 50L)
  }
