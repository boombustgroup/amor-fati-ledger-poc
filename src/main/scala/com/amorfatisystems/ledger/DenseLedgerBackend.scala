package com.amorfatisystems.ledger

/** Evidence representation requested by the caller for a dense execution. */
enum ExecutionEvidenceMode:
  case TransferLog
  case AggregatedByMechanism

/** Physical account address valid only for the dense backend that resolved it. Its underlying integer is intentionally not exposed to
  * callers.
  */
opaque type DenseLedgerAccountIndex = Int

object DenseLedgerAccountIndex:
  private[ledger] def fromOffset(value: Int): DenseLedgerAccountIndex = value
  private[ledger] def offset(index: DenseLedgerAccountIndex): Int     = index

/** Opaque identity carried by an indexed batch. It prevents a buffer prepared for one mutable backend from being applied to another backend
  * whose dense account slots happen to have the same numbers.
  */
final class DenseLedgerBackendBinding private[ledger] ()

/** Mutable only while a producer is filling it, then an immutable logical columnar batch for one immediate dense-ledger execution. The data
  * columns are deliberately primitive/index-addressed; semantic `Transfer` values are materialised only if transfer-log evidence is
  * requested after acceptance.
  */
final class DenseLedgerIndexedTransferBatch private (
    val binding: DenseLedgerBackendBinding,
    private[ledger] val sources: Array[DenseLedgerAccountIndex],
    private[ledger] val targets: Array[DenseLedgerAccountIndex],
    private[ledger] val amounts: Array[Long],
    private[ledger] val mechanisms: Array[MechanismId],
    private[ledger] val periods: Array[PeriodId]
):
  private var nextOffset  = 0
  private var sealedBatch = false

  def count: Int = amounts.length

  /** Appends one physical posting. A producer must call [[seal]] before the backend can execute the batch.
    */
  def append(
      source: DenseLedgerAccountIndex,
      target: DenseLedgerAccountIndex,
      amount: Long,
      mechanism: MechanismId,
      period: PeriodId
  ): Unit =
    require(!sealedBatch, "cannot append to a sealed dense ledger batch")
    require(nextOffset < count, "dense ledger batch capacity exceeded")
    sources(nextOffset) = source
    targets(nextOffset) = target
    amounts(nextOffset) = amount
    mechanisms(nextOffset) = mechanism
    periods(nextOffset) = period
    nextOffset += 1

  def seal(): DenseLedgerIndexedTransferBatch =
    require(nextOffset == count, s"dense ledger batch has $nextOffset postings but capacity is $count")
    sealedBatch = true
    this

  private[ledger] def isSealed: Boolean = sealedBatch

