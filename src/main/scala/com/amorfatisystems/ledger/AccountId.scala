package com.amorfatisystems.ledger

/** Opaque account handle. The ledger never derives economic meaning from it. */
opaque type AccountId = Int

object AccountId:
  def apply(value: Int): AccountId = value
  def value(id: AccountId): Int    = id
