# Quantus Node — Phase 1 MVP App

The first usable version of the Android node app: install it, tap start,
and a Quantus mainnet full node stays resident on the phone (foreground
service) while a quiet dashboard shows whether it is alive and how far
it has synced.

- applicationId: `app.quantusnode.node`
- minSdk 31 / targetSdk 35 / arm64-v8a only
- versionName `0.2.1` (versionCode 27; public releases began at v0.1.0)
- Permissions (only these): `INTERNET`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`
- `android:allowBackup="false"`
- Plain framework Java + Android SDK APIs only. **No third-party
  libraries** (JSON via the SDK's built-in `org.json`).

## What it does

- **NodeService** (foreground service, `specialUse` — Android 15 caps
  `dataSync` services at 6 h per 24 h, which a resident node cannot
  live with — not exported) runs the
  bundled `quantus-node` binary as a child process from
  `nativeLibraryDir` — direct exec first, one `/system/bin/linker64`
  fallback if the direct start fails or dies immediately (same pattern
  as the smoke app).
- Fixed launch arguments (verified against the node's CLI; do not edit
  casually). Two elements are user settings (see "Settings" below);
  everything else is constant:

  ```
  --chain mainnet --base-path <filesDir>/node --name <display name>
  --state-pruning 256 --blocks-pruning 256
  --trie-cache-size 134217728 --max-blocks-per-request 128
  --rpc-port 9944 --rpc-methods safe [--no-telemetry]
  ```

  Pruning is always passed. No `--rpc-external` (or similar) is ever
  passed, so RPC stays bound to `127.0.0.1`. All node files (chain DB,
  node key) stay inside the app-private directory. The app holds no
  wallet, no seed phrase, no asset secrets — by design.
- Node output goes to `filesDir/logs/node.log`, hard-capped by rotation
  (~512 KB; when exceeded, the older half is dropped). No unbounded
  buffers, no unbounded appends.
- If the node process exits, the exit code is logged and shown, the
  service stops, and nothing restarts it behind the user's back.
- Status comes from the **local** JSON-RPC endpoint only
  (`http://127.0.0.1:9944/`, `system_health` + `system_syncState`,
  polled every ~3 s). Connection refused while booting is shown as
  "起動中" (starting), never as an error. No external endpoint is contacted.

## MainActivity (dashboard)

