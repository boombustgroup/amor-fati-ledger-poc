package com.boombustgroup.ledger

/** Which balance array to debit/credit.
  *
  * Each (EntitySector, AssetType) pair maps to one Array[Long] in MutableWorldState. Maps 1:1 to existing state fields in amor-fati
  * (Banking.BankState, Insurance.State, Nbfi.State, etc.).
  */
enum AssetType:
  // Deposits
  case DemandDeposit
  case TermDeposit
  // Loans
  case FirmLoan
  case ConsumerLoan
  case MortgageLoan
  // Bonds
  case GovBondAFS
  case GovBondHTM
  case CorpBond
  // Central bank
  case Reserve
  case StandingFacility
  case InterbankLoan
  // Equity
  case Equity
  // Insurance
  case LifeReserve
  case NonLifeReserve
  // NBFI
  case TfiUnit
  case NbfiLoan
  // Fiscal
  case Cash
  case Capital
  case ForeignAsset
