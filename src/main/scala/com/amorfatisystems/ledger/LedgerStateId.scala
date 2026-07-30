package com.amorfatisystems.ledger

opaque type LedgerStateId = Long

object LedgerStateId:
  private val counter        = new java.util.concurrent.atomic.AtomicLong(0L)
  def fresh(): LedgerStateId = counter.incrementAndGet()
