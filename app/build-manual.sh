#!/usr/bin/env bash
# Manual APK build for the Phase 1 MVP app, using only local SDK tools
# (aapt2 / javac / d8 / zipalign / apksigner). No network, no Gradle daemon.
#
# Why this exists: in the current build VM, Java-originated network access
# is blocked by the environment's egress policy, so Gradle cannot resolve
# the Android Gradle Plugin. On a normal machine, this project is a plain
# Android project (see README.md / build.gradle) and Gradle is canonical;
# this script is the fallback that produces the same APK here.
#
# Inputs (env or defaults):
#   ANDROID_HOME  (default: ~/workspace/toolchain/android-sdk)
#   JAVA_HOME     (default: ~/workspace/toolchain/jdk-21)
#   QUANTUS_SIGNING  "debug" (default) or "release".
#     Release mode signs with the distribution key instead of the
#     debug key. The keystore itself lives OUTSIDE this repository
#     (never commit it); its location and the location of the file
#     holding its password can be overridden with:
#       QUANTUS_RELEASE_KS          (default: ~/workspace/release-signing/quantus-release.jks)
#       QUANTUS_RELEASE_KS_PASS_FILE (default: ~/workspace/release-signing/keystore-password.txt)
#       QUANTUS_RELEASE_ALIAS       (default: quantus-release)
#     At signing time the password is loaded from that file into an
#     environment variable and handed to apksigner as env:<name>
#     (apksigner's file: source consumes one line per prompt, so it
#     cannot serve both the keystore and the key password from one
#     single-line file). No password value ever appears in this
#     script or on the command line.
# Prerequisite: the two .so files in native/ (see README.md)
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/workspace/toolchain/android-sdk}"
JAVA_HOME="${JAVA_HOME:-$HOME/workspace/toolchain/jdk-21}"
BT="$ANDROID_HOME/build-tools/35.0.0"
ANDROID_JAR="$ANDROID_HOME/platforms/android-35/android.jar"
export PATH="$JAVA_HOME/bin:$PATH"   # d8 / apksigner scripts call plain `java`
APP_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT_DIR="$APP_DIR/build/outputs/apk/debug"
OUT_APK="$OUT_DIR/quantus-node-debug.apk"
DEBUG_KS="$HOME/.android/debug.keystore"

# Signing mode (see header). Default stays the debug key so day-to-day
# development builds are unchanged; release builds must opt in.
SIGNING="${QUANTUS_SIGNING:-debug}"
if [ "$SIGNING" = "release" ]; then
  REL_KS="${QUANTUS_RELEASE_KS:-$HOME/workspace/release-signing/quantus-release.jks}"
  REL_PASS_FILE="${QUANTUS_RELEASE_KS_PASS_FILE:-$HOME/workspace/release-signing/keystore-password.txt}"
  REL_ALIAS="${QUANTUS_RELEASE_ALIAS:-quantus-release}"
  OUT_DIR="$APP_DIR/build/outputs/apk/release"
  OUT_APK="$OUT_DIR/quantus-node-release.apk"
elif [ "$SIGNING" != "debug" ]; then
  # versionCode 23 (audit finding A-3): even this rejection honours
  # P-5 — a failed build must never leave a stale APK at a final
  # path. The invalid mode does not say WHICH output the user
  # meant, so both candidate outputs are set aside before exiting.
  for cand in "$APP_DIR/build/outputs/apk/debug/quantus-node-debug.apk" \
              "$APP_DIR/build/outputs/apk/release/quantus-node-release.apk"; do
    if [ -f "$cand" ]; then
      mv -f "$cand" "$cand.prev"
    fi
    if [ -f "$cand.idsig" ]; then
      mv -f "$cand.idsig" "$cand.idsig.prev"
    fi
  done
  echo "ERROR: QUANTUS_SIGNING must be 'debug' or 'release' (got: $SIGNING)" >&2
  exit 1
fi

# versionCode 25 (audit finding M5): the work directory is private
# to this run — named for the signing mode AND this process — where
# versionCode 24 and earlier shared one build/manual for every run.
# Two builds running at once in the same tree (a release build and a
# debug build, say) used to delete each other's work directory at
# startup and, worse, shared the intermediate signed.apk: a release
# run could finish its verify, have the intermediate overwritten by
# a parallel debug run, and then move a DEBUG-signed APK onto the
# release path. With a private work directory, the file a run moves
# to the final path is always the very file that same run signed
# and verified (the final move itself is atomic — same filesystem).
# The directory is removed on success; a failed build leaves its
# own directory behind for diagnosis, never another run's. Defined
# here, after SIGNING and OUT_APK are final.
WORK="$APP_DIR/build/manual-$SIGNING-$$"

# P-5: a failed build must never leave a stale APK at the final path
# (it was reproduced being mistaken for the new build). Set the
# previous build aside first; the fresh APK is moved to $OUT_APK only
# after every step — signing included — has succeeded.
# External audit round 2 (F10): this block used to sit AFTER the
# keystore and native-library preflight checks, so a build that
# failed in one of those checks left the previous APK at the final
# path after all. $OUT_APK is final at this point, so the set-aside
# happens here — before every check that can fail the build.
if [ -f "$OUT_APK" ]; then
  mv -f "$OUT_APK" "$OUT_APK.prev"
fi
if [ -f "$OUT_APK.idsig" ]; then
  mv -f "$OUT_APK.idsig" "$OUT_APK.idsig.prev"
fi

