package com.amorfatisystems.ledger

/** Opaque identifier for the mechanism that produced a flow.
  *
  * The ledger does not know the mechanism vocabulary; the simulation engine defines it and passes this handle for audit purposes. NbpQe,
  * etc. as an enum and passes `.ordinal` here). The ledger only stores and forwards the ID for audit trail purposes.
  *
  * Opaque type prevents accidental mixing with other Int values (account indices, array offsets, etc.).
  */
opaque type MechanismId = Int

object MechanismId:
  def apply(id: Int): MechanismId           = id
  extension (m: MechanismId) def toInt: Int = m
