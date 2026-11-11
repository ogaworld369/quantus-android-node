package app.quantusnode.node.depin;

/**
 * The observation tick's circuit breaker, counting ROUNDS
 * rather than tick invocations (integration design section
 * 3.3; final check round 3, H-09).
 *
 * The pre-H-09 counter lived in the tick wrapper: ANY tick
 * that returned normally reset it to zero, and any escaping
 * Throwable raised it. But most ticks return normally
 * without running a round at all — above all the tick that
 * finds the current slot's round already paid for (the
 * attempt log has attempts for it) and returns at once. A
 * failure mode in which a round SPENDS its slot's attempts
 * and only then fails (a signing failure at append time, the
 * journal cap reached, a malformed RPC response shape) was
 * therefore un-trippable: the failed tick raised the count
 * to 1, the slot's remaining ~19 ticks each "returned
 * normally" on the paid-for early return and reset it to 0,
 * and the next slot's failure started again from 1 — while
 * the status kept saying "recording". The observer now
 * reports its round boundaries explicitly:
 *
 *   - roundAttempted is implicit in the calls below: only a
 *     tick that passed the already-paid gate runs a round,
 *     and only such a tick calls either method;
 *   - {@link #roundCompleted()} — the round ran and the tick
 *     returned without an escaping failure (a checkpoint, a
 *     handled rejection, a hold: all are the loop working as
 *     designed). The consecutive count resets;
 *   - {@link #roundFailed(long)} — the round ran and a
 *     failure escaped it. The count rises by one, at most
 *     once per slot (a slot's round is paid for once, so a
 *     second signal for the same slot without an intervening
 *     completion is the same round reporting twice).
 *
 * Ticks that run no round (not initialised, slot already
 * recorded, clock behind the attempt log's maximum, slot
 * already paid for) call NEITHER method: they neither raise
 * nor reset the count. That is the whole fix — the evidence
 * of consecutive failed rounds is no longer erased by the
 * quiet ticks in between.
 *
 * Withheld rounds (the design section 3.4 free-space floor
 * refusing an append) are also neither: the caller simply
 * does not report them, like a tick that ran no round.
 */
public final class DepinTickBreaker {

    private final int threshold;
    private int consecutiveFailedRounds = 0;
    private long lastCountedSlot = -1;

    public DepinTickBreaker(int threshold) {
        this.threshold = Math.max(1, threshold);
    }

    /** A round was attempted and finished without an
     *  escaping failure: the consecutive count resets. */
    public synchronized void roundCompleted() {
        consecutiveFailedRounds = 0;
        lastCountedSlot = -1;
    }

    /** A round attempted for {@code slot} escaped with a
     *  failure. Returns true when the breaker trips — the
     *  threshold of consecutive failed rounds is reached and
     *  the observer should stop itself. */
    public synchronized boolean roundFailed(long slot) {
        if (slot != lastCountedSlot) {
            lastCountedSlot = slot;
            consecutiveFailedRounds++;
        }
        return consecutiveFailedRounds >= threshold;
    }

    /** The current consecutive-failed-rounds count (status
     *  and tests). */
    public synchronized int consecutiveFailedRounds() {
        return consecutiveFailedRounds;
    }
}
