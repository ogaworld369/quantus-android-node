package app.quantusnode.node.depin;

import android.os.Bundle;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A snapshot of the observer's state, in the plain-DTO shape the
 * transports need: public fields, sensible defaults, no behaviour
 * beyond conversion. The same snapshot travels two ways — as a
 * Bundle over IPC (service process to UI process) and as a JSON
 * map in the status file (for a reader that starts while the
 * service process is not there to answer) — so both conversions
 * live here, keyed by the DepinIpc constants, and the two forms
 * can never drift apart field by field.
 *
 * Deliberately NOT immutable and NOT defensive beyond the string
 * caps: producers fill a fresh instance in field by field, and a
 * half-filled snapshot (a service that is still preparing, say)
 * is a legitimate thing to send. The defaults encode exactly
 * that: counts that have not been established yet read as -1
 * ("unknown"), never as 0 ("none"), because the UI must not
 * present "no checkpoints yet" and "journal not read yet" as
 * the same fact.
 *
 * Strings are truncated to 512 chars in the conversions: several
 * fields carry error or self-stop detail text whose length the
 * producer does not control (an exception message, a damaged
 * file's complaint), and neither a Bundle nor a status file is
 * the place for an unbounded copy of it. The full text lives in
 * the crash log; this DTO carries enough to display.
 */
public final class DepinStatus {

    /** Not enabled, not running. */
    public static final int STATE_OFF = 0;
    /** Starting: identity and journal are being prepared. */
    public static final int STATE_PREPARING = 1;
    /** Observing and recording checkpoints. */
    public static final int STATE_RECORDING = 2;
    /** Recording is held (the live view disagrees with the
     *  recorded tail; appends wait, nothing is discarded). */
    public static final int STATE_HELD = 3;
    /** The observer stopped itself; see selfStopReason. */
    public static final int STATE_SELF_STOPPED = 4;
    /** No status could be obtained (no service, no status file). */
    public static final int STATE_UNAVAILABLE = 5;

    private static final int MAX_STRING_CHARS = 512;

    public int state = STATE_OFF;
    public boolean enabled = false;
    public String deviceId = null;
    public long identityGeneration = 0;
    /** Checkpoints in the journal; -1 while not yet known. */
    public int checkpointCount = -1;
    public long lastCheckpointUnix = 0;
    /** Unix time of the journal's first checkpoint; 0 while the
     *  journal is empty or not yet read (member card "since"). */
    public long firstCheckpointUnix = 0;
    /** Distinct local calendar days holding at least one
     *  checkpoint; -1 while not yet known (member card). */
    public int recordedDays = -1;
    /** Longest run of consecutive local days each holding at
     *  least one checkpoint; -1 while not yet known (member
     *  card streak). */
    public int longestStreakDays = -1;
    /** Peer counts of the last 48 checkpoint slots (600 s
     *  each, oldest first); -1 where a slot holds no
     *  checkpoint; null while not yet known (record ring /
     *  sparkline). */
    public int[] recentSlotPeers = null;
    /** End (unix seconds) of the newest slot in
     *  recentSlotPeers; -1 while not yet known. */
    public long recentSlotEndUnix = -1;
    /** The last 24 hours of checkpoints (oldest first): times
     *  and their peer counts; null while not yet known (ghost
     *  layer). */
    public long[] recentDayTs = null;
    public int[] recentDayPeers = null;
    /** Of today's elapsed 600 s slots, how many hold at
     *  least one checkpoint (local day); -1 unknown. */
    public int todayCheckpoints = -1;
    /** Today's 600 s slots from local midnight through the
     *  end of the slot containing now — counted over the
     *  same window as todayCheckpoints, so the checkpoint
     *  count never exceeds it; -1 unknown (home "today's
     *  record" progress). */
    public int todaySlotsElapsed = -1;
    /** Best block of the latest checkpoint; -1 while unknown. */
    public long lastBest = -1;
    public boolean holdActive = false;
    public String selfStopReason = null;
    public String selfStopDetail = null;
    public String lastError = null;
    /** A start/enable command was discarded (its expected
     *  identity binding did not match the loaded identity). */
    public boolean commandDiscarded = false;
    /** The RPC attempt log is corrupt and consumption is refused. */
    public boolean attemptLogCorrupt = false;
    /** When the attempt log was last reset through the
     *  user-confirmed repair (design section 2.3; unix
     *  seconds, 0 = never). The fact of a reset is part of
     *  the surface, never silent. */
    public long attemptLogResetUnix = 0;
    /** Restart backoff is currently in effect (internal.24:
     *  derived by the facade from the supervisor's failure
     *  count and next-retry time — the permanent latch is
     *  gone); the service itself always reports false. Not
     *  part of the SPEC. */
    public boolean supervisorSuppressed = false;
    /** Consecutive FAILED launches the supervisor derived
     *  from its window (internal.24); merged by the facade —
     *  the service itself always reports 0. Not part of the
     *  SPEC. */
    public int restartFailures = 0;
    /** Wall time the next automatic restart will be tried
     *  (0 when a retry is allowed immediately); merged by
     *  the facade. Not part of the SPEC. */
    public long restartNextWallMs = 0;
    /** Wall-clock millis when this snapshot was produced. */
    public long updatedWallMs = 0;

    private static String truncate(String s) {
        if (s == null || s.length() <= MAX_STRING_CHARS) {
            return s;
        }
        return s.substring(0, MAX_STRING_CHARS);
    }

    // ---------------- Bundle form (IPC) ----------------

    public Bundle toBundle() {
        Bundle b = new Bundle();
        b.putInt(DepinIpc.KEY_STATE, state);
        b.putBoolean(DepinIpc.KEY_ENABLED, enabled);
        b.putString(DepinIpc.KEY_DEVICE_ID, truncate(deviceId));
        b.putLong(DepinIpc.KEY_GENERATION, identityGeneration);
        b.putInt(DepinIpc.KEY_CHECKPOINTS, checkpointCount);
        b.putLong(DepinIpc.KEY_LAST_CHECKPOINT_UNIX, lastCheckpointUnix);
        b.putLong(DepinIpc.KEY_FIRST_CHECKPOINT_UNIX, firstCheckpointUnix);
        b.putInt(DepinIpc.KEY_RECORDED_DAYS, recordedDays);
        b.putInt(DepinIpc.KEY_LONGEST_STREAK_DAYS, longestStreakDays);
        if (recentSlotPeers != null) {
            b.putIntArray(DepinIpc.KEY_RECENT_SLOT_PEERS,
                    recentSlotPeers.clone());
        }
        b.putLong(DepinIpc.KEY_RECENT_SLOT_END_UNIX, recentSlotEndUnix);
        if (recentDayTs != null) {
            b.putLongArray(DepinIpc.KEY_RECENT_DAY_TS,
                    recentDayTs.clone());
        }
        if (recentDayPeers != null) {
            b.putIntArray(DepinIpc.KEY_RECENT_DAY_PEERS,
                    recentDayPeers.clone());
        }
        b.putInt(DepinIpc.KEY_TODAY_CHECKPOINTS, todayCheckpoints);
        b.putInt(DepinIpc.KEY_TODAY_SLOTS_ELAPSED, todaySlotsElapsed);
        b.putLong(DepinIpc.KEY_LAST_BEST, lastBest);
        b.putBoolean(DepinIpc.KEY_HOLD, holdActive);
        b.putString(DepinIpc.KEY_SELF_STOP_REASON, truncate(selfStopReason));
        b.putString(DepinIpc.KEY_SELF_STOP_DETAIL, truncate(selfStopDetail));
        b.putString(DepinIpc.KEY_LAST_ERROR, truncate(lastError));
        b.putBoolean(DepinIpc.KEY_DISCARDED, commandDiscarded);
        b.putBoolean(DepinIpc.KEY_ATTEMPT_CORRUPT, attemptLogCorrupt);
        b.putLong(DepinIpc.KEY_ATTEMPT_RESET_UNIX, attemptLogResetUnix);
        b.putBoolean(DepinIpc.KEY_SUPPRESSED, supervisorSuppressed);
        b.putInt(DepinIpc.KEY_RESTART_FAILURES, restartFailures);
        b.putLong(DepinIpc.KEY_RESTART_NEXT_MS, restartNextWallMs);
        b.putLong(DepinIpc.KEY_UPDATED_MS, updatedWallMs);
        return b;
    }

    public static DepinStatus fromBundle(Bundle b) {
        DepinStatus s = new DepinStatus();
        if (b == null) {
            // No answer at all is "unavailable", not "off": the
            // two read very differently in the UI.
            return unavailable();
        }
        s.state = b.getInt(DepinIpc.KEY_STATE, STATE_OFF);
        s.enabled = b.getBoolean(DepinIpc.KEY_ENABLED, false);
        s.deviceId = truncate(b.getString(DepinIpc.KEY_DEVICE_ID));
        s.identityGeneration = b.getLong(DepinIpc.KEY_GENERATION, 0L);
        s.checkpointCount = b.getInt(DepinIpc.KEY_CHECKPOINTS, -1);
        s.lastCheckpointUnix =
                b.getLong(DepinIpc.KEY_LAST_CHECKPOINT_UNIX, 0L);
        s.firstCheckpointUnix =
                b.getLong(DepinIpc.KEY_FIRST_CHECKPOINT_UNIX, 0L);
        s.recordedDays = b.getInt(DepinIpc.KEY_RECORDED_DAYS, -1);
        s.longestStreakDays =
                b.getInt(DepinIpc.KEY_LONGEST_STREAK_DAYS, -1);
        int[] slotPeers = b.getIntArray(DepinIpc.KEY_RECENT_SLOT_PEERS);
        s.recentSlotPeers = slotPeers != null ? slotPeers.clone() : null;
        s.recentSlotEndUnix =
                b.getLong(DepinIpc.KEY_RECENT_SLOT_END_UNIX, -1L);
        long[] dayTs = b.getLongArray(DepinIpc.KEY_RECENT_DAY_TS);
        s.recentDayTs = dayTs != null ? dayTs.clone() : null;
        int[] dayPeers = b.getIntArray(DepinIpc.KEY_RECENT_DAY_PEERS);
        s.recentDayPeers = dayPeers != null ? dayPeers.clone() : null;
        s.todayCheckpoints =
                b.getInt(DepinIpc.KEY_TODAY_CHECKPOINTS, -1);
        s.todaySlotsElapsed =
                b.getInt(DepinIpc.KEY_TODAY_SLOTS_ELAPSED, -1);
        s.lastBest = b.getLong(DepinIpc.KEY_LAST_BEST, -1L);
        s.holdActive = b.getBoolean(DepinIpc.KEY_HOLD, false);
        s.selfStopReason =
                truncate(b.getString(DepinIpc.KEY_SELF_STOP_REASON));
        s.selfStopDetail =
                truncate(b.getString(DepinIpc.KEY_SELF_STOP_DETAIL));
        s.lastError = truncate(b.getString(DepinIpc.KEY_LAST_ERROR));
        s.commandDiscarded = b.getBoolean(DepinIpc.KEY_DISCARDED, false);
        s.attemptLogCorrupt =
                b.getBoolean(DepinIpc.KEY_ATTEMPT_CORRUPT, false);
        s.attemptLogResetUnix =
                b.getLong(DepinIpc.KEY_ATTEMPT_RESET_UNIX, 0L);
        s.supervisorSuppressed =
                b.getBoolean(DepinIpc.KEY_SUPPRESSED, false);
        s.restartFailures = b.getInt(DepinIpc.KEY_RESTART_FAILURES, 0);
        s.restartNextWallMs = b.getLong(DepinIpc.KEY_RESTART_NEXT_MS, 0L);
        s.updatedWallMs = b.getLong(DepinIpc.KEY_UPDATED_MS, 0L);
        return s;
    }

    // ---------------- Map form (status file) ----------------

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(DepinIpc.KEY_STATE, (long) state);
        m.put(DepinIpc.KEY_ENABLED, enabled);
        m.put(DepinIpc.KEY_DEVICE_ID, truncate(deviceId));
        m.put(DepinIpc.KEY_GENERATION, identityGeneration);
        m.put(DepinIpc.KEY_CHECKPOINTS, (long) checkpointCount);
        m.put(DepinIpc.KEY_LAST_CHECKPOINT_UNIX, lastCheckpointUnix);
        m.put(DepinIpc.KEY_FIRST_CHECKPOINT_UNIX, firstCheckpointUnix);
        m.put(DepinIpc.KEY_RECORDED_DAYS, (long) recordedDays);
        m.put(DepinIpc.KEY_LONGEST_STREAK_DAYS,
                (long) longestStreakDays);
        m.put(DepinIpc.KEY_RECENT_SLOT_PEERS,
                recentSlotPeers != null
                        ? longList(recentSlotPeers) : null);
        m.put(DepinIpc.KEY_RECENT_SLOT_END_UNIX, recentSlotEndUnix);
        m.put(DepinIpc.KEY_RECENT_DAY_TS,
                recentDayTs != null ? longList(recentDayTs) : null);
        m.put(DepinIpc.KEY_RECENT_DAY_PEERS,
                recentDayPeers != null
                        ? longList(recentDayPeers) : null);
        m.put(DepinIpc.KEY_TODAY_CHECKPOINTS,
                (long) todayCheckpoints);
        m.put(DepinIpc.KEY_TODAY_SLOTS_ELAPSED,
                (long) todaySlotsElapsed);
        m.put(DepinIpc.KEY_LAST_BEST, lastBest);
        m.put(DepinIpc.KEY_HOLD, holdActive);
        m.put(DepinIpc.KEY_SELF_STOP_REASON, truncate(selfStopReason));
        m.put(DepinIpc.KEY_SELF_STOP_DETAIL, truncate(selfStopDetail));
        m.put(DepinIpc.KEY_LAST_ERROR, truncate(lastError));
        m.put(DepinIpc.KEY_DISCARDED, commandDiscarded);
        m.put(DepinIpc.KEY_ATTEMPT_CORRUPT, attemptLogCorrupt);
        m.put(DepinIpc.KEY_ATTEMPT_RESET_UNIX, attemptLogResetUnix);
        m.put(DepinIpc.KEY_SUPPRESSED, supervisorSuppressed);
        m.put(DepinIpc.KEY_RESTART_FAILURES, restartFailures);
        m.put(DepinIpc.KEY_RESTART_NEXT_MS, restartNextWallMs);
        m.put(DepinIpc.KEY_UPDATED_MS, updatedWallMs);
        return m;
    }

    public static DepinStatus fromMap(Map<String, Object> m) {
        DepinStatus s = new DepinStatus();
        if (m == null) {
            return unavailable();
        }
        s.state = (int) numberOr(m.get(DepinIpc.KEY_STATE), STATE_OFF);
        s.enabled = boolOr(m.get(DepinIpc.KEY_ENABLED), false);
        s.deviceId = truncate(stringOr(m.get(DepinIpc.KEY_DEVICE_ID)));
        s.identityGeneration =
                numberOr(m.get(DepinIpc.KEY_GENERATION), 0L);
        s.checkpointCount =
                (int) numberOr(m.get(DepinIpc.KEY_CHECKPOINTS), -1L);
        s.lastCheckpointUnix =
                numberOr(m.get(DepinIpc.KEY_LAST_CHECKPOINT_UNIX), 0L);
        s.firstCheckpointUnix =
                numberOr(m.get(DepinIpc.KEY_FIRST_CHECKPOINT_UNIX), 0L);
        s.recordedDays =
                (int) numberOr(m.get(DepinIpc.KEY_RECORDED_DAYS), -1L);
        s.longestStreakDays = (int) numberOr(
                m.get(DepinIpc.KEY_LONGEST_STREAK_DAYS), -1L);
        s.recentSlotPeers =
                intArrayOr(m.get(DepinIpc.KEY_RECENT_SLOT_PEERS));
        s.recentSlotEndUnix = numberOr(
                m.get(DepinIpc.KEY_RECENT_SLOT_END_UNIX), -1L);
        s.recentDayTs =
                longArrayOr(m.get(DepinIpc.KEY_RECENT_DAY_TS));
        s.recentDayPeers =
                intArrayOr(m.get(DepinIpc.KEY_RECENT_DAY_PEERS));
        s.todayCheckpoints = (int) numberOr(
                m.get(DepinIpc.KEY_TODAY_CHECKPOINTS), -1L);
        s.todaySlotsElapsed = (int) numberOr(
                m.get(DepinIpc.KEY_TODAY_SLOTS_ELAPSED), -1L);
        s.lastBest = numberOr(m.get(DepinIpc.KEY_LAST_BEST), -1L);
        s.holdActive = boolOr(m.get(DepinIpc.KEY_HOLD), false);
        s.selfStopReason =
                truncate(stringOr(m.get(DepinIpc.KEY_SELF_STOP_REASON)));
        s.selfStopDetail =
                truncate(stringOr(m.get(DepinIpc.KEY_SELF_STOP_DETAIL)));
        s.lastError = truncate(stringOr(m.get(DepinIpc.KEY_LAST_ERROR)));
        s.commandDiscarded = boolOr(m.get(DepinIpc.KEY_DISCARDED), false);
        s.attemptLogCorrupt =
                boolOr(m.get(DepinIpc.KEY_ATTEMPT_CORRUPT), false);
        s.attemptLogResetUnix =
                numberOr(m.get(DepinIpc.KEY_ATTEMPT_RESET_UNIX), 0L);
        s.supervisorSuppressed =
                boolOr(m.get(DepinIpc.KEY_SUPPRESSED), false);
        s.restartFailures = (int) numberOr(
                m.get(DepinIpc.KEY_RESTART_FAILURES), 0L);
        s.restartNextWallMs = numberOr(
                m.get(DepinIpc.KEY_RESTART_NEXT_MS), 0L);
        s.updatedWallMs = numberOr(m.get(DepinIpc.KEY_UPDATED_MS), 0L);
        return s;
    }

    /** Numbers in a parsed status file arrive in the JSON
     *  reader's shapes — Long for integral values, but a file
     *  written by anything else (or a hand-edited one) may carry
     *  Integer or another Number — so any Number is accepted and
     *  anything else falls back to the default instead of
     *  throwing: a status file is a display source, and a damaged
     *  one must degrade the display, not crash its reader. */
    private static long numberOr(Object v, long fallback) {
        return (v instanceof Number) ? ((Number) v).longValue() : fallback;
    }

    private static boolean boolOr(Object v, boolean fallback) {
        return (v instanceof Boolean) ? (Boolean) v : fallback;
    }

    private static String stringOr(Object v) {
        return (v instanceof String) ? (String) v : null;
    }

    /** Arrays in a parsed status file arrive as JSON arrays —
     *  a List of Numbers. Anything else (absent key, a scalar,
     *  a damaged file) degrades to null ("not known"), never
     *  to an exception: same rule as numberOr above. */
    private static int[] intArrayOr(Object v) {
        if (!(v instanceof java.util.List)) {
            return null;
        }
        java.util.List<?> list = (java.util.List<?>) v;
        int[] out = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Object e = list.get(i);
            if (!(e instanceof Number)) {
                return null;
            }
            out[i] = ((Number) e).intValue();
        }
        return out;
    }

    private static long[] longArrayOr(Object v) {
        if (!(v instanceof java.util.List)) {
            return null;
        }
        java.util.List<?> list = (java.util.List<?>) v;
        long[] out = new long[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Object e = list.get(i);
            if (!(e instanceof Number)) {
                return null;
            }
            out[i] = ((Number) e).longValue();
        }
        return out;
    }

    private static java.util.List<Long> longList(int[] a) {
        java.util.List<Long> out =
                new java.util.ArrayList<>(a.length);
        for (int v : a) {
            out.add((long) v);
        }
        return out;
    }

    private static java.util.List<Long> longList(long[] a) {
        java.util.List<Long> out =
                new java.util.ArrayList<>(a.length);
        for (long v : a) {
            out.add(v);
        }
        return out;
    }

    // ---------------- misc ----------------

    /**
     * The state a reader may PRESENT, given what the reader
     * knows about the producing service's liveness (2026-11-10
     * device incident, internal.21): this DTO is a snapshot,
     * and a snapshot outlives its producer — the status file of
     * an observer process that was killed outright kept saying
     * RECORDING for eight hours, because nothing ever checked
     * the snapshot against the service's actual liveness.
     *
     * The rule (the same one the bundle-export gate has always
     * applied: no live service, no export): the LIVE states —
     * PREPARING, RECORDING, HELD — are presented as-is only
     * while the service is bound or a binding is outstanding.
     * Otherwise they degrade to UNAVAILABLE: the durable facts
     * the snapshot carries (checkpoint count, last checkpoint,
     * the journal aggregates) stay exactly as recorded, but
     * the state no longer claims the observer is running when
     * the reader cannot know that. The settled states (OFF,
     * SELF_STOPPED, UNAVAILABLE itself) are facts about the
     * past or about authorisation, not about liveness, and
     * pass through unchanged.
     */
    public int presentedState(boolean serviceAlive,
                              boolean bindOutstanding) {
        if (serviceAlive || bindOutstanding) {
            return state;
        }
        switch (state) {
            case STATE_PREPARING:
            case STATE_RECORDING:
            case STATE_HELD:
                return STATE_UNAVAILABLE;
            default:
                return state;
        }
    }

    /** A field-by-field copy. Scalars and strings copy by
     *  value; the aggregate arrays are cloned, so a copy never
     *  shares a mutable array with its source. */
    public DepinStatus copy() {
        DepinStatus s = new DepinStatus();
        s.state = state;
        s.enabled = enabled;
        s.deviceId = deviceId;
        s.identityGeneration = identityGeneration;
        s.checkpointCount = checkpointCount;
        s.lastCheckpointUnix = lastCheckpointUnix;
        s.firstCheckpointUnix = firstCheckpointUnix;
        s.recordedDays = recordedDays;
        s.longestStreakDays = longestStreakDays;
        s.recentSlotPeers = recentSlotPeers != null
                ? recentSlotPeers.clone() : null;
        s.recentSlotEndUnix = recentSlotEndUnix;
        s.recentDayTs = recentDayTs != null
                ? recentDayTs.clone() : null;
        s.recentDayPeers = recentDayPeers != null
                ? recentDayPeers.clone() : null;
        s.todayCheckpoints = todayCheckpoints;
        s.todaySlotsElapsed = todaySlotsElapsed;
        s.lastBest = lastBest;
        s.holdActive = holdActive;
        s.selfStopReason = selfStopReason;
        s.selfStopDetail = selfStopDetail;
        s.lastError = lastError;
        s.commandDiscarded = commandDiscarded;
        s.attemptLogCorrupt = attemptLogCorrupt;
        s.attemptLogResetUnix = attemptLogResetUnix;
        s.supervisorSuppressed = supervisorSuppressed;
        s.restartFailures = restartFailures;
        s.restartNextWallMs = restartNextWallMs;
        s.updatedWallMs = updatedWallMs;
        return s;
    }

    /** The default DTO for "no status could be obtained". */
    public static DepinStatus unavailable() {
        DepinStatus s = new DepinStatus();
        s.state = STATE_UNAVAILABLE;
        return s;
    }
}
