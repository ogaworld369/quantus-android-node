package app.quantusnode.node.depin.core;

import java.util.List;
import java.util.Map;

/**
 * Proof-bundle export for the app layer (integration build step
 * 5): assemble the CURRENT-format bundle over a journal's
 * checkpoints, self-check it, and serialise it — the same three
 * steps the companion's export path performs inline (its
 * buildCurrentBundle), factored into the core because the Node
 * app's Android layer lives in a different package and must not
 * reach the package-private assembly / verification entry points
 * directly.
 *
 * Behaviour mirrors the companion exactly:
 *  - an empty checkpoint list is NOT an error: buildJson returns
 *    null and the caller reports "no checkpoints yet";
 *  - beyond MAX_CHECKPOINTS the export is REFUSED with the
 *    reason (the v0.6 rule: never truncate to a chain fragment
 *    that would fail verification downstream);
 *  - the self-check is verifyBundle with the chain recomputation
 *    skipped (the verifier's job, exercised against the
 *    reference implementation in the cross-checks); a bundle
 *    that fails its own signature / checkpoint-hash check never
 *    leaves the app.
 *
 * The seed is handled by the caller: it is loaded for exactly
 * this call and zeroed by the caller as soon as buildJson
 * returns, the same discipline as the companion's export.
 */
public final class BundleExport {
    private BundleExport() {}

    /** Export cap, identical to the companion's
     *  EXPORT_MAX_CHECKPOINTS. */
    public static final int MAX_CHECKPOINTS = 100_000;

    /**
     * Build + self-check + serialise the bundle for the given
     * checkpoints under the identity the seed belongs to.
     * Returns the pretty JSON text, or null when there are no
     * checkpoints yet. Throws IllegalStateException when the
     * export cap is exceeded or the self-check fails.
     */
    public static String buildJson(byte[] seed,
                                   List<DepinCore.Checkpoint> checkpoints,
                                   long createdAtUnix) {
        if (checkpoints.isEmpty()) {
            return null;
        }
        if (checkpoints.size() > MAX_CHECKPOINTS) {
            throw new IllegalStateException(
                    "checkpoint count " + checkpoints.size()
                            + " exceeds the export limit "
                            + MAX_CHECKPOINTS);
        }
        Map<String, Object> bundle = DepinCore.assembleBundle(
                seed, checkpoints, createdAtUnix,
                DepinCore.PRODUCTION_ITERATIONS);
        List<String> errors = DepinCore.verifyBundle(
                bundle, DepinCore.PRODUCTION_ITERATIONS, false);
        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "bundle self-check failed: " + errors.get(0));
        }
        return Json.pretty(bundle);
    }
}
