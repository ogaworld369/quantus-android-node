# Quantus Android Node (working title)

Android-only, open-source app that runs a Quantus Network (QTC) full node on a phone.

**Purpose: decentralization, not profit.** This node does not mine and earns no rewards. It syncs, verifies every block itself, and relays data to peers — so the network depends a little less on a small number of servers, and a little more on ordinary people with hardware they already own.

Status: **Phase 1 MVP in development** — the node binary builds for Android (debug and release profiles), and the MVP app source is in [app/](app/). On-device verification is in progress; there is no public release yet. The release gates are listed in [docs/RELEASE.md](docs/RELEASE.md). Design: [docs/DESIGN.md](docs/DESIGN.md).

Product concept: make the node's pulse visible — live block/peer visualization with a quiet "presence" (KEHAI) design, a local record of your contribution, and a quantum-threat watch. The UI speaks 9 languages (see [docs/I18N.md](docs/I18N.md); native-speaker improvements are very welcome). Telemetry listing is opt-in only (default off).

## Scope

- A non-validator full node for Quantus mainnet: sync, verify, relay, local status.
- No mining. No wallet. The app never handles seed phrases or reward keys — by design, there is no asset secret inside the app worth stealing.
- First target device: Pixel 9a running GrapheneOS. Wider Android support later.

## What we know so far

- Upstream: `quantus-node` v1.0.5 (Quantus-Network/chain), Substrate-based, QPoW / Poseidon2, ML-DSA signatures.
- The node cross-builds for `aarch64-linux-android` with zero upstream source patches (one lockfile update). The build recipe is fixed in [docs/BUILD-android.md](docs/BUILD-android.md). Release-profile binary: ~53 MB stripped (debug: ~123 MB).
- Upstream forces archive storage on all nodes; a minimal patch (respect explicit numeric pruning) enables a phone-sized database. Details in the design doc.
- Official minimum specs list 100 GB storage (validator-oriented); a pruned non-validator node is expected to be far smaller. On-device measurement is pending.

## Roadmap (summary)

- Phase 0: feasibility spikes (mainnet DB size with warp + pruning; Android port blockers)
- Phase 1: node MVP (sync/verify/relay, status UI, foreground service)
- Phase 2: daily-driver hardening (power, data, storage caps; GrapheneOS soak tests)
- Phase 3: security review, reproducible builds, public release prep
- Phase 4: mainnet release and wider Android device support

## Security first

The only secret is the node's P2P identity key, kept in app-private storage and excluded from cloud backup (`allowBackup=false`). RPC stays on loopback only. No analytics, no ads, no remote code loading — native code ships inside the signed APK only. Distribution is planned as signed APKs on GitHub Releases (and F-Droid), gated by [docs/RELEASE.md](docs/RELEASE.md).

## License

To be confirmed — Apache-2.0 is the leading candidate, matching upstream.
