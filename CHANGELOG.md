# Changelog

Pre-release development builds, newest first. Builds up to
versionCode 18 were test builds shared directly by the maintainer
and signed with the debug key; versionCode 19 / v0.1.0 is the first
build signed with the release key, and versionCode 20 / v0.1.0 is
the first public release (published 2026-11-04).
"versionCode" is the Android build number of the MVP app (the bundled
node binary is unchanged unless a build says otherwise).

## versionCode 23 / v0.1.3 — 2026-11-05
- **Maintenance release: the findings of the full-section audit
  of v0.1.2 (thirteen sections; no critical or high findings,
  three medium and seventeen low). The three mediums and the
  consequential lows are fixed here; two lows are deferred and
  named at the end.** No changes to the node launch arguments,
  the database, the permissions, or the bundled node binary
  (still the versionCode 10 build).
  - **The cancelled-start fix of v0.1.2 is now complete
    (medium, A-1).** The bounded wait for the destroyed child
    returned an outcome the teardown never looked at: the pid
    record was cleared unconditionally, so a child that outlived
    the 5-second bound (or an interrupt cut the wait short) lost
    its record after all — reopening, for exactly those cases,
    the double-start the wait was meant to close. The wait now
    reports whether the exit was confirmed, and the record is
    cleared only then; otherwise it stays, and a later launch
    waits on the process through the usual leftover path, whose
    own refusal bounds the outcome. Verified on the JVM with
    real processes, including a child that ignores SIGTERM and
    an interrupted wait.
  - **The in-app footer shows the real version again (medium,
    DOC-1).** v0.1.2 shipped with "v0.1.1" in the footer of all
    nine languages — the same staleness class versionCode 21
    fixed, reintroduced because the version bump missed the
    string table. All nine footers now read v0.1.3, and the
    release checklist (docs/RELEASE.md) names the footer as a
    bump item with a mechanical grep check.
  - **The usage guides describe the present again (medium,
    DOC-2).** All nine USAGE files still described the v0.1.0
    state — how to obtain the APK ("from the maintainer for
    now") and telemetry listing as unverified. They now state
    the current release, point at GitHub Releases, and record
    that listing is verified (the maintainer's node appears in
    the official list). The guides' measured figures were
    audited and are unchanged.
  - **A new session starts from unknown values (low, A-2/B-2).**
    Best/highest/peers were retained across sessions, so a new
    session's first judgement could call it SYNCED on the old
    session's numbers, and its STARTING screen and first
    notification presented them as current. They are reset at
    session establishment, with the other per-session state —
    and the first foreground post now happens after
    establishment, so it already describes the new session. The
    stopped screen keeps the last session's values until the
    next start, as before.
  - **Missing RPC fields no longer overwrite known values
    (low, B-1).** A health response without "peers" wrote a real
    0 over the retained count, and a sync response without
    "currentBlock" flipped a synced node back to SYNCING. Both
    now follow the principle F8 set for highestBlock: an absent
    field means "not said", and the judgement uses the retained
    values. Verified at expression level over the audit's
    scenarios (org.json cannot run on the JVM here).
  - **Display-name sanitizing covers the bidi marks (low,
    B-5).** U+200E, U+200F and U+061C (the directional marks)
    joined the stripped set, along with the word joiner
    (U+2060), the deprecated invisible operators (U+2061–2064)
    and the soft hyphen (U+00AD). The zero-width non-joiner and
    joiner remain kept, as before: they carry meaning in
    Arabic-script and Indic names.
  - **Sharing the log is bounded and coordinated (low, B-3).**
    The share read used to load the whole file outside the log
    writers' lock — a rotation mid-read could hand over a
    partial log that looked complete, and the tail cut could
    split a surrogate pair. It now reads at most ~3 bytes per
    wanted char from the tail, under the writers' own class
    lock, starts at a line boundary, and never splits a pair.
    What is shared is unchanged: the last 100,000 chars, with
    the omission marker when cut.
  - **Log rotation is atomic (low, B-4).** The retained tail is
    written to a temp file and renamed over the log, instead of
    truncate-and-rewrite in place, which lost the whole
    retained half if the process died mid-rotation. A failed
    rename leaves the original untouched for the next check —
    deliberately not a fallback to the in-place write. The
    v0.1.2 counter seeding and line-boundary behaviour are
    unchanged (JVM-re-verified).
  - **Settings and track reads survive a damaged preferences
    file (low, B-6).** The SharedPreferences getters threw
    ClassCastException on a stored value of the wrong type; the
    values are only written by the app itself, so that means
    damage done outside it — the audit's stated precondition —
    and the result was a crash in the UI or on the stop path.
    Reads now fall back to defaults, and a failed synchronous
    uptime commit is retried asynchronously instead of being
    silently dropped.
  - **Orphaned node processes can be recovered (low, A-4).**
    If the app process died while its node child survived,
    every later start refused for as long as the orphan lived —
    and a node never exits on its own. Now, when the session
    launching is the first one this process has ever
    established (so the leftover can have no living owner), the
    leftover was already waited on in vain, and the recorded
    pid still strictly names this app's own node executable
    (full-path cmdline match, never a substring, never this
    app's own process), the orphan is terminated — a same-UID
    kill — waited on again, and the start proceeds only once
    it is verifiably gone. Every other leftover keeps the
    wait-and-refuse behaviour, including leftovers of this
    process's own sessions and service recreations inside a
    live process: those have, or had, an owner. Implemented
    exactly within those bounds; NOT reproduced on a device.
  - **Build: the set-aside now covers every exit (low, A-3),
    and the signature is verified (low, 9-1/9-2).** An invalid
    QUANTUS_SIGNING value used to exit before the stale-APK
    set-aside; it now sets aside both candidate outputs first.
    build-manual.sh verifies the signed APK with apksigner
    before it may take the final path — a failed verification
    fails the build — and the signing comment now matches the
    shipped artifact (v3 scheme only, verified with
    `apksigner verify --verbose`).
  - **Documentation record-keeping (low, DOC-3/4/5).**
    SECURITY.md no longer speaks of public releases as future
    (they began 2026-11-04), its audit record now covers the
    versionCode 20 audit, the external audit of v0.1.1 and the
    full-section audit of versionCode 22, and app/README.md's
    present-tense values (versionName, 49 string keys) are
    current.
  - **Deferred, deliberately (named in the audit):** A-5 —
    the linker64 retry also runs for a leftover-refusal
    launch failure, adding a bounded wait and a misleading
    "second attempt" record; B-8 — most of the dashboard's
    per-second text updates are unconditional (the scroll
    protection keeps this harmless in practice). Both are
    cosmetic; neither is in this release.
  - **Verification.** JVM, against the real compiled classes:
    the sanitizing suite (the new strips, the kept joiners,
    the F11 regressions), the bounded-exit wait against real
    processes in five cases, the log sink (seeded rotation
    614,400 B → 262,163 B with no temp file left behind,
    under-cap no-op) and the share reader in ten cases
    (ASCII/CJK tails, line-boundary starts, surrogate-safe
    cut). Translations re-checked mechanically: 49 keys × 9
    languages, all footers v0.1.3. API surface re-scanned
    against the official API database: 619 references, none
    outside API ≤ 31 (the only unresolved entries are the two
    invokedynamic bootstrap artifacts every javac emits, as in
    versionCode 21/22). Byte-level comparison with the v0.1.2
    APK: only classes.dex, the version (two bytes in the
    binary manifest, byte-checked), and the signature files
    differ. Build negative tests: an invalid QUANTUS_SIGNING
    and a missing release key both fail with the previous APK
    already set aside. Not verified on a device or an
    emulator; a full Gradle build remains not runnable in the
    maintainer's build VM.

## versionCode 22 / v0.1.2 — 2026-11-05
- **Maintenance release: the findings of the external (ChatGPT)
  audit of v0.1.1 — eleven in total, delivered in two rounds
  (six, then five more), each verified against the code first,
  and all fixed here.** No changes to the node launch arguments,
  the database, the permissions, or the bundled node binary
  (still the versionCode 10 build). The first six:
  - **A cancelled start no longer loses track of its child
    (medium).** When a stop arrived while the node was still
    starting, the unaccepted child was destroyed and its pid
    record cleared immediately, without waiting for it to exit.
    A quick stop → start could then overlap the still-closing
    old node, and the new node died on the old one's database
    lock. The teardown now waits for the destroyed child to
    actually exit — bounded at 5 seconds, polling exitValue(),
    outside every lock — before clearing the pid record, so a
    following launch's leftover-process wait works exactly as
    designed. The wait and the pid-file interplay (including the
    case where the newer session records its own pid first) were
    desk-checked against the existing lifecycle sequences and
    exercised on the JVM with real processes.
  - **No more InputStream.readAllBytes() (medium).** The method
    only exists in the runtime from API 33, but this app supports
    API 31 (Android 12) and up: on Android 12/12L the call
    escapes as a NoSuchMethodError past every IOException catch
    — including in readPidFile, which runs at every node start.
    All four call sites (pid file, /proc reads, log rotation,
    log share) now use a plain chunk-loop reader. Not verified on
    a real API 31–32 device (none is available); the reference
    device runs a newer runtime, where behaviour is unchanged.
  - **Process.pid() is probed reflectively (medium, build
    hygiene).** The audit reported the direct call as a Gradle
    compile blocker because the method is missing from the
    SDK's android.jar; re-measurement here found the stub does
    contain it, so that mechanism could not be reproduced. What
    is documented fact: pid() is absent from the official API
    database (api-versions.xml) at every level, and the target
    runtime throws NoSuchMethodError for it (versionCode 17
    field run). The probe now goes through reflection, which
    keeps the runtime behaviour identical in every case and
    removes the last bytecode reference to a non-API method.
    All 345 external references of the compiled classes were
    checked against api-versions.xml (resolving through the
    superclass chain): apart from the two invokedynamic
    bootstrap methods every javac emits, nothing resolves
    outside the API ≤ 31 surface; the same check run on
    versionCode 21 flags exactly readAllBytes (since 33) and
    pid() (not in the database). A full Gradle assemble is
    still not runnable in the maintainer's build VM (the
    Gradle daemon does not start there), so Gradle compilation
    itself remains unverified.
  - **Log rotation now sees the existing log (low).** The
    rotation check counter restarted at zero for every service
    instance, so a log written by many short sessions — each
    under the 64 KiB check interval — never got a rotation
    check and grew past the 512 KB cap. The counter is now
    seeded with the existing file's length; verified on the
    JVM (a pre-grown 614,400-byte log rotates on a fresh sink's
    first write).
  - **Attempt capture caps line length (low).** The in-memory
    per-attempt capture kept whole lines (the file sink caps a
    line at 128 KB); kept lines are now truncated at 8192
    chars with a marker. Total line counts are unchanged.
  - **Gradle builds include the CA bundle (low).**
    build.gradle now points the assets source set at
    app/assets (cacert.pem), where the manual build's aapt2 -A
    expects it; a Gradle-built APK no longer ships without it.
    The native libraries still need the documented staging
    into src/main/jniLibs/arm64-v8a: the flat native/ layout
    the manual build uses cannot be expressed as a jniLibs
    source set (its roots must contain ABI-named
    subdirectories), so the staging directory is now named
    explicitly and the staging step stays required.
- The second round's five:
  - **A deeply nested RPC response can no longer kill the
    poller (medium).** org.json parses recursively, and a
    response body (up to the 64 KiB cap) of almost pure
    brackets overflows the stack: StackOverflowError is an
    Error, so it escaped rpcCall's catch (Exception) and
    runPoller's, and the polling thread died silently while
    the node kept running. (Reproduced against the AOSP
    org.json implementation, not on a device.) rpcCall now
    guards the parse with catch (Throwable) and returns null —
    a failed call, exactly like a timeout — and runPoller's
    catch is widened to Throwable as a last-resort net: a
    failed poll is a state, never a dead thread. State stays
    consistent because pollOnce applies its writes in
    generation-checked locked batches and the next poll
    re-reads everything.
  - **A missing highestBlock no longer reads as "synced"
    (low).** When system_syncState answered without a
    highestBlock, "at head" was judged true by definition and
    the status flipped to SYNCED mid-sync. Both status
    branches now judge against the retained last-known
    highest (updated first when a fresh value arrives); with
    no sync state at all, the retained best and highest are
    compared the same way. Before the first successful read
    nothing is retained and the behaviour is as before.
  - **The status notification can no longer get stuck on the
    previous session's text (low).** The de-duplication key
    survived across sessions while the new session's first
    notification is posted directly by startForeground —
    which never updates the key — so a matching key made the
    poller skip a post that had never reached the shade for
    the new session. The key is now reset where the session
    is established, alongside the other per-session state.
  - **The manual build's stale-APK set-aside now happens
    before every preflight check (low).** This closes the
    residual gap in P-5: the set-aside block sat after the
    keystore and native-library checks, so a build failing
    there still left the previous APK at the final path. It
    now runs as soon as the output path is decided. Verified
    with a negative test (keystore path pointed nowhere):
    the build fails, and the old APK and its .idsig are
    already at *.prev.
  - **Display-name sanitizing no longer lets a dash run hide
    behind a space (low).** Stripping leading dashes once and
    then trimming turned "- --help" into "--help" — an
    option token again. Strip-and-trim now repeats until
    stable, and a value that needs more than one pass (a
    dash run smuggled past whitespace) falls back to the
    default name instead of keeping what it reduces to; a
    single contiguous run is still just removed
    ("--abc" -> "abc").

## versionCode 21 / v0.1.1 — 2026-11-05
- **Maintenance release: the low-severity findings of the
  2026-11-05 full audit of the published v0.1.0, fixed.** A full
  re-read of the app sources plus a rebuild comparison found no
  high- or medium-severity defects (every entry of the rebuilt APK
  was byte-identical to the published one); the ten low-severity
  findings were triaged, and eight are fixed here. Two were
  deliberately left alone: the dashboard refresh's unconditional
  `setText` calls (the area versionCode 20 just fixed for scroll
  stability — the churn is not worth the regression risk) and
  TrackStore's synchronous `commit()` (a deliberate durability
  choice, documented in its code). No changes to the node launch
  arguments, the database, the permissions, or the bundled node
  binary (still the versionCode 10 build).
  - **Leftover-process check is strict now.** The pid-file check
    accepted any process whose cmdline contained "quantus" — which
    includes this app's own main process, so a recycled pid could
    have been mistaken for a leftover node and blocked every
    start. It now uses the same `libquantus_node.so` criterion as
    the /proc child scan. A missed leftover now surfaces as a
    refused start on the node's own database lock instead.
  - **An interrupted worker no longer abandons its session.** The
    `waitFor()` interrupt path destroyed the process and returned
    without running the finish path, which would have left a
    "started" service with no node behind it. Nothing interrupts
    that thread today; if it ever happens, the worker now destroys
    the process, keeps waiting for its real exit, and settles the
    session through the ordinary generation-checked finish (the
    linker64 quick-failure retry is skipped for an interrupted
    attempt). Verified by desk-checking the lifecycle sequences
    only — like the earlier race fixes, the interrupt and race
    sequences have NOT been reproduced on a real device.
  - **Display-name sanitizing hardened.** Bidirectional text
    controls (U+202A–U+202E, U+2066–U+2069), U+FEFF and U+200B are
    stripped (the name is public when telemetry is on, and these
    can make it look like a different name); the zero-width
    non-joiner and joiner (U+200C/U+200D) are kept, as they carry
    meaning in Arabic-script and Indic names. The 64-character cap
    now counts code points, so it can no longer split a surrogate
    pair. The raw name is also capped at 256 code points when
    stored.
  - **CA bundle freshness is a byte comparison.** The bundled
    Mozilla roots were re-copied only when their size changed; a
    same-size replacement would have gone stale. The file on disk
    is now compared byte-for-byte (the atomic write itself is
    unchanged). The per-attempt evidence join was also raised from
    1 s to 3 s, so a large unflushed backlog is less likely to
    truncate a failed attempt's final lines.
  - **Housekeeping.** Two unused string keys
    (`settings_unlock`, `settings_locked_note`) removed — the
    table is now 49 keys × 9 languages, machine-verified — the
    dashboard footer now reads v0.1.1 instead of the stale
    "v0.1-mvp", and the `NodeState.Attempt` javadoc now matches
    the implementation (the two deliberate "[app]" evidence notes
    are the documented exception to "the process's own lines").

