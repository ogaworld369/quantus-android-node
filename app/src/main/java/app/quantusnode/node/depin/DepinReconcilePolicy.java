package app.quantusnode.node.depin;

/**
 * The main-process facade's reconcile decision, as a pure,
 * stateful policy (no Android, no I/O): given the (merged)
 * enable state, the observer service's liveness, the count
 * of consecutive FAILED launches the supervisor derived from
 * its persisted launch window, the wall time the next launch
 * is allowed at, and the current wall time, decide what a
 * status refresh should do about a possibly-dead observer.
 *
 * Why the reconcile exists (2026-11-10 device incident,
 * internal.21): the observer process was killed outright (no
 * Java ran: the persisted enable flag stayed on and the
 * status file kept the last snapshot the dead service had
 * written, which the settings screen then presented as
 * "recording" for eight hours). The only code that ever
 * relaunched it was UI-driven, so nothing recovered while
 * the app was closed. The reconcile runs on every refresh
 * (UI tick and the node's ~30 s app-driver) and relaunches
 * a dead observer when recording is enabled.
 *
 * Why the backoff (internal.23's failure, fixed in .24):
 * internal.23 gated relaunches with the supervisor's
 * permanent latch — once the 24 h window saw 20 launches
 * (of any kind, clean ones included), no automatic launch
 * ever happened again and the ONLY reset was the settings
 * toggle, which itself could not land OFF while suppressed
 * (see DepinStatusMerge). A hard stop whose escape hatch is
 * broken is a dead end. The latch is gone: launches are
 * gated by a backoff over CONSECUTIVE FAILED launches,
 * derived by the supervisor from the same window (a launch
 * that was seen alive, or that recorded a clean exit, is
 * not a failure; failures decay as window entries expire).
 * The delay doubles 1, 2, 4, 8 minutes, then 15, 30, and
 * caps at 60 minutes — repeated instant deaths therefore
 * cost at most one launch per hour in the worst case, the
 * settings screen can state truthfully when the next retry
 * happens, and there is no state from which recovery is
 * impossible. A user OFF→ON remains an immediate manual
 * reset (the facade clears the supervisor window).
 */
public final class DepinReconcilePolicy {

    /** Minimum interval between reconcile launch attempts. */
    public static final long INTERVAL_MS = 30_000L;

    /** First backoff step, after one failed launch. */
    public static final long BACKOFF_BASE_MS = 60_000L;
    /** Backoff cap: one automatic retry per hour, worst case. */
    public static final long BACKOFF_MAX_MS = 60L * 60 * 1000;

    public enum Decision {
        /** Nothing to do: disabled, alive, waiting out a
         *  backoff, or inside the launch throttle. */
        NONE,
        /** Issue a launch and start the observer. */
        LAUNCH,
        /** The backoff state changed (a new failure count):
         *  surface it once — the facade logs the retry time. */
        BACKOFF_NOTICE
    }

    /**
     * The backoff delay after {@code consecutiveFailures}
     * failed launches in a row: 0, 1, 2, 4, 8, 15, 30, then
     * 60 minutes (capped). Pure and total: any count maps to
     * a finite delay, so a next-retry time always exists.
     */
    public static long backoffDelayMs(int consecutiveFailures) {
        if (consecutiveFailures <= 0) {
            return 0L;
        }
        switch (consecutiveFailures) {
            case 1:
                return BACKOFF_BASE_MS;
            case 2:
                return 2 * BACKOFF_BASE_MS;
            case 3:
                return 4 * BACKOFF_BASE_MS;
            case 4:
                return 8 * BACKOFF_BASE_MS;
            case 5:
                return 15 * BACKOFF_BASE_MS;
            case 6:
                return 30 * BACKOFF_BASE_MS;
            default:
                return BACKOFF_MAX_MS;
        }
    }

    /**
     * The wall time at which the next automatic launch is
     * allowed: the last launch's issue time plus the backoff
     * for the current failure count. 0 when a launch is
     * allowed immediately (no launch yet, or no failures).
     */
    public static long nextLaunchAllowedMs(long lastLaunchWallMs,
                                           int consecutiveFailures) {
        if (lastLaunchWallMs <= 0 || consecutiveFailures <= 0) {
            return 0L;
        }
        return lastLaunchWallMs + backoffDelayMs(consecutiveFailures);
    }

    /** Wall time of the last launch THIS policy instance
     *  issued (the launch throttle is per facade instance;
     *  the durable backoff lives in the supervisor window). */
    private long lastLaunchAttemptMs = Long.MIN_VALUE;
    /** Failure count the BACKOFF_NOTICE last fired for;
     *  re-armed whenever a decide sees zero failures. */
    private int lastNoticedFailures = 0;

    /**
     * @param enabled               merged enable state (user
     *                              intent applied — see
     *                              DepinStatusMerge)
     * @param serviceAlive          the observer is bound
     * @param consecutiveFailures   failed launches in a row,
     *                              derived by the supervisor
     * @param nextLaunchAllowedMs   from {@link
     *                              #nextLaunchAllowedMs} over
     *                              the supervisor's window
     * @param nowMs                 current wall time
     */
    public synchronized Decision decide(boolean enabled,
                                        boolean serviceAlive,
                                        int consecutiveFailures,
                                        long nextLaunchAllowedMs,
                                        long nowMs) {
        if (consecutiveFailures <= 0) {
            // A healthy reading re-arms the notice: a failure
            // episode after a recovery is surfaced again.
            lastNoticedFailures = 0;
        }
        if (!enabled || serviceAlive) {
            return Decision.NONE;
        }
        if (consecutiveFailures > 0 && nowMs < nextLaunchAllowedMs) {
            // Waiting out a backoff: say so once per count.
            if (consecutiveFailures != lastNoticedFailures) {
                lastNoticedFailures = consecutiveFailures;
                return Decision.BACKOFF_NOTICE;
            }
            return Decision.NONE;
        }
        if (lastLaunchAttemptMs != Long.MIN_VALUE
                && nowMs - lastLaunchAttemptMs < INTERVAL_MS) {
            return Decision.NONE;
        }
        lastLaunchAttemptMs = nowMs;
        return Decision.LAUNCH;
    }
}
