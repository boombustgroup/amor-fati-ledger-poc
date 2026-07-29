package com.amorfatisystems.ledger

/** Rejections returned by topology/account lifecycle transitions. */
enum AccountLifecycleError:
  case AlreadyExists(account: AccountId)
  case UnknownAccount(account: AccountId)
  case NonZeroBalance(account: AccountId, balance: Long)
  case InvalidMetadata(account: AccountId)
  case InitialBalanceOutOfBounds(account: AccountId, balance: Long)

/** Explicit account creation and closure operations.
  *
  * Lifecycle changes are immutable: a successful operation returns a new topology (and, for creation with an opening balance, a new balance
  * map). A failed validation never publishes a partially updated topology or balance state.
  */
object AccountLifecycle:
  /** Add an account after checking identity uniqueness and metadata bounds. */
  def create(topology: LedgerTopology, account: AccountId, metadata: AccountMetadata): Either[AccountLifecycleError, LedgerTopology] =
    if topology.accounts.contains(account) then Left(AccountLifecycleError.AlreadyExists(account))
    else if metadata.minBalance.exists(min => metadata.maxBalance.exists(_ < min)) then Left(AccountLifecycleError.InvalidMetadata(account))
    else Right(LedgerTopology(topology.accounts.updated(account, metadata)))

  /** Add an account and assign an opening balance accepted by its metadata bounds. */
  def createWithInitialBalance(
      topology: LedgerTopology,
      balances: Map[AccountId, Long],
      account: AccountId,
      metadata: AccountMetadata,
      initialBalance: Long
  ): Either[AccountLifecycleError, (LedgerTopology, Map[AccountId, Long])] =
    if topology.accounts.contains(account) then Left(AccountLifecycleError.AlreadyExists(account))
    else if !metadata.accepts(initialBalance) then Left(AccountLifecycleError.InitialBalanceOutOfBounds(account, initialBalance))
    else create(topology, account, metadata).map(nextTopology => (nextTopology, balances.updated(account, initialBalance)))

  /** Remove an account only after confirming it exists and has no outstanding balance. */
  def close(topology: LedgerTopology, balances: Map[AccountId, Long], account: AccountId): Either[AccountLifecycleError, LedgerTopology] =
    topology.accounts.get(account) match
      case None => Left(AccountLifecycleError.UnknownAccount(account))
      case Some(_) =>
        val balance = balances.getOrElse(account, 0L)
        if balance != 0L then Left(AccountLifecycleError.NonZeroBalance(account, balance))
        else Right(LedgerTopology(topology.accounts - account))
