# RFC-0001: Dense Backend Benchmark Protocol

**Status:** Draft for discussion
**Scope:** `amor-fati-ledger`
**Depends on:** ADR-0002 (DOD-Native Execution Path), evidence-only Dense commit contract
**Supersedes:** partially replaces `docs/benchmarks/acceptance.md`, which must be rewritten once this RFC is accepted.

## Summary

`docs/benchmarks/acceptance.md` is a 31-line protocol sketch. It does not
define workloads, environment, statistical methodology, or per-metric
thresholds. Any baseline signed under it is unverifiable and any regression
gate is theatrical.

This RFC identifies the decisions that must be made — and the empirical work
that must be done — before the Dense backend can be benchmarked as a
promotion candidate for multi-million-account Monte Carlo SFC-ABM workloads.
It does not itself set thresholds. Thresholds require a noise-floor
experiment that this RFC prescribes.

## Motivation

ADR-0002 mandates that the Dense backend be benchmarked at 100k, 1M, and 8M
accounts, that it execute `Conformance.TwoSector` from
`amor-fati-AB-SFC` RFC-0001 with bit-identical results to the reference
interpreter, and that a >10% regression against a signed baseline blocks
release. None of this is currently actionable:

- there are no fixture workloads representing SFC-ABM production;
- there is no `Conformance.TwoSector` implementation;
- the noise floor of the measurement infrastructure is unknown, so 10% is
  arbitrary;
- there is no environment lock, so a baseline does not transfer between
  developer machine and CI;
- there is no reference-comparison mandate, so a structural regression
  where Dense collapses to Reference performance would pass the gate;
- the ledger benchmark ignores the AB-SFC layer above, which may dominate
  end-to-end cost and mask ledger regressions.

Signing a baseline under these conditions locks in an unfalsifiable artefact.

## Boundary

This RFC covers:

- workload fixture library for the Dense backend;
- catalogue of metrics with rationale;
- environment lock requirements;
- statistical methodology for baseline establishment and regression gating;
- baseline lifecycle (storage, sign-off, invalidation triggers);
- an empirical prerequisite (noise-floor experiment) that must be completed
  before any thresholds are published.

This RFC does not cover:

- distributed Monte Carlo execution across machines;
- long-running (multi-day) stability testing;
- hardware performance-counter integration (perf, VTune);
- absolute throughput or latency targets, which follow from the noise-floor
  experiment, not from prescription.

## Open Decisions

Every subsection below is a decision the project cannot avoid. Where a
default is proposed, it is a starting point for discussion, not a
recommendation.

### D1. Workload fixture library

Three axes, each with three archetypes. Cartesian product is 27 configurations;
not all must be gated, but all must be nameable.

**Topology archetypes.** SFC economies differ structurally; one archetype
underrepresents. Proposed defaults:

- `SmallMarket`: 100k accounts, 1 currency, uniform balance distribution,
  no lifecycle churn, no `min/maxBalance` pressure, single mechanism.
- `MultiCurrencyEconomy`: 1M accounts, 3 currencies, log-normal balance
  distribution, moderate bounds coverage (20% of accounts have tight
  min/max), 5 mechanisms.
- `LargeGlobal`: 8M accounts, 10 currencies, log-normal + Pareto hubs (top
  0.1% of accounts hold 50% of value; act as counterparty in 50% of
  transfers), tight bounds on 40% of accounts, 20 mechanisms.

**Batch archetypes.** Different transfer patterns exercise different code
paths (cache locality, rejection classifier, aggregation cardinality):

- `SparseRandom`: <1% of accounts touched per batch, uniform random source
  and target, batch size distributed lognormal around 1000.
- `HubAndSpoke`: 1→many (distribution: one source, many targets) or
  many→1 (collection: many sources, one target), batch size 100–100k.
  Representative of payroll, tax, dividend.
- `DenseLocal`: 10%+ of accounts touched, temporal locality (each transfer
  index within ±K of the previous), batch size 10k+. Represents market
  clearing sweeps.

**Rejection axis.** ADR-0002 §168 flags rejection workloads as separate.
Split further:

- `AllValid`: 0% rejection rate.
- `TypicalRejection`: 5% rejection distributed across `Bounds`,
  `CurrencyMismatch`, `PermissionDenied`, `Overflow`, `LifecycleViolation`
  in proportion TBD by AB-SFC consultation.
- `AllReject`: 100% rejection (structural stress test).

**Lifecycle churn axis.** Independent of transfer workload:

- `NoChurn`: 0 lifecycle events per period.
- `LightChurn`: 0.1% of active accounts churned per period.
- `HeavyChurn`: 1% per period.

Fixture names are canonical. Every reported number must cite the workload
name.

### D2. Metric catalogue

Twelve metrics. Current `acceptance.md` names five; the other seven are
required for validity per §3 (threats) below.

1. Sustained committed transfers per second (successful workload throughput).
2. Batch latency distribution: p50, p90, p99, **p99.9**, max.
3. Preflight rejection latency, per rejection reason.
4. Allocated bytes per committed transfer.
5. Allocated bytes per empty batch (canary for accidental hot-path allocation).
6. Peak resident memory during workload.
7. GC pause distribution: count, total pause time as % of wall time, max pause.
8. Snapshot materialization time as a function of live account count.
9. Fork prepare time as a function of `preparedCapacity`.
10. Cold-start time: JVM launch + `DenseLedgerBackend.prepare(N)`.
11. JIT warm-up time to plateau (report as an operational number, not a gate).
12. Reference-comparison ratio: `throughput(Dense) / throughput(Map)` at the
    same workload. Gate: Dense must be at least Nx faster than Map at 1M+.
    N to be set from noise-floor data, not asserted here.

Both `TransferLog` and `AggregatedByMechanism` evidence modes are benchmarked
per workload. The mix in production is unknown until AB-SFC integration is
concrete; both must be measured until then.

### D3. Environment lock

A benchmark whose environment is not pinned is not a benchmark. Required
pins:

- **JDK distribution and version** — exact, not a range. Proposed default:
  Temurin 21 LTS. `-XshowSettings:vm` output archived with each baseline.
- **Garbage collector** — G1 as primary (mainstream, low-config); ZGC as
  secondary comparison for low-pause validation. Both flags recorded.
- **Heap sizing** — `-Xms == -Xmx == 4 × peak RSS(workload)`. Sized per
  workload; documented per baseline.
- **JIT** — C2 (default) primary. Graal secondary if the delta is >20%; if
  so, the reason must be understood before promotion.
- **`-XX:+UseCompressedOops`** — on (default). Confirmed in flag record.
- **Huge pages** — off by default; on only if measured to help at 8M and
  documented.
- **Scala version** — pinned exactly in `build.sbt`, not `%%`, not `latest`.
- **Machine class** — dedicated Linux server, CPU governor `performance`,
  turbo/boost off, no other tenants, no Docker for the baseline JVM.
  Reference SKU documented per baseline.

Baselines from CI runners are informational only. Signed baselines require
dedicated hardware.

### D4. Statistical methodology

**Repetition structure.** Per configuration:
- 5 independent JVM forks (`@Fork(5)` in JMH terms);
- 5 warm-up iterations of 10 s per fork (adjustable at 8M scale after
  noise-floor experiment);
- 10 measurement iterations of 10 s per fork.

**Reporting.** Every metric reported as median of forks' median-of-iterations,
with 95% CI. Latency additionally reports 10%-trimmed mean and raw
distribution (histogram or KDE) in the artifact bundle. Outliers are logged
with reason (GC pause, JIT compilation event, safepoint), never silently
trimmed.

**Multiple-comparison correction.** With 12 metrics × 27 workload
configurations, uncorrected α=0.05 gating produces >15 false positives per
PR in expectation. Bonferroni correction on the whole gate matrix, or
Benjamini-Hochberg FDR at target rate 0.05.

### D5. Regression policy per metric

Global 10% threshold is meaningless without variance context. Per-metric
policy (starting points; refined by noise-floor data):

| Metric | Regression threshold |
|---|---|
| Throughput | +5% (regression = throughput decrease) |
| p99 latency | +15% |
| p99.9 latency | +25% |
| Allocated bytes / commit | +10% |
| Allocated bytes / empty batch | +50% or absolute >1KB — either fails |
| Peak RSS | +20% |
| GC total pause time % | +30% |
| Snapshot materialization time | +15% |
| Fork prepare time | +20% |
| Cold-start time | +25% |
| Reference-comparison ratio | Dense must remain ≥ threshold × Map; threshold TBD |

Thresholds are per metric, per workload, and per scale. Aggregated via FDR
across the gate matrix.

### D6. Baseline lifecycle

- **Storage.** `docs/benchmarks/baselines/<yyyy-mm-dd>-<hardware-tag>/`
  containing: JVM flag record, machine SKU, hardware perf counter dump,
  JDK version, Scala version, git SHA, raw JMH results, aggregated
  metric report.
