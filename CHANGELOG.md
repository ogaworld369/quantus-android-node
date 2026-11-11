# Changelog

Pre-release development builds, newest first. Builds up to
versionCode 18 were test builds shared directly by the maintainer
and signed with the debug key; versionCode 19 / v0.1.0 is the first
build signed with the release key, and versionCode 20 / v0.1.0 is
the first public release (published 2026-11-04).
"versionCode" is the Android build number of the MVP app (the bundled
node binary is unchanged unless a build says otherwise).

## versionCode 58 / v3.0 — 2026-11-11
- **The DePIN integration release.** The node launch arguments, the
  database and the bundled node binary are unchanged (still the
  versionCode 10 build). No permission is added (still five).
  - **Activity recording (DePIN) is built into the node app —
    opt-in, off by default.** The standalone companion app's role
    is complete. With recording on, the app writes a signed
    checkpoint of the node's uptime and relay work every 10
    minutes to an on-device journal; the identity seed is kept in
    an encrypted vault (Android Keystore). Nothing is transmitted
    by the app: a proof bundle leaves the phone only as a file the
    user exports and shares. An existing identity — key and full
    history — moves from another install in a single one-file
    exchange; the strict multi-step transfer remains available.
  - **The main screens are redesigned.** A bottom navigation with
    three tabs — home, connection, membership card. Home shows
    the live QTC price next to the pulse and the node state; the
    connection tab draws the node's network field (peers and
    checkpoint echoes) in place of the previous radar-style
    figure; the membership card carries the member number (device
    ID) and three headline figures from the local record. No
    rankings, and the record is never converted into money.
  - **Three rounds of pre-release final check, all findings
    addressed.** No critical findings in any round: round 1 (two
    high, seven medium, sixteen low), round 2 (one high, three
    medium, nine low) and round 3 (no high, two medium, eleven
    low) were all re-verified against the code before fixing —
    no finding was dismissed — and are fixed across versionCodes
    53–58, together with the remaining migration-recovery items.
    Among them: a journal-cache defect that could truncate
    participation history after a migration, a staging path that
    wrote a transferred identity seed to disk in plaintext
    (removed — imports now decrypt in memory), quadratic
    re-verification cost when importing recorded history, and a
    corrupted RPC attempt log that had no in-app recovery path.
  - **Sync stall recovery.** A node whose best block stops
    advancing while the network tip keeps rising (seen in the
    field with few peers on a mobile connection) is now detected
    — syncing, tip ahead, best block unchanged for 30 minutes —
    and the node restarts automatically (at most 3 times in 24
    hours). Node startup now also carries sync debug logging,
    bounded by the existing log rotation, so this class of stall
    leaves evidence in the log.
  - **Bookkeeping.** The version defaults in `build.gradle` now
    carry the shipped version (versionCode 58 / 3.0), and
    `docs/I18N.md`'s string table is regenerated from the current
    `StringsTable.java` (329 keys × 9 languages; the previous
    table had decayed into "?" placeholders for non-ASCII text).

  - **Release record.** APK: 55,995,644 bytes, SHA-256
    `8df4591a9c966ab098cde4f1425191e1496adc501b20b8f3bde2e5595ff6684a`.
    Installed over v0.2.2 on the reference device, keeping
    settings and synced data — verified in place.