## versionCode 20 / v0.1.0 — 2026-11-04
- **Pre-release fix: the dashboard no longer loses the scroll
  position.** On the device, swiping up to read the lower cards
  made the page slide back down on its own within a second: the
  1 s refresh's `setText` calls trigger a layout pass, and during
  that pass the `ScrollView` drifted toward a focused child.
  `refresh()` now records the scroll position on entry and
  restores it (posted, so it lands after the new layout) whenever
  the user is scrolled away from the top; the start/stop button,
  the language button and the two settings switches are no
  longer focusable in touch mode, so a tap cannot turn them into
  the anchor the ScrollView re-centres on; and the two multi-line
  notes (details, restart-to-apply) are only re-set when their
  text actually changed. The settings handlers get the same
  protection at their own entry points, because their state
  changes can re-lay out the page before refresh() ever runs:
  the settings-lock handler (which enables/disables the whole
  settings block via `applySettingsLock()`) and the telemetry
  handler (which rewrites the restart note via refresh()) now
  capture the scroll offset before changing anything and
  restore it afterwards, and a language pick — which recreates
  the activity — carries the offset across the recreate.
  Still v0.1.0 — versionCode 19 was never published — and still
  signed with the release key. No service or node changes; the
  node binary is the versionCode 10 build.

## versionCode 19 / v0.1.0 — 2026-11-04
- **First build signed with the release key** (itself never
  published — the published v0.1.0 is versionCode 20). A dedicated
  distribution key (RSA 4096, kept outside this repository) was
  generated, and the release build is signed with it instead of the
  debug key used by every earlier build. The signing certificate's
  SHA-256 fingerprint is
  65:BB:1A:5F:EF:27:D7:0B:E1:DD:12:FC:12:CE:34:84:B4:C8:48:FC:EC:A9:2F:88:98:CD:96:CB:27:84:F6:FF,
  published in the GitHub Release notes so anyone can verify that a
  downloaded APK (and every future update) carries the project's
  signature. versionName is now 0.1.0. `build-manual.sh` gains an
  opt-in release signing mode (`QUANTUS_SIGNING=release`); the
  default debug-signed development build is unchanged. No app code
  changes; the node binary is the versionCode 10 build.
