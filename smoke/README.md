# Quantus Node — Smoke Test App

Minimal Android app whose only job is to answer one question on a real device
(first target: Pixel 9a / GrapheneOS):

> Does the Android (aarch64) build of `quantus-node` start at all?

The app bundles the stripped `quantus-node` binary as a "native library".
Tapping **Run --version** executes it as a child process and shows stdout,
stderr and the exit code — or the startup error, unchanged. If direct exec
fails, it automatically retries through `/system/bin/linker64` and shows that
result too. Nothing is hidden: on an untested platform, the failure mode is
the information we need.

- applicationId: `app.quantusnode.smoke`
- minSdk 31 / targetSdk 35 / arm64-v8a only
- **No permissions at all** (not even INTERNET — `--version` needs none)
- Plain framework Activity (no Compose, no AndroidX)

## Reproducing the bundled binaries (not in git)

The two `.so` files in `src/main/jniLibs/arm64-v8a/` are ~124 MB together and
are **not committed**. Recreate them exactly as follows:

```sh
NDK=~/workspace/toolchain/android-ndk          # r27d
LLVM=$NDK/toolchains/llvm/prebuilt/linux-x86_64
SRC=~/workspace/toolchain/spike/chain/target/aarch64-linux-android/debug/quantus-node
DST=src/main/jniLibs/arm64-v8a

# 1) quantus-node: copy the cross-built binary, strip the COPY (never the original)
cp "$SRC" $DST/libquantus_node.so
$LLVM/bin/llvm-strip $DST/libquantus_node.so
#    1,072,804,072 bytes -> 122,601,984 bytes (stripped)

# 2) libc++_shared.so: required at runtime (it is in quantus-node's NEEDED list)
cp $LLVM/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so $DST/
```

How the node binary itself was cross-built is documented in
[`../docs/BUILD-android.md`](../docs/BUILD-android.md).

Why the name `libquantus_node.so`: files under `jniLibs` are extracted to the
app's `nativeLibraryDir`, which is one of the few locations Android allows
executing from (the app-private data dir is mounted noexec for code). The
Gradle build sets `useLegacyPackaging = true` (and the manual build stores
the `.so` files uncompressed) so extraction really happens.

## Building the APK

### Canonical: Gradle

```sh
./gradlew assembleDebug
# -> build/outputs/apk/debug/smoke-debug.apk (debug-signed)
```

Uses Gradle 8.9 (wrapper included) + AGP 8.7.3, JDK 17+.

**Status in the current build VM: blocked by the environment, not the
project.** Java-originated network connections from this VM are denied by
the runtime egress policy ("Other TCP connections is turned off for this
assistant", `other_tcp` = Deny in Muse's permission settings): the Gradle
daemon protocol exchange is corrupted by an injected policy message, and
plugin/dependency downloads die with connection resets. curl/Python traffic
is unaffected. On any normal machine (or with that permission relaxed) the
Gradle build above is expected to work as-is; nothing in the project is
adapted to this VM except `gradle.properties`, which deliberately omits
`org.gradle.jvmargs` so the launcher JVM can run the build in-process
(matching the default daemon args via `GRADLE_OPTS`):

```sh
GRADLE_OPTS="-Xms256m -Xmx512m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8" \
  ./gradlew assembleDebug --no-daemon
```

### Fallback used for the current APK: manual SDK-tools pipeline

`build-manual.sh` builds the same APK (same manifest, sources and libs,
debug key) with only local tools — aapt2 → javac → d8 → zip → zipalign →
apksigner. No network, no daemon.

```sh
./build-manual.sh
# -> build/outputs/apk/debug/smoke-debug.apk
```

One deliberate difference from AGP: AGP injects the package name and SDK
versions into the manifest; the script injects them into a *copy* of
`AndroidManifest.xml` at build time (the committed manifest stays AGP-style).

## Verified APK (2026-11-03, manual pipeline)

- Path: `build/outputs/apk/debug/smoke-debug.apk`, **124,437,109 bytes**
- `aapt2 dump badging`: package `app.quantusnode.smoke`, versionCode 1,
  versionName `0.1-smoke`, minSdk 31 (`minSdkVersion:'31'`),
  targetSdkVersion 35, launchable activity `.MainActivity`,
  native-code `arm64-v8a`, no `uses-permission`
- `apkanalyzer apk summary`: `app.quantusnode.smoke  1  0.1-smoke`
- Contents: `classes.dex`; `lib/arm64-v8a/libquantus_node.so`
  (122,601,984 bytes, **Stored**, CRC32 `3fb560ae` — identical to the
  stripped file on disk); `lib/arm64-v8a/libc++_shared.so` (Stored)
- `zipalign -c -p 4`: verification successful (.so page-aligned)
- `apksigner verify`: Verifies (v3 scheme, 1 signer = debug key)

## Installing on the device (not done yet — next step)

Either way works; the APK is self-contained and needs no account/services:

```sh
# Option A: USB (adb from the SDK platform-tools)
adb install build/outputs/apk/debug/smoke-debug.apk

# Option B: copy the .apk to the phone and open it with the Files app
# (allow "install unknown apps" for that app when Android asks)
```

Then launch **Quantus Node Smoke**, tap **Run --version**, and record what
the screen shows (a version string, or the exact error).

## Not yet verified (honest list)

- **Everything on a real device.** Whether Android/GrapheneOS lets the app
  exec the binary from `nativeLibraryDir`, whether the bionic linker loads
  it with the bundled `libc++_shared.so`, and what `--version` prints are
  all unknown until the APK above is installed and run.
- The Gradle route (see above) — expected fine on a normal network,
  unproven here.
- A release build of the node (the bundled binary is a *debug* Rust build).
