# ADR-0002: DOD-Native Execution Path

- **Status:** Accepted
- **Date:** 2026-07-29
- **Decision owners:** Amor Fati Systems

## Context

The generic ledger kernel must support economies with millions of accounts and
high-volume transfer execution. The public contracts introduced by ADR-0001
must remain deterministic, immutable at the semantic boundary, and usable as a
reference model. They must not force production execution to allocate a new
general-purpose collection for every transfer or copy the complete balance
state for every step.

The current kernel has a pure `Map`-based interpreter and an internal mutable
array-oriented state type. These implementations are useful for reference
semantics and storage experiments, but the state boundary, execution evidence,
and optimized backend are not yet one coherent contract.

ADR-0002 refines ADR-0001's execution result contract. The alternative
"immutable state or committed mutable state" is an internal backend choice;
the semantic boundary exposes one logical `LedgerState` model.

## Decision

We will implement a single logical `LedgerState` snapshot containing:

- validated `LedgerTopology`;
- balance storage owned by the state;
- a monotonic snapshot version.

`LedgerState` is the semantic input and output of execution. A successful
execution returns a new logical state and evidence tied to the exact input and
output snapshot versions. A failed execution publishes neither partial state
nor partial evidence.

The semantic snapshot is immutable at the boundary. The DOD backend may mutate
its owned primitive buffers in place, but only behind a versioned commit
boundary. Applying a plan validated against version `V` to a state whose current
version differs from `V` is a typed `VersionMismatch`, never undefined
behaviour. Copying a snapshot for replay or a research fork is explicit and is
not part of the hot path. The reference `Map` backend continues to produce a
new balance store.

`LedgerTopology` supports checked account creation and closure operations.
Prepared state declares a `preparedCapacity`; exceeding it is a checked
lifecycle error rather than silent reallocation. Lifecycle commits increment
the same snapshot version as transfer commits. A research fork creates an
independent state and independent version axis.

The kernel will expose one immutable execution semantics for single transfers
and ordered transfer sequences. Any optimized backend must implement that same
semantics and pass equivalence tests against the reference interpreter.

The arithmetic oracle is explicit: balances and transfer amounts are signed
`Long` ledger units; individual transfer amounts are non-negative; preflight
calculations use exact `BigInt` intermediates; and a commit is accepted only
when every resulting balance and aggregate evidence total is representable as
`Long`. Overflow or underflow is a typed rejection and never wrapping
arithmetic. Transfers are applied in the order supplied by the plan, with no
implicit reordering or rounding. Transfer execution performs integer debit and
credit only; any currency scale conversion or allocation rounding belongs to
the economy contract. `DistributeModel` retains its separately documented
floor-with-residual rule. These rules apply identically to single transfers,
ordered sequences, the `Map` reference interpreter, and every DOD backend.

Production execution will use a data-oriented backend:

- balances are stored in dense primitive arrays or equivalent contiguous
  buffers;
- stable account indexes are resolved before the hot execution loop;
- transfer batches are executed without avoidable per-transfer collection
  allocation;
- topology metadata and balance storage are separated so storage layout remains
  an implementation detail;
- account indexes are allocated at lifecycle events and a batch preflight
  resolves `AccountId` to indexes once before the hot loop;
- overflow, bounds, currency, permission, and atomicity checks remain explicit
  contracts, not debug-only assertions.

Currency, permission, and balance-bound checks are performed during preflight
against the resolved batch metadata. The hot loop retains only checked numeric
updates and the snapshot-version guard; it does not repeat metadata lookups for
each transfer.

Any partitioning by instrument, currency, mechanism, or other metadata is an
internal DOD layout choice and cannot appear in the semantic API or client
types. This preserves ADR-0001's instrument-as-metadata constraint.

The current `Map` interpreter remains the executable reference semantics and a
conformance oracle. It is not the target backend for multi-million-agent
production runs.

`ExecutionEvidence` is associated with the committed snapshot boundary and has
explicit client-selected modes:

1. **TransferLog** — ordered applied records containing resolved source and
   target accounts, amount, mechanism, and period; size is linear in plan size.
