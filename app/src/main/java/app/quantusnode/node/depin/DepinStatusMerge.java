package app.quantusnode.node.depin;

/**
 * How the main-process facade merges the observer's status
 * snapshot with the user's recorded toggle intent — pure,
 * no I/O. This is the fix for the second half of the
 * 2026-11-10 internal.23 device failure.
 *
 * What went wrong: the settings switch is rendered from the
 * facade's cached snapshot, and every status refresh
 * refilled that cache from the status FILE — whose only
 * writer is the observer. With the observer dead, the file
 * kept its last write (enabled=true, "recording", stamped
 * hours earlier). DepinModule.disable() did persist
 * enabled=false in the settings file and patched its cache,
 * but the very next refresh (the ~1 s UI tick) overwrote the
 * cache from the stale file and the switch visibly turned
 * itself back ON. The prescribed escape from the
 * supervisor's suppression note — "turn it off and back
 * on" — was therefore unreachable: OFF never landed in
 * anything the UI or the reconcile read.
 *
 * The rule here: the facade records every user toggle as an
 * INTENT (value + wall time) in its own storage, in the
 * process that owns the toggle.
 *
 * Internal.26 (audit finding F-04) made the rule
 * ASYMMETRIC — grants are the user's alone:
 *
 *  - A recorded OFF wins over the snapshot ALWAYS, however
 *    fresh the snapshot is. Snapshot freshness never proved
 *    the observer saw the intent: the field case was a
 *    claim-refusal UNAVAILABLE write, stamped with the
 *    current time and carrying enabled=true read from the
 *    disk flag, produced by an observer instance that had
 *    never processed the disable — and the old
 *    fresher-fact-wins rule let it override the user's OFF
 *    in the presentation AND in the reconcile, resurrecting
 *    recording. An OFF intent is superseded only by a
 *    later ON intent, which replaces the record itself.
 *  - A recorded ON still yields to a fresher snapshot:
 *    that is how the service's own later report (it
 *    processed the enable and holds, or stopped itself —
 *    an enabled=false report) supersedes an older intent,
 *    so the merge never resurrects a state the service has
 *    since moved past. Ties go to the intent (a snapshot
 *    stamped in the same millisecond as the user's tap has
 *    not seen the tap).
 *
 * The observer side of F-04 is in DepinObserverService:
 * every start that could resume recording reads the intent
 * record fresh from disk (DepinUserIntent) and lands an
 * OFF itself, so the entity — not only the presentation —
 * stops. This merge is applied at the single read point
 * (DepinModule.latestStatus) and at the reconcile, so the
 * settings screen, the member card and the reconcile all
 * act on the same enablement; the export gate never read
 * the snapshot's enabled flag (it asks the live binder) and
 * is unaffected.
 */
public final class DepinStatusMerge {

    private DepinStatusMerge() {
    }

    /** Whether the recorded intent is at least as fresh as
     *  the snapshot (ties go to the intent). */
    public static boolean intentIsFreshest(boolean hasIntent,
                                           long intentWallMs,
                                           long snapshotUpdatedWallMs) {
        return hasIntent && intentWallMs >= snapshotUpdatedWallMs;
    }

    /** The enabled value to present and reconcile against.
     *  Asymmetric (internal.26, F-04): a recorded OFF intent
     *  decides unconditionally — a snapshot can cancel an
     *  enablement (enabled=false reports still win over an
     *  older ON intent by freshness) but can never grant
     *  one over the user's recorded OFF. */
    public static boolean enabled(boolean snapshotEnabled,
                                  long snapshotUpdatedWallMs,
                                  boolean hasIntent,
                                  boolean intentEnabled,
                                  long intentWallMs) {
        if (hasIntent && !intentEnabled) {
            return false;
        }
        if (intentIsFreshest(hasIntent, intentWallMs,
                snapshotUpdatedWallMs)) {
            return intentEnabled;
        }
        return snapshotEnabled;
    }

    /**
     * The state to present, given the merged enablement.
     * When the recorded intent is the user's OFF, a snapshot
     * still describing a live state (preparing / recording /
     * held — e.g. a dead observer's last write), or no state
     * at all (unavailable), is presented as OFF: the user
     * turned it off, and that is why it is not running.
     * Settled states that carry their own information (off,
     * self-stopped, error) pass through, and in every other
     * case the snapshot's own liveness degradation applies
     * unchanged.
     */
    public static int presentedState(DepinStatus snapshot,
                                     boolean serviceAlive,
                                     boolean bindOutstanding,
                                     boolean mergedEnabled,
                                     boolean userIntentOff) {
        if (!mergedEnabled && userIntentOff) {
            switch (snapshot.state) {
                case DepinStatus.STATE_PREPARING:
                case DepinStatus.STATE_RECORDING:
                case DepinStatus.STATE_HELD:
                case DepinStatus.STATE_UNAVAILABLE:
                    return DepinStatus.STATE_OFF;
                default:
                    break;
            }
        }
        return snapshot.presentedState(serviceAlive, bindOutstanding);
    }

    /** Residual F-11: how long a bind request may stay
     *  unanswered before it stops counting as outstanding.
     *  A bind normally completes in well under a second —
     *  30 s is far beyond any healthy cold start — and a
     *  request older than that names a wedged registration
     *  whose callback will never arrive, not a connection
     *  in progress. The facade used to trust the bare flag
     *  forever: one lost callback disabled binding, and
     *  kept the presentation at "connecting", for the rest
     *  of the process. */
    public static final long BIND_STALE_MS = 30_000L;

    /** Whether a bind requested at {@code requestedWallMs}
     *  still counts as outstanding at {@code nowWallMs}. */
    public static boolean bindOutstanding(long requestedWallMs,
                                          long nowWallMs) {
        return requestedWallMs > 0
                && nowWallMs - requestedWallMs < BIND_STALE_MS;
    }
}
