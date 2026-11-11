package app.quantusnode.node.depin.core;

import java.util.Map;

/**
 * Audit F5 — the production remediation DECISION.
 *
 * The situation this repairs: an installation sent its
 * identity onward and completed the handover (sender
 * history S2, either closure kind), believing its local
 * seed was erased. The closure erased only the TRANSFER
 * vault's copies; the seed in the app layer's own home
 * vault — the one the journal was born with — survived,
 * held back from recording solely by transfer state. One
 * lost init record (or one code path that resolves the
 * journal without consulting that state) would let it
 * record again: a second live copy of an identity that now
 * lives on another device.
 *
 * This class decides, from the transfer state alone,
 * whether the home copy must be erased; the app layer
 * supplies the home seed's derived device id (read WITHOUT
 * creating a seed) and performs the erasure plus the
 * sent-out marker. The decision is deliberately narrow —
 * erasure is due exactly when ALL of these hold:
 *
 *   - an identity section exists for the home seed's
 *     device id;
 *   - its sender_history contains an S2 (the handover
 *     COMPLETED — either closure kind);
 *   - NO sender record is open for it (an in-flight S1
 *     makes any erasure unthinkable: the handover may
 *     still be cancelled back home);
 *   - the identity is not home again as the held sender
 *     (a later cancel restored the binding): no active
 *     binding, a binding naming another identity, or a
 *     RECEIVER binding for this identity are all fine —
 *     in the receiver case the identity came back as a
 *     received identity whose operative seed is the
 *     transfer vault's copy, so the home copy is a
 *     redundant second copy either way.
 *
 * Nothing here mutates state or touches files.
 */
public final class TransferRemediation {
    private TransferRemediation() {}

    /**
     * The device id whose home seed copy must be erased —
     * always {@code homeDeviceId} itself when due — or null
     * when no erasure is due.
     */
    public static String sentOutIdentityAwaitingErasure(
            TransferState state, String homeDeviceId) {
        if (state == null || homeDeviceId == null) {
            return null;
        }
        if (state.sender(homeDeviceId) != null) {
            return null; // an S1 is open: never erase
        }
        Map<String, Object> idSection = state.identity(homeDeviceId);
        if (idSection == null) {
            return null;
        }
        boolean sent = false;
        Object hist = idSection.get("sender_history");
        if (hist instanceof java.util.List) {
            for (Object o : (java.util.List<?>) hist) {
                if (o instanceof Map && "S2".equals(
                        ((Map<?, ?>) o).get("state"))) {
                    sent = true;
                }
            }
        }
        if (!sent) {
            return null;
        }
        Map<String, Object> active = state.active();
        if (active != null
                && homeDeviceId.equals(TransferState.stringOr(
                        active, "device_id", null))
                && "sender".equals(TransferState.stringOr(
                        active, "role", null))) {
            return null; // home again as the held sender
        }
        return homeDeviceId;
    }

    /**
     * Whether the sent-out marker closes the writer gate
     * (audit F6). Round 1 left the marker's effect to the
     * app layer, which honoured it FIRST and
     * unconditionally: a store that had once sent its home
     * identity out could never record again — not a
     * different identity it later received, not even its own
     * identity returned as a new generation. The marker's
     * true scope is the HOME-SEED PATH of its own identity:
     * the gate closes exactly when the marker names the
     * identity the gate resolved to AND that identity has
     * no received copy in the transfer vault (its operative
     * seed would be the erased home copy). A received copy
     * — the identity back as a new generation, held under
     * its receiver key — is a different, live holding, and
     * the marker says nothing about it. The app layer
     * supplies the marker's device id and the vault fact;
     * the decision itself lives here so the harness pins
     * all three cases.
     */
    public static boolean sentOutMarkerClosesGate(
            String markerDeviceId, String gateDeviceId,
            boolean receiverCopyExists) {
        return markerDeviceId != null
                && markerDeviceId.equals(gateDeviceId)
                && !receiverCopyExists;
    }

    // ---------------- audit F9: the journal path decision ---

    /** Journal construction must fail closed: the gate
     *  names an identity this device holds neither at home
     *  nor as a received copy at the gate's generation. */
    public static final int JOURNAL_FAIL_CLOSED = 0;
    /** No transfer gate exists (the layer was never
     *  introduced): the pre-transfer home path, whose seed
     *  reads may create the first identity. */
    public static final int JOURNAL_LEGACY_HOME = 1;
    /** The gate names the home identity itself: the home
     *  path, but every seed read is NON-creating — a gate
     *  exists, so silently minting a fresh identity would
     *  resurrect a sender the state believes is gone. */
    public static final int JOURNAL_GATED_HOME = 2;
    /** The gate's identity is held as a received copy at
     *  the gate's own generation: the journal is built
     *  under the transfer vault's copy and the home vault
     *  is never consulted. */
    public static final int JOURNAL_RECEIVED = 3;

