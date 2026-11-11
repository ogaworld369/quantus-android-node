# Quantus Android Node — Design Document v0.2 (revised to node-only)

Created: 2026-11-03 / Revised: the same day; mining removed and the app made node-only by the owner's decision
Purpose in one sentence: **Not for rewards — for decentralization.** Build an open-source, Android-only app that lets anyone's smartphone take part as a node of the Quantus Network (QTC).

> Changes from v0.1: the app was initially "node + mining", but the owner settled on "no mining, node only". The miner-related design (wormholes, inner_hash, the QUIC miner protocol, etc.) was removed as out of scope.

---

## 1. Premises and principles

- Android only. iOS is out of scope.
- Device policy (owner's decision, 2026-11-03): not limited to Pixel. The requirement is a general Android device running Android 12 or later on arm64 (implementation: minSdk 31 / arm64-v8a only; no dependence on Google services or Pixel-specific features), and "verified working" and "expected to work" are stated separately and honestly. At present, the only verified device is the Pixel 9a + GrapheneOS. All other devices are "expected to work", with the caveat stated explicitly that vendor-specific power saving (Samsung, Xiaomi, etc.) may stop the resident service. The list of verified devices will grow through run reports filed as GitHub Issues (premised on information sharing working on the public repository).
- Published as open source (GitHub). The license's first candidate is Apache-2.0, to match upstream.
- Security is the top priority. No rushing: test repeatedly and build it to a quality that can ship as a production release.
- The theme is "the newest, like nothing before". There is officially no Android node, and a full node for a quantum-resistant chain residing on a smartphone is itself a new form.

## 2. What "node only" means (this app's role)

What this app runs is an ordinary full node that does not produce blocks (no mining):

- It syncs the chain and verifies every block and transaction itself (trustless verification).
- It connects to peers and relays blocks and transactions, supporting the network.
- The chain state can be queried on the device itself (no dependence on an external RPC service).

How this helps decentralization: the more nodes that verify, the less the network depends on a particular operator or a small number of servers. No rewards are generated. That is the substance of what the owner calls "romance", and the design commits to it.

Major advantages gained by going node-only:

1. **No wallet secrets are handled at all.** No seed phrase, no inner_hash. There is no asset information in the app whose theft would matter (the only remaining secret is the node identity key).
2. **Heat and battery consumption are orders of magnitude smaller.** No continuous computational load; syncing and relaying are the main work. It coexists easily with a daily-use phone.
3. **Wider distribution options.** What Google Play prohibits is on-device mining; a node app does not touch that clause (listing is still subject to separate review, but at least it is no longer an immediate policy violation). For the time being, however, distribution is premised on signed APKs via GitHub Releases plus F-Droid.

### Product concept (owner's decision, 2026-11-03)

From the owner's concern that "a plain node app would be boring", the product's axis was set to **visualization + a design of presence (kehai)**, with two elements.

- **Element 1: Visualizing the node's "pulse".** Block arrivals, peer connections and disconnections, and sync progress are shown live and beautifully, giving the behind-the-scenes node a felt sense of "being alive". Data is obtained only from the node state inside the device (local RPC, etc.) and is never transmitted externally.
- **Element 2: Making contributions visible.** The number of relayed blocks, the number of connected peers, and uptime are recorded on the device and shown as one's personal track record of "my contribution to decentralization". No monetary value is attached, and it is not made into a ranking competition. Comparisons with others and aggregation are deferred until an anonymous-statistics design is settled.
- **Worldview: a design of "presence" (kehai).** The whole is unified by an aesthetic continuous with the owner's photography style (85mm equivalent, shooting quietly with one's presence erased, Saul Leiter-like light and shadow). A dark base tone, minimal text and motion, depicting the node as a presence that does not assert itself yet is certainly there.

