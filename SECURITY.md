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
- The node identity key is generated on first start and stays in app-private storage; cloud backup is disabled (`allowBackup=false`), and no user input is involved. It is unrelated to any wallet.
- Distribution, once public releases begin: signed APKs with SHA-256 checksums published alongside (see Scope notes).

### Accepted, known items
- **Two small patches on upstream code** make a phone-sized node possible: one makes explicit pruning values take effect, the other adds a DNS fallback so the node can run as a plain process (both in `patches/`). They are a standing maintenance commitment: on every upstream version bump they are re-applied and re-verified before a build ships.
- **Log-writing granularity:** the node's log sink opens/closes the file per line and re-reads to rotate it. It completed a full on-device sync (about 2 hours 21 minutes) with no observed ill effect; this is accepted as a performance item, not a correctness issue.
- **Synchronous save on the stop path:** uptime is saved with a synchronous commit when the node stops. This is deliberate — preserving the contribution record takes priority over a few milliseconds of latency.

### Audit record
- 2026-11-03: a security audit (supply chain, dependency advisories, secret handling) confirmed that no secret value appears in the repository, its history, or the shipped APK.
- 2026-11-04: a full-source bug audit of the app, plus an extended audit covering the Rust patches, the provenance of the shipped node binary, the build pipeline and documentation consistency. **None of the audits produced a finding of high severity.** The confirmed findings were fixed in versionCode 8 and versionCode 9.
- 2026-11-04: a third-party-style double-check of the public repository, followed by a dependency re-audit against the current RustSec database (see "Dependency advisories" above). The double-check found no high-severity issue; the dependency re-audit's fixes shipped in versionCode 10, and its corrections to this file's earlier dependency wording are included above.
