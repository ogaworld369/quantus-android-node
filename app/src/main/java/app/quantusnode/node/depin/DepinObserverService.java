package app.quantusnode.node.depin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import app.quantusnode.node.depin.core.CompanionStatus;
import app.quantusnode.node.depin.core.CrashLog;
import app.quantusnode.node.depin.core.DepinCore;
import app.quantusnode.node.depin.core.Ed25519;
import app.quantusnode.node.depin.core.Hex;
import app.quantusnode.node.depin.core.JournalStore;
import app.quantusnode.node.depin.core.Json;
import app.quantusnode.node.depin.core.ObservationPlausibility;
import app.quantusnode.node.depin.core.RpcClient;
import app.quantusnode.node.depin.core.SegmentPolicy;
import app.quantusnode.node.depin.core.ServiceGeneration;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The integrated DePIN observer service. It runs in the app's
 * dedicated observer process and is the only component that
 * records checkpoints: every 30 seconds it wakes, and when the
 * current fixed 600-second slot has no checkpoint yet it tries
 * to observe the node once, under the per-slot RPC attempt
 * budget (design section 2.3). The observation logic — the
 * generation guard (M-04), the generation-gated append (F-09),
 * the segment-start streak (F-11), the persisted accumulation
 * hold (M-02) — follows the standalone companion's observer,
 * with the integration deltas noted at each step:
 *
 *  - Launch claims (section 3.1): a fresh instance does nothing
 *    until it has atomically claimed the launch record the main
 *    process issued for it (see verifyLaunchClaim), so a stale
 *    or replayed start intent cannot resurrect observation.
 *  - Command binding (section 3.1): enable / disable commands
 *    carry the identity binding they were issued for and are
 *    discarded, visibly, when the loaded identity does not
 *    match — never silently applied to a different identity.
 *  - Round discipline (section 2.3): every RPC attempt is paid
 *    for in the persistent attempt log BEFORE it is made, one
 *    round per slot, with exactly one in-window retry.
 *  - Circuit breaker (section 3.3): three consecutive
 *    UNEXPECTED failures escaping a tick stop the observer for
 *    good; handled outcomes (unreachable node, inconsistent or
 *    implausible readings, a spent budget) are not failures.
 *  - Status is surfaced two ways that can never diverge: the
 *    DepinStatus DTO is written atomically to the status file
 *    on every change, and the same DTO is answered over the
 *    Messenger binding.
 *
 * The notification tap target is the package's launch intent,
 * obtained from the PackageManager: this layer must not
 * reference the app's UI classes (the dependency-direction
 * check forbids it), and the launch intent is the one entry
 * point that needs no class reference.
 */
public class DepinObserverService extends Service {
    private static final String CHANNEL_ID = "depin_observation";
    private static final int NOTIFICATION_ID = 4203;
    private static final long TICK_SECONDS = 30;
    /** Consecutive unexpected tick failures that trip the
     *  circuit breaker (section 3.3). */
    private static final int MAX_CONSECUTIVE_TICK_FAILURES = 3;

    // Fallback notification texts, used only until the caller
    // supplies its localised strings in the start intent (they
    // are re-delivered with every start, and held across sticky
    // redeliveries). Deliberately minimal.
    private static final String FALLBACK_TITLE = "Quantus DePIN";
    private static final String FALLBACK_CHANNEL_NAME = "DePIN 観測";
    private static final String FALLBACK_CHANNEL_DESC =
            "Quantus ノードの観測サービスが動作中であることを示します";
    private static final String FALLBACK_STARTING = "観測を開始しています";
    private static final String FALLBACK_OBSERVING = "観測中";
    private static final String FALLBACK_CHECKPOINT_FORMAT =
            "最終チェックポイント %1$s / 蓄積 %2$d 件";
    private static final String FALLBACK_CANNOT_START = "観測を開始できません";

    private ScheduledExecutorService scheduler;
    private RpcClient rpc;
    // Volatile (final check round 3, H-03): onDestroy runs on
    // the main thread while ensureInitialized assigns this on
    // the executor; without volatile the destroy path could
    // read a stale null and skip releasing the store hold.
    private volatile JournalStore journal;
    /** The S-05 hold token minted when this instance took
     *  the journal store (jwi.holdToken); presented at both
     *  release sites so a stale instance cannot release a
     *  successor's hold. Volatile for the same reason as
     *  journal: assigned on the executor, read in onDestroy. */
    private volatile long journalHoldToken;
    private AndroidSeedVault vault;
    private RpcAttemptLog attemptLog;
    private byte[] pubkey;
    private String deviceId;
    private volatile boolean identityReady = false;
    private boolean initDone = false;
    private boolean ticksStarted = false;
    /** Plausibility baseline for accepted observations, seeded
     *  from the journal tail during background init so the jump
     *  / regression rules bind across restarts. */
    private ObservationPlausibility plausibility;
    /** Segment-start streak / recorded-tail advance cap. */
    private final SegmentPolicy segmentPolicy = new SegmentPolicy();

    // ---- launch claim (section 3.1) ----
    private boolean claimed = false;
    private String claimedLaunchId = null;

    // ---- round discipline (section 2.3) ----
    /** The slot the attempt gate currently charges attempts to;
     *  -1 outside a round. Written by the tick thread, read by
     *  whichever thread an RPC call happens to run on. */
    private volatile long currentGateSlot = -1;

    // ---- command binding (section 3.1) ----
    private volatile boolean commandDiscarded = false;

    // ---- shutdown bookkeeping ----
    /** Set on every orderly shutdown path (user disable, an
     *  internal stop, a start that finds the observer disabled):
     *  onDestroy then leaves the clean-run marker the supervisor
     *  reads. Self-stops and claim failures deliberately do NOT
     *  set it — a launch that failed must stay countable as one. */
    private volatile boolean orderlyStop = false;
    /** Set at the START of onDestroy (H-03): an
     *  ensureInitialized still in flight on the executor
     *  checks it after taking the journal store, and releases
     *  the store hold itself when the destroy path already
     *  ran (and so could not release a store it never saw). */
    private volatile boolean destroyed = false;
    private final AtomicBoolean selfStopGuard = new AtomicBoolean(false);
    /** Circuit breaker (section 3.3), round-scoped since
     *  final check round 3 (H-09): see DepinTickBreaker. */
    private final DepinTickBreaker tickBreaker =
            new DepinTickBreaker(MAX_CONSECUTIVE_TICK_FAILURES);
    /** The slot whose round the current tick is running, or
     *  -1 when the tick has not passed the already-paid gate
     *  (it then runs no round and feeds the breaker neither
     *  way). Set inside tick(), read by tickSafely() on the
     *  same thread. */
    private long roundAttemptSlot = -1;
    /** True when the current tick's round ended WITHHELD by
     *  the free-space floor (H-11): neither a completed nor a
     *  failed round for breaker purposes. */
    private boolean roundWithheld = false;

