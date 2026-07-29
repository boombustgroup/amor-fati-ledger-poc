package com.amorfatisystems.ledger

enum AccountLifecycleError:
  case AlreadyExists(account: AccountId)
  case UnknownAccount(account: AccountId)
  case NonZeroBalance(account: AccountId, balance: Long)

/** Explicit account creation and closure operations. */
object AccountLifecycle:
  def create(topology: LedgerTopology, account: AccountId, metadata: AccountMetadata): Either[AccountLifecycleError, LedgerTopology] =
    if topology.accounts.contains(account) then Left(AccountLifecycleError.AlreadyExists(account))
    else Right(LedgerTopology(topology.accounts.updated(account, metadata)))

  def createWithInitialBalance(
      topology: LedgerTopology,
      balances: Map[AccountId, Long],
      account: AccountId,
      metadata: AccountMetadata,
      initialBalance: Long
  ): Either[AccountLifecycleError, (LedgerTopology, Map[AccountId, Long])] =
    create(topology, account, metadata).map(nextTopology =>
      (nextTopology, balances.updated(account, initialBalance))
    )

  def close(topology: LedgerTopology, balances: Map[AccountId, Long], account: AccountId): Either[AccountLifecycleError, LedgerTopology] =
    topology.accounts.get(account) match
      case None => Left(AccountLifecycleError.UnknownAccount(account))
      case Some(_) =>
        val balance = balances.getOrElse(account, 0L)
        if balance != 0L then Left(AccountLifecycleError.NonZeroBalance(account, balance))
        else Right(LedgerTopology(topology.accounts - account))
