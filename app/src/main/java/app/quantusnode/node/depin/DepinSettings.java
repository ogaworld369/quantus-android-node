package app.quantusnode.node.depin;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The observer's own settings, in a dedicated preferences file
 * ("depin_settings", integration design section 2.2) — separate
 * from the node's settings file, so the two features can never
 * overwrite, or be mistaken for, each other's values.
 *
 * Writer discipline: the :depin process writes this file for
 * every observer state change — enable, identity binding,
 * self-stop. The user's OFF is no exception: it is recorded
 * by the main process as an intent in the facade's own
 * preferences (DepinUserIntent), and the observer lands it
 * here itself, in its own process, at the next start that
 * could resume recording (and immediately, via
 * ACTION_DISABLE, when it is alive). Internal.24 had one
 * exception — DepinModule.disable() wrote setDisabled from
 * the main process — and internal.26 removed it (audit
 * F-03): SharedPreferences keeps a per-process in-memory
 * map, so a cross-process commit whose value already
 * matches the writer's stale map writes nothing at all
 * (off → on → off with a dead observer left the disk flag
 * true), and one that does write overwrites the file with
 * the writer's whole stale map, rolling back what the
 * observer wrote since. The single-writer rule is what
 * makes plain commit()-based writes safe here without any
 * cross-process locking protocol; the facade's decisions
 * additionally follow the recorded intent (DepinStatusMerge)
 * rather than any process's possibly-stale copy of this
 * file, so presentation never depends on the landing's
 * timing.
 *
 * Writes use commit(), not apply(): an enable binding or a
 * self-stop record is a durable fact the next process start will
 * act on, and it must be on disk before the caller proceeds, not
 * merely queued for a later flush.
 *
 * The enable binding and the active binding are kept apart on
 * purpose. The ENABLE binding (device id + generation recorded
 * by setEnabled) is what the user last authorised; the ACTIVE
 * binding (bindActiveIdentity) is the identity the observer
 * actually loaded and is recording under. Comparing the two is
 * how a later start detects that the identity changed under an
 * existing authorisation instead of silently adopting the new
 * one.
 *
 * Timestamps are unix seconds, matching the journal and the
 * status DTO, so values from all three can be compared directly.
 *
 * Every read tolerates a stored value of the wrong type
 * (ClassCastException -> the default), following the established
 * settings pattern in this app: these values are only ever
 * written by this class, so a mismatch means the preferences
 * file was damaged outside the app, and the right answer is the
 * default, not a crash in a reader.
 */
public final class DepinSettings {
    private DepinSettings() {}

    private static final String PREFS = "depin_settings";

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_ENABLED_DEVICE_ID = "enabled_device_id";
    private static final String KEY_ENABLED_GENERATION = "enabled_generation";
    private static final String KEY_LAST_ENABLE_UNIX = "last_enable_unix";
    private static final String KEY_ACTIVE_DEVICE_ID = "active_device_id";
    private static final String KEY_IDENTITY_GENERATION = "identity_generation";
    private static final String KEY_SELF_STOP_REASON = "self_stop_reason";
    private static final String KEY_SELF_STOP_DETAIL = "self_stop_detail";
    private static final String KEY_SELF_STOP_UNIX = "self_stop_unix";
    private static final String KEY_ATTEMPT_LOG_RESET_UNIX =
            "attempt_log_reset_unix";
    private static final String KEY_LAST_KNOWN_CP_COUNT =
            "last_known_checkpoint_count";
    private static final String KEY_LAST_KNOWN_CP_UNIX =
            "last_known_checkpoint_unix";
    private static final String KEY_LAST_KNOWN_CP_DEVICE_ID =
            "last_known_checkpoint_device_id";

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static long nowUnix() {
        return System.currentTimeMillis() / 1000L;
    }

    private static void putStringOrRemove(
            SharedPreferences.Editor editor, String key, String value) {
        if (value == null) {
            editor.remove(key);
        } else {
            editor.putString(key, value);
        }
    }