    /**
     * Which seed journal construction may use (audit F9).
     *
     * Round 2 (F6) chose the received path by "a received
     * copy exists" and let EVERY other gated state fall
     * into the home path, which then built the journal
     * from the home vault's seed — creating one when absent
     * — without ever checking that the home seed derives
     * the identity the gate resolved to. Two shapes built
     * a journal for an identity that must not record here:
     * (a) a sent-out identity whose home copy survived a
     * failed erasure (no marker), while a received identity
     * was active and its copy momentarily unreadable or
     * missing — the sent-out identity's journal, exactly
     * the harm F5 exists to prevent; (b) the home copy
     * erased and the marker present for it, a received
     * identity active — the marker check compared the
     * marker against the GATE's identity, so construction
     * proceeded and minted a brand-new identity in the
     * home vault.
     *
     * The decision is therefore: a gate, when present, is
     * answered ONLY by the identity it names — its received
     * copy at its own generation, or the home seed when
     * (and only when) a NON-creating read shows the home
     * seed derives that very identity. Anything else fails
     * closed. The app layer supplies the three facts (gate
     * presence and identity, the home seed's derived id
     * from a non-creating read — null when no home seed
     * exists or it cannot be read — and whether the
     * received copy exists at the gate's generation); the
     * "copy unreadable" case never reaches this decision,
     * because the app layer's existence check throws
     * instead of answering false (see the F9 wiring).
     */
    public static int journalPath(boolean gatePresent,
                                  String gateDeviceId,
                                  String homeDeviceId,
                                  boolean receivedCopyAtGateGeneration) {
        if (!gatePresent) {
            return JOURNAL_LEGACY_HOME;
        }
        if (receivedCopyAtGateGeneration) {
            return JOURNAL_RECEIVED;
        }
        if (gateDeviceId != null
                && gateDeviceId.equals(homeDeviceId)) {
            return JOURNAL_GATED_HOME;
        }
        return JOURNAL_FAIL_CLOSED;
    }

    // ---------------- audit F10 / M12: structural derivation ---

