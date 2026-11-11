package app.quantusnode.node.depin;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Pure derivations over a checkpoint journal's (time, peers)
 * pairs, for the vision UI (stage 1+2 spec section 5). The
 * observer computes these in the same single pass it already
 * makes over the journal for the member-card aggregates; the
 * results ride the existing status channel (DepinStatus /
 * DepinIpc) and are never added to proof bundles or telemetry.
 *
 * Everything here is a plain fact of the journal's own records:
 * no estimation, no interpolation, no cross-checking against
 * anything outside the journal. A slot with no checkpoint reads
 * -1 ("no record"), never 0; a peer count of 0 is a real
 * observation and stays 0.
 *
 * Android-free (java.time only), so the JVM harness can pin the
 * derivations directly.
 */
public final class DepinJournalAggregates {

    /** Checkpoint cadence fixed by the DePIN spec. */
    public static final int SLOT_SECONDS = 600;
    /** Slots in the record-ring / sparkline window (8 hours). */
    public static final int SLOT_COUNT = 48;
    /** Cap on the last-24h series (one checkpoint per 10 min). */
    public static final int RECENT_DAY_MAX = 144;
    public static final long RECENT_DAY_SECONDS = 86_400L;

    private DepinJournalAggregates() {
    }

    public static final class Result {
        /** Longest run of consecutive local days each holding at
         *  least one checkpoint; 0 for an empty journal. */
        public int longestStreakDays = 0;
        /** Peer counts of the last SLOT_COUNT slots, oldest
         *  first; -1 where the slot holds no checkpoint. */
        public int[] recentSlotPeers;
        /** End (unix seconds) of the newest slot above. */
        public long recentSlotEndUnix = -1;
        /** Checkpoints of the last 24 hours, oldest first. */
        public long[] recentDayTs;
        public int[] recentDayPeers;
        /** Of today's elapsed slots (see todaySlotsElapsed),
         *  how many hold at least one checkpoint. A slot with
         *  two checkpoints counts once: both sides of the
         *  home card's today row count slots, over the same
         *  window, so this never exceeds todaySlotsElapsed. */
        public int todayCheckpoints = 0;
        /** Slots from local midnight through the end of the
         *  slot containing now — the denominator of the home
         *  card's today row, counted over the same window as
         *  todayCheckpoints. */
        public int todaySlotsElapsed = 0;
    }

