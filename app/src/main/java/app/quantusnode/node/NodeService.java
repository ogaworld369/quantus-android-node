package app.quantusnode.node;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that keeps the bundled quantus-node binary resident
 * as a child process and watches it quietly:
 *
 *   - launches the binary from nativeLibraryDir (same execution pattern as
 *     the smoke app, with a /system/bin/linker64 fallback),
 *   - uses the fixed, CLI-verified argument set (pruning always on, RPC
 *     loopback-only — no external RPC option is ever passed). The only
 *     variable elements are the ones the user controls in NodeSettings:
 *     the display name, and whether --no-telemetry is passed (telemetry
 *     is an opt-in, off by default),
 *   - drains stdout/stderr into a size-capped rotating log file,
 *   - polls the local JSON-RPC endpoint (system_health / system_syncState)
 *     every few seconds and mirrors the result into NodeState, the
 *     notification and the on-device track record,
 *   - if the node process exits, records the exit code and stops. It does
 *     NOT restart it in a loop.
 *
 * The app holds no secrets: no wallet, no phrase, nothing but the node's
 * own files under the app-private directory.
 */
public class NodeService extends Service {

    public static final String ACTION_START = "app.quantusnode.node.action.START";
    public static final String ACTION_STOP = "app.quantusnode.node.action.STOP";

    private static final String NODE_LIB = "libquantus_node.so";
    private static final String CHANNEL_ID = "node_status";
    private static final int NOTIF_ID = 1;

    private static final String RPC_URL = "http://127.0.0.1:9944/";
    private static final long POLL_MS = 3000;
    private static final long UPTIME_SAVE_EVERY_MS = 10000;

    /** Log rotation: at ~512 KB the older half is dropped. Never unbounded. */
    private static final long LOG_MAX_BYTES = 512L * 1024L;
    private static final long LOG_CHECK_EVERY_BYTES = 64L * 1024L;

    /** A process that dies faster than this counts as a failed start. */
    private static final long QUICK_EXIT_MS = 2500;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean pollerOn = new AtomicBoolean(false);

    /**
     * Session generation: established when a session STARTS (before any
     * of its threads run) and handed to both the process worker and the
     * RPC poller. Every teardown (stop / finish / destroy / FGS timeout)
     * bumps it again. Any end-of-session write — state, track record,
     * uptime — is made only after re-checking, under {@link #stateLock},
     * that the writer's generation is still the current one, so a
     * delayed worker or poll from a dead session can never overwrite a
     * newer session's STOPPED/SYNCED state or rewind its uptime
     * (bugs P-1, N-2, N-3, N-5).
     */
    private final java.util.concurrent.atomic.AtomicInteger sessionGen =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * The single lock serializing all session lifecycle transitions and
     * end-of-session writes: session establishment, teardown, uptime
     * accounting and the poller's batched state/track writes all happen
     * inside it, so a generation check and the writes it guards are
     * atomic with respect to a concurrent teardown (N-4, N-5, N-7).
     */
    private final Object stateLock = new Object();

    private volatile Process nodeProcess;
    private volatile long sessionStartElapsedMs = -1;
    private volatile long baseUptimeMs = 0;
    private volatile long lastUptimeSaveElapsedMs = 0;
    private volatile long prevBestBlock = -1;
    private volatile String lastNotifText = "";

