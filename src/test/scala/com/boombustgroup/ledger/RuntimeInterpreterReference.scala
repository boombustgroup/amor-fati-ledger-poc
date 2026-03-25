package com.boombustgroup.ledger

/** Pure reference model for the imperative runtime interpreter.
  *
  * This mirrors [[ImperativeInterpreter]] batch semantics on an immutable keyed state: `(sector, asset, index) -> balance`.
  */
object RuntimeInterpreterReference:

  type BalanceKey   = (EntitySector, AssetType, Int)
  type BalanceState = Map[BalanceKey, Long]

  private def key(sector: EntitySector, asset: AssetType, index: Int): BalanceKey =
    (sector, asset, index)

  private def update(state: BalanceState, sector: EntitySector, asset: AssetType, index: Int, delta: Long): BalanceState =
    val k       = key(sector, asset, index)
    val updated = state.getOrElse(k, 0L) + delta
    if updated == 0L then state - k else state.updated(k, updated)

  private def validateBatch(sectorSizes: Map[EntitySector, Int], batch: BatchedFlow): Unit = batch match
    case BatchedFlow.Scatter(from, to, amounts, targets, _, _) =>
      val fromSize = sectorSizes.getOrElse(from, 1)
      val toSize   = sectorSizes.getOrElse(to, 1)
      require(
        amounts.length == fromSize,
        s"Scatter amounts.length=${amounts.length} must equal sectorSize($from)=$fromSize"
      )
      var i = 0
      while i < amounts.length do
        require(amounts(i) >= 0L, s"Scatter amount at index $i is negative: ${amounts(i)}")
        require(
          targets(i) >= 0 && targets(i) < toSize,
          s"Scatter target index at position $i out of bounds: ${targets(i)} for sectorSize($to)=$toSize"
        )
        i += 1

    case BatchedFlow.Broadcast(from, fromIdx, to, amounts, targets, _, _) =>
      val fromSize = sectorSizes.getOrElse(from, 1)
      val toSize   = sectorSizes.getOrElse(to, 1)
      require(
        fromIdx >= 0 && fromIdx < fromSize,
        s"Broadcast fromIndex=$fromIdx out of bounds for sectorSize($from)=$fromSize"
      )
      var i = 0
      while i < amounts.length do
        require(amounts(i) >= 0L, s"Broadcast amount at index $i is negative: ${amounts(i)}")
        require(
          targets(i) >= 0 && targets(i) < toSize,
          s"Broadcast target index at position $i out of bounds: ${targets(i)} for sectorSize($to)=$toSize"
        )
        i += 1

  def applyBatch(sectorSizes: Map[EntitySector, Int], state: BalanceState, batch: BatchedFlow): BalanceState =
    validateBatch(sectorSizes, batch)
    batch match
      case BatchedFlow.Scatter(from, to, amounts, targets, asset, _) =>
        amounts.indices.foldLeft(state) { (acc, i) =>
          val amount = amounts(i)
          if amount == 0L then acc
          else
            update(
              update(acc, from, asset, i, -amount),
              to,
              asset,
              targets(i),
              amount
            )
        }

      case BatchedFlow.Broadcast(from, fromIdx, to, amounts, targets, asset, _) =>
        val afterCredits = amounts.indices.foldLeft(state) { (acc, i) =>
          val amount = amounts(i)
          if amount == 0L then acc
          else update(acc, to, asset, targets(i), amount)
        }
        val totalDebit = amounts.foldLeft(0L)(_ + _)
        update(afterCredits, from, asset, fromIdx, -totalDebit)

  def applyAll(sectorSizes: Map[EntitySector, Int], state: BalanceState, flows: Vector[BatchedFlow]): BalanceState =
    flows.foldLeft(state)((acc, batch) => applyBatch(sectorSizes, acc, batch))
