package app.quantusnode.node.depin.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Section 3.5 re-verification machinery: the CPU budget ledger
 * and the resume cursor, plus the sliced sequential-chain
 * re-verifier that consumes them.
 *
 * Budget rules implemented (design section 3.5, as revised):
 *  - The ledger is a durable record of its own
 *    (verification-budget.json in the store root), separate
 *    from any cursor: exchanging or deleting a cursor never
 *    refills the budget.
 *  - Accounting is by RESERVATION: before a slice runs, its
 *    maximum CPU (2 s) and its maximum wait obligation (2 s)
 *    are reserved in a single atomic ledger update. After the
 *    slice, both are settled with the actual consumption and
 *    the wait actually owed (= the slice's actual CPU length;
 *    duty cycle: the idle owed is at least the slice length).
 *  - An unsettled reservation found at recovery counts as
 *    FULLY consumed, its consumption time is the recovery
 *    moment, and its wait obligation is owed in full from the
 *    recovery moment.
 *  - Settled consumption is aged from its settlement moment
 *    and kept for at least one hour of MEASURED elapsed time.
 *  - Elapsed time is accumulated only across durable stamps:
 *    every ledger write stamps (boot id, monotonic reading);
 *    same-boot deltas between stamps accumulate; unmeasured
 *    intervals (across boots, or before the first stamp of a
 *    boot) count as zero. Wall clock is never used for the
 *    window.
 *  - Remaining budget = 600 s − (window sum of settled
 *    consumption + unsettled reservations).
 *
 * The cursor binds progress to one package: package SHA-256,
 * verified count, and the chain state needed to resume. A
 * cursor is only ever used against the entity that was durable
 * when it was written (the section 4.3 step-6 boundary rules
 * govern when staged results may be relied on at all).
 */
public final class Reverification {
    private Reverification() {}

    /** Time source: boot identity + monotonic clock + the
     *  current thread's CPU clock. Supplied by the platform
     *  layer (the core never reads clocks directly, so the
     *  harness can script time exactly). */
    public interface TimeSource {
        String bootId();
        long monoMs();
        long threadCpuMs();
    }

    public static final long SLICE_MAX_CPU_MS = 2_000;
    /** Upper sanity bound for one slice's settled CPU (I-16):
     *  far above any real overshoot (one checkpoint's
     *  recomputation past the slice limit), so it only ever
     *  binds on a nonsensical CPU-clock reading. */
    public static final long SETTLE_SANITY_MAX_MS = 60_000;
    public static final long WINDOW_MS = 3_600_000;
    public static final long WINDOW_BUDGET_MS = 600_000;

    // ---------------- budget ledger ----------------

    public static final class BudgetLedger {
        public static final String FILE_NAME = "verification-budget.json";
        private static final String FORMAT =
                "quantus-depin-verification-budget";

        private final File file;
        private final TimeSource time;
        private String bootId;
        private long lastStampMonoMs;
        private final List<Map<String, Object>> entries = new ArrayList<>();

        public BudgetLedger(File storeRoot, TimeSource time)
                throws IOException {
            this.file = new File(storeRoot, FILE_NAME);
            this.time = time;
            load();
            recoverUnsettled();
        }

        public File file() {
            return file;
        }

        private void load() throws IOException {
            bootId = time.bootId();
            lastStampMonoMs = time.monoMs();
            if (!file.exists()) return;
            Map<String, Object> root = Json.parseObject(new String(
                    TransferIo.readAll(file, 16L * 1024 * 1024),
                    StandardCharsets.UTF_8));
            if (!FORMAT.equals(root.get("format"))) {
                throw new IOException("verification budget format mismatch");
            }
            bootId = Json.asString(root.get("boot_id"));
            lastStampMonoMs = Json.asLong(root.get("last_stamp_mono_ms"));
            Object raw = root.get("entries");
            if (raw instanceof List) {
                for (Object o : Json.asArray(raw)) {
                    entries.add(Json.asObject(o));
                }
            }
        }

