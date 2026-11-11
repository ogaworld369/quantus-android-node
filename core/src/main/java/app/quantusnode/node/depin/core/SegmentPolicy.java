package app.quantusnode.node.depin.core;

/* Split out of RpcClient.java in the :depin-core move (integration
 * design section 2.1): promoted to a public top-level class in the
 * core package. Logic unchanged; only visibility and file placement. */
/**
 * Segment policy for the recorded journal (re-audit M-02,
 * redesigned in v0.8). The plausibility machine above decides
 * whether an observation is BELIEVED; this class decides two
 * things about the RECORDED history it feeds. Both defences sit
 * at the INTAKE side — they bound what may be recorded — because
 * the v0.8 design makes recovery asymmetric: recorded, signed
 * history is never preserved, quarantined or restarted merely
 * because the live RPC view disagrees with it. The v0.7
 * poisoned-tail preservation rule is WITHDRAWN: the v0.7
 * re-audit (M-02) showed it could be inverted — a fake LOW view
 * (best=1, three times) healed the baseline downward and then
 * "preserved" the device's GENUINE history out of the live
 * journal. RPC disagreement now only ever HOLDS appends; the
 * only destructive recovery left is the identity/format
 * quarantine in JournalStore (the B4 class), which never
 * consults RPC at all.
 *
 * (a) Segment start (unchanged from v0.7): the FIRST checkpoint
 *     of a fresh segment (a new journal, or a journal emptied
 *     by quarantine) is recorded only after
 *     SEGMENT_START_PLAUSIBLE_READINGS consecutive ACCEPTED
 *     readings. A single poisoned first response can adopt the
 *     in-memory baseline, but it can no longer put a checkpoint
 *     on disk by itself: the second reading must also be
 *     plausible against the first. Any rejection resets the
 *     streak — "consecutive" is load-bearing.
 * (b) Advance cap (new in v0.8): a new checkpoint's best may
 *     exceed the RECORDED TAIL's best by at most
 *     cap_best(now) = tail_best
 *         + floor((now − tail_timestamp) / SLOT_SEC) × A + A,
 *     where A is MAX_TAIL_ADVANCE_BLOCKS_PER_SLOT, "now" is the
 *     candidate reading's own timestamp, and tail_timestamp is
 *     the recorded tail checkpoint's timestamp — persisted in
 *     the journal, never a boot time and never an unadopted RPC
 *     value (design review A4). The plausibility machine bounds
 *     a reading against the in-memory baseline, which baseline
 *     HEALING can move; the recorded tail cannot move, so this
 *     cap bounds how far a poisoned baseline can drag the
 *     recorded history in one step: a fake-high reading that
 *     slips past plausibility mid-segment is HELD at the tail,
 *     not recorded. Readings at or below the tail are not
 *     advances at all (regressions are held by the service, as
 *     before). Because the elapsed time is measured from the
 *     tail's own timestamp, a node catching up after downtime
 *     becomes admissible as wall time passes.
 *
 * Scope of these rules, stated plainly (claims discipline,
 * design review A4): they BOUND the damage a hostile or broken
 * RPC view can do to the recorded history; they do not PREVENT
 * fabrication within the cap by a sustained hostile RPC, and
 * the two segment-start readings come from the same source —
 * a consistency check, not independent corroboration.
 * Within-cap fabrication in signed history is detectable only
 * by the aggregation side's cross-check against the real
 * chain, never locally.
 * The class is deterministic and free of I/O: the service wires
 * the decisions, the harness pins the arithmetic and the streak.
 */
public final class SegmentPolicy {

    /** Explicit public constructor (was the implicit default): the
     *  Android layer instantiates the policy directly. */
    public SegmentPolicy() {}
    /** Most blocks the recorded tail may advance per elapsed
     *  checkpoint slot (DepinCore.SLOT_SEC = 600 s). Derivation:
     *  this chain cannot produce blocks faster than roughly one
     *  per second even in theory — the intake plausibility bound
     *  (ObservationPlausibility.MAX_BLOCKS_PER_SLOT) uses the
     *  same figure — so one slot of real production is at most
     *  ~600 blocks, and at the chain's actual cadence (several
     *  seconds per block) it is ~100. The cap uses the same
     *  generous 6,000 blocks per slot: ten times the fastest
     *  conceivable production, so no honest chain movement is
     *  ever held, while a spoofed jump (e.g. a first response
     *  of 2^48-1) exceeds it by orders of magnitude. */
    static final long MAX_TAIL_ADVANCE_BLOCKS_PER_SLOT = 6000;
    /** Consecutive accepted readings required before a fresh
     *  segment records its first checkpoint. */
    static final int SEGMENT_START_PLAUSIBLE_READINGS = 2;

    private int plausibleStreak = 0;

    /** True when a candidate checkpoint may be appended after
     *  the recorded tail, under the A4 cap:
     *  cap_best(now) = tailBest + floor((now − tailTs) /
     *  SLOT_SEC) × A + A, with A =
     *  MAX_TAIL_ADVANCE_BLOCKS_PER_SLOT and "now" the
     *  candidate's timestamp. The elapsed time is measured from
     *  the recorded tail's OWN timestamp (persisted in the
     *  journal) — not from a boot time, and never from an
     *  unadopted RPC value — so the allowance grows with real
     *  wall time since the tail was recorded. A candidate at or
     *  below the tail is never an "advance" and always passes
     *  this check (the service holds regressions separately).
     *  Pure arithmetic, pinned by the harness. */
    public static boolean tailAdvanceAllowed(long tailBest, long tailTs,
                                      long candBest, long candTs) {
        if (candBest <= tailBest) {
            return true;
        }
        long elapsedSlots = Math.max(0L,
                (candTs - tailTs) / DepinCore.SLOT_SEC);
        long allowance = elapsedSlots * MAX_TAIL_ADVANCE_BLOCKS_PER_SLOT
                + MAX_TAIL_ADVANCE_BLOCKS_PER_SLOT;
        return candBest - tailBest <= allowance;
    }

    /** Record one accepted reading while the segment is empty.
     *  Returns true once the streak reaches the required count
     *  (and for every further accepted reading until reset). */
    public synchronized boolean noteAcceptedWhileSegmentEmpty() {
        plausibleStreak++;
        return plausibleStreak >= SEGMENT_START_PLAUSIBLE_READINGS;
    }

    /** A rejected reading breaks the streak: the segment-start
     *  readings must be consecutive. */
    public synchronized void noteRejected() {
        plausibleStreak = 0;
    }

    /** A new segment begins (quarantine): the streak starts
     *  over. */
    synchronized void reset() {
        plausibleStreak = 0;
    }

    synchronized int plausibleStreak() {
        return plausibleStreak;
    }
}
