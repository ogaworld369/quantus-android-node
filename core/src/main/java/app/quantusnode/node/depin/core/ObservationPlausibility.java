package app.quantusnode.node.depin.core;

/* Split out of RpcClient.java in the :depin-core move (integration
 * design section 2.1): promoted to a public top-level class in the
 * core package. Logic unchanged; only visibility and file placement. */
/**
 * Plausibility state machine for accepted observations (audit
 * M-02, extended by re-audit R-M03). The loopback endpoint is
 * unauthenticated — any local process could answer on port 9944 —
 * so a single poisoned round must not be able to write absurd
 * values into the signed history, nor wedge all future
 * observations (v0.3 accepted a huge best number once, after which
 * the real node looked like a permanent "regression").
 *
 * The tracker holds the BASELINE: the last ACCEPTED observation's
 * (best, timestamp). The service seeds it from the journal tail at
 * start, so the rules bind across process restarts exactly as they
 * bind within a run (R-M03 extension a — the v0.4 jump check
 * silently restarted from the in-memory state only). Decisions:
 *  - finalized ahead of best: rejected, no state change (it is an
 *    inconsistency, not a regression, and must not consume the
 *    regression budget);
 *  - no baseline yet (empty journal): the first observation is
 *    ACCEPTED and flagged baselineInitializing in the decision —
 *    there is nothing to compare it against, but the acceptance is
 *    never silent;
 *  - best at or above the baseline: accepted when the forward jump
 *    is within max(MAX_BLOCKS_PER_SLOT, elapsedSlots *
 *    MAX_BLOCKS_PER_SLOT), elapsedSlots counted from the
 *    baseline's timestamp; the baseline advances and the
 *    regression counter resets;
 *  - best below the baseline (regression): rejected and counted.
 *    On the THIRD consecutive regression the new best is adopted
 *    as the baseline and the round is ACCEPTED (flagged
 *    baselineReset). A node that resyncs, or a baseline seeded
 *    from one stale value, therefore costs at most three rounds —
 *    never a permanent wedge (R-M03 extension b). Any accepted
 *    observation updates the baseline.
 * The machine is deterministic and free of I/O and clocks: callers
 * pass observations in, decisions come out; the harness pins the
 * full transition table.
 *
 * Note the division of labour: this machine decides whether an
 * observation is BELIEVED. Whether an accepted observation may be
 * APPENDED is the service's call — the bundle format requires
 * non-decreasing best across checkpoints, so a baselineReset
 * acceptance whose best is still below the journal tail's best is
 * held from the journal (observations keep being accepted against
 * the new baseline, and appends resume the moment best reaches
 * the recorded tail again) rather than written as a checkpoint
 * that would make every future export fail its self-check.
 */
public final class ObservationPlausibility {

    /** One decision. reason is human-readable and always set for
     *  rejections; flags mark the two non-silent acceptances. */
    public static final class Decision {
        public final boolean accepted;
        public final boolean baselineInitializing;
        public final boolean baselineReset;
        public final String reason;

        private Decision(boolean accepted, boolean baselineInitializing,
                         boolean baselineReset, String reason) {
            this.accepted = accepted;
            this.baselineInitializing = baselineInitializing;
            this.baselineReset = baselineReset;
            this.reason = reason;
        }
    }

    private boolean hasBaseline = false;
    private long baselineBest;
    private long baselineTs;
    private int consecutiveRegressions = 0;

    public ObservationPlausibility() {}

    /** Seed from a persisted baseline (the journal tail): the
     *  first evaluate() then applies the normal rules instead of
     *  the baseline-initializing path. */
    public static ObservationPlausibility seededWith(long best, long timestampUnix) {
        ObservationPlausibility p = new ObservationPlausibility();
        p.hasBaseline = true;
        p.baselineBest = best;
        p.baselineTs = timestampUnix;
        return p;
    }

    boolean hasBaseline() {
        return hasBaseline;
    }

    long baselineBest() {
        return baselineBest;
    }

    int consecutiveRegressions() {
        return consecutiveRegressions;
    }

    public synchronized Decision evaluate(DepinCore.Observation o) {
        if (o.finalizedBlockNumber != null
                && o.finalizedBlockNumber > o.bestBlockNumber) {
            return new Decision(false, false, false,
                    "finalized " + o.finalizedBlockNumber
                            + " ahead of best " + o.bestBlockNumber);
        }
        if (!hasBaseline) {
            adopt(o);
            return new Decision(true, true, false, null);
        }
        if (o.bestBlockNumber >= baselineBest) {
            long elapsedSlots = Math.max(0L,
                    o.timestampUnix / DepinCore.SLOT_SEC
                            - baselineTs / DepinCore.SLOT_SEC);
            long limit = Math.max(RpcClient.MAX_BLOCKS_PER_SLOT,
                    elapsedSlots * RpcClient.MAX_BLOCKS_PER_SLOT);
            long jump = o.bestBlockNumber - baselineBest;
            if (jump > limit) {
                return new Decision(false, false, false,
                        "best jumped " + jump + " blocks in "
                                + elapsedSlots + " slot(s); limit is " + limit);
            }
            adopt(o);
            return new Decision(true, false, false, null);
        }
        // Regression below the baseline.
        consecutiveRegressions++;
        if (consecutiveRegressions >= 3) {
            long old = baselineBest;
            adopt(o);
            return new Decision(true, false, true,
                    "best regressed persistently (" + old + " -> "
                            + o.bestBlockNumber + "); adopted as the new baseline");
        }
        return new Decision(false, false, false,
                "best regressed " + baselineBest + " -> " + o.bestBlockNumber
                        + " (regression " + consecutiveRegressions + " of 3)");
    }

    private void adopt(DepinCore.Observation o) {
        hasBaseline = true;
        baselineBest = o.bestBlockNumber;
        baselineTs = o.timestampUnix;
        consecutiveRegressions = 0;
    }
}
