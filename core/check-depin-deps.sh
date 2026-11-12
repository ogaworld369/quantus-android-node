#!/usr/bin/env bash
# DePIN dependency-direction check (integration design section 2.1).
#
# One script, used by every build route — the Gradle builds (via the
# verifyDepinDeps tasks in core/build.gradle and app/build.gradle),
# app/build-manual.sh, and the core harness (core/tests/run-tests.sh).
# A check that exists on only one route is not enforcement.
#
# Usage: check-depin-deps.sh [core|app|all]   (default: all)
#
#   core  The device-independent core (core/src/main/java, package
#         app.quantusnode.node.depin.core) must not reference
#         android.* or app.quantusnode.node.* outside its own
#         package: no imports, no fully-qualified names, no
#         class-name strings for reflection. The core compiles on a
#         plain JVM; this check keeps it that way at the source
#         level too.
#   app   The DePIN Android layer inside the app module
#         (app/src/main/java/app/quantusnode/node/depin/**, the
#         core package excluded — it lives in the core module) must
#         not reference the node internals: no imports of
#         app.quantusnode.node.* outside the depin packages, no
#         fully-qualified names, no class-name strings naming
#         NodeService / NodeState. There are no such sources yet
#         (build step 2 creates them); the check passes vacuously
#         and reports the file count so the wiring is exercised
#         from day one. The app scope ALSO checks the main
#         process (the node package's top-level classes) for the
#         G-01 invariant: no reference to DepinTransferRuntime
#         and no call to a DepinSettings writer — depin_settings
#         is written only from the :depin process. Since final
#         check round 3 (H-05) that matching runs on
#         comment-stripped, newline-flattened source (folded
#         calls and static imports are caught), and the depin
#         package itself is allowlisted: only the classes that
#         run in the :depin process may call a writer, and only
#         DepinSettings may open the preferences file.
#
# The repository root is the parent of this script's directory;
# DEPIN_CHECK_ROOT overrides it (used by the script's own tests).
#
# Limits, stated plainly: this is a lexical source check. It does
# not see through string concatenation or indirect reflection
# (e.g. Class.forName built from fragments). It is the mechanical
# tripwire the design asks for, not a proof.
set -euo pipefail

SCOPE="${1:-all}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="${DEPIN_CHECK_ROOT:-$(cd "$SCRIPT_DIR/.." && pwd)}"
CORE_SRC="$ROOT/core/src/main/java"
APP_DEPIN_SRC="$ROOT/app/src/main/java/app/quantusnode/node/depin"

fail=0
err() { echo "check-depin-deps: ERROR: $*" >&2; fail=1; }

check_core() {
  local files n=0
  files="$(find "$CORE_SRC" -name '*.java')"
  for f in $files; do
    n=$((n+1))
    # imports of android.* or of the app package outside the core package
    if grep -nE '^\s*import\s+(android\.|app\.quantusnode\.node\.)' "$f" \
         | grep -v 'app\.quantusnode\.node\.depin\.core\.' ; then
      err "$f: forbidden import (core must not import android.* or app.quantusnode.node.* outside its own package)"
    fi
    # fully-qualified references anywhere (code or strings)
    if grep -nE 'android\.[a-z]' "$f"; then
      err "$f: fully-qualified android.* reference"
    fi
    if grep -nE 'app\.quantusnode\.node\.' "$f" \
         | grep -v 'app\.quantusnode\.node\.depin\.core' ; then
      err "$f: reference to app.quantusnode.node.* outside the core package"
    fi
  done
  echo "check-depin-deps: core: $n file(s) checked"
}

check_app() {
  local files n=0
  if [ -d "$APP_DEPIN_SRC" ]; then
    files="$(find "$APP_DEPIN_SRC" -name '*.java')"
    for f in $files; do
      n=$((n+1))
      if grep -nE '^\s*import\s+app\.quantusnode\.node\.' "$f" \
           | grep -v 'app\.quantusnode\.node\.depin\.' ; then
        err "$f: DePIN Android layer imports node internals (only :depin-core, android.*, java.* and the depin packages are allowed)"
      fi
      if grep -nE 'app\.quantusnode\.node\.[A-Z]' "$f" \
           | grep -v 'app\.quantusnode\.node\.depin\.' ; then
        err "$f: fully-qualified reference to node internals"
      fi
      if grep -nE '"[^"]*(NodeService|NodeState)"|Class\.forName\("[^"]*(NodeService|NodeState)' "$f"; then
        err "$f: reflection-style reference to NodeService/NodeState by name"
      fi
    done
  fi
  echo "check-depin-deps: app layer: $n file(s) checked"
}

# Flatten a Java source file for pattern matching: strip
# block and line comments (string- and char-literal aware)
# and remove newlines, so a call folded across lines — the
# shape final check round 3 (H-05) showed the original
# line-based greps could not see — matches exactly like its
# one-line form. Comment text (which legitimately NAMES the
# writers when documenting them) never matches.
flatten_java() {
  perl -0777 -pe '
    my $out = "";
    my $i = 0; my $len = length($_);
    while ($i < $len) {
      my $c = substr($_, $i, 1);
      my $d = $i + 1 < $len ? substr($_, $i + 1, 1) : "";
      if ($c eq "\"" || $c eq "\x27") {
        my $q = $c; $out .= $c; $i++;
        while ($i < $len) {
          my $e = substr($_, $i, 1);
          $out .= $e; $i++;
          last if $e eq $q && substr($_, $i - 2, 1) ne "\\";
          if ($e eq "\\") { $out .= substr($_, $i, 1); $i++; }
        }
      } elsif ($c eq "/" && $d eq "*") {
        $i += 2; $i++ while $i + 1 < $len && !(substr($_, $i, 1) eq "*" && substr($_, $i + 1, 1) eq "/");
        $i += 2;
      } elsif ($c eq "/" && $d eq "/") {
        $i++ while $i < $len && substr($_, $i, 1) ne "\n";
      } else {
        $out .= $c; $i++;
      }
    }
    $out =~ s/\s+/ /g;
    print $out;
  ' "$1"
}

