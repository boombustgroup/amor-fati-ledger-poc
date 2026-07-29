package com.amorfatisystems.ledger

/** Opaque audit-period handle. Scheduling and calendar semantics belong above the kernel. */
opaque type PeriodId = Long

object PeriodId:
  def apply(value: Long): PeriodId = value
  def value(id: PeriodId): Long = id