Dark, minimal, quiet — the "気配" (presence) design. Since
versionCode 26 the dashboard is four screens in one activity —
**home**, **node details**, **track record** and **settings** —
reached from home with instant switching; each screen keeps its
scroll position. The palette follows the Quantus brand colours from
quantus.com (background #0E0E0E, surfaces #181818, accent #FF6B35,
text #E8E6E0, the synced state in the brand green #4ADE80).

- **PulseView** (custom Canvas view, on home): a faint still ring and
  dot. Only while the node is synced, a poll that first observes a
  higher best block draws exactly one soft ripple, which expands and
  fades. No perpetual animation.
- The state shown is one of 停止 / 起動中 / 同期中 / 同期完了 / 失敗
  (stopped / starting / syncing / synced / failed). The failure
  classification is the service's, not a guess from the exit code: a
  start whose two launch attempts both failed, an unexpected exit,
  and a missing node binary are failures (shown with a retry and a
  path into diagnostics); a system time-limit stop is a stop with its
  reason; a user stop is a plain stop even if the process exits
  non-zero. Home carries the state, the one primary action and —
  while running — the peer count and this session's uptime.
- **Node details**: best block, highest block, the sync gap (shown
  only when meaningful; zero is never dressed up as "synced"), peers,
  uptime, and an expandable **Diagnostics** section (exit code,
  service notes, the per-attempt start records, log sharing). While
  the node is stopped, the screen shows the cumulative track record
  instead of the previous session's values.
- **航跡 (track record)**: kept only on this device
  (SharedPreferences) — cumulative uptime (saved every ~10 s while
  running, so most of it survives a kill), the number of new-block
  updates observed, and the maximum peer count observed. Never
  transmitted; no monetary value, no ranking.
- **設定 (settings)**: four user settings, all stored only on the
  device (`NodeSettings`, SharedPreferences). The telemetry,
  display name and language are read at node launch; the settings
  lock is a UI-side guard. The app version is shown at the bottom:
  - **テレメトリに表示する (telemetry opt-in)** — default **off**.
    Off: the node is launched with `--no-telemetry`, exactly as before.
    On: `--no-telemetry` is omitted and the node announces itself to
    the chain spec's built-in official telemetry endpoint (the app
    never sets a telemetry URL itself), so the node appears in the
    public list at telemetry.quantus.com. The text next to the switch
    says plainly that the node name and live status then become public
    information — the user asked for this opt-in (2026-11-03) because
    seeing their own node in the list is reassuring.
  - **表示名 (display name)** — the `--name` value; empty means the
    generic default `quantus-android-node`. It is edited in a dialog:
    the value is sanitized (control characters stripped, trimmed)
    and the dialog validates the sanitized form against the 64
    code-point limit — over the limit is refused, never silently
    truncated — with a live counter and a preview of the name as it
    will be saved. The saved name is passed as a single argv element
    — never through a shell.
  - Changing either setting while the node is running does **not**
    restart it. The dashboard compares the saved settings with the
    snapshot the running node was launched with; while they differ,
    the changed items are marked as not yet applied in settings and
    home shows a "review pending settings" link. Applying the change
    is the user's own stop → start.
  - **言語 (language)** — the UI language: 日本語 / English /
    简体中文 / Español / हिन्दी / العربية / 한국어 / Русский /
    Français, or "follow device settings" (the default). Applies to
    the dashboard immediately (activity recreate) and to the service
    notification the next time it is rebuilt. All strings live in
    `StringsTable.java` (151 keys × 9 languages, mechanically checked
    for completeness, including a check that no value has decayed
    into "?" placeholders); the full table and contribution guide are
    in `../docs/I18N.md`. Numbers always render with ASCII digits in
    every language, and Arabic mirrors the layout (RTL).
  - **設定ロック (settings lock)** — a mis-tap guard for the three
    settings above: while it is on, their controls are disabled in
    the UI. Default **off**. It holds no credential and locks
    nothing else — starting and stopping the node always stays
    available.

## Bundled native libraries (not in git)

`native/` must contain, before building:

| file | bytes | what it is |
|---|---|---|
| `libquantus_node.so` | 53,411,080 | `quantus-node` cross-built for `aarch64-linux-android`, release profile (pruning patch + DNS-fallback vendor patch + the dependency security updates listed in `../SECURITY.md`), then stripped. A debug-profile build is ~123 MB instead |
| `libc++_shared.so` | 1,794,776 | NDK r27d `sysroot/usr/lib/aarch64-linux-android/libc++_shared.so` — required: it is in the node's `NEEDED` list |

How the node binary itself is cross-built (Rust 1.93.0, NDK r27d,
protoc 29.3, the rustix lockfile bump, …) is documented step by step in
[`../docs/BUILD-android.md`](../docs/BUILD-android.md). In short: build
per that document, copy the resulting `quantus-node` binary, strip the
copy with the NDK's `llvm-strip`, name it `libquantus_node.so`, and
place it — together with `libc++_shared.so` from the NDK sysroot — in
`native/`. Same staging as the smoke app (`../smoke/README.md`).

## Building the APK

### In this VM (manual pipeline)

Gradle cannot run here: Java-originated network access is blocked by
the environment, so the Android Gradle Plugin cannot be resolved.
`build-manual.sh` builds the same APK with only local SDK tools
(aapt2 → javac → d8 → zip → zipalign → apksigner). No network, no daemon.

```sh
./build-manual.sh
# -> build/outputs/apk/debug/quantus-node-debug.apk (debug-signed)
```

