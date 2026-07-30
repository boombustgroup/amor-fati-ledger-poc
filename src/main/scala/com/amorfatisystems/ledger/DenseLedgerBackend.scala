package com.amorfatisystems.ledger

/** Evidence representation requested by the caller for a dense execution. */
enum ExecutionEvidenceMode:
  case TransferLog
  case AggregatedByMechanism

final case class AggregatedTransfer(
    currency: CurrencyId,
    from: AccountId,
    to: AccountId,
    mechanism: MechanismId,
    amount: Long
)

/** Dense, index-resolved execution backend with staged atomic commits. */
final class DenseLedgerBackend private (
    private var topology: LedgerTopology,
    private var indexByAccount: Map[AccountId, Int],
    private var accountByIndex: Vector[AccountId],
    private var balances: Array[Long],
    private var currentVersion: Long,
    val preparedCapacity: Int
):
  def version: Long = currentVersion

  def create(account: AccountId, metadata: AccountMetadata, initialBalance: Long): Either[ExecutionRejection, LedgerState] =
    LedgerStateLifecycle
      .create(snapshot, account, metadata, initialBalance)
      .left
      .map(_ => ExecutionRejection(None, ExecutionRejectionReason.LifecycleViolation, currentVersion))
      .map { next =>
        install(next)
        snapshot
      }

  def close(account: AccountId): Either[ExecutionRejection, LedgerState] =
    LedgerStateLifecycle
      .close(snapshot, account)
      .left
      .map(_ => ExecutionRejection(None, ExecutionRejectionReason.LifecycleViolation, currentVersion))
      .map { next =>
        install(next)
        snapshot
      }

  private def install(state: LedgerState): Unit =
    topology = state.topology
    accountByIndex = topology.accounts.keys.toVector.sortBy(AccountId.value)
    indexByAccount = accountByIndex.zipWithIndex.toMap
    val nextBalances = new Array[Long](preparedCapacity)
    accountByIndex.indices.foreach(index => nextBalances(index) = state.balances.getOrElse(accountByIndex(index), 0L))
    balances = nextBalances
    currentVersion = state.version

  private def rejectionReason(transfer: Transfer, fromIndex: Int, toIndex: Int, preflight: Array[Long]): ExecutionRejectionReason =
    (topology.metadata(transfer.from), topology.metadata(transfer.to)) match
      case (Right(from), Right(to)) if from.currency != to.currency => ExecutionRejectionReason.CurrencyMismatch
      case (Right(from), _) if !from.canDebit                       => ExecutionRejectionReason.PermissionDenied
      case (_, Right(to)) if !to.canCredit                          => ExecutionRejectionReason.PermissionDenied
      case _ =>
        val nextFrom = BigInt(preflight(fromIndex)) - BigInt(transfer.amount)
        val nextTo   = BigInt(preflight(toIndex)) + BigInt(transfer.amount)
        if !nextFrom.isValidLong || !nextTo.isValidLong then ExecutionRejectionReason.Overflow
        else ExecutionRejectionReason.Bounds

  def snapshot: LedgerState =
    val visible = accountByIndex.iterator.zip(balances.iterator).filter(_._2 != 0L).toMap
    LedgerState.make(topology, visible, currentVersion, preparedCapacity)

  def execute(
      transfers: Vector[Transfer],
      expectedVersion: Long,
      mode: ExecutionEvidenceMode = ExecutionEvidenceMode.TransferLog
  ): Either[ExecutionRejection, (LedgerState, ExecutionEvidence)] =
    if currentVersion != expectedVersion then Left(ExecutionRejection(None, ExecutionRejectionReason.VersionMismatch, currentVersion))
    else if transfers.isEmpty then
      val evidence: ExecutionEvidence = mode match
        case ExecutionEvidenceMode.TransferLog           => TransferLogEvidence.create(Vector.empty, 0L, 0L, currentVersion, currentVersion)
        case ExecutionEvidenceMode.AggregatedByMechanism => AggregatedEvidence.create(Vector.empty, 0L, 0L, currentVersion, currentVersion)
      Right((snapshot, evidence))
    else
      val preflight                           = balances.clone()
      val prepared                            = scala.collection.mutable.ArrayBuffer.empty[(Int, Int, Long, Transfer)]
      var failure: Option[ExecutionRejection] = None
      transfers.iterator.zipWithIndex.takeWhile(_ => failure.isEmpty).foreach { case (transfer, position) =>
        (for
          fromIndex <- indexByAccount.get(transfer.from).toRight(s"Unknown source at position $position")
          toIndex   <- indexByAccount.get(transfer.to).toRight(s"Unknown target at position $position")
          _         <- TransferValidator.validate(topology, transfer)
          from      <- topology.metadata(transfer.from)
          to        <- topology.metadata(transfer.to)
          nextFrom = BigInt(preflight(fromIndex)) - BigInt(transfer.amount)
          nextTo   = BigInt(preflight(toIndex)) + BigInt(transfer.amount)
          _ <- Either.cond(nextFrom.isValidLong && nextTo.isValidLong, (), s"Overflow at position $position")
          _ <- Either.cond(from.accepts(nextFrom.toLong), (), s"Source bounds at position $position")
          _ <- Either.cond(to.accepts(nextTo.toLong), (), s"Target bounds at position $position")
        yield (fromIndex, toIndex, nextFrom.toLong, nextTo.toLong)) match
          case Left(_) =>
            val fromIndex = indexByAccount.getOrElse(transfer.from, 0)
            val toIndex   = indexByAccount.getOrElse(transfer.to, 0)
            failure = Some(ExecutionRejection(Some(position), rejectionReason(transfer, fromIndex, toIndex, preflight), currentVersion))
          case Right((fromIndex, toIndex, nextFrom, nextTo)) =>
            preflight(fromIndex) = nextFrom
            preflight(toIndex) = nextTo
            prepared += ((fromIndex, toIndex, transfer.amount, transfer))
      }
      failure match
        case Some(error) => Left(error)
        case None =>
          val staged  = balances.clone()
          val applied = scala.collection.mutable.ArrayBuffer.empty[Transfer]
          prepared.foreach { case (fromIndex, toIndex, amount, transfer) =>
            staged(fromIndex) = Math.subtractExact(staged(fromIndex), amount)
            staged(toIndex) = Math.addExact(staged(toIndex), amount)
            applied += transfer
          }
          val nextVersion = Math.addExact(currentVersion, 1L)
          val evidenceEither: Either[ExecutionRejection, ExecutionEvidence] = mode match
            case ExecutionEvidenceMode.TransferLog =>
              val total = applied.foldLeft(BigInt(0))((sum, transfer) => sum + transfer.amount)
              if total.isValidLong then
                Right(TransferLogEvidence.create(applied.toVector, total.toLong, total.toLong, currentVersion, nextVersion))
              else Left(ExecutionRejection(None, ExecutionRejectionReason.Overflow, currentVersion))
            case ExecutionEvidenceMode.AggregatedByMechanism =>
              val grouped = scala.collection.mutable.Map.empty[(CurrencyId, AccountId, AccountId, MechanismId), BigInt]
              applied.foreach { transfer =>
                val key = (topology.accounts(transfer.from).currency, transfer.from, transfer.to, transfer.mechanism)
                grouped.update(key, grouped.getOrElse(key, BigInt(0)) + BigInt(transfer.amount))
              }
              val total = grouped.values.foldLeft(BigInt(0))((sum, amount) => sum + amount)
              if !total.isValidLong || grouped.values.exists(!_.isValidLong) then
                Left(ExecutionRejection(None, ExecutionRejectionReason.Overflow, currentVersion))
              else
                Right(
                  AggregatedEvidence.create(
                    grouped.toVector.map { case ((currency, from, to, mechanism), amount) =>
                      AggregatedTransfer(currency, from, to, mechanism, amount.toLong)
                    },
                    total.toLong,
                    total.toLong,
                    currentVersion,
                    nextVersion
                  )
                )
          evidenceEither.map { evidence =>
            balances = staged
            currentVersion = nextVersion
            (snapshot, evidence)
          }

object DenseLedgerBackend:
  def prepare(state: LedgerState): DenseLedgerBackend =
    val accounts = state.topology.accounts.keys.toVector.sortBy(AccountId.value)
    val indexes  = accounts.zipWithIndex.toMap
    val values   = new Array[Long](state.preparedCapacity)
    accounts.indices.foreach(index => values(index) = state.balances.getOrElse(accounts(index), 0L))
    new DenseLedgerBackend(state.topology, indexes, accounts, values, state.version, state.preparedCapacity)
