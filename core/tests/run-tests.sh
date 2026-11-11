#!/usr/bin/env bash
# Plain-JVM harness + Python cross-implementation check for the
# DePIN core module (:depin-core). Moved with the core from the
# companion app (integration design section 2.2: the tests move
# with the code); the companion keeps its own copy and harness.
#
#   core/tests/run-tests.sh           dependency check + harness only
#   core/tests/run-tests.sh --cross   + crosscheck.py
set -euo pipefail
JAVA_HOME="${JAVA_HOME:-$HOME/workspace/toolchain/jdk-21}"
CORE="$(cd "$(dirname "$0")/.." && pwd)"
CORE_DIR="$CORE/src/main/java/app/quantusnode/node/depin/core"
OUT="${TMPDIR:-/tmp}/depin-core-tests-$$"
mkdir -p "$OUT"

# Dependency-direction check first (integration design section
# 2.1: the import inspection runs at harness time too, not only
# in the build routes).
bash "$CORE/check-depin-deps.sh" core

"$JAVA_HOME/bin/javac" -d "$OUT" \
  "$CORE_DIR/Hex.java" \
  "$CORE_DIR/Json.java" \
  "$CORE_DIR/Ed25519.java" \
  "$CORE_DIR/DepinCore.java" \
  "$CORE_DIR/RpcClient.java" \
  "$CORE_DIR/ObservationPlausibility.java" \
  "$CORE_DIR/SegmentPolicy.java" \
  "$CORE_DIR/ServiceGeneration.java" \
  "$CORE_DIR/SeedVault.java" \
  "$CORE_DIR/AesGcmSeedBox.java" \
  "$CORE_DIR/JournalStore.java" \
  "$CORE_DIR/CompanionStatus.java" \
  "$CORE_DIR/PendingExport.java" \
  "$CORE_DIR/CrashLog.java" \
  "$CORE/tests/app/quantusnode/node/depin/core/HarnessMain.java" \
  "$CORE/tests/app/quantusnode/node/depin/core/BundleGenMain.java" \
  "$CORE/tests/app/quantusnode/node/depin/core/BundleVerifyMain.java"

"$JAVA_HOME/bin/java" -cp "$OUT" app.quantusnode.node.depin.core.HarnessMain

if [ "${1:-}" = "--cross" ]; then
  DEPIN_CORE_TEST_CP="$OUT" python3 "$CORE/tests/crosscheck.py"
fi

rm -rf "$OUT"
