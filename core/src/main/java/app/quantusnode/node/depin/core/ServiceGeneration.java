package app.quantusnode.node.depin.core;

/* Split out of RpcClient.java in the :depin-core move (integration
 * design section 2.1): promoted to a public top-level class in the
 * core package. Logic unchanged; only visibility and file placement. */
/**
 * Service generation guard for the stop/start race (re-audit
 * M-04, v0.8). A tick that is already running when the service
 * is stopped can finish its RPC round AFTER onDestroy has shut
 * the executor down — and if the service is then restarted, the
 * stale tick's append could land inside the NEW generation's
 * journal session, writing a checkpoint chained to state the
 * new generation has not (re)loaded or, worse, a best below the
 * tail another writer already recorded. The guard makes that
 * interleaving impossible to miss: each tick captures the
 * generation when it STARTS; ObservationService.onDestroy calls
 * requestStop() BEFORE shutting the executor down, which sets
 * the stop flag and advances the generation; a restarted service
 * calls beginNewGeneration(). An append is allowed only while
 * the captured generation is still current AND no stop was
 * requested since; the stale tick then returns without writing.
 * Pure logic (no Android, no I/O) so the JVM harness pins the
 * transitions; JournalStore.append's own best-monotonicity
 * check is the second line of defence.
 */
public final class ServiceGeneration {
    /** The one process-wide guard: service generations are a
     *  property of the process, not of any service instance. */
    public static final ServiceGeneration PROCESS = new ServiceGeneration();

    private final java.util.concurrent.atomic.AtomicLong current =
            new java.util.concurrent.atomic.AtomicLong(0);
    private volatile boolean stopRequested = false;

    /** The generation a starting tick must capture. */
    public long capture() {
        return current.get();
    }

    /** A stop was requested: every captured generation goes
     *  stale at once, and the flag latches until the next
     *  beginNewGeneration(). */
    public void requestStop() {
        stopRequested = true;
        current.incrementAndGet();
    }

    /** A (re)started service begins a fresh generation; ticks
     *  captured before it are stale. */
    public void beginNewGeneration() {
        stopRequested = false;
        current.incrementAndGet();
    }

    /** True only while gen is the live generation and no stop
     *  has been requested since it was captured. */
    public boolean isCurrent(long gen) {
        return !stopRequested && gen == current.get();
    }
}
