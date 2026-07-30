# Verification Notes

This document captures the detailed verification boundaries and internal trust chain behind `amor-fati-ledger`.

## Verification Scope

The project has three layers with different levels of assurance.

### Layer 1: Formally Verified Reference Model

`src/main/scala-stainless/Verified.scala` is checked with Stainless + Z3. The latest verification run completes with all generated conditions valid.

| Property | What it guarantees | Proved by |
|---|---|---|
| **Flow conservation** | `balances(from) + balances(to)` unchanged after any flow | Z3 (pointwise) |
| **Frame condition** | All accounts not involved in the flow are untouched | Z3 (universal quantifier) |
| **Sequential application** | `applyFlowList` preserves conservation across any flow sequence | Z3 (structural induction) |
| **Distribution exactness** | Residual-plug distribution sums exactly to `total` for 2, 3, and general N-way list form | Z3 (residual plug) |
| **Proportional distribution model** | Exact-division, unit-with-residual, and floor-with-residual proportional list models are non-negative and sum exactly to `total` | Z3 |
| **Runtime apply semantics** | `Map[Int, Long]` runtime model preserves exact debit/credit + frame condition under anti-overflow preconditions | Z3 |
| **Pure interpreter semantics** | A Stainless `Map[Int, Long]` model matching the pure production interpreter is defined for single flows and flow lists, and refined to the checked runtime semantics under the same executable anti-overflow contract | Z3 |
| **Runtime sequential semantics** | `applyRuntimeFlowList` is formally defined for flow sequences that satisfy an explicit `canApplyRuntimeFlowList` anti-overflow contract | Z3 |
| **Runtime-bounded refinement step** | A `BigInt` model with `Long`-range bounds is formally shown to refine to the pure `applyFlow` reference semantics for both single flows and executable flow lists | Z3 |
| **Commutativity** | Flows on disjoint accounts produce the same result in any order in `BigInt`, runtime `Int/Long`, and pure interpreter `Map[Int, Long]` models | Z3 |

This is the reference model: primarily pure `Map[BigInt, BigInt]`, plus a verified `Map[Int, Long]` runtime model with explicit anti-overflow preconditions, a verified pure-interpreter semantics layer, a verified sequential runtime contract, and a bounded `BigInt` refinement layer that makes the runtime range assumptions explicit. No arrays, no mutation.

### Layer 2: Production Code Tested Against Reference

Production implementations are not themselves formally verified. They are tested against shared models and contracts.

- **`Interpreter.scala`** — property-based tests, explicit `canApplyFlow` / `canApplyAll` overflow contracts, checked entrypoints, and a test bridge against an embedded `BigInt` reference shape for non-overflow inputs
- **`TransferExecutor`** — contract tests for topology lookup, currency compatibility, permissions, bounds, sequence atomicity, lifecycle evidence, and overflow handling
- **`LedgerStateExecutor`** — immutable snapshot semantics, stale-version rejection, atomic sequence commits, and input/output evidence versions
- **`DenseLedgerBackend`** — index-resolved staged array execution with transfer-log and aggregated evidence modes, tested against snapshot semantics
- **`Transfer.scala`** — typed account-to-account transfer contract with currency compatibility and debit/credit permission checks
- **`MutableWorldState.scala`** — direct contract tests for sparse snapshots, per-asset totals, key separation, checked reads/writes, and backing-array reuse
- **`DistributeModel.scala`** — canonical pure executable semantics for production floor-with-residual distribution, with `BigInt` internal accumulation to avoid hidden `Long` overflow in share-sum calculations
- **`Distribute.scala`** — thin production adapter over `DistributeModel`
- **`DistributeReference.scala`** — thin compatibility adapter over `DistributeModel`

### Chain of Trust

```text
Stainless/Z3 proves → Verified.scala (reference model)
Verified.scala proves → pure Map[Int, Long] interpreter semantics and flow-list refinement under executable anti-overflow preconditions
InterpreterVerifiedBridgeSpec tests → Interpreter == embedded BigInt reference model (non-overflow inputs)
InterpreterVerifiedBridgeSpec tests → Interpreter.applyAll == embedded BigInt reference model for non-overflow sequences
GenericContractSpec tests → TransferExecutor == Interpreter reference semantics for valid transfers
InterpreterPropertySpec tests → Interpreter checks analogous properties to Verified.scala
DistributeSpec tests → Distribute, DistributeReference, and DistributeModel share the same floor-with-residual semantics
DistributeVerifiedBridgeSpec tests → DistributeModel == Verified floor-with-residual BigInt list shape
```

Important distinction: `EquivalenceSpec` is a test, not a formal proof. It provides strong empirical evidence, not mathematical certainty.

### Layer 3: Not Yet Formally Verified

- `DistributeModel.scala` is the canonical population-neutral numeric helper and the primary bridge target for the Stainless proof shape, but there is still no direct Stainless proof over a production mutable implementation
- `MutableWorldState` is not formally verified; direct contract tests cover storage semantics and checked access helpers, but internal package code can still reach mutable backing arrays for performance-sensitive paths
- Direct proof bridge between runtime `Int/Long` and `BigInt` models is still partial; a bounded `BigInt` refinement step exists, but not a fully general cross-type embedding
- Overflow safety is explicit on the pure interpreter path, but higher-level callers still mostly preserve these contracts rather than constructing them through dedicated bounded domain types

## Why Pointwise, Not Global Sum?

Global `balances.values.sum == const` requires induction lemmas over arbitrary-size maps, which can stall SMT solvers. Instead, the proof uses pointwise conservation on the two touched accounts plus the frame condition for every other account. Together, those imply global conservation while staying solver-friendly.