Defaults: JDK `~/workspace/toolchain/jdk-21`, SDK
`~/workspace/toolchain/android-sdk` (build-tools 35.0.0, platform
android-35); override with `JAVA_HOME` / `ANDROID_HOME`. The `.so`
files are stored **uncompressed** in `lib/arm64-v8a/` and the manifest
sets `extractNativeLibs=true`, so the installer extracts them to
`nativeLibraryDir`, from which the node can be executed by path.

`build-manual.sh` never leaves a stale APK at the output path: the
previous build is first moved aside to `quantus-node-debug.apk.prev`,
and the new APK is placed at the final path only after signing has
succeeded (bug P-5 — a failed build used to leave the old APK looking
like the new one).

### Versioning

`versionCode` is bumped on **every** build from the 2026-11-03
generation-unification build onward (that build is versionCode 2;
`build-manual.sh` and `build.gradle` are kept in step). Android only
installs an update over an existing install when the versionCode is
strictly higher, so a fixed versionCode would silently strand testers
on old builds. `versionName` stayed `0.1-mvp` through the
pre-release builds; the first public release (versionCode 20)
shipped as `0.1.0`, and the versionName in `build-manual.sh` and
`build.gradle` moves with each release since (currently `0.2.1`).
versionCode 3 is the
evidence build: per-attempt start records on the dashboard and a
share-log button, for diagnosing the on-device instant-exit failure
(node arguments and lifecycle logic unchanged). versionCode 4 is the
ndk-context fix build: the bundled node is now the release-profile
binary with vendored hickory-resolver/ndk-context patches, so DNS
system-config lookup falls back (net.dns* properties, then public
resolvers) instead of panicking when no Android context exists
(app code itself unchanged from versionCode 3).

### On a normal machine (Gradle)

Nothing here is VM-specific in the sources: the manifest, the Java
sources and `build.gradle` (namespace/applicationId
`app.quantusnode.node`, AGP 8.7.3, `useLegacyPackaging = true`) form a
regular Android project. Stage the libraries where AGP expects them
and build as usual:

```sh
mkdir -p src/main/jniLibs/arm64-v8a
cp native/libquantus_node.so native/libc++_shared.so src/main/jniLibs/arm64-v8a/
# with a Gradle wrapper / settings in place:
./gradlew assembleDebug
```

## Installing

```sh
adb install build/outputs/apk/debug/quantus-node-debug.apk
# or copy the .apk to the phone and open it with the Files app
```

Then launch **Quantus Node**, tap **ノードを開始**, and watch the status
move from 起動中 to 同期中. First sync on mainnet takes a long time and
the pruned DB still needs real storage — this is a node, not a light
client.

## Verified here (2026-11-03, manual pipeline, build VM only)

- Path: `build/outputs/apk/debug/quantus-node-debug.apk`, **124,437,109 bytes**
- `aapt2 dump badging`: package `app.quantusnode.node`, versionCode 1,
  versionName `0.1-mvp`, minSdk 31, targetSdkVersion 35,
  launchable activity `.MainActivity`, native-code `arm64-v8a`, and
  exactly the four permissions listed at the top — nothing else.