## versionCode 28 / v0.2.2 — 2026-11-06
- **Diagnostic update: the shared log now explains the phone it
  came from.** The node launch arguments, the database, the UI and
  the bundled node binary are unchanged (still the versionCode 10
  build). One permission is added: ACCESS_NETWORK_STATE (read-only,
  see below).
  - **A summary block heads every shared log.** App and device
    (app version, Android version and API level, device model and
    maker, chain, install and update times), the state at share
    time (status, session uptime, best / highest / peer counts,
    when the last RPC response arrived, telemetry on/off — the
    running session's launched value, or the saved setting,
    marked as such, when stopped), device
    resources (node data size, free storage, battery level and
    temperature, free memory, battery-optimization exemption,
    notification permission), session history (how the previous
    session ended and its exit code — kept across a restart, so
    it survives into the next session —, starts in the last 24
    hours, the cumulative track record, when each setting last
    changed)
    and facts about the log itself (the shared excerpt's time
    range, whether it was truncated, WARN / ERROR line counts).
    Labels are fixed English; a value the app cannot know is
    printed as "n/a", never guessed — the finalized height, the
    bundled binary's own version and the genesis hash are the
    standing examples. The block sits outside the excerpt's
    existing 100,000-character cap.
  - **Network changes are stamped into the log.** When the
    default network's type changes (wifi / cellular / none /
    other), the service writes an "[app] network: …" line, so a
    peer drop in a shared log can be read against the connection
    it rode on. This is what ACCESS_NETWORK_STATE is for; the
    stamps stay in the on-device log and nothing is transmitted.
  - **Sharing can mask identifying values, on by default.** The
    share dialog gains a toggle (on unless the user turns it
    off): the display name — both the running session's launched
    name and the current setting's —, the IP addresses of this
    device's current network interfaces and the app-private
    paths are replaced in the shared log text, as whole tokens
    only (a peer's similar address, e.g. 10.0.0.10 next to this
    device's 10.0.0.1, is left untouched), so a log pasted into
    a public issue keeps its diagnostic structure without them.
    The summary block carries none of those values either way.
    If the device's addresses cannot be enumerated, masking
    stays on for that share. The external address the node
    learns through its peers (the "Discovered new external
    address" lines) is the network's outward-facing public
    address, not an interface address, so it is not masked and
    can remain in a shared log.
  - **Two small records feed the summary.** Accepted session
    starts are stamped (kept for 7 days, at most 100 entries; a
    full record marks the 24-hour count as a lower bound) for
    the 24-hour start count, and each setting setter now keeps
    its last-change time. Both live only in the app's private
    preferences.

  - **Release record.** APK: 55,733,030 bytes, SHA-256
    `e1b8fef1f4cde966b5bfe40fdc135c380fd6d555d82d96d06dc5d685ad72b701`.
    Installed over v0.2.1 — verified in place on the reference
    device, where the summary block and the masking were
    confirmed in an actual share.

## versionCode 27 / v0.2.1 — 2026-11-06
- **Maintenance update: the two verified findings of an external
  audit of v0.2.0 (one medium, one low).** No changes to the node
  launch arguments, the database, the permissions, the UI, or the
  bundled node binary (still the versionCode 10 build).
  - **A node process can no longer be lost inside the app process
    (medium).** When the on-disk pid record for a freshly spawned
    node could not be written, the spawn-intent marker was the only
    trace of the child; a service destroyed and recreated within
    the same app process then held neither the process object nor
    a usable record, and a restart could spawn a second node onto
    the same database and RPC port while the first was still
    exiting. Spawned children are now also tracked in a
    process-wide registry: the launch registers the child before
    the pid record is attempted, the worker unregisters it when it
    observes the exit, and the leftover wait consults the registry
    before the pid file and the marker — so the child is found by
    object, waited on as before, and never spawned over. A failed
    pid record still does not fail the launch, and the
    cross-process recovery (pid record, marker, orphan scan) is
    unchanged.
  - **The display name the user confirms is the name the node
    receives (low).** The editor validated and previewed one
    normalization of the name while the launch applied another
    whose steps ran in a different order (the length cap before
    the leading-dash strip), so for inputs such as "--" followed
    by 64 letters the confirmed name and the launched name
    differed. There is now a single normalization, in the
    editor's order; the editor stores the normalized value, the
    64-code-point limit is checked against the normalized result
    (over the limit is still refused, never truncated), and the
    launch applies no second transformation of its own.

  - **Release record.** APK: 55,733,030 bytes, SHA-256
    `9f1ce2da422a11d2355572ffa113e18bf02bb18a6b8f743d1c28bea96e6db46c`.

## versionCode 26 / v0.2.0 — 2026-11-06
- **The dashboard is redesigned from scratch — same node, same data,
  a new face.** No changes to the node launch arguments, the
  database, the permissions, the settings' stored values, or the
  bundled node binary (still the versionCode 10 build).
  - **Four screens instead of one long page.** Home carries only
    the pulse, the state, the one primary action and — while the
    node runs — the connected peer count and this session's uptime.
    Node details, the track record and settings each have their own
    screen, reached from home; switching is instant, and each
    screen keeps its scroll position.
  - **The pulse means one thing.** A ripple is drawn only while
    the node is synced, once per poll that first observes a higher
    best block. The service records the observation moment itself,
    so the home screen's "last observed" line and the ripple agree,
    and neither can be fabricated by a screen rebuild.
  - **Failures are classified by the service, not guessed from an
    exit code.** A start whose two launch attempts both failed, a
    node that stopped unexpectedly, and a missing node binary are
    shown as failures (with a "Failure details" path into the
    diagnostics); a stop caused by the system's time limit is shown
    as a stop with its reason, and a user stop is a plain stop —
    even when the destroyed process exits non-zero.
  - **Node details** show the best / highest block heights, the
    sync gap (shown only when it is meaningful, and a gap of zero
    is never dressed up as "synced"), and expandable Diagnostics —
    exit code, service notes, the per-attempt start records and log
    sharing. While the node is stopped,
    the details screen shows the cumulative track record instead of
    dressing up the previous session's values as current.
  - **Settings** distinguish saved values from the values the
    running node actually launched with: a telemetry or display-name
    change that has not taken effect yet is marked per item, and
    home shows a "review pending settings" link only while such a
    difference really exists. The display name is now edited in a
    dialog that validates the sanitized name against the 64
    code-point limit — over the limit is refused, never silently
    truncated — with a live counter and a preview of the name as it
    will be saved. The language picker keeps applying immediately.
  - **Quantities are grammatical in all 9 languages.** Durations,
    remaining-block estimates and "last observed" ages pick their
    plural form per language (including the few/many forms of
    Russian and the full category set of Arabic) from the actual
    long count; displayed numbers use 3-digit grouping. The string
    table grows from 49 to 151 keys; docs/I18N.md is regenerated.
  - The version line now lives at the bottom of Settings; the RPC
    note moved to node details and the log location into
    Diagnostics, replacing the old combined footer line.
  - The palette now follows the Quantus brand colours from
    quantus.com: void #0E0E0E background, #181818 surfaces, flare
    #FF6B35 accents, #E8E6E0 text, and the synced state in the
    brand's green #4ADE80. Layout and behaviour are unchanged;
    only the colours moved.
  - The placeholder Quantum watch section was removed from node
    details before release (it only ever showed a "not available
    yet" note); its three strings left the table with it.

  - **Restored strings.** The non-English UI strings that had
    been corrupted into "?" characters in the shipped builds
    are restored — recovered verbatim from the audited
    versionCode 24 string table, with a regression check added
    so the corruption cannot silently return.
  - **Pre-release audit.** A full-section audit of this build
    before release found no critical, high or medium findings;
    its seven low findings were all addressed before release.
  - **Release record.** APK: 55,733,030 bytes, SHA-256
    `ec13212cf4e4a6e9597b7779591966e094eff30fc12454030a08535442e218ee`.
    Installed over v0.1.5 on the reference device, keeping
    settings and synced data — verified in place.

## versionCode 25 / v0.1.5 — 2026-11-06
- **Maintenance release: all eleven verified findings of the
  three external audits of v0.1.4 (the first audit, the
  re-audit and an additional audit round) — five mediums
  (M1, M2, M3, F-02, F-03) and six lows (F-01/L2, L3, L4,
  M4, M5, F-05). Nothing is deferred.**
  No changes to the node launch arguments, the database, the
  permissions, or the bundled node binary (still the versionCode
  10 build). Two audits' findings overlapped (the re-audit's F-01
  and F-02 are the first round's L2 and L1); the list below uses
  the consolidated numbering of the verification reports.
  - **Cleartext loopback RPC is permitted by an explicit, narrow
    configuration (medium, M1).** The 2026-11-04 dismissal of this
    finding rested on a premise primary sources do not support:
    for targetSdk 28+ the platform's default network security
    policy denies cleartext everywhere, with no loopback exception
    before the implicit localhost configuration of API 37. The app
    now ships a network security configuration whose base denies
    cleartext and whose only permitted destinations are 127.0.0.1
    and localhost — the loopback polling the UI depends on is
    permitted by policy across the whole supported range, and
    nothing else is. SECURITY.md's earlier wording is corrected.
  - **Orphan recovery is licensed by provenance, not by a
    consumable first-session flag (medium, M2).** The pid record
    now also stamps the incarnation id of the app process that
    spawned the child. A leftover stamped by a previous, dead
    incarnation (or unstamped — a pre-versionCode-25 record) can
    be recovered by any session of this process, so a recovery
    that stands down (a stop landing inside the leftover wait)
    no longer consumes the right forever. A leftover stamped by
    THIS process is never orphan-killed, the versionCode 24
    last-moment revalidation (generation still current, record
    unchanged) is kept, and at most one recovery EPISODE
    completes per process (an episode may recover several
    orphans attested by the same evidence — see the re-audit
    residuals below).
  - **An interrupted spawn leaves a findable trace (medium,
    M3).** launch() stamps a spawn-intent marker (its incarnation
    id) immediately before ProcessBuilder.start() and removes it
    once the child's pid is on record; if the record write fails,
    the marker stays. A later incarnation that finds a foreign
    marker scans /proc for processes running this app's node
    executable — not limited to its own children — strictly
    verifies each candidate's full executable path, and treats a
    found process through the same wait-and-recovery gate as a
    recorded leftover. A foreign marker whose scan finds nothing
    is stale and cleared.
  - **"Sync complete" now requires evidence (medium, F-02; the
    first round's L1, re-rated on verification).** A session is
    judged SYNCED only after at least one system_syncState
    response in that session carried a valid block height. Before
    that evidence exists the status stays SYNCING — on the first
    poll (the original L1 shape) and for as long as a partial
    failure (health answering, syncState failing) persists,
    instead of re-deriving SYNCED from the initial retained
    values on every poll indefinitely.
  - **A stop now escalates (medium, F-03).** The user-stop path
    still delivers destroy() immediately after the teardown
    settles, but a daemon thread now waits (bounded) for the exit
    and escalates to destroyForcibly() for a child that ignores
    it — previously one destroy() with no wait was all any stop
    path had, and an unresponsive child wedged every later start
    in the process. The same escalation backs onDestroy and the
    FGS-timeout path (their destroy stays immediate; the wait is
    the thread's), and the not-accepted teardown escalates before
    deciding whether the child's pid record may be cleared.
  - **The log drain bounds a line while reading it (low,
    F-01/L2).** The drain reads in chunks and accumulates a line
    only up to a byte cap (one byte under the log sink's per-line
    cap); the rest of an over-long line is consumed and discarded
    up to its newline. A newline-free input of any length —
    the auditor demonstrated an out-of-memory with 64 MiB against
    the old readLine() drain — now costs a bounded buffer; the
    sinks' own caps still apply to the delivered prefix.
  - **An RPC call has an overall deadline, and a stop aborts the
    in-flight call (low, L3).** One monotonic 5 s deadline covers
    the whole call (the per-read timeout never bounded a trickling
    response), and the teardowns disconnect the in-flight
    connection so a blocked poll ends with its session instead of
    outliving it.
  - **The destroyed-child wait uses the monotonic clock (low,
    L4).** waitForExitBounded was the one remaining wall-clock
    deadline in the service; a clock setback could stretch its
    5 s bound by the full setback.
  - **A cancelled start no longer spawns (low, M4).** launch()
    re-checks, after the leftover wait and immediately before the
    spawn, that its session is still started and current; a stop
    delivered during the wait now results in no spawn at all,
    instead of a spawn with stale settings that the acceptance
    check then had to destroy.
  - **Parallel manual builds cannot cross-contaminate (low,
    M5).** build-manual.sh gives every run a private work
    directory (signing mode + pid), so the file a run moves to
    the final path is always the file that same run signed and
    verified; the previous-APK set-aside on failure (P-5/F10) is
    unchanged.
  - **Uptime accrues from the spawn, not the session (low,
    F-05).** The accrual anchor is set when the first spawned
    process is accepted, to the spawn timestamp — the leftover
    wait (up to 5 s) no longer counts — and the worker closes
    the accrual at the moment it observes the exit, so a session
    refused or failed before any spawn accrues nothing and the
    post-exit bookkeeping no longer inflates the total.
  - **A further re-audit of this build (run against the
    repository at the versionCode 25 commit) confirmed seven of
    the eleven closed, returned four as partially closed, and
    added four new observations — eight residuals in total, all
    low severity, all verified against the code and all fixed
    in this same build before release. Nothing is deferred.**
    - F-02 residual: org.json's optLong also COERCED malformed
      heights (-0.5 became a valid 0, 5.9 became 5). Heights are
      now adopted only when the raw JSON value is a finite,
      non-negative, integral number; anything else is "not
      said", exactly like an absent field.
    - L3 residual: the 5 s deadline did not cover the wait for
      response headers (a header-drip could stall a poll — and
      with it the poll loop's periodic uptime save — past every
      bound). A per-call watchdog now disconnects the call's own
      connection at the deadline plus a small grace, whatever
      phase the call is in.
    - M4 residual: a second started/generation re-check now
      stands between the spawn-marker write and the spawn
      itself, so a stop landing during the marker write no
      longer spawns; the acceptance refusal remains the backstop
      for a stop landing inside ProcessBuilder.start().
    - M5 residual: build-manual.sh serializes the final-path
      sections (the previous-APK set-aside and the publish) per
      signing mode with flock, so parallel runs can no longer
      pair one run's APK with another's .idsig; the failure
      behaviour (no APK at the final path, P-5/F10) is
      unchanged.
    - N1: the in-flight RPC reference is an atomic, generation-
      tagged slot: a stale poller can neither overwrite nor
      clear a newer session's registration, and a teardown
      disconnects only a connection of its own or an older
      generation.
    - N2: the orphan gate's conditions are re-verified once
      more immediately before the kill, after the last file I/O
      — a teardown completing in that gap stands the kill down.
    - N3: the log drain's truncation flag travels with each
      line again (the drain cap sits one byte under the sinks'
      caps, which had made their "...[line truncated]" marker
      unreachable for drain-cut lines); the attempt capture's
      marker still appears exactly once.
    - N4: a marker-path recovery no longer settles the spawn
      marker or the completion flag after the first orphan
      while a re-scan still finds more — the episode recovers
      every orphan attested by the same evidence, and only a
      scan that finds none left settles either. (This also
      retires the last "one recovery per start" wording, which
      the episode loop superseded.)
  - All nine FOOTER_INFO strings read v0.1.5, and the generated
    table in docs/I18N.md is regenerated to match (49 keys × 9
    languages, all cells compared).

  - **Release record.** Tag target: main commit c27959ec.
    APK: 55,700,262 bytes, SHA-256
    `22ab1fe1faa9e07598a3b13f24dd7a299c8bef403af655239a7fe25029e9ec2e`,
    confirmed against the digest shown on the release page.
    Installed over v0.1.4 on the reference device — verified
    in place; the overwrite install preserved the existing
    user data (1.21 GB).

## versionCode 24 / v0.1.4 — 2026-11-05
- **Maintenance release: all five findings of the full-section
  audit of v0.1.3 (three new lows — A-6, B23-B1, C23-1 — and two
  carried over from the audit before it, HYG-1 and the DOC-5
  remainder). Nothing is deferred this round.** No changes to
  the node launch arguments, the database, the permissions, or
  the bundled node binary (still the versionCode 10 build).
  - **The orphan-recovery gate is wired to the generation
    (low, A-6).** The versionCode 23 gate judged from values
    captured or read earlier — above all the per-instance
    "first session in this process" flag. A worker of a
    DESTROYED instance, stalled before the leftover path while
    a new instance established its own session and spawned its
    node, could then read a pid file naming the NEW session's
    living node, pass every captured check, and terminate a
    process that had a living owner. launch() now hands its
    generation to the leftover wait, and immediately before
    any termination the gate revalidates that (a) the asking
    worker's generation is still the current generation — a
    torn-down session's never is — and (b) the pid record
    still names the pid the wait began on. Either failure
    stands the recovery down to the plain refusal that
    predates it; every other gate condition (first session,
    the bounded wait already served, the strict executable
    identity, never this process itself) is unchanged, and
    the genuine orphan of a dead app process is recovered
    exactly as before. Stated plainly: every pid-file write
    and clear serializes on PID_LOCK, which the leftover path
    holds throughout, so (b) is an invariant re-assertion at
    the kill moment — (a) is what closes the audited sequence.
    Desk-traced through the audit's cross-instance order and
    exercised as a predicate on the JVM (six cases).
  - **RPC missing-field handling is now symmetric (low,
    B23-B1).** "isSyncing" was read with a flat false default:
    a health response that simply did not say could judge a
    node SYNCED at head. It has a retained value now, stated
    only by an actual JSON boolean and applied in the poll's
    generation-checked batch — the "absent means not said"
    principle F8 and B-1 applied to highestBlock and peers.
    "peers" is likewise adopted only when the value actually
    is a JSON number; a non-numeric value used to fall through
    optInt's coercion and could overwrite the retained count
    with a manufactured 0. Behaviour against a real node's
    responses is unchanged. Verified at expression level over
    a five-poll sequence (org.json cannot run on the JVM
    here), including the poll where both fields are absent.
  - **The string table in the docs is regenerated, and the
    version check is mechanical (low, C23-1).** The
    footer_info row of docs/I18N.md still showed v0.1.1 in
    all nine languages while the app itself correctly showed
    v0.1.3 — the table had not been regenerated since before
    versionCode 22. The table is now regenerated from
    StringsTable (all 441 language cells match the source),
    and the release checklist in docs/RELEASE.md requires
    exactly that — the current versionName present in
    FOOTER_INFO in all nine languages, and a zero-diff
    regeneration of the table — instead of grepping for the
    previous version, which cannot in principle catch a
    leftover that is two or more versions old. This bump was
    itself run through the strengthened check.
  - **The Gradle jniLibs staging directory is git-ignored
    (carried over, HYG-1).** The staging copies of the node
    binary under app/src/main/jniLibs/ (the jniLibs source
    set build.gradle names) were covered by no ignore rule,
    so a careless `git add -A` could have committed ~53 MB of
    build input. `src/main/jniLibs/` is now in app/.gitignore
    beside `native/` (verified with git check-ignore). Noted
    as fixed here for the record: versionCode 23's entry
    named its two deferrals, and this item was neither fixed
    nor named there — the audit counted it as carried over.
  - **The app README's settings section matches the code
    (carried over, DOC-5 remainder).** It said "two user
    settings"; NodeSettings has held four for some time.
    The section now counts all four — telemetry opt-in,
    display name, UI language, and the settings lock, which
    the section did not mention at all and now describes
    (a mis-tap guard that holds no credential and never
    locks node start/stop).
  - All nine FOOTER_INFO strings read v0.1.4.
  - **Verification.** JVM, against the real compiled classes:
    951 checks — the translation tables (49 keys × 9
    languages, placeholder parity against the Japanese
    source, every footer v0.1.4), the regenerated docs table
    compared cell-by-cell (490 comparisons including keys,
    0 mismatches; before the regeneration exactly the 9
    footer cells differed), the sanitizing suite (the B-5
    strips, the kept ZWNJ/ZWJ, the F11 regressions), the
    B23-B1 poll expressions over five polls, and the A-6
    gate predicate over six cases. API surface re-scanned
    against the official API database (api-versions.xml,
    superclass/interface walk): no reference resolves above
    API 31; the only unresolved names are the two
    invokedynamic bootstrap kinds present since versionCode
    21, and a sensitivity control (a dummy class calling
    InputStream.readAllBytes / Process.pid) is flagged by
    the scan as expected. Byte-level comparison with the
    v0.1.3 APK: same 33 entries; only classes.dex, the
    version in AndroidManifest.xml, and the signature files
    differ — the node binary, the CA bundle and all
    resources are byte-identical. apksigner verifies (v3
    scheme only, the same release certificate as v0.1.3);
    the permissions are the usual four. Not verified on a
    device or an emulator; a full Gradle build remains not
    runnable in the maintainer's build VM.

  - **Release record.** Published 2026-11-05 at 23:59 JST.
    Tag target: main commit 67e39b4c. APK: 55,700,181 bytes,
    SHA-256
    `f662112bc213b0ae5f9be724b5421badf39730652381ca0ae3dc22e8b1ec74b2`,
    confirmed against the digest shown on the release page.
    Installed over v0.1.3 on the reference device, keeping
    settings and synced data — verified in place: the node
    resumed at the chain tip, a stop → immediate start worked
    cleanly, and the in-app footer correctly showed v0.1.4.

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

  - **Release record.** Installed over v0.1.2 on the reference
    device, keeping settings and synced data — verified in
    place: the node resumed at the chain tip, a stop →
    immediate start worked cleanly, and the in-app footer
    correctly showed v0.1.3.

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

  - **Release record.** Installed over v0.1.1 on the reference
    device, keeping settings and synced data — verified in
    place: the node resumed at the chain tip without
    re-syncing, and a stop → immediate start right after
    launch (the audited start/cancel race) worked cleanly.

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

  - **Release record.** Installed over v0.1.0 on the reference
    device, keeping settings and synced data — verified in
    place.

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

  - **Release record.** The published build repeated a full
    sync from a fresh install on 2026-11-05, reaching the
    chain tip (#349,518), and the node was confirmed on the
    public telemetry list (opt-in) the same day.

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
