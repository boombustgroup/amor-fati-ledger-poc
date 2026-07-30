package com.amorfatisystems.ledger

/** One logical ledger snapshot at the semantic API boundary. */
final case class LedgerState private (
    topology: LedgerTopology,
    balances: Map[AccountId, Long],
    version: Long,
    preparedCapacity: Int
)

enum ExecutionRejectionReason:
  case Overflow
  case Bounds
  case CurrencyMismatch
  case PermissionDenied
  case AtomicityViolation
  case VersionMismatch
  case LifecycleViolation

final case class ExecutionRejection(position: Option[Int], reason: ExecutionRejectionReason, snapshotVersion: Long)

object LedgerState:
  private[ledger] def make(topology: LedgerTopology, balances: Map[AccountId, Long], version: Long, preparedCapacity: Int): LedgerState =
    new LedgerState(topology, balances, version, preparedCapacity)

  def initial(
      topology: LedgerTopology,
      balances: Map[AccountId, Long] = Map.empty,
      preparedCapacity: Int = 0
  ): Either[String, LedgerState] =
    val capacity = Math.max(preparedCapacity, topology.accounts.size)
    if preparedCapacity < 0 then Left("Prepared capacity must be non-negative")
    else if balances.keys.exists(account => !topology.accounts.contains(account)) then Left("Balances contain an unknown account")
    else if balances.exists { case (account, balance) => !topology.accounts(account).accepts(balance) } then
      Left("Balances contain a value outside account bounds")
    else Right(make(topology, balances.filter(_._2 != 0L), 0L, capacity))

/** Atomic execution over a single logical snapshot. */
object LedgerStateExecutor:
  def execute(state: LedgerState, transfer: Transfer, expectedVersion: Long): Either[ExecutionRejection, (LedgerState, ExecutionEvidence)] =
    if state.version != expectedVersion then Left(ExecutionRejection(None, ExecutionRejectionReason.VersionMismatch, state.version))
    else
      TransferExecutor
        .executeTyped(state.topology, state.balances, transfer, state.version)
        .map { case (nextBalances, _) =>
          val nextVersion = Math.addExact(state.version, 1L)
          val evidence    = ExecutionEvidence(Vector(transfer), transfer.amount, transfer.amount, state.version, nextVersion)
          (LedgerState.make(state.topology, nextBalances, nextVersion, state.preparedCapacity), evidence)
        }
        .left
        .map(rejection => rejection.copy(position = Some(0)))

  def executeSequence(
      state: LedgerState,
      transfers: Vector[Transfer],
      expectedVersion: Long
  ): Either[ExecutionRejection, (LedgerState, ExecutionEvidence)] =
    if state.version != expectedVersion then Left(ExecutionRejection(None, ExecutionRejectionReason.VersionMismatch, state.version))
    else if transfers.isEmpty then Right((state, ExecutionEvidence(Vector.empty, 0L, 0L, state.version, state.version)))
    else
      val total = transfers.foldLeft(BigInt(0))((sum, transfer) => sum + transfer.amount)
      if !total.isValidLong then Left(ExecutionRejection(None, ExecutionRejectionReason.Overflow, state.version))
      else
        TransferExecutor
          .executeSequenceTyped(state.topology, state.balances, transfers, state.version)
          .map { case (nextBalances, _) =>
            val nextVersion = Math.addExact(state.version, 1L)
            val evidence    = ExecutionEvidence(transfers, total.toLong, total.toLong, state.version, nextVersion)
            (LedgerState.make(state.topology, nextBalances, nextVersion, state.preparedCapacity), evidence)
          }
          .left
          .map(identity)

object LedgerStateLifecycle:
  def create(
      state: LedgerState,
      account: AccountId,
      metadata: AccountMetadata,
      initialBalance: Long
  ): Either[ExecutionRejection, LedgerState] =
    if state.topology.accounts.contains(account) then
      Left(ExecutionRejection(None, ExecutionRejectionReason.LifecycleViolation, state.version))
    else if state.topology.accounts.size >= state.preparedCapacity then
      Left(ExecutionRejection(None, ExecutionRejectionReason.LifecycleViolation, state.version))
    else if !metadata.accepts(initialBalance) then
      Left(ExecutionRejection(None, ExecutionRejectionReason.LifecycleViolation, state.version))
    else
      AccountLifecycle
        .create(state.topology, account, metadata)
        .left
        .map(_ => ExecutionRejection(None, ExecutionRejectionReason.LifecycleViolation, state.version))
        .map { topology =>
          LedgerState.make(
            topology,
            state.balances.updated(account, initialBalance),
            Math.addExact(state.version, 1L),
            state.preparedCapacity
          )
        }

  def close(state: LedgerState, account: AccountId): Either[ExecutionRejection, LedgerState] =
    AccountLifecycle
      .close(state.topology, state.balances, account)
      .left
      .map(_ => ExecutionRejection(None, ExecutionRejectionReason.LifecycleViolation, state.version))
      .map { topology =>
        LedgerState.make(topology, state.balances - account, Math.addExact(state.version, 1L), state.preparedCapacity)
      }
