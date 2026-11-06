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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
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
    /**
     * Overall deadline for one RPC call, start to fully-read response
     * (versionCode 25, audit finding L3). The connect/read timeouts
     * bound each individual wait, not the call: a response trickling
     * in one byte at a time stays under the per-read timeout forever
     * while the byte cap takes ~18 hours to reach, parking the single
     * poller (and the uptime saves riding it) for the duration. This
     * monotonic deadline caps the whole call instead.
     */
    private static final long RPC_OVERALL_TIMEOUT_MS = 5000;
    /**
     * Grace past the RPC deadline before the deadline watchdog
     * force-disconnects a still-running call (versionCode 25,
     * re-audit residual of finding L3): the watchdog exists for the
     * waits no in-call deadline check can reach — the response
     * header wait above all — and the grace keeps it from racing a
     * call that is completing on time.
     */
    private static final long RPC_WATCHDOG_GRACE_MS = 500;
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
    /**
     * Spawn-intent marker (versionCode 25, audit finding M3): written
     * inside {@link #PID_LOCK} immediately BEFORE ProcessBuilder.start()
     * and removed again once the child's pid is on record. A marker
     * that survives its writer therefore means "an incarnation of this
     * app died — or its pid record failed — between deciding to spawn
     * and recording the child": exactly the window in which a spawned
     * child used to become invisible to every later detection path.
     * The marker holds the spawning process's incarnation id (see
     * {@link #PROCESS_INCARNATION_ID}), so a later process can tell a
     * dead incarnation's interrupted spawn from its own in-flight one.
     */
    private static final String SPAWN_MARKER_FILE_NAME = "node-spawn.intent";
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

    /**
     * How long the not-accepted teardown waits for the destroyed
     * child to actually exit before clearing its pid record
     * (versionCode 22, external audit finding 1). Matches the
     * leftover-process wait's own bound.
     */
    private static final long DESTROY_EXIT_WAIT_MS = 5000;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean pollerOn = new AtomicBoolean(false);

    /**
     * This app process's incarnation id: generated once, when the
     * class is loaded — which is to say once per app-process lifetime
     * — and stamped into every pid record and spawn-intent marker
     * this process writes. It is the provenance evidence the
     * orphan-recovery gate of {@link #awaitLeftoverNodeProcess}
     * judges by (versionCode 25, audit finding M2): a leftover whose
     * record carries a DIFFERENT incarnation id (or none — records
     * written before versionCode 25 hold a bare pid) was spawned by
     * a previous, dead incarnation of this app and has no living
     * owner; a leftover carrying THIS id was spawned by this process
     * and is never orphan-killed — it has an owner, or its owner
     * already destroyed it, so it is only ever waited on.
     *
     * Until versionCode 24 the gate instead asked whether the
     * asking session was the first one this process had established
     * (audit finding A-4), a flag consumed at establishment time and
     * never restored: when the first session's recovery stood down
     * (a STOP landing inside the leftover wait bumps the generation,
     * and the A-6 revalidation then correctly refuses the kill),
     * every later session of the same process was refused the
     * recovery forever, and the orphan outlived them all (external
     * audit of v0.1.4, finding M2 — reproduced on a JVM harness by
     * the auditor). Provenance does not wear out that way: standing
     * down changes nothing about who spawned the leftover, so the
     * next session judges the same record and can complete the
     * recovery. The A-6 last-moment revalidation (generation still
     * current, record unchanged since the wait began) is kept
     * unchanged on top of the provenance check.
     */
    private static final String PROCESS_INCARNATION_ID = newIncarnationId();

    /**
     * Set when an orphan-recovery EPISODE involving at least one
     * termination has completed in this process: every orphan
     * attested by the foreign evidence is gone — terminated and
     * its exit confirmed, or exited on its own during the wait —
     * and a final scan found none left (versionCode 25, re-audit
     * finding N4 — an episode may recover several orphans; the
     * flag is NOT set between them, or the rest of the episode
     * would stand itself down). From then on, no further orphan
     * kill is licensed in this process: the record such a kill
     * would act on has been cleared and rewritten by this
     * process's own spawn, so any later pid-file leftover is this
     * process's own by construction.
     * Read and written only inside
     * {@link #awaitLeftoverNodeProcess}, which runs under
     * {@link #PID_LOCK}; volatile for the same belt-and-braces
     * visibility as the other cross-instance state.
     */
    private static volatile boolean orphanRecoveryCompletedInProcess = false;

    private static String newIncarnationId() {
        byte[] bytes = new byte[8];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(16);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

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
    /**
     * The last isSyncing value a health response actually STATED
     * (versionCode 24, audit finding B23-B1): an absent — or
     * non-boolean — isSyncing field is "not said", not false, the
     * same principle F8 and B-1 applied to highestBlock and peers.
     * Updated only inside pollOnce's generation-checked locked
     * batch, and reset at session establishment with the other
     * retained poll values. False before the first stated value —
     * the same default the old optBoolean fallback produced there.
     */
    private volatile boolean lastKnownSyncing = false;
    /**
     * Whether this session has EVIDENCE about synchronization: at
     * least one system_syncState response in this session carried a
     * valid block height (currentBlock or highestBlock >= 0)
     * (versionCode 25, audit findings L1 / F-02). Until versionCode
     * 24 a session could be judged SYNCED with no evidence at all:
     * with the retained values still at their initial -1, a poll
     * whose health call succeeded and whose syncState call failed
     * derived "at head" from highestBlock < 0 and reported "sync
     * complete" — and under a persistent partial failure (health
     * answering, syncState failing) it re-derived that on every poll
     * indefinitely. SYNCED now requires this flag: before the first
     * evidenced read the status stays SYNCING however the retained
     * values compare. Read and written only inside pollOnce's
     * generation-checked locked batch and at session establishment,
     * both under {@link #stateLock}.
     */
    private volatile boolean syncEvidenceSeen = false;
    /** One in-flight RPC connection, tagged with its session's generation. */
    private static final class InFlightRpc {
        final HttpURLConnection conn;
        final int gen;

        InFlightRpc(HttpURLConnection conn, int gen) {
            this.conn = conn;
            this.gen = gen;
        }
    }

    /**
     * The RPC connection currently in flight, if any, so a teardown
     * can abort it (versionCode 25, audit finding L3): a poll blocked
     * in a slow response otherwise outlives its session's stop until
     * the overall deadline expires. Registration is
     * generation-tagged and compare-and-set (versionCode 25,
     * re-audit finding N1): a call installs its connection only
     * while no NEWER generation owns the slot, and deregisters by
     * identity, so a stale poller can neither overwrite nor clear a
     * newer session's registration — the failure this replaces was
     * exactly that overwrite, after which a teardown disconnected
     * the dead connection and missed the live one. Teardown takes
     * the slot atomically (see {@link #disconnectInFlightRpc}).
     * The guarantee is best-effort, deliberately: in the worst
     * interleaving the slot can hold a stale session's connection
     * when a teardown fires, and disconnecting THAT is harmless
     * (its own call is already doomed), while the live call is
     * still bounded by its deadline and its deadline watchdog —
     * the deadline, not this slot, is the hard bound.
     */
    private final java.util.concurrent.atomic.AtomicReference<InFlightRpc>
            inFlightRpc = new java.util.concurrent.atomic.AtomicReference<>();
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

        // Establish the session — generation first, before either
        // thread starts — under the lifecycle lock, and hand the
        // generation to BOTH the worker and the poller. Previously the
        // worker ran generation-less and the generation was only fixed
        // after it had started, so a worker that died instantly (or
        // finished late, after a stop + fresh start) wrote state and
        // uptime for a session it did not belong to (N-2, N-3).
        //
        // The establishment runs BEFORE the first foreground post
        // (versionCode 23, audit findings A-2/B-2): the block is
        // light — no I/O beyond a preferences read — and the first
        // notification is built from NodeState, so establishing
        // first is what keeps the previous session's block/peers out
        // of it. Foreground still goes up before any thread starts
        // or any process spawns, which is all the promptness the
        // platform asks for.
        final int gen;
        synchronized (stateLock) {
            gen = sessionGen.incrementAndGet();
            // This START created the session: its startId is the one a
            // later end-of-session uses for stopSelfResult() (finding 3).
            sessionStopId = startId;
            NodeState st = NodeState.get();
            st.status = NodeState.Status.STARTING;
            // versionCode 23 (audit findings A-2/B-2): the retained
            // chain values belong to the previous session. They are
            // reset here, with the other per-session state, so the
            // new session's first status judgement, its STARTING
            // dashboard and its first notification never present the
            // old session's best/highest/peers as current. (With
            // F8's retained-value judgement, a stale highest could
            // even mark an unsynced new session SYNCED on its first
            // poll.) The stopped screen keeps showing the last
            // session's values until a new start — that is this
            // reset's placement doing exactly that, on purpose.
            st.bestBlock = -1;
            st.highestBlock = -1;
            st.peers = -1;
            st.lastExitCode = null;
            st.lastNote = null;
            st.lastAttempts = java.util.Collections.emptyList();
            // versionCode 26: the stop classification and the
            // per-session display anchors start empty for the new
            // session. sessionAcceptedElapsedMs stamps the moment
            // this start was accepted (session establishment): the
            // UI's "current uptime" counts from here — not from the
            // button press, and not from the spawn (the cumulative
            // accrual keeps its own spawn anchor, finding F-05).
            st.lastStopKind = NodeState.StopKind.NONE;
            st.sessionAcceptedElapsedMs = SystemClock.elapsedRealtime();
            st.lastObservedBestBlock = -1;
            st.lastObservedElapsedMs = -1;
            // No launch snapshot yet for this session: the worker fills
            // it in when the process is actually launched. Until then
            // the UI must not claim a settings/restart mismatch against
            // a previous run.
            st.launchedTelemetry = null;
            st.launchedNodeName = null;
            baseUptimeMs = TrackStore.uptimeMs(this);
            // versionCode 25 (audit finding F-05): the uptime anchor
            // is NOT set here. Uptime accrues from the moment a node
            // process is actually spawned and accepted — the worker
            // sets sessionStartElapsedMs to the spawn timestamp at
            // registration — so the leftover wait inside launch()
            // (up to 5 s) and everything before the spawn no longer
            // inflates the total, and a session whose launch is
            // refused or fails before any spawn accrues nothing at
            // all. -1 is the "not accruing" value every uptime path
            // already checks for.
            sessionStartElapsedMs = -1;
            lastUptimeSaveElapsedMs = -1;
            prevBestBlock = -1;
            lastKnownSyncing = false;
            syncEvidenceSeen = false;
            // External audit round 2 (F9): the notification de-dup
            // key must not survive into a new session. The
            // startForeground() above posted this session's first
            // notification directly, without going through
            // updateNotificationMaybe, so it never updated the key;
            // a leftover key from the previous session could then
            // make the poller skip a post whose text never reached
            // the shade for THIS session, leaving the notification
            // stuck on the previous session's wording. Reset here,
            // with the other per-session state (this block runs
            // under stateLock, the same lock updateNotificationMaybe
            // holds while it reads and writes the key).
            lastNotifText = "";
        }

        // Foreground now — promptly, before the threads and the
        // process spawn, and (since versionCode 23) after the
        // session state above has been established, so this first
        // notification already describes THIS session.
        startForegroundWith(buildNotification(
                NodeState.label(this, NodeState.Status.STARTING)));

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
        Process destroyedProcess = null;
        synchronized (stateLock) {
            started.set(false);
            sessionGen.incrementAndGet();
            pollerOn.set(false);
            disconnectInFlightRpc();
            Process p = nodeProcess;
            if (p != null) {
                p.destroy();
                destroyedProcess = p;
            }
            saveUptimeLocked();
            sessionStartElapsedMs = -1; // no live session any more
            NodeState st = NodeState.get();
            st.status = NodeState.Status.STOPPED;
            st.serviceRunning = false;
        }
        // versionCode 25 (audit finding F-03): the destroy above is
        // delivered immediately, as before — onDestroy runs during
        // teardown on the main thread, where a bounded wait does not
        // belong — but the escalation behind it (bounded wait, then
        // destroyForcibly for a child that ignores destroy()) now
        // runs on a daemon thread, so a surviving child of THIS
        // destroy is still force-killed whenever the app process
        // itself lives on. If the whole process is dying, the child
        // becomes an orphan the next incarnation recovers through
        // the pid record instead.
        escalateAfterDestroyAsync(destroyedProcess);
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
        Process destroyedProcess = null;
        synchronized (stateLock) {
            final int gen = sessionGen.get();
            started.set(false);
            logApp("foreground service timed out by the system; stopping node");
            NodeState.get().lastNote = I18n.get(this, StringsTable.NOTE_FGS_TIMEOUT);
            // versionCode 26: display classification — this stop is
            // the system's time limit, not a failure (spec A (c)).
            markStopKindLocked(NodeState.StopKind.TIME_LIMIT);
            disconnectInFlightRpc();
            Process p = nodeProcess;
            if (p != null) {
                p.destroy();
                destroyedProcess = p;
            }
            finishAsStopped(gen, null);
        }
        // As in onDestroy (versionCode 25, finding F-03): the prompt
        // self-stop above is untouched; only the post-destroy
        // escalation is handed to the daemon thread.
        escalateAfterDestroyAsync(destroyedProcess);
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
        Process stoppedProcess = null;
        synchronized (stateLock) {
            // This STOP is the newest command the service has seen, so
            // its startId takes over as the session's stop authority
            // (finding 3): ending the service below is then legitimate
            // even though it was a START that created the session.
            sessionStopId = stopStartId;
            started.set(false);
            sessionGen.incrementAndGet();
            pollerOn.set(false);
            disconnectInFlightRpc();
            stoppedProcess = nodeProcess;
            saveUptimeLocked();
            sessionStartElapsedMs = -1; // no live session any more
            // versionCode 26: display classification — a user stop,
            // even when the destroyed child exits non-zero (spec A (d)).
            markStopKindLocked(NodeState.StopKind.USER);
            NodeState.get().status = NodeState.Status.STOPPED;
            // The self-stop is decided and executed inside this same
            // locked section (finding 3, re-audit): no START can slip
            // between the teardown above and stopSelfResult() and be
            // torn down together with this session.
            stopServiceNowLocked();
        }
        // versionCode 25 (audit finding F-03): the destroy itself is
        // delivered on this thread, immediately after the teardown
        // above has settled `started` and the generation — the B-2
        // ordering is unchanged — but it is no longer the ONLY step.
        // Until versionCode 24 every stop path sent destroy() once
        // and never even waited: a child that ignored it kept the
        // worker parked in waitFor(), kept the process reference and
        // the pid record pointing at itself, and every later start
        // in this process was refused in the leftover wait for as
        // long as the app lived. The wait-and-escalate half now runs
        // on a daemon thread (a bounded wait does not belong on the
        // main thread): if the child is still alive after the bound,
        // it is destroyed forcibly — this session's OWN child, whose
        // ownership is certain through its Process object — and the
        // worker's ordinary exit path then clears the reference and
        // the record, un-wedging the next start.
        if (stoppedProcess != null) {
            stoppedProcess.destroy();
            escalateAfterDestroyAsync(stoppedProcess);
        }
    }

    /**
     * The post-destroy escalation of finding F-03 (versionCode 25):
     * wait — bounded — for a destroyed child to actually exit, and
     * if it does not, destroy it forcibly and wait once more with
     * the same bound. Runs on a short-lived daemon thread; the
     * caller has ALREADY delivered destroy() and settled the session
     * teardown, so nothing here can resurrect or re-own anything —
     * the thread only watches the exit and logs the outcome. The
     * child's own worker performs the reference/record cleanup when
     * its waitFor() returns, exactly as for a cooperative exit. A
     * child still alive after the forcible bound keeps its pid
     * record (the versionCode 23 A-1 rule): the leftover path of a
     * later start then waits on it and refuses, never double-starts.
     */
    private void escalateAfterDestroyAsync(final Process p) {
        if (p == null) {
            return;
        }
        Thread t = new Thread(() -> {
            if (waitForExitBounded(p, DESTROY_EXIT_WAIT_MS)) {
                return;
            }
            logApp("node process did not exit within " + DESTROY_EXIT_WAIT_MS
                    + "ms of destroy(); escalating to destroyForcibly()");
            try {
                p.destroyForcibly();
            } catch (RuntimeException e) {
                logApp("destroyForcibly() failed: " + e);
                return;
            }
            if (!waitForExitBounded(p, DESTROY_EXIT_WAIT_MS)) {
                logApp("node process still running after destroyForcibly();"
                        + " leaving it to the leftover-process path");
            }
        }, "node-stop-escalation");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Abort the RPC call currently in flight, if any (versionCode
     * 25, audit finding L3). Called from the teardown paths with
     * {@link #stateLock} held. The slot is taken atomically, so
     * exactly one teardown disconnects a given registration, and a
     * registration a newer session installs afterwards is never
     * retroactively hit by this call. What the slot holds at that
     * moment is the dying session's connection in every realistic
     * interleaving — a newer session can only be established (and
     * its poller can only register) after this teardown releases
     * the lock — but the contract is stated as it is implemented
     * (versionCode 25, re-audit finding N1): the disconnect hits
     * whatever is registered, a stale registration is harmless to
     * disconnect, and a live call's hard bound is its overall
     * deadline and deadline watchdog, not this best-effort abort.
     * disconnect() is the documented way to abort a blocked
     * HttpURLConnection from another thread: the poll's read
     * fails, rpcCall returns null, and the poller's
     * generation-checked batch discards the result as usual.
     */
    private void disconnectInFlightRpc() {
        InFlightRpc slot = inFlightRpc.getAndSet(null);
        if (slot != null) {
            try {
                slot.conn.disconnect();
            } catch (RuntimeException ignored) {
                // Aborting is best-effort; the overall deadline in
                // rpcCall is the backstop.
            }
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
                    // versionCode 26: display classification.
                    markStopKindLocked(NodeState.StopKind.BINARY_MISSING);
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
                        // versionCode 26: display classification —
                        // both launch attempts failed (spec A (a)).
                        markStopKindLocked(NodeState.StopKind.START_FAILED);
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
                        // versionCode 26: display classification —
                        // both launch attempts failed (spec A (a)).
                        markStopKindLocked(NodeState.StopKind.START_FAILED);
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
            // joins the session: it is destroyed here, its exit is
            // awaited (bounded, outside every lock), and only then is
            // its pid record removed (identity-checked, under
            // PID_LOCK), so nothing of it is left for a later launch
            // to trip over — while a launch that arrives during the
            // wait can still find the record and wait on the process
            // itself. The destroy, the wait and the pid clear all run
            // AFTER the stateLock is released: no stateLock ->
            // PID_LOCK nesting is created.
            boolean accepted;
            synchronized (stateLock) {
                accepted = started.get() && gen == sessionGen.get();
                if (accepted) {
                    nodeProcess = proc;
                    // versionCode 25 (audit finding F-05): the
                    // session's uptime anchor is set HERE — to the
                    // moment ProcessBuilder.start() returned for the
                    // FIRST accepted spawn of this session — not at
                    // session establishment. Everything before the
                    // spawn (the leftover wait above all, up to 5 s)
                    // no longer counts as node uptime, and a session
                    // refused before any spawn keeps the -1 anchor
                    // and accrues nothing. A linker64 retry does not
                    // re-anchor: its spawn follows the first attempt's
                    // quick exit immediately, and the anchor stays at
                    // the first spawn.
                    if (sessionStartElapsedMs < 0) {
                        sessionStartElapsedMs = spawnElapsedMs >= 0
                                ? spawnElapsedMs
                                : SystemClock.elapsedRealtime();
                        lastUptimeSaveElapsedMs = sessionStartElapsedMs;
                    }
                }
            }
            if (!accepted) {
                logApp("session ended while the node was starting; destroying it");
                proc.destroy();
                // versionCode 22 (external audit finding 1): wait,
                // bounded, for the destroyed child to actually exit
                // BEFORE clearing its pid record. The record is the
                // only handle a later launch has for the leftover
                // wait; clearing it first let a quick stop -> start
                // overlap the still-closing old node, and the new
                // node then died on the old one's database lock. The
                // wait runs outside every lock, so no new lock
                // ordering is created: a session that starts
                // meanwhile finds this record and waits on the
                // process as designed.
                //
                // versionCode 23 (audit finding A-1): the record is
                // cleared ONLY when that exit was actually
                // confirmed. versionCode 22 ignored the wait's
                // outcome and cleared unconditionally, so a child
                // that outlived the bound (or an interrupt cut the
                // wait short) lost its record anyway — reopening,
                // for exactly those cases, the double-start the
                // wait was meant to close. When the exit is not
                // confirmed the record now stays, as the comment
                // above always claimed it did: a later launch finds
                // it and keeps waiting on the process, and the
                // leftover path's own refusal bounds the outcome —
                // never a second node on the same database.
                boolean exited = waitForExitBounded(proc, DESTROY_EXIT_WAIT_MS);
                if (!exited) {
                    // versionCode 25 (audit finding F-03): the same
                    // escalation the stop paths use. This child is
                    // this session's own — ownership through its
                    // Process object is certain — and it has already
                    // been destroyed once; if it is merely slow, the
                    // forcible destroy changes nothing about the
                    // outcome, and if it is ignoring destroy() the
                    // record below no longer has to outlive it.
                    logApp("unaccepted child did not exit within "
                            + DESTROY_EXIT_WAIT_MS
                            + "ms of destroy(); escalating to destroyForcibly()");
                    try {
                        proc.destroyForcibly();
                    } catch (RuntimeException e) {
                        logApp("destroyForcibly() failed: " + e);
                    }
                    exited = waitForExitBounded(proc, DESTROY_EXIT_WAIT_MS);
                }
                if (exited) {
                    // Clear the pid record by the pid launch()
                    // resolved for THIS attempt (versionCode 18) —
                    // never by re-probing pid(), which is exactly
                    // the call that does not work on the affected
                    // runtimes.
                    if (resolvedPid > 0) {
                        clearPidFileIfMatches(resolvedPid);
                    }
                } else if (resolvedPid > 0) {
                    logApp("destroyed child (pid " + resolvedPid + ") still"
                            + " running after destroy() and destroyForcibly();"
                            + " leaving its pid record in place so a"
                            + " later launch still waits for it");
                }
                return;
            }
            logApp("node started (" + (triedLinker ? "via linker64" : "direct") + ")");

            Thread drain = new Thread(() -> drainOutput(running, capture), "node-output");
            drain.setDaemon(true);
            drain.start();

            int code = -1;
            boolean interrupted = false;
            try {
                for (;;) {
                    try {
                        code = running.waitFor();
                        break;
                    } catch (InterruptedException e) {
                        // versionCode 21 (audit finding 1): this catch
                        // used to destroy the process and return,
                        // abandoning the session — the service stayed
                        // "started" with no node behind it and
                        // finishAsStopped() never ran. Nothing
                        // interrupts this thread today; if that ever
                        // changes, destroy the process and keep waiting
                        // for its real exit instead, so the ordinary
                        // finish path below (attempt record, then
                        // finishAsStopped under the generation check)
                        // settles the session exactly as a plain exit
                        // does. The quick-failure linker64 retry is
                        // skipped for an interrupted attempt: an
                        // interrupt is a teardown, not a failed start.
                        interrupted = true;
                        running.destroy();
                    }
                }
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
            // versionCode 25 (audit finding F-05): close this
            // session's uptime accrual at the moment the exit was
            // observed, so the bookkeeping below (the drain join
            // alone can take up to 3 s) does not inflate the total
            // either. Generation-checked like every end-of-session
            // write: a session a stop already settled was folded by
            // that stop, and its anchor is already -1.
            synchronized (stateLock) {
                if (gen == sessionGen.get()) {
                    closeUptimeAtExitLocked();
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
            // The join is bounded at 3 s (1 s until versionCode 20,
            // audit finding 5): once the process has exited the pipe
            // is closed and the drain normally ends at once; the bound
            // only caps how long a large unflushed backlog can delay
            // this attempt's record.
            try {
                drain.join(3000);
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
            // An interrupted attempt is not retried either (see above).
            if (!interrupted && started.get() && gen == sessionGen.get()
                    && !triedLinker && quickFailure) {
                logApp("quick exit; retrying once via /system/bin/linker64");
                triedLinker = true;
                continue;
            }
            // versionCode 26: classify this end of session for the
            // UI (display only). Reaching the finish with the
            // generation still current and `started` still set means
            // no stop path settled the session — the child exited on
            // its own, and classifyWorkerExit tells a start that
            // never got going from a node that ran and then stopped.
            synchronized (stateLock) {
                if (gen == sessionGen.get() && started.get()) {
                    markStopKindLocked(
                            classifyWorkerExit(attempts, QUICK_EXIT_MS));
                }
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
            awaitLeftoverNodeProcess(gen);
            // versionCode 25 (external audit of v0.1.4, finding M4):
            // the leftover wait above can take up to 5 s, and a STOP
            // delivered during it was only noticed AFTER the spawn —
            // the session then started a node with the settings
            // captured before the wait, only for the acceptance
            // check to destroy it immediately. Re-check here, after
            // the wait — and once more after the marker write below
            // (the re-audit's residual of this finding): a session
            // that is no longer started, or no longer current,
            // spawns nothing. The refusal is an ordinary failed
            // launch for the worker; its generation checks keep the
            // linker64 retry and every state write from firing for
            // a stale session.
            if (!started.get() || gen != sessionGen.get()) {
                throw new IOException("start cancelled while waiting"
                        + " for the previous node process to exit");
            }
            // versionCode 25 (audit finding M3): stamp the
            // spawn-intent marker BEFORE the spawn, so a death of
            // this app process anywhere between here and the pid
            // record below leaves evidence the next incarnation can
            // act on (see SPAWN_MARKER_FILE_NAME). A failed marker
            // degrades that detection path only; it never fails the
            // launch, like a failed pid record.
            boolean markerWritten = writeSpawnMarker();
            if (!markerWritten) {
                logApp("spawn-intent marker could not be written;"
                        + " interrupted-spawn detection is degraded"
                        + " for this launch");
            }
            // versionCode 25 (re-audit residual of finding M4): the
            // re-check above sits BEFORE the marker write, and the
            // marker write is file I/O — a STOP completing inside
            // that write still reached the spawn below (the spawn's
            // product was then destroyed by the acceptance check,
            // so the window cost a brief run of a stopped session's
            // node, never a stuck state). STOP does not take
            // PID_LOCK, so no lock can close the sequence outright;
            // what CAN be closed is everything up to the spawn
            // itself: re-check once more, here, after the marker
            // write, so the only window left is the execution of
            // pb.start() itself — for which the acceptance refusal
            // remains, by design, the final defense.
            if (!started.get() || gen != sessionGen.get()) {
                if (markerWritten) {
                    // No spawn will happen: the marker must not
                    // outlive the intent (same cleanup as the
                    // spawn-failure path below).
                    clearSpawnMarkerIfMatches(PROCESS_INCARNATION_ID);
                }
                throw new IOException("start cancelled after the"
                        + " spawn-intent marker was written");
            }
            try {
                proc = pb.start();
            } catch (IOException | RuntimeException e) {
                // No spawn happened: the marker must not outlive the
                // intent, or a later incarnation would scan for a
                // child that never existed (it would find none and
                // clear the marker then — this is just the tidier,
                // immediate version of that cleanup).
                if (markerWritten) {
                    clearSpawnMarkerIfMatches(PROCESS_INCARNATION_ID);
                }
                throw e;
            }
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
            if (pidRecorded) {
                // The pid record supersedes the spawn-intent marker:
                // from here the child is findable by record, and the
                // marker's job is done. If the record FAILED, the
                // marker deliberately STAYS — it is then the only
                // trace of this child, and the next incarnation's
                // marker scan is the only path that can find it
                // (finding M3).
                clearSpawnMarkerIfMatches(PROCESS_INCARNATION_ID);
            }
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
     * Three ways the leftover can be known:
     *
     *   (a) same service instance: {@link #nodeProcess} still holds
     *       the Process object, so identity is certain and isAlive()
     *       is the exit test (this is the old F-2 wait, unified here);
     *   (b) recreated instance: the pid file survives. A recorded pid
     *       is trusted ONLY after /proc/&lt;pid&gt;/cmdline shows it
     *       still belongs to a quantus process — pids are reused,
     *       and waiting on (let alone disturbing) a stranger's process
     *       is not an option. A pid that fails the check is stale: the
     *       record is dropped and startup proceeds at once;
     *   (c) interrupted spawn (versionCode 25, audit finding M3): the
     *       spawn-intent marker survives but no usable pid record
     *       does — an incarnation died between the spawn and the
     *       record, or the record write itself failed. The child is
     *       then found by scanning /proc for processes running THIS
     *       app's node executable (not limited to this process's
     *       children: an orphan's parent is long gone), each
     *       candidate strictly verified as in
     *       {@link #isOwnNodeProcessStrict}. A marker left by THIS
     *       process names its own in-flight spawn, which the paths
     *       above own; only a FOREIGN marker triggers the scan, and a
     *       foreign marker whose scan finds nothing is stale — the
     *       marked spawn never produced a living node — and is
     *       cleared.
     *
     * The leftover is waited on, not disturbed: if it is still alive
     * after {@link #LEFTOVER_WAIT_MS}, the launch fails with an
     * IOException whose message (and a log line) records why, instead
     * of spawning a second node that would lose the fight for
     * ParityDB's exclusive lock.
     *
     * One exception, added in versionCode 23 (audit finding A-4) and
     * re-based in versionCode 25 (audit finding M2): a leftover known
     * only through the pid file or the spawn marker, whose evidence
     * was stamped by a PREVIOUS incarnation of this app (a different
     * incarnation id, or no stamp — pre-versionCode-25 records),
     * cannot have a living owner: its owning app process is dead
     * (killed by the system, crashed, or force-stopped in a way that
     * spared the child), and a node process does not exit on its
     * own, so waiting would refuse every future start forever. If —
     * and only if — the leftover still strictly names THIS app's own
     * node executable (see {@link #isOwnNodeProcessStrict}), the
     * orphan is terminated (a same-UID kill; a stranger's process is
     * never signalled), waited on with the same bound, and only then
     * does the launch proceed. A leftover owned by this process —
     * tracked by object, or stamped with this process's incarnation
     * id — is never killed: it has an owner, or its owner already
     * destroyed it (and finding F-03's escalation stands behind that
     * destroy). At most one recovery EPISODE completes per app
     * process — an episode being every orphan attested by the same
     * foreign evidence, recovered one at a time under it (finding
     * N4): see {@link #orphanRecoveryCompletedInProcess}.
     *
     * Until versionCode 24 the exception was licensed by "the asking
     * session is the first this process established" — a flag the
     * first session consumed even when its recovery stood down, so
     * the orphan outlived every later session of the process
     * (finding M2). Provenance does not wear out that way: standing
     * down changes nothing about who spawned the leftover, and the
     * next session judges the same evidence again.
     *
     * The kill is still revalidated at the last moment (versionCode
     * 24, audit finding A-6): the gate conditions above are evaluated
     * from values this worker captured or read earlier, and a worker
     * of a destroyed instance can be stalled before this method
     * while a new instance establishes its own session and spawns
     * its node, changing the evidence under it. So immediately
     * before terminating anything, this method confirms (a) that the
     * caller's generation is still the current generation — a stale
     * worker's is not, every teardown bumps it — and (b) that the
     * evidence (pid record, or spawn marker) still names what this
     * call has been waiting on, unchanged since the wait began. A
     * record written by a newer session of THIS process fails the
     * provenance check as well (its stamp is this process's id). If
     * any check fails, nothing is killed and the launch refuses,
     * exactly as it did before the orphan recovery existed.
     *
     * Called with {@link #PID_LOCK} held (from launch()); the
     * pid-file and marker reads and clears inside ride on the same
     * lock (monitors are reentrant), which is what makes this check
     * indivisible from the spawn that follows it.
     */
    private void awaitLeftoverNodeProcess(int gen) throws IOException {
        final Process prev = nodeProcess;
        // Not final: the marker path's recovery loop below moves on
        // to the next orphan after each recovery (finding N4).
        long leftoverPid;
        final boolean trackByObject;
        // Provenance of a leftover known by evidence (paths b/c):
        // the incarnation id stamped by whoever wrote the record or
        // the marker, or null for an unstamped (pre-versionCode-25)
        // record. For the tracked-by-object path it is set to this
        // process's own id — that path is never killed anyway.
        final String evidenceIncarnation;
        final boolean viaMarker;
        if (prev != null && prev.isAlive()) {
            leftoverPid = pidOf(prev);
            trackByObject = true;
            evidenceIncarnation = PROCESS_INCARNATION_ID;
            viaMarker = false;
        } else {
            long filePid = readPidFile();
            if (filePid > 0 && isQuantusProcess(filePid)) {
                leftoverPid = filePid;
                trackByObject = false;
                evidenceIncarnation = readPidRecordIncarnation();
                viaMarker = false;
            } else {
                if (filePid > 0) {
                    // Stale record: process gone, or pid reused.
                    clearPidFileIfMatches(filePid);
                }
                // Path (c): an interrupted spawn's marker (finding
                // M3). Only a FOREIGN marker — written by an
                // incarnation that no longer exists — is evidence;
                // this process's own marker names a spawn the paths
                // above are responsible for, and a completed
                // recovery leaves nothing further to find.
                String marker = readSpawnMarker();
                if (marker == null
                        || marker.equals(PROCESS_INCARNATION_ID)
                        || orphanRecoveryCompletedInProcess) {
                    return;
                }
                List<Long> orphans = scanForOrphanNodeProcesses();
                if (orphans.isEmpty()) {
                    // The marked spawn never produced a living node
                    // (the child died with its parent, or the spawn
                    // itself failed as the parent died): the marker
                    // has done its job by being checked.
                    clearSpawnMarkerIfMatches(marker);
                    return;
                }
                // More than one strict-verified orphan can exist
                // only if the interrupted-spawn window was hit more
                // than once across incarnations. They are recovered
                // one at a time, oldest (lowest pid) first, in the
                // loop below: after each recovery the scan runs
                // again, and the marker — the only evidence by which
                // the remaining orphans can be found at all — is
                // cleared only when a scan comes back empty
                // (versionCode 25, re-audit finding N4; the first
                // version of this path settled the marker and the
                // completion flag after the FIRST orphan, stranding
                // every other one permanently).
                leftoverPid = orphans.get(0);
                trackByObject = false;
                evidenceIncarnation = marker;
                viaMarker = true;
                logApp("spawn-intent marker from a previous incarnation"
                        + " has no pid record; /proc scan found node"
                        + " process (pid " + leftoverPid + ")");
            }
        }
        // The wait / recovery below runs once for the pid-record
        // and tracked-by-object paths. For the marker path it is a
        // loop (versionCode 25, re-audit finding N4): after one
        // orphan is dealt with, the scan runs again, and while
        // orphans remain the marker and the completion flag are NOT
        // settled — the marker is the only evidence by which the
        // remaining orphans can be found, in this process or any
        // later one, so settling it after the first orphan stranded
        // the rest permanently. Each orphan gets the full treatment
        // (bounded natural wait, then the gated kill), so a start
        // facing several orphans takes proportionally longer; that
        // is the price of not refusing outright, and the situation
        // needs several interrupted spawns across incarnations to
        // arise at all.
        boolean killedThisCall = false;
        for (;;) {
            logApp("previous node process (pid " + leftoverPid
                    + ") still exiting; waiting up to 5s");
            if (awaitLeftoverExit(prev, leftoverPid, trackByObject)) {
                clearPidFileIfMatches(leftoverPid);
                if (!viaMarker) {
                    return;
                }
                List<Long> more = scanForOrphanNodeProcesses();
                if (more.isEmpty()) {
                    // No orphans remain: the marker has done its
                    // job and is settled, as before. If this call
                    // terminated an earlier orphan of the episode,
                    // the completion flag is recorded too, exactly
                    // as the kill path below records it.
                    if (killedThisCall) {
                        orphanRecoveryCompletedInProcess = true;
                    }
                    clearSpawnMarkerIfMatches(evidenceIncarnation);
                    return;
                }
                leftoverPid = more.get(0);
                logApp("another orphaned node process (pid " + leftoverPid
                        + ") remains from the same interrupted spawn;"
                        + " recovering it in this start as well");
                continue;
            }
            if (!trackByObject
                    && !orphanRecoveryCompletedInProcess
                    && (evidenceIncarnation == null
                        || !evidenceIncarnation.equals(PROCESS_INCARNATION_ID))
                    && isOwnNodeProcessStrict(leftoverPid)) {
                // Orphan of a dead incarnation (see the method doc):
                // it will never exit by itself, and refusing here
                // would refuse every future start with it. Terminate
                // it, wait with the same bound, and proceed only once
                // it is verifiably gone — the strict identity is
                // re-checked by the wait itself, so a pid recycled
                // in the meantime is never mistaken for the orphan.
                //
                // First, though, the last-moment revalidation
                // (versionCode 24, audit finding A-6; see the method
                // doc): this worker's generation must still be the
                // current generation, and the evidence this call has
                // been waiting on — the pid record, or the spawn
                // marker — must be unchanged since the wait began. A
                // stale worker of a destroyed instance fails the
                // generation check by construction — its session's
                // teardown bumped the generation — and evidence
                // written by a newer session fails the second (and,
                // being stamped with this process's own incarnation
                // id, the provenance check above as well). Any failure
                // stands the recovery down: nothing is killed, and
                // the refusal below is the outcome, exactly as before
                // the recovery existed.
                boolean evidenceUnchanged = viaMarker
                        ? evidenceIncarnation.equals(readSpawnMarker())
                        : readPidFile() == leftoverPid;
                if (gen == sessionGen.get() && evidenceUnchanged) {
                    logApp("node process (pid " + leftoverPid + ") was left behind"
                            + " by a previous incarnation of this app and has no"
                            + " living owner; terminating it to recover");
                    // versionCode 25 (re-audit finding N2): the
                    // revalidation above was computed from reads
                    // taken before the wait, and the log line just
                    // written is file I/O — nothing held a lock
                    // across check and kill, so a STOP could
                    // complete in between and this by-now-stale
                    // worker would kill anyway. The target is an
                    // old incarnation's orphan either way — never a
                    // living session's child, which the provenance
                    // gate and PID_LOCK exclude — but a worker whose
                    // session has ended must not act at all.
                    // Re-verify everything once more, immediately
                    // before the kill: generation still current,
                    // evidence unchanged on a fresh read, strict
                    // identity still holding. Only the kill syscall
                    // itself follows the re-check.
                    boolean finalRecheck = gen == sessionGen.get()
                            && (viaMarker
                                ? evidenceIncarnation.equals(readSpawnMarker())
                                : readPidFile() == leftoverPid)
                            && isOwnNodeProcessStrict(leftoverPid);
                    if (!finalRecheck) {
                        logApp("orphan recovery stood down at the final"
                                + " re-check for pid " + leftoverPid
                                + ": the asking session is no longer"
                                + " current, or the evidence changed;"
                                + " refusing instead of terminating");
                    } else {
                        try {
                            android.os.Process.killProcess((int) leftoverPid);
                        } catch (RuntimeException e) {
                            logApp("orphan termination failed for pid "
                                    + leftoverPid + ": " + e);
                        }
                        if (awaitLeftoverExit(null, leftoverPid, false)) {
                            killedThisCall = true;
                            clearPidFileIfMatches(leftoverPid);
                            if (viaMarker) {
                                List<Long> more =
                                        scanForOrphanNodeProcesses();
                                if (!more.isEmpty()) {
                                    // More orphans are attested by
                                    // the same foreign marker
                                    // (finding N4): the episode is
                                    // not complete — neither the
                                    // completion flag nor the
                                    // marker is settled — and the
                                    // loop recovers the next one
                                    // under the same evidence.
                                    leftoverPid = more.get(0);
                                    logApp("orphaned node process terminated;"
                                            + " another orphaned node process"
                                            + " (pid " + leftoverPid + ") remains"
                                            + " from the same interrupted spawn;"
                                            + " recovering it in this start"
                                            + " as well");
                                    continue;
                                }
                                clearSpawnMarkerIfMatches(evidenceIncarnation);
                            }
                            // The recovery episode is complete: it
                            // is recorded BEFORE proceeding, so no
                            // later session of this process can
                            // license a second kill on evidence this
                            // process has since rewritten as its own.
                            orphanRecoveryCompletedInProcess = true;
                            logApp("orphaned node process (pid " + leftoverPid
                                    + ") terminated; proceeding with the start");
                            return;
                        }
                    }
                } else {
                    logApp("orphan recovery stood down for pid " + leftoverPid
                            + ": the asking session is no longer current, or"
                            + " the evidence changed during the wait;"
                            + " refusing instead of terminating a process"
                            + " that may have a living owner");
                }
            }
            logApp("previous node process (pid " + leftoverPid
                    + ") still running after 5s; not starting a second node");
            throw new IOException("previous node process (pid " + leftoverPid
                    + ") has not exited yet; refusing to start a second node"
                    + " on the same database");
        }
    }

    /**
     * The interrupted-spawn scan of finding M3 (versionCode 25):
     * every process on the device whose cmdline names the node
     * binary, strictly verified as THIS app's own node executable —
     * not limited to this process's children, because an orphan's
     * parent is gone by definition (the child-only scan in
     * {@link #scanForNodeChildPid} can never see it). The loose name
     * filter runs first so the strict verification only opens the
     * cmdlines of plausible candidates; read failures simply remove
     * an entry from consideration. Sorted ascending, so the caller
     * recovers the oldest orphan first. This process's own live node
     * cannot appear here: the caller reaches the scan only when the
     * pid record names no live quantus process, and a node this
     * process spawned is either tracked by object, named by that
     * record, or covered by this process's own spawn marker — all
     * checked before the scan.
     */
    private List<Long> scanForOrphanNodeProcesses() {
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
            String cmdline = readCmdline(pid);
            if (cmdline == null || !cmdline.contains(NODE_LIB)) {
                continue;
            }
            if (isOwnNodeProcessStrict(pid)) {
                out.add(pid);
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    /**
     * The bounded leftover wait itself: poll until the process is
     * gone (tracked by its Process object when this instance holds
     * one, by the /proc identity check otherwise) or
     * {@link #LEFTOVER_WAIT_MS} expires. An interrupt aborts the
     * launch exactly as it did before this was factored out.
     *
     * @return true when the process is verifiably gone.
     */
    private boolean awaitLeftoverExit(Process prev, long leftoverPid,
                                      boolean trackByObject) throws IOException {
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
                return true;
            }
        }
        return false;
    }

    /**
     * The strict identity check behind the orphan recovery in
     * {@link #awaitLeftoverNodeProcess} (versionCode 23, audit
     * finding A-4): true only when /proc/&lt;pid&gt;/cmdline shows
     * the process was started from THIS app's own node executable —
     * argv[0] is the executable's absolute path in this app's
     * nativeLibraryDir, or argv[0] is the system linker and argv[1]
     * is that path (the fallback launch form). Anything else — a
     * recycled pid, another app's process, this app's own main
     * process — is not ours to signal. Unlike
     * {@link #isQuantusProcess} (a substring test, used for waiting),
     * this compares full paths, because its result authorizes a
     * kill. Read failures read as "not ours", as there.
     */
    private boolean isOwnNodeProcessStrict(long pid) {
        if (pid <= 0 || pid == android.os.Process.myPid()) {
            return false;
        }
        String expected = new File(getApplicationInfo().nativeLibraryDir,
                NODE_LIB).getAbsolutePath();
        try (FileInputStream in = new FileInputStream(
                new File("/proc/" + pid, "cmdline"));
             ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[512];
            int n;
            while ((n = in.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
            String[] argv = new String(buf.toByteArray(),
                    StandardCharsets.UTF_8).split("\0", 3);
            if (argv.length < 1) {
                return false;
            }
            if (expected.equals(argv[0])) {
                return true;
            }
            return argv.length > 1
                    && "/system/bin/linker64".equals(argv[0])
                    && expected.equals(argv[1]);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * True while /proc says this pid is (still) one of ours: the
     * directory exists and its cmdline names the node binary. Any read
     * failure reads as "not ours" — a process we cannot identify is
     * never waited on.
     *
     * The match is the binary's file name ({@link #NODE_LIB}) — the
     * same criterion the /proc child scan uses (versionCode 18). It
     * was a loose "quantus" substring until versionCode 21 (audit
     * finding 2): that also matched this app's own main process
     * (app.quantusnode.node), so a pid recycled to the app itself
     * could have been mistaken for a leftover node and blocked every
     * start. The strict match can in principle miss a real leftover,
     * but that failure surfaces as a refused start on the node's own
     * database lock — recoverable, never a stuck state.
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
                    .contains(NODE_LIB);
        } catch (IOException e) {
            return false;
        }
    }

    private File pidFile() {
        return new File(getFilesDir(), PID_FILE_NAME);
    }

    /**
     * Wait, at most {@code timeoutMs}, for a destroyed process to
     * actually exit, polling {@link Process#exitValue()} — a method
     * every Android runtime and the SDK's android.jar both have,
     * unlike waitFor(long, TimeUnit), which this class avoids for
     * the same bootclasspath reason as Process.pid() (see below).
     *
     * @return true only when the exit was actually confirmed. When
     *         the bound expires — or an interrupt ends the wait
     *         early (the interrupt status is restored) — the
     *         process may still be alive and the caller must treat
     *         it that way (versionCode 23, audit finding A-1: the
     *         one caller used to ignore this distinction and
     *         cleared the child's pid record unconditionally).
     */
    private static boolean waitForExitBounded(Process proc, long timeoutMs) {
        // versionCode 25 (audit finding L4): the monotonic clock, like
        // every other wait in this class. This was the one remaining
        // wall-clock deadline — a device clock set BACK while a child
        // was being waited on stretched a "bounded" 5 s wait by the
        // full amount of the setback.
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        for (;;) {
            try {
                proc.exitValue();
                return true;
            } catch (IllegalThreadStateException stillAlive) {
                if (SystemClock.elapsedRealtime() >= deadline) {
                    return false;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }

    /**
     * The pid of a spawned child, or -1 when the runtime will not
     * say. Every pid() probe in this class goes through here —
     * reflectively, for the reason given at {@link #pidOf(Process,
     * Throwable[])}.
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
            // versionCode 22 (external audit finding 3): reflective
            // on purpose. Process.pid() is the proven runtime hazard
            // of this class — the target runtime throws
            // NoSuchMethodError for it (versionCode 17 field run) —
            // so the probe goes through getMethod: when the runtime
            // does not offer the method at all, the lookup itself
            // fails cleanly here, and when it does, the invocation
            // result is used. The runtime behaviour is identical to
            // the previous direct call in every case (the pid when
            // available, -1 when not, the failure named via
            // errorOut). The probe happens once per launch, so the
            // Method is not cached.
            Object v = Process.class.getMethod("pid").invoke(p);
            return ((Number) v).longValue();
        } catch (Throwable t) {
            // Unwrap the reflection wrapper, so a caller naming the
            // failure sees the real cause (e.g. the
            // UnsupportedOperationException a runtime raises inside
            // pid()), exactly as it did when the call was direct.
            Throwable report = t;
            if (t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null) {
                report = t.getCause();
            }
            if (errorOut != null && errorOut.length > 0) {
                errorOut[0] = report;
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

    /**
     * Read a stream to its end with a plain chunk loop.
     * InputStream.readAllBytes() is deliberately NOT used anywhere
     * in this app (versionCode 22, external audit finding 2): the
     * method only exists in the runtime from API 33, so on API
     * 31–32 (Android 12/12L, inside this app's supported range)
     * the call escapes as a NoSuchMethodError past every
     * IOException catch — including on paths that run at every
     * node start (the pid-file read).
     */
    private static byte[] readAllBytesCompat(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    private static byte[] readProcFile(long pid, String name) {
        try (FileInputStream in = new FileInputStream(
                new File("/proc/" + pid, name))) {
            return readAllBytesCompat(in);
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
                fos.write(encodePidRecord(pid, PROCESS_INCARNATION_ID)
                        .getBytes(StandardCharsets.UTF_8));
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

    /**
     * The on-disk pid record format (versionCode 25, audit finding
     * M2): "&lt;pid&gt; &lt;incarnation-id&gt;" — the pid of the
     * spawned child and the id of the app process that spawned it
     * (see {@link #PROCESS_INCARNATION_ID}). Records written before
     * versionCode 25 hold a bare pid; they parse with a null
     * incarnation, which the recovery gate reads as "a previous
     * incarnation" — the process that wrote them is necessarily
     * dead, since the binary writing this format only runs in a
     * process that started after the upgrade. Pure functions, so
     * the format can be exercised on a JVM.
     */
    static String encodePidRecord(long pid, String incarnationId) {
        return pid + " " + incarnationId;
    }

    /** The pid in a record's raw text, or -1 when unparseable. */
    static long parsePidRecordPid(String raw) {
        if (raw == null) {
            return -1;
        }
        String trimmed = raw.trim();
        int space = trimmed.indexOf(' ');
        String head = space >= 0 ? trimmed.substring(0, space) : trimmed;
        try {
            return Long.parseLong(head);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The incarnation stamp in a record's raw text, or null when absent. */
    static String parsePidRecordIncarnation(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        int space = trimmed.indexOf(' ');
        if (space < 0 || space + 1 >= trimmed.length()) {
            return null;
        }
        String tail = trimmed.substring(space + 1).trim();
        return tail.isEmpty() ? null : tail;
    }

    /** The pid record's raw text, or null when there is none. */
    private String readPidRecordRaw() {
        try {
            File f = pidFile();
            if (!f.isFile()) {
                return null;
            }
            byte[] data;
            try (FileInputStream in = new FileInputStream(f)) {
                data = readAllBytesCompat(in);
            }
            return new String(data, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** The recorded pid, or -1 when there is no usable record. */
    private long readPidFile() {
        return parsePidRecordPid(readPidRecordRaw());
    }

    /**
     * The incarnation stamp on the recorded pid (versionCode 25,
     * finding M2), or null when the record is unstamped (written
     * before versionCode 25) or absent. The recovery gate's
     * provenance evidence; see {@link #awaitLeftoverNodeProcess}.
     */
    private String readPidRecordIncarnation() {
        return parsePidRecordIncarnation(readPidRecordRaw());
    }

    private File spawnMarkerFile() {
        return new File(getFilesDir(), SPAWN_MARKER_FILE_NAME);
    }

    /**
     * Stamp the spawn-intent marker with this process's incarnation
     * id, atomically (tmp + rename), and report whether it is in
     * place. Called from launch() with {@link #PID_LOCK} held,
     * immediately before the spawn it marks (versionCode 25,
     * finding M3). Same rules as the pid record: a failed marker
     * degrades a detection path; it must never fail — or crash —
     * the launch itself.
     */
    private boolean writeSpawnMarker() {
        File tmp = new File(getFilesDir(), SPAWN_MARKER_FILE_NAME + ".tmp");
        boolean done = false;
        try {
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(PROCESS_INCARNATION_ID.getBytes(StandardCharsets.UTF_8));
            }
            done = tmp.renameTo(spawnMarkerFile());
        } catch (IOException | RuntimeException ignored) {
            // Reported by the caller via the return value.
        } finally {
            if (!done) {
                tmp.delete();
            }
        }
        return done;
    }

    /** The incarnation stamp on the spawn-intent marker, or null when absent. */
    private String readSpawnMarker() {
        try {
            File f = spawnMarkerFile();
            if (!f.isFile()) {
                return null;
            }
            byte[] data;
            try (FileInputStream in = new FileInputStream(f)) {
                data = readAllBytesCompat(in);
            }
            String s = new String(data, StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Remove the spawn-intent marker, but only while it still holds
     * the given incarnation stamp — the same check-and-clear rule as
     * {@link #clearPidFileIfMatches}, under the same lock: a late
     * cleanup must never erase a NEWER spawn's marker.
     */
    private void clearSpawnMarkerIfMatches(String incarnationId) {
        synchronized (PID_LOCK) {
            if (incarnationId != null
                    && incarnationId.equals(readSpawnMarker())) {
                spawnMarkerFile().delete();
            }
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
     * by path, and return that path. Refreshed when the file on disk
     * is not byte-identical to the bundled copy (a length-only check,
     * used until versionCode 20, would have kept a stale bundle whose
     * replacement happened to be the same size — audit finding 4).
     * Returns null when the copy is unavailable; the caller then
     * relies on the SSL_CERT_DIR hint alone.
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
            if (!out.isFile() || !bundleContentsMatch(out, data)) {
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

    /** True when the file at {@code out} holds exactly {@code data}. */
    private static boolean bundleContentsMatch(File out, byte[] data) {
        try (InputStream in = new FileInputStream(out);
             ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
            return Arrays.equals(buf.toByteArray(), data);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Record how the current stopped episode ended (versionCode 26).
     * Caller MUST hold {@link #stateLock}. First writer wins:
     * whichever path settles the session first owns the
     * classification, so a worker exit that lands just before a user
     * stop is not rewritten as a user stop, and a stop that lands
     * first is not rewritten as an unexpected exit by the worker's
     * delayed finish. Display data only — no lifecycle decision
     * reads this back.
     */
    private void markStopKindLocked(NodeState.StopKind kind) {
        NodeState st = NodeState.get();
        if (st.lastStopKind == NodeState.StopKind.NONE) {
            st.lastStopKind = kind;
        }
    }

    /**
     * Classify a worker-observed end of session (versionCode 26).
     * An attempt "failed to start" when it never produced a process
     * (no exit code) or exited non-zero inside the quick-exit
     * window — the same quick-failure notion the launch retry uses.
     * When EVERY attempt of the session failed to start, the start
     * itself failed; otherwise some attempt genuinely got going (a
     * long run, or a clean exit nobody requested) and its end is an
     * unexpected stop. Static and value-based, so the real method
     * can be exercised on a JVM.
     */
    static NodeState.StopKind classifyWorkerExit(
            List<NodeState.Attempt> attempts, long quickExitMs) {
        boolean allFailedToStart = !attempts.isEmpty();
        for (NodeState.Attempt a : attempts) {
            boolean failedToStart = a.exitCode == null
                    || (a.exitCode != 0 && a.livedMs < quickExitMs);
            if (!failedToStart) {
                allFailedToStart = false;
                break;
            }
        }
        return allFailedToStart
                ? NodeState.StopKind.START_FAILED
                : NodeState.StopKind.UNEXPECTED;
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
            disconnectInFlightRpc();
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

    /**
     * Per-line byte cap for the drain's read side (versionCode 25,
     * audit finding L2 / F-01): one byte short of the log sink's own
     * per-line byte cap. The point of the cap is the READ side:
     * however long a "line" the node emits — a megabyte without a
     * newline, say — the drain's live memory stays within this plus
     * one chunk, and the excess is consumed and discarded up to the
     * newline rather than materialized. A line cut here is flagged
     * as truncated for the consumer, and the sink marks it as cut
     * (re-audit finding N3) — sitting one byte under the sink's cap
     * must not make a cut line indistinguishable from a complete
     * one, which is what the flag is for.
     */
    private static final int DRAIN_LINE_MAX_BYTES = (int) (LOG_MAX_BYTES / 4) - 1;

    /**
     * Receives drained lines from {@link #drainLines}. The
     * {@code truncated} flag says the line was cut at the drain's
     * cap and its tail discarded (versionCode 25, re-audit finding
     * N3): the sinks mark such a line, so a reader of the log or
     * the attempt capture can tell a cut line from a complete one.
     */
    private interface LineConsumer {
        void onLine(String line, boolean truncated);
    }

    private void drainOutput(Process proc, AttemptCapture capture) {
        InputStream in = proc.getInputStream();
        try {
            drainLines(in, DRAIN_LINE_MAX_BYTES, (line, truncated) -> {
                // F-3: the per-line work (log encode/write, attempt
                // capture) must never kill this thread. If it died,
                // logging and per-attempt evidence would stop — and
                // once the pipe fills, the node's own writes block and
                // the node stalls. Note the failure and keep draining.
                // (The note itself goes through the same sink, so it
                // is best-effort.) Stream-level IOException from the
                // read still ends the thread, in the catch below.
                try {
                    if (logSink != null) {
                        logSink.write(line, truncated);
                    }
                    capture.add(line, truncated);
                } catch (Exception e) {
                    try {
                        logApp("output drain: line dropped after error: " + e);
                    } catch (Exception ignored) {
                    }
                }
            });
        } catch (IOException ignored) {
            // Stream closed because the process ended; that is expected.
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Read {@code in} as UTF-8 lines and hand each one to
     * {@code consumer}, bounding the memory a single line can occupy
     * (versionCode 25, audit findings L2 / F-01). The reader this
     * replaces — BufferedReader.readLine() — materializes a whole
     * line before any cap can apply: the 8192-char capture cap and
     * the sink's 128 KB cap both trimmed a line only AFTER it had
     * been read, so a never-ending line grew the drain's heap
     * without limit (the auditor's harness drove the real
     * drainOutput out of memory with 64 MiB of newline-free input
     * under -Xmx24m). Here a line is accumulated only up to
     * {@code maxLineBytes}; past the cap, its remaining bytes are
     * scanned and discarded up to the newline, and the capped prefix
     * is what the consumer receives — the sinks' own caps still
     * apply downstream, unchanged.
     *
     * Line semantics match readLine(): lines end at '\n' (a trailing
     * '\r' is stripped), and a final unterminated line at EOF is
     * delivered. Splitting at the byte level is safe for UTF-8 —
     * 0x0A never appears inside a multi-byte sequence. A line cut
     * at the cap may end mid-sequence (the decoder renders the
     * partial sequence as a replacement character), and the cut
     * itself travels with the line as the consumer's
     * {@code truncated} flag, so the sinks can mark the line as
     * cut (re-audit finding N3: an earlier version of this reader
     * capped one byte BELOW the sink's own per-line cap, so a cut
     * line passed the sink unmarked and a shared log showed an
     * incomplete line as complete). Static and stream-based, so
     * the real method can be exercised on a JVM.
     */
    static void drainLines(InputStream in, int maxLineBytes,
                           LineConsumer consumer) throws IOException {
        byte[] chunk = new byte[8192];
        ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();
        // True once the current line has crossed the cap: its prefix
        // is in lineBuf and the rest is being discarded up to the
        // newline. This doubles as the line's truncated flag.
        boolean discarding = false;
        int n;
        while ((n = in.read(chunk)) != -1) {
            int segmentStart = 0;
            for (int i = 0; i < n; i++) {
                if (chunk[i] == (byte) '\n') {
                    boolean truncated = discarding;
                    if (!discarding) {
                        // The cap applies to newline-terminated
                        // segments too, not just to the tail of a
                        // chunk: a whole line can arrive inside one
                        // read, and appending it uncapped would
                        // defeat the bound entirely.
                        int room = maxLineBytes - lineBuf.size();
                        int avail = i - segmentStart;
                        int take = Math.min(avail, Math.max(room, 0));
                        if (take > 0) {
                            lineBuf.write(chunk, segmentStart, take);
                        }
                        if (take < avail) {
                            truncated = true;
                        }
                    }
                    emitDrainedLine(lineBuf, truncated, consumer);
                    lineBuf.reset();
                    discarding = false;
                    segmentStart = i + 1;
                }
            }
            if (segmentStart < n && !discarding) {
                int room = maxLineBytes - lineBuf.size();
                int take = Math.min(n - segmentStart, Math.max(room, 0));
                if (take > 0) {
                    lineBuf.write(chunk, segmentStart, take);
                }
                if (take < n - segmentStart) {
                    discarding = true;
                }
            }
        }
        // EOF: deliver a final unterminated line, as readLine did —
        // including an over-cap one, whose capped prefix is the line
        // as far as the consumer is concerned. An input that ended
        // exactly at a newline (or was empty) leaves nothing behind.
        if (lineBuf.size() > 0 || discarding) {
            emitDrainedLine(lineBuf, discarding, consumer);
        }
    }

    /** Decode one accumulated line (stripping a trailing '\r') and deliver it. */
    private static void emitDrainedLine(ByteArrayOutputStream buf,
                                        boolean truncated,
                                        LineConsumer consumer) {
        byte[] bytes = buf.toByteArray();
        int len = bytes.length;
        if (len > 0 && bytes[len - 1] == (byte) '\r') {
            len--;
        }
        consumer.onLine(new String(bytes, 0, len, StandardCharsets.UTF_8),
                truncated);
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

        /**
         * Per-line cap for the in-memory capture (versionCode 22,
         * external audit finding 5). The file sink caps a line at
         * 128 KB, but the capture used to keep whole lines, so one
         * gigantic line from the node would be held in memory
         * verbatim, times up to {@link #MAX_KEPT_LINES}. 8192 chars
         * is far beyond any real log line and plenty for failure
         * evidence on the dashboard.
         */
        private static final int MAX_LINE_CHARS = 8192;

        private final java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>();
        private int total = 0;

        synchronized void add(String line) {
            add(line, false);
        }

        /**
         * @param truncated true when the drain already cut this
         *        line at its byte cap (finding N3): the capture's
         *        marker is owed even when the delivered prefix is
         *        short enough to pass the character cap below, and
         *        it is appended exactly once, whichever cut (or
         *        both) produced it.
         */
        synchronized void add(String line, boolean truncated) {
            total++;
            if (line.length() > MAX_LINE_CHARS) {
                int keep = MAX_LINE_CHARS;
                // Do not split a surrogate pair at the cut.
                if (Character.isHighSurrogate(line.charAt(keep - 1))) {
                    keep--;
                }
                line = line.substring(0, keep);
                truncated = true;
            }
            if (truncated) {
                line = line + " …[truncated]";
            }
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
            // versionCode 22 (external audit finding 4): seed the
            // rotation counter with the length of the log that is
            // already on disk. The counter used to restart at zero
            // with every sink, so a log written by many short-lived
            // services — each under the check interval — never got a
            // rotation check at all and grew past the cap.
            // rotateIfNeededLocked judges by the file's real length,
            // so seeding the counter is all it takes for the check
            // to run promptly on a fresh sink too.
            this.bytesSinceCheck = file.isFile() ? file.length() : 0;
        }

        void write(String line) {
            write(line, false);
        }

        /**
         * @param truncated true when the producer already cut this
         *        line at its own cap (the drain, finding N3): the
         *        line is marked as cut even though the prefix it
         *        arrives as fits this sink's per-line cap — before
         *        the flag existed, such a line was written unmarked
         *        and read as complete.
         */
        void write(String line, boolean truncated) {
            byte[] bytes = encodeLine(line, truncated);
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

        /**
         * Encode one line, truncating an oversized line at a UTF-8
         * boundary. A line the producer already cut (finding N3)
         * gets the truncation marker even when it fits the cap:
         * {@code keep} then simply covers the whole line, and only
         * the marker is appended (still within the cap, since the
         * marker's room is reserved from the same budget).
         */
        private static byte[] encodeLine(String line, boolean truncated) {
            byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
            if (!truncated && bytes.length <= MAX_LINE_BYTES) {
                return bytes;
            }
            int keep = Math.min(bytes.length - 1,
                    MAX_LINE_BYTES - TRUNC_SUFFIX.length);
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
                    all = readAllBytesCompat(in);
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
                // versionCode 23 (audit finding B-4): the rewrite is
                // atomic — the retained tail is written to a sibling
                // temp file and renamed over the log, never written
                // in place. The old in-place truncate-then-write
                // lost the whole retained half if the process was
                // killed mid-rotation. A failed rename leaves the
                // original untouched (still over the cap; the next
                // check retries) — deliberately NOT a fallback to
                // the in-place write. A temp file stranded by a kill
                // is harmless: readers only read the log itself, and
                // the next rotation truncates the temp on open.
                File tmp = new File(file.getParentFile(),
                        file.getName() + ".tmp");
                boolean moved = false;
                try {
                    try (FileOutputStream out = new FileOutputStream(tmp, false)) {
                        out.write("... [app] log rotated, older half dropped ...\n"
                                .getBytes(StandardCharsets.UTF_8));
                        out.write(all, from, all.length - from);
                    }
                    moved = tmp.renameTo(file);
                } finally {
                    if (!moved) {
                        tmp.delete();
                    }
                }
            } catch (IOException ignored) {
                // Same rule: never crash for the log's sake.
            }
        }

        /**
         * Read the log's tail for the share sheet (versionCode 23,
         * audit finding B-3). Bounded — at most about 3 bytes per
         * wanted char are read, however large the file is — and
         * taken under the same class lock as every write and
         * rotation, so a rotation cannot tear the read into a
         * partial log that looks complete. The result holds at most
         * {@code maxChars} chars; {@code truncatedOut} (when given)
         * reports whether anything was left out. Returns null when
         * there is no log to share.
         */
        static String readTailForShare(File logDir, int maxChars,
                                       boolean[] truncatedOut) {
            File f = new File(logDir, "node.log");
            synchronized (LOCK) {
                if (truncatedOut != null && truncatedOut.length > 0) {
                    truncatedOut[0] = false;
                }
                if (!f.isFile()) {
                    return null;
                }
                long len = f.length();
                if (len <= 0) {
                    return null;
                }
                // A UTF-16 char costs at most 3 UTF-8 bytes (BMP);
                // a supplementary char costs 4 bytes for 2 chars.
                // Reading 3 bytes per wanted char therefore covers
                // the last maxChars chars whenever the file holds
                // that many.
                long want = Math.min(len, (long) maxChars * 3L + 64L);
                byte[] buf = new byte[(int) want];
                try (java.io.RandomAccessFile raf =
                        new java.io.RandomAccessFile(f, "r")) {
                    raf.seek(len - want);
                    raf.readFully(buf);
                } catch (IOException e) {
                    return null;
                }
                int off = 0;
                if (want < len) {
                    markTruncated(truncatedOut);
                    // Starting mid-file: drop the partial first line.
                    // 0x0A can only ever be a newline in UTF-8 —
                    // never part of a multi-byte sequence — so this
                    // also drops any partial character at the head.
                    int nl = -1;
                    for (int i = 0; i < buf.length; i++) {
                        if (buf[i] == (byte) '\n') {
                            nl = i;
                            break;
                        }
                    }
                    if (nl >= 0) {
                        off = nl + 1;
                    } else {
                        // No newline in the window (a single line
                        // longer than the window): skip any partial
                        // UTF-8 sequence at the head instead.
                        while (off < buf.length
                                && (buf[off] & 0xC0) == 0x80) {
                            off++;
                        }
                    }
                }
                String s = new String(buf, off, buf.length - off,
                        StandardCharsets.UTF_8);
                if (s.length() > maxChars) {
                    markTruncated(truncatedOut);
                    int cut = s.length() - maxChars;
                    // Do not split a surrogate pair at the cut.
                    if (Character.isLowSurrogate(s.charAt(cut))) {
                        cut++;
                    }
                    s = s.substring(cut);
                    // Start at a line boundary where one exists in
                    // what was kept: a shared log that begins
                    // mid-line reads as torn. (A single line longer
                    // than the cap has no boundary to align to and
                    // is shared as cut.)
                    int nl = s.indexOf('\n');
                    if (nl >= 0 && nl + 1 < s.length()) {
                        s = s.substring(nl + 1);
                    }
                }
                return s;
            }
        }

        private static void markTruncated(boolean[] truncatedOut) {
            if (truncatedOut != null && truncatedOut.length > 0) {
                truncatedOut[0] = true;
            }
        }
    }

    /**
     * The share-sheet read of the node log, coordinated with the
     * writers (versionCode 23, audit finding B-3): see
     * {@link LogSink#readTailForShare}. Package-visible for
     * {@link MainActivity}.
     */
    static String readLogTailForShare(File logDir, int maxChars,
                                      boolean[] truncatedOut) {
        return LogSink.readTailForShare(logDir, maxChars, truncatedOut);
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
            } catch (Throwable ignored) {
                // A failed poll is a state ("starting"), never a crash.
                // Throwable, not just Exception (external audit round
                // 2, F7, last-resort net): an Error escaping one poll
                // — a StackOverflowError from a pathological RPC
                // response is the known shape — must not kill this
                // thread silently while the node keeps running.
                // rpcCall already converts parse failures into a null
                // result; this catch sits behind it. State stays
                // consistent because pollOnce applies its writes in
                // generation-checked locked batches: a poll that dies
                // mid-way applies at most a partial batch, and the
                // next poll re-reads everything.
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
        JSONObject health = rpcCall("system_health", gen);
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
        // versionCode 23 (audit finding B-1): a field ABSENT from a
        // response is not a value. optInt's default would have
        // turned a missing "peers" into a real 0 and overwritten the
        // retained count below; -1 now means "this response did not
        // say", and the batch keeps the retained value — the same
        // principle F8 applied to highestBlock.
        //
        // versionCode 24 (audit finding B23-B1): the same principle,
        // applied to the value's TYPE as well as its presence. A
        // "peers" that is present but not a number (a string, say)
        // still fell through to optInt's coercion/default and could
        // overwrite the retained count with a manufactured 0; it is
        // adopted now only when it actually is a JSON number, like
        // the optLong sentinel pattern current/highest already use.
        // And "isSyncing" used to be read with a flat false default:
        // absent meant "not syncing" by construction and could call
        // a node SYNCED on a response that never said. It now has a
        // retained value (lastKnownSyncing), stated only by an
        // actual JSON boolean and applied inside the locked batch
        // below; anything else is "not said".
        Object peersValue = health.opt("peers");
        int peers = (peersValue instanceof Number)
                ? ((Number) peersValue).intValue() : -1;
        Object syncingValue = health.opt("isSyncing");
        Boolean freshSyncing = (syncingValue instanceof Boolean)
                ? (Boolean) syncingValue : null;

        JSONObject sync = rpcCall("system_syncState", gen);
        long current = -1;
        long highest = -1;
        if (sync != null) {
            current = adoptBlockHeight(sync.opt("currentBlock"));
            highest = adoptBlockHeight(sync.opt("highestBlock"));
        }

        synchronized (stateLock) {
            if (gen != sessionGen.get()) {
                return; // stopped / finished while the calls were in flight
            }
            NodeState st = NodeState.get();
            // versionCode 26: the batch's "before" picture, for the
            // observation record at the end of the batch.
            long bestBeforeBatch = st.bestBlock;
            NodeState.Status statusBeforeBatch = st.status;
            if (peers >= 0) {
                st.peers = peers;
                TrackStore.observePeers(this, peers);
            }
            if (freshSyncing != null) {
                lastKnownSyncing = freshSyncing;
            }
            boolean isSyncing = lastKnownSyncing;
            // versionCode 25 (audit findings L1 / F-02): record the
            // evidence BEFORE judging, so a response that carries a
            // valid height evidences its own judgement. A syncState
            // response whose heights are BOTH missing is a successful
            // call with nothing in it — it does not count (that shape
            // reached SYNCED through the sync != null branch below
            // just as surely as the failed call did).
            if (sync != null && (current >= 0 || highest >= 0)) {
                syncEvidenceSeen = true;
            }
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
                // External audit round 2 (F8): judge "at head"
                // against st.highestBlock — the value just stored
                // when this response carried one, and otherwise the
                // retained last-known highest. The old expression
                // treated a MISSING highest in this response
                // (highest < 0) as "at head", so a syncState answer
                // without a highestBlock flipped the status to
                // SYNCED even mid-sync. Before the first successful
                // read st.highestBlock is still -1; versionCode 25
                // (findings L1 / F-02) gates the SYNCED outcome of
                // exactly that state on syncEvidenceSeen below, so
                // "at head" alone no longer promotes an unevidenced
                // session to SYNCED.
                //
                // versionCode 23 (audit finding B-1): the same
                // principle in the other direction — when THIS
                // response carried no currentBlock (current < 0),
                // comparing it against the highest read "not at
                // head" by construction and flipped a synced node
                // back to SYNCING. Judge from the retained values
                // instead, exactly as the no-response branch below
                // does; the fresh comparison is used only when this
                // response actually carried a current block (which
                // the block above has just stored as st.bestBlock).
                boolean atHead;
                if (current >= 0) {
                    atHead = st.highestBlock < 0
                            || current >= st.highestBlock;
                } else {
                    atHead = st.highestBlock < 0
                            || st.bestBlock >= st.highestBlock;
                }
                st.status = (isSyncing || !atHead || !syncEvidenceSeen)
                        ? NodeState.Status.SYNCING : NodeState.Status.SYNCED;
            } else {
                // No sync state this poll: judge from the retained
                // values with the same meaning of "at head" as above
                // — last known best against last known highest.
                // versionCode 25 (findings L1 / F-02): before the
                // first evidenced read both are -1, which the old
                // expression read as "at head" and — with a retained
                // isSyncing of false — as SYNCED, on the very first
                // poll and, under a persistent syncState failure, on
                // every poll after it. The evidence gate is what
                // changed: unevidenced is SYNCING now, however the
                // retained values compare; once evidence exists, a
                // failed call keeps judging from the retained values
                // exactly as before.
                boolean atHead = st.highestBlock < 0
                        || st.bestBlock >= st.highestBlock;
                st.status = (isSyncing || !atHead || !syncEvidenceSeen)
                        ? NodeState.Status.SYNCING : NodeState.Status.SYNCED;
            }
            // versionCode 26: an "observation" is a poll that first
            // sees a higher best block while the node was ALREADY
            // synced — the transition into SYNCED does not count,
            // and neither does the session's first read
            // (bestBeforeBatch < 0). The service records it here so
            // the UI's ripple and "last observed" line key off facts
            // the service saw, and an activity rebuild can neither
            // fabricate nor lose one.
            if (current >= 0 && bestBeforeBatch >= 0
                    && current > bestBeforeBatch
                    && statusBeforeBatch == NodeState.Status.SYNCED
                    && st.status == NodeState.Status.SYNCED) {
                st.lastObservedBestBlock = current;
                st.lastObservedElapsedMs = SystemClock.elapsedRealtime();
            }
        }
        updateNotificationMaybe(gen);
    }

    /**
     * Adopt a block height from a raw syncState field value, or -1
     * ("this response did not say") when the value is not a
     * non-negative integer number (versionCode 25, re-audit residual
     * of finding F-02). The check runs on the RAW value, not on a
     * converted one: {@code JSONObject.optLong} coerces through
     * {@code Number.longValue()}, whose double-to-long conversion
     * truncates toward zero — so a malformed height of -0.5 arrived
     * as a perfectly valid-looking 0 and, being "a height", counted
     * as the sync evidence finding F-02 requires (and 5.9 arrived
     * as a plausible but wrong 5). A fractional, negative, non-finite
     * or non-numeric value evidences nothing and adopts nothing.
     * Static and value-based, so the real method can be exercised
     * on a JVM.
     */
    static long adoptBlockHeight(Object raw) {
        if (raw instanceof Number) {
            double d = ((Number) raw).doubleValue();
            if (Double.isFinite(d) && d >= 0 && d == Math.rint(d)) {
                return ((Number) raw).longValue();
            }
        }
        return -1;
    }

    /** One JSON-RPC call to the loopback endpoint; null on any failure. */
    private JSONObject rpcCall(String method, int gen) {
        HttpURLConnection conn = null;
        // This call's registration in the in-flight slot (finding
        // N1), and its completion flag for the deadline watchdog.
        InFlightRpc mine = null;
        final java.util.concurrent.atomic.AtomicBoolean callDone =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        // versionCode 25 (audit finding L3): the whole call — connect,
        // request, response — lives under one monotonic deadline.
        // The 1500/2000 ms timeouts below bound each individual wait
        // only; a response arriving one byte at a time never trips
        // the read timeout and takes ~18 hours to reach the byte
        // cap, parking the poller (and the uptime saves riding its
        // loop) for the duration. The deadline is re-checked between
        // read chunks in readBounded, which is where a trickle is
        // actually cut off; the deadline watchdog armed below covers
        // the one wait those checks cannot reach — the response
        // header wait inside getResponseCode(), which a peer
        // dribbling headers stays inside indefinitely (re-audit
        // residual of finding L3).
        final long deadlineMs = SystemClock.elapsedRealtime()
                + RPC_OVERALL_TIMEOUT_MS;
        try {
            JSONObject req = new JSONObject();
            req.put("jsonrpc", "2.0");
            req.put("id", 1);
            req.put("method", method);
            req.put("params", new org.json.JSONArray());
            byte[] body = req.toString().getBytes(StandardCharsets.UTF_8);

            conn = (HttpURLConnection) new URL(RPC_URL).openConnection();
            // Registered before any blocking call, so a teardown
            // landing anywhere in this call can abort it via
            // disconnectInFlightRpc() (finding L3). Registration is
            // generation-tagged CAS (re-audit finding N1): this call
            // installs its connection only while no NEWER generation
            // owns the slot, and the finally below deregisters by
            // identity — a stale poller can never overwrite or clear
            // a newer session's registration.
            InFlightRpc reg = new InFlightRpc(conn, gen);
            for (;;) {
                InFlightRpc cur = inFlightRpc.get();
                if (cur != null && cur.gen > gen) {
                    reg = null; // a newer session owns the slot
                    break;
                }
                if (inFlightRpc.compareAndSet(cur, reg)) {
                    break;
                }
            }
            mine = reg;
            armRpcDeadlineWatchdog(conn, deadlineMs, callDone);
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
            if (SystemClock.elapsedRealtime() > deadlineMs) {
                return null;
            }
            byte[] resp;
            try (java.io.InputStream in = conn.getInputStream()) {
                resp = readBounded(in, RPC_MAX_RESPONSE_BYTES, deadlineMs);
            }
            JSONObject json;
            try {
                json = new JSONObject(new String(resp, StandardCharsets.UTF_8));
            } catch (Throwable t) {
                // External audit round 2 (F7): org.json's parser is
                // recursive, so a deeply nested response — the body
                // may be up to 64 KiB of almost pure brackets — dies
                // with StackOverflowError, an Error the outer
                // catch (Exception) cannot see. A failed parse is a
                // failed call: return null, like every other failure
                // of this method. (The finally below still runs.)
                return null;
            }
            Object result = json.opt("result");
            return (result instanceof JSONObject) ? (JSONObject) result : null;
        } catch (Exception e) {
            return null; // connection refused while booting is normal
        } finally {
            callDone.set(true);
            if (mine != null) {
                // Deregister by identity: only this call's own
                // registration is cleared, atomically — never a
                // newer session's (re-audit finding N1; the check-
                // then-clear this replaces could erase a registration
                // installed between the check and the clear).
                inFlightRpc.compareAndSet(mine, null);
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Cut {@code conn} off once the overall deadline (plus a small
     * grace) has passed and the call is still running (versionCode
     * 25, re-audit residual of finding L3). The in-call deadline
     * checks sit after {@code getResponseCode()} and between body
     * chunks, so a peer dribbling response HEADERS — every read
     * comfortably inside the per-read timeout — never reaches a
     * check and can park the poller (and the uptime saves riding
     * its loop) without bound. This short-lived daemon thread
     * closes that gap from outside the call: disconnect() aborts
     * whatever wait the call is parked in, so the whole call —
     * headers included — is bounded by the deadline plus the
     * grace, and the poll loop's delay with it. It acts only on
     * this call's own connection object and only while the call
     * has not finished, so it can never disturb another session's
     * traffic.
     */
    private void armRpcDeadlineWatchdog(final HttpURLConnection conn,
            final long deadlineMs,
            final java.util.concurrent.atomic.AtomicBoolean callDone) {
        Thread t = new Thread(() -> {
            long waitMs = deadlineMs + RPC_WATCHDOG_GRACE_MS
                    - SystemClock.elapsedRealtime();
            if (waitMs > 0) {
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (!callDone.get()) {
                try {
                    conn.disconnect();
                } catch (RuntimeException ignored) {
                    // Best-effort, like every abort of this call.
                }
            }
        }, "rpc-deadline-watchdog");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Read a stream to its end, refusing to buffer more than
     * {@code maxBytes}: crossing the cap is an IOException, which the
     * RPC caller treats like any other failed call. The read also
     * refuses to outlive {@code deadlineMs} (monotonic clock;
     * versionCode 25, audit finding L3): the deadline is checked
     * between chunks, which is what stops a response that trickles
     * in slowly enough to never trip the per-read socket timeout.
     * (The log-file reader is deliberately NOT routed through here —
     * that file is the app's own, hard-capped by rotation.)
     */
    static byte[] readBounded(InputStream in, int maxBytes,
                              long deadlineMs) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            if (buf.size() + n > maxBytes) {
                throw new IOException("response exceeds " + maxBytes + " bytes");
            }
            buf.write(chunk, 0, n);
            if (SystemClock.elapsedRealtime() > deadlineMs) {
                throw new IOException("response exceeded the overall"
                        + " deadline of " + RPC_OVERALL_TIMEOUT_MS + "ms");
            }
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

    /**
     * Fold the live session's uptime into the stored base and CLOSE
     * the accrual (versionCode 25, audit finding F-05): called by
     * the worker at the moment it observes the node process's exit,
     * with {@link #stateLock} held and its generation verified, so
     * the session's total covers exactly spawn → exit — not the
     * bookkeeping that follows the exit (the drain join, the finish
     * path). The teardown paths' own {@link #saveUptimeLocked} calls
     * afterwards see the -1 anchor and add nothing.
     */
    private void closeUptimeAtExitLocked() {
        if (sessionStartElapsedMs >= 0) {
            saveUptimeLocked();
            sessionStartElapsedMs = -1; // accrual closed at the exit
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