    // ---------------- enable state ----------------

    /** Whether the observer is enabled (default off). */
    public static boolean isEnabled(Context context) {
        try {
            return prefs(context).getBoolean(KEY_ENABLED, false);
        } catch (ClassCastException e) {
            return false;
        }
    }

    /** The device id the current enable was granted to, or null. */
    public static String enabledDeviceId(Context context) {
        try {
            return prefs(context).getString(KEY_ENABLED_DEVICE_ID, null);
        } catch (ClassCastException e) {
            return null;
        }
    }

    /** The identity generation the current enable was granted
     *  under (0 when never enabled). */
    public static long enabledGeneration(Context context) {
        try {
            return prefs(context).getLong(KEY_ENABLED_GENERATION, 0L);
        } catch (ClassCastException e) {
            return 0L;
        }
    }

    /**
     * Record an enable: the flag, the identity binding it was
     * granted to, and when it happened. Any earlier self-stop
     * record is cleared — it described why a PREVIOUS run stopped,
     * and leaving it in place would make a fresh, healthy start
     * present itself as stopped.
     */
    public static void setEnabled(
            Context context, String deviceId, long generation) {
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putBoolean(KEY_ENABLED, true);
        putStringOrRemove(editor, KEY_ENABLED_DEVICE_ID, deviceId);
        editor.putLong(KEY_ENABLED_GENERATION, generation);
        editor.putLong(KEY_LAST_ENABLE_UNIX, nowUnix());
        editor.remove(KEY_SELF_STOP_REASON);
        editor.remove(KEY_SELF_STOP_DETAIL);
        editor.remove(KEY_SELF_STOP_UNIX);
        editor.commit();
    }

    /**
     * Record a user disable. Only the flag changes: the enable
     * binding stays (it is the record of what was last authorised,
     * and the UI shows it as such), and a self-stop record, if
     * any, stays too — disabling by hand does not rewrite why an
     * earlier run stopped on its own.
     */
    public static void setDisabled(Context context) {
        prefs(context).edit()
                .putBoolean(KEY_ENABLED, false)
                .commit();
    }

    /** When the observer was last enabled (unix seconds, 0 when
     *  never). */
    public static long lastEnableUnix(Context context) {
        try {
            return prefs(context).getLong(KEY_LAST_ENABLE_UNIX, 0L);
        } catch (ClassCastException e) {
            return 0L;
        }
    }

    // ---------------- active identity binding ----------------

    /** The device id the observer is actually recording under,
     *  or null before the first binding. */
    public static String activeDeviceId(Context context) {
        try {
            return prefs(context).getString(KEY_ACTIVE_DEVICE_ID, null);
        } catch (ClassCastException e) {
            return null;
        }
    }

    /** The generation of the active identity (0 when unbound). */
    public static long identityGeneration(Context context) {
        try {
            return prefs(context).getLong(KEY_IDENTITY_GENERATION, 0L);
        } catch (ClassCastException e) {
            return 0L;
        }
    }

    /** Record the identity the observer actually loaded. */
    public static void bindActiveIdentity(
            Context context, String deviceId, long generation) {
        SharedPreferences.Editor editor = prefs(context).edit();
        putStringOrRemove(editor, KEY_ACTIVE_DEVICE_ID, deviceId);
        editor.putLong(KEY_IDENTITY_GENERATION, generation);
        editor.commit();
    }

    // ---------------- self-stop record ----------------

    /**
     * Record that the observer stopped itself: the flag goes off
     * (a self-stop must survive a process restart as "off", never
     * as an enable that retries the same failure forever), and the
     * reason code, an optional human-readable detail, and the time
     * are kept for the status display. Reason codes are the
     * REASON_* constants in DepinIpc. The enable binding is NOT
     * touched: what was authorised and why it stopped are separate
     * facts.
     */
    public static void recordSelfStop(
            Context context, String reasonCode, String detail) {
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putBoolean(KEY_ENABLED, false);
        putStringOrRemove(editor, KEY_SELF_STOP_REASON, reasonCode);
        putStringOrRemove(editor, KEY_SELF_STOP_DETAIL, detail);
        editor.putLong(KEY_SELF_STOP_UNIX, nowUnix());
        editor.commit();
    }