    private LogSink logSink;
    private Thread workerThread;
    private Thread pollerThread;

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        NodeState.get().serviceRunning = true;
        createNotificationChannel();
        logSink = new LogSink(new File(getFilesDir(), "logs"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopNodeAndSelf();
            return START_NOT_STICKY;
        }
        if (!started.compareAndSet(false, true)) {
            return START_NOT_STICKY; // already running: nothing to do
        }

        // Foreground first, promptly, before any heavy work.
        startForegroundWith(buildNotification(
                NodeState.label(this, NodeState.Status.STARTING)));
        // Establish the session — generation first, before either
        // thread starts — under the lifecycle lock, and hand the
        // generation to BOTH the worker and the poller. Previously the
        // worker ran generation-less and the generation was only fixed
        // after it had started, so a worker that died instantly (or
        // finished late, after a stop + fresh start) wrote state and
        // uptime for a session it did not belong to (N-2, N-3).
        final int gen;
        synchronized (stateLock) {
            gen = sessionGen.incrementAndGet();
            NodeState st = NodeState.get();
            st.status = NodeState.Status.STARTING;
            st.lastExitCode = null;
            st.lastNote = null;
            st.lastAttempts = java.util.Collections.emptyList();
            // No launch snapshot yet for this session: the worker fills
            // it in when the process is actually launched. Until then
            // the UI must not claim a settings/restart mismatch against
            // a previous run.
            st.launchedTelemetry = null;
            st.launchedNodeName = null;
            baseUptimeMs = TrackStore.uptimeMs(this);
            sessionStartElapsedMs = SystemClock.elapsedRealtime();
            lastUptimeSaveElapsedMs = sessionStartElapsedMs;
            prevBestBlock = -1;
        }

        workerThread = new Thread(() -> runNodeProcess(gen), "node-process");
        workerThread.start();
        pollerOn.set(true);
        pollerThread = new Thread(() -> runPoller(gen), "node-rpc-poll");
        pollerThread.start();
        // No hidden restarts: if the system kills us, we stay dead until
        // the user starts the node again.
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        // The whole teardown runs under the lifecycle lock, in B-2
        // order: clear `started` FIRST, then bump the generation, then
        // destroy the process. The worker is blocked in waitFor() and
        // would otherwise read the destroy as a "quick failed start"
        // and retry via linker64 — resurrecting a node the user (or the
        // system) just stopped, with nobody left to kill it (B-2).
        // With the generation bumped under the same lock, the worker's
        // own finish path is generation-stale and writes nothing (N-2).
        synchronized (stateLock) {
            started.set(false);
            sessionGen.incrementAndGet();
            pollerOn.set(false);
            Process p = nodeProcess;
            if (p != null) {
                p.destroy();
            }
            saveUptimeLocked();
            sessionStartElapsedMs = -1; // no live session any more
            NodeState st = NodeState.get();
            st.status = NodeState.Status.STOPPED;
            st.serviceRunning = false;
        }
        super.onDestroy();
    }

    /**
     * Insurance only: specialUse foreground services have no system
     * time cap today, so this is not expected to fire. If the system
     * ever does time the service out (as it does for dataSync on
     * Android 15+), it requires stopSelf() to be called promptly —
     * failing that it throws and takes the whole app down. So: stop
     * the node, record what happened, and end the service cleanly.
     */
    @Override
    public void onTimeout(int startId) {
        handleFgsTimeout();
    }

    /** Android 15 (API 35) reports the timeout with the FGS type too. */
    @Override
    public void onTimeout(int startId, int fgsType) {
        handleFgsTimeout();
    }

    private void handleFgsTimeout() {
        // Same ordering as the B-2 stop paths (N-1): clear `started`
        // BEFORE destroying the process, so the worker cannot read the
        // destroy as a quick failed start and retry it. finishAsStopped
        // then runs the locked, generation-checked teardown for the
        // session that is current right now.
        final int gen = sessionGen.get();
        started.set(false);
        logApp("foreground service timed out by the system; stopping node");
        NodeState.get().lastNote = I18n.get(this, StringsTable.NOTE_FGS_TIMEOUT);
        Process p = nodeProcess;
        if (p != null) {
            p.destroy();
        }
        finishAsStopped(gen, null);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // not a bound service
    }

    private void stopNodeAndSelf() {
        // Same ordering rule as onDestroy(), under the lifecycle lock:
        // clear `started` before destroying the process, so the worker
        // cannot mistake this user-requested stop for a failed start
        // and retry it (B-2); bump the generation so neither an
        // in-flight poll (P-1) nor the worker's delayed finish (N-2)
        // can write state after this stop is settled.
        synchronized (stateLock) {
            started.set(false);
            sessionGen.incrementAndGet();
            pollerOn.set(false);
            Process p = nodeProcess;
            if (p != null) {
                p.destroy();
            }
            saveUptimeLocked();
            sessionStartElapsedMs = -1; // no live session any more
            NodeState.get().status = NodeState.Status.STOPPED;
        }
        stopForeground(true);
        stopSelf();
    }

