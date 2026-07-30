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
  require(groups.foldLeft(BigInt(0))((sum, group) => sum + group.amount) == BigInt(debitTotal))

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
  private def typedError(
      topology: LedgerTopology,
      balances: Map[AccountId, Long],
      transfer: Transfer,
      position: Option[Int],
      version: Long
  ): Either[ExecutionRejection, (AccountId, AccountId, Long, Long)] =
    for
      fromMetadata <- topology
        .metadata(transfer.from)
        .left
        .map(_ => ExecutionRejection(position, ExecutionRejectionReason.LifecycleViolation, version))
      toMetadata <- topology
        .metadata(transfer.to)
        .left
        .map(_ => ExecutionRejection(position, ExecutionRejectionReason.LifecycleViolation, version))
      _ <- Either.cond(
        fromMetadata.currency == toMetadata.currency,
        (),
        ExecutionRejection(position, ExecutionRejectionReason.CurrencyMismatch, version)
      )
      _ <- Either.cond(
        fromMetadata.canDebit && toMetadata.canCredit,
        (),
        ExecutionRejection(position, ExecutionRejectionReason.PermissionDenied, version)
      )
      fromBalance = balances.getOrElse(transfer.from, 0L)
      toBalance   = balances.getOrElse(transfer.to, 0L)
      nextFrom    = BigInt(fromBalance) - BigInt(transfer.amount)
      nextTo      = BigInt(toBalance) + BigInt(transfer.amount)
      _ <- Either.cond(
        nextFrom.isValidLong && nextTo.isValidLong,
        (),
        ExecutionRejection(position, ExecutionRejectionReason.Overflow, version)
      )
      _ <- Either.cond(
        fromMetadata.accepts(nextFrom.toLong) && toMetadata.accepts(nextTo.toLong),
        (),
        ExecutionRejection(position, ExecutionRejectionReason.Bounds, version)
      )
    yield (transfer.from, transfer.to, nextFrom.toLong, nextTo.toLong)

  def executeTyped(
      topology: LedgerTopology,
      balances: Map[AccountId, Long],
      transfer: Transfer,
      inputVersion: Long
  ): Either[ExecutionRejection, (Map[AccountId, Long], ExecutionEvidence)] =
    typedError(topology, balances, transfer, Some(0), inputVersion).map { case (from, to, nextFrom, nextTo) =>
      val nextVersion = Math.addExact(inputVersion, 1L)
      val next        = balances.updated(from, nextFrom).updated(to, nextTo).filter(_._2 != 0L)
      (next, ExecutionEvidence(Vector(transfer), transfer.amount, transfer.amount, inputVersion, nextVersion))
    }

  def executeSequenceTyped(
      topology: LedgerTopology,
      balances: Map[AccountId, Long],
      transfers: Vector[Transfer],
      inputVersion: Long
  ): Either[ExecutionRejection, (Map[AccountId, Long], ExecutionEvidence)] =
    if transfers.isEmpty then Right((balances, ExecutionEvidence(Vector.empty, 0L, 0L, inputVersion, inputVersion)))
    else
      transfers.zipWithIndex
        .foldLeft[Either[ExecutionRejection, (Map[AccountId, Long], Vector[Transfer])]](Right((balances, Vector.empty))) {
          case (state, (transfer, position)) =>
            state.flatMap { case (current, applied) =>
              typedError(topology, current, transfer, Some(position), inputVersion).map { case (from, to, nextFrom, nextTo) =>
                (current.updated(from, nextFrom).updated(to, nextTo), applied :+ transfer)
              }
            }
        }
        .flatMap { case (next, applied) =>
          val total = applied.foldLeft(BigInt(0))((sum, transfer) => sum + transfer.amount)
          if !total.isValidLong then Left(ExecutionRejection(None, ExecutionRejectionReason.Overflow, inputVersion))
          else
            val outputVersion = Math.addExact(inputVersion, 1L)
            Right((next.filter(_._2 != 0L), ExecutionEvidence(applied, total.toLong, total.toLong, inputVersion, outputVersion)))
        }

  /** Apply one transfer immutably, returning the new balances and checked evidence. */
  @deprecated("Use executeTyped for typed rejection reasons", "0.2.0")
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
    yield (
      next.filter(_._2 != 0L),
      ExecutionEvidence(Vector(transfer), transfer.amount, transfer.amount, snapshotVersion, Math.addExact(snapshotVersion, 1L))
    )

  /** Apply transfers in order; any failure returns no partial state or evidence. */
  @deprecated("Use executeSequenceTyped for typed rejection reasons", "0.2.0")
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
          Right((state.filter(_._2 != 0L), ExecutionEvidence(applied, total, total, snapshotVersion, Math.addExact(snapshotVersion, 1L))))
        catch case _: ArithmeticException => Left("Transfer sequence evidence exceeds Long bounds")
      }
