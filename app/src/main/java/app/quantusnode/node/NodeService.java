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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
    /** Hard cap on one RPC response (finding 6); real replies are ~100 B. */
    private static final int RPC_MAX_RESPONSE_BYTES = 64 * 1024;
    private static final long POLL_MS = 3000;
    private static final long UPTIME_SAVE_EVERY_MS = 10000;

    /** Log rotation: at ~512 KB the older half is dropped. Never unbounded. */
    private static final long LOG_MAX_BYTES = 512L * 1024L;
    private static final long LOG_CHECK_EVERY_BYTES = 64L * 1024L;

    /** A process that dies faster than this counts as a failed start. */
    private static final long QUICK_EXIT_MS = 2500;

    /**
     * Cross-instance leftover-process wait (audit finding 4): the pid
     * file under filesDir, how long launch() waits for a leftover node
     * to finish exiting before refusing to spawn, and the poll step.
     */
    private static final String PID_FILE_NAME = "node-process.pid";
    private static final long LEFTOVER_WAIT_MS = 5000;
    private static final long LEFTOVER_POLL_MS = 100;

    /**
     * Serializes every pid-file operation AND the spawn sequence
     * itself (audit finding 4, re-audit): launch() holds this lock
     * across "wait for leftover -> spawn -> record pid", so a second
     * launch can never slip into the gap between a process starting
     * and its pid being on record; and the record's check-and-clear
     * is indivisible under the same lock, so a late cleanup cannot
     * pass the check, watch a newer session overwrite the record, and
     * then delete the NEWER process's pid. Static on purpose: it must
     * work across service instances (a destroyed-and-recreated service
     * is a new object in the same process) — the same reason
     * LogSink's lock is class-level (N-11).
     */
    private static final Object PID_LOCK = new Object();

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

    /**
     * The live session's node process; null while none is registered.
     * Ownership is serialized on {@link #stateLock} (third external
     * audit, 2026-11-04): the worker REGISTERS a process only inside
     * that lock, and only after re-confirming there that its session
     * is still the live one; the exit path CLEARS the reference inside
     * the same lock, identity check and assignment as one indivisible
     * step. Until versionCode 15 both writes were unguarded — a stale
     * worker could store its process over a newer session's
     * reference, or pass the identity check, watch a newer process be
     * registered, and then null THAT reference — leaving a live node
     * unpollable and unstoppable. Reads stay plain volatile
     * snapshots: the teardown paths snapshot-and-destroy under
     * stateLock anyway, and the poller and the leftover wait only
     * need an approximate view.
     */
    private volatile Process nodeProcess;

    /**
     * The startId to hand to {@code stopSelfResult()} when this service
     * ends itself (external audit 2026-11-04, finding 3). Guarded by
     * {@link #stateLock}: every read and write happens under it, bound
     * to the live session, and it is updated at exactly three moments:
     * (a) when a session is established — the startId of the
     * ACTION_START that created it; (b) when a duplicate START arrives
     * while that session is live — that START's startId: it is the
     * newest start command for the same session, and the one
     * stopSelfResult() must be given, because with the older one it
     * would decline ("a newer start exists") and leave a node-less
     * foreground service lingering after a natural exit; (c) when an
     * ACTION_STOP arrives — its startId, the latest command by
     * definition, so ending the service on its authority is always
     * correct.
     *
     * The versionCode 13 version re-read this shared field at stop
     * time, outside the lock; the re-audit showed that a session
     * starting in that gap could lend the finishing session its
     * (newer) startId and be stopped by it. The stop sequence now
     * runs as one locked unit — generation re-check, stopId read,
     * stopSelfResult(), and on acceptance stopForeground() plus the
     * notification cancel (see {@link #stopServiceNowLocked}) — and
     * onStartCommand takes the same lock to establish a session or
     * refresh this value, so a START cannot interleave inside it.
     */
    private int sessionStopId = -1;

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
            stopNodeAndSelf(startId);
            return START_NOT_STICKY;
        }
        if (!started.compareAndSet(false, true)) {
            // The CAS failing does not by itself mean a session is
            // live: decide that under stateLock, against the real
            // state. (Until versionCode 14 this branch always treated
            // the START as a duplicate; see the sequence-B case below.)
            synchronized (stateLock) {
                if (started.get()) {
                    // Genuinely already running: nothing new to
                    // start. This duplicate START still becomes the
                    // live session's stop authority (finding 3,
                    // case (b) on sessionStopId): it is the newest
                    // start command delivered for this session, and
                    // if the session later ends on its own,
                    // stopSelfResult() must be given THIS startId or
                    // it declines and the service lingers. Updated
                    // under stateLock, like every other write to
                    // that field.
                    sessionStopId = startId;
                    return START_NOT_STICKY;
                }
                // Sequence B (found by the internal desk check of
                // versionCode 14, fixed in versionCode 15): this
                // START was delivered while the previous session's
                // worker was mid-teardown inside finishAsStopped()
                // — holding stateLock, with `started` not yet
                // cleared — so the CAS above mistook it for a
                // duplicate and it waited on this lock. That teardown
                // is complete now: it clears `started` inside the
                // same locked section, so observing false here, lock
                // in hand, means no session is live. Treating this
                // START as a duplicate would consume it — the
                // finish's stopSelfResult() declines precisely
                // because this newer startId exists, the service
                // stays in the foreground, and nobody ever creates
                // a session: a node-less service lingers. Instead,
                // claim the start here, still under the lock, and
                // fall through to the normal session establishment
                // below; the establishment block records THIS
                // startId as the session's creator, exactly as for
                // a start that won the CAS. `started` is only ever
                // set by this method, on this (main) thread, so the
                // claim cannot race another claim.
                started.set(true);
            }
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
            // This START created the session: its startId is the one a
            // later end-of-session uses for stopSelfResult() (finding 3).
            sessionStopId = startId;
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
        // F-8: the whole sequence — reading the generation, stamping
        // the timeout note, and finishing — runs under stateLock, like
        // the other stop paths do. This used to read the generation
        // outside the lock and write lastNote unconditionally, so a
        // timeout racing a stop + fresh start could stamp its note
        // onto the NEW session. Under the lock the generation cannot
        // move between the read and finishAsStopped's own check (that
        // method re-takes the lock; monitors are reentrant).
        synchronized (stateLock) {
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
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // not a bound service
    }

    private void stopNodeAndSelf(int stopStartId) {
        // Same ordering rule as onDestroy(), under the lifecycle lock:
        // clear `started` before destroying the process, so the worker
        // cannot mistake this user-requested stop for a failed start
        // and retry it (B-2); bump the generation so neither an
        // in-flight poll (P-1) nor the worker's delayed finish (N-2)
        // can write state after this stop is settled.
        synchronized (stateLock) {
            // This STOP is the newest command the service has seen, so
            // its startId takes over as the session's stop authority
            // (finding 3): ending the service below is then legitimate
            // even though it was a START that created the session.
            sessionStopId = stopStartId;
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
            // The self-stop is decided and executed inside this same
            // locked section (finding 3, re-audit): no START can slip
            // between the teardown above and stopSelfResult() and be
            // torn down together with this session.
            stopServiceNowLocked();
        }
    }

    /**
     * End the service itself, startId-checked (external audit
     * 2026-11-04, finding 3). MUST be called with {@link #stateLock}
     * held, as the last step of a teardown that has already
     * re-checked its generation inside the same locked section: the
     * stopId read here, the stopSelfResult() call and — when it
     * accepts — stopForeground() plus the notification cancel are one
     * locked unit, and onStartCommand takes the same lock to
     * establish a session or refresh the stopId, so no START can
     * interleave between the check and the act. (VersionCode 13 read
     * the shared stopId field here, at call time and outside the
     * lock; the re-audit showed a session starting in that gap could
     * lend its newer startId to the finishing one and be stopped by
     * it.)
     *
     * stopSelfResult() performs the stop only if the given startId is
     * still the most recent start command. If a newer START has been
     * delivered since, it returns false and BOTH the self-stop and
     * the stopForeground() are skipped — the service and its
     * foreground state belong to the newer session now, and this
     * session stops nothing. When the stop does go through, the
     * status notification is also cancelled outright (finding 5,
     * insurance against a stale notify() landing after the stop has
     * settled; the notify side is serialized on this same lock, see
     * updateNotificationMaybe).
     */
    private void stopServiceNowLocked() {
        boolean doStop = stopSelfResult(sessionStopId);
        if (doStop) {
            stopForeground(true);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.cancel(NOTIF_ID);
            }
        }
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
            long spawnElapsedMs = -1;
            long resolvedPid = -1;
            long t0 = SystemClock.elapsedRealtime();
            AttemptCapture capture = new AttemptCapture();
            try {
                LaunchOutcome outcome = launch(bin, triedLinker, gen);
                proc = outcome.process;
                spawnElapsedMs = outcome.spawnElapsedMs;
                resolvedPid = outcome.resolvedPid;
                if (!outcome.pidRecorded) {
                    // Finding 4: a launch whose pid never got recorded
                    // is one a future service instance cannot wait
                    // for; that belongs in this attempt's evidence,
                    // not only in the log (launch() logged it too).
                    capture.add("[app] pid record failed for this attempt;"
                            + " cross-instance leftover tracking is"
                            + " degraded for this process");
                }
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
            } catch (Throwable t) {
                // vc17 safety net: launch() is meant to fail only with
                // IOException, but the spawn sequence runs platform
                // calls (Process.pid() above all) whose failure mode
                // on some runtimes is an Error, not an exception — and
                // an Error escaping this worker took the whole app
                // process down on the first field run of the
                // versionCode 13-16 builds, right after the "launch:"
                // line, with neither "node started" nor "start
                // failed" ever recorded. Whatever escapes is now
                // treated exactly like a failed launch: the throwable
                // is named in the log AND in the attempt evidence, so
                // the cause survives for diagnosis; the linker64
                // fallback gets its one try; after that the session
                // stops cleanly instead of dying. Nothing is swallowed
                // into a loop — the attempts stay bounded at
                // direct + linker, as before.
                String what = t.getClass().getName() + ": " + t.getMessage();
                logApp("start error (" + (triedLinker ? "linker64" : "direct")
                        + "): " + what);
                capture.add("[app] start error: " + what);
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
                                this, StringsTable.NOTE_START_FAILED, what);
                    }
                }
                finishAsStopped(gen, null);
                return;
            }
            final Process running = proc;
            // N-6 / START-RACE window + third-audit ownership race:
            // the stop side may have run to completion while
            // pb.start() was still creating this process (it saw
            // nodeProcess == null and had nothing to destroy), and
            // launch() itself can return late to a worker whose
            // session is already gone. Registration is therefore a
            // stateLock decision: the process becomes the session's
            // only if, inside the lock, this session is still the
            // live one (started, current generation). Until
            // versionCode 15 the assignment was unconditional and the
            // staleness re-check and null-out that followed were
            // separate unguarded steps, so a stale worker could
            // overwrite a NEWER session's reference and then erase
            // it — orphaning a live node with the DB lock and the
            // RPC port held. A process that is not accepted never
            // joins the session: it is destroyed here and its pid
            // record removed (identity-checked, under PID_LOCK), so
            // nothing of it is left for a later launch to trip over.
            // The destroy and the pid clear run AFTER the lock is
            // released: no stateLock -> PID_LOCK nesting is created.
            boolean accepted;
            synchronized (stateLock) {
                accepted = started.get() && gen == sessionGen.get();
                if (accepted) {
                    nodeProcess = proc;
                }
            }
            if (!accepted) {
                logApp("session ended while the node was starting; destroying it");
                proc.destroy();
                // Clear the pid record by the pid launch() resolved
                // for THIS attempt (versionCode 18) — never by
                // re-probing pid(), which is exactly the call that
                // does not work on the affected runtimes.
                if (resolvedPid > 0) {
                    clearPidFileIfMatches(resolvedPid);
                }
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
                // F-1 + third-audit ownership race: clear the
                // reference only if it still points at THIS session's
                // process, with the check-and-clear indivisible under
                // stateLock. This finally can run late, after a newer
                // session has already stored its own process here; the
                // identity check alone (the versionCode 8 fix) left a
                // gap — pass the check, watch the newer process
                // register, then null ITS reference — leaving it
                // unpollable and unstoppable, an orphan holding the
                // DB lock and the RPC port. Registration above takes
                // the same lock, so the two can no longer interleave.
                synchronized (stateLock) {
                    if (nodeProcess == running) {
                        nodeProcess = null;
                    }
                }
                // Finding 4: same rule for the pid file — remove it
                // only while it still names THIS process; a newer
                // session's pid must survive this late cleanup. The
                // reference goes first and the record second, in
                // separate lock scopes (stateLock, then PID_LOCK —
                // never nested): the reference is this instance's
                // live handle, while the record is only a
                // cross-instance hint that is re-verified via /proc
                // before anyone waits on it; and by the time
                // waitFor() has returned, this process is dead
                // either way, so the order cannot strand a live one.
                // The pid used is the one launch() resolved for this
                // attempt (versionCode 18), not a fresh pid() probe.
                if (resolvedPid > 0) {
                    clearPidFileIfMatches(resolvedPid);
                }
            }
            // Finding 7: lifetime is measured from the actual spawn
            // (launch() hands the moment ProcessBuilder.start()
            // returned back with the process, as a per-attempt value —
            // it was a shared field until the re-audit), so the wait
            // for a previous process inside launch() does not inflate
            // it and hide a quick failed start. The pre-launch t0
            // remains only as a fallback for a spawn that somehow left
            // no timestamp.
            long livedMs = SystemClock.elapsedRealtime()
                    - (spawnElapsedMs >= 0 ? spawnElapsedMs : t0);
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

    /** What a successful launch() hands back to the worker. */
    private static final class LaunchOutcome {
        final Process process;
        /** elapsedRealtime the moment ProcessBuilder.start() returned (finding 7). */
        final long spawnElapsedMs;
        /** False when the pid record could not be written (finding 4). */
        final boolean pidRecorded;
        /**
         * The child pid, resolved exactly once by launch()
         * (versionCode 18): {@code Process.pid()} where the runtime
         * allows it, the /proc-scan result where it does not, and -1
         * when neither worked. The worker clears the pid record by
         * THIS value — re-probing pid() at cleanup time would fail
         * again on exactly the runtimes that needed the fallback.
         */
        final long resolvedPid;

        LaunchOutcome(Process process, long spawnElapsedMs,
                      boolean pidRecorded, long resolvedPid) {
            this.process = process;
            this.spawnElapsedMs = spawnElapsedMs;
            this.pidRecorded = pidRecorded;
            this.resolvedPid = resolvedPid;
        }
    }

    private LaunchOutcome launch(File bin, boolean viaLinker, int gen) throws IOException {
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
        // Phone-sized tuning (2026-11-03 code audit): the default trie
        // cache ceiling is 1 GiB, which the node itself warns it cannot
        // initialize on this 8 GB device — cap it at 128 MiB so the
        // cache actually works and memory pressure (LMK kills) drops.
        // Blocks-per-request goes to the responder's protocol maximum
        // (128, was 64), halving request round-trips while syncing.
        cmd.add("--trie-cache-size"); cmd.add("134217728");
        cmd.add("--max-blocks-per-request"); cmd.add("128");
        cmd.add("--rpc-port");      cmd.add("9944");
        cmd.add("--rpc-methods");   cmd.add("safe");
        if (!telemetry) {
            cmd.add("--no-telemetry");
        }

        // The "what was this process launched with" snapshot is NOT
        // written here: it is recorded at the end of this method,
        // after pb.start() has succeeded (F-9).

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("LD_LIBRARY_PATH", nativeDir);
        // TLS roots for the node's telemetry (wss) connection: the node
        // runs as a bare native process, where rustls-native-certs finds
        // no system roots ("no native root CA certificates found" —
        // seen in the field on 2026-11-03, which kept the node out of
        // the public telemetry list). Point the loader at the bundled
        // Mozilla CA set, with the on-device store as a second hint.
        String caPath = ensureCaBundle();
        if (caPath != null) {
            pb.environment().put("SSL_CERT_FILE", caPath);
        }
        pb.environment().put("SSL_CERT_DIR", "/system/etc/security/cacerts");
        pb.redirectErrorStream(true);
        logApp("launch: " + String.join(" ", cmd)
                + " [telemetry " + (telemetry ? "ON (user opt-in)" : "off") + "]");
        // F-2 + audit finding 4: before spawning, give the PREVIOUS
        // session's process a bounded window to finish exiting.
        // Teardown only destroys it — if the user restarts immediately,
        // it can still be flushing/closing the DB, and the new process
        // then fails on ParityDB's exclusive lock. The old F-2 wait
        // only covered a previous process this same service instance
        // still referenced; a recreated instance (stop -> destroy ->
        // fresh service) lost that reference while the child could
        // still be exiting. The wait is now unified on
        // awaitLeftoverNodeProcess(), which finds the leftover either
        // via the field or via the pid file. launch() runs on this
        // session's worker thread, so the wait never blocks the UI.
        //
        // The whole sequence — wait, spawn, pid record — runs inside
        // PID_LOCK (finding 4, re-audit): without that, a second
        // launch could pass its leftover check in the window between
        // this child's spawn and its pid landing on record, and two
        // nodes would race for the same database.
        final Process proc;
        final long spawnElapsedMs;
        final boolean pidRecorded;
        final long resolvedPid;
        synchronized (PID_LOCK) {
            awaitLeftoverNodeProcess();
            proc = pb.start();
            // Finding 7: the lifetime clock starts HERE, at the actual
            // spawn — not before launch(), whose wait above could
            // otherwise eat the quick-exit window and mask a failed
            // start. It travels back to the worker with the process,
            // as a per-attempt value.
            spawnElapsedMs = SystemClock.elapsedRealtime();
            // Finding 4: record the child pid so a future service
            // instance can find (and wait for) this process after this
            // instance is gone. Cleared by the worker when this process
            // exits, and only if the file still names this pid. A
            // failed record does NOT fail the launch — this instance
            // still tracks the process through its Process handle; only
            // the cross-instance courtesy degrades — but the failure
            // is recorded, here in the log and in the attempt evidence
            // (via the returned outcome), never silently. Resolution
            // happens exactly once, here (resolveChildPid), and the
            // result travels back in the outcome: the worker's
            // cleanups clear the record by that value instead of
            // re-probing pid() at cleanup time (versionCode 18).
            long newPid = resolveChildPid(proc);
            resolvedPid = newPid;
            pidRecorded = newPid > 0 && writePidFile(newPid);
            if (!pidRecorded) {
                logApp("pid record FAILED for pid " + newPid
                        + "; a future service instance cannot wait for"
                        + " this process (this instance still tracks it)");
            }
        }
        // F-9: remember what this process was launched with, so the UI
        // can say "settings changed — restart to apply" when they
        // diverge — but only now that the process actually exists.
        // This used to be recorded BEFORE pb.start(), so a launch that
        // threw IOException left a snapshot behind for a process that
        // never started. (The failed attempt still leaves its own
        // record in the session's attempt list, which is where failure
        // evidence belongs.) Generation-gated like every other state
        // write: a launch that completes after its session was torn
        // down must not stamp its snapshot over a newer session's.
        // launch() serves both the direct attempt and the linker64
        // retry, so the retry's successful start is recorded here too.
        synchronized (stateLock) {
            if (gen == sessionGen.get()) {
                NodeState st = NodeState.get();
                st.launchedTelemetry = telemetry;
                st.launchedNodeName = nodeName;
            }
        }
        return new LaunchOutcome(proc, spawnElapsedMs, pidRecorded, resolvedPid);
    }

    // ------------------------------------------------------------------
    // Leftover node process (audit finding 4)
    // ------------------------------------------------------------------

    /**
     * Wait — bounded — for the previous session's node process to
     * finish exiting before a new one is spawned on the same database.
     * Two ways the leftover can be known:
     *
     *   (a) same service instance: {@link #nodeProcess} still holds
     *       the Process object, so identity is certain and isAlive()
     *       is the exit test (this is the old F-2 wait, unified here);
     *   (b) recreated instance: only the pid file survives. A recorded
     *       pid is trusted ONLY after /proc/&lt;pid&gt;/cmdline shows
     *       it still belongs to a quantus process — pids are reused,
     *       and waiting on (let alone disturbing) a stranger's process
     *       is not an option. A pid that fails the check is stale: the
     *       record is dropped and startup proceeds at once.
     *
     * The leftover is NEVER killed — this method only waits. If it is
     * still alive after {@link #LEFTOVER_WAIT_MS}, the launch fails
     * with an IOException whose message (and a log line) records why,
     * instead of spawning a second node that would lose the fight for
     * ParityDB's exclusive lock.
     *
     * Called with {@link #PID_LOCK} held (from launch()); the pid-file
     * reads and clears inside ride on the same lock (monitors are
     * reentrant), which is what makes this check indivisible from the
     * spawn that follows it.
     */
    private void awaitLeftoverNodeProcess() throws IOException {
        final Process prev = nodeProcess;
        final long leftoverPid;
        final boolean trackByObject;
        if (prev != null && prev.isAlive()) {
            leftoverPid = pidOf(prev);
            trackByObject = true;
        } else {
            long filePid = readPidFile();
            if (filePid > 0 && isQuantusProcess(filePid)) {
                leftoverPid = filePid;
                trackByObject = false;
            } else {
                if (filePid > 0) {
                    // Stale record: process gone, or pid reused.
                    clearPidFileIfMatches(filePid);
                }
                return;
            }
        }
        logApp("previous node process (pid " + leftoverPid
                + ") still exiting; waiting up to 5s");
        long deadline = SystemClock.elapsedRealtime() + LEFTOVER_WAIT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(LEFTOVER_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for the"
                        + " previous node process (pid " + leftoverPid + ") to exit");
            }
            boolean alive = trackByObject
                    ? prev.isAlive() : isQuantusProcess(leftoverPid);
            if (!alive) {
                clearPidFileIfMatches(leftoverPid);
                return;
            }
        }
        logApp("previous node process (pid " + leftoverPid
                + ") still running after 5s; not starting a second node");
        throw new IOException("previous node process (pid " + leftoverPid
                + ") has not exited yet; refusing to start a second node"
                + " on the same database");
    }

    /**
     * True while /proc says this pid is (still) one of ours: the
     * directory exists and its cmdline names the node binary. Any read
     * failure reads as "not ours" — a process we cannot identify is
     * never waited on.
     */
    private static boolean isQuantusProcess(long pid) {
        File procDir = new File("/proc/" + pid);
        if (!procDir.isDirectory()) {
            return false;
        }
        try (FileInputStream in = new FileInputStream(new File(procDir, "cmdline"));
             ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[512];
            int n;
            while ((n = in.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8)
                    .contains("quantus");
        } catch (IOException e) {
            return false;
        }
    }

    private File pidFile() {
        return new File(getFilesDir(), PID_FILE_NAME);
    }

    /**
     * The pid of a spawned child, or -1 when the runtime will not
     * say. Every Process.pid() call in this class goes through here.
     * Process.pid() is the one call in the spawn sequence whose
     * failure mode is not an IOException: on a runtime where it is
     * missing or unsupported it throws an Error (NoSuchMethodError,
     * UnsupportedOperationException), and an Error escaping the
     * worker thread is fatal to the whole app process — the vc17
     * field failure died in exactly this window, right after the
     * "launch:" log line, before "node started" or "start failed"
     * could ever be recorded. A -1 here simply means "no pid": the
     * pid record is a cross-instance courtesy, and bookkeeping must
     * never be able to fail — or crash — a launch.
     */
    private static long pidOf(Process p) {
        return pidOf(p, null);
    }

    /**
     * As {@link #pidOf(Process)}, additionally handing the caught
     * throwable back through {@code errorOut} (when given), so a
     * caller that wants to NAME the failure in the log — the
     * versionCode 18 pid resolution does — need not probe pid() a
     * second time to find out why it failed.
     */
    private static long pidOf(Process p, Throwable[] errorOut) {
        try {
            return p.pid();
        } catch (Throwable t) {
            if (errorOut != null && errorOut.length > 0) {
                errorOut[0] = t;
            }
            return -1;
        }
    }

    /**
     * Resolve a freshly spawned child's pid, exactly once per launch
     * (versionCode 18). Primary route: {@link #pidOf}. The versionCode
     * 17 field run confirmed that on the target device class
     * Process.pid() itself throws, so every launch logged "pid record
     * FAILED for pid -1": the node ran, but the finding-4 pid record
     * — and with it the cross-instance leftover wait — stayed
     * degraded on that runtime. The fallback is a bounded /proc scan
     * for this process's own child running the node binary (see
     * {@link #scanForNodeChildPid}). Whichever route succeeds, the
     * outcome is logged when the fallback was needed, including the
     * class of the throwable pid() threw. The whole method is
     * degrade-safe by construction: any failure anywhere yields -1
     * ("no pid"), and bookkeeping must never fail — or crash — the
     * launch it serves (the versionCode 17 rule, kept). Called from
     * launch() with {@link #PID_LOCK} held, as part of the spawn
     * sequence; the scan's bounded retry therefore also runs under
     * that lock, which launches do not mind — they are rare and
     * already serialized by it.
     */
    private long resolveChildPid(Process proc) {
        try {
            Throwable[] pidError = new Throwable[1];
            long pid = pidOf(proc, pidError);
            if (pid > 0) {
                return pid;
            }
            String why = pidError[0] != null
                    ? pidError[0].getClass().getName() : "no error captured";
            long scanned = scanForNodeChildPid();
            if (scanned > 0) {
                logApp("pid() unavailable (" + why + "); recovered pid "
                        + scanned + " via /proc scan");
                return scanned;
            }
            logApp("pid() unavailable (" + why + "); /proc scan found"
                    + " no child pid — pid record skipped");
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Find the node child's pid without Process.pid(): this process's
     * children (by /proc ppid) whose cmdline names the node library.
     * The child can still be mid-exec in the instant after
     * ProcessBuilder.start() returns, so the scan retries for about
     * a second (10 passes, 100 ms apart) before giving up. Exactly
     * one candidate is the answer. If several survive every pass —
     * e.g. a previous child still exiting alongside the new one —
     * only an exact argv[0] match with the node binary's absolute
     * path may disambiguate, and only if it leaves exactly one;
     * anything else returns -1 rather than guessing a pid that the
     * finding-4 leftover wait would then trust. Best-effort
     * throughout: every read failure simply removes that entry from
     * consideration (a zombie child, for instance, has an empty
     * cmdline and never becomes a candidate).
     */
    private long scanForNodeChildPid() {
        // android.os.Process, not java.lang.Process: the app
        // process's own pid is always available here.
        final int myPid = android.os.Process.myPid();
        final String binPath = new File(
                getApplicationInfo().nativeLibraryDir, NODE_LIB).getAbsolutePath();
        List<Long> candidates = new ArrayList<>();
        for (int pass = 0; pass < 10; pass++) {
            candidates = collectNodeChildPids(myPid);
            if (candidates.size() == 1) {
                return candidates.get(0);
            }
            if (pass < 9) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
        }
        if (candidates.size() > 1) {
            long match = -1;
            for (long pid : candidates) {
                if (binPath.equals(argv0(pid))) {
                    if (match != -1) {
                        return -1; // two exact matches: refuse to guess
                    }
                    match = pid;
                }
            }
            return match;
        }
        return -1;
    }

    /**
     * One /proc pass: the pids whose parent is {@code myPid} and
     * whose cmdline names the node library. /proc is world-readable
     * enough for a process to see its own children; any entry that
     * cannot be read is skipped, never fatal.
     */
    private static List<Long> collectNodeChildPids(int myPid) {
        List<Long> out = new ArrayList<>();
        String[] entries = new File("/proc").list();
        if (entries == null) {
            return out;
        }
        for (String name : entries) {
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) {
                continue;
            }
            final long pid;
            try {
                pid = Long.parseLong(name);
            } catch (NumberFormatException e) {
                continue;
            }
            if (readParentPid(pid) != myPid) {
                continue;
            }
            String cmdline = readCmdline(pid);
            if (cmdline != null && cmdline.contains(NODE_LIB)) {
                out.add(pid);
            }
        }
        return out;
    }

    /**
     * The parent pid from /proc/&lt;pid&gt;/stat, or -1 when
     * unreadable. Field 2 (comm) is parenthesized and may itself
     * contain spaces or parentheses, so parsing starts after the
     * LAST ')': the whitespace-separated fields that follow are
     * state first, then ppid.
     */
    private static long readParentPid(long pid) {
        byte[] data = readProcFile(pid, "stat");
        if (data == null) {
            return -1;
        }
        String stat = new String(data, StandardCharsets.UTF_8);
        int close = stat.lastIndexOf(')');
        if (close < 0 || close + 1 >= stat.length()) {
            return -1;
        }
        String[] fields = stat.substring(close + 1).trim().split("\\s+");
        if (fields.length < 2) {
            return -1;
        }
        try {
            return Long.parseLong(fields[1]); // [0]=state, [1]=ppid
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The NUL-separated cmdline of a process as one string, or null when unreadable. */
    private static String readCmdline(long pid) {
        byte[] data = readProcFile(pid, "cmdline");
        if (data == null) {
            return null;
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    /** argv[0] of a process — the first NUL-separated cmdline token — or null. */
    private static String argv0(long pid) {
        String cmdline = readCmdline(pid);
        if (cmdline == null) {
            return null;
        }
        int nul = cmdline.indexOf('\0');
        return nul >= 0 ? cmdline.substring(0, nul) : cmdline;
    }

    private static byte[] readProcFile(long pid, String name) {
        try (FileInputStream in = new FileInputStream(
                new File("/proc/" + pid, name))) {
            return in.readAllBytes();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Record a spawned child's pid, atomically (tmp + rename), and
     * report whether the record is in place. Called with
     * {@link #PID_LOCK} held, as part of the spawn sequence itself.
     * A failed record does not fail the launch — the pid file is a
     * cross-instance courtesy and the same-instance wait still covers
     * this process through its Process handle — but the caller
     * records the failure; it is never silent (finding 4, re-audit).
     */
    private boolean writePidFile(long pid) {
        File tmp = new File(getFilesDir(), PID_FILE_NAME + ".tmp");
        boolean done = false;
        try {
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(Long.toString(pid).getBytes(StandardCharsets.UTF_8));
            }
            done = tmp.renameTo(pidFile());
        } catch (IOException | RuntimeException ignored) {
            // Reported by the caller via the return value. The
            // RuntimeException arm is belt and braces (vc17): nothing
            // in the pid-recording path may ever escape and turn into
            // a failure — or a crash — of the launch itself.
        } finally {
            if (!done) {
                tmp.delete();
            }
        }
        return done;
    }

    /** The recorded pid, or -1 when there is no usable record. */
    private long readPidFile() {
        try {
            File f = pidFile();
            if (!f.isFile()) {
                return -1;
            }
            byte[] data;
            try (FileInputStream in = new FileInputStream(f)) {
                data = in.readAllBytes();
            }
            return Long.parseLong(
                    new String(data, StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Remove the pid file, but only while it still names this pid.
     * The read-check-delete is indivisible under {@link #PID_LOCK}
     * (finding 4, re-audit): without the lock, a late cleanup could
     * pass the check, watch a newer session overwrite the record, and
     * then delete the NEWER process's pid.
     */
    private void clearPidFileIfMatches(long pid) {
        synchronized (PID_LOCK) {
            if (readPidFile() == pid) {
                pidFile().delete();
            }
        }
    }

    /**
     * Copy the bundled Mozilla CA roots (assets/cacert.pem, the official
     * curl.se extract) into filesDir so the node process can read them
     * by path, and return that path. Refreshed when the bundled copy's
     * size changes. Returns null when the copy is unavailable; the
     * caller then relies on the SSL_CERT_DIR hint alone.
     */
    private String ensureCaBundle() {
        try {
            byte[] data;
            try (InputStream in = getAssets().open("cacert.pem");
                 ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) != -1) {
                    buf.write(chunk, 0, n);
                }
                data = buf.toByteArray();
            }
            File out = new File(getFilesDir(), "cacert.pem");
            if (!out.isFile() || out.length() != data.length) {
                // F-7: write atomically. A kill mid-write straight
                // into cacert.pem would leave a truncated bundle that
                // the node then fails telemetry TLS with. Write the
                // full copy to a tmp file, verify its length, and
                // rename it over the old file; on any failure remove
                // the tmp and report the bundle as unavailable (the
                // caller then relies on the SSL_CERT_DIR hint alone).
                File tmp = new File(getFilesDir(), "cacert.pem.tmp");
                boolean copied = false;
                try {
                    try (FileOutputStream fos = new FileOutputStream(tmp)) {
                        fos.write(data);
                    }
                    copied = tmp.length() == data.length && tmp.renameTo(out);
                } finally {
                    if (!copied) {
                        tmp.delete();
                    }
                }
                if (!copied) {
                    logApp("CA bundle copy failed (write/rename)");
                    return null;
                }
            }
            return out.getAbsolutePath();
        } catch (Exception e) {
            logApp("CA bundle unavailable: " + e);
            return null;
        }
    }

    /**
     * End-of-session teardown for the worker / timeout paths. The
     * whole decision — "is my generation still the live one?" — and
     * every write that follows from it happen inside {@link #stateLock}:
     * if a newer session has already started (or a stop has already
     * settled this one), a delayed finish from the old worker must not
     * overwrite the new session's state with STOPPED, rewind its
     * uptime base, or stopSelf() the service out from under it (N-2).
     * In that case this call does nothing at all. The service
     * self-stop is part of the same locked unit (finding 3,
     * re-audit): generation re-check, teardown writes and
     * stopServiceNowLocked() happen without releasing the lock in
     * between, so a START cannot be delivered into the gap and be
     * stopped together with this session.
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
            stopServiceNowLocked();
        }
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
                // F-3: the per-line work (log encode/write, attempt
                // capture) must never kill this thread. If it died,
                // logging and per-attempt evidence would stop — and
                // once the pipe fills, the node's own writes block and
                // the node stalls. Note the failure and keep draining.
                // (The note itself goes through the same sink, so it
                // is best-effort.) Stream-level IOException from
                // readLine() still ends the thread, in the catch below.
                try {
                    if (logSink != null) {
                        logSink.write(line);
                    }
                    capture.add(line);
                } catch (Exception e) {
                    try {
                        logApp("output drain: line dropped after error: " + e);
                    } catch (Exception ignored) {
                    }
                }
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
            // P-4: fixed rate, not fixed delay. The sleep used to be a
            // flat POLL_MS after the work, so the real period was "two
            // RPC calls + 3 s" — up to ~10 s when both calls ran into
            // their timeouts — which also stretched the periodic
            // uptime save riding this loop. Time the cycle's work and
            // sleep only the remainder; if the work alone overruns
            // POLL_MS the next cycle starts at once (floor 0 is safe:
            // pollOnce itself blocks on the RPC, so this cannot spin).
            long cycleStartMs = SystemClock.elapsedRealtime();
            try {
                pollOnce(gen);
            } catch (Exception ignored) {
                // A failed poll is a state ("starting"), never a crash.
            }
            saveUptimePeriodically(gen);
            long sleepMs = Math.max(0,
                    POLL_MS - (SystemClock.elapsedRealtime() - cycleStartMs));
            try {
                Thread.sleep(sleepMs);
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
            updateNotificationMaybe(gen);
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
        updateNotificationMaybe(gen);
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
            // Audit finding 6: never follow a redirect away from the
            // loopback endpoint, and never read an unbounded response —
            // whatever answers on this port (the node, or anything
            // else that grabbed it first) gets at most 64 KiB to
            // convince us. A longer answer is a failed call.
            conn.setInstanceFollowRedirects(false);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            if (conn.getResponseCode() != 200) {
                return null;
            }
            byte[] resp;
            try (java.io.InputStream in = conn.getInputStream()) {
                resp = readBounded(in, RPC_MAX_RESPONSE_BYTES);
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

    /**
     * Read a stream to its end, refusing to buffer more than
     * {@code maxBytes}: crossing the cap is an IOException, which the
     * RPC caller treats like any other failed call. (The log-file
     * reader is deliberately NOT routed through here — that file is
     * the app's own, hard-capped by rotation.)
     */
    private static byte[] readBounded(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            if (buf.size() + n > maxBytes) {
                throw new IOException("response exceeds " + maxBytes + " bytes");
            }
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
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

    private void updateNotificationMaybe(int gen) {
        // Audit finding 5: a poll that was in flight while its session
        // was being torn down must not (re-)post the status
        // notification after the stop has settled — the generation
        // check drops stale sessions, and the STOPPED check covers a
        // stop that settled without a generation change of its own.
        // (The stop paths also cancel the notification outright when
        // they actually end the service; see stopServiceNowLocked().)
        //
        // Re-audit: versionCode 13 made these checks OUTSIDE any lock,
        // so a stop could settle — and cancel the notification —
        // between a passed check and the notify() below, and the post
        // would land afterwards anyway. The whole check-and-post now
        // runs under stateLock, the same lock the stop side holds
        // while it tears down and cancels, so check and act are
        // serialized against the cancel: either this post completes
        // first and the stop's cancel removes it, or the stop settles
        // first and the checks above refuse the post. The locked
        // section is kept to the checks and the one notify() call;
        // nothing here calls back into the service, so no new lock
        // ordering is introduced.
        synchronized (stateLock) {
            if (gen != sessionGen.get()) {
                return;
            }
            NodeState st = NodeState.get();
            if (st.status == NodeState.Status.STOPPED) {
                return;
            }
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
}