    // ------------------------------------------------------------------
    // Node process
    // ------------------------------------------------------------------

    private void runNodeProcess(int gen) {
        File bin = new File(getApplicationInfo().nativeLibraryDir, NODE_LIB);
        if (!bin.exists()) {
            logApp("node binary not found: " + bin.getAbsolutePath());
            synchronized (stateLock) {
                if (gen == sessionGen.get()) {
                    NodeState.get().lastNote =
                            I18n.get(this, StringsTable.NOTE_BINARY_MISSING);
                }
            }
            finishAsStopped(gen, null);
            return;
        }
        // App-private dirs only: the node DB / keys stay under filesDir.
        new File(getFilesDir(), "node").mkdirs();

        // This session's per-attempt records, oldest first. Only this
        // worker thread appends; publication into NodeState happens
        // under stateLock with the usual generation check.
        List<NodeState.Attempt> attempts = new ArrayList<>();
        boolean triedLinker = false;
        while (started.get() && gen == sessionGen.get()) {
            Process proc = null;
            long t0 = SystemClock.elapsedRealtime();
            AttemptCapture capture = new AttemptCapture();
            try {
                proc = launch(bin, triedLinker, gen);
            } catch (IOException e) {
                logApp("start failed (" + (triedLinker ? "linker64" : "direct")
                        + "): " + e.getMessage());
                // A launch that never produced a process is still an
                // attempt: record it (no exit code, no output), so the
                // dashboard can show that BOTH methods were tried.
                attempts.add(new NodeState.Attempt(triedLinker,
                        SystemClock.elapsedRealtime() - t0, null,
                        capture.tailLines(), capture.totalLines()));
                if (attempts.size() > 2) {
                    attempts.remove(0);
                }
                publishAttempts(attempts, gen);
                if (!triedLinker && gen == sessionGen.get()) {
                    triedLinker = true;
                    continue; // one fallback attempt, via linker64
                }
                synchronized (stateLock) {
                    if (gen == sessionGen.get()) {
                        NodeState.get().lastNote = I18n.format(
                                this, StringsTable.NOTE_START_FAILED, e.getMessage());
                    }
                }
                finishAsStopped(gen, null);
                return;
            }
            nodeProcess = proc;
            final Process running = proc;
            // N-6 / START-RACE window: the stop side may have run to
            // completion while pb.start() was still creating this
            // process (it saw nodeProcess == null and had nothing to
            // destroy). Re-check immediately after the assignment; if
            // this session is already torn down, kill the newborn
            // process ourselves instead of orphaning it with the DB
            // lock and RPC port held.
            if (!started.get() || gen != sessionGen.get()) {
                logApp("session ended while the node was starting; destroying it");
                nodeProcess = null;
                proc.destroy();
                return;
            }
            logApp("node started (" + (triedLinker ? "via linker64" : "direct") + ")");

            Thread drain = new Thread(() -> drainOutput(running, capture), "node-output");
            drain.setDaemon(true);
            drain.start();

            int code;
            try {
                code = running.waitFor();
            } catch (InterruptedException e) {
                running.destroy();
                return;
            } finally {
                nodeProcess = null;
            }
            long livedMs = SystemClock.elapsedRealtime() - t0;
            logApp("node exited: code=" + code + " after " + livedMs + "ms");
            boolean quickFailure = code != 0 && livedMs < QUICK_EXIT_MS;
            // Let the drain thread flush the node's final lines into
            // this attempt's capture, then record the attempt: the
            // record is the dashboard's evidence, and an exit code
            // alone does not tell the user WHY the start failed (P-6).
            // Per-attempt capture replaces the old file-tail snapshot,
            // in which the retry overwrote the first attempt's record
            // and [app] lines crowded out the node's own output.
            try {
                drain.join(1000);
            } catch (InterruptedException ignored) {
            }
            attempts.add(new NodeState.Attempt(triedLinker, livedMs, code,
                    capture.tailLines(), capture.totalLines()));
            if (attempts.size() > 2) {
                attempts.remove(0);
            }
            synchronized (stateLock) {
                if (gen == sessionGen.get()) {
                    NodeState st = NodeState.get();
                    st.lastExitCode = code;
                    st.lastAttempts = java.util.Collections.unmodifiableList(
                            new ArrayList<>(attempts));
                }
            }

            // Quick, failed start on the direct path: try linker64 once.
            // Anything else is a real exit — record it and stop, no loop.
            if (started.get() && gen == sessionGen.get()
                    && !triedLinker && quickFailure) {
                logApp("quick exit; retrying once via /system/bin/linker64");
                triedLinker = true;
                continue;
            }
            finishAsStopped(gen, code);
            return;
        }
    }