- **Launcher icon added.** Every build so far had shipped with no
  icon (an empty `android:icon`); before publication this build
  adds an adaptive icon — a bright core with
  concentric green-to-teal pulse rings fading outward over a dark,
  radially lit background — plus legacy and round PNG fallbacks at
  every density. The mark sits inside the adaptive safe zone so it
  survives circle and squircle masks, and it is drawn
  programmatically (no external artwork).

## versionCode 18 — 2026-11-04
- **Pid recording restored on runtimes where `Process.pid()` throws.**
  The versionCode 17 field run confirmed the diagnosis: on the
  target device (Pixel 9a / GrapheneOS) `Process.pid()` throws, so
  versionCode 17's safety net caught it and the node started, but
  the pid was never recorded ("pid record FAILED for pid -1") and
  the cross-instance leftover wait (external audit finding 4) stayed
  degraded on that device class. `launch()` now resolves the child
  pid exactly once: `Process.pid()` first, and when that is
  unavailable, a bounded /proc scan — this process's own children
  (ppid parsed robustly from `/proc/<pid>/stat`) whose cmdline
  names the node library, retried for about a second and narrowed
  by an exact argv[0] match when ambiguous. The resolved pid
  travels back with the launch outcome; the pid file records it and
  the worker's cleanups clear the record by that same value instead
  of re-probing `pid()`. When the fallback route is used, the app
  log names the exception class `pid()` threw and the pid the scan
  recovered. If neither route yields a pid, the launch still
  proceeds unrecorded, exactly as in versionCode 17. No UI,
  settings, arguments, or lock-design changes; the node binary is
  the versionCode 10 build.