    /**
     * Clear the self-stop record WITHOUT touching the enable
     * flag or its binding. Used when the observer binds a
     * DIFFERENT identity than the one the record describes
     * (a completed transfer displaced the recorded identity):
     * the record told why the previous identity's run stopped,
     * and presenting it as the new identity's state would be
     * false. A stop of the new identity's own run is recorded
     * fresh when it happens.
     */
    public static void clearSelfStopRecord(Context context) {
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.remove(KEY_SELF_STOP_REASON);
        editor.remove(KEY_SELF_STOP_DETAIL);
        editor.remove(KEY_SELF_STOP_UNIX);
        editor.commit();
    }

    /** The self-stop reason code, or null when no self-stop is
     *  recorded. */
    public static String selfStopReason(Context context) {
        try {
            return prefs(context).getString(KEY_SELF_STOP_REASON, null);
        } catch (ClassCastException e) {
            return null;
        }
    }

    /** The self-stop detail text, or null when none was recorded. */
    public static String selfStopDetail(Context context) {
        try {
            return prefs(context).getString(KEY_SELF_STOP_DETAIL, null);
        } catch (ClassCastException e) {
            return null;
        }
    }

    /** When the observer last stopped itself (unix seconds, 0
     *  when no self-stop is recorded). */
    public static long selfStopUnix(Context context) {
        try {
            return prefs(context).getLong(KEY_SELF_STOP_UNIX, 0L);
        } catch (ClassCastException e) {
            return 0L;
        }
    }

    // ---------------- attempt-log maintenance ----------------

    /** When the RPC attempt log was last reset by the explicit
     *  maintenance operation (unix seconds, 0 when never). The
     *  reset is a user-confirmed repair of a corrupt budget log,
     *  and the fact that it happened is itself surfaced in the
     *  status display, so it is recorded here rather than only in
     *  the log that was just replaced. */
    public static long attemptLogResetUnix(Context context) {
        try {
            return prefs(context).getLong(KEY_ATTEMPT_LOG_RESET_UNIX, 0L);
        } catch (ClassCastException e) {
            return 0L;
        }
    }

    /** Record that the attempt log was reset just now. */
    public static void recordAttemptLogReset(Context context) {
        prefs(context).edit()
                .putLong(KEY_ATTEMPT_LOG_RESET_UNIX, nowUnix())
                .commit();
    }

    // ---------------- last known journal statistics ----------------

    /** The checkpoint count a live journal last reported
     *  (residual F-16), or -1 when none ever did. A process
     *  that cannot open the journal (the writer gate is
     *  closed, e.g. during a transfer) reports THESE values
     *  in its status instead of the process defaults, so
     *  the status file it writes does not erase the user's
     *  recorded history from the display. */
    public static long lastKnownCheckpointCount(
            Context context, String deviceId) {
        if (!knownStatsBelongTo(context, deviceId)) {
            return -1L;
        }
        try {
            return prefs(context).getLong(KEY_LAST_KNOWN_CP_COUNT, -1L);
        } catch (ClassCastException e) {
            return -1L;
        }
    }

    /** I-02: the stored statistics describe ONE identity.
     *  A reader asking on behalf of any other identity (or
     *  of none) is told nothing is known, instead of being
     *  handed a displaced identity's history. */
    private static boolean knownStatsBelongTo(
            Context context, String deviceId) {
        if (deviceId == null) {
            return false;
        }
        try {
            return deviceId.equals(prefs(context).getString(
                    KEY_LAST_KNOWN_CP_DEVICE_ID, null));
        } catch (ClassCastException e) {
            return false;
        }
    }

