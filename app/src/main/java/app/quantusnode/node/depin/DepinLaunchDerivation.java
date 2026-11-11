package app.quantusnode.node.depin;

import java.util.List;

/**
 * The supervisor's consecutive-failure derivation, as a
 * pure function (no Android, no I/O) over the launch
 * window's facts. Extracted from DepinSupervisor in
 * internal.26 so the derivation — the input side of the
 * restart backoff (DepinReconcilePolicy) — is decidable in
 * the JVM harness; DepinSupervisor keeps the window's
 * persistence, the boot/age bookkeeping and the launch
 * records, and resolves each entry's issue time before
 * delegating here.
 *
 * The rule (internal.26, audit finding F-02): count FAILED
 * launches from the end of the window. The run stops at
 * the first entry that is clean or was seen alive. An
 * entry is a failure when it is neither, and it has had
 * its chance: a later launch superseded it, or the fail
 * grace elapsed since it was issued. The LATEST entry,
 * while still within its grace, is unjudged — it may yet
 * come up — and is SKIPPED: exempt from the count, but it
 * does not truncate it. The settled failures before it
 * still count.
 *
 * What went wrong before internal.26: the unjudged latest
 * entry STOPPED the count (a {@code break}), so the
 * derivation returned 0 whenever the newest launch was
 * younger than the grace. The reconcile drives every
 * ~30 s and the grace is 120 s, so under a persistent
 * bind failure the newest entry was ALWAYS unjudged: the
 * derived count stayed 0 forever, the backoff never
 * engaged, BACKOFF_NOTICE never fired, and launches were
 * issued on the 30 s throttle alone — 121 per hour,
 * 2,881 per day in the audit's reproduction against the
 * shipped policy. With the skip, the same reproduction
 * yields 7 launches in the first hour and 30 in 24 hours,
 * on the designed 1/2/4/8/15/30/60-minute schedule.
 *
 * Entries with no readable issue time are judged by their
 * accrued age alone (older than the grace counts as
 * settled), so an unreadable record can neither wedge the
 * count at zero nor inflate it instantly.
 */
public final class DepinLaunchDerivation {

    private DepinLaunchDerivation() {
    }

    /** One launch, as the derivation sees it. The window is
     *  passed oldest-first. */
    public static final class Launch {
        /** The run ended orderly (clean marker), or was
         *  observed alive long enough to count as clean. */
        public final boolean clean;
        /** The service was observed bound at least once
         *  while this was the latest entry. */
        public final boolean seenAlive;
        /** Wall time the launch was issued; 0 when no
         *  readable stamp exists (the age fallback then
         *  applies). Only ever consulted for the latest
         *  entry — a superseded entry is settled by the
         *  supersede itself. */
        public final long issuedWallMs;
        /** Accrued same-boot age; the fallback judgement
         *  basis when {@link #issuedWallMs} is 0. */
        public final long ageMs;

        public Launch(boolean clean, boolean seenAlive,
                      long issuedWallMs, long ageMs) {
            this.clean = clean;
            this.seenAlive = seenAlive;
            this.issuedWallMs = issuedWallMs;
            this.ageMs = ageMs;
        }
    }

    /**
     * Count consecutive failed launches from the end of
     * the window.
     *
     * @param launches    the window, oldest first
     * @param nowWallMs   current wall time
     * @param failGraceMs the grace after which an
     *                    unobserved latest launch is
     *                    judged a failure
     */
    public static int consecutiveFailures(List<Launch> launches,
                                          long nowWallMs,
                                          long failGraceMs) {
        int failures = 0;
        for (int i = launches.size() - 1; i >= 0; i--) {
            Launch e = launches.get(i);
            if (e.clean || e.seenAlive) {
                break;
            }
            boolean superseded = i < launches.size() - 1;
            if (!superseded) {
                boolean graceElapsed = e.issuedWallMs > 0
                        ? nowWallMs - e.issuedWallMs >= failGraceMs
                        : e.ageMs >= failGraceMs;
                if (!graceElapsed) {
                    // Latest launch still unjudged: exempt
                    // it — skip, do NOT stop the count. The
                    // settled failures before it stand.
                    continue;
                }
            }
            failures++;
        }
        return failures;
    }
}
