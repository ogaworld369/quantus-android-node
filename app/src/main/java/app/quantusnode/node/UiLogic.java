package app.quantusnode.node;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure display logic for the versionCode 26 UI: every rule that
 * turns service facts ({@link NodeState}) and stored settings into
 * what a screen shows, kept Android-free so the rules can be
 * exercised on a JVM against the real code. {@link MainActivity}
 * owns the views; this class owns the decisions:
 *
 *  - which of the five home presentations a state snapshot means
 *    (the stopped / failed split comes from the service's stop
 *    classification, never from a leftover exit code);
 *  - the sync gap's validity rule (shown only when both heights are
 *    known and highest &gt;= best);
 *  - duration and "last observed" wording, with the redesign's
 *    fixed rounding (session: under a minute is "less than a
 *    minute"; cumulative: exactly zero is "0 minutes"; observation
 *    ages bucket by whole seconds / minutes / hours / days);
 *  - the pending-settings predicate (condition P): a saved setting
 *    counts as pending only while the node is running AND its value
 *    actually differs from the value this launch is using;
 *  - display-name validation: the untruncated sanitized result's
 *    code-point count decides, and an over-limit name is refused,
 *    never cut;
 *  - when a home pulse ripple may fire for a newly observed block:
 *    only against a baseline the UI adopted at display start, so
 *    the first increase after a cold start counts and a rebuild
 *    never manufactures one.
 *
 * Numbers: counts stay long end to end. {@link #grouped} renders a
 * value with ASCII digits and 3-digit grouping; the plural wording
 * is chosen separately by {@link StringsTable#pluralCategory} from
 * the same long, exactly as the redesign spec requires.
 */
public final class UiLogic {

    private UiLogic() {
    }

    // ------------------------------------------------------------------
    // Home presentation
    // ------------------------------------------------------------------

    /** The five home presentations of the redesign (spec H). */
    public enum HomeState {
        STOPPED,
        STOPPED_TIME_LIMIT,
        FAILED_START,
        FAILED_UNEXPECTED,
        FAILED_BINARY,
        STARTING,
        SYNCING,
        SYNCED
    }

    /** Map a state snapshot onto its home presentation. */
    public static HomeState homeState(NodeState.Status status,
                                      NodeState.StopKind kind) {
        switch (status) {
            case STARTING:
                return HomeState.STARTING;
            case SYNCING:
                return HomeState.SYNCING;
            case SYNCED:
                return HomeState.SYNCED;
            case STOPPED:
            default:
                switch (kind) {
                    case START_FAILED:
                        return HomeState.FAILED_START;
                    case UNEXPECTED:
                        return HomeState.FAILED_UNEXPECTED;
                    case BINARY_MISSING:
                        return HomeState.FAILED_BINARY;
                    case TIME_LIMIT:
                        return HomeState.STOPPED_TIME_LIMIT;
                    case NONE:
                    case USER:
                    default:
                        return HomeState.STOPPED;
                }
        }
    }

    /** True when the presentation is a failure (red core, retry). */
    public static boolean isFailure(HomeState s) {
        return s == HomeState.FAILED_START
                || s == HomeState.FAILED_UNEXPECTED
                || s == HomeState.FAILED_BINARY;
    }

    /** True while the node counts as running for the stats rows. */
    public static boolean showsStats(HomeState s) {
        return s == HomeState.STARTING
                || s == HomeState.SYNCING
                || s == HomeState.SYNCED;
    }

    // ------------------------------------------------------------------
    // Pulse ripple (audit finding L-1)
    // ------------------------------------------------------------------

    /**
     * Whether this refresh may fire one home pulse ripple for a
     * newly recorded observation. The baseline is the snapshot the
     * UI adopted when it (re)started displaying — adopted as a
     * value, never as a firing — so the first increase detected
     * after a cold start ripples like any other, while a rebuild, a
     * screen switch or a return to the screen cannot manufacture
     * one: those paths re-adopt the snapshot (raising the baseline
     * to it) before any tick runs, and a fresh activity's first
     * refresh never fires.
     *
     * @param baselineReady  the display-start snapshot was adopted
     * @param firstRefresh   this is the activity instance's first tick
     * @param synced         the presentation is SYNCED
     * @param onHomeScreen   the home screen is the visible one
     * @param observedBest   the service's latest observed best block
     *                       (-1 while nothing has been observed)
     * @param baselineBest   the adopted baseline (-1 before the
     *                       first observation exists)
     */
    public static boolean shouldRipple(boolean baselineReady,
                                       boolean firstRefresh,
                                       boolean synced,
                                       boolean onHomeScreen,
                                       long observedBest,
                                       long baselineBest) {
        return baselineReady
                && !firstRefresh
                && synced
                && onHomeScreen
                && observedBest >= 0
                && observedBest > baselineBest;
    }

    // ------------------------------------------------------------------
    // Numbers
    // ------------------------------------------------------------------

    /**
     * ASCII digits with 3-digit grouping ("357,605"). The app's
     * digit policy pins ASCII shapes in every language; grouping
     * follows the redesign spec ("locale-appropriate grouping" —
     * realized here as the US-style 3-digit grouping that matches
     * the pinned digit policy, including for the Indian grouping
     * convention, which is not adopted).
     */
    public static String grouped(long v) {
        if (v < 0) {
            return Long.toString(v);
        }
        String digits = Long.toString(v);
        StringBuilder out = new StringBuilder(digits.length() + 4);
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                out.append(',');
            }
            out.append(digits.charAt(i));
        }
        return out.toString();
    }

    /** One quantity string with its grouped number filled in. */
    public static String quantityText(String tag, String baseKey, long n) {
        return String.format(Locale.US,
                StringsTable.quantity(tag, baseKey, n), grouped(n));
    }

    // ------------------------------------------------------------------
    // Sync gap
    // ------------------------------------------------------------------

    /**
     * The sync gap to display, or -1 when there is nothing honest
     * to show: either height unknown, or the pair inconsistent
     * (highest below best). A gap of 0 is a valid value, not "no
     * gap" — the node's own status still decides "synced".
     */
    public static long syncGap(long best, long highest) {
        if (best < 0 || highest < 0 || highest < best) {
            return -1;
        }
        return highest - best;
    }

    /** "About N blocks to go" for the home supplement, or null. */
    public static String syncGapText(String tag, long best, long highest) {
        long gap = syncGap(best, highest);
        if (gap < 0) {
            return null;
        }
        return quantityText(tag, StringsTable.SYNC_REMAINING_BLOCKS, gap);
    }

    // ------------------------------------------------------------------
    // Durations and observation ages (rounding fixed by spec E)
    // ------------------------------------------------------------------

    /**
     * A duration for the uptime rows. Session form: under 60 s is
     * "less than a minute"; from 60 s the seconds are truncated and
     * whole days / hours / minutes are joined, zero units omitted.
     * Cumulative form (the track totals): an exact zero is
     * "0 minutes" instead of "less than a minute".
     */
    public static String durationText(String tag, long ms, boolean cumulative) {
        if (ms < 0) {
            ms = 0;
        }
        if (cumulative && ms == 0) {
            return quantityText(tag, StringsTable.DURATION_MINUTES, 0);
        }
        if (ms < 60_000L) {
            return quantityText(tag, StringsTable.DURATION_LESS_THAN_MINUTES, 1);
        }
        long totalMinutes = ms / 60_000L;
        long days = totalMinutes / 1440L;
        long hours = (totalMinutes % 1440L) / 60L;
        long minutes = totalMinutes % 60L;
        List<String> parts = new ArrayList<>(3);
        if (days > 0) {
            parts.add(quantityText(tag, StringsTable.DURATION_DAYS, days));
        }
        if (hours > 0) {
            parts.add(quantityText(tag, StringsTable.DURATION_HOURS, hours));
        }
        if (minutes > 0) {
            parts.add(quantityText(tag, StringsTable.DURATION_MINUTES, minutes));
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        if (parts.size() == 2) {
            return String.format(Locale.US,
                    StringsTable.get(tag, StringsTable.DURATION_JOIN_TWO),
                    parts.get(0), parts.get(1));
        }
        return String.format(Locale.US,
                StringsTable.get(tag, StringsTable.DURATION_JOIN_THREE),
                parts.get(0), parts.get(1), parts.get(2));
    }

    /**
     * The "last observed" line: under a second is "just now", then
     * whole seconds up to 59, whole minutes up to 3,599 s, whole
     * hours up to 86,399 s, whole days beyond that.
     */
    public static String lastSeenText(String tag, long elapsedMs) {
        if (elapsedMs < 1_000L) {
            return StringsTable.get(tag, StringsTable.OBSERVATION_JUST_NOW);
        }
        if (elapsedMs < 60_000L) {
            return quantityText(tag, StringsTable.OBSERVATION_SECONDS_AGO,
                    elapsedMs / 1_000L);
        }
        if (elapsedMs < 3_600_000L) {
            return quantityText(tag, StringsTable.OBSERVATION_MINUTES_AGO,
                    elapsedMs / 60_000L);
        }
        if (elapsedMs < 86_400_000L) {
            return quantityText(tag, StringsTable.OBSERVATION_HOURS_AGO,
                    elapsedMs / 3_600_000L);
        }
        return quantityText(tag, StringsTable.OBSERVATION_DAYS_AGO,
                elapsedMs / 86_400_000L);
    }

    // ------------------------------------------------------------------
    // Pending settings (condition P)
    // ------------------------------------------------------------------

    public static final int PENDING_TELEMETRY = 1;
    public static final int PENDING_NAME = 2;

    /**
     * Which of the next-start settings actually differ from the
     * values the running node was launched with. Mirrors the
     * predicate the pre-redesign UI used for its restart note: no
     * launch snapshot (or no running node) means nothing is pending,
     * and returning a setting to its launched value clears its flag.
     */
    public static int settingsPending(boolean serviceRunning,
                                      Boolean launchedTelemetry,
                                      boolean wantTelemetry,
                                      String launchedNodeName,
                                      String wantName) {
        if (!serviceRunning || launchedTelemetry == null
                || launchedNodeName == null) {
            return 0;
        }
        int out = 0;
        if (launchedTelemetry.booleanValue() != wantTelemetry) {
            out |= PENDING_TELEMETRY;
        }
        if (!launchedNodeName.equals(wantName)) {
            out |= PENDING_NAME;
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Display-name validation (spec C)
    // ------------------------------------------------------------------

    /** Code points of the name as it would be saved (untruncated sanitize). */
    public static int nameCodePoints(String raw) {
        String s = NodeSettings.sanitizeUntruncated(raw);
        return s.codePointCount(0, s.length());
    }

    /** The name as it would be saved, before any cap: the preview text. */
    public static String nameSavedPreview(String raw) {
        return NodeSettings.sanitizeUntruncated(raw);
    }

    /** True when the sanitized result fits the cap and may be saved. */
    public static boolean nameSaveable(String raw) {
        return nameCodePoints(raw) <= NodeSettings.nameLimitCodePoints();
    }
}
