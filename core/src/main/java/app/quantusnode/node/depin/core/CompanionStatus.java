package app.quantusnode.node.depin.core;

/**
 * Process-wide live status, written by ObservationService and read
 * by MainActivity (same process). Anything that must survive a
 * process restart lives in the JournalStore instead; this is only
 * the volatile "what is happening right now" view.
 */
public final class CompanionStatus {
    private CompanionStatus() {}

    public static volatile boolean serviceRunning = false;
    /** Unix time the user last tapped "start", until the service
     *  confirms it is running (or has failed); 0 when idle. Lets
     *  the UI show "starting" instead of looking unresponsive. */
    public static volatile long startRequestedUnix = 0;
    public static volatile String deviceId = null;
    public static volatile String nodePeerId = null;
    public static volatile long lastAttemptUnix = 0;
    public static volatile long lastCheckpointUnix = 0;
    public static volatile long lastBest = -1;
    public static volatile long lastFinalized = -1;
    public static volatile int lastPeers = -1;
    public static volatile boolean lastSyncing = false;
    public static volatile String lastError = null;
    public static volatile String identityError = null;
    /** Set when the journal itself could not be opened (B4):
     *  kept distinct from identityError so the UI names the real
     *  cause. CompanionRuntime records the failure in the
     *  persistent error log at the moment it happens. */
    public static volatile String journalError = null;
    /** 最後に確認したジャーナル件数。-1 は未確認。 */
    public static volatile int checkpointCount = -1;
    /** 保留中は記録末尾の best、保留が無ければ -1。 */
    public static volatile long holdTailBest = -1;
    /** 保留中は現在の応答の best、保留が無ければ -1。 */
    public static volatile long holdLiveBest = -1;
}
