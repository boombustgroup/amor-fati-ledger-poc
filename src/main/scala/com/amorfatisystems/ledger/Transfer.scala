package com.amorfatisystems.ledger

/** A validated account-to-account operation for the generic kernel. */
final case class Transfer(from: AccountId, to: AccountId, amount: Long, mechanism: MechanismId, period: PeriodId):
  require(from != to, "Self-transfer is not permitted")
  require(amount >= 0L, "Transfer amount must be non-negative")

final case class ExecutionEvidence private (
    applied: Vector[Transfer],
    debitTotal: Long,
    creditTotal: Long,
    snapshotVersion: Long
):
  require(debitTotal >= 0L && creditTotal >= 0L)
  require(debitTotal == creditTotal)
  require(applied.foldLeft(BigInt(0))(_ + _.amount) == BigInt(debitTotal))

object ExecutionEvidence:
  def apply(applied: Vector[Transfer], debitTotal: Long, creditTotal: Long, snapshotVersion: Long): ExecutionEvidence =
    new ExecutionEvidence(applied, debitTotal, creditTotal, snapshotVersion)

object TransferValidator:
  def validate(topology: LedgerTopology, transfer: Transfer): Either[String, Unit] =
    for
      from <- topology.metadata(transfer.from)
      to   <- topology.metadata(transfer.to)
      _    <- Either.cond(from.currency == to.currency, (), "Transfer currency mismatch")
      _    <- Either.cond(from.canDebit, (), "Source account cannot be debited")
      _    <- Either.cond(to.canCredit, (), "Target account cannot be credited")
    yield ()

object TransferExecutor:
  def execute(
      topology: LedgerTopology,
      balances: Map[AccountId, Long],
      transfer: Transfer,
      snapshotVersion: Long
  ): Either[String, (Map[AccountId, Long], ExecutionEvidence)] =
    for
      _ <- TransferValidator.validate(topology, transfer)
      fromBalance = balances.getOrElse(transfer.from, 0L)
      toBalance   = balances.getOrElse(transfer.to, 0L)
      nextFrom    = BigInt(fromBalance) - BigInt(transfer.amount)
      nextTo      = BigInt(toBalance) + BigInt(transfer.amount)
      _ <- Either.cond(nextFrom.isValidLong && nextTo.isValidLong, (), "Transfer exceeds Long bounds")
      _ <- Either.cond(topology.accounts(transfer.from).accepts(nextFrom.toLong), (), "Source balance bounds reject debit")
      _ <- Either.cond(topology.accounts(transfer.to).accepts(nextTo.toLong), (), "Target balance bounds reject credit")
      next = balances.updated(transfer.from, nextFrom.toLong).updated(transfer.to, nextTo.toLong)
    yield (next, ExecutionEvidence(Vector(transfer), transfer.amount, transfer.amount, snapshotVersion))

  def executeSequence(
      topology: LedgerTopology,
      balances: Map[AccountId, Long],
      transfers: Vector[Transfer],
      snapshotVersion: Long
  ): Either[String, (Map[AccountId, Long], ExecutionEvidence)] =
    transfers
      .foldLeft[Either[String, (Map[AccountId, Long], Vector[Transfer])]](Right((balances, Vector.empty))) { case (stateEither, transfer) =>
        stateEither.flatMap { case (state, applied) =>
          execute(topology, state, transfer, snapshotVersion).map { case (next, _) => (next, applied :+ transfer) }
        }
      }
      .flatMap { case (state, applied) =>
        try
          val total = applied.foldLeft(0L)((sum, transfer) => Math.addExact(sum, transfer.amount))
          Right((state, ExecutionEvidence(applied, total, total, snapshotVersion)))
        catch case _: ArithmeticException => Left("Transfer sequence evidence exceeds Long bounds")
      }
