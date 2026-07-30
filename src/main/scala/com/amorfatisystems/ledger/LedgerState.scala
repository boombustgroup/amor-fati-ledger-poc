package com.amorfatisystems.ledger

/** One logical ledger snapshot at the semantic API boundary. */
final case class LedgerState private (
    topology: LedgerTopology,
    balances: Map[AccountId, Long],
    version: Long,
    preparedCapacity: Int
)

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
    else Right(make(topology, balances, 0L, capacity))

/** Atomic execution over a single logical snapshot. */
object LedgerStateExecutor:
  def execute(state: LedgerState, transfer: Transfer, expectedVersion: Long): Either[String, (LedgerState, ExecutionEvidence)] =
    if state.version != expectedVersion then Left(s"VersionMismatch: expected=$expectedVersion actual=${state.version}")
    else
      TransferExecutor.execute(state.topology, state.balances, transfer, state.version).map { case (nextBalances, _) =>
        val nextVersion = Math.addExact(state.version, 1L)
        val evidence    = ExecutionEvidence(Vector(transfer), transfer.amount, transfer.amount, state.version, nextVersion)
        (LedgerState.make(state.topology, nextBalances, nextVersion, state.preparedCapacity), evidence)
      }

  def executeSequence(
      state: LedgerState,
      transfers: Vector[Transfer],
      expectedVersion: Long
  ): Either[String, (LedgerState, ExecutionEvidence)] =
    if state.version != expectedVersion then Left(s"VersionMismatch: expected=$expectedVersion actual=${state.version}")
    else
      TransferExecutor.executeSequence(state.topology, state.balances, transfers, state.version).map { case (nextBalances, _) =>
        val nextVersion = Math.addExact(state.version, 1L)
        val total       = transfers.foldLeft(BigInt(0))((sum, transfer) => sum + transfer.amount)
        if !total.isValidLong then throw ArithmeticException("Transfer sequence evidence exceeds Long bounds")
        val evidence = ExecutionEvidence(transfers, total.toLong, total.toLong, state.version, nextVersion)
        (LedgerState.make(state.topology, nextBalances, nextVersion, state.preparedCapacity), evidence)
      }

object LedgerStateLifecycle:
  def create(state: LedgerState, account: AccountId, metadata: AccountMetadata, initialBalance: Long): Either[String, LedgerState] =
    if state.topology.accounts.contains(account) then Left(s"AlreadyExists: ${AccountId.value(account)}")
    else if state.topology.accounts.size >= state.preparedCapacity then Left("Lifecycle capacity exceeded")
    else if !metadata.accepts(initialBalance) then Left("Initial balance is outside account bounds")
    else
      AccountLifecycle.create(state.topology, account, metadata).left.map(_.toString).map { topology =>
        LedgerState.make(
          topology,
          state.balances.updated(account, initialBalance),
          Math.addExact(state.version, 1L),
          state.preparedCapacity
        )
      }

  def close(state: LedgerState, account: AccountId): Either[String, LedgerState] =
    AccountLifecycle.close(state.topology, state.balances, account).left.map(_.toString).map { topology =>
      LedgerState.make(topology, state.balances - account, Math.addExact(state.version, 1L), state.preparedCapacity)
    }
