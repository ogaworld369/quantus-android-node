package app.quantusnode.node;

import android.content.Context;

import java.util.Collections;
import java.util.List;

/**
 * In-process snapshot of the node's state, written by {@link NodeService}
 * (RPC poller / process watcher) and read by {@link MainActivity}.
 *
 * This is deliberately just a volatile snapshot holder: no IPC, no files.
 * If the process dies, the state dies with it — which is the truth.
 * The durable "track" record lives separately in {@link TrackStore}.
 */
public final class NodeState {

    public enum Status {
        /** No node process. */
        STOPPED,
        /** Process is alive but local RPC is not answering yet. */
        STARTING,
        /** RPC answers and the chain is still syncing. */
        SYNCING,
        /** RPC answers and the node is at the chain head. */
        SYNCED
    }

    /**
     * How the current stopped episode ended, as classified by the
     * service (versionCode 26). Display data only: the UI derives its
     * "failed" presentation from this — never from a leftover exit
     * code — and nothing in the node's lifecycle reads it back. The
     * service resets it to NONE when a session is established and the
     * path that settles the session records its kind (first writer
     * wins: the settler is whoever ends the session first).
     */
    public enum StopKind {
        /** No classification (no session yet, or still running). */
        NONE,
        /** Both launch attempts failed; the node never got going. */
        START_FAILED,
        /** The node was running and exited without a stop request. */
        UNEXPECTED,
        /** The system's foreground-service time limit stopped it. */
        TIME_LIMIT,
        /** The user asked for the stop. */
        USER,
        /** The node binary was not found at launch time. */
        BINARY_MISSING
    }

    private static final NodeState INSTANCE = new NodeState();

    public static NodeState get() {
        return INSTANCE;
    }

    public volatile Status status = Status.STOPPED;
    public volatile boolean serviceRunning = false;
    /** Classification of the current stopped episode; see {@link StopKind}. */
    public volatile StopKind lastStopKind = StopKind.NONE;
    /**
     * How the session before the current one ended: a snapshot of
     * {@link #lastStopKind} / {@link #lastExitCode} taken when the
     * current session was established, before those live fields
     * were reset for it (versionCode 28). Display data only, read
     * solely by the diagnostic summary — the live fields keep
     * their per-episode reset and first-writer semantics, and the
     * UI's stopped presentation is derived from them alone,
     * exactly as before.
     */
    public volatile StopKind prevStopKind = StopKind.NONE;
    /** Exit code paired with {@link #prevStopKind}. */
    public volatile Integer prevExitCode = null;
    /**
     * elapsedRealtime at which the service accepted the current
     * session's start (session establishment), -1 while no session
     * has been accepted. The UI's "current uptime" counts from here
     * (redesign spec H). Display only: the cumulative uptime accrual
     * keeps its own anchor at the accepted spawn (finding F-05) —
     * the two are deliberately different values.
     */
    public volatile long sessionAcceptedElapsedMs = -1;
    /**
     * Best block height at the most recent observation: the first
     * poll that saw the best height increase while the node was
     * already SYNCED (versionCode 26). -1 until the first such
     * observation of the session. The home screen's ripple and its
     * "last observed" line key off this pair, which the service —
     * not the UI — maintains, so an activity rebuild can neither
     * fabricate nor lose an observation.
     */
    public volatile long lastObservedBestBlock = -1;
    /** elapsedRealtime of the observation {@link #lastObservedBestBlock} names. */
    public volatile long lastObservedElapsedMs = -1;
    /**
     * Wall-clock time (System.currentTimeMillis) of the most recent
     * successful local-RPC response, -1 while no call has succeeded
     * (versionCode 28). The poller stamps the moment a response
     * actually arrives and applies that stamp inside its
     * generation-checked batch, so a call that times out never
     * freshens it. Display data only — the diagnostic summary's
     * "last RPC response" line reads it as the state's freshness;
     * nothing in the lifecycle judges from it.
     */
    public volatile long lastRpcOkWallMs = -1;
    /** Best (locally synced) block number, -1 while unknown. */
    public volatile long bestBlock = -1;
    /** Highest block seen on the network, -1 while unknown. */
    public volatile long highestBlock = -1;
    /** Connected peers, -1 while unknown. */
    public volatile int peers = -1;
    /**
     * Telemetry opt-in state the current node process was launched
     * with; null until it has actually been launched. Lets the UI tell
     * "settings changed, restart needed" apart from "settings live".
     */
    public volatile Boolean launchedTelemetry = null;
    /** --name value the current node process was launched with; null until launch. */
    public volatile String launchedNodeName = null;
    /** Exit code of the last node process run, null if it never ran/exited. */
    public volatile Integer lastExitCode = null;
    /** Last human-readable note (startup error etc.), may be null. */
    public volatile String lastNote = null;
    /**
     * Per-attempt records of the most recent session (oldest first,
     * at most 2: the direct launch and the linker64 fallback). Each
     * record keeps what THAT attempt's process printed, so a failed
     * first attempt is no longer hidden behind the retry (P-6 showed
     * only the file tail of the last attempt, in which the [app]
     * lines crowded out the node's own error). Empty until the first
     * attempt of a session finishes; replaced wholesale by the
     * service under its lifecycle lock.
     */
    public volatile List<Attempt> lastAttempts = Collections.emptyList();

    /**
     * One node-process launch attempt within a session. Immutable;
     * {@link #lines} holds the tail (at most 30) of the lines
     * captured for this attempt: the process's own output, plus at
     * most two notes the app itself deliberately adds as evidence,
     * both prefixed "[app]" so they cannot be mistaken for node
     * output — the pid-record-failure note (versionCode 17,
     * finding 4) and the start-error note (versionCode 17 safety
     * net, for a launch() failure that is not an IOException).
     * {@link #totalLines} counts every captured line, notes
     * included; an empty {@code lines} therefore genuinely means
     * nothing was captured at all — the process printed nothing and
     * neither note applied — and the UI can say so.
     */
    public static final class Attempt {
        /** True when launched through /system/bin/linker64. */
        public final boolean viaLinker;
        /** Wall time from launch to exit (or to the launch failure). */
        public final long livedMs;
        /** Exit code, or null when the process never started. */
        public final Integer exitCode;
        /** Last lines captured for this attempt (at most 30); see the class doc. */
        public final List<String> lines;
        /** Total lines captured for this attempt, "[app]" notes included. */
        public final int totalLines;

        public Attempt(boolean viaLinker, long livedMs, Integer exitCode,
                       List<String> lines, int totalLines) {
            this.viaLinker = viaLinker;
            this.livedMs = livedMs;
            this.exitCode = exitCode;
            this.lines = Collections.unmodifiableList(lines);
            this.totalLines = totalLines;
        }
    }

    private NodeState() {
    }

    /** Status label for the dashboard / notification, in the UI language. */
    public static String label(Context ctx, Status s) {
        switch (s) {
            case STARTING:
                return I18n.get(ctx, StringsTable.STATUS_STARTING);
            case SYNCING:
                return I18n.get(ctx, StringsTable.STATUS_SYNCING);
            case SYNCED:
                return I18n.get(ctx, StringsTable.STATUS_SYNCED);
            case STOPPED:
            default:
                return I18n.get(ctx, StringsTable.STATUS_STOPPED);
        }
    }
}
