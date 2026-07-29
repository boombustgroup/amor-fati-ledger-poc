package com.amorfatisystems.ledger

/** Opaque namespace handle used only to group account slots for storage. Economic partition semantics belong to the caller.
  */
opaque type AccountPartitionId = Int

object AccountPartitionId:
  def apply(value: Int): AccountPartitionId = value
  def value(id: AccountPartitionId): Int    = id
