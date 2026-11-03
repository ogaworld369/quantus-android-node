package app.quantusnode.node;

import android.content.Context;

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
