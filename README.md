# Quantus Android Node (working title)

Android-only, open-source app that runs a Quantus Network (QTC) full node on a phone.

**Purpose: decentralization, not profit.** This node does not mine and earns no rewards. It syncs, verifies every block itself, and relays data to peers — so the network depends a little less on a small number of servers, and a little more on ordinary people with hardware they already own.

Status: **design phase (v0.2, node-only)** — see [docs/DESIGN.md](docs/DESIGN.md).

## Scope

- A non-validator full node for Quantus mainnet: sync, verify, relay, local status.
- No mining. No wallet. The app never handles seed phrases or reward keys — by design, there is no asset secret inside the app worth stealing.
- First target device: Pixel 9a running GrapheneOS. Wider Android support later.

## What we know so far

- Upstream: `quantus-node` v1.0.5 (Quantus-Network/chain), Substrate-based, QPoW / Poseidon2, ML-DSA signatures.
- Official minimum specs list 100 GB storage (validator-oriented). How small a pruned, warp-synced non-validator node can be is being measured in Phase 0.
- No official Android build exists. Porting blockers (wasmtime, database backends, libc differences) are being catalogued in Phase 0.

## Roadmap (summary)

- Phase 0: feasibility spikes (mainnet DB size with warp + pruning; Android port blockers)
- Phase 1: node MVP (sync/verify/relay, status UI, foreground service)
- Phase 2: daily-driver hardening (power, data, storage caps; GrapheneOS soak tests)
- Phase 3: security review, reproducible builds, public release prep
- Phase 4: mainnet release and wider Android device support

## Security first

The only secret is the node's P2P identity key, wrapped by Android Keystore and excluded from cloud backup. RPC stays on loopback only. No analytics, no ads, no remote code loading — native code ships inside the signed APK only. Distribution is planned as signed APKs on GitHub Releases (and F-Droid).

## License

To be confirmed — Apache-2.0 is the leading candidate, matching upstream.
