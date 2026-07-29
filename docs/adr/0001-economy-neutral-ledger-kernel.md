# ADR-0001: Economy-Neutral Ledger Kernel

- **Status:** Proposed
- **Date:** 2026-07-29

## Context

`amor-fati-ledger` is the accounting kernel for SFC systems. Its current
contract embeds one Polish economy: closed `EntitySector` and `AssetType`
enumerations, PLN-specific amount comments, and population-size assumptions.
Those concepts belong to an economy package or to `amor-fati-AB-SFC`, not to a
ledger kernel.

The project will make a clean contract break. We will not preserve a legacy
API and will not introduce an adapter for the current model.

The repository namespace is also part of this clean break: the canonical base
package is `com.amorfatisystems`. The former `com.boombustgroup` namespace is
not retained as a compatibility namespace.

## Decision

`amor-fati-ledger` becomes an economy-neutral, currency-neutral double-entry
execution kernel.

The kernel owns only:

- opaque account, currency, mechanism, and period handles;
- account metadata for an optional instrument handle and currency handle;
- signed integer ledger units with checked arithmetic; scale and presentation
  are supplied by the currency/economy contract;
- debit/credit flows and batched execution;
- account-state storage keyed by account handles and immutable execution plans;
- conservation, bounds, overflow, atomicity, and reference/imperative
  equivalence contracts;
- explicit account lifecycle operations.

The kernel does **not** own:

- country-specific sectors, institutions, population sizes, or currencies;
- instrument taxonomies such as Polish bonds, TFI units, or NBP reserves;
- issuer, holder, ownership, settlement, or SFC matrix semantics;
- money-creation policy, agent behaviour, market mechanisms, or calibration.
- scatter/broadcast policies and population distributions.

Those semantics are supplied by `amor-fati-AB-SFC` and economy-specific
packages through typed handles and validated topology projections.

## Clean-break requirements

The following are removed rather than deprecated or wrapped:

1. `EntitySector` and every Poland-specific sector member;
2. the closed `AssetType` enum and all country-specific asset members;
3. PLN scale and Poland-specific population assumptions in production code;
4. state keys that require a sector or asset enum instead of account handles;
5. APIs whose semantics silently default unknown entities or sectors;
6. kernel-level `Scatter`, `Broadcast`, and population-specific distribution
   contracts.
7. the `com.boombustgroup` package namespace.

No compatibility adapter, dual API, or legacy namespace is part of the target
architecture. Callers are migrated to the new contract in the same change
series; old APIs may be deleted once their replacements exist.

## Target execution contract

The public execution path must accept a fully validated topology projection:

```text
LedgerTopology
  account handles and ownership of storage slots
  optional instrument/currency metadata per account
  ledger bounds and debit/credit permissions

ValidatedBatchPlan
  immutable debit/credit operations
  explicit mechanism and period metadata
  preflight conservation and overflow checks

ExecutionResult
  new immutable state or committed mutable state
  checked execution evidence and snapshot/version stamp
```

The kernel is multi-currency: one instance may contain accounts denominated in
different currencies. A transfer between accounts with incompatible currency
metadata is rejected unless an explicit conversion operation is supplied by
the caller. FX rates and conversion policy are outside the kernel.

Instrument and currency handles are metadata, not storage dimensions. The
kernel does not enforce issuer, holder, ownership, settlement, or SFC matrix
rules. Missing topology data is a validation error rather than an inferred
default.

The kernel does not schedule periods. A period handle is audit metadata only.
Execution is single-threaded per context: a validated plan is valid only for
the state snapshot against which it was prepared. Applying it to another
snapshot fails with a version mismatch.

Account creation and closure are explicit, checked lifecycle operations. The
kernel may atomically create an account and apply its initial entries, but it
does not decide when an economic instrument is issued or destroyed.

`CanIssue`, `CanBorrow`, `CanEmploy`, and related economic capabilities belong
to `amor-fati-AB-SFC`. Kernel capabilities are limited to ledger bounds,
debit/credit permissions, lifecycle permissions, and currency compatibility.

Scatter, broadcast, and distribution policies are assembled above the kernel
into ordinary validated debit/credit operations. The kernel may retain small
numeric helpers only when they have no population or economic semantics.

## Migration sequence

1. Specify and implement opaque account handles, account metadata, and generic
   account/state keys.
2. Rebuild pure and imperative interpreters against the account-only,
   multi-currency contract.
3. Add explicit account lifecycle and snapshot/version checks.
4. Re-establish Stainless/reference proofs and equivalence/property tests.
5. Delete the current Polish-specific model and update all repository tests and
   documentation to the new API and `com.amorfatisystems` namespace.
6. Integrate the resulting kernel from `amor-fati-AB-SFC`, which supplies
   instrument semantics, topology, and economic transitions.

Each step must leave the repository compiling and tested, but intermediate
steps are not compatibility releases. The first accepted implementation is
the generic kernel.

## Consequences

Positive:

- the ledger can support PLN, EUR, USD, shared currencies, or synthetic
  currencies without changing its ontology;
- multiple currencies can coexist in one ledger instance without embedding FX
  policy in the kernel;
- economy-specific semantics become explicit and testable above the kernel;
- formal verification remains focused on accounting invariants.

Costs:

- the current API and all dependent callers must be rewritten;
- account lifecycle and currency metadata become explicit contracts;
- instrument and topology validation moves into `amor-fati-AB-SFC`;
- scatter/distribution assembly moves above the kernel;
- no incremental compatibility path is available.

## Rejected alternatives

- **Keep the Polish ledger:** violates the economy-neutral boundary.
- **Add a legacy facade:** preserves the contamination and creates two
  semantics to maintain.
- **Adapter around the current API:** hides invalid assumptions instead of
  removing them.
