package app.quantusnode.node.depin;

/**
 * How a writer-gate verdict is treated after it has been
 * computed — pure, no I/O. This is the fix for the
 * internal.24 device failure ("transfer gate:
 * not_switched_on" self-stop; progress file, internal.24
 * field-diagnosis section).
 *
 * What went wrong: DepinObserverService.onCreate starts the
 * journal construction on the runtime's background thread
 * (prefetch) before any command is processed, and the
 * construction evaluates TransferInit.writerGate. The gate's
 * LAST check is the recorded enable authorisation — a
 * function of DepinSettings, which the enable command itself
 * writes (processEnable records setEnabled(liveId) before
 * initialising, the section 9 ordering). A service created
 * while the on-disk flag was OFF — a state internal.24's
 * durable disable() made reachable for the first time —
 * therefore had its prefetch evaluate the gate BEFORE the
 * authorisation existed, fail with not_switched_on, and
 * CACHE that failure for the whole construction generation.
 * ensureInitialized then received the cached verdict even
 * though the authorisation had been recorded in the
 * meantime, and self-stopped on it; the self-stop record
 * wrote enabled=false, making the state self-perpetuating.
 *
 * The classification: exactly ONE gate reason is a function
 * of the recorded authorisation — not_switched_on (the
 * gate computes it from the explicitOn input alone, after
 * every transfer-state check has passed). Every other
 * reason is a function of transfer state, which changes
 * only through transfer operations — and those already
 * invalidate the construction cache. So:
 *
 *  - An authorisation-dependent verdict is NOT cached: the
 *    next blocking read re-evaluates the gate against the
 *    then-current settings, which by then include any
 *    authorisation an enable command recorded. This can
 *    never let recording start without an authorisation:
 *    re-evaluation reads the RECORDED flag, and the gate
 *    refuses again when it is still absent.
 *  - Every other verdict — and any failure that did not
 *    come from the gate at all — keeps the cached,
 *    fail-closed treatment, frozen_s1 included.
 *  - An unrecognised reason is state-only by default: the
 *    fail-closed side is the default, never the retry side.
 *
 * And at initialisation: a failure whose verdict is
 * authorisation-dependent is an authorisation WAIT, not a
 * self-stop. Recording a self-stop would write enabled=false
 * (DepinSettings.recordSelfStop), erasing the very
 * authorisation the enable command just recorded and making
 * the stopped state permanent; the honest presentation is
 * OFF/PREPARING — enabled, not yet recording — from which
 * the reconcile's next launch re-evaluates and proceeds.
 */
public final class DepinGateVerdict {

    private DepinGateVerdict() {
    }

    /** The writer-gate reason (TransferInit.writerGate) whose
     *  verdict is a function of the recorded enable
     *  authorisation. The harness pins this constant to the
     *  exact string the core gate produces. */
    public static final String REASON_NOT_SWITCHED_ON =
            "not_switched_on";

    /** Whether a gate verdict with this reason can change
     *  without any transfer operation taking place — i.e. it
     *  depends on the recorded authorisation, which an enable
     *  command writes and a disable/self-stop clears. */
    public static boolean isAuthorizationDependent(
            String gateReason) {
        return REASON_NOT_SWITCHED_ON.equals(gateReason);
    }

    /** Whether a construction failure at the gate is cached
     *  for the rest of the construction generation (CACHED)
     *  or dropped so the next blocking read re-evaluates the
     *  gate against the then-current state (RE_EVALUATE). */
    public enum FailureCache { CACHED, RE_EVALUATE }

    public static FailureCache failureCache(String gateReason) {
        return isAuthorizationDependent(gateReason)
                ? FailureCache.RE_EVALUATE
                : FailureCache.CACHED;
    }

    /** What initialisation does when construction fails at
     *  the gate: record a durable self-stop (SELF_STOP), or
     *  treat the run as an authorisation wait — stop without
     *  a self-stop record (AUTHORIZATION_WAIT). A null reason
     *  (the failure did not come from the gate) is a
     *  self-stop, as before. */
    public enum InitFailure { SELF_STOP, AUTHORIZATION_WAIT }

    public static InitFailure initFailure(String gateReason) {
        return isAuthorizationDependent(gateReason)
                ? InitFailure.AUTHORIZATION_WAIT
                : InitFailure.SELF_STOP;
    }
}
