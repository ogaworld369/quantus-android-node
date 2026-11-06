# Usage Guide

**Languages:** English · [日本語](USAGE.ja.md) · [简体中文](USAGE.zh-Hans.md) · [Español](USAGE.es.md) · [हिन्दी](USAGE.hi.md) · [العربية](USAGE.ar.md) · [한국어](USAGE.ko.md) · [Русский](USAGE.ru.md) · [Français](USAGE.fr.md)

This app runs a **non-validator full node** for Quantus Network (QTC mainnet) on an Android phone. It syncs the chain, verifies every block itself, and relays data to peers. It does **not** mine, and it has **no wallet** — it never touches seed phrases or reward keys.

> Status: v0.2.1 released (2026-11-06), the current release. The signed APK is published on [GitHub Releases](https://github.com/ogaworld369/quantus-android-node/releases) with a SHA-256 checksum. Earlier test builds were shared directly by the maintainer.

## Requirements

- Android 12 or later, arm64 device
- Free storage: the node database is pruned (only the latest 256 blocks are kept), so it stays small. Measured on a Pixel 9a after a full sync (2026-11-05): 1.30 GB in total (96 MB app + 1.20 GB of node data). As a guide, keep at least about 3 GB free
- An internet connection. Wi-Fi is strongly recommended for the first sync (see below)

Verified so far: **Pixel 9a running GrapheneOS**. Other devices are expected to work but are unverified. If you try it on another model, please report the result (working or not) as a GitHub Issue — the verified list grows from those reports.

## Install

1. Get the APK file from [GitHub Releases](https://github.com/ogaworld369/quantus-android-node/releases) — verify the SHA-256 checksum published with the release before installing.
2. Open the APK with your Files app and allow installation from that source when Android asks.
3. Install, then open **Quantus Node**.

## First run and the initial sync

1. (Optional) Set a **display name** and the **telemetry** option first (see Settings). Setting changes need a node restart (Stop → Start) to apply.
2. Tap **Start node**.
3. The status shows **Syncing** with the best block climbing toward the highest block. On a Pixel 9a the first sync from genesis runs at roughly 50 blocks/second — expect on the order of **2 hours**. One new block arrives about every 12 seconds after you are caught up, and the status turns to a synced state.
4. During the first sync, keep the phone **charging and on Wi-Fi** if you can. Syncing over mobile data works, but it downloads a lot of data and uses noticeably more battery (about 11% in 30 minutes measured during sync on mobile data). After the sync completes, the ongoing load is small.

You do not need to keep the app open. The node runs as a foreground service: it keeps running with the screen off or while you use other apps, and shows a persistent notification while it runs. Syncing resumes from where it stopped if you stop and start it again — the downloaded chain is kept on the device.

## What the screens show

The app has four screens. Home is where you start; the others are one tap away from it.

- **Home** — the pulse, the node's state (Stopped / Starting / Syncing / Synced / Failed) and the one main button. While the node runs, it also shows the connected peer count and this session's uptime. The pulse draws one quiet ripple only while the node is synced, at the moment a new best block is first observed. If a start fails, Home shows it as a failure with a retry button instead of a plain stop; if a setting you changed has not taken effect yet, Home shows a link to review it
- **Node details** — best block / highest block (where your node is, versus the network tip it knows), the sync gap, peers and uptime, and the **Diagnostics** section: exit code, service notes, the record of each start attempt, and **Share log** (see below). While the node is stopped, this screen shows your cumulative track record instead of the previous session's values
- **Track record** — your local record: total uptime, new blocks observed, and the most peers observed. Stored only on this device; never uploaded, and worth no money — it is simply your own trail of contribution
- **Settings** — the four settings below; the app version is shown at the bottom

## Settings

- **Appear in telemetry** (off by default) — when on, your node name and live status become publicly visible in the official telemetry at telemetry.quantus.com. Turn it on only if you want to be listed. Applies from the next node start (Stop → Start).
- **Display name** — the name your node uses (also shown in telemetry if enabled), edited in a dialog. Leave blank to use the default name `quantus-android-node`. Applies from the next node start.
- **Language** — follow the device setting, or pick one of 9 languages (Japanese, English, Simplified Chinese, Spanish, Hindi, Arabic, Korean, Russian, French). Applies immediately.
- **Lock settings** — locks the three settings above (telemetry, display name, language) so they cannot be changed by accident while the phone sits there running the node. It is a mis-tap guard only: there is no PIN, and starting or stopping the node is never locked. Turn **Lock settings** off when you want to change settings again.

Settings never restart the node by themselves. If you change telemetry or the display name while the node is running, the changed item is marked in Settings as not yet applied, and Home shows a link to review the pending settings; the change takes effect at the next node start (Stop → Start).

**Share log** lives under **Node details → Diagnostics**: it shares the node log as text, and is what to attach when you report a problem. Before posting a log in a public place, check its contents first: it can include your node's public IP address and peer information.

## Running it day to day

- The node stops only when you tap **Stop node**, force-stop the app, or reboot the phone.
- After a reboot, open the app and tap **Start node** again — automatic start after boot is not implemented yet.
- On some manufacturers' Android versions (Samsung, Xiaomi, etc.), aggressive battery saving may stop background services. If your node keeps stopping, exempt this app from battery optimization in the system settings.
- Leaving a spare phone plugged in 24/7? Check the battery from time to time — old lithium batteries can swell. Keep the phone somewhere ventilated, ideally with a battery charge limit if your device offers one.

## If something goes wrong

- **The node exits right away** — open **Node details → Diagnostics → Share log** and send the log with a GitHub Issue (bug report template). The log shows each start attempt and why it ended.
- **Peers stay at 0** — check the connection, then Stop → Start once.
- **Your node does not appear in telemetry** — make sure the telemetry option is on and the node was restarted after turning it on. (Listing is verified working: the maintainer's own node appears in the official list.)
- **Storage questions** — the database is pruned to the latest 256 blocks by design. Uninstalling the app deletes the database and the node's identity key; a reinstall starts a fresh sync with a new identity.

Questions, device reports, and ideas are welcome as GitHub Issues. Security issues follow [SECURITY.md](../SECURITY.md) instead of a public issue.