    private Process launch(File bin, boolean viaLinker, int gen) throws IOException {
        String nativeDir = getApplicationInfo().nativeLibraryDir;
        String basePath = new File(getFilesDir(), "node").getAbsolutePath();

        // User settings, read fresh at each launch. Telemetry is an
        // opt-in (default off) and the display name defaults to the
        // generic app name. Changes made while the node is running are
        // picked up by the NEXT start only — a running node is never
        // restarted behind the user's back.
        boolean telemetry = NodeSettings.telemetryEnabled(this);
        String nodeName = NodeSettings.effectiveNodeName(this);

        List<String> cmd = new ArrayList<>();
        if (viaLinker) {
            cmd.add("/system/bin/linker64");
        }
        cmd.add(bin.getAbsolutePath());
        // Fixed argument set (verified against the node's CLI). Pruning is
        // always passed; no --rpc-external / --ws-external, ever, so RPC
        // stays bound to 127.0.0.1. The name travels as a single argv
        // element (no shell involved), and the only other variable is
        // --no-telemetry: present unless the user opted into telemetry,
        // in which case the node reports to the chain spec's built-in
        // official endpoint — the app never sets a telemetry URL itself.
        cmd.add("--chain");         cmd.add("mainnet");
        cmd.add("--base-path");     cmd.add(basePath);
        cmd.add("--name");          cmd.add(nodeName);
        cmd.add("--state-pruning"); cmd.add("256");
        cmd.add("--blocks-pruning"); cmd.add("256");
        cmd.add("--rpc-port");      cmd.add("9944");
        cmd.add("--rpc-methods");   cmd.add("safe");
        if (!telemetry) {
            cmd.add("--no-telemetry");
        }

        // Remember what this process was launched with, so the UI can
        // say "settings changed — restart to apply" when they diverge.
        // Generation-gated like every other state write: a launch that
        // completes after its session was torn down must not stamp its
        // snapshot over a newer session's.
        synchronized (stateLock) {
            if (gen == sessionGen.get()) {
                NodeState st = NodeState.get();
                st.launchedTelemetry = telemetry;
                st.launchedNodeName = nodeName;
            }
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("LD_LIBRARY_PATH", nativeDir);
        pb.redirectErrorStream(true);
        logApp("launch: " + String.join(" ", cmd)
                + " [telemetry " + (telemetry ? "ON (user opt-in)" : "off") + "]");
        return pb.start();
    }

    /**
     * End-of-session teardown for the worker / timeout paths. The
     * whole decision — "is my generation still the live one?" — and
     * every write that follows from it happen inside {@link #stateLock}:
     * if a newer session has already started (or a stop has already
     * settled this one), a delayed finish from the old worker must not
     * overwrite the new session's state with STOPPED, rewind its
     * uptime base, or stopSelf() the service out from under it (N-2).
     * In that case this call does nothing at all.
     */
    private void finishAsStopped(int gen, Integer exitCode) {
        synchronized (stateLock) {
            if (gen != sessionGen.get()) {
                logApp("stale finish ignored (gen " + gen
                        + ", exitCode " + exitCode + ")");
                return;
            }
            // Invalidate this session's generation, so a pollOnce()
            // that is still blocked in an RPC call returns without
            // writing state once this stop is settled (bug P-1).
            sessionGen.incrementAndGet();
            pollerOn.set(false);
            saveUptimeLocked();
            sessionStartElapsedMs = -1; // no live session any more
            NodeState st = NodeState.get();
            st.status = NodeState.Status.STOPPED;
            // bestBlock / highestBlock / peers keep their last known
            // values, so the dashboard can still show where the node
            // got to. lastExitCode was already recorded by the worker.
            started.set(false);
        }
        stopForeground(true);
        stopSelf();
    }

    // ------------------------------------------------------------------
    // Output draining + bounded log file
    // ------------------------------------------------------------------

    /** Publish the session's attempt records into NodeState (locked, gen-checked). */
    private void publishAttempts(List<NodeState.Attempt> attempts, int gen) {
        synchronized (stateLock) {
            if (gen == sessionGen.get()) {
                NodeState.get().lastAttempts =
                        java.util.Collections.unmodifiableList(
                                new ArrayList<>(attempts));
            }
        }
    }

    private void drainOutput(Process proc, AttemptCapture capture) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (logSink != null) {
                    logSink.write(line);
                }
                capture.add(line);
            }
        } catch (IOException ignored) {
            // Stream closed because the process ended; that is expected.
        }
    }

    /**
     * In-memory capture of ONE launch attempt's process output, fed
     * by the drain thread alongside the log file. Keeps only the tail
     * (30 lines) plus the total count, so a long-running node cannot
     * grow it without bound — and an attempt that printed nothing is
     * distinguishable from one whose lines were merely dropped.
     */
    private static final class AttemptCapture {
        private static final int MAX_KEPT_LINES = 30;

        private final java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>();
        private int total = 0;

        synchronized void add(String line) {
            total++;
            if (tail.size() >= MAX_KEPT_LINES) {
                tail.pollFirst();
            }
            tail.addLast(line);
        }

        synchronized List<String> tailLines() {
            return new ArrayList<>(tail);
        }

        synchronized int totalLines() {
            return total;
        }
    }

    private void logApp(String msg) {
        if (logSink != null) {
            logSink.write("[app] " + msg);
        }
    }

    /**
     * Append-only log with a hard cap: once the file passes ~512 KB it is
     * rewritten keeping only its newer half. One writer at a time (app
     * lines and node lines interleave by line, which is fine).
     *
     * Synchronization is on a CLASS-level lock, not the instance: a
     * service destroyed and recreated across sessions leaves two
     * LogSink objects pointing at the same node.log file, and
     * instance-level locks let their appends/rotations interleave and
     * corrupt each other's view of the file (N-11).
     */
    private static final class LogSink {
        private static final Object LOCK = new Object();

        /**
         * Per-line byte cap (a quarter of the file cap). Without it, a
         * single line longer than the whole file cap would, at the next
         * rotation, push the keep-window start past every newline in
         * the file and the rotation would drop ALL retained history,
         * leaving only the marker (LOG-H-03). Oversized lines are
         * truncated and marked instead; the file cap still holds.
         */
        private static final int MAX_LINE_BYTES = (int) (LOG_MAX_BYTES / 4);
        private static final byte[] TRUNC_SUFFIX =
                " ...[line truncated]\n".getBytes(StandardCharsets.UTF_8);

        private final File file;
        private long bytesSinceCheck = 0;

        LogSink(File logDir) {
            logDir.mkdirs();
            this.file = new File(logDir, "node.log");
        }

        void write(String line) {
            byte[] bytes = encodeLine(line);
            synchronized (LOCK) {
                try (FileOutputStream out = new FileOutputStream(file, true)) {
                    out.write(bytes);
                } catch (IOException ignored) {
                    // Logging must never take the node down with it.
                    return;
                }
                bytesSinceCheck += bytes.length;
                if (bytesSinceCheck >= LOG_CHECK_EVERY_BYTES) {
                    bytesSinceCheck = 0;
                    rotateIfNeededLocked();
                }
            }
        }

        /** Encode one line, truncating an oversized line at a UTF-8 boundary. */
        private static byte[] encodeLine(String line) {
            byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
            if (bytes.length <= MAX_LINE_BYTES) {
                return bytes;
            }
            int keep = MAX_LINE_BYTES - TRUNC_SUFFIX.length;
            // Do not split a multi-byte UTF-8 sequence: back off while
            // the first dropped byte is a continuation byte (10xxxxxx).
            while (keep > 0 && (bytes[keep] & 0xC0) == 0x80) {
                keep--;
            }
            byte[] out = new byte[keep + TRUNC_SUFFIX.length];
            System.arraycopy(bytes, 0, out, 0, keep);
            System.arraycopy(TRUNC_SUFFIX, 0, out, keep, TRUNC_SUFFIX.length);
            return out;
        }

        /** Caller must hold LOCK. */
        private void rotateIfNeededLocked() {
            try {
                long len = file.length();
                if (len <= LOG_MAX_BYTES) {
                    return;
                }
                byte[] all;
                try (FileInputStream in = new FileInputStream(file)) {
                    all = in.readAllBytes();
                }
                int keep = (int) (LOG_MAX_BYTES / 2);
                int from = Math.max(0, all.length - keep);
                // Start at a line boundary so the log stays readable.
                while (from < all.length && all[from] != (byte) '\n') {
                    from++;
                }
                if (from < all.length) {
                    from++;
                }
                try (FileOutputStream out = new FileOutputStream(file, false)) {
                    out.write("... [app] log rotated, older half dropped ...\n"
                            .getBytes(StandardCharsets.UTF_8));
                    out.write(all, from, all.length - from);
                }
            } catch (IOException ignored) {
                // Same rule: never crash for the log's sake.
            }
        }
    }

    // ------------------------------------------------------------------
    // Local RPC polling
    // ------------------------------------------------------------------

    private void runPoller(int gen) {
        while (pollerOn.get() && gen == sessionGen.get()) {
            try {
                pollOnce(gen);
            } catch (Exception ignored) {
                // A failed poll is a state ("starting"), never a crash.
            }
            saveUptimePeriodically(gen);
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void pollOnce(int gen) {
        if (nodeProcess == null) {
            return; // watcher thread owns the stopped transition
        }
        if (gen != sessionGen.get()) {
            return; // stale session: never write state (bug P-1)
        }
        // RPC calls run OUTSIDE the lock (they can block for seconds);
        // every resulting write is applied in ONE locked batch at the
        // end, after re-checking the generation inside the lock. That
        // makes "generation check + writes" atomic with respect to a
        // teardown: there is no longer a window in which a stop can
        // settle between the check and the writes and still get its
        // STOPPED overwritten by this poll (N-5), and the track
        // read-modify-write (prevBestBlock + delta) cannot interleave
        // with another session's poller (N-7).
        JSONObject health = rpcCall("system_health");
        if (health == null) {
            // RPC not up yet (connection refused etc.): still starting.
            synchronized (stateLock) {
                if (gen != sessionGen.get()) {
                    return;
                }
                NodeState st = NodeState.get();
                if (st.status != NodeState.Status.STOPPED) {
                    st.status = NodeState.Status.STARTING;
                }
            }
            updateNotificationMaybe();
            return;
        }
        int peers = health.optInt("peers", 0);
        boolean isSyncing = health.optBoolean("isSyncing", false);

        JSONObject sync = rpcCall("system_syncState");
        long current = -1;
        long highest = -1;
        if (sync != null) {
            current = sync.optLong("currentBlock", -1);
            highest = sync.optLong("highestBlock", -1);
        }

        synchronized (stateLock) {
            if (gen != sessionGen.get()) {
                return; // stopped / finished while the calls were in flight
            }
            NodeState st = NodeState.get();
            st.peers = peers;
            TrackStore.observePeers(this, peers);
            if (sync != null) {
                if (current >= 0) {
                    if (prevBestBlock >= 0 && current > prevBestBlock) {
                        // Count blocks, not polls: one poll interval
                        // during the initial sync can cover thousands
                        // of blocks (bug B-3). On a rewind (current <=
                        // prev) nothing is added; prevBestBlock is
                        // still updated below.
                        TrackStore.addBlockUpdates(this, current - prevBestBlock);
                    }
                    prevBestBlock = current;
                    st.bestBlock = current;
                }
                if (highest >= 0) {
                    st.highestBlock = highest;
                }
                boolean atHead = highest < 0 || current >= highest;
                st.status = (isSyncing || !atHead)
                        ? NodeState.Status.SYNCING : NodeState.Status.SYNCED;
            } else {
                st.status = isSyncing
                        ? NodeState.Status.SYNCING : NodeState.Status.SYNCED;
            }
        }
        updateNotificationMaybe();
    }

    /** One JSON-RPC call to the loopback endpoint; null on any failure. */
    private JSONObject rpcCall(String method) {
        HttpURLConnection conn = null;
        try {
            JSONObject req = new JSONObject();
            req.put("jsonrpc", "2.0");
            req.put("id", 1);
            req.put("method", method);
            req.put("params", new org.json.JSONArray());
            byte[] body = req.toString().getBytes(StandardCharsets.UTF_8);

            conn = (HttpURLConnection) new URL(RPC_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(2000);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            if (conn.getResponseCode() != 200) {
                return null;
            }
            byte[] resp;
            try (java.io.InputStream in = conn.getInputStream()) {
                resp = in.readAllBytes();
            }
            JSONObject json = new JSONObject(new String(resp, StandardCharsets.UTF_8));
            Object result = json.opt("result");
            return (result instanceof JSONObject) ? (JSONObject) result : null;
        } catch (Exception e) {
            return null; // connection refused while booting is normal
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ------------------------------------------------------------------
    // Uptime accounting (survives most kills: saved every ~10 s)
    // ------------------------------------------------------------------

    /**
     * Periodic save from the poller. Generation-checked and locked:
     * a stale poller must not keep accruing uptime for a session that
     * has already ended (N-3), and the read-modify-rebase of the
     * uptime base must not interleave between the poller, the worker
     * and the main thread (N-4).
     */
    private void saveUptimePeriodically(int gen) {
        synchronized (stateLock) {
            if (gen != sessionGen.get() || sessionStartElapsedMs < 0) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (now - lastUptimeSaveElapsedMs >= UPTIME_SAVE_EVERY_MS) {
                lastUptimeSaveElapsedMs = now;
                TrackStore.saveUptimeMs(this,
                        baseUptimeMs + (now - sessionStartElapsedMs));
            }
        }
    }

    /**
     * Fold the live session's elapsed time into the stored base and
     * rebase. Caller MUST hold {@link #stateLock} (all teardown paths
     * do); keeping this atomic with the generation bump is what stops
     * an old session's late save from rewinding a newer session's
     * accumulated uptime (N-2, N-4).
     */
    private void saveUptimeLocked() {
        if (sessionStartElapsedMs >= 0) {
            long total = baseUptimeMs
                    + (SystemClock.elapsedRealtime() - sessionStartElapsedMs);
            TrackStore.saveUptimeMs(this, total);
            baseUptimeMs = total;
            sessionStartElapsedMs = SystemClock.elapsedRealtime();
        }
    }

    // ------------------------------------------------------------------
    // Notification (quiet: text changes only when the state does)
    // ------------------------------------------------------------------

    private void createNotificationChannel() {
        // Name/description in the current UI language. Re-creating the
        // channel under the same ID refreshes them, so a language
        // change reaches the channel the next time the service starts.
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, I18n.get(this, StringsTable.NOTIF_CHANNEL_NAME),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(I18n.get(this, StringsTable.NOTIF_CHANNEL_DESC));
        ch.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String statusText) {
        NodeState st = NodeState.get();
        StringBuilder text = new StringBuilder(statusText);
        if (st.bestBlock >= 0) {
            text.append(" · #").append(st.bestBlock);
        }
        if (st.peers >= 0) {
            text.append(" · ").append(I18n.format(this, StringsTable.NOTIF_PEERS, st.peers));
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, NodeService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Quantus Node")
                .setContentText(text.toString())
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        null, I18n.get(this, StringsTable.NOTIF_ACTION_STOP), stopPi).build())
                .build();
    }

    private void startForegroundWith(Notification n) {
        // specialUse (API 34+), matching the manifest: dataSync would be
        // capped at 6 cumulative hours per 24 h on Android 15 (bug B-1);
        // the resident node is the specialUse case, with its subtype
        // declared via PROPERTY_SPECIAL_USE_FGS_SUBTYPE in the manifest.
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void updateNotificationMaybe() {
        NodeState st = NodeState.get();
        String label = NodeState.label(this, st.status);
        String key = label + "|" + st.bestBlock + "|" + st.peers;
        if (key.equals(lastNotifText)) {
            return;
        }
        lastNotifText = key;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification(label));
        }
    }
}
