package com.amorfatisystems.ledger

/** Ledger-level metadata; issuer, holder, ownership, and SFC semantics stay above the kernel. */
final case class AccountMetadata(
    currency: CurrencyId,
    instrument: Option[InstrumentId] = None,
    minBalance: Option[Long] = None,
    maxBalance: Option[Long] = None,
    canDebit: Boolean = true,
    canCredit: Boolean = true
):
  def accepts(value: Long): Boolean =
    minBalance.forall(value >= _) && maxBalance.forall(value <= _)
