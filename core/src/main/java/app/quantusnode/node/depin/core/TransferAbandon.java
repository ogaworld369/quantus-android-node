package app.quantusnode.node.depin.core;

import java.io.File;
import java.util.Map;

/**
 * The receiver-side abandon composition (integration design
 * section 4.3 step 9 and the pre-plan explicit cancel): issue
 * the cannot-confirm response for a receiver generation,
 * routing on the RECORDED STATE of that generation rather
 * than on the core's refusal types.
 *
 * The core's {@link TransferReceiver#issueAbandon} states its
 * terminal conditions directly: a closed generation refuses
 * (Hold), an open trial refuses unless its stage is already
 * issuable, and a missing staged seed refuses
 * (Inconsistency). Which of those the user is asking for
 * depends on the state, so the composition reads the record
 * first and branches on what it finds:
 *
 *   - Closure of kind "refusal" with its stored response:
 *     the refusal was already issued and durably recorded;
 *     re-present the stored full value as-is. (The core
 *     cannot do this itself — its issuance path refuses a
 *     closed generation before any value is produced.)
 *   - Any other closure (a verified cancel token, an R2
 *     commit, or a refusal whose value is missing): the
 *     generation is closed; the core's own refusal governs
 *     and no cancel is attempted.
 *   - An open trial whose judgement is not "confirm" and
 *     whose stage is not issuable (e.g. "planned"): the
 *     design's explicit cancel closes the trial first
 *     (step 9), then issuance proceeds on the closed trial.
 *   - Everything else (no trial; an issuable stage; a
 *     confirm judgement): issuance goes to the core as-is
 *     and its outcome — success or refusal — governs.
 *
 * The core's refusals are NEVER caught here. In particular
 * the Inconsistency refusal ("abandon issuance without the
 * staged seed") is the classification-(c) preservation
 * state: everything is kept and recovery is stopped, so the
 * composition must not "help" by cancelling — cancelling
 * would commit a judgement and move files in exactly the
 * state the design says to preserve untouched.
 *
 * The record read is adoption-refreshed before branching
 * (section 4.2: re-presenting messages is adoption-gated).
 * The read only ROUTES; every core operation re-loads and
 * re-validates the state itself, so a state change between
 * the read and the operation surfaces as the core's own
 * refusal, never as a composition-level bypass.
 *
 * This class lives in the core so the composition itself is
 * covered by the JVM transfer harness; the apps' runtimes
 * only construct the receiver and delegate.
 */
public final class TransferAbandon {
    private TransferAbandon() {}

    /** Issue (or re-present) the abandon response for the
     *  receiver generation (deviceId, generation). */
    public static Map<String, Object> issueReceiverAbandon(
            TransferReceiver receiver, File storeRoot,
            String deviceId, long generation) throws Exception {
        TransferState st = TransferState.load(storeRoot);
        // Adoption: after this call the instance holds the
        // durable content whether or not it had drifted.
        st.adopt();
        Map<String, Object> rec = st.receiver(deviceId, generation);
        if (rec == null) {
            // No record: the core's refusal ("no such
            // generation") is the answer.
            return receiver.issueAbandon(deviceId, generation);
        }
        Map<String, Object> closure =
                TransferState.child(rec, "closure");
        if (closure != null) {
            if ("refusal".equals(TransferState.stringOr(
                    closure, "kind", null))) {
                Map<String, Object> stored =
                        TransferState.child(closure, "abandon");
                if (stored != null) {
                    // Re-presentation: the recorded refusal,
                    // full value, exactly as issued.
                    return stored;
                }
            }
            // Closed by a cancel token or an R2 commit (or a
            // refusal whose value was lost): the generation
            // stays closed and the core says so.
            return receiver.issueAbandon(deviceId, generation);
        }
        Map<String, Object> trial = TransferState.child(rec, "trial");
        if (trial != null) {
            Map<String, Object> judgement =
                    TransferState.child(trial, "judgement");
            boolean confirm = judgement != null && "confirm".equals(
                    TransferState.stringOr(judgement, "kind", null));
            String stage = TransferState.stringOr(trial, "stage", null);
            boolean issuable = "cancel_complete".equals(stage)
                    || "start_blocked".equals(stage)
                    || ("started".equals(stage)
                            && !TransferState.boolOr(
                                    trial, "plan_adopted", false));
            if (!confirm && !issuable) {
                // Design section 4.3 step 9: an undecided
                // trial in a non-issuable stage is closed by
                // the explicit cancel first; issuance then
                // proceeds on the cancelled trial. A cancel
                // that itself refuses (the judgement is
                // already committed inside the core's own
                // checks) propagates unchanged.
                receiver.cancelTrial(deviceId, generation);
            }
        }
        return receiver.issueAbandon(deviceId, generation);
    }
}