- **Sign-off.** Manual. New baseline requires 5-run agreement (max fork
  variance ≤ noise floor + 20%) and human review of anomalies.
- **Invalidation triggers.** Any of: hardware change, JDK major/minor
  change, GC change, Scala minor change, `build.sbt` JVM flag change,
  `--add-opens` / module changes. Baseline is retired, next PR
  re-establishes.
- **Emergency escape.** A regression that reproduces on baseline hardware
  under baseline JVM flags is not overridable by "it's just CI noise".

### D7. `Conformance.TwoSector` dependency

ADR-0002 Constraint 6 requires that the Dense backend execute
`Conformance.TwoSector` from `amor-fati-AB-SFC` RFC-0001 with
bit-identical evidence to the reference interpreter. This fixture does not
yet exist; when it does, it is the canonical realistic-workload gate. This
RFC does not attempt to substitute for it — synthetic fixtures (D1) exist
to stress specific code paths, not to replace SFC realism.

Consequence: this RFC's fixture library is a stress-test suite. Promotion
of Dense requires both:
1. passing the stress-test gate under this RFC, and
2. passing `Conformance.TwoSector` bit-equivalence to Reference.

## Threats to Validity

Grouped by category. Not exhaustive; the ones that would block a
credible baseline.

### External validity

**Synthetic workloads underrepresent SFC dynamics.** Uniform-random topologies
have no hubs, no wealth concentration, no bursty spending. Baselines under
`SparseRandom / SmallMarket` predict worst-case cache behavior and are
uninformative about typical AB-SFC runs. Mitigation: `Conformance.TwoSector`
as the canonical realistic gate; synthetic fixtures relegated to
stress-test role.

**Ledger-only benchmark ignores AB-SFC overhead.** The layer above the
kernel (`MechanismDispatcher`, `SectorAgent`, plan generation) may dominate
end-to-end cost. Ledger regressions ≤ AB-SFC noise floor are invisible.
Mitigation: mandate a paired AB-SFC-level benchmark once
`amor-fati-AB-SFC` has an executable core.

### Internal validity

**Dead-code elimination.** Benchmarks that do not consume `ExecutionEvidence`
via `Blackhole.consume` may be optimized to no-op. Every measurement
harness must consume every returned value.

**Escape analysis eliminates allocations that occur in production.** Tight
loops with a single call site can trigger scalar replacement absent from
real callers. Mitigation: benchmark drivers must include realistic call
diversity.

**BigInt allocation on hot path.** Preflight allocates ≥3 `BigInt` per
transfer (`nextFrom`, `nextTo`, plus classifier path). At 8M-scale
dense workloads this is the dominant allocation source. Metric 4
(allocated bytes / commit) must isolate this contribution; if it does
not, per-transfer overhead attribution is guesswork.

**LongMap resize thresholds.** `scala.collection.mutable.LongMap` grows in
power-of-2 chunks. Account counts adjacent to a resize threshold behave
10× differently. Fixtures must include threshold-adjacent counts
(e.g., 2^17 − 1, 2^17 + 1) for robustness.

**Cache-line effects.** `balances: Array[Long]` is 64 MB at 8M accounts —
20× larger than L3 on typical CPUs. Sequential access is bandwidth-bound;
random access is latency-bound. Transfer-pattern locality is the largest
single determinant of realized throughput. Baselines must record access
pattern; workloads must span both extremes.

**GC noise dominates at 100k scale.** One minor GC pause (~5–10 ms) exceeds
per-batch latency on small workloads. p99 becomes a GC measurement, not a
code measurement. Noise-floor experiment must characterize this before
100k thresholds are published.

### Reproducibility

**Seed discipline.** A single seed is insufficient. Independent seeds required
for: topology generation, initial balance draw, batch generation, rejection
injection. All four recorded per run.

**CI runner variance.** Shared runners have noisy neighbors. Baseline
signing requires dedicated hardware, per D3.

**sbt daemon warmup.** Baselines run under fresh sbt (or, preferably,
compiled artifact executed via `java -jar`), not reused daemon.

### Statistical validity

**Single-run baseline.** `acceptance.md`'s "first accepted run establishes
signed baseline" is statistically invalid. D4 mandates 5 forks with
variance-bounded acceptance.