2. **AggregatedByMechanism** — checked totals grouped by currency, source,
   target, and mechanism; size is linear in the number of distinct groups.

The selected mode is computed during checked execution. The kernel never claims
an evidence mode that was not produced, and one mode is not reconstructed from
another after the fact. Failed execution returns a typed `ExecutionRejection`
with the offending plan position, reason (`Overflow`, `Bounds`,
`CurrencyMismatch`, `PermissionDenied`, `AtomicityViolation`,
`VersionMismatch`, or `LifecycleViolation`), and validation snapshot version.

`DistributeModel` remains in the ledger only as a population-neutral, pure
numeric helper. It must not acquire population, sector, or economy-specific
semantics. If future profiling shows that it is not a kernel concern, it may
be moved in a separate decision without changing ledger execution semantics.

## Non-Goals

- Adding economy-specific agents, sectors, instruments, or currency rules.
- Making storage layout part of the public semantic API.
- Preserving the removed batch API or old package namespace.
- Introducing concurrency into the kernel execution contract.
- Optimizing before benchmark fixtures and equivalence tests exist.
- Running Stainless proof code in the production hot path; `Verified.scala` is
  a reference proof artifact only.

## Consequences

### Positive

- One state boundary makes versioning and evidence provenance explicit.
- Reference and optimized execution can be tested for bit-equivalent results.
- Dense storage and batch execution provide a path to multi-million-agent
  workloads without changing the domain contract.
- Immutable semantics remain suitable for research reproducibility and replay.

### Costs and risks

- The first implementation requires a deliberate rewrite of the current
  interpreter/storage boundary.
- A dense backend needs stable account indexing and explicit capacity policy.
- Full transfer-level evidence can dominate memory and allocation costs; the
  API must make aggregation explicit.
- Benchmarks must cover realistic account counts, transfer densities, and
  instrument-metadata cardinality distributions rather than only
  microbenchmarks.

## Implementation Constraints

1. Define `LedgerState` and snapshot/evidence contracts before backend changes.
2. Preserve the current pure interpreter as the reference oracle until the new
   backend has equivalent property and conformance coverage. This minimum
   coverage includes per-currency conservation, non-conflicting transfer
   commutativity, batch atomicity under injected failure, randomized
   reference-to-DOD bit equivalence, stale-snapshot rejection, and independent
   fork equivalence.
3. Make successful state transitions atomic and version-monotonic; failed
   validation must leave the input state unchanged.
4. Benchmark at least 100k, 1M, and 8M account configurations (and larger when
   required by the largest planned economy fixture), including sparse,
   dense, valid, and rejection workloads. Rejection workloads are a separate
   axis because validation dominates their cost.
5. Treat allocation rate, peak resident memory, sustained throughput, and p99
   per-batch execution latency as acceptance metrics. Exact per-scale thresholds
   must be published in versioned `docs/benchmarks/acceptance.md` before the DOD
   backend is promoted; a regression greater than 10% against the signed
   baseline blocks release.
6. The DOD backend must execute `Conformance.TwoSector` from the
   `amor-fati-AB-SFC` RFC and produce bit-identical state progression and
   evidence to the reference interpreter.

## Rejected Alternatives

### Keep `Map` as the production backend

Rejected because object overhead, pointer indirection, copying, and garbage
collection pressure are unsuitable as the default path for multi-million-agent
execution.

### Optimize the mutable backend without a unified state contract

Rejected because it would create a second semantic path and make snapshot
versioning and evidence correctness dependent on backend-specific behavior.

### Expose arrays and partition keys publicly

Rejected because it couples the economy model to one storage layout and prevents
future backends or different account-resolution strategies.

## Open Question for Implementation

Which dense layout best fits the expected account/instrument cardinalities:
flat account-major, instrument-major, or segmented arrays? Any such layout is
strictly internal and must preserve the account-only semantic API.

This implementation question does not change the decision: all
implementations must preserve the single `LedgerState` semantics and the
reference-equivalence contract. Snapshot versions are monotonic within one
state instance; forked states have independent version axes. A future opaque
`LedgerStateId` may identify an instance, but versions from different forks are
not comparable.
