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
    private val topology: LedgerTopology,
    private val indexByAccount: Map[AccountId, Int],
    private val accountByIndex: Vector[AccountId],
    private var balances: Array[Long],
    private var currentVersion: Long,
    val preparedCapacity: Int
):
  def version: Long = currentVersion

  def snapshot: LedgerState =
    val visible = accountByIndex.iterator.zip(balances.iterator).filter(_._2 != 0L).toMap
    LedgerState.make(topology, visible, currentVersion, preparedCapacity)

  def execute(
      transfers: Vector[Transfer],
      expectedVersion: Long,
      mode: ExecutionEvidenceMode = ExecutionEvidenceMode.TransferLog
  ): Either[ExecutionRejection, (LedgerState, Either[Vector[Transfer], Vector[AggregatedTransfer]])] =
    if currentVersion != expectedVersion then Left(ExecutionRejection(None, ExecutionRejectionReason.VersionMismatch, currentVersion))
    else
      val staged                              = balances.clone()
      val applied                             = scala.collection.mutable.ArrayBuffer.empty[Transfer]
      var failure: Option[ExecutionRejection] = None
      transfers.iterator.zipWithIndex.takeWhile(_ => failure.isEmpty).foreach { case (transfer, position) =>
        (for
          fromIndex <- indexByAccount.get(transfer.from).toRight(s"Unknown source at position $position")
          toIndex   <- indexByAccount.get(transfer.to).toRight(s"Unknown target at position $position")
          _         <- TransferValidator.validate(topology, transfer)
          from      <- topology.metadata(transfer.from)
          to        <- topology.metadata(transfer.to)
          nextFrom = BigInt(staged(fromIndex)) - BigInt(transfer.amount)
          nextTo   = BigInt(staged(toIndex)) + BigInt(transfer.amount)
          _ <- Either.cond(nextFrom.isValidLong && nextTo.isValidLong, (), s"Overflow at position $position")
          _ <- Either.cond(from.accepts(nextFrom.toLong), (), s"Source bounds at position $position")
          _ <- Either.cond(to.accepts(nextTo.toLong), (), s"Target bounds at position $position")
        yield (fromIndex, toIndex, nextFrom.toLong, nextTo.toLong)) match
          case Left(_) => failure = Some(ExecutionRejection(Some(position), ExecutionRejectionReason.Bounds, currentVersion))
          case Right((fromIndex, toIndex, nextFrom, nextTo)) =>
            staged(fromIndex) = nextFrom
            staged(toIndex) = nextTo
            applied += transfer
      }
      failure match
        case Some(error) => Left(error)
        case None =>
          val nextVersion = Math.addExact(currentVersion, 1L)
          balances = staged
          currentVersion = nextVersion
          val evidence = mode match
            case ExecutionEvidenceMode.TransferLog => Left(applied.toVector)
            case ExecutionEvidenceMode.AggregatedByMechanism =>
              val grouped = applied.toVector.groupMapReduce(transfer =>
                (topology.accounts(transfer.from).currency, transfer.from, transfer.to, transfer.mechanism)
              )(_.amount)(_ + _)
              Right(grouped.toVector.map { case ((currency, from, to, mechanism), amount) =>
                AggregatedTransfer(currency, from, to, mechanism, amount)
              })
          Right((snapshot, evidence))

object DenseLedgerBackend:
  def prepare(state: LedgerState): DenseLedgerBackend =
    val accounts = state.topology.accounts.keys.toVector.sortBy(AccountId.value)
    val indexes  = accounts.zipWithIndex.toMap
    val values   = accounts.map(account => state.balances.getOrElse(account, 0L)).toArray
    new DenseLedgerBackend(state.topology, indexes, accounts, values, state.version, state.preparedCapacity)