## versionCode 17 — 2026-11-04
- **Field-crash fix: the app died the moment Start was pressed.**
  On the first device run of the versionCode 13–16 builds (which
  jumped straight from versionCode 11), pressing Start killed the
  whole app. The node log shows the `[app] launch:` line repeated,
  but never `node started` or `start failed` — the worker thread
  died inside the spawn sequence, after the launch log and before
  any point that records an outcome, from something that is not an
  IOException: an Error or RuntimeException escaping `launch()`.
  The only new machinery in that window since versionCode 12 is the
  pid recording, and `Process.pid()` is the one call there whose
  failure mode is an Error on runtimes where it is missing or
  unsupported. The exact throwable is not yet confirmed — that is
  what the new safety net below is for. Changes: every `Process.pid()`
  call now goes through a `pidOf()` helper that returns -1 instead
  of throwing, a pid that cannot be obtained simply leaves the
  launch unrecorded (pid bookkeeping already never failed a launch),
  and `writePidFile` also absorbs RuntimeExceptions. The worker's
  launch loop additionally catches any other escaping Throwable,
  names it in the app log ("start error (direct): <class>: <msg>")
  and in the attempt evidence, then follows the normal failed-start
  flow — one linker64 retry, then a clean stop. No UI, settings,
  arguments, or lock-design changes; the node binary is the
  versionCode 10 build.

