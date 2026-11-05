package app.quantusnode.node;

import android.content.Context;
import android.content.SharedPreferences;

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
}
