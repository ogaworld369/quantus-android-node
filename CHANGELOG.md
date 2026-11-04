# Changelog

Pre-release development builds, newest first. These are test builds
shared directly by the maintainer; there is no public release yet.
"versionCode" is the Android build number of the MVP app (the bundled
node binary is unchanged unless a build says otherwise).

## versionCode 8 — 2026-11-04
- Bug fixes from a full audit of the app sources (no
  high-severity findings; five of the reported items are fixed
  here, all in the node-service internals — no UI or translation
  changes):
  - Two stop → immediate-restart races: a finishing session could
    clear the new session's process reference (leaving the new
    node orphaned and unreachable), and a new start did not wait
    for the previous process to release the database lock, so it
    could fail on the spot. The finishing side now only clears
    its own reference, and a new start waits — bounded, 3
    seconds — for the previous process to exit first.
  - The log-drain thread now survives unexpected exceptions
    instead of dying silently, which would have stopped log
    capture (and could have stalled the node on a full pipe).
  - Display names no longer keep a leading '-' (the node's CLI
    parser could misread it as another option and fail the
    start).
  - The bundled CA root set is now written atomically (write to
    a temp file, verify the length, then rename), so a kill
    mid-copy cannot leave a truncated bundle behind and break
    telemetry TLS on the next start.

## versionCode 7 — 2026-11-03
- Settings lock: a "Lock settings" button at the bottom of the
  settings section. While locked, the telemetry switch, the display
  name field and the language picker are disabled (a mis-tap guard
  for a phone that sits running the node; no PIN, and node
  start/stop is never locked). Three new UI strings in all 9
  languages (50 keys total, mechanically checked).

## versionCode 6 — 2026-11-03
- Phone-sized sync tuning, from a code-level audit of the node:
  `--trie-cache-size` capped at 128 MiB (the 1 GiB default could not
  initialize on an 8 GB phone — the node logged a warning and ran
  without an effective cache), and `--max-blocks-per-request` raised
  to the protocol maximum of 128 (was 64). Includes everything in
  versionCode 5.

## versionCode 5 — 2026-11-03
- Telemetry fix: the node never appeared in the official telemetry
  list because its TLS connection failed with "no native root CA
  certificates found" — as a bare native process it cannot find
  Android's CA store. The app now bundles the official Mozilla CA
  root set (curl.se extract, SHA-256 verified) and passes
  `SSL_CERT_FILE` / `SSL_CERT_DIR` to the node process.
- Translation audit fixes across the 9 UI languages (French gender
  agreement, the "Track" section term unified in 5 languages,
  Chinese/Korean typography).

## versionCode 4 — 2026-11-03
- DNS fallback patch (vendored `ndk-context` / `hickory-resolver`):
  the node no longer panics at network start when run as a plain
  process. First build to run on a real device.
- Same day: the first full sync completed on a Pixel 9a
  (GrapheneOS) — genesis to tip (#341,592) in about 2 hours
  21 minutes of node uptime, peaking at 18 peers.

## versionCode 3 — 2026-11-03
- Evidence build: per-attempt start records on screen and a
  "Share log" button, built to diagnose the start-up failure that
  versionCode 4 then fixed.

## versionCode 2 — 2026-11-03
- Session-generation hardening and minor fixes from a full day of
  dynamic tests (no user-visible changes).

## versionCode 1 — 2026-11-03
- First MVP: foreground-service node with pruning (256 blocks),
  the "presence" (KEHAI) dashboard with a ripple per new block,
  local contribution track, quantum-watch frame, opt-in telemetry
  (default off), and a 9-language UI.
