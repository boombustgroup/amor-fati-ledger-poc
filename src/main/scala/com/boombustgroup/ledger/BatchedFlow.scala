package com.boombustgroup.ledger

/** A batch of monetary flows from one sector to another.
  *
  * Performance-critical: one Array[Long] allocation per mechanism x sector, not per agent. At 100K households this is the difference
  * between millions of GC-pressured Flow objects and a single cache-friendly array.
  *
  * @param from
  *   source sector (who pays)
  * @param to
  *   target sector (who receives)
  * @param amounts
  *   per-agent amounts, indexed by agent position within `from` sector. amounts(42) = what agent #42 pays.
  * @param targetIndices
  *   routing map: targetIndices(42) = 3 means agent #42 in `from` pays agent #3 in `to`. For 1:1 same-index flows, pass identity indices
  *   (0,1,2,...).
  * @param asset
  *   which balance array to debit/credit
  * @param mechanism
  *   audit trail — which mechanism produced this flow
  */
case class BatchedFlow(
    from: EntitySector,
    to: EntitySector,
    amounts: Array[Long],
    targetIndices: Array[Int],
    asset: AssetType,
    mechanism: Mechanism
):
  require(amounts.length == targetIndices.length, s"amounts.length=${amounts.length} != targetIndices.length=${targetIndices.length}")
