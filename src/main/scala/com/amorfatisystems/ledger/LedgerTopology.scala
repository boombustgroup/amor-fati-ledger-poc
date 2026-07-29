package com.amorfatisystems.ledger

/** Validated account metadata supplied by the SFC-ABM topology. */
final case class LedgerTopology private[ledger] (accounts: Map[AccountId, AccountMetadata]):
  def metadata(account: AccountId): Either[String, AccountMetadata] =
    accounts.get(account).toRight(s"Unknown account: ${AccountId.value(account)}")

object LedgerTopology:
  def validate(accounts: Map[AccountId, AccountMetadata]): Either[String, LedgerTopology] =
    if accounts.isEmpty then Left("Ledger topology must declare at least one account")
    else Right(LedgerTopology(accounts))
