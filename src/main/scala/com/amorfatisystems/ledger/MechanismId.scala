package com.amorfatisystems.ledger

/** Opaque identifier for the mechanism that produced a flow.
  *
  * The ledger does not know what mechanisms exist — that is defined by the simulation engine (e.g. amor-fati defines ZusContribution,
  * NbpQe, etc. as an enum and passes `.ordinal` here). The ledger only stores and forwards the ID for audit trail purposes.
  *
  * Opaque type prevents accidental mixing with other Int values (account indices, array offsets, etc.).
  */
opaque type MechanismId = Int

object MechanismId:
  def apply(id: Int): MechanismId           = id
  given Conversion[Int, MechanismId] with
    def apply(value: Int): MechanismId = value
  extension (m: MechanismId) def toInt: Int = m
