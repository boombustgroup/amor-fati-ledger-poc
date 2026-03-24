package com.boombustgroup.ledger

/** Compile-time safe audit trail — which mechanism produced a flow.
  *
  * Using enum (not String) so a typo is a compile error, not a silent audit trail bug.
  */
enum Mechanism:
  // Household
  case HhIncome, HhConsumption, HhRent, HhDebtService, HhDepositInterest
  // Firm
  case FirmRevenue, FirmWages, FirmTax, FirmLoanRepayment, FirmCapex
  // Banking
  case BankInterest, BankNplWriteoff, BankInterbank, BankBondAllocation
  // Government
  case GovSpending, GovTaxRevenue, GovBondIssuance, GovDebtService
  // Central bank
  case NbpQe, NbpReserveInterest, NbpStandingFacility
  // Social security
  case ZusContribution, ZusPension, NfzContribution, NfzSpending
  // Insurance
  case InsurancePremium, InsuranceClaim, InsuranceInvestment
  // External
  case TradeExport, TradeImport, FdiInflow, PortfolioFlow, Remittance
  // Housing
  case MortgageOrigination, MortgageRepayment, MortgageDefault
  // Consumer credit
  case ConsumerCreditOrigination, ConsumerCreditService, ConsumerCreditDefault
  // Corporate bonds
  case CorpBondIssuance, CorpBondCoupon, CorpBondDefault