    // ---- IPC ----
    private HandlerThread ipcThread;
    private Messenger messenger;
    /** Bundle exports run on this single thread (audit M-4):
     *  two MSG_EXPORT_BUNDLE builds formerly ran concurrently
     *  on ad-hoc threads and raced over the same journal, seed
     *  and staged file. */
    private final java.util.concurrent.ExecutorService exportExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "depin-bundle-export");
                t.setDaemon(true);
                return t;
            });
    /** Localised notification texts supplied by the caller. */
    private DepinIpc.NotifStrings notifStrings;

    @Override
    public IBinder onBind(Intent intent) {
        return messenger != null ? messenger.getBinder() : null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // No history-length work here: journal construction was
        // started on the runtime's background thread, and the
        // identity setup completes later on this service's own
        // executor (see ensureInitialized) — after the foreground
        // transition. onCreate only wires the cheap pieces: the
        // RPC client with its attempt gate, and the IPC endpoint.
        rpc = new RpcClient(RpcClient.DEFAULT_ENDPOINT);
        rpc.setAttemptGate(method -> {
            // Section 2.3: an attempt is recorded as consumed
            // BEFORE the request is issued, charged to the slot
            // whose round is in flight. Outside a round there is
            // no slot to charge, and the call must not happen.
            long slot = currentGateSlot;
            RpcAttemptLog log = attemptLog;
            if (slot < 0 || log == null) {
                throw new RpcClient.RpcException(
                        "attempt gate: no active round for " + method);
            }
            try {
                log.consume(slot, method);
            } catch (RpcAttemptLog.AttemptLogException e) {
                throw new RpcClient.RpcException(
                        "attempt budget: " + e.getMessage(), e);
            }
        });
        DepinRuntime.prefetchJournal(this);

        ipcThread = new HandlerThread("depin-ipc");
        ipcThread.start();
        Handler ipcHandler = new Handler(ipcThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                if (msg.what == DepinIpc.MSG_GET_STATUS
                        && msg.replyTo != null) {
                    Message reply = Message.obtain(
                            null, DepinIpc.MSG_STATUS);
                    reply.setData(buildStatus().toBundle());
                    try {
                        msg.replyTo.send(reply);
                    } catch (RemoteException ignored) {
                        // The caller went away; the status file
                        // still carries the same snapshot.
                    }
                } else if (msg.what == DepinIpc.MSG_EXPORT_BUNDLE
                        && msg.replyTo != null) {
                    // Bundle building reads the whole journal and
                    // unwraps the identity seed: never on the IPC
                    // thread. The reply carries only the outcome
                    // and the staged file's suggested name; the
                    // JSON itself stays in the staged file.
                    final Messenger replyTo = msg.replyTo;
                    exportExecutor.execute(() -> {
                        Bundle data = new Bundle();
                        try {
                            DepinBundleExport.Staged staged =
                                    DepinBundleExport.buildAndStage(
                                            DepinObserverService.this);
                            if (staged == null) {
                                data.putInt(DepinIpc.KEY_BUNDLE_RESULT,
                                        DepinIpc.BUNDLE_RESULT_NONE);
                            } else {
                                data.putInt(DepinIpc.KEY_BUNDLE_RESULT,
                                        DepinIpc.BUNDLE_RESULT_OK);
                                data.putString(DepinIpc.KEY_BUNDLE_NAME,
                                        staged.fileName);
                            }
                        } catch (Throwable t) {
                            DepinRuntime.recordError(
                                    DepinObserverService.this,
                                    "bundle_export", t);
                            data.putInt(DepinIpc.KEY_BUNDLE_RESULT,
                                    DepinIpc.BUNDLE_RESULT_ERROR);
                            data.putString(DepinIpc.KEY_BUNDLE_ERROR,
                                    t.getClass().getSimpleName()
                                            + ": " + t.getMessage());
                        }
                        Message reply = Message.obtain(
                                null, DepinIpc.MSG_BUNDLE_READY);
                        reply.setData(data);
                        try {
                            replyTo.send(reply);
                        } catch (RemoteException ignored) {
                            // The caller went away; the staged
                            // file is overwritten by the next
                            // export or dropped on the next save.
                        }
                    });
                } else {
                    super.handleMessage(msg);
                }
            }
        };
        messenger = new Messenger(ipcHandler);
        sweepStaleStatusTmp();
    }

    /** I-05: remove status tmp files a killed writer left
     *  behind. Only files older than a minute, so a write in
     *  flight on another thread is never raced. */
    private void sweepStaleStatusTmp() {
        try {
            File target = DepinFiles.statusFile(this);
            File[] files = target.getParentFile().listFiles();
            if (files == null) {
                return;
            }
            String prefix = target.getName() + ".tmp";
            long now = System.currentTimeMillis();
            for (File f : files) {
                if (f.getName().startsWith(prefix)
                        && now - f.lastModified() > 60_000L) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        } catch (Throwable ignored) {
            // Housekeeping only.
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        // Internal stop: no foreground transition is owed for a
        // plain startService delivery, and none is attempted —
        // the same shape the companion used.
        if (DepinIpc.ACTION_STOP.equals(action)) {
            orderlyStop = true;
            ServiceGeneration.PROCESS.requestStop();
            CompanionStatus.serviceRunning = false;
            stopSelf();
            return START_NOT_STICKY;
        }

        // Notification texts ride the start intent and are held
        // on the instance, so a sticky redelivery (which may
        // carry no extras at all) keeps the last supplied set.
        if (intent != null) {
            Bundle notifBundle = intent.getBundleExtra(DepinIpc.EXTRA_NOTIF);
            if (notifBundle != null) {
                notifStrings = DepinIpc.NotifStrings.fromBundle(notifBundle);
                // Final check round 4 (I-20): keep the set
                // for starts that arrive without one.
                try {
                    DepinSettings.recordNotifStrings(this, notifStrings);
                } catch (Throwable ignored) {
                    // The instance still holds the set.
                }
            }
        }
        if (notifStrings == null) {
            // I-20: only the user's own enable carries the
            // texts. A reconcile-issued ACTION_START or a
            // sticky restart in a NEW process has none, and
            // the notification then fell back to the built-in
            // Japanese defaults whatever the app's language.
            try {
                notifStrings = DepinSettings.notifStrings(this);
            } catch (Throwable ignored) {
                // Built-in defaults, as before.
            }
        }

        // Section 3.1, first-command disable: an instance that
        // has not claimed a launch yet applies a disable WITHOUT
        // opening the store — the flag is the whole request.
        // The foreground transition is attempted best-effort
        // first, in case this delivery did come through the
        // foreground-service route.
        if (!claimed && DepinIpc.ACTION_DISABLE.equals(action)) {
            try {
                createNotificationChannel();
                startForegroundNow(buildNotification(nsStarting()));
            } catch (Throwable ignored) {
                // Not a foreground delivery; the disable below
                // stands on its own.
            }
            DepinSettings.setDisabled(this);
            orderlyStop = true;
            writeStatusFile(offStatus());
            try {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } catch (Throwable ignored) {
                // Never went foreground; nothing to remove.
            }
            stopSelf();
            return START_NOT_STICKY;
        }

        // The foreground-service contract: startForeground MUST
        // be called promptly, whatever state onCreate left
        // behind. Go foreground FIRST, then decide whether the
        // command may proceed — a recoverable failure must not
        // become a did-not-start-in-time crash.
        try {
            createNotificationChannel();
            startForegroundNow(buildNotification(nsStarting()));
        } catch (Throwable t) {
            DepinRuntime.recordError(this, "start_foreground", t);
            CompanionStatus.identityError = describe(t);
            CompanionStatus.startRequestedUnix = 0;
            stopSelf();
            return START_NOT_STICKY;
        }

        // Section 3.1: before its first claim, an instance must
        // prove it is the launch the main process most recently
        // issued. Commands re-sent to an already-claimed
        // instance need no new claim.
        if (!claimed) {
            String launchId = intent != null
                    ? intent.getStringExtra(DepinIpc.EXTRA_LAUNCH_ID)
                    : null;
            String failure = verifyLaunchClaim(launchId);
            if (failure != null) {
                DepinStatus s = DepinStatus.unavailable();
                s.lastError = failure;
                // F-04: never assert an enablement the user
                // has revoked — this write's fresh stamp made
                // it the merge's "freshest fact" in the field,
                // and it is produced before any command (and
                // therefore any intent) is processed.
                s.enabled = DepinSettings.isEnabled(this)
                        && !userIntentOff();
                s.updatedWallMs = System.currentTimeMillis();
                writeStatusFile(s);
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return START_NOT_STICKY;
            }
            claimed = true;
            claimedLaunchId = launchId;
        }

        if (scheduler == null || scheduler.isShutdown()) {
            if (selfStopGuard.get()) {
                return START_NOT_STICKY; // a self-stop is in flight
            }
            // A fresh service generation begins with this start;
            // ticks captured under any earlier generation are
            // stale from here on (M-04).
            ServiceGeneration.PROCESS.beginNewGeneration();
            scheduler = newServiceExecutor();
        }

        // Commands run on the executor: identity initialisation
        // (history-length work) and the binding check belong
        // there, never on this caller thread.
        final String expectedDeviceId = intent != null
                ? intent.getStringExtra(DepinIpc.EXTRA_EXPECTED_DEVICE_ID)
                : null;
        final long expectedGeneration = intent != null
                ? intent.getLongExtra(DepinIpc.EXTRA_EXPECTED_GENERATION, 0L)
                : 0L;
        try {
            if (DepinIpc.ACTION_ENABLE.equals(action)) {
                scheduler.execute(() ->
                        runCommand(() -> processEnable(
                                expectedDeviceId, expectedGeneration)));
            } else if (DepinIpc.ACTION_DISABLE.equals(action)) {
                scheduler.execute(() ->
                        runCommand(() -> processDisable(
                                expectedDeviceId, expectedGeneration)));
            } else {
                // ACTION_START (reconcile after a restart) and
                // anything unrecognised: reconcile against the
                // persisted enable state.
                scheduler.execute(() -> runCommand(this::processStart));
            }
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return START_NOT_STICKY; // executor shut down mid-delivery
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        // H-03: mark destroyed before anything else, so an
        // ensureInitialized completing after this point sees
        // it and releases the store hold itself (below, the
        // release can only cover a store already assigned).
        destroyed = true;
        // Request the stop FIRST — flag latched, generation
        // advanced — so a tick still in flight on the executor
        // sees its generation go stale and returns without
        // appending (M-04).
        ServiceGeneration.PROCESS.requestStop();
        CompanionStatus.serviceRunning = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (journal != null) {
            // G-06: this instance no longer holds the store;
            // an invalidation deferred while it was live (a
            // transfer operation ran) takes effect now.
            DepinRuntime.observerReleasedStore(journal, journalHoldToken);
        }
        // The clean-run marker is written only for an orderly
        // stop of a claimed launch: it is the supervisor's
        // evidence that this launch ended because it was asked
        // to, not because it died. Best effort — a missing
        // marker only ever makes the supervisor MORE cautious.
        if (orderlyStop && claimedLaunchId != null) {
            try {
                File clean = new File(DepinFiles.supervisorDir(this),
                        "clean-" + claimedLaunchId);
                if (!clean.exists()) {
                    try (FileOutputStream out = new FileOutputStream(clean)) {
                        out.getFD().sync();
                    }
                }
            } catch (Throwable ignored) {
                // Best effort by design; see above.
            }
        }
        if (vault != null) {
            // Emergency wipe hook: since the vault keeps no
            // plaintext seed cache, a safeguard rather than a
            // load-bearing zeroing.
            vault.lock();
        }
        if (ipcThread != null) {
            ipcThread.quitSafely();
            ipcThread = null;
        }
        super.onDestroy();
    }

    // ---------------- executor plumbing ----------------

    /** The single service executor, with the dedicated
     *  uncaught-exception handler section 3.3 requires: an
     *  exception that escapes a task on THIS thread is recorded
     *  and stops the observer (REASON_UNCAUGHT). The process-wide
     *  default handler is deliberately NOT replaced — threads
     *  this service does not own keep the platform behaviour. */
    private ScheduledExecutorService newServiceExecutor() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "depin-observer");
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, e) -> {
                DepinRuntime.recordError(this, "uncaught", e);
                selfStop(DepinIpc.REASON_UNCAUGHT, describe(e));
            });
            return t;
        });
    }

    /** Run one command body, keeping an escape from becoming a
     *  silent executor death: the failure is recorded and
     *  surfaced in the status. (The thread's uncaught handler
     *  remains the backstop for failures outside command bodies,
     *  e.g. inside the tick wrapper's own error handling.) */
    private void runCommand(Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            DepinRuntime.recordError(this, "command", t);
            CompanionStatus.lastError = describe(t);
            writeStatusFile(buildStatus());
        }
    }

    // ---------------- launch claim (section 3.1) ----------------

    /**
     * Verify and atomically claim a launch: the record file
     * launch-&lt;id&gt;.json in the supervisor directory must
     * exist, name this id, and be unexpired by wall clock; the
     * claim itself is the createNewFile() of launch-&lt;id&gt;.claimed,
     * which succeeds for exactly one claimant, ever — a replayed
     * intent, a second instance, or a stale id left over from an
     * earlier launch all fail here.
     *
     * @return null on success (the launch is claimed), else the
     *         human-readable reason the launch was refused
     */
    private String verifyLaunchClaim(String launchId) {
        if (launchId == null || launchId.isEmpty()
                || !launchId.matches("[A-Za-z0-9_-]{1,64}")) {
            // The id becomes a file name below; anything outside
            // the issued shape is refused before it can be one.
            return "launch claim refused: missing or malformed launch id";
        }
        File dir = DepinFiles.supervisorDir(this);
        File record = new File(dir, "launch-" + launchId + ".json");
        if (!record.exists()) {
            return "launch claim refused: no launch record for this id";
        }
        try {
            String text;
            try (FileInputStream in = new FileInputStream(record)) {
                byte[] buf = new byte[(int) Math.min(record.length(), 65536L)];
                int off = 0;
                int n;
                while (off < buf.length
                        && (n = in.read(buf, off, buf.length - off)) > 0) {
                    off += n;
                }
                text = new String(buf, 0, off, StandardCharsets.UTF_8);
            }
            Map<String, Object> m =
                    Json.parseObject(text);
            String recordedId =
                    Json.asString(m.get("id"));
            if (!launchId.equals(recordedId)) {
                return "launch claim refused: launch record names a different id";
            }
            long expires = Json.asLong(
                    m.get("expiresWallMs"));
            if (System.currentTimeMillis() > expires) {
                return "launch claim refused: launch record has expired";
            }
        } catch (Throwable t) {
            return "launch claim refused: launch record unreadable ("
                    + describe(t) + ")";
        }
        File claim = new File(dir, "launch-" + launchId + ".claimed");
        try {
            if (!claim.createNewFile()) {
                return "launch claim refused: launch already claimed";
            }
            // Durable before the claim is acted on: a process
            // death from here on must not free the launch for a
            // second claimant.
            try (FileOutputStream out = new FileOutputStream(claim, true)) {
                out.getFD().sync();
            }
        } catch (Throwable t) {
            return "launch claim refused: could not create claim ("
                    + describe(t) + ")";
        }
        return null;
    }

    // ---------------- initialisation (executor thread) ----------------

    /**
     * Identity + journal + attempt-log initialisation, run on
     * the service executor (never on a caller thread: the journal
     * load is history-length work). Idempotent — the first
     * command performs it, later commands reuse the result.
     *
     * Failure policy: an attempt log found corrupt stops the
     * observer at once (REASON_ATTEMPT_LOG — a budget that cannot
     * be trusted must never be bypassed by observing anyway), and
     * a journal / identity failure stops it at once too, with the
     * reason chosen by which of the runtime's error mirrors is
     * set (REASON_JOURNAL_OPEN when neither is — the journal side
     * is the default suspect, as in the companion). ONE exception
     * (internal.25): a journal failure whose writer-gate verdict
     * is authorisation-dependent (DepinGateVerdict) is an
     * authorisation wait — the run stops orderly WITHOUT a
     * self-stop record, because the verdict may describe the
     * settings as they were before the enable command that this
     * launch answers recorded its authorisation.
     *
     * @return true when the identity is ready for commands/ticks
     */
    private boolean ensureInitialized() {
        if (initDone) {
            return true;
        }
        if (selfStopGuard.get()) {
            return false;
        }
        // Audit M6: catch the settings' DISPLAY binding up to
        // the transfer-active identity before anything reads
        // it (an update onto a completed transfer otherwise
        // leaves it naming the displaced identity until the
        // rebind at the end of this method — which a held
        // construction never reaches).
        DepinTransferRuntime.syncSettingsBindingToTransferActive(this);
        // The attempt log comes first: it is cheap, and a corrupt
        // budget must fail the start BEFORE any identity or
        // journal work lends the run legitimacy.
        try {
            attemptLog = new RpcAttemptLog(DepinFiles.attemptLogFile(this));
        } catch (Throwable t) {
            DepinRuntime.recordError(this, "attempt_log_open", t);
            CompanionStatus.lastError = describe(t);
            selfStop(DepinIpc.REASON_ATTEMPT_LOG, describe(t));
            return false;
        }
        // H-01 (final check round 3): the reset request is
        // consumed at EVERY initialisation, whether or not
        // the log turns out to be corrupt. The pre-H-01 code
        // consumed it only on the corrupt branch, so a
        // request made against an in-memory-only corruption
        // (an append failure had marked the log corrupt in
        // memory while the file stayed healthy) survived the
        // restart that cleared it — and a LATER, unrelated
        // corruption was then reset without any confirmation,
        // breaking the "one confirmation, one reset" rule
        // the button exists to keep. A request that finds a
        // healthy log is spent unexecuted: re-enabling was
        // the remedy for the in-memory case all along.
        boolean resetRequested = consumeAttemptResetRequest();
        if (attemptLog.isCorrupt()) {
            if (!resetRequested || !performConfirmedAttemptReset()) {
                CompanionStatus.lastError = attemptLog.corruptDetail();
                selfStop(DepinIpc.REASON_ATTEMPT_LOG,
                        attemptLog.corruptDetail());
                return false;
            }
        }
        final DepinRuntime.JournalWithIdentity jwi;
        try {
            // Audit M5: store and identity are read atomically
            // (see DepinRuntime.journalWithIdentity) — the
            // separate volatile reads this replaces could pair
            // a store with a null or next-generation identity
            // when a transfer operation invalidated the cache
            // in between.
            // H-03: the OBSERVER variant registers the G-06
            // hold inside the runtime's lock, before the store
            // is handed out — a separate adopt call after the
            // return left a window in which an invalidation
            // dropped the cache and the next reader built a
            // second store over the same file.
            jwi = DepinRuntime.journalWithIdentityForObserver(this);
            journal = jwi.store;
            journalHoldToken = jwi.holdToken;
        } catch (Throwable t) {
            // Internal.25: an authorisation-dependent gate
            // verdict (DepinGateVerdict) is a WAIT, not a
            // failure of this run. The construction evaluated
            // the gate against a recorded authorisation that
            // was absent at evaluation time — the onCreate
            // prefetch can win that race against the enable
            // command this launch answers — and the blocking
            // read has already re-evaluated once against the
            // current settings by the time this refusal is
            // served. Recording a self-stop here would write
            // enabled=false (recordSelfStop), erasing an
            // authorisation an enable command may have just
            // recorded and making the stopped state
            // self-perpetuating — the internal.24 device
            // failure. Instead: publish the status as it
            // stands (enabled, not yet initialised =
            // PREPARING; or OFF when no authorisation exists)
            // and stop ORDERLY, like a start that found the
            // observer disabled. The reconcile's next launch
            // re-evaluates the gate fresh; with the
            // authorisation recorded it proceeds, without one
            // processStart presents OFF without reaching here.
            if (DepinGateVerdict.initFailure(
                    DepinRuntime.journalGateReason())
                    == DepinGateVerdict.InitFailure
                            .AUTHORIZATION_WAIT) {
                CompanionStatus.lastError =
                        CompanionStatus.journalError;
                writeStatusFile(buildStatus());
                orderlyStop = true;
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                } catch (Throwable ignored) {
                    // May never have gone foreground; stopSelf
                    // below is the real end.
                }
                stopSelf();
                return false;
            }
            String reason = CompanionStatus.journalError != null
                    ? DepinIpc.REASON_JOURNAL_OPEN
                    : CompanionStatus.identityError != null
                            ? DepinIpc.REASON_IDENTITY
                            : DepinIpc.REASON_JOURNAL_OPEN;
            String detail = CompanionStatus.journalError != null
                    ? CompanionStatus.journalError
                    : describe(t);
            CompanionStatus.lastError = detail;
            try {
                updateNotification(nsCannotStart());
            } catch (Throwable ignored) {
                // The self-stop below removes the notification
                // anyway; this only mirrors the companion's
                // say-why-before-stopping shape.
            }
            selfStop(reason, detail);
            return false;
        }
        if (destroyed) {
            // H-03: onDestroy already ran (on the main thread)
            // while this initialisation was in flight, and its
            // release saw no store assigned yet. Release the
            // hold here instead of leaking it — a leaked hold
            // would defer every future cache invalidation
            // forever, and this run is over either way.
            DepinRuntime.observerReleasedStore(journal, journalHoldToken);
            return false;
        }
        try {
            vault = new AndroidSeedVault(this);
            // The identity is the one the journal was
            // CONSTRUCTED under — the writer resolution's
            // outcome (the app vault's own identity for a
            // shadow store, the received identity for a
            // transferred-in store). Deriving it from the app
            // vault here instead made bindIdentity refuse
            // every received identity, so a transferred-in
            // identity could never be recorded (internal.11
            // defect; progress file section 9).
            deviceId = jwi.deviceId;
            pubkey = jwi.pubkey;
            if (deviceId == null || pubkey == null) {
                throw new IllegalStateException(
                        "journal constructed without an identity");
            }
            journal.bindIdentity(deviceId, Hex.encode(pubkey));
        } catch (Throwable t) {
            DepinRuntime.recordError(this, "service_init", t);
            CompanionStatus.identityError = describe(t);
            CompanionStatus.lastError = describe(t);
            selfStop(DepinIpc.REASON_IDENTITY, describe(t));
            return false;
        }
        CompanionStatus.deviceId = deviceId;
        CompanionStatus.identityError = null;

        DepinCore.Checkpoint last = journal.last();
        // Seed the plausibility baseline from the persisted
        // journal tail: the last accepted observation of previous
        // runs is the baseline of this one, so the rules bind
        // across restarts exactly as within a run.
        plausibility = (last != null)
                ? ObservationPlausibility.seededWith(
                        last.bestBlockNumber, last.timestampUnix)
                : new ObservationPlausibility();
        // Surface a hold persisted by a previous process: the
        // notice must be true from the first status after a
        // restart, not only after the next disagreeing tick.
        JournalStore.HoldInfo hold = journal.holdInfo();
        if (hold != null) {
            CompanionStatus.lastError =
                    "ノードの応答が記録済みの履歴（best " + hold.tailBest
                            + "）と食い違うためチェックポイントを保留しています"
                            + "（現在の応答 best " + hold.liveBest + "）";
        }
        rebindActiveIdentity(deviceId);
        syncMirrorFromJournal();
        identityReady = true;
        initDone = true;
        return true;
    }

    /**
     * Bring the settings' active binding onto the given
     * identity (the one actually loaded, or the transfer-live
     * one). The first binding keeps an existing generation
     * value when one is somehow already recorded, and starts
     * at 1 otherwise. A CHANGED identity — a completed
     * transfer displaced the bound one — is rebound with the
     * generation advanced, so commands addressed to the old
     * binding stop matching it. The enable authorisation is
     * deliberately NOT touched: it is identity-bound and never
     * inherited by a different identity (section 3.2/R13-6);
     * the writer gate re-checks it against the transfer-active
     * identity on every construction. A self-stop record
     * describing the previous identity's run is cleared: it
     * must not be presented as the new identity's state.
     */
    private void rebindActiveIdentity(String liveId) {
        String bound = DepinSettings.activeDeviceId(this);
        if (bound == null) {
            long existing = DepinSettings.identityGeneration(this);
            DepinSettings.bindActiveIdentity(
                    this, liveId, existing != 0 ? existing : 1L);
        } else if (!bound.equals(liveId)) {
            DepinSettings.bindActiveIdentity(this, liveId,
                    DepinSettings.identityGeneration(this) + 1);
            DepinSettings.clearSelfStopRecord(this);
        }
    }

    /** Mirror the journal's durable facts into the process-wide
     *  status fields. Called when initialisation completes, after
     *  every checkpoint append, and whenever the hold changes —
     *  the three moments the facts themselves can change. */
    private void syncMirrorFromJournal() {
        if (journal == null) {
            return;
        }
        CompanionStatus.checkpointCount = journal.count();
        DepinCore.Checkpoint last = journal.last();
        if (last != null) {
            CompanionStatus.lastCheckpointUnix = last.timestampUnix;
            CompanionStatus.lastBest = last.bestBlockNumber;
            CompanionStatus.lastFinalized =
                    last.finalizedBlockNumber == null ? -1 : last.finalizedBlockNumber;
            // Checkpoint.observedPeers is a long; the status field
            // is a display int, and values this service records
            // pass the RPC intake bound long before storage.
            CompanionStatus.lastPeers = (int) last.observedPeers;
        }
        JournalStore.HoldInfo hold = journal.holdInfo();
        if (hold != null) {
            CompanionStatus.holdTailBest = hold.tailBest;
            CompanionStatus.holdLiveBest = hold.liveBest;
        } else {
            CompanionStatus.holdTailBest = -1;
            CompanionStatus.holdLiveBest = -1;
        }
        if (deviceId != null) {
            CompanionStatus.deviceId = deviceId;
        }
    }

    // ---------------- commands (executor thread) ----------------

    /**
     * The section 3.1 binding check: a command applies only when
     * every expectation it carries matches the ACTIVE binding
     * (the identity actually loaded, as recorded by
     * bindActiveIdentity). A null device id / a generation of 0
     * means "no expectation" for that half. This also covers the
     * first enable of a fresh identity: initialisation has just
     * bound the generated identity, so an expectation-free enable
     * matches it trivially, and an enable issued for some OTHER
     * identity is discarded like any other mismatch.
     */
    private boolean bindingMatches(String expectedDeviceId, long expectedGeneration) {
        String activeId = DepinSettings.activeDeviceId(this);
        long activeGeneration = DepinSettings.identityGeneration(this);
        if (expectedDeviceId != null && !expectedDeviceId.equals(activeId)) {
            return false;
        }
        return expectedGeneration == 0 || expectedGeneration == activeGeneration;
    }

    /**
     * Whether the user's recorded toggle intent — read fresh
     * from its file on every call, never through a
     * SharedPreferences cache (the cache is the F-03 defect)
     * — is OFF. No record, or an unreadable one, is not an
     * OFF: the pre-existing settings decide, as before.
     */
    private boolean userIntentOff() {
        DepinUserIntent.Record intent = DepinUserIntent.read(this);
        return intent.present && !intent.enabled;
    }

    /**
     * Land the user's recorded OFF in this process
     * (internal.26, F-03/F-04): the settings flag is written
     * HERE — the single-writer rule for depin_settings —
     * ticks stop, the OFF status is written before the
     * foreground state is torn down, and the service stops
     * orderly, exactly as a processed ACTION_DISABLE lands.
     * This is how an OFF the observer never lived to receive
     * (it was dead; the facade sends no command then) still
     * takes effect at the next start, instead of the start
     * resurrecting recording under a stale flag.
     */
    private void applyUserOff() {
        commandDiscarded = false;
        DepinSettings.setDisabled(this);
        ServiceGeneration.PROCESS.requestStop();
        CompanionStatus.serviceRunning = false;
        stopTicksAndScheduler();
        writeStatusFile(buildStatus());
        orderlyStop = true;
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignored) {
            // See processDisable.
        }
        stopSelf();
    }

    private void processEnable(String expectedDeviceId, long expectedGeneration) {
        // The user's later act wins over this command
        // (internal.26, F-03/F-04): enable() records the ON
        // intent before dispatching, so a recorded OFF here
        // means the user turned it back off after this
        // command was sent — and a disable that found no
        // live observer sends no ACTION_DISABLE to overtake
        // it. Honour the record, not the overtaken command.
        if (userIntentOff()) {
            applyUserOff();
            return;
        }
        // Transfer-introduced installs: the live identity is
        // the transfer state's active binding, and this command
        // is an authorisation FOR THAT identity. The order
        // matters (progress file section 9): the writer gate
        // requires the recorded enable to name the
        // transfer-active identity BEFORE a writer may be
        // constructed (section 3.2), so the authorisation is
        // recorded first — from this explicit command only. A
        // displaced identity's enable is never inherited
        // (R13-6): without this command, nothing here records
        // an enable for the new identity.
        String liveId = DepinTransferRuntime.activeBindingDeviceId(this);
        if (liveId != null) {
            if (expectedDeviceId != null
                    && !expectedDeviceId.equals(liveId)) {
                // Addressed to a displaced identity: discarded,
                // and visibly so, like any other mismatch.
                commandDiscarded = true;
                writeStatusFile(buildStatus());
                return;
            }
            commandDiscarded = false;
            rebindActiveIdentity(liveId);
            // setEnabled also clears any earlier self-stop
            // record: it described why a PREVIOUS run stopped.
            DepinSettings.setEnabled(this, liveId,
                    DepinSettings.identityGeneration(this));
            if (!ensureInitialized()) {
                return;
            }
            startTicksIfNeeded();
            updateNotification(nsObserving());
            writeStatusFile(buildStatus());
            return;
        }
        if (!ensureInitialized()) {
            return;
        }
        if (!bindingMatches(expectedDeviceId, expectedGeneration)) {
            // Discarded, and visibly so: the flag is surfaced in
            // the status until the next well-bound command.
            commandDiscarded = true;
            writeStatusFile(buildStatus());
            return;
        }
        commandDiscarded = false;
        // setEnabled also clears any earlier self-stop record:
        // it described why a PREVIOUS run stopped.
        DepinSettings.setEnabled(this,
                DepinSettings.activeDeviceId(this),
                DepinSettings.identityGeneration(this));
        startTicksIfNeeded();
        updateNotification(nsObserving());
        writeStatusFile(buildStatus());
    }

    private void processDisable(String expectedDeviceId, long expectedGeneration) {
        // A disable never requires the journal. Requiring
        // initialisation here converted the transfer freeze's
        // drain into a self-stop whenever the writer gate was
        // closed for the identity being frozen — the
        // production "transfer gate: frozen_s1" self-stop
        // record: the freeze asked for OFF and a persisted
        // failure was shown instead (progress file section 9).
        // A disable applies when it carries no expectation,
        // names the settings binding, or names the
        // transfer-live identity; anything else is addressed
        // to some other identity and is discarded. The
        // generation half is not consulted: a disable only
        // ever stops recording (the fail-safe direction), and
        // the freeze reads its expectation fresh from the
        // same settings at send time.
        String boundId = DepinSettings.activeDeviceId(this);
        String liveId = DepinTransferRuntime.activeBindingDeviceId(this);
        boolean applies = expectedDeviceId == null
                || expectedDeviceId.equals(boundId)
                || (liveId != null && expectedDeviceId.equals(liveId));
        if (!applies) {
            commandDiscarded = true;
            writeStatusFile(buildStatus());
            return;
        }
        commandDiscarded = false;
        DepinSettings.setDisabled(this);
        ServiceGeneration.PROCESS.requestStop();
        CompanionStatus.serviceRunning = false;
        stopTicksAndScheduler();
        // The OFF status is written BEFORE the foreground state
        // is torn down, so no reader can catch a window where the
        // service is gone but the file still says recording.
        writeStatusFile(buildStatus());
        orderlyStop = true;
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignored) {
            // Already detached; stopSelf below is the real end.
        }
        stopSelf();
    }

    /** ACTION_START: reconcile a (re)start against the persisted
     *  enable state. Enabled -> observe. Disabled -> the start
     *  was speculative; write OFF and leave, orderly. */
    private void processStart() {
        // The recorded user intent first (internal.26,
        // F-03/F-04): a start — reconcile-issued or a sticky
        // redelivery — must not resume recording the user has
        // revoked, however the settings flag still reads. The
        // flag may still say enabled=true precisely because
        // the disable found no observer to land on; landing
        // it is this process's own write (applyUserOff).
        if (userIntentOff()) {
            applyUserOff();
            return;
        }
        // Transfer-introduced installs reconcile against the
        // transfer-live identity: a persisted authorisation
        // naming a DISPLACED identity is never inherited
        // (R13-6). It is retired — flag off, the binding kept
        // as the record of what was last authorised — and the
        // service presents OFF under the live binding instead
        // of self-stopping on the writer gate's
        // not-switched-on refusal.
        String liveId = DepinTransferRuntime.activeBindingDeviceId(this);
        if (liveId != null) {
            commandDiscarded = false;
            boolean resume = DepinSettings.isEnabled(this)
                    && liveId.equals(
                            DepinSettings.enabledDeviceId(this));
            if (!resume) {
                if (DepinSettings.isEnabled(this)) {
                    DepinSettings.setDisabled(this);
                }
                rebindActiveIdentity(liveId);
                writeStatusFile(buildStatus());
                orderlyStop = true;
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                } catch (Throwable ignored) {
                    // See processDisable.
                }
                stopSelf();
                return;
            }
            if (!ensureInitialized()) {
                return;
            }
            startTicksIfNeeded();
            updateNotification(nsObserving());
            writeStatusFile(buildStatus());
            return;
        }
        if (!ensureInitialized()) {
            return;
        }
        commandDiscarded = false;
        if (DepinSettings.isEnabled(this)) {
            startTicksIfNeeded();
            updateNotification(nsObserving());
            writeStatusFile(buildStatus());
        } else {
            writeStatusFile(buildStatus());
            orderlyStop = true;
            try {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } catch (Throwable ignored) {
                // See processDisable.
            }
            stopSelf();
        }
    }

    private void startTicksIfNeeded() {
        if (ticksStarted || scheduler == null || scheduler.isShutdown()
                || selfStopGuard.get()) {
            return;
        }
        ticksStarted = true;
        CompanionStatus.serviceRunning = true;
        CompanionStatus.startRequestedUnix = 0;
        scheduler.scheduleWithFixedDelay(this::tickSafely,
                0, TICK_SECONDS, TimeUnit.SECONDS);
    }

    private void stopTicksAndScheduler() {
        ticksStarted = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /**
     * The design section 2.3 repair for a corrupt attempt log
     * (G-11): resume ONLY through a user-confirmed maintenance
     * reset. The confirmation is a durable request file the UI
     * side writes after the user's explicit confirmation on the
     * settings screen — a bare flag in exactly the user-intent
     * shape: written by the main process as a request, acted on
     * only here, in the process that owns the log and its
     * settings (the settings writers stay :depin-only, G-01).
     * The request is consumed (deleted) at EVERY
     * initialisation — corrupt log or not (H-01) — so one
     * confirmation authorises at most one repair, and a
     * request made against a corruption that turns out to be
     * in-memory only can never survive to reset a later,
     * different corruption unconfirmed. The reset itself is
     * RpcAttemptLog's: the corrupt bytes are set aside under
     * a fixed name (rpc-attempts.jsonl.corrupt, with -1, -2…
     * on collision), never deleted, and the fact is recorded
     * in the settings
     * ({@link DepinSettings#recordAttemptLogReset}) and the
     * dedicated log so the status surface shows that it
     * happened and when.
     */

    /** Consume the UI's reset-request file, if present.
     *
     * @return true when a request existed and was consumed */
    private boolean consumeAttemptResetRequest() {
        java.io.File request =
                DepinFiles.attemptResetRequestFile(this);
        if (!request.exists()) {
            return false;
        }
        //noinspection ResultOfMethodCallIgnored
        request.delete();
        return true;
    }

    /** Run the confirmed reset. Only called when the log is
     *  corrupt AND this initialisation consumed a request.
     *  Returns true when the log is healthy afterwards. */
    private boolean performConfirmedAttemptReset() {
        java.io.File aside = attemptLog.resetCorrupt();
        if (attemptLog.isCorrupt()) {
            if (aside == null) {
                DepinRuntime.recordError(this, "attempt_log_reset",
                        new IllegalStateException(
                                "confirmed reset could not set the "
                                        + "corrupt attempt log aside"));
            }
            return false;
        }
        try {
            DepinSettings.recordAttemptLogReset(this);
        } catch (Throwable ignored) {
            // The reset stands (the corrupt bytes are aside and
            // the log is healthy); the record is best-effort,
            // like the self-stop record's.
        }
        // Design section 2.3 records the execution in the
        // status display AND a dedicated log (H-13): the
        // settings timestamp alone could vanish with the
        // settings, leaving no trace a budget record was
        // ever reset.
        app.quantusnode.node.depin.core.CrashLog.recordMessageAt(
                DepinFiles.crashLogFile(this), "attempt_log_reset",
                "confirmed reset of the RPC attempt budget log executed");
        CompanionStatus.lastError = null;
        return true;
    }

    // ---------------- self-stop (section 3.3) ----------------

    /**
     * Stop the observer on its own initiative, exactly once:
     * record the self-stop durably (which also turns the enable
     * flag off, so a restart presents as stopped, never as an
     * enable retrying the same failure forever), advance the
     * generation, halt the scheduler, publish the SELF_STOPPED
     * status, and leave the foreground state. Callable from any
     * thread — the tick thread, a command, the executor's
     * uncaught-exception handler.
     */
    private void selfStop(String reason, String detail) {
        if (!selfStopGuard.compareAndSet(false, true)) {
            return;
        }
        try {
            DepinSettings.recordSelfStop(this, reason, detail);
        } catch (Throwable ignored) {
            // The remaining steps still run: a settings write
            // failure must not leave a failed observer running.
        }
        ServiceGeneration.PROCESS.requestStop();
        CompanionStatus.serviceRunning = false;
        CompanionStatus.startRequestedUnix = 0;
        if (detail != null) {
            CompanionStatus.lastError = detail;
        }
        stopTicksAndScheduler();
        writeStatusFile(buildStatus());
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignored) {
            // May never have gone foreground; stopSelf is next.
        }
        stopSelf();
    }

    // ---------------- status surface ----------------

    /**
     * Assemble the current status DTO. State derivation, in
     * order (design section 3.1): a VALID self-stop record (its
     * time is not older than the last enable, and it names a
     * reason) wins over everything; then disabled is OFF; then a
     * not-yet-initialised identity is PREPARING; then a persisted
     * hold is HELD; otherwise the observer is RECORDING. A stale
     * self-stop record (an enable happened after it) is not
     * surfaced at all — it describes a previous run.
     */
    private DepinStatus buildStatus() {
        DepinStatus s = new DepinStatus();
        boolean enabled = DepinSettings.isEnabled(this);
        String reason = DepinSettings.selfStopReason(this);
        long stopUnix = DepinSettings.selfStopUnix(this);
        long enableUnix = DepinSettings.lastEnableUnix(this);
        boolean selfStopped = reason != null && stopUnix >= enableUnix;
        boolean hold = journal != null && journal.holdInfo() != null;

        s.enabled = enabled;
        s.deviceId = deviceId != null
                ? deviceId : DepinSettings.activeDeviceId(this);
        s.identityGeneration = DepinSettings.identityGeneration(this);
        // Residual F-16: with no journal open (a fresh
        // process stopped at the writer gate), the counts
        // used to fall back to this process's defaults
        // (-1 / 0) and the status file written from them
        // erased the recorded history from the display.
        // The last statistics a live journal reported are
        // persisted in the settings and reported instead;
        // the process defaults remain only for an install
        // whose journal never reported.
        // I-02: the process mirror and the persisted statistics
        // are reported only for the identity they describe.
        boolean mirrorIsThisIdentity = s.deviceId != null
                && s.deviceId.equals(CompanionStatus.deviceId);
        long knownCount = DepinSettings.lastKnownCheckpointCount(
                this, s.deviceId);
        s.checkpointCount = journal != null
                ? journal.count()
                : (knownCount >= 0
                        ? (int) Math.min(knownCount, Integer.MAX_VALUE)
                        : (mirrorIsThisIdentity
                                ? CompanionStatus.checkpointCount
                                : -1));
        if (journal != null) {
            // Member-card aggregates (vision UI): plain facts of
            // the journal's own records — the first record's time
            // and the number of distinct local days that hold a
            // record. No estimation, no cross-checking against
            // anything outside this journal.
            //
            // H-12 (final check round 3): the aggregates read
            // the store's CACHED (timestamp, peers) series
            // instead of copying every checkpoint — signatures
            // and all — on every status build. buildStatus runs
            // on the IPC thread and several times a minute from
            // the tick path; the full copy made each of those
            // an O(n) allocation under no lock the design
            // sanctions (design section 3.2: IPC status reads
            // do not take the store's lock; the cached series
            // is how that rule and fresh aggregates coexist).
            JournalStore.CheckpointSeries series =
                    journal.checkpointSeries();
            long[] cpTs = series.timestampsUnix;
            long[] cpPeers = series.observedPeers;
            if (cpTs.length > 0) {
                long first = Long.MAX_VALUE;
                java.util.Set<java.time.LocalDate> days =
                        new java.util.HashSet<>();
                java.time.ZoneId zone = java.time.ZoneId.systemDefault();
                for (long ts : cpTs) {
                    if (ts < first) {
                        first = ts;
                    }
                    days.add(java.time.Instant
                            .ofEpochSecond(ts)
                            .atZone(zone).toLocalDate());
                }
                s.firstCheckpointUnix = first;
                s.recordedDays = days.size();
            } else {
                s.firstCheckpointUnix = 0;
                s.recordedDays = 0;
            }
            // Vision UI stage 1+2 aggregates (spec section 5):
            // streak, the last-48 slot window, the last-24h
            // series and today's progress, derived from the
            // same series in the same pass. Plain facts of
            // this journal only; the results ride the status
            // channel and are never added to proof bundles or
            // telemetry.
            DepinJournalAggregates.Result agg =
                    DepinJournalAggregates.compute(cpTs, cpPeers,
                            System.currentTimeMillis() / 1000L,
                            java.time.ZoneId.systemDefault());
            s.longestStreakDays = agg.longestStreakDays;
            s.recentSlotPeers = agg.recentSlotPeers;
            s.recentSlotEndUnix = agg.recentSlotEndUnix;
            s.recentDayTs = agg.recentDayTs;
            s.recentDayPeers = agg.recentDayPeers;
            s.todayCheckpoints = agg.todayCheckpoints;
            s.todaySlotsElapsed = agg.todaySlotsElapsed;
            // Residual F-16: remember the journal's own
            // statistics for the processes that cannot
            // open it (see the checkpointCount fallback
            // above). The last checkpoint time is the
            // series' own maximum — the process mirror can
            // lag a just-appended checkpoint.
            long lastCpUnix = 0;
            for (long ts : cpTs) {
                if (ts > lastCpUnix) {
                    lastCpUnix = ts;
                }
            }
            s.lastCheckpointUnix = lastCpUnix;
            if (deviceId != null) {
                DepinSettings.recordKnownCheckpointStats(
                        this, deviceId, s.checkpointCount, lastCpUnix);
            }
        }
        if (journal == null) {
            // Residual F-16: the persisted statistics, when
            // a live journal ever reported them.
            long knownUnix = DepinSettings.lastKnownCheckpointUnix(
                    this, s.deviceId);
            s.lastCheckpointUnix = knownUnix > 0
                    ? knownUnix
                    : (mirrorIsThisIdentity
                            ? CompanionStatus.lastCheckpointUnix
                            : 0L);
        }
        s.lastBest = CompanionStatus.lastBest;
        s.holdActive = hold;
        s.selfStopReason = selfStopped ? reason : null;
        s.selfStopDetail = selfStopped
                ? DepinSettings.selfStopDetail(this) : null;
        s.lastError = CompanionStatus.lastError;
        s.commandDiscarded = commandDiscarded;
        s.attemptLogCorrupt = attemptLog != null && attemptLog.isCorrupt();
        s.attemptLogResetUnix = DepinSettings.attemptLogResetUnix(this);
        // supervisorSuppressed stays false here: suppression is
        // the main process's own bookkeeping, merged in by the
        // facade that owns the supervisor.
        s.updatedWallMs = System.currentTimeMillis();

        if (selfStopped) {
            s.state = DepinStatus.STATE_SELF_STOPPED;
        } else if (!enabled) {
            s.state = DepinStatus.STATE_OFF;
        } else if (!identityReady) {
            s.state = DepinStatus.STATE_PREPARING;
        } else if (hold) {
            s.state = DepinStatus.STATE_HELD;
        } else {
            s.state = DepinStatus.STATE_RECORDING;
        }
        return s;
    }

    /** The status of an instance that never got going: OFF, with
     *  whatever binding the settings already hold. */
    private DepinStatus offStatus() {
        DepinStatus s = new DepinStatus();
        s.state = DepinStatus.STATE_OFF;
        s.enabled = false;
        s.deviceId = DepinSettings.activeDeviceId(this);
        s.identityGeneration = DepinSettings.identityGeneration(this);
        s.updatedWallMs = System.currentTimeMillis();
        return s;
    }

    /** Write the status file atomically (tmp + fsync + rename),
     *  so a reader in the other process only ever sees a whole
     *  snapshot. Never throws: the status file is a display
     *  source, and failing to refresh it must not fail the
     *  operation it describes. */
    private void writeStatusFile(DepinStatus s) {
        File target = DepinFiles.statusFile(this);
        // Residual F-25: the tmp name is unique per call.
        // The fixed name let two writers in this process
        // (tick thread, IPC thread) tear each other's tmp
        // file, and one thread's rename could move the
        // other's half-written file into place.
        File tmp = new File(target.getParentFile(),
                target.getName() + ".tmp-"
                        + Thread.currentThread().getId()
                        + "-" + System.nanoTime());
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(Json.pretty(s.toMap())
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            }
            if (!tmp.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Throwable t) {
            DepinRuntime.recordError(this, "status_write", t);
        }
    }

    private static String describe(Throwable t) {
        return t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
    }

    // ---------------- observation tick ----------------

    /**
     * The circuit-breaker wrapper (section 3.3), round-scoped
     * since final check round 3 (H-09). The breaker is fed
     * ONLY by ticks that actually ran a round: tick() sets
     * roundAttemptSlot once it passes the already-paid gate,
     * and such a tick either completes the round (the count
     * resets — RPC failures, inconsistent or implausible
     * readings, holds and spent budgets are all handled
     * inside tick() and return normally) or escapes with a
     * failure (the count rises). Ticks that run no round —
     * the slot already recorded, the clock behind the
     * attempt log's maximum, or the slot's round already paid
     * for by an earlier run — feed it NEITHER way; before
     * H-09 their normal returns reset the count, which made
     * the breaker untrippable for exactly the failures that
     * spend a round's attempts before failing (the count
     * returned to 0 on the next tick, ~19 times per slot).
     * MAX_CONSECUTIVE_TICK_FAILURES consecutive failed ROUNDS
     * stop the observer (REASON_TICK_FAILURES).
     */
    private void tickSafely() {
        roundAttemptSlot = -1;
        roundWithheld = false;
        try {
            tick();
            if (roundAttemptSlot >= 0 && !roundWithheld) {
                tickBreaker.roundCompleted();
            }
        } catch (Throwable t) {
            CompanionStatus.lastError = "内部エラー: " + t.getMessage();
            DepinRuntime.recordError(this, "tick", t);
            if (roundAttemptSlot >= 0
                    && tickBreaker.roundFailed(roundAttemptSlot)) {
                selfStop(DepinIpc.REASON_TICK_FAILURES, describe(t));
            }
        }
    }

    /** Residual F-15: whether this process has already
     *  recorded the clock-skew note. */
    private boolean clockSkewNoted = false;

    /** Record, once per process, that the tick is skipping
     *  rounds because recorded slots are ahead of the
     *  current clock (the clock was moved back, or the
     *  recorded observations run ahead of it). Residual
     *  F-15: these skips used to be silent — recording
     *  simply stopped, with nothing anywhere saying why.
     *  The note lands in the persistent diagnostic log,
     *  the file the diagnostic summary is built from. No
     *  behaviour changes: the rounds still skip, exactly
     *  as before. */
    private void noteClockSkew(long recordedSlot, long currentSlot) {
        if (clockSkewNoted) {
            return;
        }
        clockSkewNoted = true;
        try {
            CrashLog.recordMessageAt(
                    DepinFiles.crashLogFile(this), "tick_clock_skew",
                    "recorded slot " + recordedSlot
                            + " is ahead of the current slot "
                            + currentSlot
                            + "; rounds resume when the clock"
                            + " catches up");
        } catch (Throwable ignored) {
            // Diagnostics must never take the tick down.
        }
    }

    private void tick() {
        if (!identityReady || journal == null || attemptLog == null) {
            return;
        }
        // Capture the service generation NOW; it is re-checked
        // immediately before the append, after the (comparatively
        // long) RPC round, so a stop or restart that lands
        // mid-round retires this tick quietly (M-04).
        final long gen = ServiceGeneration.PROCESS.capture();
        long now = System.currentTimeMillis() / 1000L;
        long slot = now / DepinCore.SLOT_SEC;
        DepinCore.Checkpoint last = journal.last();
        if (last != null && last.timestampUnix / DepinCore.SLOT_SEC >= slot) {
            if (last.timestampUnix / DepinCore.SLOT_SEC > slot) {
                // The recorded tail is ahead of the clock
                // (residual F-15: this skip was silent).
                noteClockSkew(
                        last.timestampUnix / DepinCore.SLOT_SEC, slot);
            }
            return; // this slot already has its checkpoint
        }

        // ---- round discipline (section 2.3) ----
        if (attemptLog.isCorrupt()) {
            // The budget record can no longer be trusted; fail
            // closed rather than observe off-budget.
            selfStop(DepinIpc.REASON_ATTEMPT_LOG, attemptLog.corruptDetail());
            return;
        }
        long maxSlot = attemptLog.maxSlot();
        if (maxSlot >= 0 && slot < maxSlot) {
            // The clock sits behind the recorded maximum: this
            // slot's round may no longer be charged at all
            // (G-11 — consume() refuses any slot behind the
            // maximum, because rows appended out of slot order
            // are what the loader calls corrupt; slots older
            // than the window have the older reason too: their
            // budget record has been pruned, so how much of it
            // was spent is unknowable). Skip the round — counted
            // as neither an error nor a failure.
            // Residual F-15: the skip is no longer silent.
            noteClockSkew(maxSlot, slot);
            return;
        }
        if (attemptLog.hasAttempts(slot)) {
            // A round for this slot was already paid for (most
            // likely by an earlier process run that died before
            // or during it). One round per slot: the slot stays
            // a gap rather than buying a second round.
            return;
        }
        // H-09: from here the tick RUNS this slot's round.
        // The breaker hears about its outcome (and only its
        // outcome) from tickSafely.
        roundAttemptSlot = slot;
        CompanionStatus.lastAttemptUnix = now;

        // One round budget covers the observation AND the peer-id
        // fetch: the peer-id call no longer runs on its own
        // unbounded path after the round's deadline has closed.
        // The gate slot is published BEFORE beginRound so every
        // attempt the round makes is charged to this slot.
        RpcClient.NodeObservation round;
        currentGateSlot = slot;
        rpc.beginRound();
        try {
            round = fetchRound();
            if (round == null) {
                return; // failure already surfaced by fetchRound
            }
            if (CompanionStatus.nodePeerId == null) {
                try {
                    CompanionStatus.nodePeerId = rpc.fetchLocalPeerId();
                } catch (RpcClient.RpcException ignored) {
                    // Informational only; retried on a later tick.
                }
            }
        } finally {
            rpc.endRound();
            currentGateSlot = -1;
        }
        DepinCore.Observation o = round.observation;

        // Plausibility against the accepted-observation baseline:
        // the loopback endpoint is unauthenticated, so absurd
        // values (finalized ahead of best, a best number that
        // jumped implausibly far, a regression) are treated as a
        // gap, never written. The baseline persists across
        // restarts (seeded from the journal tail) and persistent
        // regressions re-adopt a new baseline after three rounds
        // instead of wedging forever.
        ObservationPlausibility.Decision decision = plausibility.evaluate(o);
        if (!decision.accepted) {
            // A rejection also breaks the segment-start streak:
            // the readings that open a segment must be
            // CONSECUTIVELY plausible.
            segmentPolicy.noteRejected();
            if (last != null && o.bestBlockNumber < last.bestBlockNumber) {
                // The live view sits below the recorded tail:
                // accumulation is held, never resolved
                // destructively. Persist the hold so the notice
                // survives restarts.
                journal.setHold(o.bestBlockNumber, o.timestampUnix);
                syncMirrorFromJournal();
                writeStatusFile(buildStatus());
            }
            CompanionStatus.lastError = "観測値が異常なため見送り: " + decision.reason;
            return;
        }
        if (decision.baselineInitializing) {
            CompanionStatus.lastError =
                    "初回の観測を基準値として受理しました（比較対象の履歴がまだありません）";
        }

        if (journal.count() == 0
                && !segmentPolicy.noteAcceptedWhileSegmentEmpty()) {
            // A fresh segment records its first checkpoint only
            // after 2 consecutive plausible readings. A single
            // poisoned first response can adopt the in-memory
            // baseline, but it cannot put a checkpoint on disk
            // by itself.
            CompanionStatus.lastError =
                    "新しいセグメントの開始を確認しています"
                            + "（連続した正常な観測が複数回必要です）";
            return;
        }

        if (last != null) {
            if (o.bestBlockNumber < last.bestBlockNumber) {
                // Reachable only via a baselineReset acceptance:
                // the observation is believed and the baseline has
                // adopted it, but the bundle format requires
                // non-decreasing best across checkpoints, so
                // writing it now would poison every future export.
                // Hold the append — and hold ONLY: the recorded
                // history is never set aside over an RPC
                // disagreement, however large.
                journal.setHold(o.bestBlockNumber, o.timestampUnix);
                syncMirrorFromJournal();
                CompanionStatus.lastError =
                        "best の基準値を再採用しましたが、記録済みの best ("
                                + last.bestBlockNumber + ") に再び達するまで"
                                + "チェックポイントを保留します: " + decision.reason;
                writeStatusFile(buildStatus());
                return;
            }
            if (!SegmentPolicy.tailAdvanceAllowed(
                    last.bestBlockNumber, last.timestampUnix,
                    o.bestBlockNumber, o.timestampUnix)) {
                // Advance cap: a checkpoint may run ahead of the
                // recorded tail by at most the wall time elapsed
                // since the tail was recorded, at the chain's
                // plausible cadence. The recorded tail cannot
                // move, so a jump beyond the cap is HELD here
                // instead of being recorded.
                journal.setHold(o.bestBlockNumber, o.timestampUnix);
                syncMirrorFromJournal();
                CompanionStatus.lastError =
                        "観測された best (" + o.bestBlockNumber
                                + ") と記録済みの末尾（best "
                                + last.bestBlockNumber
                                + "）の差が上限を超えているため、"
                                + "チェックポイントを保留します";
                writeStatusFile(buildStatus());
                return;
            }
            if (o.timestampUnix <= last.timestampUnix) {
                return; // clock oddity; never write a non-increasing timestamp
            }
        }

        DepinCore.Checkpoint cp = DepinCore.makeCheckpoint(
                journal.count(), o,
                journal.nextPrevState(), journal.nextPrevCheckpointHash(),
                DepinCore.PRODUCTION_ITERATIONS, pubkey);
        if (!ServiceGeneration.PROCESS.isCurrent(gen)) {
            // A stop (and possibly a restart) landed while this
            // tick's RPC round was in flight. This tick belongs
            // to a dead generation: return quietly — the live
            // generation's own ticks observe the following slots.
            return;
        }
        try {
            // The generation is re-verified by the store, under
            // its lock, as part of the write itself (F-09) — the
            // check above is only the quiet early-out.
            journal.append(cp, gen);
        } catch (JournalStore.FreeFloorException e) {
            // Design section 3.4 (H-11): the append was
            // WITHHELD because writing would breach the
            // free-space floor. That is not a journal failure
            // and must not feed the breaker: the round ends
            // quietly with the fact on the status display, and
            // recording resumes by itself once space frees.
            roundWithheld = true;
            CompanionStatus.lastError =
                    "空き容量が不足しているため記録を保留しています"
                            + "（ノード用の空き容量の床に触れています）";
            return;
        } catch (IllegalStateException e) {
            if (!ServiceGeneration.PROCESS.isCurrent(gen)) {
                // Went stale inside the gap: retire quietly,
                // exactly as the early-out above does. Any other
                // refusal is a real journal problem and propagates
                // to tickSafely, where the breaker counts it.
                return;
            }
            throw e;
        }
        journal.clearHold();

        CompanionStatus.lastError = null;
        CompanionStatus.lastCheckpointUnix = cp.timestampUnix;
        CompanionStatus.lastBest = cp.bestBlockNumber;
        CompanionStatus.lastFinalized =
                cp.finalizedBlockNumber == null ? -1 : cp.finalizedBlockNumber;
        CompanionStatus.lastPeers = (int) cp.observedPeers;
        CompanionStatus.lastSyncing = round.syncing;
        syncMirrorFromJournal();
        writeStatusFile(buildStatus());
        updateNotification(checkpointText(cp));
    }

    /**
     * One observation round, with the section 2.3 retry rule:
     * an RpcException (unreachable node, deadline, malformed or
     * budget-refused response) earns exactly ONE re-run of the
     * whole fetch inside the same round window — the round
     * deadline and the attempt gate's slot both stay the ones
     * the caller opened. An inconsistent round is never retried:
     * the node state demonstrably moved mid-round, and asking
     * again immediately would just bind a different mixture.
     *
     * Every ultimately-failed round breaks the segment-start
     * streak (F-11): a round that produced no accepted reading
     * must not let "consecutive" bridge across it. A round whose
     * second attempt succeeds is one accepted reading — the
     * failed first attempt is part of the same round, not a
     * separate reading.
     *
     * @return the round, or null when it failed (the failure has
     *         already been surfaced in the status)
     */
    private RpcClient.NodeObservation fetchRound() {
        try {
            return rpc.fetchObservation();
        } catch (RpcClient.InconsistentObservation e) {
            segmentPolicy.noteRejected();
            CompanionStatus.lastError = "観測の整合性が取れず見送り: " + e.getMessage();
            return null;
        } catch (RpcClient.RpcException firstFailure) {
            // The single in-window retry; fall through.
        }
        try {
            return rpc.fetchObservation();
        } catch (RpcClient.InconsistentObservation e) {
            segmentPolicy.noteRejected();
            CompanionStatus.lastError = "観測の整合性が取れず見送り: " + e.getMessage();
            return null;
        } catch (RpcClient.RpcException e) {
            if (attemptLog.isCorrupt()) {
                // The failure IS the budget log (a consume that
                // could not be made durable fails the log closed):
                // stop now instead of waiting for the next tick
                // to notice.
                selfStop(DepinIpc.REASON_ATTEMPT_LOG, attemptLog.corruptDetail());
                return null;
            }
            segmentPolicy.noteRejected();
            CompanionStatus.lastError = "ノードに接続できず: " + e.getMessage();
            return null;
        }
    }

    // ---------------- notification ----------------

    private String nsTitle() {
        return notifStrings != null && notifStrings.title != null
                ? notifStrings.title : FALLBACK_TITLE;
    }

    private String nsChannelName() {
        return notifStrings != null && notifStrings.channelName != null
                ? notifStrings.channelName : FALLBACK_CHANNEL_NAME;
    }

    private String nsChannelDesc() {
        return notifStrings != null && notifStrings.channelDesc != null
                ? notifStrings.channelDesc : FALLBACK_CHANNEL_DESC;
    }

    private String nsStarting() {
        return notifStrings != null && notifStrings.starting != null
                ? notifStrings.starting : FALLBACK_STARTING;
    }

    private String nsObserving() {
        return notifStrings != null && notifStrings.observing != null
                ? notifStrings.observing : FALLBACK_OBSERVING;
    }

    private String nsCannotStart() {
        return notifStrings != null && notifStrings.cannotStart != null
                ? notifStrings.cannotStart : FALLBACK_CANNOT_START;
    }

    /** The checkpoint notification text: the caller's format
     *  string with %1$s = checkpoint time, %2$d = accumulated
     *  checkpoint count. A caller format that does not apply
     *  cleanly falls back to the built-in shape rather than
     *  breaking the tick that produced the checkpoint. */
    private String checkpointText(DepinCore.Checkpoint cp) {
        String format = notifStrings != null
                && notifStrings.checkpointFormat != null
                ? notifStrings.checkpointFormat : FALLBACK_CHECKPOINT_FORMAT;
        try {
            return String.format(Locale.US, format,
                    formatTime(cp.timestampUnix), journal.count());
        } catch (RuntimeException e) {
            return String.format(Locale.US, FALLBACK_CHECKPOINT_FORMAT,
                    formatTime(cp.timestampUnix), journal.count());
        }
    }

    private void createNotificationChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, nsChannelName(), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(nsChannelDesc());
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(nsTitle())
                .setContentText(text)
                .setOngoing(true);
        // The tap target is the app's launch intent, obtained
        // without naming any UI class (dependency direction).
        // A package with no launchable entry simply gets no
        // content intent.
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            builder.setContentIntent(PendingIntent.getActivity(
                    this, 0, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        return builder.build();
    }

    private void startForegroundNow(Notification notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private static String formatTime(long unix) {
        return new SimpleDateFormat("HH:mm:ss", Locale.US)
                .format(new Date(unix * 1000L));
    }
}
