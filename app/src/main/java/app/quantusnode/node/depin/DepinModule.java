package app.quantusnode.node.depin;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import app.quantusnode.node.depin.core.CrashLog;
import app.quantusnode.node.depin.core.Json;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The main process's facade over the DePIN observer — the ONLY
 * entry point the rest of the app touches. Everything behind it
 * (the supervisor's launch bookkeeping, the service's claim
 * protocol, the two status transports) is an internal detail of
 * the observer feature; callers ask to enable, disable, or
 * refresh, and read the latest status snapshot.
 *
 * Threading: the facade owns a single-thread executor
 * ("depin-facade") and every operation runs there, serialised,
 * so no public method blocks a UI thread on file I/O, a settings
 * commit, or an IPC round trip, and no two operations can race
 * each other into the supervisor or the binder. latestStatus()
 * is the one deliberate exception: it reads a volatile snapshot
 * and never blocks.
 *
 * Status comes from two transports that carry the same DTO:
 * while the service is bound, MSG_GET_STATUS over the Messenger;
 * otherwise the status file the service keeps current. Snapshots
 * are stored as reported; the merges only this process can make
 * (the supervisor's restart state, the user's recorded intent)
 * are applied at read time in latestStatus(). When no snapshot
 * has ever been obtained the cached value is an OFF DTO (or
 * whatever the status file held at construction) — never null.
 *
 * Binding is NOT auto-creating (flags 0): binding must never be
 * what brings the observer into existence. Only an issued launch
 * (tryLaunch + a foreground-service start) may do that; the
 * binding then attaches to the already-launched service.
 *
 * Reconcile: refreshStatus also closes the loop after a process
 * death — enablement says observation should be running, but
 * nothing is bound — by issuing a fresh launch with
 * ACTION_START, throttled and backed off by
 * {@link DepinReconcilePolicy} over the supervisor's derived
 * failure count, so a status poll can never turn into a launch
 * loop and repeated instant deaths decay to at most one retry
 * per hour instead of a permanent stop (the internal.23 latch;
 * see DepinSupervisor). refreshStatus is called by the
 * activity's UI refresh and, since internal.23, by the node
 * service's poller while the node runs: the 2026-11-10
 * incident was an observer killed outright whose recording
 * stayed stopped for eight hours because the UI path was the
 * only thing that ever reconciled. The facade also records the
 * events a later diagnosis needs — a lost service connection,
 * a reconcile (re)launch, a restart backoff taking effect —
 * into the persistent error log: before internal.23 every one
 * of those paths was silent, so a dead observer left no trace
 * of why it never came back.
 *
 * User intent (internal.24): every toggle is recorded by THIS
 * process as an intent (value + wall time) in the facade's own
 * preferences, synchronously on the calling thread, before any
 * executor work runs. latestStatus() and the reconcile merge
 * the observer's snapshot with that intent via
 * {@link DepinStatusMerge} — asymmetrically since internal.26
 * (a recorded OFF is superseded only by a later recorded ON) —
 * because the snapshot channel (the status file, written only
 * by the observer) cannot carry a user act the observer never
 * lived to process, and pre-.24 it silently overwrote one
 * within a second of every refresh. The OFF must also land
 * when there is no observer to receive the command, but NOT
 * by this process writing the observer's settings file
 * (internal.24 did; audit F-03 — a cross-process
 * SharedPreferences write can silently not happen, or roll
 * the file back to a stale map). Since internal.26 the
 * observer reads the intent record itself, fresh from disk,
 * at every start that could resume recording, and lands the
 * OFF in its own process (DepinUserIntent).
 */
public final class DepinModule {

    private static DepinModule instance;

    private final Context appContext;
    private final DepinSupervisor supervisor;
    private final DepinReconcilePolicy reconcilePolicy =
            new DepinReconcilePolicy();
    private final ExecutorService executor;
    private final Messenger replyMessenger;

