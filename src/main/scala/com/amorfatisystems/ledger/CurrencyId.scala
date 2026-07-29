package com.amorfatisystems.ledger

/** Opaque currency handle used for compatibility checks between accounts. */
opaque type CurrencyId = Int

object CurrencyId:
  def apply(value: Int): CurrencyId = value
  def value(id: CurrencyId): Int = id
