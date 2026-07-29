# ADR-0002: DOD-Native Execution Path

- **Status:** Proposed
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

## Decision

We will implement a single logical `LedgerState` snapshot containing:

- validated `LedgerTopology`;
- balance storage owned by the state;
- a monotonic snapshot version.

`LedgerState` is the semantic input and output of execution. A successful
execution returns a new logical state and evidence tied to the exact input and
output snapshot versions. A failed execution publishes neither partial state
nor partial evidence.

The kernel will expose one immutable execution semantics for single transfers
and ordered transfer sequences. Any optimized backend must implement that same
semantics and pass equivalence tests against the reference interpreter.

Production execution will use a data-oriented backend:

- balances are stored in dense primitive arrays or equivalent contiguous
  buffers;
- stable account indexes are resolved before the hot execution loop;
- transfer batches are executed without avoidable per-transfer collection
  allocation;
- topology metadata and balance storage are separated so storage layout remains
  an implementation detail;
- overflow, bounds, currency, permission, and atomicity checks remain explicit
  contracts, not debug-only assertions.

The current `Map` interpreter remains the executable reference semantics and a
conformance oracle. It is not the target backend for multi-million-agent
production runs.

`ExecutionEvidence` will be associated with the committed snapshot boundary.
The API may provide full transfer-level evidence or an explicitly aggregated
form, but an evidence object must never claim totals that were not produced by
the checked execution path.

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
  instrument distributions rather than only microbenchmarks.

## Implementation Constraints

1. Define `LedgerState` and snapshot/evidence contracts before backend changes.
2. Preserve the current pure interpreter as the reference oracle until the new
   backend has equivalent property and conformance coverage.
3. Make successful state transitions atomic and version-monotonic; failed
   validation must leave the input state unchanged.
4. Benchmark at least 100k, 1M, and 8M account configurations, including sparse
   and dense transfer workloads.
5. Treat allocation rate, peak memory, throughput, and p99 execution latency as
   first-class acceptance metrics.

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

## Open Questions for Implementation

- Which dense layout best fits the expected account/instrument cardinalities:
  flat account-major, instrument-major, or segmented arrays?
- Should capacity growth be forbidden after topology preparation or support a
  checked reallocation boundary?
- Which evidence modes are required for research replay versus production
  throughput?

These questions do not change the decision: all implementations must preserve
the single `LedgerState` semantics and the reference-equivalence contract.
