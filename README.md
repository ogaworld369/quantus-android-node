# Quantus Android Node (working title)

Android-only, open-source app that runs a Quantus Network (QTC) full node on a phone.

**Purpose: decentralization, not profit.** This node does not mine and earns no rewards. It syncs, verifies every block itself, and relays data to peers — so the network depends a little less on a small number of servers, and a little more on ordinary people with hardware they already own.

Status: **Phase 1 MVP — full sync completed on a real device.** On 2026-11-03 the node started on a Pixel 9a (GrapheneOS) and completed a full sync from genesis to the chain tip (#341,592) in about 2 hours 21 minutes of node uptime, peaking at 18 peers. Current development builds (versionCode 9, shared directly by the maintainer — not a public release) add a telemetry TLS fix, phone-sized sync tuning, a settings lock, the fixes from the 2026-11-04 full audits, and an audited 9-language UI. Still in progress: the remaining on-device checks (hours of uptime, pruned database size, battery/heat). There is no public release yet. The release gates are listed in [docs/RELEASE.md](docs/RELEASE.md). Design: [docs/DESIGN.md](docs/DESIGN.md). Version history: [CHANGELOG.md](CHANGELOG.md).

Product concept: make the node's pulse visible — live block/peer visualization with a quiet "presence" (KEHAI) design, a local record of your contribution, and a quantum-threat watch. The UI speaks 9 languages (see [docs/I18N.md](docs/I18N.md); native-speaker improvements are very welcome). Telemetry listing is opt-in only (default off).

## Usage

New to the app? Start with the Usage Guide — available in all 9 languages the app speaks: [English](docs/USAGE.md), [日本語](docs/USAGE.ja.md), [简体中文](docs/USAGE.zh-Hans.md), [Español](docs/USAGE.es.md), [हिन्दी](docs/USAGE.hi.md), [العربية](docs/USAGE.ar.md), [한국어](docs/USAGE.ko.md), [Русский](docs/USAGE.ru.md), [Français](docs/USAGE.fr.md). Requirements, installation, the first sync, settings, and troubleshooting.

## Scope

- A non-validator full node for Quantus mainnet: sync, verify, relay, local status.
- No mining. No wallet. The app never handles seed phrases or reward keys — by design, there is no asset secret inside the app worth stealing.
- Devices: not Pixel-only. Requirements: Android 12+ on arm64 — the implementation has no Google-services or Pixel-specific dependency (it runs on GrapheneOS, which ships without Google services). Verified so far: Pixel 9a running GrapheneOS. Other devices are expected to work but are unverified; OEM battery savers (Samsung, Xiaomi, …) may stop the resident service. Device reports via GitHub Issues are welcome — the verified list grows from them.

## What we know so far

- Upstream: `quantus-node` v1.0.5 (Quantus-Network/chain), Substrate-based, QPoW / Poseidon2, ML-DSA signatures.
- The node cross-builds for `aarch64-linux-android`. Two small patches are needed, both in [patches/](patches/): one makes explicit pruning values take effect (next bullet), and one stops DNS resolution from panicking when the node runs as a plain process outside an Android app runtime (vendored `ndk-context` / `hickory-resolver` with a fallback). The build recipe is fixed in [docs/BUILD-android.md](docs/BUILD-android.md). Release-profile binary: ~53 MB stripped (debug: ~123 MB).
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

Apache License 2.0 — see [LICENSE](LICENSE). (The upstream node's crates declare Apache-2.0; its repository root LICENSE is MIT — the two are compatible for this use, and this app itself is Apache-2.0.)
