package app.quantusnode.node.depin;

import app.quantusnode.node.depin.core.Json;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The RPC attempt budget log (integration design section 2.3):
 * the persistent record that makes "at most 12 RPC attempts per
 * 600 s slot" hold across service stops and process death.
 *
 * A checkpoint journal alone cannot carry this budget: attempts
 * that fail, or that never reach a checkpoint, leave no journal
 * entry, so without this log every restart would silently refund
 * the slot's budget. Instead, every attempt is recorded HERE,
 * before it is made (see consume), with an fsync, one JSON object
 * per line:
 *
 *   {"slot":<long>,"seq":<long>,"method":"<name>","result":"started"}
 *
 * Interpretation of "result", stated once and applied
 * consistently: consumption is recorded at attempt START, so the
 * only value ever written is "started". This log answers exactly
 * one question — how much of this slot's budget has been spent —
 * and the pre-recording is the point: an attempt that is about to
 * happen is already paid for, even if the process dies during it.
 * What the attempt produced is not this log's business; outcomes
 * live in the checkpoint journal and the status display.
 *
 * Retention window: only the two newest slots are kept — the
 * greatest slot ever recorded (maxSlot) and the one before it.
 * Pruning is keyed to that recorded maximum, never to the wall
 * clock, so a clock moving backwards cannot rewind the window.
 * Symmetrically, ANY slot behind the recorded maximum
 * (slot < maxSlot) is refused at consume() time with
 * KIND_STALE_SLOT, and the observer's tick skips such a slot on
 * the same condition: while the clock sits behind the maximum,
 * rounds are simply not run. G-11 (final check round 2) made
 * the writer's rule match the reader's: consume() used to admit
 * the previous slot (maxSlot - 1) even after the maximum had
 * records, appending its row AFTER them — a slot order the
 * loader itself calls corrupt, so the app's own legal writes
 * wedged the log at the next start. The two-slot RECORDS are
 * still kept (they answer "was this slot's round already paid
 * for" across restarts); what changed is only that no NEW row
 * may be appended behind the maximum.
 *
 * Corruption fails CLOSED. The file is strictly validated at
 * load; any violation, and any read failure, puts the log in the
 * corrupt state, and consume() then refuses everything with
 * KIND_CORRUPT. The budget is never silently reset. The only way
 * back is the explicit maintenance operation resetCorrupt(),
 * which preserves the corrupt bytes under a new name for
 * inspection; that operation is for a user-confirmed repair path
 * only, never for normal control flow.
 *
 * One damage shape is NOT corruption (G-11): a torn final line —
 * the unterminated, unparseable remnant of an append that power
 * loss interrupted. When its slot can be recovered from the
 * surviving prefix (the writer's field order is fixed), the line
 * counts as the one started attempt it was: a complete record
 * for it replaces the remnant in place during the load, so the
 * budget it spent is never refunded, and the repair is durable
 * across restarts. A prefix too damaged to name its slot still
 * fails closed, like any other violation.
 */
public final class RpcAttemptLog {

    /** Hard cap on recorded attempts in any one slot. */
    public static final int MAX_ATTEMPTS_PER_SLOT = 12;

    /** Hard cap on the log file's size at load (final check
     *  round 3): two retained slots of at most
     *  MAX_ATTEMPTS_PER_SLOT short rows each is a few KB;
     *  1 MiB bounds the read without ever binding a
     *  legitimate file. */
    private static final long MAX_LOG_BYTES = 1024L * 1024;

    // AttemptLogException kind aliases, for callers that hold
    // only this class. The canonical definitions live on the
    // exception itself.
    public static final int KIND_CORRUPT = AttemptLogException.KIND_CORRUPT;
    public static final int KIND_BUDGET = AttemptLogException.KIND_BUDGET;
    public static final int KIND_STALE_SLOT =
            AttemptLogException.KIND_STALE_SLOT;
    public static final int KIND_IO = AttemptLogException.KIND_IO;

    private static final Set<String> KNOWN_METHODS = new HashSet<>(
            Arrays.asList(
                    "system_syncState",
                    "chain_getBlockHash",
                    "chain_getFinalizedHead",
                    "chain_getHeader",
                    "system_health",
                    "system_localPeerId"));

    private static final String RESULT_STARTED = "started";

    /** Method value carried ONLY by a record the torn-tail
     *  repair synthesises (G-11): the attempt was started —
     *  that much the surviving prefix proves — but its record
     *  tore before the method was durable, so the repair
     *  states exactly that instead of inventing one. Load
     *  validation accepts it; consume() never does (it is not
     *  in KNOWN_METHODS, so it can never be charged as a real
     *  call). */
    private static final String RECOVERED_TORN_METHOD =
            "recovered_torn";

    /** One retained line: its slot, and the raw line text (kept
     *  verbatim so a pruning rewrite reproduces the surviving
     *  records byte for byte). */
    private static final class Row {
        final long slot;
        final String raw;

        Row(long slot, String raw) {
            this.slot = slot;
            this.raw = raw;
        }
    }

    private final File file;
    private final List<Row> rows = new ArrayList<>();
    private long maxSlot = -1;
    private boolean corrupt = false;
    private String corruptDetail = null;
    /** True when the file on disk does not end with a newline
     *  (its last line is complete and valid, but unterminated):
     *  the next append must terminate that line first instead of
     *  gluing the new record onto it. */
    private boolean appendNeedsNewline = false;
    /** True when this load repaired a torn final line in place
     *  (G-11): the remnant counted as its one started attempt
     *  and was replaced by a complete record for it. */
    private boolean tornTailRecovered = false;

    /** The outcome of a torn-tail recovery: the slot and seq
     *  the torn record provably held, and the complete record
     *  that replaces its remnant in the file. */
    private static final class TornRecovery {
        final long slot;
        final long seq;
        final String canonicalLine;

        TornRecovery(long slot, long seq, String canonicalLine) {
            this.slot = slot;
            this.seq = seq;
            this.canonicalLine = canonicalLine;
        }
    }

    /**
     * Load and strictly validate the log at the given file. A
     * missing or empty file is a healthy empty log. Anything else
     * that does not check out — an unparseable line, a slot/seq
     * that is not a JSON integer, an unknown method, a result
     * other than "started", slots going backwards, a per-slot seq
     * that is not the consecutive run 1..n, more attempts in one
     * slot than the budget allows, or a read failure — leaves the
     * log in the corrupt state, with the detail kept for the
     * status display. On corruption the partially read records
     * are discarded wholesale: a budget record that cannot be
     * trusted in full cannot be trusted in part. The single
     * exception is the torn final line (an unterminated last line
     * that does not parse — an append interrupted by power
     * loss): when its slot is recoverable from the surviving
     * prefix it is counted and repaired in place instead
     * (G-11; see the class comment).
     */
    public RpcAttemptLog(File file) {
        this.file = file;
        if (file == null || !file.exists()) {
            return;
        }
        List<String> lines = new ArrayList<>();
        boolean endsNl;
        try {
            // Bounded read (final check round 3, H-04/H-13):
            // the design's 1 MiB per-line cap (section 3.4)
            // did not apply to this file — the loader used
            // BufferedReader.readLine(), so a single
            // multi-megabyte "line" was materialised whole
            // before validation could reject it. The whole
            // file is now read under a hard cap (a legitimate
            // log holds at most two slots × 12 short rows —
            // a few KB; 1 MiB is headroom, not a target) and
            // split here; an over-cap file fails closed like
            // any other violation.
            byte[] rawBytes = app.quantusnode.node.depin.core
                    .TransferIo.readAll(file, MAX_LOG_BYTES);
            endsNl = rawBytes.length == 0
                    || rawBytes[rawBytes.length - 1] == (byte) '\n';
            appendNeedsNewline = rawBytes.length > 0 && !endsNl;
            String content = new String(rawBytes, StandardCharsets.UTF_8);
            int start = 0;
            for (int i = 0; i < content.length(); i++) {
                if (content.charAt(i) == '\n') {
                    String line = content.substring(start, i);
                    if (line.endsWith("\r")) {
                        line = line.substring(0, line.length() - 1);
                    }
                    lines.add(line);
                    start = i + 1;
                }
            }
            if (start < content.length()) {
                String line = content.substring(start);
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                lines.add(line);
            }
        } catch (IOException e) {
            markCorrupt("read failed: " + e.getMessage());
            return;
        }
        List<Row> loaded = new ArrayList<>();
        long loadedMax = -1;
        long currentSlot = 0;
        long expectedSeq = 1;
        boolean haveSlot = false;
        TornRecovery torn = null;
        String tornRawLine = null;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            long lineNo = i + 1L;
            String detail = null;
            long slot = 0;
            long seq = 0;
            Map<String, Object> obj = null;
            boolean parseFailed = false;
            try {
                obj = Json.parseObject(line);
            } catch (RuntimeException e) {
                parseFailed = true;
                detail = "line " + lineNo + ": not a JSON object ("
                        + e.getMessage() + ")";
            }
            if (detail == null) {
                Object slotValue = obj.get("slot");
                Object seqValue = obj.get("seq");
                Object methodValue = obj.get("method");
                Object resultValue = obj.get("result");
                // JSON integers only: the parser yields
                // Long for integral numbers, so a Double
                // (or a string, or a missing key) here is
                // a format violation, not a value to be
                // coerced.
                if (!(slotValue instanceof Long)
                        || !(seqValue instanceof Long)) {
                    detail = "line " + lineNo
                            + ": slot/seq is not a JSON integer";
                } else if (!(methodValue instanceof String)
                        || !(KNOWN_METHODS.contains(methodValue)
                                || RECOVERED_TORN_METHOD
                                        .equals(methodValue))) {
                    detail = "line " + lineNo + ": unknown method";
                } else if (!RESULT_STARTED.equals(resultValue)) {
                    detail = "line " + lineNo
                            + ": result is not \"started\"";
                } else {
                    slot = (Long) slotValue;
                    seq = (Long) seqValue;
                }
            }
            if (detail == null) {
                if (haveSlot && slot < currentSlot) {
                    detail = "line " + lineNo
                            + ": slots are not in non-decreasing order";
                } else if (!haveSlot || slot != currentSlot) {
                    if (seq != 1L) {
                        detail = "line " + lineNo
                                + ": first seq in a slot is not 1";
                    }
                } else if (seq != expectedSeq) {
                    detail = "line " + lineNo
                            + ": seq is not consecutive within its slot";
                } else if (seq > MAX_ATTEMPTS_PER_SLOT) {
                    detail = "line " + lineNo
                            + ": slot holds more attempts than the budget";
                }
            }
            if (detail != null) {
                // G-11 torn-tail exception: the FINAL line of
                // a file that does not end with a newline,
                // failing only at the JSON parse, is the
                // remnant of an append interrupted mid-write —
                // not damage to a settled record. When the
                // prefix still proves its slot, the attempt
                // counts and the line is repaired in place
                // (after the loop); anything else about the
                // failure keeps the corrupt verdict.
                TornRecovery rec = null;
                if (parseFailed && i == lines.size() - 1 && !endsNl) {
                    rec = recoverTornTail(line, haveSlot,
                            currentSlot, expectedSeq);
                }
                if (rec == null) {
                    markCorrupt(detail);
                    return;
                }
                torn = rec;
                tornRawLine = line;
                slot = rec.slot;
                seq = rec.seq;
                line = rec.canonicalLine;
                detail = null;
            }
            if (!haveSlot || slot != currentSlot) {
                currentSlot = slot;
                haveSlot = true;
            }
            expectedSeq = seq + 1;
            loaded.add(new Row(slot, line));
            if (slot > loadedMax) {
                loadedMax = slot;
            }
        }
        rows.addAll(loaded);
        maxSlot = loadedMax;
        if (torn != null) {
            // Replace the remnant with the complete record in
            // the file itself, so the count is durable. The
            // repair rewrites the whole file atomically
            // (tmp + fsync + rename): the earlier
            // truncate-then-append shape had a window in
            // which a second crash left a SHORTER file whose
            // settled rows were intact but whose torn attempt
            // had silently vanished — a one-attempt refund in
            // exactly the crash window G-11 exists to close
            // (final check round 3, H-04). A repair that
            // cannot be made durable fails closed like any
            // other violation — the in-memory count alone
            // would refund the attempt at the next start.
            if (!repairTornTail(loaded)) {
                markCorrupt("torn-tail repair could not be "
                        + "made durable");
                return;
            }
            tornTailRecovered = true;
            appendNeedsNewline = false;
        }
    }

    /**
     * Recover a torn final line, or return null when the
     * prefix does not prove what it was. The writer's format
     * is fixed — {"slot":S,"seq":Q,"method":"M","result":
     * "started"} — so a genuine tear is a PREFIX of such a
     * line, and since final check round 3 (H-04) that is
     * exactly what is verified: from the (slot, seq) position
     * the prefix claims, the candidate canonical lines are
     * rebuilt for every method the writer can emit (the six
     * known methods, plus the recovered-torn marker a
     * previous repair may itself have written), and the torn
     * line is accepted ONLY when it is a prefix of at least
     * one candidate. The previous recovery checked the
     * fields piecemeal and accepted lines no writer could
     * have produced — a non-numeric seq, garbage after a
     * complete record, a missing seq field — as long as the
     * slot digits survived; one accepted shape
     * ({"slot":999999999999,…) also let a single crafted
     * line poison maxSlot so every later real slot read as
     * stale and recording stopped with no corruption shown.
     *
     * The slot itself is bounded before any candidate is
     * built: a continuation of the current slot, or the
     * first row of a LATER slot the wall clock has actually
     * reached (slot ≤ current wall slot + 1) — a torn append
     * happens during a live round, and rounds run at the
     * wall clock's slot, so a slot beyond that cannot be a
     * genuine tear. The seq position follows the slot: the
     * next seq of the current slot, or 1 for a new slot.
     * The recovered method is the one candidate the prefix
     * determines uniquely; a prefix consistent with several
     * candidates (a tear before or inside the method value
     * that several methods share) is recorded with
     * RECOVERED_TORN_METHOD, exactly the fact the prefix
     * proves: an attempt started, its method is not
     * determinable.
     */
    private TornRecovery recoverTornTail(String line,
            boolean haveSlot, long currentSlot, long expectedSeq) {
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) > 0x7F) {
                return null;
            }
        }
        String slotKey = "{\"slot\":";
        if (!line.startsWith(slotKey)) {
            return null;
        }
        int p = slotKey.length();
        int digitStart = p;
        while (p < line.length()
                && Character.isDigit(line.charAt(p))) {
            p++;
        }
        if (p == digitStart || p >= line.length()
                || line.charAt(p) != ',') {
            return null; // the slot itself is torn or malformed
        }
        final long slot;
        try {
            slot = Long.parseLong(line.substring(digitStart, p));
        } catch (NumberFormatException e) {
            return null;
        }
        long wallSlot = System.currentTimeMillis() / 1000L
                / app.quantusnode.node.depin.core.DepinCore.SLOT_SEC;
        final long seq;
        if (haveSlot && slot == currentSlot) {
            seq = expectedSeq;
            if (seq > MAX_ATTEMPTS_PER_SLOT) {
                return null; // consume() would have refused it
            }
        } else if (slot > (haveSlot ? currentSlot : -1)
                && slot <= wallSlot + 1) {
            seq = 1L;
        } else {
            return null; // behind the tail, or beyond the
            // wall clock's reach: never a writable position
        }
        // Candidate-prefix validation (H-04): the torn line
        // must be a prefix of a line the writer can emit for
        // this (slot, seq).
        Set<String> candidates = new HashSet<>(KNOWN_METHODS);
        candidates.add(RECOVERED_TORN_METHOD);
        String determined = null;
        int matches = 0;
        for (String method : candidates) {
            String canonical = "{\"slot\":" + slot
                    + ",\"seq\":" + seq
                    + ",\"method\":\"" + method + "\""
                    + ",\"result\":\"" + RESULT_STARTED + "\"}";
            if (canonical.startsWith(line)) {
                matches++;
                determined = method;
            }
        }
        if (matches == 0) {
            return null;
        }
        String method = matches == 1
                ? determined : RECOVERED_TORN_METHOD;
        String canonical = "{\"slot\":" + slot
                + ",\"seq\":" + seq
                + ",\"method\":\"" + method + "\""
                + ",\"result\":\"" + RESULT_STARTED + "\"}";
        return new TornRecovery(slot, seq, canonical);
    }

    /** Replace the file with the loaded rows (whose last row
     *  is the repaired canonical record), atomically: tmp +
     *  fsync + rename + directory fsync (H-04). The rename
     *  leaves either the whole old file or the whole repaired
     *  one; there is no intermediate shorter state. */
    private boolean repairTornTail(List<Row> loaded) {
        File parent = file.getAbsoluteFile().getParentFile();
        File tmp = new File(parent, file.getName() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                for (Row row : loaded) {
                    out.write((row.raw + "\n")
                            .getBytes(StandardCharsets.UTF_8));
                }
                out.flush();
                out.getFD().sync();
            }
            if (!tmp.renameTo(file)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return false;
            }
            app.quantusnode.node.depin.core.TransferIo.fsyncDir(parent);
            return true;
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
    }

    /** True when this load repaired a torn final line in
     *  place (G-11): the remnant counted as its one started
     *  attempt instead of corrupting the log. */
    public synchronized boolean tornTailRecovered() {
        return tornTailRecovered;
    }

    private static boolean endsWithNewline(File f) throws IOException {
        try (java.io.RandomAccessFile raf =
                     new java.io.RandomAccessFile(f, "r")) {
            raf.seek(raf.length() - 1);
            return raf.read() == '\n';
        }
    }

    private void markCorrupt(String detail) {
        corrupt = true;
        corruptDetail = detail;
        rows.clear();
        maxSlot = -1;
        appendNeedsNewline = false;
    }

    /** True when the log failed validation (or a later write
     *  failed) and is refusing all consumption. */
    public synchronized boolean isCorrupt() {
        return corrupt;
    }

    /** Why the log is corrupt, or null when it is healthy. */
    public synchronized String corruptDetail() {
        return corruptDetail;
    }

    /** The greatest slot with any record, or -1 when the log is
     *  empty (including in the corrupt state, where no recorded
     *  maximum can be trusted). */
    public synchronized long maxSlot() {
        return maxSlot;
    }

    /** Attempts already recorded in the given slot. */
    public synchronized int attemptsInSlot(long slot) {
        int n = 0;
        for (Row row : rows) {
            if (row.slot == slot) {
                n++;
            }
        }
        return n;
    }

    /** Whether any attempt is recorded in the given slot. */
    public synchronized boolean hasAttempts(long slot) {
        return attemptsInSlot(slot) > 0;
    }

    /**
     * Record the consumption of one attempt in the given slot,
     * durably, BEFORE the attempt is made. On return the record
     * is flushed and fsynced, so a process death from this point
     * on cannot refund the attempt.
     *
     * Order of checks, deliberately: corruption first (nothing is
     * trusted), then the stale-slot rule (a slot behind the
     * recorded maximum starts no round at all), then the window
     * advance for a newer slot, then the per-slot budget.
     *
     * @throws AttemptLogException KIND_CORRUPT when the log is
     *         corrupt, KIND_STALE_SLOT when the slot is behind
     *         the recorded maximum, KIND_BUDGET when the
     *         slot's budget is already spent, KIND_IO when the
     *         record could not be made durable (the log is then
     *         corrupt from this point on — the on-disk state can
     *         no longer be vouched for, so it fails closed)
     * @throws IllegalArgumentException when the method is not one
     *         of the six known RPC methods — writing a record this
     *         class would reject on its own next load is a caller
     *         bug, not a runtime condition
     */
    public synchronized void consume(long slot, String method)
            throws AttemptLogException {
        if (method == null || !KNOWN_METHODS.contains(method)) {
            throw new IllegalArgumentException("unknown RPC method: " + method);
        }
        if (corrupt) {
            throw new AttemptLogException(AttemptLogException.KIND_CORRUPT,
                    "attempt log is corrupt: " + corruptDetail);
        }
        if (maxSlot >= 0 && slot < maxSlot) {
            // G-11: ANY slot behind the recorded maximum is
            // refused, not just one behind the retention window.
            // Rows are appended in call order, so admitting the
            // previous slot after the maximum has records would
            // write a file the loader itself calls corrupt.
            // While the clock sits behind the maximum, rounds
            // are skipped (the tick applies the same condition
            // before it ever gets here).
            throw new AttemptLogException(AttemptLogException.KIND_STALE_SLOT,
                    "slot " + slot + " is behind the recorded maximum"
                            + " (max slot " + maxSlot + ")");
        }
        if (slot > maxSlot) {
            advanceWindow(slot);
        }
        if (attemptsInSlot(slot) >= MAX_ATTEMPTS_PER_SLOT) {
            throw new AttemptLogException(AttemptLogException.KIND_BUDGET,
                    "attempt budget for slot " + slot + " is spent ("
                            + MAX_ATTEMPTS_PER_SLOT + ")");
        }
        long seq = attemptsInSlot(slot) + 1L;
        String line = "{\"slot\":" + slot
                + ",\"seq\":" + seq
                + ",\"method\":\"" + method + "\""
                + ",\"result\":\"" + RESULT_STARTED + "\"}";
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            if (appendNeedsNewline) {
                out.write('\n');
            }
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } catch (IOException e) {
            String detail = "append failed: " + e.getMessage();
            markCorrupt(detail);
            throw new AttemptLogException(
                    AttemptLogException.KIND_IO, detail);
        }
        appendNeedsNewline = false;
        rows.add(new Row(slot, line));
        if (slot > maxSlot) {
            maxSlot = slot;
        }
    }

    /**
     * Advance the retention window to a newer slot: only records
     * for the new slot and the one before it survive. (There can
     * be no records for the new slot itself yet — it is newer
     * than every recorded slot — so in practice this drops every
     * slot older than slot - 1.) The rewrite goes to a tmp file,
     * is fsynced, and is renamed over the original, so a crash
     * mid-prune leaves either the whole old log or the whole new
     * one, never a torn mixture. When nothing would be dropped
     * the file is left untouched.
     */
    private void advanceWindow(long slot) throws AttemptLogException {
        List<Row> kept = new ArrayList<>();
        for (Row row : rows) {
            if (row.slot == slot || row.slot == slot - 1) {
                kept.add(row);
            }
        }
        if (kept.size() != rows.size()) {
            File parent = file.getAbsoluteFile().getParentFile();
            File tmp = new File(parent, file.getName() + ".tmp");
            try {
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    for (Row row : kept) {
                        out.write((row.raw + "\n")
                                .getBytes(StandardCharsets.UTF_8));
                    }
                    out.flush();
                    out.getFD().sync();
                }
                if (!tmp.renameTo(file)) {
                    throw new IOException("could not replace attempt log");
                }
                // Final check round 4 (I-15): the rename is
                // made durable like the torn-tail repair's
                // (H-04). The record appended next is fsynced
                // into the NEW file; without the directory
                // sync a power loss could bring the old file
                // back under this name and take that record —
                // a paid attempt — with it.
                app.quantusnode.node.depin.core.TransferIo
                        .fsyncDir(parent);
            } catch (IOException e) {
                // The prune is part of making consumption durable:
                // if it cannot be completed, the on-disk state is
                // no longer the state this object vouches for, so
                // the log fails closed like any other write error.
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                String detail = "window advance failed: " + e.getMessage();
                markCorrupt(detail);
                throw new AttemptLogException(
                        AttemptLogException.KIND_IO, detail);
            }
            rows.clear();
            rows.addAll(kept);
            appendNeedsNewline = false;
        }
        maxSlot = slot;
    }

    /**
     * Maintenance only: set the current file aside and start
     * over empty. The file is renamed to
     * "rpc-attempts.jsonl.corrupt" next to the original (with a
     * "-1", "-2", ... suffix when that name is taken), its bytes
     * preserved exactly for inspection, and this log's state is
     * reset to a healthy empty log.
     *
     * This is the ONLY path that resets a spent or unreadable
     * budget, which is why it is not part of any normal flow: it
     * exists for a user-confirmed repair, and the caller records
     * the fact separately (see DepinSettings.recordAttemptLogReset)
     * so the reset itself is never silent.
     *
     * @return the file the old log was moved to, or null when
     *         there was no file to move or the move failed — on
     *         failure the in-memory state is NOT reset, because
     *         discarding the record while its bytes still sit at
     *         the original path would let the next load resurrect
     *         a state this process claimed to have set aside
     */
    public synchronized File resetCorrupt() {
        if (file == null || !file.exists()) {
            corrupt = false;
            corruptDetail = null;
            rows.clear();
            maxSlot = -1;
            appendNeedsNewline = false;
            return null;
        }
        File parent = file.getAbsoluteFile().getParentFile();
        File dest = new File(parent, "rpc-attempts.jsonl.corrupt");
        int n = 1;
        while (dest.exists()) {
            dest = new File(parent,
                    "rpc-attempts.jsonl.corrupt-" + (n++));
        }
        if (!file.renameTo(dest)) {
            return null;
        }
        corrupt = false;
        corruptDetail = null;
        rows.clear();
        maxSlot = -1;
        appendNeedsNewline = false;
        return dest;
    }

    /** Failure of a consume() call, with a machine-readable kind. */
    public static final class AttemptLogException extends Exception {
        /** The log is corrupt; no consumption is possible. */
        public static final int KIND_CORRUPT = 1;
        /** The slot's attempt budget is already spent. */
        public static final int KIND_BUDGET = 2;
        /** The slot is older than the retention window. */
        public static final int KIND_STALE_SLOT = 3;
        /** The record could not be made durable. */
        public static final int KIND_IO = 4;

        public final int kind;

        public AttemptLogException(int kind, String message) {
            super(message);
            this.kind = kind;
        }
    }
}
