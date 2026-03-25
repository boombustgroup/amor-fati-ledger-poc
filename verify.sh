#!/usr/bin/env bash
set -euo pipefail

STAINLESS_DIR="${STAINLESS_DIR:-/tmp/stainless-standalone}"

if [ ! -f "$STAINLESS_DIR/stainless" ]; then
  echo "Stainless not found at $STAINLESS_DIR. Set STAINLESS_DIR or install from:"
  echo "  https://github.com/epfl-lara/stainless/releases"
  exit 1
fi

echo "Running Stainless verification..."
"$STAINLESS_DIR/stainless" --solvers=smt-z3 --timeout=180 \
  src/main/scala-stainless/Verified.scala

echo "All properties verified."
