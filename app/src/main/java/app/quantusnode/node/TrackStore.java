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

    public static long uptimeMs(Context ctx) {
        return prefs(ctx).getLong(KEY_UPTIME_MS, 0L);
    }

    public static long blockUpdates(Context ctx) {
        return prefs(ctx).getLong(KEY_BLOCK_UPDATES, 0L);
    }

    public static int maxPeers(Context ctx) {
        return prefs(ctx).getInt(KEY_MAX_PEERS, 0);
    }

    /** Persist a new cumulative uptime value. commit() so a kill loses little. */
    public static void saveUptimeMs(Context ctx, long value) {
        prefs(ctx).edit().putLong(KEY_UPTIME_MS, value).commit();
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
        p.edit().putLong(KEY_BLOCK_UPDATES, p.getLong(KEY_BLOCK_UPDATES, 0L) + delta).apply();
    }

    public static void observePeers(Context ctx, int peers) {
        SharedPreferences p = prefs(ctx);
        if (peers > p.getInt(KEY_MAX_PEERS, 0)) {
            p.edit().putInt(KEY_MAX_PEERS, peers).apply();
        }
    }
}
