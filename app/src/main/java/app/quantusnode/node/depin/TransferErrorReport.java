package app.quantusnode.node.depin;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A transfer failure's diagnostic file, built ONLY from a
 * strict whitelist (design consultation E10, which replaced
 * the first version's exception dump): the app's OWN durable
 * state classification plus manifest-derived facts about the
 * package the user was handling. Failures are described by
 * OUR OWN reason codes — a phase plus a code from the fixed
 * taxonomy below — never by exception text.
 *
 * Excluded by construction (there is no parameter that could
 * carry them): exception messages and stack traces, file
 * URIs and paths, decrypted payload fragments, the transfer
 * code (and even its length), and all secret material — the
 * seed, private keys, the confirm secret and any HMAC key.
 * The builder's inputs are metadata the UI already displays.
 *
 * If a failure fits no taxonomy code, add one here rather
 * than widening the file.
 */
public final class TransferErrorReport {

    // ---- phases ----
    public static final String PHASE_EXPORT = "export";
    public static final String PHASE_STAGE = "stage";
    public static final String PHASE_CONFIRM = "confirm";
    public static final String PHASE_CLOSE = "close";
    public static final String PHASE_CANCEL = "cancel";
    public static final String PHASE_NOTICE = "notice";
    public static final String PHASE_PERMIT = "permit";
    public static final String PHASE_RECEIPT = "receipt";
    public static final String PHASE_ACK = "ack";
    public static final String PHASE_RECOVERY = "recovery";
    public static final String PHASE_SERVICE = "service";
    public static final String PHASE_FILE = "file";

    // ---- reason taxonomy (fixed; extend, never improvise) ----
    /** The state machine refused the operation (frozen,
     *  closed, mode guard, tombstone, number mismatch...). */
    public static final String R_HOLD = "hold_state";
    /** Durable state contradicts itself (torn or tampered). */
    public static final String R_INCONSISTENT = "inconsistent_state";
    /** A presented value failed verification (signature,
     *  chain, manifest agreement, count mismatch). */
    public static final String R_VERIFICATION = "verification_failed";
    /** The package did not open under the entered code. */
    public static final String R_CODE = "code_rejected";
    /** The device keystore / key material was unavailable
     *  (final check round 3, H-13): a key that cannot be
     *  loaded is not a wrong code, and reporting it as one
     *  sent diagnosis — and the user, re-entering the code —
     *  in the wrong direction. */
    public static final String R_KEYSTORE = "keystore_unavailable";
    /** Local I/O failed (storage, fsync, file pick). */
    public static final String R_IO = "io_error";
    /** The transfer service could not be reached or bound. */
    public static final String R_SERVICE = "service_unavailable";
    /** The operation was interrupted. */
    public static final String R_INTERRUPTED = "interrupted";
    /** The user's input was not in the required form. */
    public static final String R_INPUT = "invalid_input";
    /** Anything else; pair with the phase and state fields. */
    public static final String R_UNKNOWN = "unknown";

    private TransferErrorReport() {}

    /**
     * Classify a failure into the taxonomy using ONLY the
     * exception's type — never its message. Walks the cause
     * chain looking for the core's verdict types first.
     */
    public static String classify(Throwable t) {
        boolean sawIo = false;
        boolean sawIllegalArg = false;
        boolean sawHold = false;
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof InterruptedException) {
                return R_INTERRUPTED;
            }
            String n = c.getClass().getSimpleName();
            if ("HoldException".equals(n)) {
                return R_HOLD;
            }
            if ("InconsistencyException".equals(n)) {
                return R_INCONSISTENT;
            }
            if (c instanceof javax.crypto.AEADBadTagException
                    || n.contains("AEAD")) {
                // The AEAD tag did not verify. Under the
                // transfer code's own key that IS the entered
                // code's verdict. Final check round 4 (I-11):
                // under a Keystore-held key it is not — the
                // platform reports a failed unwrap there as an
                // AEAD tag failure CAUSED BY a keystore
                // exception, and it is the wrap key or the
                // wrapped record that failed; no code was
                // involved. The cause chain decides.
                return keystoreCaused(c.getCause())
                        ? R_KEYSTORE : R_CODE;
            }
            if (c instanceof java.security.KeyStoreException
                    || c instanceof java.security
                            .UnrecoverableKeyException
                    || c instanceof java.security
                            .UnrecoverableEntryException
                    || c instanceof java.security.InvalidKeyException
                    || c instanceof java.security.ProviderException
                    || c instanceof java.security.NoSuchAlgorithmException
                    || c instanceof java.security.NoSuchProviderException
                    || c instanceof java.security.cert.CertificateException
                    || n.contains("KeyStore")) {
                // H-13: keystore-family failures (the type
                // checks cover the JVM/Android Keystore
                // surface; the name check covers
                // android.security.KeyStoreException, which
                // shares no supertype with them) mean the key
                // material could not be reached at all —
                // never "the code was wrong".
                return R_KEYSTORE;
            }
            if (c instanceof java.security.GeneralSecurityException) {
                // Any other crypto failure is neither a code
                // verdict nor a keystore verdict: unknown,
                // honestly, instead of the old blanket
                // code_rejected.
                return R_UNKNOWN;
            }
            if (c instanceof IllegalStateException) {
                // Residual F-25: remembered, not returned.
                // An app-layer guard's ISE wrapping the
                // core's own verdict used to end the walk
                // here and report a hold, concealing the
                // more specific verdict below (an AEAD tag
                // failure became "hold_state" instead of the
                // code verdict). The walk continues; the hold
                // decides only when nothing more specific
                // follows in the chain.
                sawHold = true;
            }
            if (c instanceof java.io.IOException) {
                sawIo = true;
            }
            if (c instanceof IllegalArgumentException) {
                sawIllegalArg = true;
            }
        }
        if (sawHold) {
            return R_HOLD;
        }
        if (sawIllegalArg) {
            return R_VERIFICATION;
        }
        if (sawIo) {
            return R_IO;
        }
        return R_UNKNOWN;
    }

    /** Whether a keystore-family failure appears in the
     *  cause chain starting at {@code c} (type and class
     *  name only, never a message). Bounded walk. */
    private static boolean keystoreCaused(Throwable c) {
        for (int i = 0; c != null && i < 16; c = c.getCause(), i++) {
            if (c instanceof java.security.KeyStoreException
                    || c instanceof java.security
                            .UnrecoverableKeyException
                    || c instanceof java.security
                            .UnrecoverableEntryException
                    || c instanceof java.security.ProviderException
                    || c.getClass().getSimpleName()
                            .contains("KeyStore")) {
                return true;
            }
        }
        return false;
    }

    /** Build the file body from whitelist fields only. */
    public static String buildJson(
            String app, String versionName, long versionCode,
            long wallMs,
            String phase, String mode, String side, String reason,
            TransferFlow.Snapshot own,
            String peerDeviceId, long peerGeneration,
            String peerPackageSha256,
            long totalCheckpoints, long verifiedCheckpoints)
            throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "quantus-depin-transfer-error");
        root.put("version", 2L);
        root.put("app", app);
        root.put("version_name", versionName);
        root.put("version_code", versionCode);
        root.put("wall_unix", wallMs / 1000L);
        root.put("phase", phase);
        root.put("mode", mode);
        root.put("side", side);
        root.put("reason", reason);
        Map<String, Object> local = new LinkedHashMap<>();
        if (own != null) {
            local.put("receiver_state", own.receiver.name());
            local.put("sender_state", own.sender.name());
            if (own.activeDeviceId != null) {
                local.put("device_id", own.activeDeviceId);
            }
            if (own.senderDeviceId != null) {
                local.put("sender_device_id", own.senderDeviceId);
                local.put("sender_generation", own.senderGeneration);
            }
            if (own.receiverDeviceId != null) {
                local.put("receiver_device_id", own.receiverDeviceId);
                local.put("receiver_generation", own.receiverGeneration);
            }
            local.put("load_failed", own.loadFailed);
        }
        root.put("local_state", local);
        if (peerDeviceId != null) {
            Map<String, Object> peer = new LinkedHashMap<>();
            peer.put("device_id", peerDeviceId);
            if (peerGeneration >= 0) {
                peer.put("generation", peerGeneration);
            }
            if (peerPackageSha256 != null) {
                peer.put("package_sha256", peerPackageSha256);
            }
            root.put("peer_from_manifest", peer);
        }
        if (totalCheckpoints >= 0) {
            Map<String, Object> counts = new LinkedHashMap<>();
            long cappedTotal = Math.min(totalCheckpoints,
                    TransferFlow.MAX_DISPLAY_CHECKPOINTS);
            counts.put("total_checkpoints", cappedTotal);
            if (verifiedCheckpoints >= 0) {
                // Audit Low: the verified count was emitted
                // raw beside the capped total — an impossible
                // pair (verified > total) in the file a user
                // hands over for diagnosis. Cap it at both.
                counts.put("verified_checkpoints",
                        Math.min(verifiedCheckpoints, cappedTotal));
            }
            root.put("counts", counts);
        }
        return new JSONObject(root).toString(2);
    }

    /** depin-transfer-error-<yyyymmdd-hhmmss>.json (UTC). */
    public static String suggestedFileName() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                "yyyyMMdd-HHmmss", java.util.Locale.ROOT);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return "depin-transfer-error-" + f.format(
                new java.util.Date()) + ".json";
    }
}
