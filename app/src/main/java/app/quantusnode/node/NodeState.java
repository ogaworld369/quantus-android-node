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

    private static final NodeState INSTANCE = new NodeState();

    public static NodeState get() {
        return INSTANCE;
    }

    public volatile Status status = Status.STOPPED;
    public volatile boolean serviceRunning = false;
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
     * {@link #lines} holds the tail (at most 30) of the lines this
     * attempt's process itself printed — never the app's own log
     * lines — and {@link #totalLines} counts all of them, so an
     * empty {@code lines} genuinely means "the process printed
     * nothing" and the UI can say so.
     */
    public static final class Attempt {
        /** True when launched through /system/bin/linker64. */
        public final boolean viaLinker;
        /** Wall time from launch to exit (or to the launch failure). */
        public final long livedMs;
        /** Exit code, or null when the process never started. */
        public final Integer exitCode;
        /** Last lines printed by this attempt's process (at most 30). */
        public final List<String> lines;
        /** Total lines this attempt's process printed. */
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