## versionCode 16 — 2026-11-04
- **Process-reference ownership fix (third external audit of the
  versionCode 15 tree; see SECURITY.md).** The audit confirmed the
  versionCode 14 fixes for findings 3 and 5 and the versionCode 15
  sequence-B fix, and found one residual race in how the worker owns
  the shared node-process reference: it registered its process
  unconditionally after launch, and the exit-path clear used an
  identity check that was not atomic with the assignment, so a stale
  worker could overwrite a newer session's reference — or pass the
  check and then null out the newer process's reference — leaving a
  live node unpollable and unstoppable. Registration is now a
  decision under the lifecycle lock: a process is stored only if,
  inside the lock, its session is still the live one; a process that
  is not accepted is destroyed and its pid record removed with it;
  and the exit-path check-and-clear is indivisible under the same
  lock. No behavior change in normal use; the node binary is the
  versionCode 10 build.

## versionCode 15 — 2026-11-04
- **Sequence-B race fix (found by an internal desk check of the
  versionCode 14 tree).** A START delivered while the previous
  session's worker was mid-teardown could be mistaken for a
  duplicate start: the teardown's `stopSelfResult()` then declined
  because of that newer startId, the service stayed in the
  foreground, and the start was consumed without ever creating a
  session — a node-less foreground service lingered. The
  duplicate-vs-new decision is now made under the lifecycle lock
  against the real state: if the previous session has already
  finished, the racing START claims the start and creates a session
  through the normal establishment flow. No behavior change in
  normal use; the node binary is the versionCode 10 build.

