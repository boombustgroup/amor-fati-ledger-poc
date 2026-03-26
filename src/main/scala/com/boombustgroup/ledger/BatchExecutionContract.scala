package com.boombustgroup.ledger

/** Shared executable contract for runtime batched flows.
  *
  * Keeps batch shape, index bounds, non-negative amounts, and Long overflow assumptions in one place so both the imperative runtime path
  * and its pure reference model validate the same thing.
  */
object BatchExecutionContract:

  type SectorSizeOf = EntitySector => Int
  type BalanceAt    = (EntitySector, AssetType, Int) => Long

  def validateBatch(
      sectorSizeOf: SectorSizeOf,
      balanceAt: BalanceAt,
      batch: BatchedFlow
  ): Either[String, Unit] = batch match
    case BatchedFlow.Scatter(from, to, amounts, targets, asset, _) =>
      val fromSize = sectorSizeOf(from)
      val toSize   = sectorSizeOf(to)
      if amounts.length != fromSize then Left(s"Scatter amounts.length=${amounts.length} must equal sectorSize($from)=$fromSize")
      else
        var i: Int                = 0
        var error: Option[String] = None
        while i < amounts.length && error.isEmpty do
          val amount = amounts(i)
          if amount < 0L then error = Some(s"Scatter amount at index $i is negative: $amount")
          else if targets(i) < 0 || targets(i) >= toSize then
            error = Some(
              s"Scatter target index at position $i out of bounds: ${targets(i)} for sectorSize($to)=$toSize"
            )
          else
            val fromBalance = balanceAt(from, asset, i)
            val toBalance   = balanceAt(to, asset, targets(i))
            if fromBalance < Long.MinValue + amount then
              error = Some(
                s"Scatter debit at index $i would underflow Long: balance=$fromBalance amount=$amount"
              )
            else if toBalance > Long.MaxValue - amount then
              error = Some(
                s"Scatter credit at target ${targets(i)} would overflow Long: balance=$toBalance amount=$amount"
              )
          i += 1

        error.toLeft(())

    case BatchedFlow.Broadcast(from, fromIdx, to, amounts, targets, asset, _) =>
      val fromSize = sectorSizeOf(from)
      val toSize   = sectorSizeOf(to)
      if fromIdx < 0 || fromIdx >= fromSize then Left(s"Broadcast fromIndex=$fromIdx out of bounds for sectorSize($from)=$fromSize")
      else
        var i: Int                = 0
        var totalDebit: Long      = 0L
        var error: Option[String] = None
        while i < amounts.length && error.isEmpty do
          val amount = amounts(i)
          if amount < 0L then error = Some(s"Broadcast amount at index $i is negative: $amount")
          else if targets(i) < 0 || targets(i) >= toSize then
            error = Some(
              s"Broadcast target index at position $i out of bounds: ${targets(i)} for sectorSize($to)=$toSize"
            )
          else if totalDebit > Long.MaxValue - amount then
            error = Some(s"Broadcast total debit would overflow Long at index $i: total=$totalDebit amount=$amount")
          else
            val toBalance = balanceAt(to, asset, targets(i))
            if toBalance > Long.MaxValue - amount then
              error = Some(
                s"Broadcast credit at target ${targets(i)} would overflow Long: balance=$toBalance amount=$amount"
              )
            else totalDebit += amount
          i += 1

        if error.isDefined then error.toLeft(())
        else
          val fromBalance = balanceAt(from, asset, fromIdx)
          if fromBalance < Long.MinValue + totalDebit then
            Left(
              s"Broadcast debit at sender $fromIdx would underflow Long: balance=$fromBalance totalDebit=$totalDebit"
            )
          else Right(())

  def canApplyBatch(
      sectorSizeOf: SectorSizeOf,
      balanceAt: BalanceAt,
      batch: BatchedFlow
  ): Boolean =
    validateBatch(sectorSizeOf, balanceAt, batch).isRight

  def requireValidBatch(
      sectorSizeOf: SectorSizeOf,
      balanceAt: BalanceAt,
      batch: BatchedFlow
  ): Unit =
    validateBatch(sectorSizeOf, balanceAt, batch).fold(msg => throw IllegalArgumentException(msg), identity)
