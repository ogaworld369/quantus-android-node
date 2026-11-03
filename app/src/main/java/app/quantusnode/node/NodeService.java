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
     * Session generation: bumped every time a session starts and every
     * time one is torn down (stop / finish / destroy). The RPC poller
     * carries the generation it was started with and refuses to write
     * state once its generation is stale, so a poll that was in flight
     * while the service stopped cannot resurrect STARTING / SYNCED on
     * top of the final STOPPED state (bug P-1).
     */
    private final java.util.concurrent.atomic.AtomicInteger sessionGen =
            new java.util.concurrent.atomic.AtomicInteger(0);

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
        NodeState st = NodeState.get();
        st.status = NodeState.Status.STARTING;
        st.lastExitCode = null;
        st.lastNote = null;
        // No launch snapshot yet for this session: the worker fills it in
        // when the process is actually launched. Until then the UI must
        // not claim a settings/restart mismatch against a previous run.
        st.launchedTelemetry = null;
        st.launchedNodeName = null;
        baseUptimeMs = TrackStore.uptimeMs(this);
        sessionStartElapsedMs = SystemClock.elapsedRealtime();
        lastUptimeSaveElapsedMs = sessionStartElapsedMs;
        prevBestBlock = -1;

        workerThread = new Thread(this::runNodeProcess, "node-process");
        workerThread.start();
        final int gen = sessionGen.incrementAndGet();
        pollerOn.set(true);
        pollerThread = new Thread(() -> runPoller(gen), "node-rpc-poll");
        pollerThread.start();
        // No hidden restarts: if the system kills us, we stay dead until
        // the user starts the node again.
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        // Clear `started` FIRST: the worker is blocked in waitFor() and
        // would otherwise read the process destroy below as a "quick
        // failed start" and retry via linker64 — resurrecting a node the
        // user (or the system) just stopped, with nobody left to kill it
        // (bug B-2). Bumping the generation likewise invalidates any
        // in-flight poll (bug P-1).
        started.set(false);
        sessionGen.incrementAndGet();
        pollerOn.set(false);
        Process p = nodeProcess;
        if (p != null) {
            p.destroy();
        }
        saveUptimeNow();
        NodeState st = NodeState.get();
        st.status = NodeState.Status.STOPPED;
        st.serviceRunning = false;
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
        logApp("foreground service timed out by the system; stopping node");
        NodeState.get().lastNote = I18n.get(this, StringsTable.NOTE_FGS_TIMEOUT);
        Process p = nodeProcess;
        if (p != null) {
            p.destroy();
        }
        finishAsStopped(null);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // not a bound service
    }

    private void stopNodeAndSelf() {
        // Same ordering rule as onDestroy(): clear `started` before
        // destroying the process, so the worker cannot mistake this
        // user-requested stop for a failed start and retry it (B-2);
        // bump the generation so an in-flight poll cannot write state
        // after this stop is settled (P-1).
        started.set(false);
        sessionGen.incrementAndGet();
        pollerOn.set(false);
        Process p = nodeProcess;
        if (p != null) {
            p.destroy();
        }
        saveUptimeNow();
        NodeState.get().status = NodeState.Status.STOPPED;
        stopForeground(true);
        stopSelf();
    }

    // ------------------------------------------------------------------
    // Node process
    // ------------------------------------------------------------------

    private void runNodeProcess() {
        File bin = new File(getApplicationInfo().nativeLibraryDir, NODE_LIB);
        if (!bin.exists()) {
            logApp("node binary not found: " + bin.getAbsolutePath());
            NodeState.get().lastNote = I18n.get(this, StringsTable.NOTE_BINARY_MISSING);
            finishAsStopped(null);
            return;
        }
        // App-private dirs only: the node DB / keys stay under filesDir.
        new File(getFilesDir(), "node").mkdirs();

        boolean triedLinker = false;
        while (started.get()) {
            Process proc = null;
            long t0 = SystemClock.elapsedRealtime();
            try {
                proc = launch(bin, triedLinker);
            } catch (IOException e) {
                logApp("start failed (" + (triedLinker ? "linker64" : "direct")
                        + "): " + e.getMessage());
                if (!triedLinker) {
                    triedLinker = true;
                    continue; // one fallback attempt, via linker64
                }
                NodeState.get().lastNote = I18n.format(
                        this, StringsTable.NOTE_START_FAILED, e.getMessage());
                finishAsStopped(null);
                return;
            }
            nodeProcess = proc;
            final Process running = proc;
            logApp("node started (" + (triedLinker ? "via linker64" : "direct") + ")");

            Thread drain = new Thread(() -> drainOutput(running), "node-output");
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
            NodeState.get().lastExitCode = code;

            // Quick, failed start on the direct path: try linker64 once.
            // Anything else is a real exit — record it and stop, no loop.
            if (started.get() && !triedLinker && code != 0 && livedMs < QUICK_EXIT_MS) {
                logApp("quick exit; retrying once via /system/bin/linker64");
                triedLinker = true;
                continue;
            }
            finishAsStopped(code);
            return;
        }
    }

    private Process launch(File bin, boolean viaLinker) throws IOException {
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
        NodeState st = NodeState.get();
        st.launchedTelemetry = telemetry;
        st.launchedNodeName = nodeName;

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("LD_LIBRARY_PATH", nativeDir);
        pb.redirectErrorStream(true);
        logApp("launch: " + String.join(" ", cmd)
                + " [telemetry " + (telemetry ? "ON (user opt-in)" : "off") + "]");
        return pb.start();
    }

    private void finishAsStopped(Integer exitCode) {
        // Invalidate the session generation first, so a pollOnce() that
        // is still blocked in an RPC call returns without writing state
        // once this stop is settled (bug P-1).
        sessionGen.incrementAndGet();
        pollerOn.set(false);
        saveUptimeNow();
        NodeState st = NodeState.get();
        st.status = NodeState.Status.STOPPED;
        // bestBlock / highestBlock / peers keep their last known values,
        // so the dashboard can still show where the node got to.
        started.set(false);
        stopForeground(true);
        stopSelf();
    }

    // ------------------------------------------------------------------
    // Output draining + bounded log file
    // ------------------------------------------------------------------

    private void drainOutput(Process proc) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (logSink != null) {
                    logSink.write(line);
                }
            }
        } catch (IOException ignored) {
            // Stream closed because the process ended; that is expected.
        }
    }

    private void logApp(String msg) {
        if (logSink != null) {
            logSink.write("[app] " + msg);
        }
    }

    /**
     * Append-only log with a hard cap: once the file passes ~512 KB it is
     * rewritten keeping only its newer half. Synchronized; one writer at a
     * time (app lines and node lines interleave by line, which is fine).
     */
    private static final class LogSink {
        private final File file;
        private long bytesSinceCheck = 0;

        LogSink(File logDir) {
            logDir.mkdirs();
            this.file = new File(logDir, "node.log");
        }

        synchronized void write(String line) {
            byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(bytes);
            } catch (IOException ignored) {
                // Logging must never take the node down with it.
                return;
            }
            bytesSinceCheck += bytes.length;
            if (bytesSinceCheck >= LOG_CHECK_EVERY_BYTES) {
                bytesSinceCheck = 0;
                rotateIfNeeded();
            }
        }

        private void rotateIfNeeded() {
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
            saveUptimePeriodically();
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void pollOnce(int gen) {
        NodeState st = NodeState.get();
        if (nodeProcess == null) {
            return; // watcher thread owns the stopped transition
        }
        if (gen != sessionGen.get()) {
            return; // stale session: never write state (bug P-1)
        }
        JSONObject health = rpcCall("system_health");
        if (gen != sessionGen.get()) {
            return; // stopped / finished while the call was in flight
        }
        if (health == null) {
            // RPC not up yet (connection refused etc.): still starting.
            if (st.status != NodeState.Status.STOPPED) {
                st.status = NodeState.Status.STARTING;
            }
            updateNotificationMaybe();
            return;
        }
        int peers = health.optInt("peers", 0);
        boolean isSyncing = health.optBoolean("isSyncing", false);
        st.peers = peers;
        TrackStore.observePeers(this, peers);

        JSONObject sync = rpcCall("system_syncState");
        if (gen != sessionGen.get()) {
            return; // stopped / finished while the call was in flight
        }
        if (sync != null) {
            long current = sync.optLong("currentBlock", -1);
            long highest = sync.optLong("highestBlock", -1);
            if (current >= 0) {
                if (prevBestBlock >= 0 && current > prevBestBlock) {
                    // Count blocks, not polls: one poll interval during
                    // the initial sync can cover thousands of blocks
                    // (bug B-3). On a rewind (current <= prev) nothing
                    // is added; prevBestBlock is still updated below.
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
            st.status = isSyncing ? NodeState.Status.SYNCING : NodeState.Status.SYNCED;
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

    private void saveUptimePeriodically() {
        long now = SystemClock.elapsedRealtime();
        if (sessionStartElapsedMs >= 0
                && now - lastUptimeSaveElapsedMs >= UPTIME_SAVE_EVERY_MS) {
            lastUptimeSaveElapsedMs = now;
            TrackStore.saveUptimeMs(this,
                    baseUptimeMs + (now - sessionStartElapsedMs));
        }
    }

    private void saveUptimeNow() {
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