## versionCode 14 — 2026-11-04
- **Re-audit follow-ups (2026-11-04 ChatGPT re-audit of versionCode
  13; see SECURITY.md).** The re-audit confirmed the versionCode 13
  fixes for findings 2, 6 and 7, but found residual races in the
  fixes for findings 3, 4 and 5. No behavior change in normal use;
  the node binary is the versionCode 10 build.
  - *Service stop is serialized with a per-session startId.* The
    stop startId is bound to its session under the lifecycle lock —
    set at session creation, refreshed by duplicate STARTs and by
    STOP — and a finish performs the generation re-check, the
    startId read, `stopSelfResult()` and `stopForeground()` as one
    locked unit. A session starting in between can no longer lend
    its startId to a finishing one, and a duplicate START can no
    longer leave a node-less foreground service lingering after a
    natural exit.
  - *Pid-file operations are indivisible.* One process-wide lock is
    held across the leftover wait, the spawn and the pid record,
    and the record's check-and-clear runs under the same lock, so a
    late cleanup cannot erase a newer process's pid. A failed pid
    record is now logged and shown in the launch attempt's evidence
    instead of passing silently; the launch itself still proceeds
    (the process stays tracked in-process).
  - *Notification updates are serialized.* The poller's
    generation/state check and its `notify()` call run under the
    same lifecycle lock the stop paths hold while cancelling the
    notification, closing the check-then-post gap.
  - *Data-extraction rules cover all five domains.* Root and
    external storage join files, databases and shared preferences
    in the cloud-backup and device-transfer exclusions.
  - *Spawn timestamp is per-attempt.* The quick-exit lifetime clock
    is handed back by the launch itself instead of living in a
    shared field.

