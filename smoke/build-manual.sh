#!/usr/bin/env bash
# Manual APK build for the smoke app, using only local SDK tools
# (aapt2 / javac / d8 / zipalign / apksigner). No network, no Gradle daemon.
#
# Why this exists: in the current build VM, Java-originated network access is
# blocked by the environment's egress policy, so Gradle cannot resolve the
# Android Gradle Plugin. On a normal machine, `./gradlew assembleDebug`
# (same applicationId / manifest / sources) is the canonical build and this
# script is only a fallback. Both produce a debug-signed APK.
#
# Inputs (env or defaults):
#   ANDROID_HOME  (default: ~/workspace/toolchain/android-sdk)
#   JAVA_HOME     (default: ~/workspace/toolchain/jdk-21)
# Prerequisite: the two .so files in src/main/jniLibs/arm64-v8a/ (see README.md)
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/workspace/toolchain/android-sdk}"
JAVA_HOME="${JAVA_HOME:-$HOME/workspace/toolchain/jdk-21}"
BT="$ANDROID_HOME/build-tools/35.0.0"
ANDROID_JAR="$ANDROID_HOME/platforms/android-35/android.jar"
export PATH="$JAVA_HOME/bin:$PATH"   # d8 / apksigner scripts call plain `java`
APP_DIR="$(cd "$(dirname "$0")" && pwd)"
WORK="$APP_DIR/build/manual"
OUT_DIR="$APP_DIR/build/outputs/apk/debug"
OUT_APK="$OUT_DIR/smoke-debug.apk"
DEBUG_KS="$HOME/.android/debug.keystore"

for so in libquantus_node.so libc++_shared.so; do
  if [ ! -f "$APP_DIR/src/main/jniLibs/arm64-v8a/$so" ]; then
    echo "ERROR: missing $APP_DIR/src/main/jniLibs/arm64-v8a/$so (see README.md)" >&2
    exit 1
  fi
done

rm -rf "$WORK"
mkdir -p "$WORK/classes" "$WORK/dex" "$WORK/stage/lib/arm64-v8a" "$OUT_DIR"

# P-5 (same countermeasure as the app build): a failed build must
# never leave a stale APK at the final path (it was reproduced being
# mistaken for the new build). Set the previous build aside first;
# the fresh APK is moved to $OUT_APK only after every step —
# signing included — has succeeded.
if [ -f "$OUT_APK" ]; then
  mv -f "$OUT_APK" "$OUT_APK.prev"
fi
if [ -f "$OUT_APK.idsig" ]; then
  mv -f "$OUT_APK.idsig" "$OUT_APK.idsig.prev"
fi

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
sed 's|<manifest |<manifest package="app.quantusnode.smoke" |' \
  "$APP_DIR/src/main/AndroidManifest.xml" > "$WORK/AndroidManifest.xml"

# 3) aapt2 link: base APK with binary manifest (no resources in this app).
"$BT/aapt2" link -o "$WORK/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$WORK/AndroidManifest.xml" \
  --min-sdk-version 31 --target-sdk-version 35 \
  --version-code 1 --version-name 0.1-smoke

# 4) Compile the Activity.
"$JAVA_HOME/bin/javac" -source 17 -target 17 \
  -cp "$ANDROID_JAR" -d "$WORK/classes" \
  "$APP_DIR/src/main/java/app/quantusnode/smoke/MainActivity.java"

# 5) Dex.
"$BT/d8" --lib "$ANDROID_JAR" --min-api 31 \
  --output "$WORK/dex" $(find "$WORK/classes" -name '*.class')

# 6) Assemble: dex (deflated) + native libs (STORED, like legacy packaging,
#    so the installer extracts them to nativeLibraryDir for exec by path).
cp "$WORK/base.apk" "$WORK/app.apk"
cp "$WORK/dex/classes.dex" "$WORK/stage/classes.dex"
cp "$APP_DIR/src/main/jniLibs/arm64-v8a/libquantus_node.so" \
   "$APP_DIR/src/main/jniLibs/arm64-v8a/libc++_shared.so" \
   "$WORK/stage/lib/arm64-v8a/"
( cd "$WORK/stage" && zip -q "$WORK/app.apk" classes.dex )
( cd "$WORK/stage" && zip -q -0 "$WORK/app.apk" lib/arm64-v8a/libquantus_node.so lib/arm64-v8a/libc++_shared.so )

# 7) Align (page-align .so) and sign (v1+v2+v3 via apksigner defaults).
"$BT/zipalign" -f -p 4 "$WORK/app.apk" "$WORK/aligned.apk"
"$BT/apksigner" sign \
  --ks "$DEBUG_KS" --ks-pass pass:android \
  --ks-key-alias androiddebugkey --key-pass pass:android \
  --out "$WORK/signed.apk" "$WORK/aligned.apk"

# Success-only placement (P-5): only now does the new APK take the
# final path. On any earlier failure the path stays empty and the
# previous build remains available as *.prev for comparison.
mv -f "$WORK/signed.apk" "$OUT_APK"
if [ -f "$WORK/signed.apk.idsig" ]; then
  mv -f "$WORK/signed.apk.idsig" "$OUT_APK.idsig"
fi

echo "Built: $OUT_APK"
ls -lh "$OUT_APK"