- `aapt2 dump xmltree`: `allowBackup=false`, `extractNativeLibs=true`,
  service `.NodeService` with `exported=false` and
  `foregroundServiceType=0x40000000` (specialUse, with the
  `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property declaring the node use
  case).
- Contents (`unzip -l` / `-v`): `classes.dex` (27,312 bytes, deflated);
  `lib/arm64-v8a/libquantus_node.so` (122,615,272 bytes, **Stored**,
  CRC32 `42063cd2` — identical to the file in `native/`);
  `lib/arm64-v8a/libc++_shared.so` (1,794,776 bytes, **Stored**,
  CRC32 `cdc23959` — identical).
- `zipalign -c -p 4`: verification successful.
- `apksigner verify -v`: **Verifies** (v3 scheme, 1 signer). The signer
  is the **debug** key (`CN=Android Debug, O=Android, C=US`, SHA-256
  `cc86d3e2…c91201c`) — the same well-known key AGP uses. This APK is
  for development/testing, not a release build.

### Re-verified after the telemetry opt-in (2026-11-03)

- Same path, **124,453,493 bytes** (SHA-256 `8edbef28…`). Delta vs.
  the build above is code only; the bundled node binary is untouched.
- `aapt2 dump badging`: unchanged package / version / SDK range /
  `arm64-v8a`, and still **exactly the same four permissions** — the
  opt-in adds none (telemetry rides on the node's own networking).
- `classes.dex` is now 32,460 bytes and contains 6 classes (the new
  `NodeSettings` plus the previous five).
- `zipalign -c -p 4`: OK. `apksigner verify`: **Verifies** (v3, same
  debug key).
- Argument behaviour verified by code re-reading: with default
  settings the launched argument list is identical to the fixed list
  above (including `--no-telemetry`); with the opt-in on, the only
  difference is that `--no-telemetry` is absent. Pruning and all other
  elements are unchanged in both modes.

### Re-verified after the generation-unification fixes (2026-11-03)

Fixes for the latent-bug cluster found by the day-long test campaign
(session-generation asymmetry N-1/N-2/N-3/N-5, launch-window orphan
N-6, LogSink class lock N-11, giant-line log loss LOG-H-03, track
saturation TRK-07, build staleness P-5, quick-exit reason display
P-6). These were **code-reviewed findings, not reproduced on a
device or emulator**; the fixes are verified here by rebuild, static
checks and code re-reading only.

- Same path, **124,453,493 bytes** (SHA-256 `98a32dc6…`; the bundled
  node binary is untouched, and the equal total size vs. the previous
  build is a deflate coincidence — `classes.dex` grew 56,968 →
  59,148 bytes and contains the new code paths).
- `aapt2 dump badging`: **versionCode 2**, versionName `0.1-mvp`,
  package / SDK range / `arm64-v8a` unchanged, still **exactly the
  same four permissions**. `aapt2 dump xmltree`: `allowBackup=false`,
  `foregroundServiceType=0x40000000` (specialUse) with subtype —
  unchanged. `zipalign -c -p 4`: OK. `apksigner verify`: **Verifies**
  (v3, same debug key).
- P-5 fix verified by inducing a build failure (broken `JAVA_HOME`,
  exit 127): the output directory was left with only
  `quantus-node-debug.apk.prev` / `.idsig.prev` — **no APK at the
  final path** — and a subsequent good build restored it.

## Not yet verified (honest list)

Verified since the first version of this list: on a real Pixel 9a
(GrapheneOS) the node runs as a child process from
`nativeLibraryDir`, the local RPC answers, and a full sync from
genesis to the chain tip completed on 2026-11-03 (#341,592, about
2 h 21 min of node uptime, peaking at 18 peers — see
`../docs/DESIGN.md`). The bundled node binary is the release-profile
build (53,411,080 bytes stripped as of versionCode 10), not a debug build.
The published v0.1.0 release build (versionCode 20) repeated the
full sync from a fresh install on 2026-11-05 (#349,518), and with
telemetry opted in the node appeared in the public telemetry list
the same day — the CA fix below is confirmed working end to end.

Still not verified:

- **Other devices.** Only the Pixel 9a on GrapheneOS is confirmed;
  other makers' battery savers may stop the resident service.
- **Steady-state numbers over many hours**: storage has its first
  measurement — 1.30 GB in total after the full sync (96 MB app +
  1.20 GB of node data, Pixel 9a, 2026-11-05) — but the pruned
  database size plateau over weeks, and battery/thermal behaviour
  after the initial sync, are still open (the sync itself took the
  battery from 71% to 27%, including time on mobile data).
- **Repeated stop → start cycles on device**: the races found in
  the 2026-11-04 audit are fixed in versionCode 8/9, verified by
  code inspection, not yet by an on-device soak.
- The Gradle route (expected fine on a normal network, unproven here).
