package com.amorfatisystems.ledger

/** A validated account-to-account operation for the generic kernel. */
final case class Transfer(from: AccountId, to: AccountId, amount: Long, mechanism: MechanismId, period: Long):
  require(from != to, "Self-transfer is not permitted")
  require(amount >= 0L, "Transfer amount must be non-negative")

final case class ExecutionEvidence(
    applied: Vector[Transfer],
    debitTotal: Long,
    creditTotal: Long,
    snapshotVersion: Long
):
  require(debitTotal == creditTotal)

object TransferValidator:
  def validate(topology: LedgerTopology, transfer: Transfer): Either[String, Unit] =
    for
      from <- topology.metadata(transfer.from)
      to   <- topology.metadata(transfer.to)
      _ <- Either.cond(from.currency == to.currency, (), "Transfer currency mismatch")
      _ <- Either.cond(from.canDebit, (), "Source account cannot be debited")
      _ <- Either.cond(to.canCredit, (), "Target account cannot be credited")
    yield ()