if [ "$SIGNING" = "release" ]; then
  if [ ! -f "$REL_KS" ]; then
    echo "ERROR: release keystore not found: $REL_KS" >&2
    exit 1
  fi
  if [ ! -f "$REL_PASS_FILE" ]; then
    echo "ERROR: release keystore password file not found: $REL_PASS_FILE" >&2
    exit 1
  fi
fi

for so in libquantus_node.so libc++_shared.so; do
  if [ ! -f "$APP_DIR/native/$so" ]; then
    echo "ERROR: missing $APP_DIR/native/$so (see README.md)" >&2
    exit 1
  fi
done

rm -rf "$WORK"
mkdir -p "$WORK/classes" "$WORK/dex" "$WORK/stage/lib/arm64-v8a" "$OUT_DIR"

# 1) Debug keystore (same well-known debug key AGP uses), created once.
if [ ! -f "$DEBUG_KS" ]; then
  mkdir -p "$(dirname "$DEBUG_KS")"
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$DEBUG_KS" \
    -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi

# 2) Manifest for aapt2: AGP normally injects the package name and SDK
#    versions; for the manual build we inject them into a COPY (the
#    committed AndroidManifest.xml stays AGP-style).
sed 's|<manifest |<manifest package="app.quantusnode.node" |' \
  "$APP_DIR/src/main/AndroidManifest.xml" > "$WORK/AndroidManifest.xml"

# 3) Resources + aapt2 link: base APK with binary manifest. The app's
#    resources are the res/xml files referenced by the manifest
#    (data_extraction_rules.xml, network_security_config.xml);
#    compile the directory and pass the compiled output to link.
"$BT/aapt2" compile --dir "$APP_DIR/src/main/res" -o "$WORK/res.zip"
"$BT/aapt2" link -o "$WORK/base.apk" \
  -I "$ANDROID_JAR" \
  -A "$APP_DIR/assets" \
  --manifest "$WORK/AndroidManifest.xml" \
  --min-sdk-version 31 --target-sdk-version 35 \
  --version-code 25 --version-name 0.1.5 \
  "$WORK/res.zip"

# 4) Compile all app sources (plain Java + Android SDK APIs only).
"$JAVA_HOME/bin/javac" -source 17 -target 17 \
  -cp "$ANDROID_JAR" -d "$WORK/classes" \
  $(find "$APP_DIR/src/main/java" -name '*.java')

# 5) Dex.
"$BT/d8" --lib "$ANDROID_JAR" --min-api 31 \
  --output "$WORK/dex" $(find "$WORK/classes" -name '*.class')

# 6) Assemble: dex (deflated) + native libs (STORED, uncompressed, so the
#    installer extracts them to nativeLibraryDir — the manifest sets
#    extractNativeLibs=true — where the node can be executed by path).
cp "$WORK/base.apk" "$WORK/app.apk"
cp "$WORK/dex/classes.dex" "$WORK/stage/classes.dex"
cp "$APP_DIR/native/libquantus_node.so" \
   "$APP_DIR/native/libc++_shared.so" \
   "$WORK/stage/lib/arm64-v8a/"
( cd "$WORK/stage" && zip -q "$WORK/app.apk" classes.dex )
( cd "$WORK/stage" && zip -q -0 "$WORK/app.apk" lib/arm64-v8a/libquantus_node.so lib/arm64-v8a/libc++_shared.so )

# 7) Align (page-align .so) and sign (apksigner defaults; the
#    shipped APK carries a v3 signature only — verified with
#    `apksigner verify --verbose` on the v0.1.2 release APK,
#    versionCode 23 / audit finding 9-2).
"$BT/zipalign" -f -p 4 "$WORK/app.apk" "$WORK/aligned.apk"
if [ "$SIGNING" = "release" ]; then
  QUANTUS_KS_PASS="$(cat "$REL_PASS_FILE")"
  export QUANTUS_KS_PASS
  "$BT/apksigner" sign \
    --ks "$REL_KS" --ks-pass env:QUANTUS_KS_PASS \
    --ks-key-alias "$REL_ALIAS" --key-pass env:QUANTUS_KS_PASS \
    --out "$WORK/signed.apk" "$WORK/aligned.apk"
  unset QUANTUS_KS_PASS
else
  "$BT/apksigner" sign \
    --ks "$DEBUG_KS" --ks-pass pass:android \
    --ks-key-alias androiddebugkey --key-pass pass:android \
    --out "$WORK/signed.apk" "$WORK/aligned.apk"
fi

# 8) Verify what was just signed, BEFORE it may take the final
#    path (versionCode 23, audit finding 9-1): a signing step that
#    produced an unverifiable APK fails the build here (set -e),
#    instead of shipping silently.
"$BT/apksigner" verify "$WORK/signed.apk"
echo "Signature verified: $WORK/signed.apk"

# Success-only placement (P-5): only now does the new APK take the
# final path. On any earlier failure the path stays empty and the
# previous build remains available as *.prev for comparison.
mv -f "$WORK/signed.apk" "$OUT_APK"
if [ -f "$WORK/signed.apk.idsig" ]; then
  mv -f "$WORK/signed.apk.idsig" "$OUT_APK.idsig"
fi

echo "Built: $OUT_APK"
ls -lh "$OUT_APK"

# Success: this run's private work directory (finding M5) has served
# its purpose — remove it, so per-run directories do not accumulate.
# A failed build exits earlier under set -e and leaves its own
# directory in place for diagnosis.
rm -rf "$WORK"