## versionCode 13 — 2026-11-04
- **External-audit follow-ups (findings 2–7 of the 2026-11-04 ChatGPT
  audit; see SECURITY.md).** No behavior change in normal use; the
  node binary is the versionCode 10 build.
  - *Node key no longer travels to a new phone.* Data-extraction
    rules now exclude all app data domains (files, databases,
    shared preferences) from both cloud backup and device-to-device
    transfer, so a phone migration cannot clone the node's P2P
    identity key. (`allowBackup=false` alone does not govern
    device transfer on Android 12+.)
  - *Stop is startId-checked.* Session teardown ends the service via
    `stopSelfResult()` with the creating START's startId (a STOP
    command's startId takes over when one arrives), so a delayed
    finish can no longer stop the service out from under a newer
    session whose start was already queued.
  - *Leftover node processes are waited out across service
    instances.* Each spawned child's pid is recorded in filesDir;
    before a new spawn, a leftover from a previous (possibly
    destroyed-and-recreated) service instance is waited on for up to
    5 s — verified via /proc as still being a quantus process, never
    killed — and the launch fails with the reason recorded instead of
    racing it for the database lock.
  - *Notification race closed.* The RPC poller no longer re-posts
    the status notification for a stale generation or after a stop
    has settled, and the stop paths cancel the notification when
    they end the service.
  - *Local RPC hardened.* Responses are capped at 64 KiB and HTTP
    redirects are no longer followed away from the loopback
    endpoint.
  - *Failed-start detection tightened.* The quick-exit lifetime is
    now measured from the moment the child process actually spawns,
    not from before the wait for a previous process, so a quick
    failed start can no longer be masked and skip the linker64
    retry.

## versionCode 12 — 2026-11-04
- **Settings lock is now a switch row, with its purpose always
  visible.** The lock was a borderless text button ("Lock settings"
  / "Unlock settings") and its purpose was explained only in a note
  shown while locked, which made it hard to understand. It is now
  an iOS-style switch in the same shape as the telemetry row — the
  switch position shows the state at a glance — with a short
  always-visible explanation under it: it prevents accidental
  changes to telemetry, display name, and language, and starting
  or stopping the node is never locked. Behavior is unchanged
  (same stored setting, the same three controls are disabled
  while locked, still no PIN). The USAGE guides were reworded to
  match the switch. The node binary is the versionCode 10 build.

## versionCode 11 — 2026-11-04
- **UI refresh (visual only).** The dashboard is reorganized in an
  Apple-style dark grouped-list design: pure-black background,
  rounded dark cards, iOS-settings-style label/value rows with
  hairline separators, a large pill-shaped start/stop button, and a
  quieter, thinner pulse ripple. Colors, spacing and typography are
  centralized in one style block in MainActivity. No behavior,
  wording, or displayed item changed; the node binary is the
  versionCode 10 build.

## versionCode 10 — 2026-11-04
- **Node binary rebuilt with security-updated dependencies.** A
  re-audit against the current RustSec database found 22 advisories
  in the shipped binary's dependency closure; 20 are resolved by
  semver-compatible lockfile updates: wasmtime 36.0.13 -> 36.0.20
  (including two critical Cranelift miscompilations), quinn-proto
  0.11.15 -> 0.11.19, rustls 0.23.32 -> 0.23.46, rustls-webpki
  0.103.13 -> 0.103.16, and the zstd stack. Two advisories remain
  (no compatible fix exists; see SECURITY.md for the exact status,
  which also corrects earlier wording about ring /
  curve25519-dalek / anyhow). The app code is unchanged from
  versionCode 9; the on-device checks for this binary are part of
  the ongoing Gate 1 verification.

## versionCode 9 — 2026-11-04
- Finishing touches ahead of an external audit (app internals and
  documentation; no new features):
  - The foreground-service timeout path now reads its session
    generation under the same lock as the other stop paths, so a
    timeout racing a manual restart cannot stamp a note on the
    wrong session.
  - The per-attempt start snapshot is now recorded only after the
    process actually starts, so a failed launch no longer leaves
    a record for a process that never existed.
  - The dashboard ripple now fires only when the best block
    increases after sync has completed — during the initial sync
    it no longer ripples almost continuously.
  - Polling now holds closer to its intended interval instead of
    stretching toward ~10 seconds when RPC calls are slow.
  - Sharing the log reads the file in the background instead of
    on the UI thread.
  - SECURITY.md gained a "Security notes" section (dependency
    advisory status, design boundaries, accepted known items,
    audit record), and all 9 usage guides now warn that a shared
    log can contain the node's public IP address and peer
    information.

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