**Threshold theatre.** Publishing thresholds without knowing noise floor
guarantees false positives (unnecessary blocks) and false negatives
(missed regressions). D8 (below) exists to prevent this.

### Coverage

**Missing scenarios.** Fork prepare, checkpoint/snapshot cost, cold-start
time, reference-comparison ratio are all absent from `acceptance.md`. All
are in D2 here.

**Missing patological cases.** Cascade failures, all-hub topologies,
zero-balance-dominant states. Included in D1's `LargeGlobal` archetype
and `AllReject` rejection axis.

## Proposed Structure

Three sequential deliverables before any threshold-bearing baseline is
signed.

### Deliverable 1: This RFC accepted

Decisions D1–D7 negotiated and locked. AB-SFC team consulted on realistic
mechanism/currency/pattern distributions (D1). No thresholds yet — this RFC
does not attempt to set them.

### Deliverable 2: Noise-floor experiment (D8)

**This is an experiment, not a document.** Run the current
`refactor/evidence-only-dense-commit` (or its merged descendant) 20 times on
the target baseline hardware under baseline JVM flags, on 3 workloads
(`SparseRandom / SmallMarket`, `HubAndSpoke / MultiCurrencyEconomy`,
`DenseLocal / LargeGlobal`), collecting every metric from D2. Report:
inter-run variance per metric per workload, cross-workload correlation,
distributional shape.

Only after this experiment is the noise floor known. Only then can D5's
threshold table be filled with defensible numbers.

Explicit non-decision: this RFC does not publish threshold numbers. Any
number published without noise-floor data is a fabrication.

### Deliverable 3: Rewritten `acceptance.md`

Replace the current 31-line sketch with a document that references this
RFC, cites the noise-floor experiment for thresholds, and points to
`docs/benchmarks/baselines/` for signed artifacts. Structure inversion:
`acceptance.md` becomes the operational manual; this RFC remains the
underlying rationale.

## Non-Goals

- Absolute throughput targets. These follow from noise-floor data.
- Migration to a distributed benchmark harness. Single-machine only;
  distributed Monte Carlo is a follow-up decision, out of scope.
- Continuous benchmarking on every PR. Signed baselines are established at
  named events (major refactor, release candidate), not on every merge.
- Prescribing a benchmarking framework beyond "JMH-shaped". Framework
  selection is a Deliverable 2 side product.

## Migration and Rollout

1. This RFC accepted → `docs/benchmarks/acceptance.md` marked "superseded
   pending RFC-0001 completion".
2. AB-SFC consultation → D1 fixture archetypes finalized.
3. Noise-floor experiment executed → D5 threshold table populated.
4. `Conformance.TwoSector` fixture available in `amor-fati-AB-SFC` →
   ADR-0002 Constraint 6 gate becomes exercisable.
5. First signed baseline under this protocol → Dense backend promoted from
   experimental to production-eligible.

Between steps 1 and 5 the Dense backend is not blocked from evolution.
Correctness gates (property-based Reference↔Dense equivalence, injected
failure atomicity, stale-snapshot rejection) remain the release blocker
until the benchmark gate is exercisable.

## Open Questions

1. **Fixture negotiation.** Are the proposed topology/batch archetypes in
   D1 aligned with `amor-fati-AB-SFC`'s expected SFC realism? Requires
   input from the AB-SFC track before D1 locks.
2. **Fork memory ceiling.** At 8M × N forks, single-machine memory is
   exceeded around N=10 (300 MB persistent state per fork). Does the
   ledger benchmark protocol prescribe distributed Monte Carlo, or
   defer to an upstream orchestration decision? Current recommendation:
   defer; measure single-fork cost only.
3. **Checkpoint format.** Serialization of `LedgerState` is out of ledger
   scope, but checkpoint throughput is a metric consumer. Who owns the
   format?
4. **AB-SFC-level paired benchmark.** Once `amor-fati-AB-SFC` has an
   executable core, does its benchmark protocol subsume or complement
   this one? Complement is simpler; subsume risks losing ledger
   attribution.
5. **Baseline hardware SKU.** Which machine class defines the reference?
   A single owned server? A specific cloud SKU? Cost/reproducibility
   trade-off pending.

## References

- ADR-0002: DOD-Native Execution Path (`docs/adr/0002-dod-native-execution-path.md`)
- `docs/benchmarks/acceptance.md` (to be superseded)
- `amor-fati-AB-SFC` RFC-0001: Economy-Neutral SFC-ABM Foundation
- PR #43: evidence-only Dense commit contract
