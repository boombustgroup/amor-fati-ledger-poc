# amor-fati-ledger-poc

Double-entry flow interpreter for Stock-Flow Consistent (SFC) agent-based models, with a formally verified reference core.

Built for [amor-fati](https://github.com/boombustgroup/amor-fati), a macroeconomic SFC-ABM simulation engine.

## Verification scope

The project has three layers with different levels of assurance:

### Layer 1: Formally verified reference model (Stainless + Z3)

`src/main/scala-stainless/Verified.scala` — mathematical proofs verified by Z3 SMT solver. The latest verification run completes with all generated conditions valid.

| Property | What it guarantees | Proved by |
|---|---|---|
| **Flow conservation** | `balances(from) + balances(to)` unchanged after any flow | Z3 (pointwise) |
| **Frame condition** | All accounts not involved in the flow are untouched | Z3 (universal quantifier) |
| **Sequential application** | `applyFlowList` preserves conservation across any flow sequence | Z3 (structural induction) |
| **Distribution exactness** | Residual-plug distribution sums exactly to `total` for 2, 3, and general N-way list form | Z3 (residual plug) |
| **Proportional distribution model** | Exact-division, unit-with-residual, and floor-with-residual proportional list models are non-negative and sum exactly to `total` | Z3 |
| **Runtime apply semantics** | `Map[Int, Long]` runtime model preserves exact debit/credit + frame condition under anti-overflow preconditions | Z3 |
| **Runtime sequential semantics** | `applyRuntimeFlowList` is formally defined for flow sequences that satisfy an explicit `canApplyRuntimeFlowList` anti-overflow contract | Z3 |
| **Runtime-bounded refinement step** | A `BigInt` model with `Long`-range bounds is formally shown to refine to the pure `applyFlow` reference semantics for both single flows and executable flow lists | Z3 |
| **Commutativity** | Flows on disjoint accounts produce the same result in any order in both `BigInt` and runtime `Int/Long` models | Z3 |

This is the reference model — primarily pure `Map[BigInt, BigInt]`, plus a verified `Map[Int, Long]` runtime model with explicit anti-overflow preconditions, a verified sequential runtime contract, and a bounded `BigInt` refinement layer that makes the runtime range assumptions explicit. No arrays, no mutation. A true formal proof.

### Layer 2: Production code tested against reference (ScalaCheck + equivalence)

Production implementations are **not themselves formally verified**. They are tested for correctness:

- **`Interpreter.scala`** (pure Map-based) — property-based tests (ScalaCheck, 100+ random scenarios per property), plus a test bridge against an embedded `BigInt` reference-model shape for non-overflow inputs
- **`ImperativeInterpreter.scala`** (Array-based, fast) — tested for bit-for-bit equivalence with both `Interpreter.scala` and a pure runtime reference model via `EquivalenceSpec`, with runtime validation of batch dimensions, indices, and non-negative amounts
- **`Distribute.scala`** (N-way distribution with floor-based residual plug) — property-based tests checking `sum == total`, non-negativity, and exact equivalence with a pure reference model

The chain of trust:

```
Stainless/Z3 proves → Verified.scala (reference model)
InterpreterVerifiedBridgeSpec tests → Interpreter == embedded BigInt reference model (non-overflow inputs)
EquivalenceSpec tests → RuntimeInterpreterReference == Interpreter (bit-for-bit)
EquivalenceSpec tests → ImperativeInterpreter == RuntimeInterpreterReference (bit-for-bit)
InterpreterPropertySpec tests → Interpreter checks analogous properties to Verified.scala
```

**Important distinction:** `EquivalenceSpec` is a test, not a formal proof. It provides strong empirical evidence but not mathematical certainty that the production interpreter matches the verified model.

### Layer 3: Not yet formally verified

- Residual-plug N-way distribution and proportional floor-with-residual list models are formally verified in `Verified.scala`; production `Distribute.scala` now uses the same floor-with-residual shape, but is still only tested, not formally linked to the model
- Batch dimensions, sender/target index bounds, and non-negative amounts — enforced at runtime by `ImperativeInterpreter.validateBatch`, not formally verified
- `MutableWorldState` — tested via equivalence, not formally verified (mutable state is hard to verify in SMT solvers)
- Direct proof bridge between runtime `Int/Long` model and `BigInt` reference model — not yet fully formalized in Stainless; a bounded `BigInt` refinement step now exists, but direct cross-type embedding for `Int/Long -> BigInt` is still missing

### Why pointwise, not global sum?

Global `balances.values.sum == const` requires induction lemmas over arbitrary-size maps, which can stall SMT solvers. Instead, we prove **pointwise conservation** (the two involved accounts sum to the same value) plus the **frame condition** (everything else unchanged). This implies global conservation and verifies in <1 second.

## Architecture

```
src/
  main/
    scala/                    # Production code (sbt compile + test)
      com/boombustgroup/ledger/
        Flow.scala            # Single flow: from, to, amount, mechanism
        BatchedFlow.scala     # Sealed trait: Scatter (N:M) + Broadcast (1:N)
        EntitySector.scala    # Population types: Households, Firms, Banks, ...
        AssetType.scala       # Balance types: DemandDeposit, FirmLoan, ...
        MechanismId.scala     # Opaque type for audit trail (generic, not model-specific)
        Interpreter.scala     # Pure functional interpreter (Map-based)
        ImperativeInterpreter.scala  # Production interpreter (Array-based, fast)
        MutableWorldState.scala      # Array[Long] per (sector, asset) — DOD
        Distribute.scala      # Proportional distribution with residual plug
    scala-stainless/          # Formally verified reference model (Stainless standalone)
      Verified.scala          # Post-conditions verified by Z3
  test/
    scala/
      com/boombustgroup/ledger/
        InterpreterSpec.scala         # Unit tests
        InterpreterPropertySpec.scala # ScalaCheck property-based tests
        DistributeSpec.scala          # Distribution exactness tests
        EquivalenceSpec.scala         # Pure == Imperative bit-for-bit equivalence
```

## Run

```bash
# Tests (24 tests, property-based + equivalence)
sbt test

# Formal verification (requires Stainless standalone + Z3)
./verify.sh
```

## Stack

- **Scala 3.8** (Stainless standalone bundles its own 3.7.2 compiler)
- **Stainless** (EPFL) — formal verification for Scala, powered by Z3
- **Z3** (Microsoft Research) — SMT solver
- **ScalaCheck** — property-based testing
- **Long-based arithmetic** — all amounts are `Long` (scale 10^4), avoiding floating-point error within bounded integer arithmetic

## Part of the amor-fati ecosystem

- [amor-fati](https://github.com/boombustgroup/amor-fati) — macroeconomic SFC-ABM simulation engine
- **amor-fati-ledger-poc** — this repo (ledger POC with a formally verified reference core)

## License

Apache 2.0 — Copyright 2026 [BoomBustGroup](https://www.boombustgroup.com/)