        private void persist() throws IOException {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("format", FORMAT);
            root.put("version", 1L);
            root.put("boot_id", bootId);
            root.put("last_stamp_mono_ms", lastStampMonoMs);
            root.put("entries", new ArrayList<>(entries));
            TransferIo.writeRecordAtomic(
                    file, Json.canonicalBytes(root));
        }

        /**
         * Advance the durable stamps to now, accumulating
         * measured elapsed time into every entry (and into
         * outstanding waits), then persist. Called on every
         * budget judgement (this is also the stamping trigger
         * while verification is suppressed: re-judging the
         * budget stamps the ledger).
         */
        public synchronized void stamp() throws IOException {
            long now = time.monoMs();
            String boot = time.bootId();
            long delta = 0;
            if (boot.equals(bootId)) {
                delta = Math.max(0, now - lastStampMonoMs);
            } else {
                // New boot: re-anchor. The unmeasured interval
                // counts as zero elapsed (conservative side).
                bootId = boot;
            }
            lastStampMonoMs = now;
            if (delta > 0) {
                for (Map<String, Object> e : entries) {
                    e.put("elapsed_accum_ms",
                            Json.asLong(e.get("elapsed_accum_ms")) + delta);
                    if (Boolean.TRUE.equals(e.get("settled"))) {
                        e.put("wait_accum_ms",
                                Json.asLong(e.get("wait_accum_ms")) + delta);
                    }
                }
            }
            prune();
            persist();
        }

        private void prune() {
            entries.removeIf(e ->
                    Json.asLong(e.get("elapsed_accum_ms")) >= WINDOW_MS
                            && waitFulfilled(e));
        }

        private boolean waitFulfilled(Map<String, Object> e) {
            return Json.asLong(e.get("wait_accum_ms"))
                    >= Json.asLong(e.get("wait_required_ms"));
        }

        /** Recovery: any reservation that was never settled
         *  counts in full, from now. */
        private void recoverUnsettled() throws IOException {
            boolean changed = false;
            for (Map<String, Object> e : entries) {
                if (!Boolean.TRUE.equals(e.get("settled"))) {
                    e.put("cpu_consumed_ms",
                            Json.asLong(e.get("cpu_reserved_ms")));
                    e.put("settled", Boolean.TRUE);
                    e.put("elapsed_accum_ms", 0L);
                    e.put("wait_accum_ms", 0L);
                    changed = true;
                }
            }
            if (changed) {
                stamp();
            }
        }

        /** Window sum per the design: settled consumption plus
         *  unsettled reservations, aged by accumulated elapsed. */
        public synchronized long windowUsedMs() {
            long sum = 0;
            for (Map<String, Object> e : entries) {
                if (Json.asLong(e.get("elapsed_accum_ms")) >= WINDOW_MS) {
                    continue;
                }
                if (Boolean.TRUE.equals(e.get("settled"))) {
                    sum += Json.asLong(e.get("cpu_consumed_ms"));
                } else {
                    sum += Json.asLong(e.get("cpu_reserved_ms"));
                }
            }
            return sum;
        }

        public synchronized long remainingMs() {
            return Math.max(0, WINDOW_BUDGET_MS - windowUsedMs());
        }

