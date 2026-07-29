package com.amorfatisystems.ledger

/** Opaque instrument metadata handle. The ledger does not interpret it. */
opaque type InstrumentId = Int

object InstrumentId:
  def apply(value: Int): InstrumentId = value
  def value(id: InstrumentId): Int = id