Note: a third element, "Quantum Watch" (a monitoring screen lining up quantum-computer threat trends and the health of one's own node), was also initially decided on, but since the external threat information was never implemented and only the display frame remained, the owner judged on 2026-11-06 that it was "not needed", and it was removed completely, frame and all, in v0.2.0. The concept now stands on the two pillars above.

The constraints are unchanged: no wallet, no mining, no asset secrets. No analytics SDKs. Visualization and recording are completed entirely on the device.

## 3. Official specifications confirmed by research (as of 2026-11-03, verified against the real thing)

### The node (quantus-node v1.0.5)
- Substrate-based. Consensus is QPoW (Poseidon2), signatures are of the ML-DSA family, block time is about 12 seconds on mainnet. QTC has a maximum supply of 21 million.
- Minimum requirements (official MINING.md): CPU 2+ cores / RAM 4GB / **storage 100GB** / connection 3Mbps+. Recommended RAM 8GB+ / SSD 500GB+.
  * These are official figures assuming validator operation. The actual size of a pruned ordinary node is being measured in Phase 0.
- Sync modes: `--sync full | fast | fast-unsafe | warp` are listed side by side, but Phase 0 measurement established that **only full is valid on mainnet / v1.0.5** (details in §3.5).
- Pruning: Phase 0 measurement established that the CLI options `--state-pruning` / `--blocks-pruning` are **overridden on the node side and do not take effect** (details in §3.5).
- RPC is local-only by default. Not publishing Prometheus or RPC externally is the official safety guideline (strictly observed in the app as well).

### Findings from Phase 0 measurement (2026-11-03; confirmed facts that revise the premises of §3 and §4)

- **Warp sync cannot be used.** It exits immediately after startup with "Warp sync enabled, but no warp sync provider configured." (PoW has no finality mechanism.) The same for validators and non-validators.
- **Fast sync cannot be used either.** It exits with "Fast sync doesn't work for archive nodes". The only valid sync is **full**.
- **The pruning options do not take effect.** Even specifying `--state-pruning 256` / `--blocks-pruning 256`, the startup log shows State pruning: ArchiveCanonical / Blocks pruning: KeepFinalized. With the stock binary, smartphone-friendly size reduction is impossible, and **modifying the node side becomes a prerequisite**.
- **Capacity looks much smaller than expected.** The assistant's VM could not establish P2P connections due to outbound proxy constraints, so sync could not be measured there; the estimate comes from sampling 49 blocks via the official RPC: block bodies average about 17KB (median 267B, about 70% are empty blocks), extrapolating the total of bodies up to the tip #337,678 (2026-11-03) gives about 5.7GB, and the total including state is **probably in the 6–15GB range**. The official "minimum 100GB" may diverge greatly from the current chain's reality. The growth pace extrapolates to about 120MB/day (about 45GB/year). (→ This estimate was updated by the 2026-11-05 measurement: the actual usage at the completion of syncing was 1.30GB in total, far below the estimate. This is thought to be because pruning 256 is in effect; the long-term growth pace remains under continued observation.)
- **The biggest unknown is sync time.** With full sync only, the time for a smartphone CPU to finish full execution verification of 337k blocks is unverified. The next measurement will be retaken in an environment with normal connectivity that passes P2P (a real device, etc.).
- The node is designed on the assumption of a GB-scale trie cache (a warning about reserving 1GB at startup was confirmed). Peak RAM during sync is unmeasured, and a point of caution for the Pixel 9a (8GB).

### Findings from Phase 0 measurement (part 2: the Android port, 2026-11-03)

- **The quantus-node binary itself passed compile verification (cargo check) for Android.** The target is `aarch64-linux-android`; it exited 0 even with the Wasm runtime enabled, and generation of `quantus_runtime.wasm` (about 3.1MB) was confirmed. The foundational crates (qpow-math, qp-dilithium-crypto, sc-network, sc-executor-wasmtime, sc-client-db) all passed, and cross-compiled RocksDB and ring artifacts were also confirmed.
- All five blockers encountered were proven avoidable, with **zero patches to upstream code**:
  - An Android bug in rustix 1.1.2 → `cargo update -p rustix` (1.1.6, a Cargo.lock-only change)
  - litep2p's protoc requirement → provided protoc 29.3
  - libclang / Android header resolution for bindgen → specified the NDK-bundled libclang and added cross arguments
  - The heaviness of host-side builds (about 20 min/run with Binaryen and ZK circuit generation) → CI must be designed around caching
- The initially feared glibc assumptions and wasmtime platform assumptions did not surface within the scope of check.
- **The final link also succeeded (added on the morning of 2026-11-03)**: `cargo build -p quantus-node --target aarch64-linux-android` ran to completion, producing an ARM aarch64 ELF executable (interpreter is Android's /system/bin/linker64, about 123MB after strip). No additional patches were needed for link errors. The build procedure is fixed in the repository's docs/BUILD-android.md. At runtime, libc++_shared.so must be bundled on the app side (it is in NEEDED).
- **Subsequent progress (added 2026-11-03)**: a real-device run has been performed; the node opened its DB (ParityDB) and reached genesis initialization. However, it terminated with the panic described later immediately after the network started, and completion of syncing remained unverified (details in "part 4"; completion was confirmed later the same night, details in "part 5"). A release-profile build has also been performed. The caveat at the time — "a successful link is not proof that it 'works'" — was correct.
- Conclusion on the alternative route: a light client (smoldot etc.) has no trace in Quantus, and does not hold up in PoW without finality. The main route is "full sync + a pruning-modified node built for Android", and no additional principled barriers to that build itself are in sight.

### Findings from the Phase 1 investigation (part 3: the pruning modification, 2026-11-03)

- **The cause of the pruning options not taking effect is a single place in Quantus's own code.** In the normal startup branch of `node/src/command.rs`, "Obligatory configuration for all node holders" unconditionally overwrote the values with `ArchiveCanonical` and `KeepFinalized`. The CLI parsing layer and Configuration construction were normal; the specified values were correctly delivered and then discarded. It is intentional design, and the README also states "mandatory storage configuration settings that cannot be overridden" and "enforced at the code level" (a philosophy of guaranteeing past-block proof reads for the ZK tree and archive RPC on all nodes).
- **A minimal patch was drafted and passed cargo check for Android**: 1 file, +12/−3 lines. Only when a numeric value is specified is the specification respected; when unspecified, the conventional forced values are maintained (the default behavior of existing operators is unchanged). The patch is kept as an uncommitted diff in the spike clone, and a copy is also saved in the working files.
- **Side effect**: the rejection condition for fast sync was identified in code as "the effective pruning is archive". If pruning takes effect numerically, the fast gate opens in code (whether state download actually succeeds is unverified). Warp remains unavailable due to the unconfigured provider and PoW.
- Trade-off: on a pruned node, state_call / zkTree proofs / archive RPC for deep history fail with "no state". Judged acceptable for personal smartphone-node use, but noted explicitly as a difference from the official philosophy.
- **Unverified**: starting the patched binary in an environment where P2P passes, and confirming the startup-log switching, the DB-size ceiling during sync, and the success or failure of fast sync. Not performed in this VM because P2P does not pass.

### Established in the Phase 1 implementation (2026-11-03)

- **The finalized form of the node launch arguments** (verified against the CLI spec and the node-side code): `--chain mainnet --base-path <app-private storage>/node --name quantus-android-node --state-pruning 256 --blocks-pruning 256 --trie-cache-size 134217728 --max-blocks-per-request 128 --rpc-port 9944 --rpc-methods safe --no-telemetry`
  - `--chain mainnet` is required (the default is the heisenberg testnet). RPC is closed to 127.0.0.1 only by not adding `--rpc-external`. Both pruning flags are always passed every time (a consequence of audit P-2/P-3). The node name defaults to the generic, device-non-specific name `quantus-android-node`; when the user sets a display name, that value is used (see the telemetry opt-in item below).
  - Because the mainnet chain spec includes the official telemetry endpoint, `--no-telemetry` is given explicitly by default to minimize external transmission (removed when the user opts in to telemetry display; see below).
  - The node key (Dilithium) is auto-generated and stored under the base-path by default (`chains/mainnet/network/secret_dilithium`). Placed in app-private storage with allowBackup=false, this satisfies design §6-2.
- **Dependency correction**: the known advisories (6 DoS-class items) in h2 0.4.12, rated "high" in the audit, were updated with `cargo update -p h2` to 0.4.20, and a re-run of cargo-audit confirmed the resolution. Other upstream-origin findings (ring etc.) remain pending upstream follow-up.
- **Telemetry display opt-in** (owner's decision, 2026-11-03; implemented in the MVP): at the owner's wish — seeing one's own node in the official telemetry list (telemetry.quantus.com) gives peace of mind — it was implemented as an opt-in, off by default and on by choice. When off, the node starts with `--no-telemetry` as in the finalized form above. When on, only `--no-telemetry` is removed, and the destination is the official endpoint that is the chain spec default (the app does not specify a destination URL of its own). It is stated explicitly next to the setting toggle that when on, the node name and live status become publicly visible to anyone, and the owner has agreed. The display name is based on the default generic name `quantus-android-node`, and the user can set an arbitrary display name (stored only on the device, and passed to the arguments as an array). Setting changes take effect at the next node start. If a setting is changed while running, each item whose actually-started value differs from the saved value is shown per-item as "not applied" on the settings screen, and a link to check unapplied settings also appears on the home screen; the app never silently restarts (the v0.2.0 UI; initially it was a form that displayed a note urging a restart). No permissions are added (telemetry transmission rides on the node binary's own communication). Whether it would actually appear in the list required separate confirmation on a real device and a real connection, and on 2026-11-05 the listing with a display name was confirmed on release v0.1.0 (versionCode 20).

### Findings on a real device in Phase 1 (part 4: the ndk-context panic and the DNS fallback fix, 2026-11-03)

- **The cause of the node dying immediately after network start on a real device was identified.** Running an evidence-gathering build (versionCode 3) on a Pixel 9a / GrapheneOS, the node started, opened its DB (ParityDB), and completed genesis initialization, but right after "Running litep2p network backend" it panicked and exited with exit code 1 (survival about 17.8 seconds). The log was obtained through the evidence build's share feature, and the panic message remained verbatim.
- **The panic itself**: `Thread 'main' panicked at 'android context was not initialized'`, occurring at src/lib.rs:72 of ndk-context 0.1.1. The dependency chain is quantus-node → litep2p 0.13.3 → hickory-resolver 0.26.1 → ndk-context 0.1.1.
- **The mechanism**: the Android version of `read_system_conf()` in hickory-resolver (the DNS resolver) calls `ndk_context::android_context()` at the outset of reading the system DNS settings. This function assumes an Android runtime such as NativeActivity has initialized the context, and panics if uninitialized. This app's node is a plain process exec'd from the app; there is no one to initialize the context.
- **The trigger**: because all mainnet bootnodes are /dns/ addresses, the first name resolution immediately after the network starts always enters this path.
- **Why it did not reproduce on PC**: on Linux this path itself does not exist (hickory reads /etc/resolv.conf). This is consistent with the host reproduction experiment, in which both the official binary and the patched binary started normally, and it turned out that the breakdown of what had been separated out as "environment-side" was this Android-specific dependency path.
- **The fix (a second patch to upstream; the first is the pruning patch)**: ndk-context 0.1.1 and hickory-resolver 0.26.1 were vendored into the spike tree (chain/vendor/) and replaced via `[patch.crates-io]` in chain/Cargo.toml.
  - A non-panicking `try_android_context() -> Option<AndroidContext>` was added to ndk-context.
  - hickory-resolver's `read_system_conf()` was changed to dispatch: when a context exists, it obtains DNS servers from ConnectivityManager via JNI as before (behavior unchanged). When it does not, the fallback reads `net.dns1`–`net.dns4` via libc's `__system_property_get` and adopts those interpretable as IPs. If that also yields nothing, public DNS (1.1.1.1 / 8.8.8.8) is used. A warn log is emitted when the fallback is used. No path panics. An Android libc dependency was also added to hickory-resolver's Cargo.toml.
  - The canonical diff is the repository's `patches/hickory-ndk-fallback-patch.diff` (4 items: 3 crate files + chain/Cargo.toml). The reproduction procedure is as described at the top of that file (copy the 2 crates from the registry into vendor/, apply the diff, add [patch.crates-io]; Cargo.lock is updated by cargo).
- **Verified scope**: `cargo check -p quantus-node` passes with EXIT 0 for both Android (aarch64-linux-android) and host. The Android release was rebuilt, and the fallback's warn string was confirmed embedded in the binary. APK versionCode 4 was created bundling this release binary (about 53.4MB after strip).
- **Confirmed resolved on a real device (2026-11-03 17:25)**: running versionCode 4 on a Pixel 9a / GrapheneOS, the node started past the panic point, connected to 12 peers, and was syncing (best #61642 / highest #341089, cumulative uptime 18 minutes), as the owner confirmed on screen. Remaining real-device checks: completion of syncing, several hours of residency, the DB-size ceiling at pruning 256, battery and heat, stop and restart. The details of the fallback (whether net.dns* could be read, or whether it was the public DNS path) remain undetermined.

### Findings on a real device in Phase 1 (part 5: sync completion, telemetry TLS, tuning, 2026-11-03 to 04)

- **The initial sync completed (2026-11-03 19:28)**: on the real device, best #341592 = highest #341592, 18 peers, "Synced" confirmed. From the start (17:07), cumulative uptime was 2 hours 21 minutes, and sync speed held at about 50 blocks/second. Battery over the whole sync went 71% → 27% (including sync over a mobile connection while out). Remaining real-device checks: several hours of continued residency, the DB-size ceiling at pruning 256, repetition of stop and restart.
- **The cause of not appearing in telemetry was the TLS CA certificates**: in the node log the owner shared, wss connections to the official telemetry were failing about every 10 seconds with "no native root CA certificates found". Because the node runs as a plain native process, rustls-native-certs cannot find Android's CA store. Fix (versionCode 5): the official Mozilla CA bundle (distributed by curl.se, SHA-256 verified against the official hash) is bundled in the app, and `SSL_CERT_FILE` / `SSL_CERT_DIR` are passed as environment variables to the child process at startup. The final listing confirmation was to be made after a real-device start with the fixed version (→ settled on 2026-11-05, with the listing confirmed on release v0.1.0).
- **Launch-argument tuning (versionCode 6)**: based on a close reading of the node-side code, `--trie-cache-size 134217728` (128MiB; the 1GiB default produced an initialization-failure warning on the real device, and the cache was not effectively working) and `--max-blocks-per-request 128` (default 64, up to the responder-side limit) were added. Raising parallel downloads (`--max-parallel-downloads`, `--out-peers`) was not mixed in, since the trade-off with heat and battery cannot be read; it will be evaluated separately later.
- **Fast sync was settled as not viable**: the pruning patch opens the builder gate, but two mechanism-level barriers were confirmed in code: (1) if finalized state exists in the DB, LightState reverts to Full at initialization, and (2) the start condition for state sync (finalization within 8 blocks of the tip) is structurally incompatible with QPoW's finalization method, which lags 100 blocks behind the best. Viability would require multiple additional patches on the SDK side, out of proportion to the maintenance burden of reapplying and re-verifying at every upstream follow-up. It will not be pursued.
- **thin LTO was not adopted**: the experiment build (prod-thin profile) ran to completion, but the size after strip was +2.5% versus the current release, contributing nothing to size reduction, and on 2026-11-04 the owner judged that "size reduction is unnecessary", ending it. fat LTO (insufficient memory on the build machine) and panic="abort" (losing the resident node's recovery path) were also settled as not adopted.
- **Settings lock (versionCode 7)**: as protection against accidental taps on a device left sitting around, a "Lock settings" button was added to the settings section. While locked, the telemetry switch, the display name, and the language picker are disabled. It is specified explicitly that there is no PIN (it is not an authentication feature) and that starting/stopping the node is not locked.

### Finishing touches before the external audit (part 6, 2026-11-04)

- **vc8 fixed two race conditions found in the audit**: a full-source bug audit (2026-11-04, no findings of "high" severity) found and resolved the "stop → start immediately" races (the old session's exit handling deleting the new session's process reference, and starting a new process without waiting for the old process's DB lock release) (the former via an ownership check, the latter via a bounded 3-second exit wait). Along with this, exception resilience of the drain thread, removal of a leading '-' from the display name, and atomic writing of the CA bundle were included.
- **vc9 resolved five remaining items**: the FGS timeout path's generation reads were unified inside the same lock as the other stop paths, the startup snapshot recording was moved to after successful process spawn, ripples were limited to only when the best block increases after sync completion, the effective polling interval was improved (when the RPC was slow it had stretched to about 10 seconds at worst), and log-sharing reads were moved to the background.
- **The public security record was put in order**: a "Security notes" section was added to SECURITY.md, stating the dependency advisory status and upstream follow-up policy, the design security boundaries, accepted known items, and the audit record. A note was added to the usage guides in all 9 languages that shared logs may contain one's own node's global IP address and peer information. These are finishing touches in preparation for tomorrow's external audit (a security and bug audit).
- **Remaining known items**: LogSink's per-line open/close showed no observed harm in a real-device full sync (about 2 hours 21 minutes) and is accepted as a performance item. The upstream-origin ring / curve25519-dalek / yanked anyhow dependencies remain and will follow upstream updates (details in SECURITY.md).

### Differences between the early design description and the implementation (part 7, 2026-11-04)

§5 and §6 are descriptions from the early design stage (Phase 0) and are not rewritten, as history. However, they differ from the current implementation in the following points, so the differences are stated here (so that outside readers do not misread the early description as the current specification).

- **Tech stack**: §5's architecture proposal depicted Kotlin / Jetpack Compose / JNI-izing as a .so, but the implementation became plain Java (Android SDK only, no external dependencies). The node binary is not called via JNI; the executable is launched directly as a child process of the app. The UI is also implemented with ordinary Views and Canvas drawing (PulseView), not Compose.
- **Node key storage**: §6-2 made "node keys protected by Android Keystore" a mandatory requirement, but the implementation does not use Android Keystore (there are no Keystore references in the app code). The node key is generated and held as a file by the node binary under the base-path; its location is inside app-private storage (not readable by other apps), and allowBackup=false excludes it from cloud backup as well. The substance of the requirement that keys never leave the device is satisfied by this arrangement. The descriptions in README.md / SECURITY.md (app-private storage) match the current implementation.
- **License**: §1's "Apache-2.0 as the first candidate" and §10's "license (Apache-2.0 proposal)" are descriptions from before the decision was final. The license was settled as Apache-2.0 by the owner on 2026-11-04, and a LICENSE file is in place in the repository.

### Dependency re-audit and binary rebuild (part 8, 2026-11-04)

- Prompted by a third-party double-check in preparation for the external audit, the dependencies were retaken against the RustSec Advisory DB (fetched the same day). The vulnerabilities within the dependency closure of the shipped binary (quantus-node) (aarch64-linux-android, normal+build) numbered **22**, differing greatly from the 2026-11-03 internal audit report (remaining items were a few upstream-origin ones). That audit appears to have been looking at an old lock state; in particular, the "remaining" descriptions of curve25519-dalek 3.2.0 and yanked anyhow 1.0.99 that had remained in SECURITY.md were errors not matching the current lock (dalek 4.1.3, anyhow 1.0.104). ring 0.17.14 is also outside the target, being within the fixed range of RUSTSEC-2025-0009 (≥0.17.12).
- **Response**: 20 items were resolved by lockfile updates within semver ranges (wasmtime 36.0.13→36.0.20, quinn-proto 0.11.15→0.11.19, rustls 0.23.32→0.23.46, rustls-webpki 0.103.13→0.103.16, the zstd family). It was confirmed that `cargo check -p quantus-node --target aarch64-linux-android` passes with the updated lock, and the node binary was rebuilt and put into distribution as **versionCode 10** (53,411,080 bytes after strip). The remaining 2 items (zstd-safe 6.0.6's legacy-frames-only item and crypto-bigint 0.5.5's timing item) have no fixed version within a compatible range and wait on an upstream framework update. Reachability assessments (quinn-proto is reachable only via the miner server path, which is disabled in this app; wasmtime's component-model/WASI family is unreachable, etc.) and the accurate current status are consolidated in SECURITY.md's "Dependency advisories".
- The detailed report is kept on the working-record side (dependency-audit-2026-11-04). Real-device confirmation of the rebuilt binary is included in Gate 1's continued confirmation.

### The v0.2.0 UI redesign (2026-11-06)

The v0.1.x single-page + card layout was abolished and the UI fully redesigned (versionCode 26 / v0.2.0). The node launch arguments, database, permissions, saved setting values, and the bundled node binary (unchanged since vc10) are unchanged; only the presentation layer changes.

- **Four-screen structure**: Home (pulse, state, main button; while running, the peer count and this session's uptime), Node details (best/highest block, sync gap, diagnostics), Track record (cumulative records), Settings. Screen transitions are immediate, and each screen retains its scroll position.
- **Five states**: Stopped / Starting / Syncing / Synced / Failed. Failure classification is done on the service side (when both of the two startup attempts fail, on an unexpected exit, or when the node binary is missing, it is "Failed" with the reason and retry shown. A stop due to the system's time limit is treated as a stop with a reason, and a user stop as an ordinary stop; failure is never decided by the exit code alone).
- **The pulse's meaning was narrowed to one**: a ripple appears only during Synced, at the fetch that first observes a new best block. The observation time is recorded on the service side and kept consistent with the home screen's "Last observed" display.
- **Node details and diagnostics**: the sync gap is shown only when it is meaningful, and it never says "Synced" just because the gap is 0. Diagnostics is an expandable section holding the exit code, the service's notes, per-startup-attempt records, and log sharing. While stopped, the previous session's values are not shown as if they were current; the cumulative track record is displayed instead.
- **Settings**: while the saved value and the actually-started value differ for telemetry and the display name, each item is shown as not applied, and a check link also appears on the home screen. The display name is edited in a dialog; if it exceeds 64 code points after sanitization, saving is refused without truncation (with a character counter and a post-save preview). Language remains immediate-apply on selection. The version display was moved to the bottom of Settings.
- **Quantity expressions**: times, remaining block counts, and elapsed times choose the plural category of each language (including Russian's few/many and all of Arabic's categories) from the actual number. Strings total 153 keys × 9 languages.
- **Colors**: aligned with the brand tokens of the official Quantus site (quantus.com). Background #0E0E0E (void), surface #181818 (surface), accent #FF6B35 (flare), text #E8E6E0 (content), and Synced uses the brand green #4ADE80.
- **Removal of Quantum Watch**: as recorded in §2, by the owner's decision the display frame and dedicated strings were removed completely (the concept is the two pillars of pulse visualization and contribution visibility).
- Along with this, the problem of 242 translated strings having been shipped mangled into "?" (also included in the v0.1.x line) was restored verbatim from the table committed at vc24, and a regression check was added to the verification harness.

### Pixel 9a (the real-device target)
- Tensor G4 (1× X4 + 3× A720 + 4× A520), RAM 8GB, storage 128/256GB UFS 3.1 (no microSD), battery 5,100mAh, Titan M2.
- For node use, the CPU is sufficient. The contest narrows to storage capacity and whether background residency is possible.

## 4. Feasibility assessment (a frank evaluation)

| Question | Verdict | Basis |
|---|---|---|
| Compute and memory | ◎ | The node's sync and verification look sufficient on the Pixel 9a's performance. No mining load |
| Storage | ▲ The biggest gate | Official minimum 100GB. The measurement under strong pruning was 1.30GB in total at sync completion (2026-11-05). Depending on results, conditions such as "256GB model recommended" or a "minimum free space" may attach |
| Porting to Android | ▲ A hard part | Substrate full nodes assume glibc. The Android (bionic) port, and whether wasmtime / the DBs (RocksDB, ParityDB) build, are being inventoried as blockers |
| Residency | △ Design-dependent | Run it in a Foreground Service. Long-term stability under GrapheneOS's strict limits will be worked out in real-device tests |

## 5. Architecture proposal

```
┌────────────────────── Android app ──────────────────────┐
│ UI layer (Kotlin / Jetpack Compose)                      │
│  Sync state / peers / block height / DB use / settings   │
├──────────────────────────────────────────────────────────┤
│ Control layer (Kotlin, Foreground Service)               │
│  Start/stop, Wi-Fi-only sync, power saving, alerts       │
├──────────────────────────────────────────────────────────┤
│ Node layer (Rust, as a .so via JNI)                      │
│  quantus-node built in, warp + pruned configuration      │
│  RPC is loopback only, never published externally        │
├──────────────────────────────────────────────────────────┤
│ Key storage (Android Keystore)                           │
│  Node key (P2P identity) only. No asset secrets          │
└──────────────────────────────────────────────────────────┘
```

- Compute and protocol are Rust (upstream reuse and memory safety); Kotlin handles only the UI and the Android lifecycle.
- Keep the settings the user touches few: sync start/stop, Wi-Fi only, storage cap, data deletion. Jargon is displayed in plain language.
- If the DB approaches its cap, do not let it overflow silently; warn and stop safely.

## 6. Security design (the top priority)

Threat model: ① device theft or loss ② malicious apps ③ network attackers (fake peers, eavesdropping) ④ supply chain (dependency crates, tampering with the distributed APK) ⑤ operational mistakes.

Mandatory requirements:
1. **Preserve the design of holding no asset secrets.** No wallet functionality. If talk of adding it comes up in the future, design it separately.
2. **Node keys are protected by the Keystore** and never placed in plaintext on external storage. Excluded from cloud backup (`allowBackup=false`).
3. **RPC and management ports are never published externally.** In-app communication is loopback only. Only the P2P port connects outside.
4. **Zero external transmission by default.** No analytics SDKs, ad SDKs, or automatic crash reporting. No personal information or keys in logs.
5. **No self-distribution of binaries.** Native code comes only inside the signed APK. No mechanism for downloading executable code from the net is built. Updates are signed APK updates only.
6. **Minimal permissions.** Internet, Foreground Service, notifications, about that much. Contacts, location, etc. are never requested.
7. **Reproducible builds.** Aim for a configuration in which anyone's build yields the same hash, and attach checksums and signatures to releases. Dependencies are pinned, and audits are built into CI.
8. **Discipline in following upstream.** Follow official node updates and do not keep distributing old versions. Quantum resistance uses upstream's ML-DSA / ML-KEM configuration as-is; no home-grown cryptography.

## 7. Test strategy (conditions for production quality)

- **Correctness of sync**: reach the same chain tip as the official node (PC version), with matching block hashes.
- **Long-duration soak tests**: continuous operation on a Pixel 9a real device for 24h / 72h / 1 week (reconnection, memory, DB growth, battery consumption).
- **Failure cases**: connection loss and recovery, airplane mode, storage pressure, recovery from a forced app kill, and chain reorganizations (reorgs).
- **Power-saving cases**: Doze, app standby, behavior under GrapheneOS restrictions.
- **Reproducible-build verification**: hash agreement between CI and local builds.
- **Staged rollout**: dev chain → testnet equivalent → mainnet.

## 8. Roadmap

- **Phase 0 — Verification spike (in progress)**
  0-1. Measure the mainnet DB size (non-validator configuration, warp + pruning) *measuring*
  0-2. Inventory the blockers for porting quantus-node to Android *started*
- **Phase 1 — Node MVP**: the minimal app where sync, verification, and peer relay work. Sync-state display, Foreground Service.
- **Phase 2 — Real-device livability**: power saving, traffic volume, storage-cap management, long-term stability on GrapheneOS, checking models other than the Pixel 9a.
- **Phase 3 — Hardening and release preparation**: security review, reproducible builds, publishing the README / threat model, GitHub public release.
- **Phase 4 — Production**: mainnet official release, expansion to other Android devices, F-Droid listing procedures.

## 9. Undecided items and risks (not hidden)

- DB size awaits measurement results. Conditions may become stricter on the 128GB model.
- If the Android port requires upstream patches, the maintenance burden becomes permanent. Continuation is judged on the Phase 0-2 results.
- The upstream patches did in fact become a two-patch arrangement (the pruning patch, and the ndk-context / hickory-resolver DNS fallback. See §3, "part 4"). Each upstream version bump requires reapplication and re-verification, and the maintenance burden is real. Upstreaming (Issues / PRs) is desirable.
- Syncing over a mobile connection uses a large amount of traffic. The default is Wi-Fi only, and a mobile connection is permitted only when the user chooses it.

## 10. Things to decide

1. The Pixel 9a's storage capacity (128GB / 256GB) — material for judging the node's viability conditions. No rush; after waking is fine.
2. The app name and repository name, and the license (Apache-2.0 proposal) — can be later.

## 11. GitHub usage policy (added 2026-11-03 at the owner's instruction)

Design from the start for "a state where many people know about it and improvement proposals come in":

- **A public repository** (under ogaworld369, provisionally named quantus-android-node). Public from the design stage, showing the progress itself.
- **The README is in English + Japanese**. What is being built and why (for decentralization, no rewards) is made clear at the top, doubling as differentiation from misunderstandings (mining-scam-like apps, etc.).
- **Attach Topics (tags)**: quantus, qtc, android, substrate, rust, post-quantum, full-node, grapheneos, kotlin, etc., so it can be found in search.
- **Issue templates** (bug / proposal) and the **good first issue** label are prepared, creating an entrance where external improvement proposals and PRs come easily. CONTRIBUTING.md describes how to participate and the verification procedure.
- **SECURITY.md** is placed, directing vulnerability reports to private reporting rather than public Issues.
- **Releases** are distributed as signed APKs + checksums, keeping a change history.
- **CI (GitHub Actions)** automates builds and tests, keeping a state in which PRs can be verified when they come.
- Progress is visualized with Milestones / Projects. In coordination with the Quantus Network JAPAN community, results that can be returned upstream (the Quantus-Network org) — such as the Android port patches — are also shared upstream.

### Status addendum (part 9, 2026-11-11)

Later developments that postdate the sections above, which are kept as written:

- **DePIN integration shipped in v3.0 (2026-11-11).** Element 2's on-device track record grew into the DePIN participation record: with recording on (opt-in, off by default), the app writes a signed checkpoint every 10 minutes to an on-device journal and can export a proof bundle as a file. The standalone companion app that first implemented this is retired; its identity and history were transferred into the node app. Aggregation follows the design settled in the separate quantus-depin repository (SPEC v0.9); the public aggregation pilot has not started, and — as in §2 — no rankings are made and no monetary value is attached to the record.
- **One more secret.** §2's "the only remaining secret is the node identity key" predates DePIN: with recording on, the app also holds the DePIN identity seed, kept in an encrypted vault (Android Keystore). SECURITY.md states the current scope.
- **§11 corrections.** The README is English-only (the bilingual wording above predates the 2026-11-06 rule that public documents are written in English from the start; the usage guides remain the multilingual exception, in 9 languages). This repository has no GitHub Actions CI: verification is the project's local JVM suites, run before every release (see docs/RELEASE.md).