    /**
     * Whether {@code seed} derives exactly {@code deviceId}
     * — the structural identity check, computed from the
     * key material itself. Two decisions used to trust
     * generation numbers and upstream string comparisons
     * instead (audit F10 / M12):
     *
     *   - the superseded-copy sweep (and the closure-time
     *     erasure it retries) classified a vault copy by the
     *     GENERATION it is filed under alone. The vault
     *     namespace is generation-only, shared by every
     *     identity in the store, so a copy filed under a
     *     generation can belong to a different lineage than
     *     the record being swept; deleting it on the number
     *     alone silently destroyed the copy — and with it
     *     the evidence the recovery classification would
     *     have surfaced as "seed does not derive the
     *     identity". Callers now prove derivation with this
     *     check before erasing, and leave an unproven copy
     *     exactly where the classification will find it.
     *   - the GATED_HOME journal construction admitted the
     *     home path because an EARLIER, separate read of
     *     the home vault produced an id string equal to the
     *     gate's, then built from a SECOND read without
     *     re-verifying the loaded bytes. Construction now
     *     re-checks the very seed it is about to build
     *     under, at the point of use.
     *
     * A null seed, a null id, or an unparseable seed all
     * answer false: an unproven copy is never treated as
     * belonging to the identity.
     */
    public static boolean seedDerivesIdentity(byte[] seed,
                                              String deviceId) {
        if (seed == null || deviceId == null) {
            return false;
        }
        try {
            return deviceId.equals(DepinCore.deviceIdFromPubkey(
                    Ed25519.publickeyFromSeed(seed)));
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---------------- audit L15: the signing-time proof ----

    /**
     * Prove, at signing time, that the seed about to sign
     * belongs to the journal it signs for (audit L15).
     *
     * The journal constructions prove the loaded seed
     * against the gate / binding identity ONCE, at build
     * time (audit M12) — but the signers do not sign with
     * the proven bytes: they RE-READ the seed from its
     * vault on every signature (the working copy is zeroed
     * between checkpoints), and round 3 checked nothing
     * about the re-read. A copy swapped or corrupted
     * between the build and a later checkpoint — the F10
     * slot collision is one way the bytes under a
     * generation change lineage — would sign entries the
     * journal's own verifier then rejects: the identity's
     * proof chain breaks at that checkpoint, permanently.
     * The check is therefore repeated where it is cheap
     * and decisive, inside the signer, against the
     * journal's effective public key: a mismatch fails
     * closed (the append throws) instead of writing an
     * entry nothing can verify.
     *
     * Throws IllegalStateException on any mismatch, on a
     * missing seed, or on a seed that cannot be derived
     * from at all.
     */
    public static void checkSigningSeed(byte[] seed,
                                        byte[] expectedPubkey) {
        if (seed == null) {
            throw new IllegalStateException(
                    "no seed is available for journal signing");
        }
        final byte[] derived;
        try {
            derived = Ed25519.publickeyFromSeed(seed);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "the seed loaded for journal signing is "
                            + "unusable", e);
        }
        if (expectedPubkey == null
                || !java.util.Arrays.equals(derived,
                        expectedPubkey)) {
            throw new IllegalStateException(
                    "the seed loaded for journal signing does "
                            + "not belong to the journal's "
                            + "identity (audit L15)");
        }
    }

    // ---------------- audit L16: the erasure stage machine -

    /** Not one of the three stages: the machine must not
     *  act on a stage it does not know. */
    public static final int ERASURE_STAGE_UNKNOWN = -1;
    /** The target is durable; the seed has not been
     *  erased yet. The ONLY stage the seed may be erased
     *  in. */
    public static final int ERASURE_STAGE_PENDING = 0;
    /** The seed is verified gone; the sent-out marker
     *  has not landed. */
    public static final int ERASURE_STAGE_SEED_ERASED = 1;
    /** The marker is on disk; only the record's own
     *  removal remains. */
    public static final int ERASURE_STAGE_COMPLETE = 2;

    /**
     * Classify an erasure record's stage string (audit
     * L16). Round 3's resume logic branched on string
     * equality for two stages and let EVERYTHING else —
     * a "complete" record whose seed was still present,
     * a stage string no build ever wrote — fall into the
     * erasure branch, so the documented stage rules
     * (erase only in pending; the marker only from
     * seed_erased, with the seed verified gone) were the
     * javadoc's claim, not the code's. The classifier is
     * total: any string but the three is UNKNOWN, and the
     * app layer records an unknown stage and stops,
     * touching nothing.
     */
    public static int erasureStage(String stage) {
        if ("pending".equals(stage)) {
            return ERASURE_STAGE_PENDING;
        }
        if ("seed_erased".equals(stage)) {
            return ERASURE_STAGE_SEED_ERASED;
        }
        if ("complete".equals(stage)) {
            return ERASURE_STAGE_COMPLETE;
        }
        return ERASURE_STAGE_UNKNOWN;
    }

    /** Whether the seed may be erased at {@code stage}:
     *  only ever from pending, with the record already
     *  durable — an erasure with no resumable record
     *  cannot happen, and no other stage re-opens the
     *  question. */
    public static boolean erasureSeedMayBeErased(int stage) {
        return stage == ERASURE_STAGE_PENDING;
    }

    /**
     * Whether the sent-out marker may be written at
     * {@code stage}: only from a post-erasure stage
     * (seed_erased, or complete when re-deriving a lost
     * marker), and only when the seed's absence has been
     * RE-VERIFIED on this entry — {@code seedPresent} and
     * {@code seedUnreadable} are the caller's fresh probe
     * of the home vault, never the record's own claim. A
     * marker asserting an erasure that did not happen —
     * or that nobody could check — must not be writable.
     */
    public static boolean erasureMarkerMayBeWritten(
            int stage, boolean seedPresent,
            boolean seedUnreadable) {
        return (stage == ERASURE_STAGE_SEED_ERASED
                || stage == ERASURE_STAGE_COMPLETE)
                && !seedPresent && !seedUnreadable;
    }

    // ---------------- audit L17: the erasure record parse --

    /** A parsed home-seed erasure record (companion app
     *  layer's stage record, audit L14): the fields the
     *  stage machine consumes, nothing else. */
    public static final class ErasureRecordData {
        public final String deviceId;
        public final long generation;
        public final String stage;

        ErasureRecordData(String deviceId, long generation,
                          String stage) {
            this.deviceId = deviceId;
            this.generation = generation;
            this.stage = stage;
        }
    }

    /**
     * Parse an erasure record's bytes, or return null when
     * they do not form one (audit L17). The parse is the
     * same shape the companion's reader always applied —
     * a device id and a stage are required, the generation
     * defaults to -1 — but it lives here so the harness
     * pins it: a torn or truncated record (round 3 wrote
     * the record with a plain fsynced write, which a crash
     * can cut in half) is a PARSE FAILURE the caller
     * records durably, never a silent "no record" that
     * freezes the stage machine mid-erasure.
     */
    public static ErasureRecordData parseErasureRecord(
            byte[] content) {
        if (content == null) {
            return null;
        }
        try {
            Map<String, Object> m = Json.parseObject(
                    new String(content,
                            java.nio.charset.StandardCharsets
                                    .UTF_8));
            String deviceId = TransferState.stringOr(
                    m, "device_id", null);
            String stage = TransferState.stringOr(
                    m, "stage", null);
            if (deviceId == null || stage == null) {
                return null;
            }
            return new ErasureRecordData(deviceId,
                    TransferState.longOr(m, "generation", -1),
                    stage);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
