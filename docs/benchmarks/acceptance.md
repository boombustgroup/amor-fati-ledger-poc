# DOD Backend Benchmark Acceptance

This document defines the initial benchmark protocol for promoting the dense
ledger backend. It is a protocol, not a claim that the thresholds have already
been met.

## Workloads

Run each workload with 100k, 1M, and at least 8M accounts, plus the largest
planned economy fixture when larger. Cover sparse and dense transfer plans,
valid plans, and rejection plans that fail during preflight. Use fixed seeds,
fixed JVM settings, and a recorded commit for every run.

## Metrics

- sustained committed transfers per second;
- p99 latency per batch;
- peak resident memory;
- allocated bytes per committed transfer;
- rejection latency and allocation rate.

The `Map` interpreter is the semantic reference, not the performance target.
Each dense result must first pass reference-equivalence checks.

## Release Gate

The first accepted run establishes a signed baseline per workload and scale.
Subsequent releases fail the gate when any metric regresses by more than 10%
against its matching baseline, or when reference equivalence fails. Thresholds
for absolute throughput, memory, and latency are recorded with the first signed
baseline after representative hardware and JVM settings are selected.
