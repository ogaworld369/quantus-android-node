# Security Policy

## Reporting a vulnerability
Please do **not** open a public issue for security vulnerabilities.
Use GitHub's private vulnerability reporting ("Security" tab → "Report a vulnerability") for this repository.

We will acknowledge reports as soon as we reasonably can and will credit reporters who wish to be named.

## Scope notes
- This app holds no wallet secrets by design; its only secret is the node P2P identity key.
- There is no public release yet. When public releases begin, signed release APKs and their SHA-256 checksums will be published on the Releases page. Builds from other sources should not be trusted.

## Security notes

### Dependency advisories
The node binary's dependency tree is inherited from upstream (Quantus-Network/chain, built on polkadot-sdk); this project adds no dependencies of its own to that tree.

Re-checked on 2026-11-04 against the then-current RustSec advisory database, scoped to the dependency closure of the shipped `quantus-node` binary. (A whole-workspace audit reports more; the difference is tooling and test-only graphs that never enter the binary.)

Of the 22 advisories inside the binary's closure, 20 are resolved by semver-compatible lockfile updates, applied and shipped in the versionCode 10 node binary (verified with `cargo check` for `aarch64-linux-android` before the rebuild):

- `wasmtime` 36.0.13 -> 36.0.20 (six advisories, including two critical Cranelift miscompilations; three of the six concern the component model and WASI, which this build does not enable),
- `quinn-proto` 0.11.15 -> 0.11.19 (five advisories; the QUIC stack is only exercised by the node's optional external-miner server, which this app never enables),
- `rustls` 0.23.32 -> 0.23.46 and `rustls-webpki` 0.103.13 -> 0.103.16 (TLS client paths: opt-in telemetry, offchain HTTP, litep2p),
- `zstd` 0.13.3 -> 0.13.4, `zstd-safe` 7.2.4 -> 7.4.0, `zstd-sys` -> 2.1.2 (wasmtime's on-device module cache).

Two advisories remain in the closure because no fixed release exists inside the version ranges the pinned framework allows; both are inherited from upstream unchanged:

- `zstd-safe` 6.0.6 (RUSTSEC-2026-0403) — uninitialized memory when streaming decompression of a *legacy-format* frame fails. The only call site in this build decompresses the chain's own runtime blobs, which are produced in the current format.
- `crypto-bigint` 0.5.5 (RUSTSEC-2026-0387) — timing variability in operations intended to be constant-time. The fix requires a newer elliptic-curve stack than the pinned framework provides.

Four items from earlier reviews are **not applicable** to the current lockfile, and this section corrects earlier wording that said otherwise: `h2` is locked at the fixed 0.4.20; `ring` 0.17.14 is inside the patched range of RUSTSEC-2025-0009 (>= 0.17.12); `curve25519-dalek` is locked at 4.1.3, the fixed release for RUSTSEC-2024-0344 (>= 4.1.3) — no 3.x copy exists in the lockfile; `anyhow` is locked at 1.0.104, not the yanked 1.0.99.

**Exposure assessment:** the app holds no asset secrets — there is no wallet and no seed phrase to steal; the only secret is the node's P2P identity key, kept in app-private storage. **Policy: track upstream** — apply semver-compatible lockfile security updates as they become available, follow upstream releases for the rest, and re-run this audit on every node binary rebuild.

### Security boundaries by design
- No wallet, no seed phrases, no reward keys: there is no asset secret inside the app.
- The node's RPC listens on loopback only (127.0.0.1) and is never exposed to the network; the app polls it locally.
- The only exported Android component is the launcher activity; the node service is not exported.
- Telemetry is opt-in and off by default: the node starts with `--no-telemetry` unless the user explicitly turns telemetry on. Turning it on makes the node name and status publicly visible.
- The node identity key is generated on first start and stays in app-private storage; cloud backup is disabled (`allowBackup=false`) and data-extraction rules exclude all five data domains (root, files, databases, shared preferences and external storage) from both cloud backup and device-to-device transfer, so a phone migration cannot clone the identity. No user input is involved. It is unrelated to any wallet.
- Distribution, once public releases begin: signed APKs with SHA-256 checksums published alongside (see Scope notes).

### Accepted, known items
- **Two small patches on upstream code** make a phone-sized node possible: one makes explicit pruning values take effect, the other adds a DNS fallback so the node can run as a plain process (both in `patches/`). They are a standing maintenance commitment: on every upstream version bump they are re-applied and re-verified before a build ships.
- **Log-writing granularity:** the node's log sink opens/closes the file per line and re-reads to rotate it. It completed a full on-device sync (about 2 hours 21 minutes) with no observed ill effect; this is accepted as a performance item, not a correctness issue.
- **Synchronous save on the stop path:** uptime is saved with a synchronous commit when the node stops. This is deliberate — preserving the contribution record takes priority over a few milliseconds of latency.

### External audit — 2026-11-04 (ChatGPT)
An external source audit of the versionCode 12 tree raised seven findings; six were confirmed and addressed in versionCode 13. A re-audit of the versionCode 13 tree then confirmed the fixes for findings 2, 6 and 7, withdrew the cleartext finding's priority (see the last paragraph), and showed that the versionCode 13 fixes for findings 3, 4 and 5 left residual races. Those are addressed in versionCode 14, and the status below is the versionCode 14 state. On-device race-reproduction tests have **not** been performed: the race items are closed by construction and code review, not by reproduction, and this section does not claim otherwise.

- **Finding 2 (node key could travel to a new device):** `allowBackup=false` does not govern device-to-device transfer on Android 12+. Data-extraction rules exclude all five data domains — root, file, database, shared preferences and external — from both cloud backup and device transfer.
- **Finding 3 (stop could tear down a newer session):** versionCode 13 ended the service with `stopSelfResult()` on a startId recorded per session, but the value was re-read from a shared field outside the lifecycle lock at stop time, a duplicate START never refreshed it (so a natural exit could leave a node-less service lingering), and the stop sequence was not atomic with the generation check. In versionCode 14 the stop startId is bound to its session and managed under the lifecycle lock — set when the session is created, refreshed by duplicate STARTs while it is live and by a STOP command — and a finish performs the generation re-check, the startId read, `stopSelfResult()`, and (on acceptance) `stopForeground()` plus the notification cancel as one locked unit, so a START cannot interleave between the check and the act.
- **Finding 4 (database lock race across service instances):** the previous F-2 wait only covered a process the same service instance still referenced. Each spawned child's pid is recorded in filesDir; a later instance waits up to 5 s for a verified leftover (checked via `/proc/<pid>/cmdline`, never killed) and otherwise fails the launch with the reason recorded, instead of racing it for ParityDB's exclusive lock. In versionCode 14 the pid-file operations are indivisible under a process-wide lock that launch() holds across the whole wait → spawn → record sequence, and the record's check-and-clear runs under the same lock, so a late cleanup cannot erase a newer process's pid. If the pid record itself cannot be written, the launch still proceeds — the process remains tracked in-process through its `Process` handle — and the failure is recorded in the app log and in the launch attempt's evidence; in that case cross-instance tracking degrades rather than being guaranteed.
- **Finding 5 (stale notification after stop):** in versionCode 14 the poller's notification update performs its generation/state check and the `notify()` call itself under the same lifecycle lock the stop paths hold while cancelling the notification, so a passed check can no longer be followed by a stop settling in between and the post landing afterwards. The stop paths also cancel the status notification when they end the service.
- **Finding 6 (unbounded local RPC reads):** RPC responses are capped at 64 KiB and redirects are no longer followed away from the loopback endpoint. (Confirmed fixed by the re-audit.)
- **Finding 7 (quick-exit window could be masked):** the failed-start lifetime is measured from the actual process spawn, excluding the wait for a previous process, so a quick failed start reliably triggers the one linker64 retry. The spawn timestamp is a per-attempt value handed back by the launch, not a shared field. (Main fix confirmed by the re-audit.)

One finding was reviewed and deliberately **not** acted on: the suggestion to add a network security configuration for the loopback RPC's cleartext HTTP. The platform's default network security policy permits cleartext traffic to loopback destinations, and the app's RPC polling over `http://127.0.0.1:9944/` has been observed working on the target device class in every build since versionCode 1; no configuration is added for it. The re-audit withdrew this finding's original priority rating for the verified device class. When the supported-device range is widened, per-OS-version RPC reachability checks are part of device verification.

### Audit record
- 2026-11-03: a security audit (supply chain, dependency advisories, secret handling) confirmed that no secret value appears in the repository, its history, or the shipped APK.
- 2026-11-04: a full-source bug audit of the app, plus an extended audit covering the Rust patches, the provenance of the shipped node binary, the build pipeline and documentation consistency. **None of the audits produced a finding of high severity.** The confirmed findings were fixed in versionCode 8 and versionCode 9.
- 2026-11-04: a third-party-style double-check of the public repository, followed by a dependency re-audit against the current RustSec database (see "Dependency advisories" above). The double-check found no high-severity issue; the dependency re-audit's fixes shipped in versionCode 10, and its corrections to this file's earlier dependency wording are included above.
- 2026-11-04: an external audit (ChatGPT) of the versionCode 12 source tree. Of its seven findings, six were confirmed and fixed in versionCode 13; one (cleartext configuration for the loopback RPC) was reviewed and deliberately not acted on. See "External audit — 2026-11-04 (ChatGPT)" above.
- 2026-11-04: a re-audit (ChatGPT) of the versionCode 13 source tree. It confirmed the fixes for findings 2, 6 and 7 and withdrew the cleartext finding's priority for the verified device class; it found residual races in the versionCode 13 fixes for findings 3, 4 and 5, addressed in versionCode 14. On-device race-reproduction tests have not been performed.
