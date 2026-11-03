# Usage Guide

[日本語版はこちら](USAGE.ja.md)

This app runs a **non-validator full node** for Quantus Network (QTC mainnet) on an Android phone. It syncs the chain, verifies every block itself, and relays data to peers. It does **not** mine, and it has **no wallet** — it never touches seed phrases or reward keys.

> Status: pre-release (Phase 1 MVP). There is no public release yet; test builds are shared directly by the maintainer. Once public releases start, signed APKs will be published on GitHub Releases with SHA-256 checksums.

## Requirements

- Android 12 or later, arm64 device
- Free storage: the node database is pruned (only the latest 256 blocks are kept), so it is designed to stay small. The exact on-device size is still being measured — as a guide, keep about 10 GB free for the first sync
- An internet connection. Wi-Fi is strongly recommended for the first sync (see below)

Verified so far: **Pixel 9a running GrapheneOS**. Other devices are expected to work but are unverified. If you try it on another model, please report the result (working or not) as a GitHub Issue — the verified list grows from those reports.

## Install

1. Get the APK file (from the maintainer for now; from GitHub Releases once public releases start — verify the SHA-256 checksum published with the release before installing).
2. Open the APK with your Files app and allow installation from that source when Android asks.
3. Install, then open **Quantus Node**.

## First run and the initial sync

1. (Optional) Set a **display name** and the **telemetry** option first (see Settings). Setting changes need a node restart (Stop → Start) to apply.
2. Tap **Start node**.
3. The status shows **Syncing** with the best block climbing toward the highest block. On a Pixel 9a the first sync from genesis runs at roughly 50 blocks/second — expect on the order of **2 hours**. One new block arrives about every 12 seconds after you are caught up, and the status turns to a synced state.
4. During the first sync, keep the phone **charging and on Wi-Fi** if you can. Syncing over mobile data works, but it downloads a lot of data and uses noticeably more battery (about 11% in 30 minutes measured during sync on mobile data). After the sync completes, the ongoing load is small.

You do not need to keep the app open. The node runs as a foreground service: it keeps running with the screen off or while you use other apps, and shows a persistent notification while it runs. Syncing resumes from where it stopped if you stop and start it again — the downloaded chain is kept on the device.

## What the screen shows

- **Status** — Syncing / synced, with a quiet ripple on the pulse view each time a new best block arrives
- **Best block / Highest block** — where your node is, versus the network tip it knows
- **Peers** — how many nodes you are connected to
- **Track** — your local record: total uptime, new blocks observed, and the most peers observed. Stored only on this device; never uploaded, and worth no money — it is simply your own trail of contribution
- **Quantum Watch** — your node's sync state and network connection health (external threat intel is not implemented yet)

## Settings

- **Appear in telemetry** (off by default) — when on, your node name and live status become publicly visible in the official telemetry at telemetry.quantus.com. Turn it on only if you want to be listed. Applies from the next node start (Stop → Start).
- **Display name** — the name your node uses (also shown in telemetry if enabled). Leave blank to use the default name `quantus-android-node`. Applies from the next node start.
- **Language** — follow the device setting, or pick one of 9 languages (Japanese, English, Simplified Chinese, Spanish, Hindi, Arabic, Korean, Russian, French).
- **Share log** — shares the node log as text. This is what to attach when you report a problem.

Settings never restart the node by themselves. If you change something while the node is running, the screen tells you a restart (Stop → Start) is needed.

## Running it day to day

- The node stops only when you tap **Stop node**, force-stop the app, or reboot the phone.
- After a reboot, open the app and tap **Start node** again — automatic start after boot is not implemented yet.
- On some manufacturers' Android versions (Samsung, Xiaomi, etc.), aggressive battery saving may stop background services. If your node keeps stopping, exempt this app from battery optimization in the system settings.
- Leaving a spare phone plugged in 24/7? Check the battery from time to time — old lithium batteries can swell. Keep the phone somewhere ventilated, ideally with a battery charge limit if your device offers one.

## If something goes wrong

- **The node exits right away** — tap **Share log** in Settings and send the log with a GitHub Issue (bug report template). The log shows each start attempt and why it ended.
- **Peers stay at 0** — check the connection, then Stop → Start once.
- **Your node does not appear in telemetry** — make sure the telemetry option is on and the node was restarted after turning it on. (Listing behavior is still being verified.)
- **Storage questions** — the database is pruned to the latest 256 blocks by design. Uninstalling the app deletes the database and the node's identity key; a reinstall starts a fresh sync with a new identity.

Questions, device reports, and ideas are welcome as GitHub Issues. Security issues follow [SECURITY.md](../SECURITY.md) instead of a public issue.
