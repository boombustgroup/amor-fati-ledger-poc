package com.amorfatisystems.ledger

/** A validated account-to-account operation for the generic kernel.
  *
  * Amounts are signed ledger units but an individual transfer is always non-negative; direction is represented by `from` and `to`.
  * Mechanism and period are opaque audit metadata. Currency compatibility, account permissions, and balance bounds are checked by
  * [[TransferValidator]] and [[TransferExecutor]].
  */
final case class Transfer(from: AccountId, to: AccountId, amount: Long, mechanism: MechanismId, period: PeriodId):
  require(from != to, "Self-transfer is not permitted")
  require(amount >= 0L, "Transfer amount must be non-negative")

sealed trait ExecutionEvidence:
  def applied: Vector[Transfer]
  def debitTotal: Long
  def creditTotal: Long
  def inputVersion: Long
  def outputVersion: Long
  def snapshotVersion: Long = outputVersion

/** Immutable transfer-log evidence emitted after checked execution. */
final case class TransferLogEvidence private (
    applied: Vector[Transfer],
    debitTotal: Long,
    creditTotal: Long,
    inputVersion: Long,
    outputVersion: Long
) extends ExecutionEvidence:
  require(debitTotal >= 0L && creditTotal >= 0L)
  require(debitTotal == creditTotal)
  require(applied.foldLeft(BigInt(0))(_ + _.amount) == BigInt(debitTotal))

object TransferLogEvidence:
  def create(applied: Vector[Transfer], debitTotal: Long, creditTotal: Long, inputVersion: Long, outputVersion: Long): TransferLogEvidence =
    new TransferLogEvidence(applied, debitTotal, creditTotal, inputVersion, outputVersion)

/** Aggregated evidence grouped by currency, accounts, and mechanism. */
final case class AggregatedEvidence private (
    groups: Vector[AggregatedTransfer],
    debitTotal: Long,
    creditTotal: Long,
    inputVersion: Long,
    outputVersion: Long
) extends ExecutionEvidence:
  val applied: Vector[Transfer] = Vector.empty
  require(debitTotal >= 0L && creditTotal >= 0L)
  require(debitTotal == creditTotal)

object AggregatedEvidence:
  def create(
      groups: Vector[AggregatedTransfer],
      debitTotal: Long,
      creditTotal: Long,
      inputVersion: Long,
      outputVersion: Long
  ): AggregatedEvidence =
    new AggregatedEvidence(groups, debitTotal, creditTotal, inputVersion, outputVersion)

object ExecutionEvidence:
  /** Construct evidence only when all supplied totals agree with `applied`. */
  def apply(applied: Vector[Transfer], debitTotal: Long, creditTotal: Long, inputVersion: Long, outputVersion: Long): ExecutionEvidence =
    TransferLogEvidence.create(applied, debitTotal, creditTotal, inputVersion, outputVersion)

  /** Legacy reference-helper constructor where validation and commit share one stamp. */
  def apply(applied: Vector[Transfer], debitTotal: Long, creditTotal: Long, snapshotVersion: Long): ExecutionEvidence =
    apply(applied, debitTotal, creditTotal, snapshotVersion, snapshotVersion)

object TransferValidator:
  /** Validate topology-level prerequisites without changing balances. */
  def validate(topology: LedgerTopology, transfer: Transfer): Either[String, Unit] =
    for
      from <- topology.metadata(transfer.from)
      to   <- topology.metadata(transfer.to)
      _    <- Either.cond(from.currency == to.currency, (), "Transfer currency mismatch")
      _    <- Either.cond(from.canDebit, (), "Source account cannot be debited")
      _    <- Either.cond(to.canCredit, (), "Target account cannot be credited")
    yield ()

object TransferExecutor:
  /** Apply one transfer immutably, returning the new balances and checked evidence. */
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

  /** Apply transfers in order; any failure returns no partial state or evidence. */
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
