package app.quantusnode.node;

/**
 * The price fetch's pure decisions (no Android, no I/O), split
 * out of {@link PriceStore} so they are exercisable on a JVM:
 * the failure backoff and the staleness judgement.
 *
 * Backoff shape (changed 2026-11-10, internal.23): the first
 * failure waits 5 minutes, every later failure 15 minutes.
 * The previous third tier (60 minutes) is gone on purpose:
 * with failures recorded nowhere, three failed attempts used
 * to silence the price for an hour while the app was in active
 * use, and the user could neither see why nor when it would
 * recover. Fifteen minutes bounds the silence; the endpoint
 * recovering is picked up within one backoff window, and the
 * fetch error itself is now persisted by PriceStore, so a
 * long failure is at least diagnosable after the fact.
 */
public final class PriceLogic {

    private PriceLogic() {
    }

    /** Delay before the next attempt after {@code failures}
     *  consecutive failures (1-based: the delay scheduled BY
     *  the failure that just happened). */
    public static long backoffMs(int failures) {
        return failures <= 1
                ? 5L * 60 * 1000
                : 15L * 60 * 1000;
    }

    /**
     * Whether a cached value is presented as stale: the most
     * recent attempt failed, or the value's fetch is older than
     * {@code staleMs}. A value that was never fetched (fetch
     * time 0) is not stale — it is absent, which the UI renders
     * as a dash, a different fact.
     */
    public static boolean isStale(long fetchedWallMs, long nowWallMs,
                                  long staleMs,
                                  boolean lastAttemptFailed) {
        if (fetchedWallMs <= 0) {
            return false;
        }
        return lastAttemptFailed || nowWallMs - fetchedWallMs > staleMs;
    }
}
