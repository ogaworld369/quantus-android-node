package app.quantusnode.node.depin;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import app.quantusnode.node.depin.core.Hex;
import app.quantusnode.node.depin.core.Json;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Re-launch bookkeeping, kept by the MAIN process (design
 * section 3.1). This is deliberately NOT the observer process's
 * self-report: the supervisor counts launches independently, in
 * its own directory, from evidence the observer cannot rewrite
 * after the fact — so an observer that dies instantly, every
 * time, still accumulates a record here.
 *
 * Internal.24 replaced the permanent latch (20 launches of any
 * kind inside the window, then no launch ever again until a
 * manual reset — whose settings toggle itself could not land
 * while suppressed; see DepinStatusMerge) with a FAILURE-counted
 * backoff. The durable state is still just the launch window;
 * everything else is derived from it at evaluation time:
 *
 *   - an entry is CLEAN when the observer's clean-&lt;id&gt;
 *     marker is seen (written only on an orderly stop), or when
 *     the service is observed alive at least CLEAN_RUN_MS after
 *     the entry was issued;
 *   - an entry is SEEN ALIVE when any evaluation ran with the
 *     service bound (a run that demonstrably started and bound
 *     is not a launch failure, however it later ends — an OS
 *     kill hours into a healthy run is not a crash loop);
 *   - an entry is a FAILURE when it is neither clean nor seen
 *     alive and it has had its chance: it was superseded by a
 *     later launch, or FAIL_GRACE_MS passed since it was issued
 *     without the service ever being observed.
 *
 * The count of consecutive failures is the run of failures at
 * the END of the window; the backoff schedule over it lives in
 * DepinReconcilePolicy (1 min doubling to a 60 min cap). Entries
 * expiring out of the rolling WINDOW_MS decay the count, so the
 * backoff always lifts on its own — there is no latched state.
 * manualReenable (the user's explicit OFF→ON) remains as an
 * immediate reset that clears the window entirely.
 *
 * How a launch is counted: tryLaunch writes a launch record
 * (launch-&lt;id&gt;.json, with its issue and expiry wall times)
 * that the observer must claim in its own process; the window
 * file (launch-window.json) carries one entry per launch. A
 * launch is recorded BEFORE the start is attempted, so a start
 * that throws is still counted — it failed.
 *
 * Boot approximation, stated plainly: elapsed time is measured
 * only between durable stamps taken within the SAME boot. Each
 * entry carries the boot token and elapsed-realtime stamp of
 * its last evaluation; on the next evaluation, same-boot entries
 * accrue the difference of the two elapsed stamps, and entries
 * stamped under a different boot are simply re-stamped — the
 * unmeasured interval across a reboot is never counted, in
 * either direction. The boot itself is detected approximately:
 * a dedicated preferences file remembers the last elapsed value
 * it saw, and an elapsed clock that goes backwards means the
 * device rebooted since (a fresh random token is then issued).
 * This can over- or under-count wall time around a reboot; it
 * cannot resurrect a pruned launch, and the failure derivation
 * depends on recorded issue times and observed liveness, never
 * on precise ages.
 */
public final class DepinSupervisor {

    /** Rolling window over which launches are counted. */
    public static final long WINDOW_MS = 24L * 60 * 60 * 1000;
    /** A launch that was never observed alive is judged a
     *  failure once it is superseded by a later launch, or
     *  once this long has passed since it was issued — long
     *  enough for a slow device to start the service, bind,
     *  and be observed by an evaluation. */
    public static final long FAIL_GRACE_MS = 2L * 60 * 1000;
    /** A launch record is claimable for this long after issue. */
    public static final long RECORD_TTL_MS = 10L * 60 * 1000;
    /** A service still alive this long after a launch issued
     *  counts that launch as a clean run. */
    public static final long CLEAN_RUN_MS = 6L * 60 * 60 * 1000;

    private static final String PREFS = "depin_supervisor";
    private static final String KEY_BOOT_TOKEN = "boot_token";
    private static final String KEY_LAST_ELAPSED = "last_elapsed";
    private static final String WINDOW_FILE = "launch-window.json";

    /** One counted launch. */
    private static final class Entry {
        String id;
        long ageMs;
        String stampBoot;
        long stampElapsed;
        boolean clean;
        /** Wall time the launch was issued (0 in windows
         *  written before internal.24; the derivation then
         *  falls back to the launch record's own stamp). */
        long issuedWallMs;
        /** The service was observed bound at least once while
         *  this was the latest entry: the launch succeeded,
         *  whatever happened to the run afterwards. */
        boolean seenAlive;
    }

    /** The derived restart state the facade reconciles and
     *  displays against: how many launches in a row failed,
     *  when the last one was issued, and when the backoff
     *  allows the next one. */
    public static final class RestartState {
        public final int consecutiveFailures;
        public final long lastLaunchWallMs;
        public final long nextLaunchAllowedWallMs;

        RestartState(int consecutiveFailures, long lastLaunchWallMs,
                     long nextLaunchAllowedWallMs) {
            this.consecutiveFailures = consecutiveFailures;
            this.lastLaunchWallMs = lastLaunchWallMs;
            this.nextLaunchAllowedWallMs = nextLaunchAllowedWallMs;
        }
    }

    private final Context appContext;
    private final SecureRandom random = new SecureRandom();
    private final List<Entry> entries = new ArrayList<>();
    private boolean loaded = false;
    /** The boot token of the most recent evaluation. */
    private String currentBootToken = null;

    public DepinSupervisor(Context context) {
        this.appContext = context.getApplicationContext();
        loadWindow();
    }

    // ---------------- public surface (all synchronized) ----------------

    /**
     * Evaluate the window and, unless the failure backoff is
     * still running, issue one launch: a fresh random id
     * (SecureRandom, 32 hex chars) and its durable launch
     * record, fsynced before the id is handed out — the
     * observer's claim check reads that record, so the record
     * must exist on disk before any intent carrying the id can
     * be sent. The launch is counted BEFORE the caller attempts
     * the start: a start that throws has still consumed a
     * launch, and if the service then never appears, the entry
     * becomes a failure once its grace passes.
     *
     * @param serviceAlive whether the observer is currently bound
     *        and alive (feeds the seen-alive / clean-run rules
     *        for the latest launch)
     * @return the launch id, or null when the backoff has not
     *         expired yet, or the launch could not be recorded
     */
    public synchronized String tryLaunch(boolean serviceAlive) {
        evaluate(serviceAlive);
        long nowWall = System.currentTimeMillis();
        int failures = deriveConsecutiveFailures(nowWall);
        long nextAllowed = DepinReconcilePolicy.nextLaunchAllowedMs(
                lastLaunchWallMs(), failures);
        if (nextAllowed > nowWall) {
            return null; // backoff still running
        }
        String id = randomHex32();
        if (!writeLaunchRecord(id, nowWall, nowWall + RECORD_TTL_MS)) {
            return null; // an unrecorded launch is never issued
        }
        Entry e = new Entry();
        e.id = id;
        e.ageMs = 0;
        e.stampBoot = currentBootToken;
        e.stampElapsed = SystemClock.elapsedRealtime();
        e.clean = false;
        e.issuedWallMs = nowWall;
        e.seenAlive = false;
        entries.add(e);
        persistWindow();
        return id;
    }

    /**
     * The user's explicit re-enable: an immediate reset of the
     * restart backoff. The window is cleared entirely — the
     * user's decision supersedes the counted history — and
     * persisted, so the reset itself survives a restart. (Also
     * the migration path out of a pre-internal.24 latched
     * window: the latch flag is no longer read at all.)
     */
    public synchronized void manualReenable() {
        loadWindow();
        entries.clear();
        persistWindow();
    }

    /** Derive the current restart state from the window:
     *  consecutive failed launches, the last launch's issue
     *  time, and the wall time the backoff next allows a
     *  launch. Evaluates first, so clean markers, expiries and
     *  seen-alive observations are all accounted. */
    public synchronized RestartState restartState(boolean serviceAlive) {
        evaluate(serviceAlive);
        long nowWall = System.currentTimeMillis();
        int failures = deriveConsecutiveFailures(nowWall);
        long last = lastLaunchWallMs();
        return new RestartState(failures, last,
                DepinReconcilePolicy.nextLaunchAllowedMs(last, failures));
    }

    /** Record that the observer service is bound and alive:
     *  the latest launch demonstrably succeeded. Called by the
     *  facade when a bind connects, so a launch is marked seen
     *  alive even between status refreshes. */
    public synchronized void serviceAliveObserved() {
        evaluate(true);
    }

    /** Whether a restart is currently backed off (the
     *  successor of the pre-internal.24 latch query): failures
     *  exist and their backoff has not expired yet. Never a
     *  permanent state — the window decays and the backoff is
     *  finite by construction. */
    public synchronized boolean isSuppressed() {
        RestartState rs = restartState(false);
        return rs.consecutiveFailures > 0
                && rs.nextLaunchAllowedWallMs
                        > System.currentTimeMillis();
    }

    // ---------------- evaluation ----------------

    /**
     * Bring the window up to date (callers hold the class lock):
     * detect the current boot, accrue same-boot elapsed time
     * into each entry's age, notice clean markers (consuming
     * them), apply the long-clean-run rule to the latest entry,
     * and drop entries older than the window. Persists when
     * anything changed, because the stamps ARE the durable
     * record the next evaluation — possibly in a new process —
     * measures from.
     */
    private void evaluate(boolean serviceAlive) {
        loadWindow();
        File dir = DepinFiles.supervisorDir(appContext);

        SharedPreferences prefs =
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long nowElapsed = SystemClock.elapsedRealtime();
        String bootToken;
        try {
            bootToken = prefs.getString(KEY_BOOT_TOKEN, null);
        } catch (ClassCastException e) {
            bootToken = null;
        }
        long lastElapsed = -1;
        try {
            lastElapsed = prefs.getLong(KEY_LAST_ELAPSED, -1L);
        } catch (ClassCastException e) {
            lastElapsed = -1;
        }
        if (bootToken == null || lastElapsed < 0 || nowElapsed < lastElapsed) {
            // First run ever, damaged prefs, or an elapsed clock
            // that went backwards: a new boot, a new token.
            bootToken = randomHex32();
        }
        currentBootToken = bootToken;
        // last_elapsed is updated on EVERY evaluation: it is the
        // reference the next evaluation's reboot test compares
        // against. commit(), because the value must be durable
        // before this process can die, not merely queued.
        prefs.edit()
                .putString(KEY_BOOT_TOKEN, bootToken)
                .putLong(KEY_LAST_ELAPSED, nowElapsed)
                .commit();

        boolean dirty = false;
        Entry last = entries.isEmpty()
                ? null : entries.get(entries.size() - 1);
        boolean lastSameBoot = last != null && bootToken.equals(last.stampBoot);

        Iterator<Entry> it = entries.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (bootToken.equals(e.stampBoot)) {
                long delta = nowElapsed - e.stampElapsed;
                if (delta > 0) {
                    e.ageMs += delta;
                    dirty = true;
                }
                e.stampElapsed = nowElapsed;
            } else {
                // A different boot: re-stamp only. The interval
                // across the reboot was never measured and is
                // never counted.
                e.stampBoot = bootToken;
                e.stampElapsed = nowElapsed;
                dirty = true;
            }
            File cleanMarker = new File(dir, "clean-" + e.id);
            if (cleanMarker.exists()) {
                if (!e.clean) {
                    e.clean = true;
                    dirty = true;
                }
                // Consumed either way: the marker's information
                // now lives in the window, and the file must not
                // be re-read as fresh evidence forever.
                //noinspection ResultOfMethodCallIgnored
                cleanMarker.delete();
            }
            if (e.ageMs > WINDOW_MS) {
                it.remove();
                dirty = true;
            }
        }

        if (serviceAlive && last != null && entries.contains(last)
                && !last.seenAlive) {
            // The latest launch demonstrably produced a live,
            // bindable service. It is not a failure however the
            // run later ends (an OS kill of a healthy run is
            // not a launch failure), so this is recorded
            // independently of the clean-run rule below.
            last.seenAlive = true;
            dirty = true;
        }

        if (serviceAlive && last != null && !last.clean && lastSameBoot
                && entries.contains(last)) {
            // Observed alive long after issue: the run is clean
            // even though no orderly stop ever wrote a marker.
            // "Long" is measured in real time since issue, read
            // from the launch record's issue stamp; the accrued
            // age (same-boot measured intervals only) is the
            // fallback when the record is gone.
            long issuedWall = readIssuedWallMs(dir, last.id);
            long sinceIssue = issuedWall > 0
                    ? System.currentTimeMillis() - issuedWall
                    : last.ageMs;
            if (sinceIssue >= CLEAN_RUN_MS) {
                last.clean = true;
                dirty = true;
            }
        }

        // Residual F-13: launch records (and their claim
        // markers) were never deleted, so the supervisor
        // directory grew by two small files per launch,
        // forever. Housekeeping only — see the sweep.
        sweepLaunchRecords(dir, System.currentTimeMillis());

        if (dirty) {
            persistWindow();
        }
    }

    /** Delete launch records no window entry claims any
     *  more, once their own TTL has passed: an expired
     *  record can neither be claimed (the observer's claim
     *  check enforces the same expiry) nor consulted (the
     *  issue-time fallback reads records of window entries
     *  only). Unexpired orphans stay — a launch in flight
     *  across a window clear (manualReenable) must still
     *  be claimable. An unreadable record is swept only
     *  when its file is older than the TTL, so a record
     *  being written right now is never raced. */
    private void sweepLaunchRecords(File dir, long nowWall) {
        try {
            File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            java.util.Set<String> live = new java.util.HashSet<>();
            for (Entry e : entries) {
                live.add(e.id);
            }
            for (File f : files) {
                String name = f.getName();
                // I-03: the supervisor's own window file shares
                // the "launch-" prefix and must never be swept.
                if (name.equals(WINDOW_FILE)
                        || name.startsWith(WINDOW_FILE + ".")) {
                    continue;
                }
                // I-03: orphan markers of launches the window
                // no longer holds.
                if (name.startsWith("launch-")
                        && name.endsWith(".claimed")) {
                    String cid = name.substring(
                            "launch-".length(),
                            name.length() - ".claimed".length());
                    if (!live.contains(cid)
                            && !new File(dir, "launch-" + cid
                                    + ".json").exists()
                            && nowWall - f.lastModified()
                                    > RECORD_TTL_MS) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                    continue;
                }
                if (name.startsWith("clean-")) {
                    String cid = name.substring("clean-".length());
                    if (!live.contains(cid)
                            && nowWall - f.lastModified()
                                    > RECORD_TTL_MS) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                    continue;
                }
                if (!name.startsWith("launch-")
                        || !name.endsWith(".json")) {
                    continue;
                }
                String id = name.substring(
                        "launch-".length(),
                        name.length() - ".json".length());
                if (live.contains(id)) {
                    continue;
                }
                long expires = -1L;
                try {
                    Map<String, Object> m =
                            Json.parseObject(readSmallFile(f));
                    expires = longOr(m.get("expiresWallMs"), -1L);
                } catch (Throwable t) {
                    expires = -1L;
                }
                boolean expired = expires >= 0
                        ? expires < nowWall
                        : nowWall - f.lastModified()
                                > RECORD_TTL_MS;
                if (expired) {
                    //noinspection ResultOfMethodCallIgnored
                    new File(dir, "launch-" + id + ".claimed")
                            .delete();
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        } catch (Throwable ignored) {
            // Housekeeping: a failed sweep leaves the files
            // for the next evaluation.
        }
    }

    /** The issue wall time of an entry: its own stamp, or —
     *  for entries written before internal.24 — the launch
     *  record's stamp (the record is not deleted on claim, so
     *  it outlives the run). 0 when neither is available. */
    private long issuedWallOf(File dir, Entry e) {
        if (e.issuedWallMs > 0) {
            return e.issuedWallMs;
        }
        long fromRecord = readIssuedWallMs(dir, e.id);
        return fromRecord > 0 ? fromRecord : 0L;
    }

    /** Issue wall time of the most recent launch, or 0. */
    private long lastLaunchWallMs() {
        if (entries.isEmpty()) {
            return 0L;
        }
        File dir = DepinFiles.supervisorDir(appContext);
        return issuedWallOf(dir, entries.get(entries.size() - 1));
    }

    /**
     * Count consecutive FAILED launches from the end of the
     * window (callers hold the class lock, after evaluate()).
     * The counting rule itself is pure and lives in
     * DepinLaunchDerivation (internal.26, finding F-02);
     * what stays here is resolving each entry's issue time —
     * the launch record's stamp is the fallback for entries
     * written before internal.24, and it is consulted for
     * the latest entry only, exactly as the derivation
     * consults it.
     */
    private int deriveConsecutiveFailures(long nowWallMs) {
        File dir = DepinFiles.supervisorDir(appContext);
        List<DepinLaunchDerivation.Launch> facts =
                new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            long issued = i == entries.size() - 1
                    ? issuedWallOf(dir, e)
                    : e.issuedWallMs;
            facts.add(new DepinLaunchDerivation.Launch(
                    e.clean, e.seenAlive, issued, e.ageMs));
        }
        return DepinLaunchDerivation.consecutiveFailures(
                facts, nowWallMs, FAIL_GRACE_MS);
    }

    // ---------------- persistence ----------------

    private File windowFile() {
        return new File(DepinFiles.supervisorDir(appContext), WINDOW_FILE);
    }

    /** Load the window file once (construction / first use). A
     *  missing file is an empty window. A damaged one is also an
     *  empty window: the file is this process's own bookkeeping
     *  in app-private storage, and wedging every future launch
     *  on an unreadable counter would punish the user for a
     *  storage failure; suppression decisions made from an empty
     *  window err toward allowing, which the per-launch records
     *  and the observer's own claim check still bound. */
    private void loadWindow() {
        if (loaded) {
            return;
        }
        loaded = true;
        File f = windowFile();
        if (!f.exists()) {
            return;
        }
        try {
            String text = readSmallFile(f);
            Map<String, Object> root = Json.parseObject(text);
            // A pre-internal.24 "latched" flag is deliberately
            // not read: suppression is derived now, never
            // stored, so an old latched window simply becomes
            // a set of (mostly old) entries again.
            Object rawEntries = root.get("entries");
            if (rawEntries instanceof List) {
                for (Object item : (List<?>) rawEntries) {
                    if (!(item instanceof Map)) {
                        continue;
                    }
                    Map<?, ?> m = (Map<?, ?>) item;
                    Entry e = new Entry();
                    e.id = stringOr(m.get("id"));
                    if (e.id == null) {
                        continue;
                    }
                    e.ageMs = longOr(m.get("ageMs"), 0L);
                    e.stampBoot = stringOr(m.get("stampBoot"));
                    e.stampElapsed = longOr(m.get("stampElapsed"), 0L);
                    // Residual F-14: the three fields the
                    // suppression judgement reads must be
                    // present and well-typed. The forgiving
                    // defaults (clean=false, seenAlive=false,
                    // issuedWallMs=0) would admit a malformed
                    // entry as an unclean, never-alive launch
                    // and bias the window toward suppression,
                    // against its err-toward-allowing
                    // doctrine; a malformed entry is skipped
                    // like one without an id.
                    if (!(m.get("clean") instanceof Boolean)
                            || !(m.get("seenAlive")
                                    instanceof Boolean)
                            || !(m.get("issuedWallMs")
                                    instanceof Number)) {
                        continue;
                    }
                    e.clean = (Boolean) m.get("clean");
                    e.issuedWallMs = ((Number) m.get(
                            "issuedWallMs")).longValue();
                    e.seenAlive = (Boolean) m.get("seenAlive");
                    entries.add(e);
                }
            }
        } catch (Throwable t) {
            entries.clear();
        }
    }

    /** Persist the window atomically: tmp file, fsync, rename,
     *  so a death mid-write leaves either the whole old window
     *  or the whole new one. Best effort beyond that — a failed
     *  persist leaves the in-memory window authoritative for
     *  this process, and the next evaluation tries again. */
    private void persistWindow() {
        List<Object> list = new ArrayList<>();
        for (Entry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id);
            m.put("ageMs", e.ageMs);
            m.put("stampBoot", e.stampBoot);
            m.put("stampElapsed", e.stampElapsed);
            m.put("clean", e.clean);
            m.put("issuedWallMs", e.issuedWallMs);
            m.put("seenAlive", e.seenAlive);
            list.add(m);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("entries", list);
        File target = windowFile();
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(Json.pretty(root).getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            }
            if (!tmp.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Throwable ignored) {
            // See the doc above: in-memory state carries on.
        }
    }

    /** Write one launch record, fsynced before the id is handed
     *  out. Direct write (not tmp+rename): the record is created
     *  exactly once under a random name, and the claiming side
     *  validates its contents, so a torn record fails the claim
     *  rather than being mistaken for a valid launch. */
    private boolean writeLaunchRecord(String id, long issuedWallMs, long expiresWallMs) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", id);
        record.put("issuedWallMs", issuedWallMs);
        record.put("expiresWallMs", expiresWallMs);
        File f = new File(DepinFiles.supervisorDir(appContext),
                "launch-" + id + ".json");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(Json.pretty(record).getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The issue wall time from a launch record, or -1 when the
     *  record is missing or unreadable (the caller then falls
     *  back to the accrued age). Read-only: the supervisor never
     *  rewrites another component's record of a launch. */
    private long readIssuedWallMs(File dir, String id) {
        try {
            File f = new File(dir, "launch-" + id + ".json");
            if (!f.exists()) {
                return -1;
            }
            Map<String, Object> m = Json.parseObject(readSmallFile(f));
            return longOr(m.get("issuedWallMs"), -1L);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String readSmallFile(File f) throws java.io.IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 1024L * 1024)];
            int off = 0;
            int n;
            while (off < buf.length
                    && (n = in.read(buf, off, buf.length - off)) > 0) {
                off += n;
            }
            return new String(buf, 0, off, StandardCharsets.UTF_8);
        }
    }

    private String randomHex32() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return Hex.encode(bytes);
    }

    private static long longOr(Object v, long fallback) {
        return (v instanceof Number) ? ((Number) v).longValue() : fallback;
    }

    private static boolean boolOr(Object v, boolean fallback) {
        return (v instanceof Boolean) ? (Boolean) v : fallback;
    }

    private static String stringOr(Object v) {
        return (v instanceof String) ? (String) v : null;
    }
}
