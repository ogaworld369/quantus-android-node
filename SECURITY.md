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
The node binary's dependency tree is inherited from upstream (Quantus-Network/chain, built on polkadot-sdk); this project adds no dependencies of its own to that tree. Status as of 2026-11-04:

- **h2 — resolved.** The locked h2 0.4.12 carried six known DoS-class advisories. It was updated to 0.4.20 in the lockfile, and the fix was confirmed by re-running cargo-audit.
- **Still present, inherited from upstream** — the same set is part of the official node's dependency tree; none of it comes from a change made by this project:
  - `ring` 0.17.14 — RUSTSEC-2025-0009 (an AES panic issue). No fixed release is available yet.
  - `curve25519-dalek` 3.2.0 — RUSTSEC-2024-0344 (timing variability in the old 3.x line, reachable via libp2p-noise key operations).
  - `anyhow` 1.0.99 — a yanked crate version. A yank is a retraction notice by the publisher, not a vulnerability; the build is unaffected.
- **Exposure assessment:** the app holds no asset secrets — there is no wallet and no seed phrase to steal; the only secret is the node's P2P identity key, kept in app-private storage. **Policy: track upstream.** When upstream updates these dependencies, the bundled node is rebuilt, re-verified and shipped in a new build.

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
