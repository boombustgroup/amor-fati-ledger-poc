# amor-fati-ledger-poc

Formally verified double-entry flow interpreter for SFC-ABM models.

## What is this?

A proof-of-concept for a verified accounting kernel that guarantees
Stock-Flow Consistency (SFC) by construction. Every monetary flow is a
debit/credit pair — the interpreter makes it mathematically impossible
to produce unbalanced books.

Built for [amor-fati](https://github.com/boombustgroup/amor-fati), a
macroeconomic SFC-ABM simulation engine.

## Why?

Testing is insufficient for SFC correctness. Empirical evidence from
amor-fati's Long migration (PR #114):

- ConsumerCredit P+I bug survived 1333 tests — found only by runtime crash
- workerShare Int/Int integer division caused 149M PLN accounting leak
- Every new random seed = potential SFC violation despite full test suite

Formal verification of the interpreter eliminates this entire class of bugs.

## Core properties

1. **Flow conservation**: total system wealth unchanged after any flow
2. **Frame condition**: accounts not involved in a flow are untouched
3. **Commutativity**: independent flows (disjoint accounts) can be reordered
4. **Distribution exactness**: `distribute(total, shares).sum == total` always
5. **Termination**: interpreter terminates for any finite flow list

## Stack

- Scala 3.8 / sbt
- Property-based testing (ScalaCheck)
- Target: [Stainless](https://epfl-lara.github.io/stainless/) + Z3 formal verification

## Run

```bash
sbt test
```

## License

Apache 2.0 — see [LICENSE](LICENSE).

## Part of the amor-fati ecosystem

- [amor-fati](https://github.com/boombustgroup/amor-fati) — macroeconomic SFC-ABM simulation engine
- **amor-fati-ledger-poc** — this repo (verified flow interpreter)