# The DepinSettings writer methods (the class's mutators).
DEPIN_SETTINGS_WRITERS='setEnabled|setDisabled|bindActiveIdentity|recordSelfStop|clearSelfStopRecord|recordAttemptLogReset|recordKnownCheckpointStats|recordNotifStrings'

check_main() {
  # G-01 (final check round 2): the invariant "DepinSettings
  # is written only from the :depin process". The main
  # process — the node package's top-level classes — is
  # display-only for DePIN state: it must not reference
  # DepinTransferRuntime (whose binding sync WRITES
  # depin_settings) nor call any DepinSettings writer.
  # Readers are fine: a stale read can mislead a label, but a
  # stale WRITE commits the reader's whole in-memory settings
  # map back over the :depin process's newer state.
  #
  # H-05 (final check round 3): the original line-based
  # greps missed the F-03 shapes — a writer call folded
  # across lines, a writer reached through a static import,
  # the preferences file opened directly by name, and a
  # synchronous call routed through a facade or helper.
  # Matching now runs on comment-stripped, newline-flattened
  # source, and the depin package itself is allowlisted
  # (check_settings_writers below): only the classes that
  # run in the :depin process may call a writer at all, so a
  # facade (DepinModule), the IPC client
  # (DepinTransferClient) or any future helper calling one
  # synchronously from the main process fails here even
  # though its own file lives in the depin package.
  local dir="$ROOT/app/src/main/java/app/quantusnode/node"
  local files n=0
  files="$(find "$dir" -maxdepth 1 -name '*.java')"
  for f in $files; do
    n=$((n+1))
    local flat
    flat="$(flatten_java "$f")"
    if printf '%s' "$flat" | grep -qE 'DepinTransferRuntime'; then
      err "$f: main process references DepinTransferRuntime (the binding sync belongs on the :depin side; see G-01)"
    fi
    if printf '%s' "$flat" | grep -qE "DepinSettings[[:space:]]*\.[[:space:]]*($DEPIN_SETTINGS_WRITERS)[[:space:]]*\("; then
      err "$f: main process calls a DepinSettings writer (DepinSettings is written only from the :depin process; see G-01)"
    fi
    if printf '%s' "$flat" | grep -qE 'import[[:space:]]+static[[:space:]]+app\.quantusnode\.node\.depin\.DepinSettings'; then
      err "$f: main process statically imports DepinSettings members (a statically imported writer call is still a main-process write; see G-01/H-05)"
    fi
    if printf '%s' "$flat" | grep -qE 'getSharedPreferences\([[:space:]]*"depin_settings"'; then
      err "$f: main process opens the depin_settings preferences directly (DepinSettings is written only from the :depin process; see G-01)"
    fi
    if printf '%s' "$flat" | grep -qE '"depin_settings"'; then
      err "$f: main process names the depin_settings preferences file (only DepinSettings may; see G-01/H-05)"
    fi
  done
  echo "check-depin-deps: main process: $n file(s) checked"
}

check_settings_writers() {
  # The depin-package half of the G-01/H-05 allowlist: a
  # DepinSettings writer may be CALLED only from the classes
  # that execute in the :depin process (the observer
  # service, the transfer service and the transfer runtime),
  # and the preferences file may be OPENED only by
  # DepinSettings itself. Every other class in the package —
  # the main-process facade and client included — is a
  # reader. Runs on the same flattened source as check_main.
  local dir="$APP_DEPIN_SRC"
  [ -d "$dir" ] || return 0
  local files n=0
  files="$(find "$dir" -name '*.java')"
  for f in $files; do
    n=$((n+1))
    local base flat
    base="$(basename "$f")"
    flat="$(flatten_java "$f")"
    case "$base" in
      DepinObserverService.java|DepinTransferService.java|DepinTransferRuntime.java|DepinSettings.java)
        ;;
      *)
        if printf '%s' "$flat" | grep -qE "DepinSettings[[:space:]]*\.[[:space:]]*($DEPIN_SETTINGS_WRITERS)[[:space:]]*\("; then
          err "$f: calls a DepinSettings writer outside the :depin-process allowlist (G-01/H-05)"
        fi
        ;;
    esac
    if [ "$base" != "DepinSettings.java" ]; then
      if printf '%s' "$flat" | grep -qE 'getSharedPreferences\([[:space:]]*"depin_settings"'; then
        err "$f: opens the depin_settings preferences directly (only DepinSettings may; see G-01/H-05)"
      fi
      if printf '%s' "$flat" | grep -qE 'import[[:space:]]+static[[:space:]]+app\.quantusnode\.node\.depin\.DepinSettings'; then
        err "$f: statically imports DepinSettings members (see G-01/H-05)"
      fi
    fi
  done
  echo "check-depin-deps: settings writers: $n file(s) checked"
}

case "$SCOPE" in
  core) check_core ;;
  app)  check_app; check_main; check_settings_writers ;;
  all)  check_core; check_app; check_main; check_settings_writers ;;
  *) echo "usage: $0 [core|app|all]" >&2; exit 2 ;;
esac

if [ "$fail" != "0" ]; then
  echo "check-depin-deps: FAILED" >&2
  exit 1
fi
echo "check-depin-deps: OK ($SCOPE)"