    private volatile DepinStatus cached;
    private volatile Messenger serviceMessenger;
    private volatile BundleCallback pendingBundleCallback;
    private volatile boolean alive = false;
    private volatile boolean bindRequested = false;

    // The user's recorded toggle intent (internal.24): held in
    // volatiles for latestStatus() and persisted to the
    // facade's own preferences file by the executor. See the
    // class doc and DepinStatusMerge for why this exists.
    // The record's names live in DepinUserIntent (internal.26):
    // the :depin process reads the same record as a file, and
    // the wire format has exactly one source.
    private static final String INTENT_PREFS =
            DepinUserIntent.PREFS_NAME;
    private static final String KEY_INTENT_SET =
            DepinUserIntent.KEY_SET;
    private static final String KEY_INTENT_ENABLED =
            DepinUserIntent.KEY_ENABLED;
    private static final String KEY_INTENT_WALL_MS =
            DepinUserIntent.KEY_WALL_MS;
    private volatile boolean hasUserIntent = false;
    private volatile boolean userIntentEnabled = false;
    private volatile long userIntentWallMs = 0;
    /** The supervisor's last derived restart state; merged
     *  into latestStatus() so the backoff is presented
     *  without file I/O on the read path. */
    private volatile DepinSupervisor.RestartState restartStateCache;

    public static synchronized DepinModule get(Context context) {
        if (instance == null) {
            instance = new DepinModule(context);
        }
        return instance;
    }

