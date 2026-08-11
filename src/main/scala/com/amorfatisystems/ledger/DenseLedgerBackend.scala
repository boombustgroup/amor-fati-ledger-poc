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

/** Dense, index-resolved execution backend with staged atomic commits.
  *
  * This backend is mutable and requires single-threaded ownership. Version checks do not isolate concurrent callers because validation and
  * commit are unsynchronized.
  */
final class DenseLedgerBackend private (
    private var topology: LedgerTopology,
    private val indexByAccount: scala.collection.mutable.LongMap[Int],
    private val accountByIndex: Array[AccountId],
    private val active: Array[Boolean],
    private var size: Int,
    private var balances: Array[Long],
    private var currentVersion: Long,
    val preparedCapacity: Int,
    val id: LedgerStateId
):
  private val freeIndexes = scala.collection.mutable.ArrayDeque.empty[Int]
  /** Reused, index-addressed staging workspace for one execution. An epoch
    * marks the entries visible to the current commit, avoiding both a full
    * balance clone and the former linear scan through touched accounts.
    */
  private val stagingEpochs          = new Array[Int](preparedCapacity)
  private val stagingBalances        = new Array[Long](preparedCapacity)
  private val touchedStagingIndexes  = new Array[Int](preparedCapacity)
  private var currentStagingEpoch    = 0

  def version: Long = currentVersion

  def create(
      account: AccountId,
      metadata: AccountMetadata,
      initialBalance: Long,
      expectedVersion: Long = currentVersion
  ): Either[ExecutionRejection, Long] =
    if expectedVersion != currentVersion then Left(ExecutionRejection(None, ExecutionRejectionReason.VersionMismatch, currentVersion))
    else if indexByAccount.contains(AccountId.value(account).toLong) || (freeIndexes.isEmpty && size >= preparedCapacity) || !metadata
        .accepts(
          initialBalance
        )
    then Left(ExecutionRejection(None, ExecutionRejectionReason.LifecycleViolation, currentVersion))
    else
      AccountLifecycle
        .create(topology, account, metadata)
        .left
        .map(_ => ExecutionRejection(None, ExecutionRejectionReason.LifecycleViolation, currentVersion))
        .map { nextTopology =>
          topology = nextTopology
          val slot = freeIndexes.removeHeadOption().getOrElse { val appended = size; size += 1; appended }
          accountByIndex(slot) = account
          active(slot) = true
          indexByAccount.update(AccountId.value(account).toLong, slot)
          balances(slot) = initialBalance
          currentVersion = Math.addExact(currentVersion, 1L)
          currentVersion
        }

  def close(account: AccountId, expectedVersion: Long = currentVersion): Either[ExecutionRejection, Long] =
    if expectedVersion != currentVersion then Left(ExecutionRejection(None, ExecutionRejectionReason.VersionMismatch, currentVersion))
    else
      indexByAccount.get(AccountId.value(account).toLong) match
        case None => Left(ExecutionRejection(None, ExecutionRejectionReason.LifecycleViolation, currentVersion))
        case Some(index) if balances(index) != 0L =>
          Left(ExecutionRejection(None, ExecutionRejectionReason.LifecycleViolation, currentVersion))
        case Some(index) =>
          topology = LedgerTopology(topology.accounts - account)
          indexByAccount.remove(AccountId.value(account).toLong)
          active(index) = false
          balances(index) = 0L
          freeIndexes.addOne(index)
          currentVersion = Math.addExact(currentVersion, 1L)
          Right(currentVersion)

  private def rejectionReason(
      transfer: Transfer,
      fromIndex: Int,
      toIndex: Int,
      fromBalance: Long,
      toBalance: Long
  ): ExecutionRejectionReason =
    (topology.metadata(transfer.from), topology.metadata(transfer.to)) match
      case (Right(from), Right(to)) if from.currency != to.currency => ExecutionRejectionReason.CurrencyMismatch
      case (Right(from), _) if !from.canDebit                       => ExecutionRejectionReason.PermissionDenied
      case (_, Right(to)) if !to.canCredit                          => ExecutionRejectionReason.PermissionDenied
      case _ =>
        if subtractExact(fromBalance, transfer.amount).isEmpty || addExact(toBalance, transfer.amount).isEmpty then
          ExecutionRejectionReason.Overflow
        else ExecutionRejectionReason.Bounds

  private def subtractExact(left: Long, right: Long): Option[Long] =
    try Some(Math.subtractExact(left, right))
    catch case _: ArithmeticException => None

  private def addExact(left: Long, right: Long): Option[Long] =
    try Some(Math.addExact(left, right))
    catch case _: ArithmeticException => None

  private def beginStagingEpoch(): Int =
    if currentStagingEpoch == Int.MaxValue then
      java.util.Arrays.fill(stagingEpochs, 0)
      currentStagingEpoch = 1
    else currentStagingEpoch += 1
    currentStagingEpoch

  def snapshot: LedgerState =
    val visible = (0 until size).iterator.filter(active).map(index => accountByIndex(index) -> balances(index)).filter(_._2 != 0L).toMap
    LedgerState.make(topology, visible, currentVersion, preparedCapacity, id)

  def execute(
      transfers: Vector[Transfer],
      expectedVersion: Long,
      mode: ExecutionEvidenceMode = ExecutionEvidenceMode.TransferLog
  ): Either[ExecutionRejection, ExecutionEvidence] =
    if currentVersion != expectedVersion then Left(ExecutionRejection(None, ExecutionRejectionReason.VersionMismatch, currentVersion))
    else if transfers.isEmpty then
      val evidence: ExecutionEvidence = mode match
        case ExecutionEvidenceMode.TransferLog           => TransferLogEvidence.create(Vector.empty, 0L, 0L, currentVersion, currentVersion)
        case ExecutionEvidenceMode.AggregatedByMechanism => AggregatedEvidence.create(Vector.empty, 0L, 0L, currentVersion, currentVersion)
      Right(evidence)
    else
      /** Stages only accounts touched by this execution. The workspace is
        * reused and indexed by dense account slot, so lookup stays O(1) even
        * when one batch touches many distinct accounts.
        */
      val stagingEpoch = beginStagingEpoch()
      var stagedCount  = 0
      def stagedBalance(index: Int): Long =
        if stagingEpochs(index) == stagingEpoch then stagingBalances(index)
        else
          stagingEpochs(index) = stagingEpoch
          stagingBalances(index) = balances(index)
          touchedStagingIndexes(stagedCount) = index
          stagedCount += 1
          balances(index)
      def writeStaged(index: Int, value: Long): Unit =
        if stagingEpochs(index) != stagingEpoch then throw IllegalStateException(s"account index $index was not staged")
        stagingBalances(index) = value
      val prepared                            = scala.collection.mutable.ArrayBuffer.empty[Transfer]
      var failure: Option[ExecutionRejection] = None
      transfers.iterator.zipWithIndex.takeWhile(_ => failure.isEmpty).foreach { case (transfer, position) =>
        (for
          fromIndex <- indexByAccount.get(AccountId.value(transfer.from).toLong).toRight(s"Unknown source at position $position")
          toIndex   <- indexByAccount.get(AccountId.value(transfer.to).toLong).toRight(s"Unknown target at position $position")
          _         <- TransferValidator.validate(topology, transfer)
          from      <- topology.metadata(transfer.from)
          to        <- topology.metadata(transfer.to)
          fromBalance = stagedBalance(fromIndex)
          toBalance   = stagedBalance(toIndex)
          nextFrom <- subtractExact(fromBalance, transfer.amount).toRight(s"Overflow at position $position")
          nextTo   <- addExact(toBalance, transfer.amount).toRight(s"Overflow at position $position")
          _        <- Either.cond(from.accepts(nextFrom), (), s"Source bounds at position $position")
          _        <- Either.cond(to.accepts(nextTo), (), s"Target bounds at position $position")
        yield (fromIndex, toIndex, nextFrom, nextTo)) match
          case Left(_) =>
            val reason =
              if !indexByAccount
                  .contains(AccountId.value(transfer.from).toLong) || !indexByAccount.contains(AccountId.value(transfer.to).toLong)
              then ExecutionRejectionReason.LifecycleViolation
              else
                val fromIndex = indexByAccount(AccountId.value(transfer.from).toLong)
                val toIndex   = indexByAccount(AccountId.value(transfer.to).toLong)
                rejectionReason(transfer, fromIndex, toIndex, stagedBalance(fromIndex), stagedBalance(toIndex))
            failure = Some(ExecutionRejection(Some(position), reason, currentVersion))
          case Right((fromIndex, toIndex, nextFrom, nextTo)) =>
            writeStaged(fromIndex, nextFrom)
            writeStaged(toIndex, nextTo)
            prepared += transfer
      }
      failure match
        case Some(error) => Left(error)
        case None =>
          val nextVersion = Math.addExact(currentVersion, 1L)
          val evidenceEither: Either[ExecutionRejection, ExecutionEvidence] = mode match
            case ExecutionEvidenceMode.TransferLog =>
              var total: Option[Long] = Some(0L)
              prepared.foreach(transfer => total = total.flatMap(value => addExact(value, transfer.amount)))
              total match
                case Some(value) => Right(TransferLogEvidence.create(prepared.toVector, value, value, currentVersion, nextVersion))
                case None        => Left(ExecutionRejection(None, ExecutionRejectionReason.Overflow, currentVersion))
            case ExecutionEvidenceMode.AggregatedByMechanism =>
              val grouped = scala.collection.mutable.Map.empty[(CurrencyId, AccountId, AccountId, MechanismId), BigInt]
              prepared.foreach { transfer =>
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
            var offset = 0
            while offset < stagedCount do
              balances(touchedStagingIndexes(offset)) = stagingBalances(touchedStagingIndexes(offset))
              offset += 1
            currentVersion = nextVersion
            evidence
          }

object DenseLedgerBackend:
  def prepare(state: LedgerState): DenseLedgerBackend =
    val accounts = state.topology.accounts.keys.toVector.sortBy(AccountId.value)
    val indexes  = scala.collection.mutable.LongMap.empty[Int]
    accounts.zipWithIndex.foreach { case (account, index) => indexes.update(AccountId.value(account).toLong, index) }
    val values       = new Array[Long](state.preparedCapacity)
    val accountArray = new Array[AccountId](state.preparedCapacity)
    val active       = new Array[Boolean](state.preparedCapacity)
    accounts.zipWithIndex.foreach { case (account, index) => accountArray(index) = account; active(index) = true }
    accounts.indices.foreach(index => values(index) = state.balances.getOrElse(accounts(index), 0L))
    new DenseLedgerBackend(
      state.topology,
      indexes,
      accountArray,
      active,
      accounts.size,
      values,
      state.version,
      state.preparedCapacity,
      LedgerStateId.fresh()
    )
