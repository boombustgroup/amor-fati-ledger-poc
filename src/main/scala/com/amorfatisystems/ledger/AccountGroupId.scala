package com.amorfatisystems.ledger

/** Opaque namespace handle used only to group account slots for storage.
  * Economic sector semantics belong to the caller.
  */
opaque type AccountGroupId = Int

object AccountGroupId:
  def apply(value: Int): AccountGroupId = value
  def value(id: AccountGroupId): Int = id
