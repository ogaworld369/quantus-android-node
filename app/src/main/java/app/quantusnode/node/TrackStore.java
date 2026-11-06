package app.quantusnode.node;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * "航跡" — the on-device-only record of what this phone has contributed:
 *
 *   - cumulative uptime of the node,
 *   - number of new blocks observed (best block moving up, counted by
 *     the actual block-height delta, not by poll count),
 *   - the largest peer count ever observed.
 *
 * Stored in the app's private SharedPreferences. Never sent anywhere,
 * no monetary value, no ranking — just the phone's own log of itself.
 */
public final class TrackStore {

    private static final String PREFS = "track";
    private static final String KEY_UPTIME_MS = "uptime_ms";
    private static final String KEY_BLOCK_UPDATES = "block_updates";
    private static final String KEY_MAX_PEERS = "max_peers";
    /**
     * Session start stamps (versionCode 28): the wall-clock times
     * of accepted session starts, comma-separated epoch millis,
     * pruned to the last 7 days and at most 100 entries. Feeds only
     * the diagnostic summary's "starts in the last 24 h" line — a
     * crash-loop tell — and nothing else reads it.
     */
    private static final String KEY_START_TIMES = "start_times";
    private static final long START_TIMES_KEEP_MS = 7L * 24 * 3600 * 1000;
    private static final int START_TIMES_MAX = 100;

    private TrackStore() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // versionCode 23 (audit finding B-6): the reads tolerate a
    // stored value of the wrong type. SharedPreferences getters
    // throw ClassCastException in that case; the values here are
    // only ever written by this class, so a mismatch means the
    // preferences file was damaged outside the app (the audit's
    // stated precondition) — the right answer is the default, not
    // a crash on the service's stop path or in the poller.

    public static long uptimeMs(Context ctx) {
        try {
            return prefs(ctx).getLong(KEY_UPTIME_MS, 0L);
        } catch (ClassCastException e) {
            return 0L;
        }
    }

    public static long blockUpdates(Context ctx) {
        try {
            return prefs(ctx).getLong(KEY_BLOCK_UPDATES, 0L);
        } catch (ClassCastException e) {
            return 0L;
        }
    }

    public static int maxPeers(Context ctx) {
        try {
            return prefs(ctx).getInt(KEY_MAX_PEERS, 0);
        } catch (ClassCastException e) {
            return 0;
        }
    }

    /** Persist a new cumulative uptime value. commit() so a kill loses little. */
    public static void saveUptimeMs(Context ctx, long value) {
        SharedPreferences.Editor editor =
                prefs(ctx).edit().putLong(KEY_UPTIME_MS, value);
        if (!editor.commit()) {
            // versionCode 23 (audit finding B-6): a failed
            // synchronous write used to be silently dropped. The
            // value is already updated in memory for this process;
            // make one asynchronous attempt at the disk write
            // instead of none. If the storage itself is broken both
            // attempts fail, and the record simply stays at its
            // last persisted value — visible in the numbers, not
            // hidden by a swallowed error.
            editor.apply();
        }
    }

    /**
     * Add {@code delta} newly observed blocks. Same storage key as the
     * old +1-per-poll counter, so values recorded by earlier builds are
     * carried over unchanged — only the counting becomes accurate
     * (during the initial sync one poll can cover thousands of blocks).
     */
    public static void addBlockUpdates(Context ctx, long delta) {
        if (delta <= 0) {
            return;
        }
        SharedPreferences p = prefs(ctx);
        long current = blockUpdates(ctx);
        // Saturating add (TRK-07): with a stored value near
        // Long.MAX_VALUE a plain add would wrap negative and the
        // track record would collapse. Unreachable with real chain
        // heights, but the guard costs nothing.
        long next = (Long.MAX_VALUE - current < delta)
                ? Long.MAX_VALUE : current + delta;
        p.edit().putLong(KEY_BLOCK_UPDATES, next).apply();
    }

    public static void observePeers(Context ctx, int peers) {
        if (peers > maxPeers(ctx)) {
            prefs(ctx).edit().putInt(KEY_MAX_PEERS, peers).apply();
        }
    }

    // ------------------------------------------------------------------
    // Session start stamps (versionCode 28)
    // ------------------------------------------------------------------

    /**
     * Record one accepted session start. Called by the service at
     * session establishment (where it stamps
     * {@code sessionAcceptedElapsedMs}); recording starts only from
     * this version on — earlier starts simply do not exist in the
     * record, and the count the summary shows is the count of the
     * recorded ones, never an estimate.
     */
    public static void recordStart(Context ctx, long wallMs) {
        List<Long> times;
        try {
            times = parseStartTimes(
                    prefs(ctx).getString(KEY_START_TIMES, ""));
        } catch (ClassCastException e) {
            times = new ArrayList<>();
        }
        times.add(wallMs);
        prefs(ctx).edit().putString(KEY_START_TIMES,
                encodeStartTimes(pruneStartTimes(times, wallMs))).apply();
    }

    /** Recorded starts at or after {@code sinceMs}. */
    public static int startsSince(Context ctx, long sinceMs) {
        try {
            return countStartsSince(parseStartTimes(
                    prefs(ctx).getString(KEY_START_TIMES, "")), sinceMs);
        } catch (ClassCastException e) {
            return 0;
        }
    }

    /**
     * Whether the stamp ring currently sits at its retention cap.
     * At the cap, older stamps have been dropped, so a count over
     * any window is a lower bound; the diagnostic summary marks
     * its 24 h count with a "+" in exactly that case rather than
     * presenting the count as exact.
     */
    public static boolean startRingAtCap(Context ctx) {
        try {
            return startRingCapped(parseStartTimes(
                    prefs(ctx).getString(KEY_START_TIMES, "")));
        } catch (ClassCastException e) {
            return false;
        }
    }

    static boolean startRingCapped(List<Long> times) {
        return times != null && times.size() >= START_TIMES_MAX;
    }

    /** Parse the stored form; malformed entries are skipped, not fatal. */
    static List<Long> parseStartTimes(String stored) {
        List<Long> out = new ArrayList<>();
        if (stored == null || stored.isEmpty()) {
            return out;
        }
        for (String part : stored.split(",")) {
            try {
                out.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {
                // A damaged entry says nothing; drop it.
            }
        }
        return out;
    }

    static String encodeStartTimes(List<Long> times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(times.get(i));
        }
        return sb.toString();
    }

    /**
     * Drop entries older than the keep window (relative to
     * {@code nowMs}) and cap the list at the newest
     * {@link #START_TIMES_MAX}, preserving order.
     */
    static List<Long> pruneStartTimes(List<Long> in, long nowMs) {
        List<Long> out = new ArrayList<>();
        long floor = nowMs - START_TIMES_KEEP_MS;
        for (Long t : in) {
            if (t != null && t >= floor) {
                out.add(t);
            }
        }
        while (out.size() > START_TIMES_MAX) {
            out.remove(0);
        }
        return out;
    }

    static int countStartsSince(List<Long> times, long sinceMs) {
        int n = 0;
        for (Long t : times) {
            if (t != null && t >= sinceMs) {
                n++;
            }
        }
        return n;
    }
}
