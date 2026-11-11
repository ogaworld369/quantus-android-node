# Quantus Android Node (working title)

Android-only, open-source app that runs a Quantus Network (QTC) full node on a phone.

**Purpose: decentralization, not profit.** This node does not mine and earns no rewards. It syncs, verifies every block itself, and relays data to peers — so the network depends a little less on a small number of servers, and a little more on ordinary people with hardware they already own.

The design concept is to make the node's pulse visible: live block/peer visualization with a quiet "presence" (KEHAI) design, and a local record of your contribution.

## At a glance

| | |
|---|---|
| Current release | **v3.0** (versionCode 58), published 2026-11-11 — [release page](https://github.com/ogaworld369/quantus-android-node/releases/tag/v3.0) |
| First public release | v0.1.0, published 2026-11-04 |
| Download | Signed APK from [GitHub Releases](https://github.com/ogaworld369/quantus-android-node/releases) |
| Requirements | Android 12 or later, arm64 device |
| Verified device | Pixel 9a running GrapheneOS |
| Languages | 9 (329 strings per language; see [docs/I18N.md](docs/I18N.md); native-speaker improvements are very welcome) |
| Telemetry listing | Opt-in only (off by default) |
| Licence | Apache License 2.0 |

## What this app is — and is not

- **It is** a non-validator full node for Quantus mainnet: it syncs, verifies every block itself, and relays data to peers.
- **It is not** a miner. It does not mine and earns no rewards.
- **It is not** a wallet. It never handles wallet seed phrases or reward keys; the only secrets on the device are identity keys, and neither controls any assets (see "Security first" below).
- **New in v3.0 — activity recording (DePIN), built in.** Opt-in and off by default. With it on, the app keeps a signed record of the node's uptime and relay work, which you can export as a proof bundle file whenever you choose.

## What's in v3.0

- **Activity recording (DePIN) is built into the node app** — the standalone companion app's role is complete. Recording writes a signed checkpoint every 10 minutes to an on-device journal; the identity seed is kept in an encrypted vault; an existing identity — key and full history — moves between installs in a single one-file exchange.
- **The main screens are redesigned** around a three-tab bottom navigation (home, connection, membership card). Home shows the live QTC price next to the pulse and the node state; the connection tab draws the node's network field in place of the previous radar-style figure; the membership card carries your member number (device ID) and three headline figures from your local record — no rankings, and the record is never converted into money.
- **Sync stall recovery.** The node service detects a stalled sync — the network tip advancing while the best block stays frozen — and restarts the node on its own (at most three times in 24 hours), with sync debug logging enabled so this class of stall leaves evidence.
- **Three rounds of pre-release final check**, with no critical findings in any round and every verified finding fixed before release.

Details and the full version history: [CHANGELOG.md](CHANGELOG.md).

## Download

Get the signed APK from [GitHub Releases](https://github.com/ogaworld369/quantus-android-node/releases). The current release is [v3.0](https://github.com/ogaworld369/quantus-android-node/releases/tag/v3.0); its APK size and SHA-256 are listed on the release page and in the [CHANGELOG](CHANGELOG.md), so you can verify your download. v3.0 installs over v0.2.2 keeping settings and synced data — verified in place on the reference device.

## Usage

New to the app? Start with the Usage Guide — available in all 9 languages the app speaks: [English](docs/USAGE.md), [日本語](docs/USAGE.ja.md), [简体中文](docs/USAGE.zh-Hans.md), [Español](docs/USAGE.es.md), [हिन्दी](docs/USAGE.hi.md), [العربية](docs/USAGE.ar.md), [한국어](docs/USAGE.ko.md), [Русский](docs/USAGE.ru.md), [Français](docs/USAGE.fr.md). Requirements, installation, the first sync, settings, and troubleshooting.

## Status

- Publicly released since 2026-11-04; the current release is v3.0 (above). Per-version details live in the [CHANGELOG](CHANGELOG.md), not here.
- Still being watched on the reference device: the remaining on-device checks — where the pruned database size plateaus, and battery/heat over long uptimes.
- Release gates: [docs/RELEASE.md](docs/RELEASE.md). Design: [docs/DESIGN.md](docs/DESIGN.md).

## Scope

- A non-validator full node for Quantus mainnet: sync, verify, relay, local status.
- No mining. No wallet. The app never handles seed phrases or reward keys — by design, there is no asset secret inside the app worth stealing.
- Devices: not Pixel-only. Requirements: Android 12+ on arm64 — the implementation has no Google-services or Pixel-specific dependency (it runs on GrapheneOS, which ships without Google services). Verified so far: Pixel 9a running GrapheneOS. Other devices are expected to work but are unverified; OEM battery savers (Samsung, Xiaomi, …) may stop the resident service. Device reports via GitHub Issues are welcome — the verified list grows from them.

## What we know so far

- Upstream: `quantus-node` v1.0.5 (Quantus-Network/chain), Substrate-based, QPoW / Poseidon2, ML-DSA signatures.
- The node cross-builds for `aarch64-linux-android`. Two small patches are needed, both in [patches/](patches/): one makes explicit pruning values take effect (next bullet), and one stops DNS resolution from panicking when the node runs as a plain process outside an Android app runtime (vendored `ndk-context` / `hickory-resolver` with a fallback). The build recipe is fixed in [docs/BUILD-android.md](docs/BUILD-android.md). Release-profile binary: ~53 MB stripped (debug: ~123 MB).
- Upstream forces archive storage on all nodes; a minimal patch (respect explicit numeric pruning) enables a phone-sized database. Details in the design doc.
- Official minimum specs list 100 GB storage (validator-oriented); a pruned non-validator node is far smaller in practice. Measured on a Pixel 9a after a full sync (2026-11-05): 1.30 GB in total (96 MB app + 1.20 GB of node data). Where it plateaus over months is still being watched.

## Roadmap (summary)

- Phase 0: feasibility spikes (mainnet DB size with warp + pruning; Android port blockers)
- Phase 1: node MVP (sync/verify/relay, status UI, foreground service)
- Phase 2: daily-driver hardening (power, data, storage caps; GrapheneOS soak tests)
- Phase 3: security review, reproducible builds, public release prep
- Phase 4: mainnet release and wider Android device support

## Security first

The secrets on the device are identity keys only: the node's P2P identity key, and — only when DePIN activity recording is turned on — the participation identity seed, stored encrypted in app-private storage. Neither controls any assets. Both are excluded from cloud backup (`allowBackup=false`). RPC stays on loopback only. No analytics, no ads, no remote code loading — native code ships inside the signed APK only. Distribution is signed APKs on GitHub Releases, gated by [docs/RELEASE.md](docs/RELEASE.md); an F-Droid listing is planned for a later phase.

## License

Apache License 2.0 — see [LICENSE](LICENSE). (The upstream node's crates declare Apache-2.0; its repository root LICENSE is MIT — the two are compatible for this use, and this app itself is Apache-2.0.)

## Support

Quantus Node for Android is a personal open-source project, developed for the love of decentralization. If it is useful to you, donations in QTC are gratefully received:

`qzoqy3pcLsBcPpGFqj9BTv3UyvRB9o8ScnKqcQfSbQTR6odnu`