object DenseLedgerIndexedTransferBatch:
  def allocate(binding: DenseLedgerBackendBinding, count: Int): DenseLedgerIndexedTransferBatch =
    require(count >= 0, "dense ledger batch size must be non-negative")
    new DenseLedgerIndexedTransferBatch(
      binding,
      new Array[DenseLedgerAccountIndex](count),
      new Array[DenseLedgerAccountIndex](count),
      new Array[Long](count),
      new Array[MechanismId](count),
      new Array[PeriodId](count)
    )

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
    private val metadataByIndex: Array[AccountMetadata],
    private val active: Array[Boolean],
    private var size: Int,
    private var balances: Array[Long],
    private var currentVersion: Long,
    val preparedCapacity: Int,
    val id: LedgerStateId
):
  private val backendBinding = DenseLedgerBackendBinding()
  private val freeIndexes    = scala.collection.mutable.ArrayDeque.empty[Int]

  /** Reused, index-addressed staging workspace for one execution. An epoch marks the entries visible to the current commit, avoiding both a
    * full balance clone and the former linear scan through touched accounts.
    */
  private val stagingEpochs         = new Array[Int](preparedCapacity)
  private val stagingBalances       = new Array[Long](preparedCapacity)
  private val touchedStagingIndexes = new Array[Int](preparedCapacity)
  private var currentStagingEpoch   = 0

  def version: Long = currentVersion

  /** Returns the opaque binding required to assemble a raw indexed batch for this backend. The batch remains valid only for this backend
    * identity.
    */
  def indexedBatchBinding: DenseLedgerBackendBinding = backendBinding

  /** Resolves a semantic account once at a control-plane boundary. Hot loops retain only the returned opaque dense index.
    */
  def denseAccountIndex(account: AccountId): Option[DenseLedgerAccountIndex] =
    indexByAccount.get(AccountId.value(account).toLong).map(DenseLedgerAccountIndex.fromOffset)

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
          metadataByIndex(slot) = metadata
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
          metadataByIndex(index) = null
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

  /** Executes a sealed index-addressed batch without materialising a `Transfer` object per posting in the validation and balance-update
    * loop. Transfer-log evidence, when requested, is materialised only after the complete batch has passed validation.
    */
  def executeIndexed(
      batch: DenseLedgerIndexedTransferBatch,
      expectedVersion: Long,
      mode: ExecutionEvidenceMode = ExecutionEvidenceMode.TransferLog
  ): Either[ExecutionRejection, ExecutionEvidence] =
    if !(batch.binding eq backendBinding) then
      Left(ExecutionRejection(None, ExecutionRejectionReason.IndexedBatchBackendMismatch, currentVersion))
    else if !batch.isSealed then Left(ExecutionRejection(None, ExecutionRejectionReason.InvalidTransfer, currentVersion))
    else if currentVersion != expectedVersion then Left(ExecutionRejection(None, ExecutionRejectionReason.VersionMismatch, currentVersion))
    else if batch.count == 0 then
      val evidence: ExecutionEvidence = mode match
        case ExecutionEvidenceMode.TransferLog           => TransferLogEvidence.create(Vector.empty, 0L, 0L, currentVersion, currentVersion)
        case ExecutionEvidenceMode.AggregatedByMechanism => AggregatedEvidence.create(Vector.empty, 0L, 0L, currentVersion, currentVersion)
      Right(evidence)
    else
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

      var offset                              = 0
      var total                               = 0L
      var failure: Option[ExecutionRejection] = None
      while offset < batch.count && failure.isEmpty do
        val sourceIndex = DenseLedgerAccountIndex.offset(batch.sources(offset))
        val targetIndex = DenseLedgerAccountIndex.offset(batch.targets(offset))
        val amount      = batch.amounts(offset)
        if sourceIndex < 0 || sourceIndex >= size || targetIndex < 0 || targetIndex >= size || !active(sourceIndex) || !active(targetIndex)
        then failure = Some(ExecutionRejection(Some(offset), ExecutionRejectionReason.LifecycleViolation, currentVersion))
        else if sourceIndex == targetIndex || amount < 0L then
          failure = Some(ExecutionRejection(Some(offset), ExecutionRejectionReason.InvalidTransfer, currentVersion))
        else
          val sourceMetadata = metadataByIndex(sourceIndex)
          val targetMetadata = metadataByIndex(targetIndex)
          if sourceMetadata.currency != targetMetadata.currency then
            failure = Some(ExecutionRejection(Some(offset), ExecutionRejectionReason.CurrencyMismatch, currentVersion))
          else if !sourceMetadata.canDebit || !targetMetadata.canCredit then
            failure = Some(ExecutionRejection(Some(offset), ExecutionRejectionReason.PermissionDenied, currentVersion))
          else
            try
              val nextSource = Math.subtractExact(stagedBalance(sourceIndex), amount)
              val nextTarget = Math.addExact(stagedBalance(targetIndex), amount)
              total = Math.addExact(total, amount)
              if !sourceMetadata.accepts(nextSource) || !targetMetadata.accepts(nextTarget) then
                failure = Some(ExecutionRejection(Some(offset), ExecutionRejectionReason.Bounds, currentVersion))
              else
                writeStaged(sourceIndex, nextSource)
                writeStaged(targetIndex, nextTarget)
            catch
              case _: ArithmeticException =>
                failure = Some(ExecutionRejection(Some(offset), ExecutionRejectionReason.Overflow, currentVersion))
        offset += 1

      failure match
        case Some(error) => Left(error)
        case None =>
          try
            val nextVersion = Math.addExact(currentVersion, 1L)
            val evidence: ExecutionEvidence = mode match
              case ExecutionEvidenceMode.TransferLog =>
                val transfers      = Vector.newBuilder[Transfer]
                var evidenceOffset = 0
                while evidenceOffset < batch.count do
                  transfers += Transfer(
                    accountByIndex(DenseLedgerAccountIndex.offset(batch.sources(evidenceOffset))),
                    accountByIndex(DenseLedgerAccountIndex.offset(batch.targets(evidenceOffset))),
                    batch.amounts(evidenceOffset),
                    batch.mechanisms(evidenceOffset),
                    batch.periods(evidenceOffset)
                  )
                  evidenceOffset += 1
                TransferLogEvidence.create(transfers.result(), total, total, currentVersion, nextVersion)
              case ExecutionEvidenceMode.AggregatedByMechanism =>
                val grouped        = scala.collection.mutable.Map.empty[(CurrencyId, AccountId, AccountId, MechanismId), BigInt]
                var evidenceOffset = 0
                while evidenceOffset < batch.count do
                  val source = accountByIndex(DenseLedgerAccountIndex.offset(batch.sources(evidenceOffset)))
                  val target = accountByIndex(DenseLedgerAccountIndex.offset(batch.targets(evidenceOffset)))
                  val key = (
                    metadataByIndex(DenseLedgerAccountIndex.offset(batch.sources(evidenceOffset))).currency,
                    source,
                    target,
                    batch.mechanisms(evidenceOffset)
                  )
                  grouped.update(key, grouped.getOrElse(key, BigInt(0)) + BigInt(batch.amounts(evidenceOffset)))
                  evidenceOffset += 1
                val groupedTotal = grouped.values.foldLeft(BigInt(0))((sum, amount) => sum + amount)
                if !groupedTotal.isValidLong || grouped.values.exists(!_.isValidLong) then
                  return Left(ExecutionRejection(None, ExecutionRejectionReason.Overflow, currentVersion))
                AggregatedEvidence.create(
                  grouped.toVector.map { case ((currency, from, to, mechanism), amount) =>
                    AggregatedTransfer(currency, from, to, mechanism, amount.toLong)
                  },
                  groupedTotal.toLong,
                  groupedTotal.toLong,
                  currentVersion,
                  nextVersion
                )
            var stagedOffset = 0
            while stagedOffset < stagedCount do
              balances(touchedStagingIndexes(stagedOffset)) = stagingBalances(touchedStagingIndexes(stagedOffset))
              stagedOffset += 1
            currentVersion = nextVersion
            Right(evidence)
          catch case _: ArithmeticException => Left(ExecutionRejection(None, ExecutionRejectionReason.Overflow, currentVersion))

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
      /** Stages only accounts touched by this execution. The workspace is reused and indexed by dense account slot, so lookup stays O(1)
        * even when one batch touches many distinct accounts.
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
    val metadata     = new Array[AccountMetadata](state.preparedCapacity)
    val active       = new Array[Boolean](state.preparedCapacity)
    accounts.zipWithIndex.foreach { case (account, index) =>
      accountArray(index) = account
      metadata(index) = state.topology.accounts(account)
      active(index) = true
    }
    accounts.indices.foreach(index => values(index) = state.balances.getOrElse(accounts(index), 0L))
    new DenseLedgerBackend(
      state.topology,
      indexes,
      accountArray,
      metadata,
      active,
      accounts.size,
      values,
      state.version,
      state.preparedCapacity,
      LedgerStateId.fresh()
    )