    /**
     * @param ts     checkpoint times (unix seconds), any order
     * @param peers  observed peer counts paired with {@code ts}
     * @param nowUnix decision time (unix seconds)
     * @param zone   the local zone for day boundaries
     */
    public static Result compute(long[] ts, long[] peers,
                                 long nowUnix, ZoneId zone) {
        Result r = new Result();
        // Slot window: the 48 slots ending with the slot that
        // contains now. A timestamp exactly on a boundary
        // belongs to the slot it CLOSES (checkpoints land on
        // cadence boundaries; the record they close is the
        // newest one), so the window end is computed from
        // now - 1: at an exact boundary the just-completed
        // slot is the newest, not the empty one ahead.
        long windowEnd = Math.floorDiv(nowUnix - 1, SLOT_SECONDS)
                * SLOT_SECONDS + SLOT_SECONDS;
        long windowStart = windowEnd - (long) SLOT_COUNT * SLOT_SECONDS;
        r.recentSlotEndUnix = windowEnd;
        r.recentSlotPeers = new int[SLOT_COUNT];
        Arrays.fill(r.recentSlotPeers, -1);

        // Today (local): the home card's today row counts
        // BOTH sides over the same window — local midnight
        // through the end of the slot containing now (the
        // windowEnd derived above, clamped into the day) —
        // and in the same unit, slots: the denominator is
        // the number of day-relative slot indices the window
        // spans, the numerator (below) the number of those
        // slots holding at least one checkpoint. Internal.27
        // counted only COMPLETED slots below and raw records
        // above, so a checkpoint inside the still-open slot
        // rendered "16 / 15" and one just after midnight
        // "1 / 0" (audit finding F-23); with both sides on
        // one window the numerator can never exceed the
        // denominator.
        LocalDate today = Instant.ofEpochSecond(nowUnix)
                .atZone(zone).toLocalDate();
        long dayStart = today.atStartOfDay(zone).toEpochSecond();
        long nextDayStart = today.plusDays(1)
                .atStartOfDay(zone).toEpochSecond();
        long todayWindowEnd = windowEnd;
        if (todayWindowEnd > nextDayStart) {
            todayWindowEnd = nextDayStart;
        }
        if (todayWindowEnd < dayStart) {
            todayWindowEnd = dayStart;
        }
        long slots = 0;
        if (todayWindowEnd > dayStart) {
            slots = (todayWindowEnd - 1 - dayStart) / SLOT_SECONDS + 1;
        }
        long maxSlots = (nextDayStart - dayStart + SLOT_SECONDS - 1)
                / SLOT_SECONDS;
        if (slots > maxSlots) {
            slots = maxSlots;
        }
        r.todaySlotsElapsed = (int) slots;

        if (ts == null || peers == null) {
            r.recentDayTs = new long[0];
            r.recentDayPeers = new int[0];
            return r;
        }
        int n = Math.min(ts.length, peers.length);
        // Pair and sort ascending by time (defensive: callers
        // pass the journal's own order, which is already
        // ascending, but the derivation must not depend on it).
        long[][] pairs = new long[n][2];
        for (int i = 0; i < n; i++) {
            pairs[i][0] = ts[i];
            pairs[i][1] = peers[i];
        }
        Arrays.sort(pairs, (a, b) -> Long.compare(a[0], b[0]));

        Set<LocalDate> days = new HashSet<>();
        long recentFrom = nowUnix - RECENT_DAY_SECONDS;
        int recentCount = 0;
        for (int i = 0; i < n; i++) {
            if (pairs[i][0] >= recentFrom && pairs[i][0] <= nowUnix) {
                recentCount++;
            }
        }
        int keep = Math.min(recentCount, RECENT_DAY_MAX);
        long[] rts = new long[keep];
        int[] rpeers = new int[keep];
        int w = 0;
        int skipped = recentCount - keep;
        int seen = 0;
        int todayCount = 0;
        int lastTodaySlot = -1;
        for (int i = 0; i < n; i++) {
            long t = pairs[i][0];
            int p = clampPeers(pairs[i][1]);
            days.add(Instant.ofEpochSecond(t).atZone(zone)
                    .toLocalDate());
            if (t >= windowStart && t < windowEnd) {
                int idx = (int) ((t - windowStart) / SLOT_SECONDS);
                // Ascending order: a later record in the same
                // slot overwrites the earlier one (later wins).
                r.recentSlotPeers[idx] = p;
            }
            if (t >= recentFrom && t <= nowUnix) {
                if (seen >= skipped) {
                    rts[w] = t;
                    rpeers[w] = p;
                    w++;
                }
                seen++;
            }
            if (t >= dayStart && t < todayWindowEnd) {
                // Covered slots, not records: two checkpoints
                // inside one slot count once, so the numerator
                // stays within the denominator by construction.
                // Pairs are ascending, so tracking the last
                // counted slot index suffices.
                int idx = (int) ((t - dayStart) / SLOT_SECONDS);
                if (idx != lastTodaySlot) {
                    todayCount++;
                    lastTodaySlot = idx;
                }
            }
        }
        r.recentDayTs = rts;
        r.recentDayPeers = rpeers;
        r.todayCheckpoints = todayCount;
        r.longestStreakDays = longestStreak(days);
        return r;
    }

    private static int clampPeers(long peers) {
        if (peers < 0) {
            return 0;
        }
        if (peers > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) peers;
    }

    /** Longest run of consecutive calendar days in the set. */
    static int longestStreak(Set<LocalDate> days) {
        if (days == null || days.isEmpty()) {
            return 0;
        }
        LocalDate[] sorted = days.toArray(new LocalDate[0]);
        Arrays.sort(sorted);
        int best = 1;
        int run = 1;
        for (int i = 1; i < sorted.length; i++) {
            if (sorted[i].equals(sorted[i - 1].plusDays(1))) {
                run++;
                if (run > best) {
                    best = run;
                }
            } else {
                run = 1;
            }
        }
        return best;
    }
}
