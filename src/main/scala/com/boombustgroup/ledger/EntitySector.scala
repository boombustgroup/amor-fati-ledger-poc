package com.boombustgroup.ledger

/** Which population an agent belongs to.
  *
  * Not to be confused with economic sector (BPO, Manufacturing, etc.). EntitySector identifies the population type for array-based storage
  * — each sector has its own Array[Long] per AssetType.
  */
enum EntitySector:
  case Households // 100K agents
  case Firms      // 10K agents
  case Banks      // 7 agents
  case Government // singleton (index 0)
  case NBP        // singleton (index 0)
  case Insurance  // singleton (index 0)
  case Funds      // ZUS, NFZ, FP, PFRON, FGSP, BGK, PFR (7 funds)
  case Foreign    // per-country in multi-country mode