    /** The last checkpoint time a live journal last
     *  reported (unix seconds, 0 when none ever did). */
    public static long lastKnownCheckpointUnix(
            Context context, String deviceId) {
        if (!knownStatsBelongTo(context, deviceId)) {
            return 0L;
        }
        try {
            return prefs(context).getLong(KEY_LAST_KNOWN_CP_UNIX, 0L);
        } catch (ClassCastException e) {
            return 0L;
        }
    }

    /** Remember the journal's current statistics. Called
     *  from the status build whenever a journal IS open;
     *  writes only when a value actually changed. */
    public static void recordKnownCheckpointStats(
            Context context, String deviceId,
            long count, long lastUnix) {
        if (deviceId == null) {
            return;
        }
        if (lastKnownCheckpointCount(context, deviceId) == count
                && lastKnownCheckpointUnix(context, deviceId)
                        == lastUnix) {
            return;
        }
        prefs(context).edit()
                .putString(KEY_LAST_KNOWN_CP_DEVICE_ID, deviceId)
                .putLong(KEY_LAST_KNOWN_CP_COUNT, count)
                .putLong(KEY_LAST_KNOWN_CP_UNIX, lastUnix)
                .apply();
    }

    // ---- notification texts (final check round 4, I-20) ----

    private static final String KEY_NOTIF_SET = "notif_set";
    private static final String KEY_NOTIF_TITLE = "notif_title";
    private static final String KEY_NOTIF_CHANNEL_NAME =
            "notif_channel_name";
    private static final String KEY_NOTIF_CHANNEL_DESC =
            "notif_channel_desc";
    private static final String KEY_NOTIF_STARTING = "notif_starting";
    private static final String KEY_NOTIF_OBSERVING = "notif_observing";
    private static final String KEY_NOTIF_CHECKPOINT_FORMAT =
            "notif_checkpoint_format";
    private static final String KEY_NOTIF_CANNOT_START =
            "notif_cannot_start";

    /** The notification texts last supplied by the UI, or
     *  null when none were ever recorded. */
    public static DepinIpc.NotifStrings notifStrings(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.getBoolean(KEY_NOTIF_SET, false)) {
            return null;
        }
        DepinIpc.NotifStrings n = new DepinIpc.NotifStrings();
        n.title = p.getString(KEY_NOTIF_TITLE, null);
        n.channelName = p.getString(KEY_NOTIF_CHANNEL_NAME, null);
        n.channelDesc = p.getString(KEY_NOTIF_CHANNEL_DESC, null);
        n.starting = p.getString(KEY_NOTIF_STARTING, null);
        n.observing = p.getString(KEY_NOTIF_OBSERVING, null);
        n.checkpointFormat =
                p.getString(KEY_NOTIF_CHECKPOINT_FORMAT, null);
        n.cannotStart = p.getString(KEY_NOTIF_CANNOT_START, null);
        return n;
    }

    /** Record the notification texts the UI supplied with a
     *  start (:depin process only, like every writer here).
     *  The texts ride only the user's own enable; every other
     *  start of the observer — a reconcile-issued relaunch,
     *  the system's sticky restart — arrives without them, in
     *  a new process that has nothing to fall back on but the
     *  built-in (Japanese) defaults. Recording the last
     *  supplied set here is what lets those starts keep the
     *  user's language. */
    public static void recordNotifStrings(
            Context context, DepinIpc.NotifStrings n) {
        if (n == null) {
            return;
        }
        prefs(context).edit()
                .putBoolean(KEY_NOTIF_SET, true)
                .putString(KEY_NOTIF_TITLE, n.title)
                .putString(KEY_NOTIF_CHANNEL_NAME, n.channelName)
                .putString(KEY_NOTIF_CHANNEL_DESC, n.channelDesc)
                .putString(KEY_NOTIF_STARTING, n.starting)
                .putString(KEY_NOTIF_OBSERVING, n.observing)
                .putString(KEY_NOTIF_CHECKPOINT_FORMAT,
                        n.checkpointFormat)
                .putString(KEY_NOTIF_CANNOT_START, n.cannotStart)
                .apply();
    }
}
