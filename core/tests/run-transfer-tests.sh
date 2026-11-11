#!/usr/bin/env bash
# JVM harness for the transfer machinery (integration design
# section 4, build step 4): compiles ALL core sources plus the
# transfer harness and runs it. The baseline suite stays in
# run-tests.sh; this runner exists so the transfer tests can
# compile the whole core (the state machines cross-reference
# every core class) without changing the baseline list.
set -euo pipefail
JAVA_HOME="${JAVA_HOME:-$HOME/workspace/toolchain/jdk-21}"
CORE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/depin-transfer-tests-$$"
mkdir -p "$OUT"

bash "$CORE/check-depin-deps.sh" core

"$JAVA_HOME/bin/javac" -d "$OUT" \
  $(find "$CORE/src/main/java" -name '*.java') \
  "$CORE/tests/app/quantusnode/node/depin/core/TransferHarnessMain.java"

"$JAVA_HOME/bin/java" -cp "$OUT" \
  app.quantusnode.node.depin.core.TransferHarnessMain

rm -rf "$OUT"