        /** True when some settled entry still owes idle time. */
        public synchronized boolean waitOutstanding() {
            for (Map<String, Object> e : entries) {
                if (Boolean.TRUE.equals(e.get("settled"))
                        && !waitFulfilled(e)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Reserve one slice (max CPU + max wait) in a single
         * atomic update. Returns the reservation entry, or null
         * when the remaining budget cannot cover the maximum.
         */
        public synchronized Map<String, Object> reserve()
                throws IOException {
            stamp();
            if (remainingMs() < SLICE_MAX_CPU_MS) return null;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("cpu_reserved_ms", SLICE_MAX_CPU_MS);
            e.put("cpu_consumed_ms", 0L);
            e.put("wait_required_ms", SLICE_MAX_CPU_MS);
            e.put("wait_accum_ms", 0L);
            e.put("elapsed_accum_ms", 0L);
            e.put("settled", Boolean.FALSE);
            entries.add(e);
            persist();
            return e;
        }

        /** Settle a reservation with the actual CPU consumed;
         *  the wait owed becomes the slice's actual length.
         *  Settlement is idempotent (a settled entry is never
         *  re-charged). */
        public synchronized void settle(Map<String, Object> reservation,
                                        long actualCpuMs)
                throws IOException {
            if (Boolean.TRUE.equals(reservation.get("settled"))) {
                return;
            }
            // Final check round 4 (I-16): the BUDGET is
            // charged what the slice ACTUALLY spent. The slice
            // loop can only stop between checkpoints, so one
            // checkpoint's recomputation routinely carries it
            // past the 2 s it reserved; clipping the charge
            // to the reservation hid that overshoot from the
            // hourly budget. The reservation stays the
            // admission test (2 s must be available to
            // start); the settlement is the truth, bounded
            // only by a sanity limit against a broken CPU
            // clock. The idle owed keeps its cap at the slice
            // limit on purpose: the app layers absorb a wait
            // pause with one sleep of exactly that cap plus a
            // margin, and a longer obligation would make
            // every overshooting slice cost one more
            // re-issued stage — more unledgered work, not
            // less.
            reservation.put("cpu_consumed_ms", Math.max(0,
                    Math.min(actualCpuMs, SETTLE_SANITY_MAX_MS)));
            reservation.put("wait_required_ms",
                    Math.max(0, Math.min(actualCpuMs, SLICE_MAX_CPU_MS)));
            reservation.put("wait_accum_ms", 0L);
            reservation.put("elapsed_accum_ms", 0L);
            reservation.put("settled", Boolean.TRUE);
            // Settlement restarts this entry's stamp baseline so
            // its age counts from completion, not reservation.
            lastStampMonoMs = time.monoMs();
            bootId = time.bootId();
            persist();
        }
    }

    // ---------------- resume cursor ----------------

    public static final class Cursor {
        public static final String FILE_NAME = "verification-cursor.json";
        private static final String FORMAT =
                "quantus-depin-verification-cursor";

        public final File file;
        public String packageSha256;
        public int verifiedCount;
        public byte[] prevState;

        private Cursor(File file) {
            this.file = file;
        }

        /** Load the cursor in a staging root, or null when it
         *  does not match the given package (a cursor for a
         *  different package is not progress on this one). */
        public static Cursor load(File stagingRoot, String packageSha256)
                throws IOException {
            File f = new File(stagingRoot, FILE_NAME);
            if (!f.exists()) return null;
            Map<String, Object> m = Json.parseObject(new String(
                    TransferIo.readAll(f, 1024 * 1024),
                    StandardCharsets.UTF_8));
            if (!FORMAT.equals(m.get("format"))) return null;
            if (!packageSha256.equals(m.get("package_sha256"))) {
                return null;
            }
            Cursor c = new Cursor(f);
            c.packageSha256 = packageSha256;
            c.verifiedCount = (int) Json.asLong(m.get("verified_count"));
            c.prevState = Hex.decode(Json.asString(m.get("prev_state")));
            return c;
        }

        public static Cursor fresh(File stagingRoot, String packageSha256) {
            Cursor c = new Cursor(new File(stagingRoot, FILE_NAME));
            c.packageSha256 = packageSha256;
            c.verifiedCount = 0;
            c.prevState = DepinCore.ZERO32.clone();
            return c;
        }

        public void persist() throws IOException {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("format", FORMAT);
            m.put("version", 1L);
            m.put("package_sha256", packageSha256);
            m.put("verified_count", (long) verifiedCount);
            m.put("prev_state", Hex.encode(prevState));
            TransferIo.writeRecordAtomic(file, Json.canonicalBytes(m));
        }

        public void delete() {
            // Best-effort: a stale cursor is invalidated by its
            // package binding, never silently reused.
            // (Deletion is a cleanup step, not a safety step.)
            if (file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    // ---------------- sliced chain re-verifier ----------------

    public enum SliceOutcome {
        PROGRESS, DONE, PAUSED_BUDGET, PAUSED_WAIT, MISMATCH
    }

    /**
     * Recomputes the sequential chain of a parsed journal in
     * CPU-bounded slices, resuming from the cursor. One slice:
     * reserve → run until the slice's CPU budget is spent (or
     * the chain ends) → settle → persist cursor. Callers loop
     * step() with real idle time between calls (the duty cycle
     * is enforced by the ledger's wait obligation, not by the
     * caller sleeping inside a slice).
     */
    public static final class ChainVerifier {
        private final JournalFile.Parsed journal;
        private final Cursor cursor;
        private final BudgetLedger budget;
        private final TimeSource time;
        private boolean mismatch = false;

        public ChainVerifier(JournalFile.Parsed journal, Cursor cursor,
                             BudgetLedger budget, TimeSource time) {
            this.journal = journal;
            this.cursor = cursor;
            this.budget = budget;
            this.time = time;
            if (cursor.verifiedCount > journal.count()) {
                throw new IllegalArgumentException(
                        "cursor is ahead of the journal it claims to verify");
            }
            // Re-derive the resume state when the cursor did not
            // carry one consistent with the journal prefix is
            // unnecessary: prev_state in the cursor IS the state
            // after verifiedCount checkpoints by construction.
        }

        public boolean isDone() {
            return !mismatch && cursor.verifiedCount >= journal.count();
        }

        public boolean isMismatch() {
            return mismatch;
        }

        public SliceOutcome step() throws IOException {
            if (mismatch) return SliceOutcome.MISMATCH;
            if (isDone()) return SliceOutcome.DONE;
            budget.stamp();
            if (budget.waitOutstanding()) {
                return SliceOutcome.PAUSED_WAIT;
            }
            Map<String, Object> reservation = budget.reserve();
            if (reservation == null) {
                return SliceOutcome.PAUSED_BUDGET;
            }
            long cpuStart = time.threadCpuMs();
            int i = cursor.verifiedCount;
            byte[] prevState = cursor.prevState;
            SliceOutcome outcome = SliceOutcome.PROGRESS;
            try {
                while (i < journal.count()) {
                    DepinCore.Checkpoint c = journal.checkpoints.get(i);
                    long slot = c.timestampUnix / DepinCore.SLOT_SEC;
                    long fin = (c.finalizedBlockNumber == null)
                            ? -1 : c.finalizedBlockNumber;
                    byte[] recomputed = DepinCore.sequentialChain(
                            journal.profile, prevState, c.bestBlockHash,
                            c.seq, c.bestBlockNumber, c.iterations,
                            journal.pubkey, slot, fin, c.observedPeers);
                    if (!java.util.Arrays.equals(recomputed, c.chainState)) {
                        mismatch = true;
                        outcome = SliceOutcome.MISMATCH;
                        break;
                    }
                    prevState = c.chainState;
                    i++;
                    if (time.threadCpuMs() - cpuStart >= SLICE_MAX_CPU_MS) {
                        break;
                    }
                }
            } finally {
                long used = time.threadCpuMs() - cpuStart;
                budget.settle(reservation, Math.max(used, 0));
            }
            if (!mismatch) {
                cursor.verifiedCount = i;
                cursor.prevState = prevState;
                cursor.persist();
                if (i >= journal.count()) {
                    outcome = SliceOutcome.DONE;
                }
            }
            return outcome;
        }
    }
}
