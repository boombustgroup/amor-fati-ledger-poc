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

## Decision

`amor-fati-ledger` becomes an economy-neutral, currency-neutral double-entry
execution kernel.

The kernel owns only:

- opaque account, entity, instrument, currency, mechanism, and period handles;
- signed or explicitly typed fixed-point amounts with checked arithmetic;
- debit/credit flows and batched execution;
- account-state storage and immutable execution plans;
- conservation, bounds, overflow, atomicity, and reference/imperative
  equivalence contracts;
- distribution primitives and their verified reference semantics.

The kernel does **not** own:

- country-specific sectors, institutions, population sizes, or currencies;
- instrument taxonomies such as Polish bonds, TFI units, or NBP reserves;
- issuer, holder, ownership, settlement, or SFC matrix semantics;
- money-creation policy, agent behaviour, market mechanisms, or calibration.

Those semantics are supplied by `amor-fati-AB-SFC` and economy-specific
packages through typed handles and validated topology projections.

## Clean-break requirements

The following are removed rather than deprecated or wrapped:

1. `EntitySector` and every Poland-specific sector member;
2. the closed `AssetType` enum and all country-specific asset members;
3. PLN scale and Poland-specific population assumptions in production code;
4. state keys that require a sector or asset enum instead of opaque handles;
5. APIs whose semantics silently default unknown entities or sectors.

No compatibility adapter, dual API, or legacy namespace is part of the target
architecture. Callers are migrated to the new contract in the same change
series; old APIs may be deleted once their replacements exist.

## Target execution contract

The public execution path must accept a fully validated topology projection:

```text
LedgerTopology
  account handles and ownership of storage slots
  instrument/currency handles
  account capabilities and bounds

ValidatedBatchPlan
  immutable debit/credit operations
  explicit mechanism and period metadata
  preflight conservation and overflow checks

ExecutionResult
  new immutable state or committed mutable state
  checked accounting evidence
```

The ledger does not infer sectors, holders, issuers, currencies, or account
creation rules from identifiers. Missing topology data is a validation error.

## Migration sequence

1. Specify and implement opaque handles and generic account/state keys.
2. Move amount and currency semantics into explicit kernel types; retain
   checked fixed-point arithmetic without a currency-specific scale.
3. Rebuild pure and imperative interpreters against the generic contract.
4. Re-establish Stainless/reference proofs and equivalence/property tests.
5. Delete the current Polish-specific model and update all repository tests and
   documentation to the new API.
6. Integrate the resulting kernel from `amor-fati-AB-SFC`.

Each step must leave the repository compiling and tested, but intermediate
steps are not compatibility releases. The first accepted implementation is
the generic kernel.

## Consequences

Positive:

- the ledger can support PLN, EUR, USD, shared currencies, or synthetic
  currencies without changing its ontology;
- economy-specific semantics become explicit and testable above the kernel;
- formal verification remains focused on accounting invariants.

Costs:

- the current API and all dependent callers must be rewritten;
- instrument and topology validation moves into `amor-fati-AB-SFC`;
- no incremental compatibility path is available.

## Rejected alternatives

- **Keep the Polish ledger:** violates the economy-neutral boundary.
- **Add a legacy facade:** preserves the contamination and creates two
  semantics to maintain.
- **Adapter around the current API:** hides invalid assumptions instead of
  removing them.