    private DepinModule(Context context) {
        this.appContext = context.getApplicationContext();
        this.supervisor = new DepinSupervisor(appContext);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "depin-facade");
            t.setDaemon(true);
            return t;
        });
        // The initial snapshot is the status file's, read once
        // here; a file that is absent or unreadable simply means
        // OFF — the observer has never reported anything.
        DepinStatus initial = readStatusFile();
        this.cached = initial != null ? initial : new DepinStatus();
        // The persisted user intent, if a toggle ever ran in
        // this install (internal.24+): latestStatus() merges it
        // from the first read, before any refresh.
        try {
            SharedPreferences ip = appContext.getSharedPreferences(
                    INTENT_PREFS, Context.MODE_PRIVATE);
            hasUserIntent = ip.getBoolean(KEY_INTENT_SET, false);
            userIntentEnabled = ip.getBoolean(KEY_INTENT_ENABLED, false);
            userIntentWallMs = ip.getLong(KEY_INTENT_WALL_MS, 0L);
        } catch (Throwable ignored) {
            hasUserIntent = false;
        }

        // Replies arrive on their own thread: handling one is a
        // DTO parse and a volatile write, never UI work.
        HandlerThread replyThread = new HandlerThread("depin-facade-reply");
        replyThread.start();
        Handler replyHandler = new Handler(replyThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                if (msg.what == DepinIpc.MSG_STATUS && msg.getData() != null) {
                    // Stored as reported; the user-intent merge
                    // and the restart state are applied at read
                    // time in latestStatus(), so no supervisor
                    // file I/O happens on this reply thread.
                    cached = DepinStatus.fromBundle(msg.getData());
                } else if (msg.what == DepinIpc.MSG_BUNDLE_READY
                        && msg.getData() != null) {
                    BundleCallback cb = pendingBundleCallback;
                    pendingBundleCallback = null;
                    if (cb != null) {
                        android.os.Bundle d = msg.getData();
                        cb.onBundleResult(
                                d.getInt(DepinIpc.KEY_BUNDLE_RESULT,
                                        DepinIpc.BUNDLE_RESULT_ERROR),
                                d.getString(DepinIpc.KEY_BUNDLE_NAME),
                                d.getString(DepinIpc.KEY_BUNDLE_ERROR));
                    }
                } else {
                    super.handleMessage(msg);
                }
            }
        };
        this.replyMessenger = new Messenger(replyHandler);
    }

    // ---------------- public surface ----------------

    /**
     * Enable the observer for the given identity binding (a null
     * device id / generation 0 carries no expectation, for the
     * first enable of a fresh identity). An explicit user enable
     * IS the reset of the restart backoff (manualReenable,
     * unconditionally — clearing an empty window is a no-op):
     * the backoff exists to pace AUTOMATIC relaunches, never to
     * overrule the user. The intent is recorded synchronously,
     * before the executor runs, so the presentation honours the
     * tap from this instant.
     *
     * Alive -> the command is re-sent to the running service
     * (no new launch, no new claim). Not alive -> a launch is
     * issued and the service is started as a foreground service
     * carrying the claim id.
     */
    public void enable(String expectedDeviceId, long expectedGeneration,
                       DepinIpc.NotifStrings strings) {
        recordUserIntent(true);
        executor.execute(() -> {
            try {
                supervisor.manualReenable();
                refreshRestartStateCache();
                Intent intent = serviceIntent(DepinIpc.ACTION_ENABLE);
                intent.putExtra(DepinIpc.EXTRA_EXPECTED_DEVICE_ID,
                        expectedDeviceId);
                intent.putExtra(DepinIpc.EXTRA_EXPECTED_GENERATION,
                        expectedGeneration);
                if (strings != null) {
                    intent.putExtra(DepinIpc.EXTRA_NOTIF, strings.toBundle());
                }
                if (alive) {
                    appContext.startService(intent);
                } else {
                    String launchId = supervisor.tryLaunch(false);
                    if (launchId == null) {
                        // The launch could not even be recorded:
                        // start nothing, and let the cached
                        // restart state say where things stand —
                        // the next refresh reconciles.
                        refreshRestartStateCache();
                        return;
                    }
                    intent.putExtra(DepinIpc.EXTRA_LAUNCH_ID, launchId);
                    appContext.startForegroundService(intent);
                }
                ensureBound();
                refreshCachedFromFile();
            } catch (Throwable ignored) {
                // The facade never throws into its caller: a
                // failed start leaves the cached status as the
                // record, and the next refresh reconciles.
            }
        });
    }

    /**
     * Request the design section 2.3 repair of a corrupt RPC
     * attempt log (G-11): the settings screen calls this after
     * the user's explicit confirmation, and then enable() —
     * the observer's next initialisation consumes the request
     * in the :depin process, sets the corrupt log aside there
     * (RpcAttemptLog.resetCorrupt), records the fact in the
     * settings itself, and resumes. This writes ONLY the bare
     * request flag: no settings content crosses processes
     * (the DepinSettings writers stay :depin-only, G-01), and
     * the request authorises exactly one repair — the observer
     * deletes it before acting, so it cannot reset a later,
     * different corruption.
     */
    public void requestAttemptLogReset() {
        try {
            File f = DepinFiles.attemptResetRequestFile(appContext);
            try (java.io.FileOutputStream out =
                         new java.io.FileOutputStream(f)) {
                out.write(("reset requested at "
                        + System.currentTimeMillis() + "\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            }
        } catch (Throwable ignored) {
            // Best effort like every facade write: if the flag
            // cannot be written, the observer's next start
            // finds no request and self-stops on the corruption
            // as before — the user can confirm again.
        }
    }

    /**
     * Disable the observer. The OFF is recorded as the user's
     * intent (synchronously, in recordUserIntent) and delivered
     * as ACTION_DISABLE when the service is alive. This process
     * does NOT write the observer's settings file — internal.24
     * did (an unconditional DepinSettings.setDisabled from
     * here), and the audit (F-03) showed why that cannot be
     * made safe: SharedPreferences serves each process its own
     * in-memory map, so a main-process commit whose value
     * already matches the stale map writes NOTHING (off → on →
     * off with a dead observer left the disk flag true), and a
     * commit that does write overwrites the file with the whole
     * stale map, rolling back bindings and self-stop records
     * the observer wrote since. Since internal.26 the landing
     * belongs to the observer: every start that could resume
     * recording reads the intent record fresh from disk
     * (DepinUserIntent) and lands the OFF in its own process —
     * the settings file's single-writer rule is restored, and
     * there is no start path left that honours a flag the user
     * has revoked. The presentation and the reconcile follow
     * the recorded intent asymmetrically (DepinStatusMerge,
     * F-04): an OFF is never overridden by a snapshot.
     *
     * The cached snapshot's enabled flag is patched with a
     * fresh stamp as well, so even the raw cache no longer
     * claims enablement the user has revoked.
     */
    public void disable(String expectedDeviceId, long expectedGeneration) {
        recordUserIntent(false);
        executor.execute(() -> {
            try {
                if (alive) {
                    Intent intent = serviceIntent(DepinIpc.ACTION_DISABLE);
                    intent.putExtra(DepinIpc.EXTRA_EXPECTED_DEVICE_ID,
                            expectedDeviceId);
                    intent.putExtra(DepinIpc.EXTRA_EXPECTED_GENERATION,
                            expectedGeneration);
                    appContext.startService(intent);
                }
                DepinStatus s = cached.copy();
                s.enabled = false;
                s.updatedWallMs = System.currentTimeMillis();
                cached = s;
            } catch (Throwable ignored) {
                // See enable(): the facade never throws.
            }
        });
    }

    /**
     * Refresh the cached status and reconcile. Alive -> ask the
     * service over IPC (the reply lands asynchronously in the
     * reply handler and replaces the cache). Not alive -> read
     * the status file. Then the supervisor's restart state is
     * derived (and cached for latestStatus), and the reconcile
     * decision ({@link DepinReconcilePolicy}) runs over the
     * MERGED enablement — the snapshot plus the user's recorded
     * intent, never this process's possibly-stale copy of the
     * settings file: enabled but nothing bound -> issue a
     * launch and start the service with ACTION_START so it can
     * resume under its existing authorisation; backed off ->
     * log the new failure count and the next retry time, once
     * per count, instead of silently leaving a stale snapshot
     * (or a dead end) in place.
     */
    public void refreshStatus() {
        executor.execute(() -> {
            try {
                if (alive && serviceMessenger != null) {
                    requestStatus();
                } else {
                    refreshCachedFromFile();
                }
                DepinSupervisor.RestartState rs =
                        supervisor.restartState(alive);
                restartStateCache = rs;
                DepinReconcilePolicy.Decision decision =
                        reconcilePolicy.decide(
                                mergedEnabled(cached),
                                alive,
                                rs.consecutiveFailures,
                                rs.nextLaunchAllowedWallMs,
                                System.currentTimeMillis());
                if (decision
                        == DepinReconcilePolicy.Decision.LAUNCH) {
                    String launchId = supervisor.tryLaunch(alive);
                    if (launchId != null) {
                        Intent intent =
                                serviceIntent(DepinIpc.ACTION_START);
                        intent.putExtra(DepinIpc.EXTRA_LAUNCH_ID, launchId);
                        appContext.startForegroundService(intent);
                        ensureBound();
                        logFacadeEvent("observer_relaunch",
                                "reconcile issued a launch for the"
                                        + " enabled-but-not-running"
                                        + " observer");
                    } else {
                        // The backoff boundary moved between
                        // the decision and the issue (or the
                        // launch could not be recorded): refresh
                        // the cached restart state; the next
                        // refresh decides again.
                        refreshRestartStateCache();
                    }
                } else if (decision == DepinReconcilePolicy
                        .Decision.BACKOFF_NOTICE) {
                    logFacadeEvent("observer_restart_backoff",
                            "observer restart has failed "
                                    + rs.consecutiveFailures
                                    + " time(s) in a row; next"
                                    + " automatic retry at wall"
                                    + " time "
                                    + rs.nextLaunchAllowedWallMs);
                }
            } catch (Throwable ignored) {
                // See enable(): the facade never throws.
            }
        });
    }

    /**
     * The latest snapshot, presented consistently with what
     * this process actually knows. Two merges are applied at
     * this single read point, so every reader — the settings
     * screen, the member card, the home screen — sees the same
     * status (the bundle-export gate never read the snapshot;
     * it asks the live binder, and still does):
     *
     * 1. Liveness (internal.23): the snapshot's LIVE states are
     *    presented only while the observer service is bound or
     *    a binding is outstanding — the same liveness the
     *    export gate applies — and degrade to UNAVAILABLE
     *    otherwise (see {@link DepinStatus#presentedState}).
     *    Before this rule the settings screen presented a
     *    killed observer's last file snapshot as "recording"
     *    indefinitely, while the export button, reading
     *    liveness, refused: the two halves of one screen
     *    disagreed for eight hours.
     * 2. User intent (internal.24, asymmetric since
     *    internal.26): the snapshot is merged with the user's
     *    recorded toggle intent via {@link DepinStatusMerge}
     *    — a recorded OFF presents as OFF over any snapshot,
     *    even a fresher one (pre-.24 the file refresh
     *    re-asserted a dead observer's last "recording" write
     *    and the switch turned itself back on; the .24
     *    fresher-fact rule still let a fresh enabled=true
     *    write that had never seen the intent resurrect it,
     *    F-04), and the restart backoff derived by the
     *    supervisor is merged in so the settings screen can
     *    state when the next retry happens.
     *
     * Never null, never blocks, never does file I/O.
     */
    public DepinStatus latestStatus() {
        DepinStatus s = cached;
        if (s == null) {
            return new DepinStatus();
        }
        boolean merged = mergedEnabled(s);
        int presented = DepinStatusMerge.presentedState(
                s, alive, bindRequested, merged, userIntentOff());
        DepinSupervisor.RestartState rs = restartStateCache;
        int failures = rs != null
                ? rs.consecutiveFailures : s.restartFailures;
        long next = rs != null
                ? rs.nextLaunchAllowedWallMs : s.restartNextWallMs;
        boolean backedOff = failures > 0
                && next > System.currentTimeMillis();
        if (presented == s.state && merged == s.enabled
                && failures == s.restartFailures
                && next == s.restartNextWallMs
                && backedOff == s.supervisorSuppressed) {
            return s;
        }
        DepinStatus c = s.copy();
        c.state = presented;
        c.enabled = merged;
        c.restartFailures = failures;
        c.restartNextWallMs = next;
        c.supervisorSuppressed = backedOff;
        return c;
    }

    // ---------------- user intent ----------------

    /** Record a user toggle: the volatiles move synchronously
     *  (the presentation honours the tap from this instant,
     *  before any executor work), and the durable copy is
     *  committed by the executor ahead of the enable/disable
     *  operation this call precedes. */
    private void recordUserIntent(boolean enabled) {
        hasUserIntent = true;
        userIntentEnabled = enabled;
        userIntentWallMs = System.currentTimeMillis();
        final boolean en = enabled;
        final long wall = userIntentWallMs;
        executor.execute(() -> {
            try {
                appContext.getSharedPreferences(
                                INTENT_PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_INTENT_SET, true)
                        .putBoolean(KEY_INTENT_ENABLED, en)
                        .putLong(KEY_INTENT_WALL_MS, wall)
                        .commit();
            } catch (Throwable ignored) {
                // The volatiles still carry the intent for
                // this process's lifetime.
            }
        });
    }

    /** The enablement a snapshot merges to under the recorded
     *  intent (DepinStatusMerge). */
    private boolean mergedEnabled(DepinStatus snapshot) {
        if (snapshot == null) {
            return hasUserIntent && userIntentEnabled;
        }
        return DepinStatusMerge.enabled(
                snapshot.enabled, snapshot.updatedWallMs,
                hasUserIntent, userIntentEnabled, userIntentWallMs);
    }

    /** Whether the recorded intent is the user's OFF. Under
     *  the asymmetric merge (internal.26, F-04) that fact —
     *  not the intent's freshness — is what makes a
     *  live-looking snapshot present as OFF: freshness never
     *  proved the observer saw the intent. */
    private boolean userIntentOff() {
        return hasUserIntent && !userIntentEnabled;
    }

    /** Refresh the cached restart state from the supervisor.
     *  Executor-thread only (it evaluates the window file). */
    private void refreshRestartStateCache() {
        try {
            restartStateCache = supervisor.restartState(alive);
        } catch (Throwable ignored) {
            // The previous cache (or the snapshot's own fields)
            // carries latestStatus() meanwhile.
        }
    }

    /** Record a facade-level event in the persistent error
     *  log (the same file the observer records into), so the
     *  observer's deaths and this process's resume attempts
     *  are diagnosable after the fact. Never throws. */
    private void logFacadeEvent(String kind, String message) {
        try {
            CrashLog.recordMessageAt(
                    DepinFiles.crashLogFile(appContext), kind, message);
        } catch (Throwable ignored) {
            // Diagnostics must never take the facade down.
        }
    }

    // ---------------- bundle export ----------------

    /** Facade-level outcome when no live observer service can
     *  build a bundle (the journal and the seed live in the
     *  :depin process; the main process never builds one). */
    public static final int BUNDLE_NOT_RUNNING = 3;

    /** Result of an exportBundle request. Invoked on a facade
     *  background thread; result is a DepinIpc.BUNDLE_RESULT_*
     *  value, or BUNDLE_NOT_RUNNING. name is the staged file's
     *  suggested destination name (BUNDLE_RESULT_OK only);
     *  error describes a BUNDLE_RESULT_ERROR. */
    public interface BundleCallback {
        void onBundleResult(int result, String name, String error);
    }

    /**
     * Ask the observer service to build the current proof
     * bundle and stage it at DepinFiles.pendingBundleFile
     * (build step 5; mirrors the companion's file export). Only
     * one export may be in flight (audit M-4): a request made
     * while another is pending is refused immediately with
     * BUNDLE_RESULT_ERROR instead of superseding it — the
     * former behaviour silently dropped the first caller's
     * callback — and if the service connection dies with an
     * export pending, the pending callback is failed with
     * BUNDLE_NOT_RUNNING rather than never firing.
     */
    public void exportBundle(final BundleCallback callback) {
        executor.execute(() -> {
            if (pendingBundleCallback != null) {
                callback.onBundleResult(DepinIpc.BUNDLE_RESULT_ERROR,
                        null, "a bundle export is already in progress");
                return;
            }
            Messenger svc = serviceMessenger;
            if (!alive || svc == null) {
                callback.onBundleResult(BUNDLE_NOT_RUNNING, null, null);
                return;
            }
            pendingBundleCallback = callback;
            Message msg = Message.obtain(
                    null, DepinIpc.MSG_EXPORT_BUNDLE);
            msg.replyTo = replyMessenger;
            try {
                svc.send(msg);
            } catch (RemoteException e) {
                // Dead binder: same conclusion as requestStatus.
                serviceMessenger = null;
                alive = false;
                bindRequested = false;
                if (pendingBundleCallback == callback) {
                    pendingBundleCallback = null;
                }
                callback.onBundleResult(BUNDLE_NOT_RUNNING, null, null);
            }
        });
    }

    /** Fail a pending export callback, if any (the service
     *  connection is gone, so its reply can never arrive). Runs
     *  on the facade executor, like every pending-callback
     *  transition. */
    private void failPendingBundleCallback() {
        executor.execute(() -> {
            BundleCallback cb = pendingBundleCallback;
            pendingBundleCallback = null;
            if (cb != null) {
                cb.onBundleResult(BUNDLE_NOT_RUNNING, null, null);
            }
        });
    }

    // ---------------- binding ----------------

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            serviceMessenger = new Messenger(binder);
            alive = true;
            // The latest launch demonstrably produced a live
            // service: tell the supervisor (on the executor —
            // an evaluation does file I/O, this is a binder
            // thread), so the launch is marked seen alive even
            // before the next status refresh derives the
            // restart state.
            executor.execute(() -> {
                try {
                    supervisor.serviceAliveObserved();
                    refreshRestartStateCache();
                } catch (Throwable ignored) {
                    // The next refreshStatus derives it anyway.
                }
            });
            // Ask at once: a fresh binding's first useful act is
            // replacing the file-derived cache with the service's
            // own live snapshot.
            requestStatus();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceMessenger = null;
            alive = false;
            bindRequested = false;
            // Internal.23: the observer's death used to leave
            // no record anywhere — the status file simply went
            // stale. A disconnect also follows an orderly stop
            // (disable, self-stop), so the record states the
            // bare fact; whether a resume should have followed
            // is what the reconcile records are for.
            logFacadeEvent("observer_disconnected",
                    "observer service connection closed");
            // M-4: an export pending on this connection can
            // never be answered now; fail its callback instead
            // of leaving the caller waiting forever.
            failPendingBundleCallback();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            serviceMessenger = null;
            alive = false;
            bindRequested = false;
            logFacadeEvent("observer_disconnected",
                    "observer service binding died");
        }

        @Override
        public void onNullBinding(ComponentName name) {
            serviceMessenger = null;
            alive = false;
            bindRequested = false;
        }
    };

    /** Bind to the (already launched) service. Flags 0: NO
     *  auto-create — binding attaches to a running service or
     *  fails, it never starts one. */
    private void ensureBound() {
        if (alive || bindRequested) {
            return;
        }
        Intent intent = new Intent(appContext, DepinObserverService.class);
        try {
            if (appContext.bindService(intent, connection, 0)) {
                bindRequested = true;
            }
        } catch (Throwable ignored) {
            // Binding is retried by the next facade operation;
            // the status file keeps the cache honest meanwhile.
        }
    }

    private void requestStatus() {
        Messenger m = serviceMessenger;
        if (m == null) {
            return;
        }
        Message msg = Message.obtain(null, DepinIpc.MSG_GET_STATUS);
        msg.replyTo = replyMessenger;
        try {
            m.send(msg);
        } catch (RemoteException e) {
            // The binder is dead even though no disconnect has
            // been delivered yet: believe the send, not the flag.
            serviceMessenger = null;
            alive = false;
            bindRequested = false;
        }
    }

    // ---------------- status file ----------------

    /** Replace the cache from the status file, as reported.
     *  The user-intent merge and the supervisor's restart
     *  state are applied at read time in latestStatus(), not
     *  baked into the cache. A file that cannot be read leaves
     *  the cache untouched: the last known snapshot is better
     *  evidence than none. */
    private void refreshCachedFromFile() {
        DepinStatus s = readStatusFile();
        if (s != null) {
            cached = s;
        }
    }

    /** Read and parse the status file, or null when it is absent
     *  or does not parse (a damaged status file degrades the
     *  display, never the caller). */
    private DepinStatus readStatusFile() {
        try {
            File f = DepinFiles.statusFile(appContext);
            if (!f.exists()) {
                return null;
            }
            String text;
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[(int) Math.min(f.length(), 256L * 1024)];
                int off = 0;
                int n;
                while (off < buf.length
                        && (n = in.read(buf, off, buf.length - off)) > 0) {
                    off += n;
                }
                text = new String(buf, 0, off, StandardCharsets.UTF_8);
            }
            Map<String, Object> m = Json.parseObject(text);
            return DepinStatus.fromMap(m);
        } catch (Throwable t) {
            return null;
        }
    }

    private Intent serviceIntent(String action) {
        return new Intent(appContext, DepinObserverService.class)
                .setAction(action);
    }
}
