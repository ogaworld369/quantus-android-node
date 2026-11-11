package app.quantusnode.node.depin.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;

/**
 * The receiver side of the transfer (design section 4.3 state
 * machine R0/R1/R2, the intake steps 1-9, section 4.4 response
 * rules, and the crash-recovery router).
 *
 * Durable facts this class keeps strictly separate, as the
 * design requires: (1) a trial's end, (2) a permit's end
 * (expiry latch or consumption by a trial's end), (3) a
 * generation's closure (R2, permanent refusal, or a cancel
 * token — never a trial's mere convergence). The trial
 * record's judgement field is the ONLY authoritative record
 * of whether the confirmation judgement was taken.
 *
 * Two failure vocabularies are distinguished throughout:
 * {@link HoldException} — a precondition is not currently met;
 * the message/input is retained by the caller and the state is
 * unchanged (design section 4.4 (b): hold, surface, never
 * discard). {@link InconsistencyException} — classification
 * (c): everything is preserved untouched and recovery stops;
 * no R1 return, no abandon issuance, no new judgement, no new
 * permit intake, no cleanup.
 */
public final class TransferReceiver {

    public static class HoldException extends IllegalStateException {
        public HoldException(String msg) { super(msg); }
    }

    public static class InconsistencyException extends IllegalStateException {
        public InconsistencyException(String msg) { super(msg); }
    }

    /** Thrown when a re-verification pauses on the section 3.5
     *  budget (window budget spent, or a wait obligation
     *  outstanding). The intake is resumable via
     *  {@link #continueIntake} once the budget allows. */
    public static class VerificationPausedException extends Exception {
        public final String deviceId;
        public final long generation;
        public final String packageSha256;
        public VerificationPausedException(String deviceId, long gen,
                                           String pkg, String why) {
            super(why);
            this.deviceId = deviceId;
            this.generation = gen;
            this.packageSha256 = pkg;
        }
    }

    /** A fresh finalized-block read (each call must be a NEW
     *  read; cached values are not "new" for the design's
     *  reading protocols). */
    public interface FinalizedSource {
        long readFinalized() throws Exception;
    }

    public interface Clock {
        long wallUnix();
        long monoNanos();
    }

    /**
     * Freeze hook for the FOREIGN writer (audit C-2; design
     * section 7.2 supplement / F-21). When the live placement
     * belongs to a DIFFERENT identity than the one being
     * received (in the integrated app: the shadow identity
     * writing into the same store root), the promotion path
     * must stop that writer and drain it before the foreign
     * placement is quarantined — the same freeze discipline
     * the sender side applies to its own writer, applied to
     * the displaced one. The runtime supplies the
     * implementation (it owns the writer); the core never
     * reaches into the app. When a quarantine is needed but
     * no hook is installed, promotion refuses with
     * HoldException instead of moving a live writer's files.
     */
    public interface ForeignWriterFreeze {
        void freezeForeignWriter(String activeDeviceId)
                throws Exception;
    }

    /** The 20-second cap of the step 8-5 reading protocol. */
    public static final long JUDGEMENT_STEP_CAP_NANOS =
            20L * 1_000_000_000L;
    /** One continuous verification session's wall budget
     *  (section 3.5): 30 minutes, after which verification
     *  pauses resumably. */
    public static final long SESSION_WALL_BUDGET_NANOS =
            30L * 60 * 1_000_000_000L;

    public static final class IntakeResult {
        public String deviceId;
        public long generation;
        public String packageSha256;
        public File stagingRoot;
        public long checkpointCount;
        public long firstTimestampUnix;
        public long tailTimestampUnix;
        public String manifestHash;
        public boolean alreadyNotified;
    }

    private final File storeRoot;
    private final TransferVault vault;
    private final SecretKey wrapKey;
    private final FinalizedSource finalizedSource;
    private final Clock clock;
    private final Reverification.TimeSource timeSource;
    private ForeignWriterFreeze foreignFreeze;

    /** Install the foreign-writer freeze hook (see
     *  {@link ForeignWriterFreeze}). Optional; only needed
     *  where a foreign identity can hold the live placement. */
    public void setForeignWriterFreeze(ForeignWriterFreeze hook) {
        this.foreignFreeze = hook;
    }

    /** The sequential-chain iteration count the intake
     *  accepts (final check round 3, H-08): a staged
     *  checkpoint whose iterations differ is refused at
     *  verifyStagedEntities, BEFORE the sliced
     *  re-verification starts spending CPU on it. Without
     *  the gate, a staged journal naming an iterations value
     *  near 2^31 made ONE checkpoint's recomputation an
     *  uninterruptible multi-minute burn inside a single
     *  slice, on a value the sender could choose freely.
     *  0 = not installed (core-only use); the app layers
     *  install DepinCore.PRODUCTION_ITERATIONS, and the JVM
     *  harness installs its fixture value explicitly. */
    private long expectedIterations = 0;

    public void setExpectedIterations(long iterations) {
        this.expectedIterations = iterations;
    }

    /** Design section 3.4's free-space floor (bytes that
     *  must remain free AFTER the write), for the two
     *  receiver writes that can be large: the staging
     *  expansion and the promotion. 0 = not installed
     *  (core-only use and the JVM harness, whose scratch
     *  filesystems are smaller than the floor); the app
     *  layers install 2 GiB. Enforced through
     *  TransferStore.freeFloorRespected. */
    private long freeFloorBytes = 0;

    public void setFreeFloorBytes(long bytes) {
        this.freeFloorBytes = Math.max(0, bytes);
    }

    private void checkFreeFloor(File dir, String what)
            throws IOException {
        if (freeFloorBytes > 0
                && !TransferStore.freeFloorRespected(
                        dir, freeFloorBytes)) {
            throw new IOException(
                    "free space would fall below the design "
                            + "section 3.4 floor of "
                            + freeFloorBytes + " bytes; " + what
                            + " not started");
        }
    }

    public TransferReceiver(File storeRoot, TransferVault vault,
                            SecretKey wrapKey,
                            FinalizedSource finalizedSource, Clock clock,
                            Reverification.TimeSource timeSource) {
        this.storeRoot = storeRoot;
        this.vault = vault;
        this.wrapKey = wrapKey;
        this.finalizedSource = finalizedSource;
        this.clock = clock;
        this.timeSource = timeSource;
    }

    private TransferState state() throws IOException {
        return TransferState.load(storeRoot);
    }

    // ---------------- intake (steps 1-6) ----------------

    /**
     * Steps 1-6 of the intake procedure, ending in notified R1
     * (or a resumable pause / rejection): {@link #stagePackage}
     * followed by {@link #completeIntake}. Safe to re-enter
     * for the same package: an already-staged package is
     * re-verified against its CURRENT entities and re-runs the
     * synchronisation boundary before anything is treated as
     * held (rev.23 preamble to step 6).
     */
    public IntakeResult intakePackage(File packageFile,
                                      String transferCode)
            throws Exception {
        IntakeResult r = stagePackage(packageFile, transferCode);
        if (!r.alreadyNotified) {
            completeIntake(r.deviceId, r.generation, r.packageSha256);
        }
        return r;
    }

    /**
     * The manifests of packages this store has SENT for an
     * identity, projected to the tombstone fields (export_id,
     * tail hash, checkpoint count): the OPEN sender record —
     * an in-flight export is already spoken for, and taking
     * it in here would duplicate the identity on one device —
     * plus every sender_history entry. S0 (cancelled) entries
     * are included on purpose: a cancelled export's file may
     * still have left this device, and receiving one's own
     * export is never a legitimate flow either way.
     */
    private static List<Map<String, Object>> consumedSenderManifests(
            TransferState st, String deviceId) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> open = st.sender(deviceId);
        if (open != null) {
            Map<String, Object> p = manifestProjection(open);
            if (p != null) {
                out.add(p);
            }
        }
        Map<String, Object> idSection = st.identity(deviceId);
        if (idSection != null) {
            for (Map<String, Object> h : historyEntries(
                    idSection, "sender_history")) {
                Map<String, Object> p = manifestProjection(h);
                if (p != null) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    /** The map entries of a history list inside an identity
     *  section (empty when absent or malformed). */
    private static List<Map<String, Object>> historyEntries(
            Map<String, Object> idSection, String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (idSection == null) {
            return out;
        }
        Object v = idSection.get(key);
        if (v instanceof List) {
            for (Object o : (List<?>) v) {
                if (o instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> e = (Map<String, Object>) o;
                    out.add(e);
                }
            }
        }
        return out;
    }

    /** Project a sender record/history entry to its manifest's
     *  tombstone fields; null when it carries no manifest. */
    private static Map<String, Object> manifestProjection(
            Map<String, Object> record) {
        Map<String, Object> mf = TransferState.child(record, "manifest");
        if (mf == null) {
            return null;
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("export_id", TransferCommitCode.manifestHash(mf));
        p.put("tail_checkpoint_hash", mf.get("tail_checkpoint_hash"));
        p.put("checkpoint_count", mf.get("checkpoint_count"));
        return p;
    }

    /** Serialises the M3 commit-key rescue across every
     *  receiver instance in this process (audit F7): the
     *  staging record is the rescue's one file, and two
     *  concurrent re-presentations must not interleave its
     *  read-modify-write. */
    private static final Object COMMIT_KEY_RESCUE_LOCK =
            new Object();

    /**
     * The M3 rescue (audit F7 form, audit M11 verification):
     * a staging created before confirmation numbers existed
     * carries no key, and confirmOneshot refuses to proceed
     * without one, so re-presenting the file WITH its
     * transfer code re-derives the key here and persists it
     * (wrapped). Round 1's inline form re-derived and
     * re-persisted on every re-presentation that found the
     * staging keyless, with no record that it had fired: an
     * auditor could not distinguish "rescued once,
     * legitimately" from "re-derived repeatedly under codes
     * nobody tracked". The rescue is now a single counted
     * operation: under the process-wide lock the record is
     * RE-READ (a concurrent rescue may already have landed),
     * the key is derived and sealed only when the durable
     * record still lacks one, and the firing is stamped into
     * the record itself — count and wall time, in the same
     * atomic write as the key — so the ledger accumulates
     * across firings and survives in exactly the file an
     * audit reads. Returns the current record (the rescued
     * one when the rescue fired, the untouched one when it
     * did not), or null when no record exists.
     *
     * Audit M11: the derived key is TRUSTED from the moment
     * it lands — it produces the confirmation number the
     * receiver later reads to the sender — so the presented
     * code must first prove itself against the re-presented
     * package: an AEAD open under the code (the envelope is
     * a single seal, so the probe is a full open whose
     * plaintext is discarded unexamined) either succeeds,
     * and the code IS this package's code, or throws, and
     * NOTHING is persisted — no key, no ledger entry — so
     * the rescue stays available for the right code. Round
     * 2's ledger made a wrongful firing visible but could
     * not undo it: a key derived from a mistyped code made
     * hasCommitKeyMaterial() true and blocked every later
     * rescue, stranding the user between a receiver that
     * displays a number the sender will never accept and a
     * cancel that restores the sender's binding — the
     * identity held live in two places.
     *
     * Audit L13: the M11 probe is a FULL in-memory open of
     * the payload, held on top of the package bytes the
     * caller already buffers — an unbounded second copy of
     * a package the intake cap allows to reach 512 MB.
     * The rescue therefore refuses packages above
     * {@link #COMMIT_KEY_RESCUE_MAX_PACKAGE_BYTES}: the
     * refusal is an explicit, named error (never a silent
     * skip), nothing is persisted, and the staging is left
     * exactly as it was. The user's remedy is a fresh
     * import of the same file — the fresh path decrypts to
     * a FILE, not to memory, and derives the key during
     * expansion, so no rescue is needed there at any size
     * the intake cap admits. The cap itself is sized for
     * the journals this flow carries: 128 MiB of payload
     * is on the order of 150,000 checkpoints, decades of
     * ten-minute slots beyond the largest real journal
     * this protocol has moved (the production handover
     * was ~0.25 MB in total).
     */
    static final long COMMIT_KEY_RESCUE_MAX_PACKAGE_BYTES =
            128L * 1024 * 1024;

    static TransferStore.StagingRecord rescueCommitKeyIfAbsent(
            File stagingRoot, String transferCode,
            TransferPackage.Envelope env, long packageBytes,
            javax.crypto.SecretKey wrapKey, Clock clock)
            throws Exception {
        synchronized (COMMIT_KEY_RESCUE_LOCK) {
            TransferStore.StagingRecord sr =
                    TransferStore.loadStaging(stagingRoot);
            if (sr == null || sr.hasCommitKeyMaterial()
                    || transferCode == null) {
                return sr;
            }
            String code = TransferCodec.normalizeCode(transferCode);
            if (code == null) {
                return sr;
            }
            if (packageBytes
                    > COMMIT_KEY_RESCUE_MAX_PACKAGE_BYTES) {
                throw new IllegalArgumentException(
                        "commit-key rescue is limited to "
                                + "packages of at most "
                                + (COMMIT_KEY_RESCUE_MAX_PACKAGE_BYTES
                                        / (1024 * 1024))
                                + " MiB (this package is "
                                + packageBytes + " bytes); "
                                + "discard this staging and import "
                                + "the file again instead — no "
                                + "key was rescued");
            }
            byte[] probe;
            try {
                probe = TransferPackage.decryptPayload(env, code);
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalArgumentException(
                        "the presented transfer code does not "
                                + "open this package; no key was "
                                + "rescued", e);
            }
            java.util.Arrays.fill(probe, (byte) 0);
            sr.commitKeyB64 = TransferCodec.b64Encode(
                    TransferCommitCode.deriveKey(code));
            sr.sealCommitKey(wrapKey);
            sr.commitKeyRescueCount = sr.commitKeyRescueCount + 1;
            sr.commitKeyRescueLastUnix = clock.wallUnix();
            TransferStore.saveStaging(stagingRoot, sr);
            return sr;
        }
    }

    /**
     * Steps 1-5 and the step-6-head synchronisation boundary,
     * WITHOUT recording the notified R1: the design's
     * confirmation screen (device_id, counts, first/tail
     * timestamps, manifest hash — all full values, from the
     * returned IntakeResult) sits between this and
     * {@link #completeIntake}, with abort as its only forward
     * path.
     */
    public IntakeResult stagePackage(File packageFile,
                                     String transferCode)
            throws Exception {
        long len = packageFile.length();
        if (len > TransferPackage.MAX_PACKAGE_BYTES) {
            throw new IllegalArgumentException(
                    "package exceeds the 512 MB cap before reading");
        }
        TransferState state = state();
        if (!state.adopt()) state = state();
        byte[] fileBytes = TransferIo.readAll(
                packageFile, TransferPackage.MAX_PACKAGE_BYTES);
        TransferPackage.Envelope env =
                TransferPackage.parseEnvelope(fileBytes);
        // Step 1: manifest signature, device/pubkey agreement,
        // generation above the committed maximum.
        if (!env.verifyManifestSignature()) {
            throw new IllegalArgumentException(
                    "manifest signature does not verify");
        }
        TransferPackage.Manifest mf = env.manifest;
        String deviceId = mf.deviceId;
        if (!deviceId.equals(DepinCore.deviceIdFromPubkey(mf.pubkey))) {
            throw new IllegalArgumentException(
                    "manifest device_id does not derive from its pubkey");
        }
        if (mf.generation <= state.maxCommittedGeneration(deviceId)) {
            throw new IllegalArgumentException(
                    "generation at or below the committed maximum");
        }
        // E1 tombstone: an export that this installation has
        // already CONSUMED (a completed intake, either mode)
        // is refused on re-presentation — by export_id (the
        // manifest hash), and by tip: a package whose journal
        // tip is not newer than a consumed one carries nothing
        // this device has not already taken in, however it is
        // repackaged. The completed records are the tombstone;
        // they are never deleted. (The residual case — the
        // same file taken in on a DIFFERENT device — cannot be
        // prevented without a server or a return trip, and is
        // documented as accepted residual risk.)
        String thisExportId = TransferCommitCode.manifestHash(mf.raw);
        for (long g : state.receiverGenerations(deviceId)) {
            Map<String, Object> r = state.receiver(deviceId, g);
            Map<String, Object> cl = TransferState.child(r, "closure");
            if (cl == null) {
                continue;
            }
            String kind = TransferState.stringOr(cl, "kind", null);
            if (!"committed_r2".equals(kind)
                    && !"committed_oneshot".equals(kind)) {
                continue;
            }
            Map<String, Object> rmf = TransferState.child(r, "manifest");
            if (rmf == null) {
                continue;
            }
            if (thisExportId.equals(
                    TransferCommitCode.manifestHash(rmf))) {
                throw new IllegalArgumentException(
                        "this handover file was already taken in "
                                + "on this device");
            }
            if (mf.tailCheckpointHash != null
                    && mf.tailCheckpointHash.equals(
                            TransferState.stringOr(
                                    rmf, "tail_checkpoint_hash", null))
                    && mf.checkpointCount <= TransferState.longOr(
                            rmf, "checkpoint_count", -1)) {
                throw new IllegalArgumentException(
                        "the journal tip is not newer than one "
                                + "already taken in on this device");
            }
        }
        // F1 tombstone, sender side: an export this store
        // itself SENT is consumed just as surely as one it
        // received. The loop above only ever saw the receiver
        // track; the sender track (the open S1's manifest and
        // every sender_history entry — S2 of either closure
        // kind, and S0 cancels, whose files may equally have
        // left this device) was never consulted, so a device
        // could take in its OWN file and hold the identity
        // twice: once as its live journal, once promoted over
        // it. Self-intake is never a legitimate flow — a
        // return comes back as a NEW export from the other
        // device, with a different manifest.
        for (Map<String, Object> sent
                : consumedSenderManifests(state, deviceId)) {
            if (thisExportId.equals(TransferState.stringOr(
                    sent, "export_id", null))) {
                throw new IllegalArgumentException(
                        "this handover file was exported by "
                                + "this device itself");
            }
            if (mf.tailCheckpointHash != null
                    && mf.tailCheckpointHash.equals(
                            TransferState.stringOr(
                                    sent, "tail_checkpoint_hash", null))
                    && mf.checkpointCount <= TransferState.longOr(
                            sent, "checkpoint_count", -1)) {
                throw new IllegalArgumentException(
                        "the journal tip is not newer than one "
                                + "this device already sent onward");
            }
        }
        String packageSha = env.packageSha256();
        Map<String, Object> existing = state.receiver(deviceId, mf.generation);
        if (existing != null) {
            if (TransferState.child(existing, "closure") != null) {
                throw new IllegalArgumentException(
                        "generation is closed");
            }
            if (!packageSha.equals(TransferState.stringOr(
                    existing, "package_sha256", null))) {
                throw new IllegalArgumentException(
                        "a different package is already staged "
                                + "for this generation");
            }
            // Same package re-presented: fall through and
            // re-verify + re-run the boundary (step 6 preamble).
        }
        File root = TransferStore.stagingRoot(
                storeRoot, deviceId, mf.generation, packageSha);
        TransferStore.StagingRecord staging =
                TransferStore.loadStaging(root);
        boolean freshExpansion = (staging == null);
        if (staging != null && !packageSha.equals(staging.packageSha256)) {
            throw new IllegalArgumentException(
                    "staging root collision: different package");
        }
        if (staging != null && !staging.hasCommitKeyMaterial()
                && transferCode != null) {
            // M3 rescue, made real (and audit F7: counted,
            // timestamped, single-flight; audit M11: the
            // code is verified against this envelope before
            // anything is persisted — see
            // rescueCommitKeyIfAbsent).
            TransferStore.StagingRecord rescued =
                    rescueCommitKeyIfAbsent(root, transferCode,
                            env, fileBytes.length, wrapKey, clock);
            if (rescued != null) {
                staging = rescued;
            }
        }
        // Step 2 (fresh only): decrypt, persist seed +
        // confirm_secret wrapped into the staging record, and
        // create the attempt ledger with the record.
        if (freshExpansion) {
            // The code's canonical form is the hyphenless 12
            // characters; normalisation removes hyphens ONLY
            // (no case folding, design section 4.1).
            String code = TransferCodec.normalizeCode(transferCode);
            if (code == null) {
                throw new IllegalArgumentException(
                        "transfer code is not in canonical form");
            }
            // Design section 3.4 floor (H-11): the expansion
            // is the intake's large write; it does not start
            // if the projected free space breaches the floor.
            checkFreeFloor(root.getParentFile(),
                    "staging expansion");
            root.mkdirs();
            TransferIo.fsyncDir(root.getParentFile());
            // Final check round 3, H-07: the decrypted payload
            // is NEVER written to disk. The previous shape
            // decrypted the whole package into
            // .plaintext.tmp inside the staging root and
            // streamed the seed / confirm_secret entries out
            // of it through .seed.tmp / .secret.tmp: a process
            // death anywhere in that window left raw key
            // material on disk until a later recovery happened
            // to sweep it. The envelope is already held whole
            // in memory by this method (the package bytes
            // above), so decrypting in memory adds no new
            // whole-payload copy; the two small secret entries
            // are taken straight from the decrypted buffer,
            // wrapped, and zeroed, and only the journal and
            // archive entries are streamed to files.
            byte[] plain = null;
            try {
                plain = TransferPackage.decryptPayload(env, code);
                staging = expandPayloadInMemory(root, env, plain);
                // E4: keep the confirmation key derived from
                // the code with the staging (a one-way
                // derivative; the code itself is never
                // persisted), so the confirm can compute the
                // number the receiver displays afterwards.
                staging.commitKeyB64 = TransferCodec.b64Encode(
                        TransferCommitCode.deriveKey(code));
            } catch (Throwable t) {
                // Audit H-4 (legacy debris): builds before
                // H-07 could leave plaintext debris under the
                // fixed names; a failure in the window still
                // sweeps them (and recover() /
                // sweepExpansionDebris sweep again later).
                deleteExpansionDebris(root);
                throw t;
            } finally {
                if (plain != null) {
                    java.util.Arrays.fill(plain, (byte) 0);
                }
            }
            // Persist the confirmation key WRAPPED, like the
            // seed and confirm_secret beside it (audit Low:
            // it was the one secret-adjacent value the record
            // kept in plaintext).
            staging.sealCommitKey(wrapKey);
            TransferStore.saveStaging(root, staging);
            // Capacity (sections 3.4 / 4.3 rule (3)): ALL
            // generations' stagings count; if the cap is blown
            // the new staging is held back (closed by the
            // regular procedures or discarded unnotified),
            // never silently overflowed.
            if (!TransferStore.stagingWithinCap(storeRoot)) {
                TransferIo.deleteTree(root);
                throw new HoldException(
                        "staging capacity (1 GB) would be exceeded");
            }
            // The retained original package copy lives in the
            // staging root (section 4.1 placement).
            File copy = TransferStore.stagingPackageFile(root);
            copy.getParentFile().mkdirs();
            TransferIo.writeFileSync(copy, fileBytes);
        } else {
            // Resume: the retained copy must still reconstruct
            // the bound package hash (checked at the boundary).
            File copy = TransferStore.stagingPackageFile(root);
            if (!copy.exists()) {
                throw new InconsistencyException(
                        "staged package copy is missing on resume");
            }
        }
        // Steps 3-5: re-verify the staged entities in full.
        verifyStagedEntities(root, staging, env);
        // Step 6 head: the pre-notification synchronisation
        // boundary. A failure here leaves the staging
        // UNNOTIFIED — unless it was already notified, in which
        // case the failure surfaces as an inconsistency and
        // nothing is discarded as "unnotified".
        boolean wasNotified = staging.notified;
        try {
            runSyncBoundary(root);
        } catch (IOException e) {
            if (wasNotified) {
                throw new InconsistencyException(
                        "sync boundary re-run failed for a notified R1: "
                                + e.getMessage());
            }
            throw e;
        }
        // Steps 1-6-head are complete: the entities are
        // verified and the boundary has passed. Recording the
        // notified R1 is completeIntake's separate step (the
        // design puts the confirmation screen between them).
        IntakeResult r = new IntakeResult();
        r.deviceId = deviceId;
        r.generation = mf.generation;
        r.packageSha256 = packageSha;
        r.stagingRoot = root;
        JournalFile.Parsed parsed = JournalFile.parse(
                TransferStore.stagingJournal(root), deviceId);
        // E11: the manifest's count is an unverified claim
        // until here; a mismatch with the verified journal is
        // an error, never a display difference.
        if (parsed.count() != mf.checkpointCount) {
            throw new InconsistencyException(
                    "verified checkpoint count differs from the "
                            + "manifest");
        }
        r.checkpointCount = parsed.count();
        r.firstTimestampUnix = parsed.count() == 0 ? 0
                : parsed.checkpoints.get(0).timestampUnix;
        r.tailTimestampUnix = parsed.count() == 0 ? 0
                : parsed.last().timestampUnix;
        r.manifestHash = Hex.encode(DepinCore.sha256(
                TransferCodec.jcs(env.manifest.raw)));
        Map<String, Object> recNow = state.receiver(deviceId, mf.generation);
        r.alreadyNotified = recNow != null && TransferState.boolOr(
                recNow, "notified_r1", false);
        return r;
    }

    /**
     * Step 6 tail, as its own step (design section 4.3 step
     * 6): record notified R1 and the intake NONE in the SAME
     * transfer-record update, then fix the pre-promotion
     * placement manifest. The application shows the
     * confirmation screen (full values from the IntakeResult)
     * between {@link #stagePackage} and this call; aborting
     * there leaves the staging unnotified and discardable.
     * Idempotent for an already-notified generation.
     */
    public void completeIntake(String deviceId, long generation,
                               String packageSha256) throws Exception {
        File root = TransferStore.stagingRoot(
                storeRoot, deviceId, generation, packageSha256);
        TransferStore.StagingRecord staging =
                TransferStore.loadStaging(root);
        if (staging == null) {
            throw new HoldException("no staged package to complete");
        }
        TransferPackage.Envelope env = TransferPackage.parseEnvelope(
                TransferIo.readAll(TransferStore.stagingPackageFile(root),
                        TransferPackage.MAX_PACKAGE_BYTES));
        if (!env.packageSha256().equals(packageSha256)) {
            throw new InconsistencyException(
                    "staged copy does not reconstruct the bound hash");
        }
        // Audit F11: refuse BEFORE the notified R1 is
        // recorded — and so before any notice or receipt
        // can be issued — when the vault slot for this
        // generation already holds ANOTHER identity's key.
        // The slot is keyed by generation alone, shared by
        // every lineage in the store (audit F10), and the
        // promotion used to read an occupied slot as "this
        // identity's seed is already placed": the intake
        // completed, the sender closed and erased its side,
        // and the identity could never be materialised,
        // because its seed was never in the vault and the
        // copy under its generation belonged to someone
        // else. A slot holding THIS identity's key is the
        // legitimate re-receipt case and proceeds; an empty
        // slot proceeds. The refusal itself is a "not now",
        // not a "never" (audit round 5 correction): the
        // occupant may be mid-handover, and once its
        // closure frees the slot the SAME staging — left
        // un-notified by this refusal — completes through
        // continueIntake.
        //
        // Audit L24: the exception TYPE now says "not now"
        // too. Round 6 corrected the wording but left this
        // an InconsistencyException, while the same wait at
        // the entrance below (M14) and at planAndPromote's
        // head is a HoldException — and TransferErrorReport
        // classifies by type alone, so the same waiting
        // state surfaced as "inconsistent" on this route
        // and "hold" on the others. The refusal is a hold:
        // nothing has been recorded, and the same staging
        // completes once the slot frees. (The stop INSIDE
        // placePromotedSeed, after a plan is adopted, stays
        // an inconsistency: there a premise collapsed after
        // commitment, which is a different fact.)
        if (vault.has(TransferVault.receiverKey(generation))
                && !slotHoldsIdentityKey(generation, deviceId)) {
            throw new HoldException(
                    "the vault slot for generation " + generation
                            + " already holds another identity's "
                            + "key, so this package's seed cannot "
                            + "be placed while the slot is held; "
                            + "the staging is left un-notified "
                            + "and can be completed later, once "
                            + "the slot frees (audit F11)");
        }
        TransferState state = state();
        // Audit M14 (a): close the concurrent case at the
        // entrance. The slot refusal above fires only once
        // a copy is IN the slot, but two identities can be
        // notified for the same generation while the slot
        // is still empty — stagePackage checks duplicates
        // per identity only — and the second promotion then
        // raced the first one's placement. While ANOTHER
        // identity's notified record for this generation is
        // un-closed, this intake waits: a hold, because the
        // condition clears when that record closes. An
        // intake that is already notified itself is its own
        // continuation and proceeds.
        Map<String, Object> self =
                state.receiver(deviceId, generation);
        if (self == null || !TransferState.boolOr(
                self, "notified_r1", false)) {
            for (String other : state.identities().keySet()) {
                if (other.equals(deviceId)) {
                    continue;
                }
                Map<String, Object> orec =
                        state.receiver(other, generation);
                if (orec != null
                        && TransferState.boolOr(
                                orec, "notified_r1", false)
                        && TransferState.child(
                                orec, "closure") == null) {
                    throw new HoldException(
                            "another identity's generation "
                                    + generation + " intake is "
                                    + "still open in this store; "
                                    + "this intake waits until "
                                    + "it closes (audit M14)");
                }
            }
        }
        Map<String, Object> rec = state.receiverOrCreate(
                deviceId, generation);
        if (!TransferState.boolOr(rec, "notified_r1", false)) {
            rec.put("package_sha256", packageSha256);
            rec.put("staging_root", relativeStagingPath(
                    deviceId, generation, packageSha256));
            rec.put("manifest", env.manifest.raw);
            rec.put("notified_r1", true);
            Map<String, Object> none = new LinkedHashMap<>();
            none.put("transfer_generation", generation);
            none.put("package_sha256", packageSha256);
            none.put("receiver_install_id", state.installId());
            rec.put("intake_none", none);
            rec.put("intake_history", new ArrayList<>());
            state.save();
        }
        fixPrePromotionManifest(state, deviceId);
        if (!staging.notified) {
            staging.notified = true;
            TransferStore.saveStaging(root, staging);
        }
    }

    /** Resume an intake paused by the section 3.5 budget.
     *  Decryption already happened, so no transfer code is
     *  needed: re-verification runs against the current staged
     *  entities and the boundary is re-run before anything is
     *  treated as held. The section 3.5 cursor is honoured for
     *  the sequential re-verification (its entities were made
     *  durable by the expansion writes before the pause). */
    public IntakeResult continueIntake(String deviceId, long generation,
                                       String packageSha256)
            throws Exception {
        File root = TransferStore.stagingRoot(
                storeRoot, deviceId, generation, packageSha256);
        File copy = TransferStore.stagingPackageFile(root);
        if (!copy.exists()) {
            throw new HoldException("staged package copy missing");
        }
        return resumeStagedIntake(deviceId, generation, packageSha256);
    }

    private IntakeResult resumeStagedIntake(String deviceId,
                                            long generation,
                                            String packageSha256)
            throws Exception {
        TransferState state = state();
        Map<String, Object> rec = state.receiver(deviceId, generation);
        File root = TransferStore.stagingRoot(
                storeRoot, deviceId, generation, packageSha256);
        TransferStore.StagingRecord staging =
                TransferStore.loadStaging(root);
        if (staging == null) {
            throw new HoldException("staging record missing");
        }
        TransferPackage.Envelope env = TransferPackage.parseEnvelope(
                TransferIo.readAll(TransferStore.stagingPackageFile(root),
                        TransferPackage.MAX_PACKAGE_BYTES));
        verifyStagedEntities(root, staging, env);
        boolean wasNotified = staging.notified;
        try {
            runSyncBoundary(root);
        } catch (IOException e) {
            if (wasNotified) {
                throw new InconsistencyException(
                        "sync boundary re-run failed for a notified R1: "
                                + e.getMessage());
            }
            throw e;
        }
        if (rec == null || !TransferState.boolOr(rec, "notified_r1", false)) {
            completeIntake(deviceId, generation, packageSha256);
            staging = TransferStore.loadStaging(root);
        }
        IntakeResult r = new IntakeResult();
        r.deviceId = deviceId;
        r.generation = generation;
        r.packageSha256 = packageSha256;
        r.stagingRoot = root;
        JournalFile.Parsed parsed = JournalFile.parse(
                TransferStore.stagingJournal(root), deviceId);
        r.checkpointCount = parsed.count();
        r.firstTimestampUnix = parsed.count() == 0 ? 0
                : parsed.checkpoints.get(0).timestampUnix;
        r.tailTimestampUnix = parsed.count() == 0 ? 0
                : parsed.last().timestampUnix;
        r.manifestHash = Hex.encode(DepinCore.sha256(
                TransferCodec.jcs(env.manifest.raw)));
        r.alreadyNotified = wasNotified;
        return r;
    }

    private String relativeStagingPath(String deviceId, long generation,
                                       String packageSha256) {
        return TransferStore.STAGING_AREA_NAME + "/" + deviceId
                + "/" + generation + "/" + packageSha256;
    }

    private File stagingRootOf(Map<String, Object> rec,
                               String deviceId) {
        // The record stores its staging root as a store-relative
        // path at intake; that stored path is the binding.
        String rel = TransferState.stringOr(rec, "staging_root", null);
        if (rel != null) return new File(storeRoot, rel);
        throw new InconsistencyException(
                "receiver record has no staging root for " + deviceId);
    }

    /** Plaintext expansion debris names (audit H-4): the
     *  decrypted payload and the seed/secret plaintexts exist
     *  under these names only inside the expansion window.
     *  Any failure inside the window removes them, and
     *  recovery sweeps them from every staging root on disk —
     *  including roots whose staging record no longer exists
     *  (a process death inside the window leaves no record
     *  to clean up after). */
    private static final String[] EXPANSION_DEBRIS = {
            ".plaintext.tmp", ".seed.tmp", ".secret.tmp"};

    private static void deleteExpansionDebris(File root) {
        for (String name : EXPANSION_DEBRIS) {
            try {
                TransferIo.deleteTree(new File(root, name));
            } catch (Exception ignored) {
                // best effort; recovery sweeps again
            }
        }
    }

    /**
     * Sweep plaintext expansion debris (audit H-4 names)
     * from every staging root on disk, as a standalone
     * operation (final check round 3, H-07): before this
     * round the ONLY cleanup paths were the expansion
     * window's own finally, the exception path of a later
     * stagePackage, and recover() — and recover() was never
     * invoked from either app at runtime, so debris left by
     * a process death inside the (pre-H-07) expansion
     * window persisted across ordinary restarts and
     * operations indefinitely. The app layers now run this
     * ahead of every transfer operation, next to the
     * superseded-copy sweep. Post-H-07 intakes create no
     * debris; this cleans up after older builds and after
     * any interrupted legacy state. Returns notes naming
     * the roots swept (empty when nothing was due).
     */
    public List<String> sweepExpansionDebris() {
        List<String> notes = new ArrayList<>();
        for (File root : TransferStore.stagingRoots(storeRoot)) {
            boolean swept = false;
            for (String name : EXPANSION_DEBRIS) {
                if (new File(root, name).exists()) swept = true;
            }
            if (swept) {
                deleteExpansionDebris(root);
                notes.add("expansion debris swept from "
                        + root.getName());
            }
        }
        return notes;
    }

    /**
     * Expand the decrypted payload — held in memory by the
     * caller (H-07) — into the staging root, and build the
     * staging record. The seed and confirm_secret entries are
     * taken as byte arrays straight from the decrypted
     * buffer, wrapped under the vault wrap key and zeroed;
     * they are never written to disk in plaintext. The
     * journal and archive entries are streamed from the
     * buffer to their files, each with its SHA-256 computed
     * while copying (intake step 4's substance check uses
     * them).
     */
    private TransferStore.StagingRecord expandPayloadInMemory(
            File root, TransferPackage.Envelope env, byte[] plain)
            throws Exception {
        TransferPackage.Manifest mf = env.manifest;
        PayloadBytesReader reader = new PayloadBytesReader(plain);
        byte[] seed = null;
        byte[] secret = null;
        try {
            expectEntry(reader, "seed");
            seed = reader.copyContentBytes();
            expectEntry(reader, "confirm_secret");
            secret = reader.copyContentBytes();
            expectEntry(reader, "journal");
            String journalSha = reader.copyContentTo(
                    TransferStore.stagingJournal(root));
            for (TransferPackage.ArchiveEntry ae : mf.archives) {
                expectEntry(reader, ae.id);
                File dst = TransferStore.stagingArchive(root, ae.id);
                dst.getParentFile().mkdirs();
                String sha = reader.copyContentTo(dst);
                if (!sha.equals(ae.sha256)) {
                    throw new IllegalArgumentException(
                            "archive substance mismatch for " + ae.id
                                    + ": manifest sha256 does not match "
                                    + "the expanded file");
                }
            }
            if (reader.nextEntryName() != null) {
                throw new IllegalArgumentException(
                        "payload has trailing entries");
            }
            if (seed.length != 32 || secret.length != 16) {
                throw new IllegalArgumentException(
                        "payload seed/confirm_secret length mismatch");
            }
            if (!mf.deviceId.equals(
                    DepinCore.deviceIdFromPubkey(
                            Ed25519.publickeyFromSeed(seed)))) {
                throw new IllegalArgumentException(
                        "payload seed does not derive the manifest identity");
            }
            TransferStore.StagingRecord sr =
                    new TransferStore.StagingRecord();
            sr.deviceId = mf.deviceId;
            sr.generation = mf.generation;
            sr.packageSha256 = env.packageSha256();
            sr.manifest = mf.raw;
            sr.wrappedSeedB64 = TransferCodec.b64Encode(
                    wrapToBytes(wrapKey, seed));
            sr.wrappedSecretB64 = TransferCodec.b64Encode(
                    wrapToBytes(wrapKey, secret));
            sr.journalSha256 = journalSha;
            for (TransferPackage.ArchiveEntry ae : mf.archives) {
                sr.archiveSha256.put(ae.id, ae.sha256);
            }
            sr.notified = false;
            sr.nextAttempt = 0;
            sr.verifiedCount = 0;
            return sr;
        } finally {
            if (seed != null) {
                java.util.Arrays.fill(seed, (byte) 0);
            }
            if (secret != null) {
                java.util.Arrays.fill(secret, (byte) 0);
            }
        }
    }

    private static void expectEntry(
            PayloadBytesReader reader, String name)
            throws IOException {
        String got = reader.nextEntryName();
        if (!name.equals(got)) {
            throw new IllegalArgumentException(
                    "payload entry order mismatch: expected " + name
                            + ", got " + got);
        }
    }

    /**
     * Reads payload entries from a decrypted payload held in
     * memory (H-07): the same framing and the same bounds
     * validation as the file reader this replaces —
     * [4-byte name length][name][8-byte content length]
     * [content], big-endian — with the two differences the
     * in-memory form exists for: small entries can be taken
     * as byte arrays without touching disk at all, and no
     * plaintext file ever exists.
     */
    private static final class PayloadBytesReader {
        private final byte[] data;
        private int pos = 0;
        private int pendingContentLength = -1;

        PayloadBytesReader(byte[] data) {
            this.data = data;
        }

        String nextEntryName() throws IOException {
            if (pos == data.length) return null;
            if (data.length - pos < 4) {
                throw new IOException("payload truncated (name)");
            }
            int nameLen = ((data[pos] & 0xff) << 24)
                    | ((data[pos + 1] & 0xff) << 16)
                    | ((data[pos + 2] & 0xff) << 8)
                    | (data[pos + 3] & 0xff);
            pos += 4;
            if (nameLen < 0 || nameLen > data.length - pos) {
                throw new IOException("payload name length invalid");
            }
            String name = new String(data, pos, nameLen,
                    StandardCharsets.UTF_8);
            pos += nameLen;
            if (data.length - pos < 8) {
                throw new IOException(
                        "payload truncated (content length)");
            }
            long contentLen = 0;
            for (int i = 0; i < 8; i++) {
                contentLen = (contentLen << 8) | (data[pos + i] & 0xffL);
            }
            pos += 8;
            if (contentLen < 0 || contentLen > data.length - pos) {
                throw new IOException("payload content length invalid");
            }
            pendingContentLength = (int) contentLen;
            return name;
        }

        /** Take the current entry's content as a fresh byte
         *  array (the caller zeroes it after use). */
        byte[] copyContentBytes() throws IOException {
            if (pendingContentLength < 0) {
                throw new IOException("no pending payload entry");
            }
            byte[] out = java.util.Arrays.copyOfRange(
                    data, pos, pos + pendingContentLength);
            pos += pendingContentLength;
            pendingContentLength = -1;
            return out;
        }

        /** Stream the current entry's content to {@code out}
         *  (fsync included), returning its SHA-256 hex. */
        String copyContentTo(File out) throws IOException {
            if (pendingContentLength < 0) {
                throw new IOException("no pending payload entry");
            }
            java.security.MessageDigest md;
            try {
                md = java.security.MessageDigest.getInstance("SHA-256");
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            File parent = out.getAbsoluteFile().getParentFile();
            if (parent != null) parent.mkdirs();
            try (java.io.FileOutputStream fos =
                    new java.io.FileOutputStream(out)) {
                fos.write(data, pos, pendingContentLength);
                md.update(data, pos, pendingContentLength);
                fos.getFD().sync();
            }
            pos += pendingContentLength;
            pendingContentLength = -1;
            return Hex.encode(md.digest());
        }
    }

    /** Wrap encoding for staging-record material: the
     *  AesGcmSeedBox iv (12 bytes) followed by the ciphertext,
     *  Base64'd into the record by the caller. */
    static byte[] wrapToBytes(SecretKey key, byte[] plain)
            throws GeneralSecurityException {
        AesGcmSeedBox.Wrapped w = AesGcmSeedBox.wrap(key, plain);
        byte[] out = new byte[w.iv.length + w.ciphertext.length];
        System.arraycopy(w.iv, 0, out, 0, w.iv.length);
        System.arraycopy(w.ciphertext, 0, out, w.iv.length,
                w.ciphertext.length);
        return out;
    }

    static byte[] unwrapFromBytes(SecretKey key, byte[] blob)
            throws GeneralSecurityException {
        if (blob.length < 13) {
            throw new IllegalArgumentException("wrapped blob too short");
        }
        byte[] iv = new byte[12];
        byte[] ct = new byte[blob.length - 12];
        System.arraycopy(blob, 0, iv, 0, 12);
        System.arraycopy(blob, 12, ct, 0, ct.length);
        return AesGcmSeedBox.unwrap(key, new AesGcmSeedBox.Wrapped(iv, ct));
    }

    byte[] unwrapSeed(TransferStore.StagingRecord sr) throws Exception {
        if (sr.seedErased()) {
            throw new IllegalStateException(
                    "the staged seed was invalidated by the "
                            + "superseded-copy sweep (audit M10)");
        }
        return unwrapFromBytes(wrapKey, TransferCodec.b64DecodeStrict(
                sr.wrappedSeedB64, "staging.wrapped_seed"));
    }

    byte[] unwrapSecret(TransferStore.StagingRecord sr) throws Exception {
        return unwrapFromBytes(wrapKey, TransferCodec.b64DecodeStrict(
                sr.wrappedSecretB64, "staging.wrapped_secret"));
    }

    /**
     * Intake steps 3-5 against the CURRENT staged entities:
     * header identity agreement, full signature/chain
     * verification (JournalFile.parse), the section 3.5 sliced
     * sequential re-verification to completion, archive header
     * verification, archive substance comparison (done at
     * expansion for fresh intakes; re-checked here from the
     * files), tail + anchor agreement, and the direct
     * checkpoint_count comparison.
     */
    private void verifyStagedEntities(File root,
                                      TransferStore.StagingRecord sr,
                                      TransferPackage.Envelope env)
            throws Exception {
        TransferPackage.Manifest mf = env.manifest;
        File journalFile = TransferStore.stagingJournal(root);
        byte[] journalBytes = TransferIo.readAll(
                journalFile, TransferPackage.MAX_PACKAGE_BYTES);
        // File-level hash must equal what expansion recorded.
        String fileSha = Hex.encode(DepinCore.sha256(journalBytes));
        if (!fileSha.equals(sr.journalSha256)) {
            throw new IllegalArgumentException(
                    "staged journal file changed since expansion");
        }
        // Final check round 3, H-10: the full parse verifies
        // every entry signature — O(n) Ed25519 verifications
        // OUTSIDE the section 3.5 budget ledger — and this
        // method re-runs on every stagePackage call, which
        // the app layer re-issues once per wait-obligation
        // pause (dozens of times for a production journal;
        // the audit measured the unledgered share at ~46% of
        // stage CPU). The parse is now paid in full ONCE per
        // distinct file content: when the file's SHA-256
        // equals the value whose full verification already
        // completed (recorded in the staging record), the
        // re-parse skips only the signature step — structure,
        // sequence, linkage and every checkpoint_hash are
        // still recomputed, and the sliced ChainVerifier
        // below still re-derives the whole chain under the
        // budget on every run. Any byte change changes the
        // SHA and forces a full parse again.
        boolean journalFullVerification =
                !fileSha.equals(sr.journalSigVerifiedSha256);
        JournalFile.Parsed parsed = JournalFile.parse(
                journalBytes, mf.deviceId, journalFullVerification);
        if (journalFullVerification) {
            // Record the sig-verified hash NOW, before the
            // sliced chain phase below: a budget pause there
            // makes the app layer re-issue stagePackage, and
            // without this early record every re-issue paid
            // the full signature parse again (the audit's
            // R3StageLoop shape: dozens of calls for one
            // production journal). The record claims only
            // what the full parse just proved — these exact
            // bytes' entry signatures verify. It claims
            // nothing about the sequential chain, which the
            // sliced verifier still re-derives from its
            // cursor on every run.
            sr.journalSigVerifiedSha256 = fileSha;
            TransferStore.saveStaging(root, sr);
        }
        // Final check round 3, H-08: refuse a staged journal
        // whose checkpoints name an iteration count other
        // than the expected one, BEFORE the sliced
        // re-verification below starts. Checkpoint.fromMap
        // accepts iterations up to 2^31-1, and one
        // sequentialChain recomputation at that value is an
        // uninterruptible multi-minute burn inside a single
        // slice — on a value the sender chooses freely, paid
        // by the receiver. The production count is a
        // protocol constant (DepinCore.PRODUCTION_ITERATIONS;
        // the app layers install it, the harness installs
        // its fixture value), so an honest package never
        // trips this gate.
        if (expectedIterations > 0) {
            for (DepinCore.Checkpoint c : parsed.checkpoints) {
                if (c.iterations != expectedIterations) {
                    throw new IllegalArgumentException(
                            "staged checkpoint names iterations="
                                    + c.iterations + "; this intake "
                                    + "accepts only "
                                    + expectedIterations
                                    + " (final check round 3, H-08)");
                }
            }
        }
        // Section 3.5 sliced sequential re-verification.
        Reverification.BudgetLedger budget =
                new Reverification.BudgetLedger(storeRoot, timeSource);
        Reverification.Cursor cursor =
                Reverification.Cursor.load(root, sr.packageSha256);
        if (cursor == null) {
            cursor = Reverification.Cursor.fresh(root, sr.packageSha256);
        }
        Reverification.ChainVerifier verifier =
                new Reverification.ChainVerifier(
                        parsed, cursor, budget, timeSource);
        long sessionStart = clock.monoNanos();
        while (true) {
            Reverification.SliceOutcome outcome = verifier.step();
            if (outcome == Reverification.SliceOutcome.DONE) break;
            if (outcome == Reverification.SliceOutcome.MISMATCH) {
                throw new IllegalArgumentException(
                        "sequential chain re-verification mismatch");
            }
            if (clock.monoNanos() - sessionStart
                    > SESSION_WALL_BUDGET_NANOS) {
                throw new VerificationPausedException(
                        mf.deviceId, mf.generation, sr.packageSha256,
                        "verification session wall budget exhausted");
            }
            if (outcome == Reverification.SliceOutcome.PAUSED_BUDGET
                    || outcome == Reverification.SliceOutcome.PAUSED_WAIT) {
                throw new VerificationPausedException(
                        mf.deviceId, mf.generation, sr.packageSha256,
                        "verification paused by the section 3.5 budget: "
                                + outcome);
            }
        }
        sr.verifiedCount = parsed.count();
        // Archives: header verification + substance hashes.
        // The H-10 discipline applies per archive as well:
        // full signature verification once per distinct file
        // content (keyed by the archive's SHA-256), the
        // light re-parse for byte-identical re-runs.
        for (TransferPackage.ArchiveEntry ae : mf.archives) {
            File af = TransferStore.stagingArchive(root, ae.id);
            byte[] archiveBytes = TransferIo.readAll(
                    af, TransferPackage.MAX_PACKAGE_BYTES);
            String sha = Hex.encode(DepinCore.sha256(archiveBytes));
            if (!sha.equals(ae.sha256)
                    || !sha.equals(sr.archiveSha256.get(ae.id))) {
                throw new IllegalArgumentException(
                        "archive substance mismatch for " + ae.id);
            }
            boolean archiveFullVerification = !sha.equals(
                    sr.archiveSigVerifiedSha256.get(ae.id));
            JournalFile.Parsed ap = JournalFile.parse(
                    archiveBytes, mf.deviceId,
                    archiveFullVerification);
            if (ap.count() != ae.count) {
                throw new IllegalArgumentException(
                        "archive count mismatch for " + ae.id);
            }
            String tail = (ap.last() == null) ? null
                    : Hex.encode(ap.last().checkpointHash);
            if (tail == null || !tail.equals(ae.tailCheckpointHash)) {
                throw new IllegalArgumentException(
                        "archive tail mismatch for " + ae.id);
            }
            if (archiveFullVerification) {
                // Recorded per archive, as soon as this
                // archive's own checks have passed (the same
                // early-record discipline as the journal).
                sr.archiveSigVerifiedSha256.put(ae.id, sha);
                TransferStore.saveStaging(root, sr);
            }
        }
        // Step 5: tail + anchor + direct count comparison.
        DepinCore.Checkpoint tail = parsed.last();
        if (tail == null) {
            throw new IllegalArgumentException(
                    "staged journal has no checkpoints");
        }
        if (tail.seq != mf.tailSeq
                || !Hex.encode(tail.checkpointHash).equals(
                        mf.tailCheckpointHash)
                || tail.timestampUnix != mf.tailTimestampUnix) {
            throw new IllegalArgumentException(
                    "journal tail does not match the manifest");
        }
        if (parsed.count() != mf.checkpointCount) {
            throw new IllegalArgumentException(
                    "checkpoint_count does not match the journal");
        }
        String anchor = JournalFile.anchorBundleHash(
                parsed, mf.anchorCreatedAtUnix, mf.anchorParams);
        if (!anchor.equals(mf.anchorBundleHash)) {
            throw new IllegalArgumentException(
                    "anchor bundle hash does not match the manifest");
        }
    }

    /**
     * The pre-notification synchronisation boundary (intake
     * step 6 head, rev.23): fsync every verified expanded file
     * (the live journal at the staging root and every archive),
     * then the parent directories, with ALL ancestors fsynced
     * unconditionally; fsync the retained package copy and its
     * directory; finally confirm the copy still reconstructs
     * the bound package hash. A failed fsync is never used as
     * durability evidence (a later successful retry of the
     * same fsync does not retroactively prove the earlier
     * write) — the caller treats failure per the step-6 rules.
     */
    private void runSyncBoundary(File root) throws IOException {
        File journal = TransferStore.stagingJournal(root);
        TransferIo.fsync(journal);
        File archivesDir = new File(root, "archives");
        File[] archives = archivesDir.listFiles();
        if (archives != null) {
            for (File a : archives) {
                if (a.isFile()) TransferIo.fsync(a);
            }
        }
        File pkg = TransferStore.stagingPackageFile(root);
        TransferIo.fsync(pkg);
        TransferIo.fsyncDir(root);
        if (archivesDir.exists()) TransferIo.fsyncDir(archivesDir);
        TransferIo.fsyncDir(pkg.getParentFile());
        TransferIo.fsyncAncestors(storeRoot, root);
        // The copy must still reconstruct the bound hash.
        TransferStore.StagingRecord sr = TransferStore.loadStaging(root);
        TransferPackage.Envelope env = TransferPackage.parseEnvelope(
                TransferIo.readAll(pkg, TransferPackage.MAX_PACKAGE_BYTES));
        if (!env.packageSha256().equals(sr.packageSha256)) {
            throw new IOException(
                    "retained package copy no longer reconstructs "
                            + "the bound package hash");
        }
    }

    /** Fix the pre-promotion placement manifest (live-manifest
     *  update point (1)): durable BEFORE the notice is
     *  presented, for every reception. An existing manifest
     *  that still matches the live placement is left as is;
     *  a missing one is created from the current placement
     *  with origin "existing". When the live placement belongs
     *  to a FOREIGN identity (audit C-2; the integrated app's
     *  shadow), the receiving identity's pre-promotion
     *  placement is the EMPTY placement by rule (section 7.2
     *  supplement / F-21): a missing manifest is fixed as
     *  empty rather than as a snapshot of the foreign files,
     *  and an existing manifest — which truthfully describes
     *  the foreign placement until the promotion-time
     *  quarantine displaces it — is left untouched. */
    private void fixPrePromotionManifest(TransferState state,
                                         String deviceId)
            throws IOException {
        TransferStore.LiveManifest lm =
                TransferStore.loadLiveManifest(storeRoot);
        if (lm == null) {
            Map<String, Object> active = state.active();
            String activeId = active == null ? null
                    : TransferState.stringOr(active, "device_id", null);
            if (activeId != null && !activeId.equals(deviceId)) {
                lm = new TransferStore.LiveManifest();
                lm.origin = "existing";
                lm.sequence = 1;
                TransferStore.saveLiveManifest(storeRoot, lm);
                return;
            }
            lm = TransferStore.snapshotLive(storeRoot, "existing", 1);
            TransferStore.saveLiveManifest(storeRoot, lm);
        }
    }

    // ---------------- notice (step 6 tail / section 5.3) ----------------

    /**
     * Build (or re-present) the staging notice for a notified
     * R1. Initial presentation and re-presentation pass through
     * the same gate: the transfer record's notified-R1 record
     * is adopted and the pre-promotion manifest is fixed.
     */
    public Map<String, Object> buildNotice(String deviceId,
                                           long generation)
            throws Exception {
        TransferState state = state();
        if (!state.adopt()) state = state();
        Map<String, Object> rec = state.receiver(deviceId, generation);
        if (rec == null || !TransferState.boolOr(rec, "notified_r1", false)) {
            throw new HoldException(
                    "no notified R1 for generation " + generation);
        }
        fixPrePromotionManifest(state, deviceId);
        File root = stagingRootOf(rec, deviceId);
        TransferStore.StagingRecord sr =
                TransferStore.adoptStaging(root);
        if (sr == null) {
            throw new InconsistencyException(
                    "notified R1 without a staging record");
        }
        byte[] secret = unwrapSecret(sr);
        return TransferPackage.buildNotice(generation,
                TransferState.stringOr(rec, "package_sha256", null),
                state.installId(), secret);
    }

    // ---------------- permit intake (step 7) ----------------

    public enum PermitIntakeOutcome {
        COMMITTED, ALREADY_COMMITTED, EXPIRED_LATCHED
    }

    /**
     * Step 7: take in a confirmation permit. Two phases —
     * preparation (the full value is saved into the staging
     * record and fsynced) then commit (the intake history
     * update in the transfer record is adopted). The intake
     * state of a generation is DERIVED from its intake history:
     * NONE holds exactly while zero permits are committed.
     */
    public PermitIntakeOutcome intakePermit(Map<String, Object> msg)
            throws Exception {
        long gen = Json.asLong(msg.get("transfer_generation"));
        String pkg = Json.asString(msg.get("package_sha256"));
        TransferState state = state();
        if (!state.adopt()) state = state();
        // Locate the target generation by (generation, package).
        String deviceId = null;
        Map<String, Object> rec = null;
        for (String did : state.identities().keySet()) {
            Map<String, Object> r = state.receiver(did, gen);
            if (r != null && pkg.equals(TransferState.stringOr(
                    r, "package_sha256", null))) {
                deviceId = did;
                rec = r;
                break;
            }
        }
        if (rec == null) {
            throw new HoldException(
                    "no staged generation matches this permit");
        }
        if (TransferState.child(rec, "closure") != null) {
            throw new IllegalArgumentException(
                    "generation is closed; permit refused on the closure");
        }
        Map<String, Object> trial = TransferState.child(rec, "trial");
        if (trial != null && !isTerminalTrial(trial)) {
            throw new HoldException(
                    "an unconverged trial exists; no new permit intake");
        }
        if ("pending".equals(TransferState.stringOr(rec, "restoration", null))) {
            throw new HoldException("restoration is pending");
        }
        Map<String, Object> manifest = TransferState.child(rec, "manifest");
        byte[] pubkey = TransferCodec.hexBytes(
                TransferState.stringOr(manifest, "pubkey", null),
                32, "manifest.pubkey");
        TransferPackage.validatePermit(msg, pubkey);
        if (!state.installId().equals(TransferState.stringOr(
                msg, "receiver_install_id", null))) {
            throw new IllegalArgumentException(
                    "permit is addressed to another installation");
        }
        String hash = TransferCodec.fullValueHash(msg);
        // An ended trial's bound permit, re-presented: refused
        // as "already ended" (the generation does NOT close).
        if (trial != null && isTerminalTrial(trial)
                && hash.equals(TransferState.stringOr(
                        trial, "permit_hash", null))) {
            throw new IllegalArgumentException(
                    "this permit already ended with its trial");
        }
        List<Object> history = TransferState.listOrCreate(
                rec, "intake_history");
        for (Object o : history) {
            if (o instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> e = (Map<String, Object>) o;
                if (hash.equals(e.get("hash"))
                        && "committed".equals(e.get("state"))) {
                    return PermitIntakeOutcome.ALREADY_COMMITTED;
                }
            }
        }
        File root = stagingRootOf(rec, deviceId);
        TransferStore.StagingRecord sr = TransferStore.loadStaging(root);
        if (sr == null) {
            throw new InconsistencyException(
                    "permit intake without a staging record");
        }
        // Phase 1 (prepare): full value into the staging record.
        sr.permitFull = msg;
        TransferStore.saveStaging(root, sr);
        // Phase 2 (commit): the intake-history update.
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("hash", hash);
        entry.put("state", "committed");
        history.add(entry);
        state.save();
        // Clock evaluation at intake: an OBSERVED expiry is
        // latched under the permit's hash. An unreadable clock
        // leaves the permit committed and R1 waiting — the
        // step 8-5 judgement reads remain authoritative.
        long expWall = Json.asLong(msg.get("expires_at_unix"));
        long expFin = Json.asLong(msg.get("expires_at_finalized"));
        try {
            long f = finalizedSource.readFinalized();
            if (f >= expFin) {
                state.recordLatch(deviceId, hash, "finalized", f);
                return PermitIntakeOutcome.EXPIRED_LATCHED;
            }
            long w = clock.wallUnix();
            if (w >= expWall) {
                state.recordLatch(deviceId, hash, "wall", w);
                return PermitIntakeOutcome.EXPIRED_LATCHED;
            }
        } catch (Exception unreadable) {
            // wait in R1 (see above)
        }
        return PermitIntakeOutcome.COMMITTED;
    }

    /** Terminal-trial test, shared with TransferInit's
     *  writer gate (F-06: the gate must recognise an
     *  unfinished trial that has displaced the live
     *  placement). */
    static boolean isTerminalTrial(Map<String, Object> trial) {
        String stage = TransferState.stringOr(trial, "stage", null);
        return "cancel_complete".equals(stage)
                || "refused".equals(stage)
                || "confirmed".equals(stage)
                || "superseded".equals(stage)
                || "orphaned".equals(stage);
    }

    /** Ensure a prepared permit is committed in the intake
     *  history (the crash-between-phases case), returning its
     *  full-value hash. */
    private String ensurePermitCommitted(TransferState state,
                                         Map<String, Object> rec,
                                         TransferStore.StagingRecord sr)
            throws IOException {
        if (sr.permitFull == null) {
            throw new HoldException("no permit has been taken in");
        }
        String hash = TransferCodec.fullValueHash(sr.permitFull);
        List<Object> history = TransferState.listOrCreate(
                rec, "intake_history");
        for (Object o : history) {
            if (o instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> e = (Map<String, Object>) o;
                if (hash.equals(e.get("hash"))
                        && "committed".equals(e.get("state"))) {
                    return hash;
                }
            }
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("hash", hash);
        entry.put("state", "committed");
        history.add(entry);
        state.save();
        return hash;
    }

    /**
     * Supplement intake (recovery-only, design section 4.3
     * step 7 supplement): re-presented permit whose signature,
     * generation, fixed destination, package and canonical
     * hash (== the trial's bound hash H) are all verified; the
     * full value is restored for the EXISTING trial. No new
     * clock judgement is made in this step.
     */
    public void supplyPermitForRecovery(String deviceId, long generation,
                                        Map<String, Object> msg)
            throws Exception {
        TransferState state = state();
        Map<String, Object> rec = state.receiver(deviceId, generation);
        if (rec == null) throw new HoldException("no such generation");
        Map<String, Object> trial = TransferState.child(rec, "trial");
        if (trial == null) {
            throw new HoldException("no trial to supplement");
        }
        String boundHash = TransferState.stringOr(trial, "permit_hash", null);
        Map<String, Object> manifest = TransferState.child(rec, "manifest");
        byte[] pubkey = TransferCodec.hexBytes(
                TransferState.stringOr(manifest, "pubkey", null),
                32, "manifest.pubkey");
        TransferPackage.validatePermit(msg, pubkey);
        if (Json.asLong(msg.get("transfer_generation")) != generation
                || !TransferState.stringOr(rec, "package_sha256", "")
                        .equals(Json.asString(msg.get("package_sha256")))
                || !state.installId().equals(TransferState.stringOr(
                        msg, "receiver_install_id", null))) {
            throw new IllegalArgumentException(
                    "supplement permit does not match the trial binding");
        }
        if (!boundHash.equals(TransferCodec.fullValueHash(msg))) {
            throw new IllegalArgumentException(
                    "supplement permit hash differs from the bound hash");
        }
        File root = stagingRootOf(rec, deviceId);
        TransferStore.StagingRecord sr = TransferStore.loadStaging(root);
        sr.permitFull = msg;
        TransferStore.saveStaging(root, sr);
        ensurePermitCommitted(state, rec, sr);
    }

    // ---------------- confirmation (step 8) ----------------

    /**
     * Run step 8 for a staged generation with a committed
     * permit: CHECK-1, trial start (the attempt-ledger update
     * is the commit point), plan construction and adoption,
     * seed vaulting, promotion, the step 8-5 judgement, and
     * materialisation — or the step 9 cancellation when the
     * judgement cannot be taken.
     */
    public void confirmTrial(String deviceId, long generation)
            throws Exception {
        TransferState state = state();
        if (!state.adopt()) state = state();
        Map<String, Object> rec = state.receiver(deviceId, generation);
        if (rec == null || !TransferState.boolOr(rec, "notified_r1", false)) {
            throw new HoldException("no notified R1 for this generation");
        }
        if (TransferState.child(rec, "closure") != null) {
            throw new HoldException("generation is closed");
        }
        // E5 (strict side): a generation a one-shot confirm
        // has taken over is never confirmed through the strict
        // flow.
        if ("oneshot".equals(TransferState.stringOr(
                rec, "mode", null))) {
            throw new HoldException(
                    "this generation was taken over by the "
                            + "one-shot flow");
        }
        Map<String, Object> trial = TransferState.child(rec, "trial");
        if (trial != null && !isTerminalTrial(trial)) {
            throw new HoldException(
                    "a trial is in progress; run recovery");
        }
        if (trial != null) {
            // The previous trial ended (e.g. cancellation
            // complete): archive it and let a NEW trial start
            // under a new attempt ID with the newly committed
            // permit (design section 4.3 step 7: after a trial
            // converges by cancellation, a new permit may be
            // accepted under a new trial ID).
            TransferState.listOrCreate(rec, "past_trials").add(trial);
            rec.remove("trial");
            trial = null;
        }
        // CHECK-1: the old S1 (as sender, same identity, this
        // installation) must be closed, and the gates the
        // design puts before any live change must be clear.
        if (state.sender(deviceId) != null) {
            throw new HoldException(
                    "CHECK-1: an S1 for this identity is still open "
                            + "on this installation");
        }
        for (long g : state.receiverGenerations(deviceId)) {
            Map<String, Object> r = state.receiver(deviceId, g);
            if ("pending".equals(
                    TransferState.stringOr(r, "restoration", null))) {
                throw new HoldException(
                        "CHECK-1: restoration pending for generation " + g);
            }
        }
        // Final check round 1, F-07: an unfinished trial of
        // an EARLIER generation of this same identity blocks
        // this confirm, and a generation older than the
        // committed maximum can never be confirmed at all.
        // Without the first, the earlier trial's late
        // recovery materialised OVER this confirm's commit
        // (held_generation and the active binding rewound to
        // the older generation); without the second, a
        // merely-staged older generation could be confirmed
        // after a newer one committed. The materialise choke
        // point carries the same guard as defence in depth.
        if (generation < state.maxCommittedGeneration(deviceId)) {
            throw new HoldException(
                    "a newer generation of this identity is "
                            + "already committed here; generation "
                            + generation + " can no longer be "
                            + "confirmed (F-07)");
        }
        for (long g : state.receiverGenerations(deviceId)) {
            if (g == generation) {
                continue;
            }
            Map<String, Object> r = state.receiver(deviceId, g);
            Map<String, Object> t = TransferState.child(r, "trial");
            if (t != null && !isTerminalTrial(t)
                    && TransferState.child(r, "closure") == null) {
                throw new HoldException(
                        "an earlier generation (" + g + ") of "
                                + "this identity has an unfinished "
                                + "trial; run recovery first (F-07)");
            }
        }
        File root = stagingRootOf(rec, deviceId);
        TransferStore.StagingRecord sr =
                TransferStore.adoptStaging(root);
        if (sr == null) {
            throw new InconsistencyException("staging record missing");
        }
        String permitHash = ensurePermitCommitted(state, rec, sr);
        if (state.hasLatch(deviceId, permitHash)) {
            throw new HoldException(
                    "the permit's expiry was already observed (latched)");
        }
        // Trial start: the attempt-ledger update IS the start
        // commit point.
        long n = sr.nextAttempt;
        String trialId = generation + "-" + n;
        Map<String, Object> attempt = new LinkedHashMap<>();
        attempt.put("attempt_id", trialId);
        attempt.put("generation", generation);
        attempt.put("permit_hash", permitHash);
        attempt.put("stage_summary", "started");
        sr.currentAttempt = attempt;
        sr.nextAttempt = n + 1;
        TransferStore.saveStaging(root, sr);
        trial = new LinkedHashMap<>();
        trial.put("trial_id", trialId);
        trial.put("generation", generation);
        trial.put("package_sha256",
                TransferState.stringOr(rec, "package_sha256", null));
        trial.put("permit_hash", permitHash);
        trial.put("stage", "started");
        Map<String, Object> judgement = new LinkedHashMap<>();
        judgement.put("kind", "undecided");
        trial.put("judgement", judgement);
        rec.put("trial", trial);
        state.save();
        // Plan, promote, judge.
        Map<String, Object> plan = planAndPromote(state, rec, sr, trial);
        judgementStep(state, rec, sr, trial, plan);
    }

    /**
     * Confirm a staged generation in one-shot mode (simple
     * handover; design note in the integration progress file,
     * section 10): the same step-8 machinery as confirmTrial —
     * CHECK-1, trial start, plan, promotion, judgement,
     * materialisation — with the permit replaced by the local
     * user's all-values confirmation, which the UI has already
     * taken before calling this. There is no notice, permit,
     * receipt or ack in this mode: the trial carries mode
     * "oneshot" and the sentinel permit hash "oneshot" (the
     * trial/plan/judgement fields keep their shapes; no latch
     * or expiry machinery ever reads the sentinel as a hash of
     * a real permit). The promotion, the foreign-placement
     * quarantine and the recovery classification are shared
     * with the strict mode unchanged.
     */
    public void confirmOneshot(String deviceId, long generation)
            throws Exception {
        TransferState state = state();
        if (!state.adopt()) state = state();
        Map<String, Object> rec = state.receiver(deviceId, generation);
        if (rec == null || !TransferState.boolOr(rec, "notified_r1", false)) {
            throw new HoldException("no notified R1 for this generation");
        }
        if (TransferState.child(rec, "closure") != null) {
            throw new HoldException("generation is closed");
        }
        // E8: a DIFFERENT live identity is never displaced by
        // a one-shot confirm. The strict protocol quarantines
        // the foreign placement by explicit design; the simple
        // mode refuses instead — displacement, if wanted, is a
        // separate explicit act.
        Map<String, Object> activeB = state.active();
        if (activeB != null) {
            String activeDevice = TransferState.stringOr(
                    activeB, "device_id", null);
            if (activeDevice != null
                    && !activeDevice.equals(deviceId)) {
                throw new HoldException(
                        "a different identity is live on this device");
            }
        }
        // E5: the modes do not cross mid-flight. A generation
        // with a committed permit is inside the strict flow,
        // and an existing strict trial (trial records carry
        // no mode; one-shot trials carry "oneshot") blocks a
        // one-shot confirm whatever its stage.
        Object ih = rec.get("intake_history");
        if (ih instanceof List) {
            for (Object o : (List<?>) ih) {
                if (o instanceof Map
                        && "committed".equals(
                                ((Map<?, ?>) o).get("state"))) {
                    throw new HoldException(
                            "a permit is already committed for this "
                                    + "generation (strict flow in "
                                    + "progress)");
                }
            }
        }
        Map<String, Object> trial = TransferState.child(rec, "trial");
        if (trial != null && !"oneshot".equals(
                TransferState.stringOr(trial, "mode", null))) {
            throw new HoldException(
                    "the existing trial belongs to the strict flow");
        }
        if (trial != null && !isTerminalTrial(trial)) {
            throw new HoldException(
                    "a trial is in progress; run recovery");
        }
        if (trial != null) {
            // The previous trial ended (e.g. cancellation
            // complete): archive it and let a NEW trial start,
            // exactly as in confirmTrial.
            TransferState.listOrCreate(rec, "past_trials").add(trial);
            rec.remove("trial");
            trial = null;
        }
        // CHECK-1, identical to the strict confirm: the old S1
        // (as sender, same identity, this installation) must
        // be closed, and no restoration may be pending.
        if (state.sender(deviceId) != null) {
            throw new HoldException(
                    "CHECK-1: an S1 for this identity is still open "
                            + "on this installation");
        }
        for (long g : state.receiverGenerations(deviceId)) {
            Map<String, Object> r = state.receiver(deviceId, g);
            if ("pending".equals(
                    TransferState.stringOr(r, "restoration", null))) {
                throw new HoldException(
                        "CHECK-1: restoration pending for generation " + g);
            }
        }
        // F-07, as in the strict confirm: a stale generation
        // cannot be confirmed over a newer commit, and an
        // earlier generation's unfinished trial blocks this
        // confirm until recovery has dealt with it.
        if (generation < state.maxCommittedGeneration(deviceId)) {
            throw new HoldException(
                    "a newer generation of this identity is "
                            + "already committed here; generation "
                            + generation + " can no longer be "
                            + "confirmed (F-07)");
        }
        for (long g : state.receiverGenerations(deviceId)) {
            if (g == generation) {
                continue;
            }
            Map<String, Object> r = state.receiver(deviceId, g);
            Map<String, Object> t = TransferState.child(r, "trial");
            if (t != null && !isTerminalTrial(t)
                    && TransferState.child(r, "closure") == null) {
                throw new HoldException(
                        "an earlier generation (" + g + ") of "
                                + "this identity has an unfinished "
                                + "trial; run recovery first (F-07)");
            }
        }
        File root = stagingRootOf(rec, deviceId);
        TransferStore.StagingRecord sr =
                TransferStore.adoptStaging(root);
        if (sr == null) {
            throw new InconsistencyException("staging record missing");
        }
        // E6 (return import), two anchors, both enforced on
        // the one-shot path:
        // (a) when THIS identity is the live one here, the
        //     received journal must extend the live journal —
        //     the section 4.7 prefix condition. A journal that
        //     diverges from the live chain is a fork.
        // (b) when this installation once SENT the identity
        //     away (an S2 in the sender history), the received
        //     journal must still contain the frozen tip: its
        //     checkpoint at the frozen count hashes to the
        //     frozen tail recorded in the history's manifest.
        // Either failure refuses the confirm; the identity is
        // never silently rewound or replaced by a sibling
        // history.
        JournalFile.Parsed stagedParsed = null;
        if (activeB != null && deviceId.equals(
                TransferState.stringOr(activeB, "device_id", null))) {
            File liveJournalFile = TransferStore.journalFile(storeRoot);
            if (liveJournalFile.exists()) {
                JournalFile.Parsed live = JournalFile.parse(
                        TransferIo.readAll(liveJournalFile,
                                TransferPackage.MAX_PACKAGE_BYTES),
                        deviceId);
                stagedParsed = JournalFile.parse(
                        TransferIo.readAll(
                                TransferStore.stagingJournal(root),
                                TransferPackage.MAX_PACKAGE_BYTES),
                        deviceId);
                if (!isPrefixByChain(live, stagedParsed)) {
                    throw new HoldException(
                            "the received journal does not extend "
                                    + "this device's journal (fork)");
                }
            }
        }
        Map<String, Object> idSec = state.identity(deviceId);
        Object hist = idSec == null ? null : idSec.get("sender_history");
        Map<String, Object> lastS2 = null;
        if (hist instanceof List) {
            for (Object o : (List<?>) hist) {
                if (o instanceof Map && "S2".equals(
                        ((Map<?, ?>) o).get("state"))) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> e = (Map<String, Object>) o;
                    lastS2 = e;
                }
            }
        }
        if (lastS2 != null) {
            Map<String, Object> hmf =
                    TransferState.child(lastS2, "manifest");
            long frozenCount = hmf == null ? 0
                    : TransferState.longOr(hmf, "checkpoint_count", 0);
            String frozenTail = hmf == null ? null
                    : TransferState.stringOr(
                            hmf, "tail_checkpoint_hash", null);
            if (frozenCount > 0 && frozenTail != null) {
                if (stagedParsed == null) {
                    stagedParsed = JournalFile.parse(
                            TransferIo.readAll(
                                    TransferStore.stagingJournal(root),
                                    TransferPackage.MAX_PACKAGE_BYTES),
                            deviceId);
                }
                boolean contains = stagedParsed.count() >= frozenCount
                        && frozenTail.equals(Hex.encode(
                                stagedParsed.checkpoints
                                        .get((int) frozenCount - 1)
                                        .checkpointHash));
                if (!contains) {
                    throw new HoldException(
                            "the received journal does not contain "
                                    + "the tip this device froze "
                                    + "when it sent the identity away");
                }
            }
        }
        // E4: the confirmation number the receiver will show
        // is computed NOW, from the staged derived key and the
        // record's manifest hash, and committed with the trial
        // start — so it survives a crash mid-confirm and is
        // re-derivable from the record alone afterwards.
        // M3: the key is MANDATORY here, not best-effort. A
        // staging created before confirmation numbers existed
        // carries none, and confirming without one would
        // commit the identity with no number for the sender
        // to check — the exact gap E4 exists to close. The
        // user re-presents the file with its transfer code
        // (stagePackage re-derives the key on re-presentation)
        // and then confirms.
        String commitKeyB64 = sr.derivedCommitKeyB64(wrapKey);
        if (commitKeyB64 == null) {
            throw new HoldException(
                    "this intake predates confirmation numbers: "
                            + "import the same file once more "
                            + "(with its transfer code) and then "
                            + "confirm");
        }
        Map<String, Object> mfMap =
                TransferState.child(rec, "manifest");
        if (mfMap != null) {
            rec.put("commit_code", TransferCommitCode.codeFor(
                    TransferCodec.b64DecodeStrict(
                            commitKeyB64, "commit key"),
                    TransferCommitCode.manifestHash(mfMap)));
        }
        // Trial start: the attempt-ledger update IS the start
        // commit point, as in the strict mode.
        long n = sr.nextAttempt;
        String trialId = generation + "-" + n;
        Map<String, Object> attempt = new LinkedHashMap<>();
        attempt.put("attempt_id", trialId);
        attempt.put("generation", generation);
        attempt.put("permit_hash", "oneshot");
        attempt.put("mode", "oneshot");
        attempt.put("stage_summary", "started");
        sr.currentAttempt = attempt;
        sr.nextAttempt = n + 1;
        TransferStore.saveStaging(root, sr);
        trial = new LinkedHashMap<>();
        trial.put("trial_id", trialId);
        trial.put("generation", generation);
        trial.put("package_sha256",
                TransferState.stringOr(rec, "package_sha256", null));
        trial.put("permit_hash", "oneshot");
        trial.put("mode", "oneshot");
        trial.put("stage", "started");
        Map<String, Object> judgement = new LinkedHashMap<>();
        judgement.put("kind", "undecided");
        trial.put("judgement", judgement);
        rec.put("trial", trial);
        rec.put("mode", "oneshot");
        state.save();
        // Plan, promote, judge (one-shot judgement).
        Map<String, Object> plan = planAndPromote(state, rec, sr, trial);
        judgementStepOneshot(state, rec, sr, trial, plan);
    }

    /** The one-shot judgement step: CHECK-2's premise
     *  agreement, then the judgement commit and
     *  materialisation. No permit exists, so there are no
     *  expiry readings and no latch: the judgement is the
     *  local confirmation itself, attested with the wall
     *  clock reading for the record. */
    private void judgementStepOneshot(TransferState state,
                                      Map<String, Object> rec,
                                      TransferStore.StagingRecord sr,
                                      Map<String, Object> trial,
                                      Map<String, Object> plan)
            throws Exception {
        String deviceId = sr.deviceId;
        long generation = sr.generation;
        // CHECK-2 equivalent: the (generation, state) premise
        // must still agree at the judgement step's door.
        Map<String, Object> fresh = state.receiver(deviceId, generation);
        Map<String, Object> freshTrial = TransferState.child(fresh, "trial");
        if (freshTrial == null || !"oneshot".equals(
                TransferState.stringOr(freshTrial, "permit_hash", null))
                || TransferState.child(fresh, "closure") != null) {
            throw new InconsistencyException(
                    "CHECK-2: trial premise changed before judgement");
        }
        trial.put("stage", "confirming");
        state.save();
        Map<String, Object> attest = new LinkedHashMap<>();
        attest.put("wall_unix", clock.wallUnix());
        attest.put("mode", "oneshot");
        Map<String, Object> judgement = new LinkedHashMap<>();
        judgement.put("kind", "confirm");
        judgement.put("mode", "oneshot");
        judgement.put("attest", attest);
        judgement.put("permit_hash", "oneshot");
        trial.put("judgement", judgement);
        state.save();   // the confirmation judgement commit point
        materialize(state, rec, sr, trial, plan, attest);
    }

    // ---------------- foreign placement quarantine ----------
    // (audit C-2; design section 7.2 supplement / F-21)

    /** Key of the quarantine entry inside a receiver record. */
    private static final String KEY_FOREIGN_QUARANTINE =
            "foreign_quarantine";

    /**
     * Quarantine the live placement when it belongs to a
     * DIFFERENT identity than the one being received.
     *
     * <p>The design's rule for that configuration (section
     * 7.2 supplement, F-21): the receiving identity's old
     * placement starts EMPTY, and the displaced identity's
     * journal and archives are quarantined INTACT — never
     * snapshotted into the plan's old placement, never
     * retired into the promotion backup. The displaced
     * identity's active binding stays in force until the R2
     * commit replaces it (materialize flips the quarantine
     * entry to "retained" in the same save); a cancelled
     * trial restores the quarantined files to the live
     * placement (cancelTrialInternal / pursueRestoration).
     *
     * <p>Discipline: the foreign writer is frozen through
     * the runtime hook BEFORE anything moves (no hook =
     * HoldException: the core will not move a live writer's
     * files), the post-freeze placement is snapshotted as
     * the durable anchor BEFORE the first rename, and every
     * move is hash-checked against that anchor. Re-entry
     * with a "moving" entry re-runs the (idempotent) freeze
     * and completes the moves against the SAME anchor.
     */
    private void quarantineForeignPlacement(TransferState state,
                                            Map<String, Object> rec,
                                            String deviceId)
            throws Exception {
        Map<String, Object> fq = TransferState.child(
                rec, KEY_FOREIGN_QUARANTINE);
        if (fq != null && "quarantined".equals(
                TransferState.stringOr(fq, "state", null))) {
            // Completed by an earlier pass; the quarantined
            // files must still be intact and the live
            // placement still clear of them.
            verifyQuarantinedFiles(fq);
            return;
        }
        Map<String, Object> active = state.active();
        String activeId = active == null ? null
                : TransferState.stringOr(active, "device_id", null);
        if (activeId == null || activeId.equals(deviceId)) {
            if (fq != null) {
                throw new InconsistencyException(
                        "foreign quarantine in progress but the "
                                + "active binding is not the "
                                + "foreign identity");
            }
            return; // no foreign placement: ordinary rules
        }
        if (TransferStore.snapshotLive(storeRoot, "existing", 0).files.isEmpty()) {
            if (fq != null) {
                // Final check round 1, F-20 (i): a crash
                // after the LAST rename of the move phase but
                // before the "quarantined" save leaves the
                // entry "moving" with the live placement
                // empty — every anchored file is already in
                // the quarantine directory. That is a
                // COMPLETED move whose record lagged, not an
                // inconsistency: verify the anchored files
                // are all present, hash-exact, in quarantine
                // (verifyQuarantinedFiles also proves none
                // of them is back live), then finish the
                // entry. Anything missing or changed still
                // throws, exactly as before.
                verifyQuarantinedFiles(fq);
                fq.put("state", "quarantined");
                state.save();
                return;
            }
            return; // nothing to displace
        }
        if (foreignFreeze == null) {
            throw new HoldException(
                    "the live placement belongs to another "
                            + "identity (" + activeId + ") and no "
                            + "foreign-writer freeze hook is "
                            + "installed; refusing to promote "
                            + "over a live placement");
        }
        foreignFreeze.freezeForeignWriter(activeId);
        // Anchor AFTER the freeze: the drained writer's final
        // placement, recorded before the first rename.
        Map<String, String> liveFiles = TransferStore.snapshotLive(
                storeRoot, "existing", 0).files;
        Map<String, Object> anchor;
        if (fq == null) {
            fq = new LinkedHashMap<>();
            rec.put(KEY_FOREIGN_QUARANTINE, fq);
            anchor = new LinkedHashMap<>();
            for (Map.Entry<String, String> e
                    : liveFiles.entrySet()) {
                anchor.put(e.getKey(), e.getValue());
            }
            fq.put("device_id", activeId);
            fq.put("files", anchor);
            fq.put("state", "moving");
            state.save();
        } else {
            anchor = TransferState.child(fq, "files");
            if (anchor == null || !activeId.equals(
                    TransferState.stringOr(fq, "device_id", null))) {
                throw new InconsistencyException(
                        "foreign quarantine entry is malformed");
            }
            // Nothing may have appeared outside the anchor
            // after the freeze.
            for (String key : liveFiles.keySet()) {
                if (!anchor.containsKey(key)) {
                    throw new InconsistencyException(
                            "foreign placement gained a file "
                                    + "after the freeze: " + key);
                }
            }
        }
        File qDir = TransferStore.shadowQuarantineDir(
                storeRoot, activeId);
        for (Map.Entry<String, Object> e : anchor.entrySet()) {
            String key = e.getKey();
            String sha = (String) e.getValue();
            File live = liveFileFor(key);
            File qf = new File(qDir, key);
            if (qf.isFile()) {
                if (!sha.equals(hashOfFile(qf))) {
                    throw new InconsistencyException(
                            "quarantined file changed: " + key);
                }
                if (live.isFile()) {
                    throw new InconsistencyException(
                            "file exists both live and "
                                    + "quarantined: " + key);
                }
            } else if (live.isFile()) {
                if (!sha.equals(hashOfFile(live))) {
                    throw new InconsistencyException(
                            "foreign placement changed after "
                                    + "the freeze: " + key);
                }
                File parent = qf.getParentFile();
                if (parent != null) parent.mkdirs();
                if (!live.renameTo(qf)) {
                    throw new IOException(
                            "quarantine rename failed: " + key);
                }
                TransferIo.fsyncDir(parent);
                TransferIo.fsyncDir(live.getParentFile());
            } else {
                throw new InconsistencyException(
                        "foreign placement file vanished: " + key);
            }
        }
        fq.put("state", "quarantined");
        state.save();
    }

    /** Verify a completed quarantine: every anchored file is
     *  present in the quarantine directory with its anchored
     *  hash, and nothing of it is back in the live placement. */
    private void verifyQuarantinedFiles(Map<String, Object> fq)
            throws Exception {
        String foreignId = TransferState.stringOr(
                fq, "device_id", null);
        Map<String, Object> anchor = TransferState.child(fq, "files");
        if (foreignId == null || anchor == null) {
            throw new InconsistencyException(
                    "foreign quarantine entry is malformed");
        }
        File qDir = TransferStore.shadowQuarantineDir(
                storeRoot, foreignId);
        for (Map.Entry<String, Object> e : anchor.entrySet()) {
            String key = e.getKey();
            String sha = (String) e.getValue();
            File qf = new File(qDir, key);
            if (!qf.isFile()
                    || !sha.equals(hashOfFile(qf))) {
                throw new InconsistencyException(
                        "quarantined file missing or changed: "
                                + key);
            }
            if (liveFileFor(key).isFile()) {
                throw new InconsistencyException(
                        "quarantined file reappeared live: " + key);
            }
        }
    }

    /**
     * Restore a quarantined foreign placement to the live
     * placement (trial cancelled). The caller drives the live
     * placement to the plan's old placement (the empty
     * placement) first; this method then moves the foreign
     * files back, hash-checked and idempotent, and rewrites
     * the live manifest to describe the restored placement.
     * A no-op when no quarantine is outstanding.
     */
    private void restoreForeignQuarantine(TransferState state,
                                          Map<String, Object> rec)
            throws Exception {
        Map<String, Object> fq = TransferState.child(
                rec, KEY_FOREIGN_QUARANTINE);
        if (fq == null) return;
        String fqState = TransferState.stringOr(fq, "state", null);
        if ("restored".equals(fqState)
                || "retained".equals(fqState)) {
            return;
        }
        if (!"quarantined".equals(fqState)) {
            throw new InconsistencyException(
                    "foreign quarantine is not restorable: "
                            + fqState);
        }
        String foreignId = TransferState.stringOr(
                fq, "device_id", null);
        Map<String, Object> anchor = TransferState.child(fq, "files");
        if (foreignId == null || anchor == null) {
            throw new InconsistencyException(
                    "foreign quarantine entry is malformed");
        }
        File qDir = TransferStore.shadowQuarantineDir(
                storeRoot, foreignId);
        boolean movedAny = false;
        for (Map.Entry<String, Object> e : anchor.entrySet()) {
            String key = e.getKey();
            String sha = (String) e.getValue();
            File live = liveFileFor(key);
            File qf = new File(qDir, key);
            if (live.isFile()) {
                if (!sha.equals(hashOfFile(live))) {
                    throw new InconsistencyException(
                            "live placement occupied by other "
                                    + "content: " + key);
                }
                if (qf.isFile()) {
                    throw new InconsistencyException(
                            "file exists both live and "
                                    + "quarantined: " + key);
                }
                continue; // already restored (re-entry)
            }
            if (!qf.isFile()
                    || !sha.equals(hashOfFile(qf))) {
                throw new InconsistencyException(
                        "quarantined file missing or changed: "
                                + key);
            }
            File parent = live.getParentFile();
            if (parent != null) parent.mkdirs();
            if (!qf.renameTo(live)) {
                throw new IOException(
                        "quarantine restore rename failed: " + key);
            }
            TransferIo.fsyncDir(parent);
            TransferIo.fsyncDir(qf.getParentFile());
            movedAny = true;
        }
        if (movedAny) {
            TransferStore.LiveManifest restored =
                    TransferStore.snapshotLive(storeRoot, "existing", 0);
            TransferStore.LiveManifest cur =
                    TransferStore.loadLiveManifest(storeRoot);
            restored.sequence = (cur == null ? 0 : cur.sequence) + 1;
            TransferStore.saveLiveManifest(storeRoot, restored);
        }
        fq.put("state", "restored");
        state.save();
    }

    /**
     * The slot-lineage hold (audit M14 (a)): if the vault
     * slot for {@code generation} holds ANOTHER identity's
     * key, stop — as a hold, before anything is frozen,
     * quarantined or adopted. Shared by planAndPromote
     * (its first statement) and by the recovery
     * reconstruction branch, which must pass the same gate
     * BEFORE it adopts the plan it reconstructed (audit
     * L25 (2): that branch used to adopt first and meet
     * the proof afterwards, inside placePromotedSeed —
     * the one place the M14 (a) ordering survived).
     *
     * Audit L25 (1): the wording states how resumption
     * actually works. Round 6's message ("waits until the
     * slot is free") and the progress record ("a later
     * confirm (or recovery) retries from here") implied a
     * later confirmation could pick the trial back up; it
     * cannot — once the trial start is committed, both
     * confirms refuse with "a trial is in progress; run
     * recovery". Resumption is through recovery (or
     * cancellation) only, and neither is wired into either
     * app at runtime yet: a trial held here waits for that
     * wiring. That is a known constraint, recorded as such;
     * the entrance refusals keep it a defence-in-depth
     * layer rather than a normal path.
     */
    private void holdIfSlotHeldByOtherLineage(long generation,
                                              String deviceId)
            throws Exception {
        if (vault.has(TransferVault.receiverKey(generation))
                && !slotHoldsIdentityKey(generation, deviceId)) {
            throw new HoldException(
                    "the vault slot for generation " + generation
                            + " holds another identity's key; "
                            + "this promotion stops here, before "
                            + "anything moves, and resumes only "
                            + "through recovery (or cancellation) "
                            + "once the slot is free "
                            + "(audit M14/L25)");
        }
    }

    /** Build the promotion plan by the canonical procedure
     *  (from verified durable inputs only), append it to the
     *  promotion journal, adopt it (the trial record's plan
     *  marker is the adoption point), vault the seed, and
     *  execute the file movements. Idempotent: safe to re-enter
     *  for a trial whose plan is already adopted (recovery). */
    private Map<String, Object> planAndPromote(
            TransferState state, Map<String, Object> rec,
            TransferStore.StagingRecord sr, Map<String, Object> trial)
            throws Exception {
        String deviceId = sr.deviceId;
        long generation = sr.generation;
        File root = stagingRootOf(rec, deviceId);
        String trialId = TransferState.stringOr(trial, "trial_id", null);
        // Audit M14 (a): the slot-lineage proof comes FIRST,
        // before anything hard to undo. Round 5 proved the
        // slot at the point of use (step 8-2, below) — but
        // by then this method has already frozen a writer,
        // quarantined a placement and ADOPTED a plan, so a
        // promotion that could never place its seed stopped
        // only after moving the store. Nothing but a
        // precondition has collapsed at this point, so the
        // refusal is a hold. Audit L25: a later CONFIRM does
        // not resume from here — it is refused while the
        // trial is in progress ("a trial is in progress;
        // run recovery"); resumption is through recovery
        // (or cancellation) only, once the slot is free.
        holdIfSlotHeldByOtherLineage(generation, deviceId);
        // M4: the SAME-identity case needs the writer freeze
        // too. Quarantine below covers a foreign placement
        // only; when the live placement belongs to the very
        // identity being received (a return, or a re-receipt
        // after growth), nothing stopped its writer before
        // buildPlan snapshotted the live journal — a recorder
        // appending mid-plan raced the promotion. Freeze
        // through the same hook first. With no hook installed
        // (core-only stores) there is no live writer to race.
        if (foreignFreeze != null) {
            Map<String, Object> activeNow = state.active();
            if (activeNow != null && deviceId.equals(
                    TransferState.stringOr(
                            activeNow, "device_id", null))) {
                foreignFreeze.freezeForeignWriter(deviceId);
            }
        }
        // C-2 / F-21: a foreign identity's placement is
        // quarantined intact BEFORE the plan snapshots the
        // live placement, so the plan's old placement is the
        // empty placement the design prescribes.
        quarantineForeignPlacement(state, rec, deviceId);
        Map<String, Object> plan;
        String planHash = TransferState.stringOr(trial, "plan_hash", null);
        List<Map<String, Object>> entries = readPromotionEntries();
        if (TransferState.boolOr(trial, "plan_adopted", false)
                && planHash != null) {
            plan = findPlan(entries, trialId, planHash);
            if (plan == null) {
                throw new InconsistencyException(
                        "adopted plan is missing or its hash differs");
            }
        } else {
            plan = buildPlan(state, rec, sr, trial);
            planHash = Hex.encode(DepinCore.sha256(
                    TransferCodec.jcs(plan)));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("kind", "plan");
            entry.put("trial_id", trialId);
            entry.put("generation", generation);
            entry.put("plan_hash", planHash);
            entry.put("plan", plan);
            TransferIo.appendFramed(
                    TransferStore.promotionJournal(storeRoot), entry);
            trial.put("plan_hash", planHash);
            trial.put("plan_adopted", true);
            trial.put("stage", "planned");
            state.save();   // the plan adoption point
        }
        // Step 8-2: seed into the vault (after adoption
        // only) — the extracted placement below, shared
        // with the recovery path (audit M14).
        placePromotedSeed(sr, generation, deviceId);
        executePromotion(plan, trialId);
        return plan;
    }

    /**
     * Step 8-2: place the staged seed in the vault slot for
     * its generation, proved at the point of use.
     *
     * Audit F11: the slot is re-proved HERE — never taken
     * on the plan's say-so. The plan was built from an
     * earlier read, and the slot is shared by every lineage
     * in the store (audit F10): a copy that proves itself
     * this identity's key is the placement, already done;
     * an empty slot takes the staged seed (itself
     * derivation-checked, as before); a slot occupied by
     * ANOTHER lineage's key is neither "already placed"
     * nor ours to overwrite — the promotion stops, loudly,
     * with the foreign copy intact. (completeIntake refuses
     * that intake long before this point; this is the same
     * proof at the last moment it can still be made, for
     * plans adopted before the slot changed.)
     *
     * Audit M14 (b): this step is a method of its own
     * because the recovery path needs it too. Round 5 ran
     * it only inside planAndPromote — whose plan_adopted
     * branch no caller can reach (both confirms build a
     * fresh trial; recovery calls planAndPromote only for
     * a trial with no plan) — while the ACTUAL re-entry
     * for an adopted plan, recoverUndecidedTrial, went
     * straight to executePromotion. A crash between the
     * adoption save and the vault put therefore recovered
     * into a committed generation with no seed in the
     * vault: F11's outcome, arrived at by crash instead of
     * by intake. Recovery now calls this before every
     * executePromotion of an undecided trial, so the seed
     * is placed (or proved already placed) before any file
     * moves, on every route that can promote.
     */
    private void placePromotedSeed(TransferStore.StagingRecord sr,
                                   long generation,
                                   String deviceId)
            throws Exception {
        if (!slotHoldsIdentityKey(generation, deviceId)) {
            if (vault.has(TransferVault.receiverKey(generation))) {
                throw new InconsistencyException(
                        "the vault slot for generation "
                                + generation + " holds another "
                                + "identity's key; refusing to "
                                + "strand or overwrite it "
                                + "(audit F11)");
            }
            byte[] seed = unwrapSeed(sr);
            if (!deviceId.equals(DepinCore.deviceIdFromPubkey(
                    Ed25519.publickeyFromSeed(seed)))) {
                throw new InconsistencyException(
                        "staged seed does not derive the manifest identity");
            }
            vault.put(TransferVault.receiverKey(generation), seed);
        }
    }

    /**
     * Audit M14 (b): the minimum proof for a DECIDED trial
     * on the recovery route. Its promotion ran step 8-2
     * when the plan was adopted (in planAndPromote), so
     * the slot must still prove this identity's key before
     * recovery moves files or materialises on top of it. A
     * slot that no longer does — emptied, or taken by
     * another lineage — stops the materialisation instead
     * of committing over an unproven seed.
     */
    private void assertDecidedTrialSeed(
            TransferStore.StagingRecord sr) throws Exception {
        if (!slotHoldsIdentityKey(sr.generation, sr.deviceId)) {
            throw new InconsistencyException(
                    "decided trial for generation " + sr.generation
                            + " has no proven seed in the vault "
                            + "slot; refusing to materialise "
                            + "(audit M14)");
        }
    }

    private Map<String, Object> buildPlan(
            TransferState state, Map<String, Object> rec,
            TransferStore.StagingRecord sr, Map<String, Object> trial)
            throws Exception {
        File root = stagingRootOf(rec, sr.deviceId);
        // Verified inputs: the staged files must still hash to
        // the values fixed at intake.
        String journalSha = Hex.encode(DepinCore.sha256(TransferIo.readAll(
                TransferStore.stagingJournal(root),
                TransferPackage.MAX_PACKAGE_BYTES)));
        if (!journalSha.equals(sr.journalSha256)) {
            throw new InconsistencyException(
                    "staged journal changed since intake");
        }
        for (Map.Entry<String, String> e : sr.archiveSha256.entrySet()) {
            String sha = Hex.encode(DepinCore.sha256(TransferIo.readAll(
                    TransferStore.stagingArchive(root, e.getKey()),
                    TransferPackage.MAX_PACKAGE_BYTES)));
            if (!sha.equals(e.getValue())) {
                throw new InconsistencyException(
                        "staged archive changed since intake: "
                                + e.getKey());
            }
        }
        TransferStore.LiveManifest oldSnap = TransferStore.snapshotLive(
                storeRoot, "existing", 0);
        TransferStore.LiveManifest current =
                TransferStore.loadLiveManifest(storeRoot);
        long newSeq = (current == null) ? 1 : current.sequence + 1;
        Map<String, Object> manifest = TransferState.child(rec, "manifest");
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("format", "quantus-depin-promotion-plan");
        plan.put("version", 1L);
        plan.put("generation", sr.generation);
        plan.put("trial_id", TransferState.stringOr(trial, "trial_id", null));
        plan.put("permit_hash",
                TransferState.stringOr(trial, "permit_hash", null));
        plan.put("package_sha256",
                TransferState.stringOr(rec, "package_sha256", null));
        List<Object> files = new ArrayList<>();
        Map<String, String> newFiles = new LinkedHashMap<>();
        newFiles.put(JournalStore.FILE_NAME, sr.journalSha256);
        for (Map.Entry<String, String> e : sr.archiveSha256.entrySet()) {
            newFiles.put("archives/" + e.getKey(), e.getValue());
        }
        for (Map.Entry<String, String> e : newFiles.entrySet()) {
            Map<String, Object> fe = new LinkedHashMap<>();
            fe.put("key", e.getKey());
            fe.put("action", "promote");
            fe.put("expect_sha256", e.getValue());
            if (JournalStore.FILE_NAME.equals(e.getKey())) {
                Map<String, Object> binding = new LinkedHashMap<>();
                binding.put("tail_seq",
                        TransferState.longOr(manifest, "tail_seq", -1));
                binding.put("tail_checkpoint_hash", TransferState.stringOr(
                        manifest, "tail_checkpoint_hash", null));
                binding.put("checkpoint_count", TransferState.longOr(
                        manifest, "checkpoint_count", -1));
                binding.put("anchor_bundle_hash", TransferState.stringOr(
                        manifest, "anchor_bundle_hash", null));
                binding.put("file_sha256", sr.journalSha256);
                fe.put("journal_binding", binding);
            }
            files.add(fe);
        }
        for (String key : oldSnap.files.keySet()) {
            if (!newFiles.containsKey(key)) {
                Map<String, Object> fe = new LinkedHashMap<>();
                fe.put("key", key);
                fe.put("action", "retire");
                fe.put("expect_sha256", oldSnap.files.get(key));
                files.add(fe);
            }
        }
        plan.put("files", files);
        // Audit F11: seed_needed means "the slot does not
        // hold THIS identity's key" — derivation-proven,
        // never existence. An occupied slot held by a
        // foreign lineage planned as seed_needed=false in
        // round 3, and the promotion then skipped the one
        // placement that could have saved the intake.
        plan.put("seed_needed",
                !slotHoldsIdentityKey(sr.generation, sr.deviceId));
        plan.put("seed_device_id", sr.deviceId);
        plan.put("seed_pubkey", TransferState.stringOr(
                manifest, "pubkey", null));
        Map<String, Object> oldM = new LinkedHashMap<>();
        Map<String, Object> of = new LinkedHashMap<>();
        of.putAll(oldSnap.files);
        oldM.put("files", of);
        oldM.put("origin", (current != null && current.origin != null)
                ? current.origin : "existing");
        oldM.put("sequence", (current != null) ? current.sequence : 0L);
        plan.put("old_manifest", oldM);
        Map<String, Object> newM = new LinkedHashMap<>();
        Map<String, Object> nf = new LinkedHashMap<>();
        nf.putAll(newFiles);
        newM.put("files", nf);
        newM.put("origin", "transfer:" + sr.generation);
        newM.put("sequence", newSeq);
        plan.put("new_manifest", newM);
        plan.put("backup_dir", TransferStore.PRE_PROMOTION_BACKUP_NAME
                + "/" + sr.generation
                + "/" + TransferState.stringOr(trial, "trial_id", null));
        return plan;
    }

    /** SHA-256 of a file's full content, hex (the placement
     *  hash form used by manifests and plans). */
    private static String hashOfFile(File f) throws IOException {
        return Hex.encode(DepinCore.sha256(TransferIo.readAll(
                f, TransferPackage.MAX_PACKAGE_BYTES)));
    }

    private File liveFileFor(String key) {
        if (JournalStore.FILE_NAME.equals(key)) {
            return TransferStore.journalFile(storeRoot);
        }
        if (key.startsWith("archives/")) {
            return TransferStore.archiveFile(
                    storeRoot, key.substring("archives/".length()));
        }
        return new File(storeRoot, key);
    }

    private File stagingFileFor(File root, String key) {
        if (JournalStore.FILE_NAME.equals(key)) {
            return TransferStore.stagingJournal(root);
        }
        if (key.startsWith("archives/")) {
            return TransferStore.stagingArchive(
                    root, key.substring("archives/".length()));
        }
        return new File(root, key);
    }

    private File backupFileFor(Map<String, Object> plan, String key) {
        return new File(new File(storeRoot,
                TransferState.stringOr(plan, "backup_dir", null)), key);
    }

    /** Execute (or idempotently resume) the file movements of
     *  an adopted plan. Completion evidence is the promotion
     *  journal's per-file entries; physical state is judged by
     *  hashes, never by assumption. */
    private void executePromotion(Map<String, Object> plan,
                                  String trialId) throws Exception {
        // Design section 3.4 floor (final check round 3,
        // H-11): the promotion is the receiver's other large
        // write; it does not start if the free space has
        // fallen below the installed floor.
        checkFreeFloor(storeRoot, "promotion");
        List<Map<String, Object>> entries = readPromotionEntries();
        java.util.Set<String> backupDone = doneKeys(entries, trialId,
                "backup_done");
        java.util.Set<String> promoteDone = doneKeys(entries, trialId,
                "promote_done");
        Map<String, Object> oldM = TransferState.child(plan, "old_manifest");
        Map<String, Object> oldFiles = TransferState.child(oldM, "files");
        File backupRootDir = new File(storeRoot,
                TransferState.stringOr(plan, "backup_dir", null));
        boolean backupRootReady = backupDone.size() > 0
                || promoteDone.size() > 0;
        @SuppressWarnings("unchecked")
        List<Object> files = (List<Object>) plan.get("files");
        // Recover the staging root from any receiver record
        // pointing at this plan's generation.
        File stagingRoot = findStagingRoot(
                TransferState.longOr(plan, "generation", -1),
                TransferState.stringOr(plan, "package_sha256", null));
        for (Object o : files) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fe = (Map<String, Object>) o;
            String key = TransferState.stringOr(fe, "key", null);
            String action = TransferState.stringOr(fe, "action", null);
            String expect = TransferState.stringOr(
                    fe, "expect_sha256", null);
            File dest = liveFileFor(key);
            File backup = backupFileFor(plan, key);
            boolean hadOld = oldFiles != null && oldFiles.containsKey(key);
            if (!backupDone.contains(key) && hadOld
                    && !promoteDone.contains(key)) {
                // Audit C-2: EVERY divergence from the adopted
                // plan is an inconsistency stop now. The former
                // code silently skipped the backup whenever the
                // live file no longer matched the plan, and the
                // promotion then overwrote it — destroying the
                // old placement without a restorable copy.
                if (backup.isFile()) {
                    // A previous pass moved the old file but
                    // died before recording it: adopt the move
                    // after hash verification.
                    if (!String.valueOf(oldFiles.get(key)).equals(
                            hashOfFile(backup))) {
                        throw new InconsistencyException(
                                "promotion backup for " + key
                                        + " does not match the plan");
                    }
                    if (dest.exists()) {
                        throw new InconsistencyException(
                                "promotion state for " + key
                                        + " cannot be decided: the "
                                        + "old file is both live and "
                                        + "backed up");
                    }
                    appendPromotion("backup_done", trialId, key);
                    backupDone.add(key);
                } else if (dest.exists()) {
                    String destSha = Hex.encode(DepinCore.sha256(
                            TransferIo.readAll(dest,
                                    TransferPackage.MAX_PACKAGE_BYTES)));
                    if (!destSha.equals(oldFiles.get(key))) {
                        throw new InconsistencyException(
                                "live file " + key + " changed after "
                                        + "the promotion plan was "
                                        + "adopted");
                    }
                    if (!backupRootReady) {
                        backupRootDir.mkdirs();
                        TransferIo.fsyncAncestors(
                                storeRoot, backupRootDir);
                        backupRootReady = true;
                    }
                    backup.getParentFile().mkdirs();
                    if (!dest.renameTo(backup)) {
                        throw new IOException(
                                "promotion: backup rename failed for "
                                        + key);
                    }
                    TransferIo.fsyncDir(dest.getParentFile());
                    TransferIo.fsyncDir(backup.getParentFile());
                    appendPromotion("backup_done", trialId, key);
                    backupDone.add(key);
                } else {
                    throw new InconsistencyException(
                            "old live file " + key + " vanished "
                                    + "before its backup");
                }
            }
            if ("retire".equals(action)) {
                continue;
            }
            if (promoteDone.contains(key)) continue;
            String liveSha = dest.exists()
                    ? Hex.encode(DepinCore.sha256(TransferIo.readAll(
                            dest, TransferPackage.MAX_PACKAGE_BYTES)))
                    : null;
            if (expect.equals(liveSha)) {
                // Promoted but unrecorded: adopt the movement —
                // but a pre-existing file's backup proof is
                // required. A backup file left by an interrupted
                // pass is verified and adopted first; when old
                // == new content, the backup is satisfied by
                // copying the (identical) file. Anything else is
                // undecidable (audit C-2).
                if (hadOld && !backupDone.contains(key)) {
                    if (backup.isFile()) {
                        if (!String.valueOf(oldFiles.get(key)).equals(
                                hashOfFile(backup))) {
                            throw new InconsistencyException(
                                    "promotion backup for " + key
                                            + " does not match the "
                                            + "plan");
                        }
                        appendPromotion("backup_done", trialId, key);
                        backupDone.add(key);
                    } else if (expect.equals(oldFiles.get(key))) {
                        backup.getParentFile().mkdirs();
                        TransferIo.writeFileSync(backup,
                                TransferIo.readAll(dest,
                                        TransferPackage.MAX_PACKAGE_BYTES));
                        appendPromotion("backup_done", trialId, key);
                        backupDone.add(key);
                    } else {
                        throw new InconsistencyException(
                                "promotion state for " + key
                                        + " cannot be decided");
                    }
                }
                appendPromotion("promote_done", trialId, key);
                promoteDone.add(key);
                continue;
            }
            File src = stagingFileFor(stagingRoot, key);
            if (!src.exists()) {
                throw new InconsistencyException(
                        "staged source missing for " + key);
            }
            TransferIo.fsync(src);
            dest.getParentFile().mkdirs();
            if (!src.renameTo(dest)) {
                throw new IOException(
                        "promotion: rename failed for " + key);
            }
            TransferIo.fsyncDir(src.getParentFile());
            TransferIo.fsyncDir(dest.getParentFile());
            appendPromotion("promote_done", trialId, key);
            promoteDone.add(key);
        }
        TransferIo.fsyncDir(storeRoot);
        File ad = TransferStore.archivesDir(storeRoot);
        if (ad.exists()) TransferIo.fsyncDir(ad);
    }

    private File findStagingRoot(long generation, String packageSha256)
            throws IOException {
        TransferState state = state();
        for (String did : state.identities().keySet()) {
            Map<String, Object> rec = state.receiver(did, generation);
            if (rec != null && packageSha256 != null
                    && packageSha256.equals(TransferState.stringOr(
                            rec, "package_sha256", null))) {
                return stagingRootOf(rec, did);
            }
        }
        throw new InconsistencyException(
                "no staging root found for generation " + generation);
    }

    private void appendPromotion(String kind, String trialId, String key)
            throws IOException {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("kind", kind);
        e.put("trial_id", trialId);
        if (key != null) e.put("key", key);
        TransferIo.appendFramed(
                TransferStore.promotionJournal(storeRoot), e);
    }

    private List<Map<String, Object>> readPromotionEntries()
            throws IOException {
        TransferIo.FramedRead fr = TransferIo.readFramed(
                TransferStore.promotionJournal(storeRoot));
        if (fr.midDamage) {
            throw new InconsistencyException(
                    "promotion journal has interior corruption");
        }
        return fr.entries;
    }

    private static java.util.Set<String> doneKeys(
            List<Map<String, Object>> entries, String trialId,
            String kind) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (Map<String, Object> e : entries) {
            if (kind.equals(e.get("kind"))
                    && trialId.equals(e.get("trial_id"))
                    && e.get("key") instanceof String) {
                out.add((String) e.get("key"));
            }
        }
        return out;
    }

    private static Map<String, Object> findPlan(
            List<Map<String, Object>> entries, String trialId,
            String planHash) {
        for (Map<String, Object> e : entries) {
            if ("plan".equals(e.get("kind"))
                    && trialId.equals(e.get("trial_id"))
                    && planHash.equals(e.get("plan_hash"))) {
                return TransferState.child(e, "plan");
            }
        }
        return null;
    }

    /** The step 8-5 judgement step, entered uniformly for
     *  every trial (CHECK-2 first). */
    private void judgementStep(TransferState state, Map<String, Object> rec,
                               TransferStore.StagingRecord sr,
                               Map<String, Object> trial,
                               Map<String, Object> plan) throws Exception {
        String deviceId = sr.deviceId;
        long generation = sr.generation;
        String permitHash = TransferState.stringOr(
                trial, "permit_hash", null);
        // CHECK-2: the (generation, role, state) triple must
        // still agree at the judgement step's door.
        Map<String, Object> fresh = state.receiver(deviceId, generation);
        Map<String, Object> freshTrial = TransferState.child(fresh, "trial");
        if (freshTrial == null || !permitHash.equals(TransferState.stringOr(
                freshTrial, "permit_hash", null))
                || TransferState.child(fresh, "closure") != null) {
            throw new InconsistencyException(
                    "CHECK-2: trial premise changed before judgement");
        }
        trial.put("stage", "confirming");
        state.save();
        Map<String, Object> permit = sr.permitFull;
        long expWall = Json.asLong(permit.get("expires_at_unix"));
        long expFin = Json.asLong(permit.get("expires_at_finalized"));
        if (state.hasLatch(deviceId, permitHash)) {
            cancelTrialInternal(state, rec, sr, trial, plan);
            return;
        }
        long t0 = clock.monoNanos();
        long finalized;
        try {
            finalized = finalizedSource.readFinalized();
        } catch (Exception e) {
            // Read failure: no judgement, no latch, cancel to R1.
            cancelTrialInternal(state, rec, sr, trial, plan);
            return;
        }
        if (finalized >= expFin) {
            // One side, verified within the cap, reached expiry:
            // record the observation, then cancel.
            state.recordLatch(deviceId, permitHash, "finalized", finalized);
            cancelTrialInternal(state, rec, sr, trial, plan);
            return;
        }
        long wall = clock.wallUnix();
        long elapsed = clock.monoNanos() - t0;
        if (elapsed > JUDGEMENT_STEP_CAP_NANOS) {
            cancelTrialInternal(state, rec, sr, trial, plan);
            return;
        }
        if (wall >= expWall) {
            state.recordLatch(deviceId, permitHash, "wall", wall);
            cancelTrialInternal(state, rec, sr, trial, plan);
            return;
        }
        // The judgement is taken at the reading point: commit
        // it into the trial record's judgement field FIRST.
        Map<String, Object> attest = new LinkedHashMap<>();
        attest.put("finalized", finalized);
        attest.put("wall_unix", wall);
        attest.put("permit_hash", permitHash);
        Map<String, Object> judgement = new LinkedHashMap<>();
        judgement.put("kind", "confirm");
        judgement.put("attest", attest);
        judgement.put("permit_hash", permitHash);
        trial.put("judgement", judgement);
        state.save();   // the confirmation judgement commit point
        materialize(state, rec, sr, trial, plan, attest);
    }

    /** Materialise a committed judgement (step 8 final
     *  stage): completion entry → stage → live manifest →
     *  the R2 confirmation record. Also used by recovery's
     *  classification (a) — it never writes a confirmation
     *  record except as this materialisation. */
    private void materialize(TransferState state, Map<String, Object> rec,
                             TransferStore.StagingRecord sr,
                             Map<String, Object> trial,
                             Map<String, Object> plan,
                             Map<String, Object> attest) throws Exception {
        // Final check round 1, F-07 (the choke point): a
        // generation older than the committed maximum is
        // NEVER materialised — doing so rewound
        // held_generation and the active binding to the older
        // generation when a judged trial's recovery ran late,
        // after a newer generation had committed. Both
        // confirms hold such generations at their entrances
        // and recovery closes them as superseded; this guard
        // is the last line, before anything is written.
        Map<String, Object> idSecM = state.identity(sr.deviceId);
        long maxCommittedM = idSecM == null ? 0
                : TransferState.longOr(
                        idSecM, "max_committed_generation", 0);
        if (sr.generation < maxCommittedM) {
            throw new InconsistencyException(
                    "materialisation of generation " + sr.generation
                            + " refused: generation " + maxCommittedM
                            + " of this identity is already committed "
                            + "(F-07)");
        }
        String trialId = TransferState.stringOr(trial, "trial_id", null);
        List<Map<String, Object>> entries = readPromotionEntries();
        boolean hasComplete = false;
        for (Map<String, Object> e : entries) {
            if ("complete".equals(e.get("kind"))
                    && trialId.equals(e.get("trial_id"))) {
                hasComplete = true;
            }
        }
        if (!hasComplete) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("kind", "complete");
            e.put("trial_id", trialId);
            e.put("attest", attest);
            e.put("permit_hash",
                    TransferState.stringOr(trial, "permit_hash", null));
            TransferIo.appendFramed(
                    TransferStore.promotionJournal(storeRoot), e);
        }
        trial.put("stage", "entry_saved");
        state.save();
        Map<String, Object> newM = TransferState.child(plan, "new_manifest");
        TransferStore.LiveManifest lm = new TransferStore.LiveManifest();
        Map<String, Object> nf = TransferState.child(newM, "files");
        for (Map.Entry<String, Object> e : nf.entrySet()) {
            lm.files.put(e.getKey(), (String) e.getValue());
        }
        lm.origin = TransferState.stringOr(newM, "origin", null);
        lm.sequence = TransferState.longOr(newM, "sequence", 0);
        TransferStore.saveLiveManifest(storeRoot, lm);
        // Final stage: the R2 confirmation record + binding.
        // A one-shot trial closes with its own kind: the
        // confirmation record (r2) and the binding switch are
        // the same, but no receipt can be issued for it and no
        // ack will ever be taken in (buildReceipt / intakeAck
        // key on "committed_r2" and refuse it).
        Map<String, Object> closure = new LinkedHashMap<>();
        closure.put("kind", "oneshot".equals(TransferState.stringOr(
                trial, "mode", null))
                ? "committed_oneshot" : "committed_r2");
        rec.put("closure", closure);
        Map<String, Object> r2 = new LinkedHashMap<>();
        r2.put("device_id", sr.deviceId);
        r2.put("generation", sr.generation);
        r2.put("receiver_install_id", state.installId());
        r2.put("attest", attest);
        rec.put("r2", r2);
        rec.put("restoration", "complete");
        // C-2 / F-21: the R2 commit is where the displaced
        // identity's active binding ends; its quarantined
        // placement is RETAINED as that identity's own data
        // in the same save (a later promotion of the shadow
        // identity itself is a separate flow).
        Map<String, Object> fqM = TransferState.child(
                rec, KEY_FOREIGN_QUARANTINE);
        if (fqM != null && "quarantined".equals(
                TransferState.stringOr(fqM, "state", null))) {
            fqM.put("state", "retained");
        }
        Map<String, Object> idSection =
                state.identityOrCreate(sr.deviceId);
        if (sr.generation > TransferState.longOr(
                idSection, "max_committed_generation", 0)) {
            idSection.put("max_committed_generation", sr.generation);
        }
        idSection.put("held_generation", sr.generation);
        trial.put("stage", "confirmed");
        state.setActive(TransferState.binding(
                sr.deviceId, sr.generation, "receiver", "holding"));
        // setActive saves the whole document: the R2 record,
        // the closure and the binding switch are one update.
    }

    // ---------------- cancellation (step 9) ----------------

    /** Cancel an undecided trial (step 9). The cancellation
     *  decision is committed in the trial record BEFORE any
     *  file is moved; restoration then proceeds file by file
     *  and completion is judged against the plan's snapshot. */
    public void cancelTrial(String deviceId, long generation)
            throws Exception {
        TransferState state = state();
        if (!state.adopt()) state = state();
        Map<String, Object> rec = state.receiver(deviceId, generation);
        if (rec == null) throw new HoldException("no such generation");
        Map<String, Object> trial = TransferState.child(rec, "trial");
        if (trial == null) throw new HoldException("no trial to cancel");
        File root = stagingRootOf(rec, deviceId);
        TransferStore.StagingRecord sr = TransferStore.loadStaging(root);
        Map<String, Object> plan = null;
        if (TransferState.boolOr(trial, "plan_adopted", false)) {
            plan = findPlan(readPromotionEntries(),
                    TransferState.stringOr(trial, "trial_id", null),
                    TransferState.stringOr(trial, "plan_hash", null));
            if (plan == null) {
                throw new InconsistencyException(
                        "adopted plan missing at cancellation");
            }
        }
        cancelTrialInternal(state, rec, sr, trial, plan);
    }

    private void cancelTrialInternal(TransferState state,
                                     Map<String, Object> rec,
                                     TransferStore.StagingRecord sr,
                                     Map<String, Object> trial,
                                     Map<String, Object> plan)
            throws Exception {
        Map<String, Object> judgement =
                TransferState.child(trial, "judgement");
        if (judgement != null && "confirm".equals(
                TransferState.stringOr(judgement, "kind", null))) {
            throw new InconsistencyException(
                    "a judged trial cannot be cancelled; "
                            + "live displacement here is an inconsistency");
        }
        // The cancellation decision commit (same atomic update
        // as the judgement field's cancel value).
        if (judgement == null || !"cancel".equals(
                TransferState.stringOr(judgement, "kind", null))) {
            Map<String, Object> j = new LinkedHashMap<>();
            j.put("kind", "cancel");
            trial.put("judgement", j);
            trial.put("stage", "cancel_decided");
            state.save();
        }
        String trialId = TransferState.stringOr(trial, "trial_id", null);
        if (plan != null) {
            restoreFromPlan(plan, trialId,
                    stagingRootOf(rec, sr.deviceId));
            // Completion: restored live == plan snapshot, then
            // the live manifest returns to the old placement,
            // then the journal records completion (the append
            // inside restoreFromPlan precedes this update), and
            // only then the stage update below.
            Map<String, Object> oldM =
                    TransferState.child(plan, "old_manifest");
            Map<String, Object> oldFiles =
                    TransferState.child(oldM, "files");
            TransferStore.LiveManifest now = TransferStore.snapshotLive(
                    storeRoot, "existing", 0);
            Map<String, String> expect = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : oldFiles.entrySet()) {
                expect.put(e.getKey(), (String) e.getValue());
            }
            if (!now.files.equals(expect)) {
                throw new InconsistencyException(
                        "restored live placement differs from the "
                                + "plan snapshot; cancellation cannot "
                                + "complete");
            }
            TransferStore.LiveManifest cur =
                    TransferStore.loadLiveManifest(storeRoot);
            TransferStore.LiveManifest lm =
                    new TransferStore.LiveManifest();
            lm.files.putAll(expect);
            lm.origin = TransferState.stringOr(oldM, "origin", null);
            lm.sequence = (cur == null) ? 1 : cur.sequence + 1;
            TransferStore.saveLiveManifest(storeRoot, lm);
        }
        // C-2 / F-21: with the live placement back at the
        // plan's (empty) old placement — or never promoted at
        // all — a quarantined foreign placement returns to
        // live before the cancellation completes.
        restoreForeignAfterCancel(state, rec, sr.deviceId);
        // Audit L19: a cancelled trial takes the key it
        // placed back out of the slot. Step 8-2 put the seed
        // there for THIS trial (the plan says so:
        // seed_needed was true, i.e. the slot did not hold
        // this identity's key when the plan was built), and
        // round 5 left it behind — where the M13 occupancy
        // allowance then read it as a legitimate holding
        // and no classification ever looked at the slot
        // again for the cancelled identity. The removal is
        // proved the same way the placement was: the copy
        // comes out only if it still derives this trial's
        // identity. A pre-existing copy (seed_needed false)
        // is not this trial's to remove and stays.
        if (plan != null && sr != null
                && TransferState.boolOr(plan, "seed_needed", false)
                && vault.has(TransferVault.receiverKey(sr.generation))
                && slotHoldsIdentityKey(sr.generation, sr.deviceId)) {
            vault.delete(TransferVault.receiverKey(sr.generation));
        }
        trial.put("stage", "cancel_complete");
        state.save();
    }

    /** Cancellation-side foreign restore: a crash may have
     *  interrupted the quarantine itself (entry still
     *  "moving"); complete it under the same anchor
     *  discipline first, then restore it like any completed
     *  quarantine. */
    private void restoreForeignAfterCancel(TransferState state,
                                           Map<String, Object> rec,
                                           String deviceId)
            throws Exception {
        Map<String, Object> fq = TransferState.child(
                rec, KEY_FOREIGN_QUARANTINE);
        if (fq != null && "moving".equals(
                TransferState.stringOr(fq, "state", null))) {
            quarantineForeignPlacement(state, rec, deviceId);
        }
        restoreForeignQuarantine(state, rec);
    }

    /** Reverse a plan's file movements: promoted files return
     *  to the staging root, backups return to live, each with
     *  both-directory fsyncs and a journal record. Used by
     *  cancellation and by post-closure restoration pursuit. */
    private void restoreFromPlan(Map<String, Object> plan,
                                 String trialId, File stagingRoot)
            throws Exception {
        List<Map<String, Object>> entries = readPromotionEntries();
        java.util.Set<String> restored = new java.util.HashSet<>();
        for (Map<String, Object> e : entries) {
            if ("restore_done".equals(e.get("kind"))
                    && trialId.equals(e.get("trial_id"))
                    && e.get("key") instanceof String) {
                restored.add((String) e.get("key"));
            }
        }
        boolean cancelRecorded = false;
        for (Map<String, Object> e : entries) {
            if ("cancel_done".equals(e.get("kind"))
                    && trialId.equals(e.get("trial_id"))) {
                cancelRecorded = true;
            }
        }
        @SuppressWarnings("unchecked")
        List<Object> files = (List<Object>) plan.get("files");
        List<Object> reversed = new ArrayList<>(files);
        java.util.Collections.reverse(reversed);
        for (Object o : reversed) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fe = (Map<String, Object>) o;
            String key = TransferState.stringOr(fe, "key", null);
            if (restored.contains(key)) continue;
            String action = TransferState.stringOr(fe, "action", null);
            File live = liveFileFor(key);
            File backup = backupFileFor(plan, key);
            if ("promote".equals(action)) {
                // Return B to the staging root (its absence
                // does not block completion; it gates retries).
                File stagedDest = stagingFileFor(stagingRoot, key);
                if (live.exists()) {
                    String liveSha = Hex.encode(DepinCore.sha256(
                            TransferIo.readAll(live,
                                    TransferPackage.MAX_PACKAGE_BYTES)));
                    if (liveSha.equals(TransferState.stringOr(
                            fe, "expect_sha256", null))) {
                        stagedDest.getParentFile().mkdirs();
                        if (!live.renameTo(stagedDest)) {
                            throw new IOException(
                                    "restore: return rename failed for "
                                            + key);
                        }
                        TransferIo.fsyncDir(live.getParentFile());
                        TransferIo.fsyncDir(stagedDest.getParentFile());
                    }
                }
            }
            if (backup.exists()) {
                live.getParentFile().mkdirs();
                if (!backup.renameTo(live)) {
                    throw new IOException(
                            "restore: backup rename failed for " + key);
                }
                TransferIo.fsyncDir(backup.getParentFile());
                TransferIo.fsyncDir(live.getParentFile());
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("kind", "restore_done");
            e.put("trial_id", trialId);
            e.put("key", key);
            TransferIo.appendFramed(
                    TransferStore.promotionJournal(storeRoot), e);
            restored.add(key);
        }
        if (!cancelRecorded) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("kind", "cancel_done");
            e.put("trial_id", trialId);
            TransferIo.appendFramed(
                    TransferStore.promotionJournal(storeRoot), e);
        }
        TransferIo.fsyncDir(storeRoot);
    }

    // ---------------- recovery (section 4.3) ----------------

    /**
     * The crash-recovery router and ordered procedure. Runs
     * after adoption of every record; the router selects
     * exactly one route per generation: (a) adopted refusal →
     * closure rules; (a') adopted cancel-token closure →
     * closed-generation handling; (b) adopted current R2 →
     * the R2 route; (d) trial-not-started R1 (notified only)
     * → the R1 rules; otherwise the ordered procedure with
     * judgement classification (a)/(b)/(c). Classification (c)
     * throws {@link InconsistencyException} with everything
     * preserved. Returns human-readable notes per action.
     */
    public List<String> recover() throws Exception {
        List<String> notes = new ArrayList<>();
        TransferState.InitRecord init = TransferState.loadInit(storeRoot);
        if (init == null) {
            notes.add("no initialisation record; nothing to recover");
            return notes;
        }
        TransferState state = state();
        if (state.existsOnDisk()) state.adopt();
        // Ordered procedure step (2): frame verification and
        // repair of the promotion journal. A torn tail is cut
        // and the cut is logged; mid-file damage is evidence
        // for classification (c), never cut.
        File pj = TransferStore.promotionJournal(storeRoot);
        TransferIo.FramedRead fr = TransferIo.readFramed(pj);
        if (fr.tornTail) {
            TransferIo.truncateTo(pj, fr.validPrefixBytes);
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("kind", "recovery_note");
            note.put("detail", "frame_repair: torn tail truncated at "
                    + fr.validPrefixBytes);
            TransferIo.appendFramed(pj, note);
            notes.add("promotion journal torn tail repaired");
            fr = TransferIo.readFramed(pj);
        }
        if (fr.midDamage) {
            throw new InconsistencyException(
                    "promotion journal has mid-file frame damage");
        }
        // Audit H-4: sweep plaintext expansion debris from
        // every staging root on disk, whether or not a staging
        // record survived for it (a process death inside the
        // expansion window leaves debris and no record). The
        // sweep is a standalone operation since H-07 (the
        // app layers run it ahead of every transfer
        // operation); recover() reuses it here.
        notes.addAll(sweepExpansionDebris());
        // Audit M10: the superseded-copy sweep runs BEFORE
        // the per-generation classification, so the states
        // it completes — closures whose erasure was cut off
        // midway — are what the classification then finds.
        // (Audit L18's change/steady split is merged back
        // into this call's single note list, in execution
        // order.)
        sweepSupersededSeedCopies(state, notes, notes);
        List<Map<String, Object>> entries = fr.entries;
        for (String deviceId
                : new ArrayList<>(state.identities().keySet())) {
            for (long gen : state.receiverGenerations(deviceId)) {
                Map<String, Object> rec = state.receiver(deviceId, gen);
                recoverGeneration(state, rec, deviceId, gen, entries,
                        notes);
            }
        }
        return notes;
    }

    /**
     * The audit M10 sweep, callable on its own: the app
     * layers run it when the app / transfer service starts,
     * so the crash-window retry below does not wait for a
     * full recovery classification. Loads and adopts the
     * state the way {@link #recover()} does; a store with
     * no initialisation record has nothing to sweep.
     * Returns notes naming what was swept (empty when
     * nothing was due).
     */
    public List<String> sweepSupersededSeedCopies()
            throws Exception {
        List<String> notes = new ArrayList<>();
        TransferState.InitRecord init =
                TransferState.loadInit(storeRoot);
        if (init == null) {
            return notes;
        }
        TransferState state = state();
        if (state.existsOnDisk()) state.adopt();
        sweepSupersededSeedCopies(state, notes, notes);
        return notes;
    }

    /**
     * The standalone sweep's notes, split by kind (audit
     * L18): {@code changes} names what the sweep DID — a
     * vault copy deleted, a staging wrap invalidated — and
     * {@code steady} names what it deliberately LEFT, in a
     * state that persists for exactly as long as the
     * underlying collision does. M13 required the sweep's
     * outcome to reach the durable record, and round 5
     * persisted every note alike: a legitimate collision
     * then re-wrote the same two "left in place" notes on
     * every app open and every transfer operation, growing
     * the error log with steady-state noise and covering
     * the most recent REAL error in the companion's
     * "last recorded error" display. The split lets the
     * app layers persist changes unconditionally (M13's
     * requirement is about mutations) and treat steady
     * notes as the deduplicated, separately-kinded record
     * they are.
     */
    public static final class SweepNotes {
        public final List<String> changes = new ArrayList<>();
        public final List<String> steady = new ArrayList<>();
    }

    /** The standalone sweep with its notes classified
     *  (audit L18); see {@link SweepNotes}. */
    public SweepNotes sweepSupersededSeedCopiesClassified()
            throws Exception {
        SweepNotes out = new SweepNotes();
        TransferState.InitRecord init =
                TransferState.loadInit(storeRoot);
        if (init == null) {
            return out;
        }
        TransferState state = state();
        if (state.existsOnDisk()) state.adopt();
        sweepSupersededSeedCopies(state, out.changes, out.steady);
        return out;
    }

    /**
     * Audit M10 — the second envelope, swept at last.
     *
     * Round 2 (F8) taught the two S2 closures to erase the
     * vault's receiverKey for every committed generation
     * they supersede. Two gaps remained. First, the staging
     * record of such a generation carries the SAME seed in
     * a second wrap (the staging wrap), and nothing ever
     * invalidated it: for committed_oneshot — which by
     * design never receives an ack, the one event that
     * deletes a staging root — the wrap survived
     * indefinitely, so "no second envelope" held for the
     * vault and not for staging. Second, the closures save
     * the S2 record and THEN erase; a process death between
     * the two left the copies in place with no path that
     * ever retried.
     *
     * This sweep is that retry, and it is idempotent: for
     * every committed generation whose absence the recovery
     * allowance already explains (an S2 at a higher
     * generation in the sender history, and the active
     * binding no longer naming this generation as the held
     * receiver copy — {@link #seedAbsenceExplained}, the
     * exact F8 range), it deletes the vault copy if one
     * survives and invalidates the staging record's wrapped
     * seed. The staging RECORD is kept in every case: a
     * committed_r2 generation still awaiting its ack needs
     * its confirm_secret to re-issue the receipt, and the
     * seed is the only thing the sweep takes. A second run
     * over a swept store changes nothing.
     *
     * Audit F10: a vault copy is taken only when it is
     * proven — from its own bytes — to derive the identity
     * whose record is being swept. The generation number
     * alone never authorises an erasure: the vault files
     * copies under bare generation numbers shared by every
     * identity in the store, and the sender-history
     * comparison in {@link #seedAbsenceExplained} crosses
     * two independent counters, so a foreign lineage's copy
     * can sit under this generation. Such a copy is left in
     * place, never silently erased here.
     *
     * Audit M13: the record's OWN staging wrap is a
     * separate envelope with its own proof. Round 3 let
     * the F10 skip shield it: a superseded identity's
     * wrapped seed survived for exactly as long as the
     * collision lasted. The wrap is now judged on its own
     * bytes — invalidated when the wrapped seed derives
     * this record's identity, whatever the vault slot
     * holds — and the recovery classification below treats
     * an explained absence whose slot copy derives another
     * in-store record as the legitimate occupancy it is,
     * instead of stopping on it as an inconsistency.
     *
     * Audit L18: the notes are collected into two lists.
     * {@code changes} takes the notes that report a
     * mutation this run performed; {@code steady} takes
     * the notes that report a deliberate non-action in a
     * persisting state (a foreign vault copy left in
     * place, an unprovable wrap left in place), which
     * would otherwise repeat verbatim on every run for as
     * long as the collision lasts. Callers that want one
     * list pass the same list twice.
     */
    private void sweepSupersededSeedCopies(TransferState state,
                                           List<String> changes,
                                           List<String> steady)
            throws Exception {
        for (String deviceId
                : new ArrayList<>(state.identities().keySet())) {
            for (long gen : state.receiverGenerations(deviceId)) {
                Map<String, Object> rec =
                        state.receiver(deviceId, gen);
                Map<String, Object> closure =
                        TransferState.child(rec, "closure");
                if (closure == null) {
                    continue;
                }
                String kind = TransferState.stringOr(
                        closure, "kind", null);
                if (!"committed_r2".equals(kind)
                        && !"committed_oneshot".equals(kind)) {
                    continue;
                }
                if (!seedAbsenceExplained(state, deviceId, gen)) {
                    continue;
                }
                boolean vaultCopyForeign = false;
                if (vault.has(TransferVault.receiverKey(gen))) {
                    // Audit F10: the vault namespace is
                    // generation-only, shared by every
                    // identity in the store, and the
                    // classification above compares generation
                    // NUMBERS across two independent counters
                    // (receiver generations against sender
                    // history). A copy filed under this
                    // generation can therefore belong to a
                    // different lineage than this record —
                    // exactly the state the recovery
                    // classification below used to surface as
                    // "seed does not derive the identity"
                    // before this sweep ran ahead of it. The
                    // copy is erased only when it is PROVEN to
                    // derive this record's identity; an
                    // unproven (foreign or unreadable) copy is
                    // left untouched — vault entry and staging
                    // wrap alike — for the classification to
                    // find, and the skip is noted rather than
                    // silent.
                    byte[] copy = null;
                    boolean proven = false;
                    try {
                        copy = vault.get(
                                TransferVault.receiverKey(gen));
                        proven = copy != null
                                && TransferRemediation
                                        .seedDerivesIdentity(
                                                copy, deviceId);
                    } catch (Exception e) {
                        proven = false;
                    } finally {
                        if (copy != null) {
                            java.util.Arrays.fill(copy, (byte) 0);
                        }
                    }
                    if (proven) {
                        vault.delete(
                                TransferVault.receiverKey(gen));
                        changes.add("gen " + gen
                                + ": superseded receiver copy "
                                + "swept (audit M10)");
                    } else {
                        // Audit M13: the unproven copy stays
                        // — but the skip no longer takes this
                        // record's OWN staging wrap with it.
                        // Round 3's continue left a
                        // superseded identity's second
                        // envelope in place for exactly as
                        // long as the collision lasted, which
                        // can be permanent. The wrap is
                        // judged below, on its own proof.
                        steady.add("gen " + gen
                                + ": superseded-copy sweep left "
                                + "the vault copy in place: it "
                                + "does not derive this identity "
                                + "(audit F10)");
                        vaultCopyForeign = true;
                    }
                }
                // The record's stored staging path, read
                // without stagingRootOf's throw: a committed
                // record that somehow lacks one must not stop
                // the sweep (or the recovery around it) — its
                // staging simply is not this sweep's to touch.
                String rel = TransferState.stringOr(
                        rec, "staging_root", null);
                if (rel == null) {
                    continue;
                }
                File root = new File(storeRoot, rel);
                TransferStore.StagingRecord sr =
                        TransferStore.loadStaging(root);
                if (sr != null && !sr.seedErased()) {
                    // Audit M13: the wrap is THIS record's
                    // own second envelope, and it is taken
                    // only on the same standard the vault
                    // copy gets — the wrapped seed must
                    // derive THIS record's identity, proven
                    // from the wrap itself. The proof is the
                    // wrap's own, independent of the vault
                    // slot: a foreign vault copy no longer
                    // shields it, and an unprovable wrap is
                    // left in place, noted, for the recovery
                    // classification to find. The app layers
                    // persist these notes to the durable
                    // error log (audit M13), so the
                    // invalidation is on the record, not
                    // only in this return value.
                    boolean wrapProven = false;
                    byte[] wseed = null;
                    try {
                        wseed = unwrapSeed(sr);
                        wrapProven = wseed != null
                                && TransferRemediation
                                        .seedDerivesIdentity(
                                                wseed, deviceId);
                    } catch (Exception e) {
                        wrapProven = false;
                    } finally {
                        if (wseed != null) {
                            java.util.Arrays.fill(wseed,
                                    (byte) 0);
                        }
                    }
                    if (wrapProven) {
                        sr.eraseWrappedSeed();
                        TransferStore.saveStaging(root, sr);
                        changes.add(vaultCopyForeign
                                ? "gen " + gen
                                        + ": superseded staging "
                                        + "seed wrap invalidated "
                                        + "on its own proof, the "
                                        + "vault copy being "
                                        + "foreign (audit M13)"
                                : "gen " + gen
                                        + ": superseded staging "
                                        + "seed wrap invalidated "
                                        + "(audit M10)");
                    } else {
                        steady.add("gen " + gen
                                + ": superseded-copy sweep "
                                + "left the staging seed wrap "
                                + "in place: it could not be "
                                + "proven to derive this "
                                + "identity (audit M13)");
                    }
                }
            }
        }
    }

    /**
     * Whether the vault slot for {@code generation} holds
     * a key that derives {@code deviceId} (audit F11). The
     * slot is keyed by the bare generation number, shared
     * by every lineage in the store (audit F10), so
     * existence is never identity: the copy must prove
     * itself from its own bytes, exactly as the F10 sweep
     * requires before an erasure. An absent slot answers
     * false (callers distinguish "absent" with vault.has);
     * an unreadable slot throws, which every caller treats
     * as a stop.
     */
    private boolean slotHoldsIdentityKey(long generation,
                                         String deviceId)
            throws Exception {
        byte[] copy = vault.get(
                TransferVault.receiverKey(generation));
        if (copy == null) {
            return false;
        }
        try {
            return TransferRemediation.seedDerivesIdentity(
                    copy, deviceId);
        } finally {
            java.util.Arrays.fill(copy, (byte) 0);
        }
    }

    /**
     * True when a committed generation's missing vault seed
     * is EXPLAINED (audit F5): the identity was later sent
     * onward FROM this store — an S2 sender-history entry at
     * a higher generation, whose closure legitimately erased
     * the sender-side copies and with them, in an app layer
     * that holds the received copy in the same vault, the
     * receiver key — and the active binding no longer names
     * this generation as the held receiver copy (if it did,
     * the live identity would have no seed anywhere: a real
     * inconsistency). Any other absence stays an
     * inconsistency, exactly as before.
     */
    private boolean seedAbsenceExplained(TransferState state,
                                         String deviceId, long gen) {
        Map<String, Object> idSection = state.identity(deviceId);
        if (idSection == null) {
            return false;
        }
        boolean sentOnward = false;
        for (Map<String, Object> h : historyEntries(
                idSection, "sender_history")) {
            if ("S2".equals(TransferState.stringOr(
                    h, "state", null))
                    && TransferState.longOr(
                            h, "generation", -1) > gen) {
                sentOnward = true;
            }
        }
        if (!sentOnward) {
            return false;
        }
        Map<String, Object> active = state.active();
        return active == null
                || !deviceId.equals(TransferState.stringOr(
                        active, "device_id", null))
                || !"receiver".equals(TransferState.stringOr(
                        active, "role", null))
                || TransferState.longOr(
                        active, "generation", -1) != gen;
    }

    /**
     * Whether the copy under {@code gen} derives an
     * identity OTHER than {@code deviceId} that holds its
     * own receiver record for that generation in this
     * store (audit M13): the legitimate-occupancy half of
     * the recovery allowance. The other identity's record
     * must exist — a copy deriving an identity this store
     * has never received explains nothing and stops the
     * classification, as before.
     *
     * Audit L19: existence of the record is not enough —
     * the record must be one that SHOULD hold the seed.
     * Round 5's form also covered a record whose trial was
     * CANCELLED after step 8-2 placed its key: the leftover
     * key read as a legitimate occupancy for this record's
     * classification, while the cancelled identity's own
     * classification never looks at the vault again, so
     * the residue was undetectable. The allowance is now
     * limited to a record that is committed with its
     * absence unexplained (the copy is its live holding),
     * or whose adopted trial is still in progress (the
     * placement is that trial's, mid-flight). Cancellation
     * removes the key it placed (cancelTrialInternal), so
     * a cancelled record's key should not be here at all;
     * if one is (a store written before that removal), it
     * no longer buys an allowance — the classification
     * stops on it, by name, as it did before M13.
     */
    private boolean slotOccupiedByOtherRecord(
            TransferState state, String deviceId, long gen,
            byte[] seed) {
        for (String other : state.identities().keySet()) {
            if (other.equals(deviceId)) {
                continue;
            }
            Map<String, Object> orec = state.receiver(other, gen);
            if (orec == null
                    || !TransferRemediation.seedDerivesIdentity(
                            seed, other)) {
                continue;
            }
            Map<String, Object> closure =
                    TransferState.child(orec, "closure");
            if (closure != null) {
                String kind = TransferState.stringOr(
                        closure, "kind", null);
                if (("committed_r2".equals(kind)
                        || "committed_oneshot".equals(kind))
                        && !seedAbsenceExplained(
                                state, other, gen)) {
                    return true;
                }
                continue;
            }
            Map<String, Object> trial =
                    TransferState.child(orec, "trial");
            if (trial != null
                    && TransferState.boolOr(
                            trial, "plan_adopted", false)
                    && !isTerminalTrial(trial)) {
                return true;
            }
        }
        return false;
    }

    private void recoverGeneration(TransferState state,
                                   Map<String, Object> rec,
                                   String deviceId, long gen,
                                   List<Map<String, Object>> entries,
                                   List<String> notes) throws Exception {
        Map<String, Object> closure = TransferState.child(rec, "closure");
        if (closure != null) {
            String kind = TransferState.stringOr(closure, "kind", null);
            if ("refusal".equals(kind)) {
                pursueRestoration(state, rec, deviceId, gen, notes);
                return;
            }
            if ("cancel_token".equals(kind)) {
                File root = stagingRootOf(rec, deviceId);
                if (root.exists()) {
                    TransferIo.deleteTree(root);
                    notes.add("gen " + gen
                            + ": token-closed staging discarded");
                }
                return;
            }
            if ("committed_r2".equals(kind)) {
                // (b) R2 route: verify live + seed + materials;
                // never run plan processing for this generation.
                if (!vault.has(TransferVault.receiverKey(gen))) {
                    if (seedAbsenceExplained(state, deviceId, gen)) {
                        notes.add("gen " + gen
                                + ": R2 durable; seed copy absent "
                                + "(identity sent onward from this "
                                + "device; tolerated, audit F5)");
                        return;
                    }
                    throw new InconsistencyException(
                            "R2 generation " + gen
                                    + " without its seed in the vault");
                }
                byte[] seed = vault.get(TransferVault.receiverKey(gen));
                if (!deviceId.equals(DepinCore.deviceIdFromPubkey(
                        Ed25519.publickeyFromSeed(seed)))) {
                    // Audit M13: a mismatch is a LEGITIMATE
                    // occupancy when this record's absence is
                    // explained (the identity was sent onward;
                    // its copy was lawfully erased) AND the
                    // copy under its generation proves itself
                    // another in-store identity's key for that
                    // generation — the F10 collision's
                    // steady state, not damage. Anything else
                    // (an unexplained absence, a copy no
                    // record claims) still stops here, by
                    // name, exactly as before.
                    if (seedAbsenceExplained(state, deviceId, gen)
                            && slotOccupiedByOtherRecord(
                                    state, deviceId, gen, seed)) {
                        notes.add("gen " + gen
                                + ": R2 seed slot legitimately "
                                + "occupied by another "
                                + "identity's copy for this "
                                + "generation; this record's "
                                + "absence is explained "
                                + "(audit M13)");
                        return;
                    }
                    throw new InconsistencyException(
                            "R2 seed does not derive the identity");
                }
                if (!TransferState.boolOr(rec, "ack_received", false)) {
                    notes.add("gen " + gen
                            + ": R2 durable; receipt re-issuable");
                } else {
                    notes.add("gen " + gen + ": R2 complete");
                }
                return;
            }
            if ("committed_oneshot".equals(kind)) {
                // One-shot completion: the same vault-seed
                // consistency check; no receipt can be issued
                // and no ack is outstanding, so nothing else
                // remains for this generation.
                if (!vault.has(TransferVault.receiverKey(gen))) {
                    if (seedAbsenceExplained(state, deviceId, gen)) {
                        notes.add("gen " + gen
                                + ": one-shot complete; seed copy "
                                + "absent (identity sent onward "
                                + "from this device; tolerated, "
                                + "audit F5)");
                        return;
                    }
                    throw new InconsistencyException(
                            "one-shot generation " + gen
                                    + " without its seed in the vault");
                }
                byte[] seed = vault.get(TransferVault.receiverKey(gen));
                if (!deviceId.equals(DepinCore.deviceIdFromPubkey(
                        Ed25519.publickeyFromSeed(seed)))) {
                    // Audit M13: the legitimate-occupancy
                    // allowance, as in the R2 branch above.
                    if (seedAbsenceExplained(state, deviceId, gen)
                            && slotOccupiedByOtherRecord(
                                    state, deviceId, gen, seed)) {
                        notes.add("gen " + gen
                                + ": one-shot seed slot "
                                + "legitimately occupied by "
                                + "another identity's copy for "
                                + "this generation; this "
                                + "record's absence is explained "
                                + "(audit M13)");
                        return;
                    }
                    throw new InconsistencyException(
                            "one-shot seed does not derive the identity");
                }
                notes.add("gen " + gen + ": one-shot R2 complete");
                return;
            }
            throw new InconsistencyException(
                    "unknown closure kind: " + kind);
        }
        Map<String, Object> trial = TransferState.child(rec, "trial");
        File root = stagingRootOf(rec, deviceId);
        TransferStore.StagingRecord sr = TransferStore.loadStaging(root);
        if (trial == null) {
            if (TransferState.boolOr(rec, "notified_r1", false)) {
                // (d) trial-not-started R1 — the holding basis
                // is the NOTIFIED R1 record only (rev.23).
                if (sr == null) {
                    throw new InconsistencyException(
                            "notified R1 without a staging record");
                }
                if (sr.nextAttempt != 0 || sr.currentAttempt != null) {
                    throw new InconsistencyException(
                            "attempt ledger advanced without a trial "
                                    + "record; not route (d)");
                }
                fixPrePromotionManifest(state, deviceId);
                notes.add("gen " + gen + ": R1 ready");
            } else {
                notes.add("gen " + gen
                        + ": unnotified staging (no holding basis; "
                        + "re-verify via intake or discard)");
            }
            return;
        }
        String trialId = TransferState.stringOr(trial, "trial_id", null);
        String stage = TransferState.stringOr(trial, "stage", null);
        if (isTerminalTrial(trial)) {
            notes.add("gen " + gen + ": trial terminal (" + stage + ")");
            return;
        }
        if ("start_blocked".equals(stage)) {
            notes.add("gen " + gen + ": start-blocked terminal stands");
            return;
        }
        // Step (4): orphan trial record (attempt ledger still
        // at 0 while a trial record exists).
        if (sr != null && sr.nextAttempt == 0
                && sr.currentAttempt == null) {
            Map<String, Object> judgement =
                    TransferState.child(trial, "judgement");
            boolean undecided = judgement != null && "undecided".equals(
                    TransferState.stringOr(judgement, "kind", null));
            boolean noProgress = true;
            for (Map<String, Object> e : entries) {
                if (trialId.equals(e.get("trial_id"))) {
                    noProgress = false;
                }
            }
            TransferStore.LiveManifest lm =
                    TransferStore.loadLiveManifest(storeRoot);
            boolean liveUnchanged = lm != null
                    && TransferStore.liveMatches(storeRoot, lm);
            if (undecided && noProgress && liveUnchanged) {
                trial.put("stage", "orphaned");
                trial.put("orphaned", true);
                state.save();
                notes.add("gen " + gen
                        + ": orphan trial record quarantined; R1 "
                        + "continues");
                return;
            }
            throw new InconsistencyException(
                    "orphan trial record cannot be confirmed "
                            + "progress-free (gen " + gen + ")");
        }
        if (sr == null) {
            throw new InconsistencyException(
                    "trial without a staging record (gen " + gen + ")");
        }
        // Step (6): judgement classification.
        Map<String, Object> judgement = TransferState.child(trial, "judgement");
        if (judgement == null) {
            throw new InconsistencyException(
                    "trial without a judgement field (gen " + gen + ")");
        }
        String jkind = TransferState.stringOr(judgement, "kind", null);
        if ("confirm".equals(jkind)) {
            recoverJudgedTrial(state, rec, sr, trial, entries, notes);
        } else if ("cancel".equals(jkind)) {
            Map<String, Object> plan = null;
            if (TransferState.boolOr(trial, "plan_adopted", false)) {
                plan = findPlan(entries, trialId,
                        TransferState.stringOr(trial, "plan_hash", null));
                if (plan == null) {
                    throw new InconsistencyException(
                            "adopted plan missing in recovery");
                }
            }
            cancelTrialInternal(state, rec, sr, trial, plan);
            notes.add("gen " + gen + ": cancellation completed");
        } else if ("undecided".equals(jkind)) {
            recoverUndecidedTrial(state, rec, sr, trial, entries, notes);
        } else {
            throw new InconsistencyException(
                    "unknown judgement kind: " + jkind);
        }
    }

    /**
     * The promotion preamble for the RECOVERY routes (final
     * check round 1, F-20 (ii)): planAndPromote opens with
     * the slot-lineage gate, the same-identity writer freeze
     * and the foreign-quarantine step, but the recovery
     * branches used to enter executePromotion directly —
     * the reconstruction branch rebuilt a plan and promoted
     * without the freeze, and the adopted branch promoted
     * with neither the freeze nor a quarantine completion.
     * Every recovery route that can promote now passes the
     * same three gates first, in the same order — with the
     * slot gate in its recovery (inconsistency) form; see
     * the note inside. (The reconstruction branch ALSO
     * keeps its pre-adoption hold — audit L25 (2) — so
     * nothing is adopted before the slot proof there
     * either.)
     */
    private void recoveryPromotionPreamble(TransferState state,
                                           Map<String, Object> rec,
                                           TransferStore.StagingRecord sr)
            throws Exception {
        // The slot-lineage gate, with RECOVERY semantics:
        // the interactive route refuses this shape as a hold
        // (holdIfSlotHeldByOtherLineage — the trial may be
        // resumed once the slot frees), but on the recovery
        // route the same shape has always been judged an
        // inconsistency by placePromotedSeed's point-of-use
        // proof (audit F11/M14: recovery over a foreign key
        // is evidence, not a wait), and the harness pins
        // that verdict. The check is therefore made here in
        // its inconsistency form; the freeze and quarantine
        // steps below are the F-20 additions proper.
        if (vault.has(TransferVault.receiverKey(sr.generation))
                && !slotHoldsIdentityKey(sr.generation, sr.deviceId)) {
            throw new InconsistencyException(
                    "the vault slot for generation " + sr.generation
                            + " holds another identity's key; "
                            + "recovery stops before anything moves "
                            + "(audit F11/M14, via the F-20 preamble)");
        }
        if (foreignFreeze != null) {
            Map<String, Object> activeNow = state.active();
            if (activeNow != null && sr.deviceId.equals(
                    TransferState.stringOr(
                            activeNow, "device_id", null))) {
                foreignFreeze.freezeForeignWriter(sr.deviceId);
            }
        }
        Map<String, Object> fq = TransferState.child(
                rec, KEY_FOREIGN_QUARANTINE);
        if (fq != null) {
            String fqState = TransferState.stringOr(fq, "state", null);
            if ("moving".equals(fqState)
                    || "quarantined".equals(fqState)) {
                quarantineForeignPlacement(state, rec, sr.deviceId);
            }
        }
    }

    /** Classification (a): judgement exists and is verified.
     *  A missing completion entry (or a torn one) is
     *  materialisation FROM the judgement field; a well-formed
     *  entry contradicting the judgement is evidence → (c). */
    private void recoverJudgedTrial(TransferState state,
                                    Map<String, Object> rec,
                                    TransferStore.StagingRecord sr,
                                    Map<String, Object> trial,
                                    List<Map<String, Object>> entries,
                                    List<String> notes) throws Exception {
        String trialId = TransferState.stringOr(trial, "trial_id", null);
        Map<String, Object> judgement = TransferState.child(trial, "judgement");
        Map<String, Object> attest = TransferState.child(judgement, "attest");
        if (attest == null || !TransferState.stringOr(
                trial, "permit_hash", "").equals(TransferState.stringOr(
                        judgement, "permit_hash", null))) {
            throw new InconsistencyException(
                    "judgement field is malformed");
        }
        // Final check round 1, F-07: a judged trial of a
        // generation OLDER than the committed maximum is
        // closed as superseded — never promoted, never
        // materialised. (Before the F-07 confirm holds, a
        // newer generation could commit while this trial sat
        // judged-but-unmaterialised, and this recovery then
        // rewound the binding and held_generation to the
        // older generation.) The trial record is the only
        // thing written; the staging and the live placement
        // are left exactly as they are.
        Map<String, Object> idSecJ = state.identity(sr.deviceId);
        long maxCommittedJ = idSecJ == null ? 0
                : TransferState.longOr(
                        idSecJ, "max_committed_generation", 0);
        if (sr.generation < maxCommittedJ) {
            trial.put("stage", "superseded");
            state.save();
            notes.add("gen " + sr.generation + ": judged trial "
                    + "superseded by committed generation "
                    + maxCommittedJ + "; closed without "
                    + "materialising (F-07)");
            return;
        }
        if ("oneshot".equals(TransferState.stringOr(
                trial, "mode", null))) {
            // One-shot: no permit exists to supplement or to
            // expire, so the permit checks below have nothing
            // to bind to. The completion-entry consistency
            // checks still apply, then materialisation proceeds
            // from the judgement field as in the strict mode.
            for (Map<String, Object> e : entries) {
                if ("complete".equals(e.get("kind"))
                        && trialId.equals(e.get("trial_id"))) {
                    Map<String, Object> eAttest =
                            TransferState.child(e, "attest");
                    if (eAttest == null || !TransferCodec.jcsEquals(
                            eAttest, attest)) {
                        throw new InconsistencyException(
                                "a well-formed completion entry "
                                        + "contradicts the judgement "
                                        + "field");
                    }
                }
            }
            if (!TransferState.boolOr(trial, "plan_adopted", false)) {
                throw new InconsistencyException(
                        "judged trial without an adopted plan");
            }
            Map<String, Object> plan = findPlan(entries, trialId,
                    TransferState.stringOr(trial, "plan_hash", null));
            if (plan == null) {
                throw new InconsistencyException(
                        "adopted plan missing in recovery");
            }
            recoveryPromotionPreamble(state, rec, sr);
            assertDecidedTrialSeed(sr);
            executePromotion(plan, trialId);
            materialize(state, rec, sr, trial, plan, attest);
            notes.add("gen " + sr.generation + ": R2 materialised "
                    + "from the judgement field (one-shot)");
            return;
        }
        if (sr.permitFull == null) {
            // Supplement first: the classification is re-run
            // after the permit's full value is restored via
            // supplyPermitForRecovery.
            throw new HoldException(
                    "permit full value missing; re-present the permit "
                            + "for supplement intake");
        }
        long expWall = Json.asLong(sr.permitFull.get("expires_at_unix"));
        long expFin = Json.asLong(
                sr.permitFull.get("expires_at_finalized"));
        if (Json.asLong(attest.get("finalized")) >= expFin
                || Json.asLong(attest.get("wall_unix")) >= expWall) {
            throw new InconsistencyException(
                    "attest values are outside the bound permit's "
                            + "validity");
        }
        for (Map<String, Object> e : entries) {
            if ("complete".equals(e.get("kind"))
                    && trialId.equals(e.get("trial_id"))) {
                Map<String, Object> eAttest =
                        TransferState.child(e, "attest");
                if (eAttest == null || !TransferCodec.jcsEquals(
                        eAttest, attest)) {
                    throw new InconsistencyException(
                            "a well-formed completion entry contradicts "
                                    + "the judgement field");
                }
            }
        }
        if (!TransferState.boolOr(trial, "plan_adopted", false)) {
            throw new InconsistencyException(
                    "judged trial without an adopted plan");
        }
        Map<String, Object> plan = findPlan(entries, trialId,
                TransferState.stringOr(trial, "plan_hash", null));
        if (plan == null) {
            throw new InconsistencyException(
                    "adopted plan missing in recovery");
        }
        recoveryPromotionPreamble(state, rec, sr);
        assertDecidedTrialSeed(sr);
        executePromotion(plan, trialId);
        materialize(state, rec, sr, trial, plan, attest);
        notes.add("gen " + sr.generation + ": R2 materialised "
                + "from the judgement field");
    }

    /** Classification (b): no judgement, confirmed from the
     *  judgement field itself. Resume promotion idempotently,
     *  then take the judgement by the ordinary route (CHECK-2
     *  → dual clocks → judgement field) — never by a recovery
     *  shortcut. A completion-entry candidate under an
     *  undecided judgement is a contradiction → (c). */
    private void recoverUndecidedTrial(TransferState state,
                                       Map<String, Object> rec,
                                       TransferStore.StagingRecord sr,
                                       Map<String, Object> trial,
                                       List<Map<String, Object>> entries,
                                       List<String> notes)
            throws Exception {
        String trialId = TransferState.stringOr(trial, "trial_id", null);
        for (Map<String, Object> e : entries) {
            if ("complete".equals(e.get("kind"))
                    && trialId.equals(e.get("trial_id"))) {
                throw new InconsistencyException(
                        "completion-entry candidate exists under an "
                                + "undecided judgement");
            }
        }
        Map<String, Object> plan;
        if (TransferState.boolOr(trial, "plan_adopted", false)) {
            plan = findPlan(entries, trialId,
                    TransferState.stringOr(trial, "plan_hash", null));
            if (plan == null) {
                throw new InconsistencyException(
                        "adopted plan missing in recovery");
            }
            // Audit M14 (b): step 8-2 runs HERE, on the
            // actual re-entry route — a crash between the
            // adoption save and the vault put left the plan
            // adopted with the seed unplaced, and this
            // branch used to promote anyway, committing a
            // generation whose seed was never in the vault.
            // The placement (or its proof) precedes every
            // file movement.
            // F-20 (ii): the promotion preamble (slot hold,
            // same-identity writer freeze, quarantine
            // completion) precedes it, as in planAndPromote.
            recoveryPromotionPreamble(state, rec, sr);
            placePromotedSeed(sr, sr.generation, sr.deviceId);
            executePromotion(plan, trialId);
        } else {
            // Plan handling step (3)(ii): a complete plan file
            // without the adoption marker is adopted only if a
            // canonical reconstruction matches it in full; any
            // progress with no marker is (c).
            Map<String, Object> found = null;
            boolean progress = false;
            for (Map<String, Object> e : entries) {
                if (trialId.equals(e.get("trial_id"))) {
                    if ("plan".equals(e.get("kind"))) {
                        found = TransferState.child(e, "plan");
                    } else {
                        progress = true;
                    }
                }
            }
            if (progress) {
                throw new InconsistencyException(
                        "promotion progress without a plan adoption "
                                + "marker");
            }
            if (found != null) {
                Map<String, Object> rebuilt =
                        buildPlan(state, rec, sr, trial);
                if (!TransferCodec.jcsEquals(rebuilt, found)) {
                    throw new InconsistencyException(
                            "unadopted plan differs from the canonical "
                                    + "reconstruction");
                }
                // Audit L25 (2): the same slot-lineage gate
                // planAndPromote opens with, BEFORE the
                // adoption save below. Round 6 put step 8-2
                // after the adoption here, so a slot that
                // changed lineage since the plan was written
                // stopped this branch only after it adopted —
                // "adopt, then stop", the ordering M14 (a)
                // removed everywhere else. Nothing has been
                // adopted yet at this point; the hold leaves
                // the trial exactly as it was found.
                holdIfSlotHeldByOtherLineage(sr.generation,
                        sr.deviceId);
                String hash = Hex.encode(DepinCore.sha256(
                        TransferCodec.jcs(found)));
                trial.put("plan_hash", hash);
                trial.put("plan_adopted", true);
                trial.put("stage", "planned");
                state.save();
                plan = found;
                // Audit M14 (b): as in the adopted branch
                // above — the adoption save just landed, so
                // this is exactly the crash window's far
                // side; step 8-2 precedes the promotion.
                // F-20 (ii): the promotion preamble runs
                // here too (its slot hold repeats the
                // pre-adoption one above — a pure check).
                recoveryPromotionPreamble(state, rec, sr);
                placePromotedSeed(sr, sr.generation,
                        sr.deviceId);
                executePromotion(plan, trialId);
            } else {
                // No plan, no progress: re-plan with the SAME
                // trial ID (the main route for stage "started").
                plan = planAndPromote(state, rec, sr, trial);
            }
        }
        if ("oneshot".equals(TransferState.stringOr(
                trial, "mode", null))) {
            judgementStepOneshot(state, rec, sr, trial, plan);
        } else {
            judgementStep(state, rec, sr, trial, plan);
        }
        notes.add("gen " + sr.generation
                + ": undecided trial resumed and judged");
    }

    /** Pursue the restoration obligation of a refusal-closed
     *  generation: restore the pre-promotion placement from
     *  the backup, verify against the plan snapshot, return
     *  the live manifest, and only then mark restoration
     *  complete. A damaged/missing backup is surfaced as the
     *  loss path (never silently passed). */
    private void pursueRestoration(TransferState state,
                                   Map<String, Object> rec,
                                   String deviceId, long gen,
                                   List<String> notes) throws Exception {
        if (!"pending".equals(
                TransferState.stringOr(rec, "restoration", null))) {
            return;
        }
        Map<String, Object> trial = TransferState.child(rec, "trial");
        if (trial == null || !TransferState.boolOr(
                trial, "plan_adopted", false)) {
            // Nothing was promoted — but a foreign placement
            // may still sit in quarantine from an interrupted
            // plan build (audit C-2); returning it is part of
            // the restoration.
            restoreForeignAfterCancel(state, rec, deviceId);
            rec.put("restoration", "complete");
            state.save();
            notes.add("gen " + gen + ": restoration complete "
                    + "(nothing had moved)");
            return;
        }
        String trialId = TransferState.stringOr(trial, "trial_id", null);
        Map<String, Object> plan = findPlan(readPromotionEntries(),
                trialId, TransferState.stringOr(trial, "plan_hash", null));
        if (plan == null) {
            throw new InconsistencyException(
                    "restoration without the adopted plan");
        }
        restoreFromPlan(plan, trialId, stagingRootOf(rec, deviceId));
        Map<String, Object> oldFiles = TransferState.child(
                TransferState.child(plan, "old_manifest"), "files");
        Map<String, String> expect = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : oldFiles.entrySet()) {
            expect.put(e.getKey(), (String) e.getValue());
        }
        TransferStore.LiveManifest now =
                TransferStore.snapshotLive(storeRoot, "existing", 0);
        if (now.files.equals(expect)) {
            TransferStore.LiveManifest cur =
                    TransferStore.loadLiveManifest(storeRoot);
            TransferStore.LiveManifest lm =
                    new TransferStore.LiveManifest();
            lm.files.putAll(expect);
            lm.origin = TransferState.stringOr(
                    TransferState.child(plan, "old_manifest"),
                    "origin", null);
            lm.sequence = (cur == null) ? 1 : cur.sequence + 1;
            TransferStore.saveLiveManifest(storeRoot, lm);
            // C-2 / F-21: the pre-trial placement of THIS store
            // may include a quarantined foreign placement;
            // returning it completes the restoration.
            restoreForeignQuarantine(state, rec);
            rec.put("restoration", "complete");
            state.save();
            notes.add("gen " + gen + ": restoration complete");
        } else {
            // The old placement cannot be restored from the
            // backup: surface the loss path (distinct from the
            // closure itself).
            Map<String, Object> loss =
                    TransferState.childOrCreate(rec, "loss");
            if (!loss.containsKey("state")) {
                loss.put("state", "unapproved");
                state.save();
            }
            notes.add("gen " + gen + ": restoration IMPOSSIBLE — "
                    + "loss path open (unapproved)");
        }
    }

    /** Loss path, approval step: the user approves treating
     *  the lost placement as quarantined. The gate stays
     *  closed; only {@link #adoptEmptyPlacement} opens it. */
    public void approveLoss(String deviceId, long generation)
            throws Exception {
        TransferState state = state();
        Map<String, Object> rec = state.receiver(deviceId, generation);
        Map<String, Object> loss = (rec == null)
                ? null : TransferState.child(rec, "loss");
        if (loss == null || !"unapproved".equals(
                TransferState.stringOr(loss, "state", null))) {
            throw new HoldException("no unapproved loss to approve");
        }
        loss.put("state", "approved_quarantined");
        state.save();
    }

    /** Loss path, final step: adopt the empty placement. The
     *  live manifest switches to the empty placement at THIS
     *  update (not at approval), and the final state update is
     *  bound to the adopted manifest's hash. */
    public void adoptEmptyPlacement(String deviceId, long generation)
            throws Exception {
        TransferState state = state();
        Map<String, Object> rec = state.receiver(deviceId, generation);
        Map<String, Object> loss = (rec == null)
                ? null : TransferState.child(rec, "loss");
        if (loss == null || !"approved_quarantined".equals(
                TransferState.stringOr(loss, "state", null))) {
            throw new HoldException("loss is not approved/quarantined");
        }
        TransferStore.LiveManifest cur =
                TransferStore.loadLiveManifest(storeRoot);
        TransferStore.LiveManifest lm =
                new TransferStore.LiveManifest();
        lm.origin = "after_loss";
        lm.sequence = (cur == null) ? 1 : cur.sequence + 1;
        TransferStore.saveLiveManifest(storeRoot, lm);
        loss.put("state", "empty_adopted");
        loss.put("manifest_hash", lm.sha256Hex());
        rec.put("restoration", "impossible");
        state.save();
    }

    // ---------------- receipt / ack (section 4.4 phase 2b) ----------------

    /** Issue (or re-issue) the receipt: possible in every
     *  state where R2 is durable and the completion ack has
     *  not been taken in. Materials are retained until the
     *  ack intake. */
    public Map<String, Object> buildReceipt(String deviceId,
                                            long generation)
            throws Exception {
        TransferState state = state();
        Map<String, Object> rec = state.receiver(deviceId, generation);
        if (rec == null) throw new HoldException("no such generation");
        Map<String, Object> closure = TransferState.child(rec, "closure");
        if (closure == null || !"committed_r2".equals(
                TransferState.stringOr(closure, "kind", null))) {
            throw new HoldException("receipt issues only after R2");
        }
        if (TransferState.boolOr(rec, "ack_received", false)) {
            throw new HoldException("completion ack already taken in");
        }
        File root = stagingRootOf(rec, deviceId);
        TransferStore.StagingRecord sr = TransferStore.loadStaging(root);
        if (sr == null) {
            throw new InconsistencyException(
                    "receipt materials (staging record) are gone");
        }
        byte[] secret = unwrapSecret(sr);
        return TransferPackage.buildReceipt(generation, state.installId(),
                TransferState.stringOr(rec, "package_sha256", null),
                secret);
    }

    /** Take in the completion ack: verify everything, FIRST
     *  persist "ack accepted" in this side's record, and only
     *  then delete the retained materials. The journal itself
     *  stays as the read-only reserve (it is the live journal
     *  now; the staging copies are what is deleted). */
    public void intakeAck(Map<String, Object> msg) throws Exception {
        long gen = Json.asLong(msg.get("transfer_generation"));
        String pkg = Json.asString(msg.get("package_sha256"));
        TransferState state = state();
        String deviceId = null;
        Map<String, Object> rec = null;
        for (String did : state.identities().keySet()) {
            Map<String, Object> r = state.receiver(did, gen);
            if (r != null && pkg.equals(TransferState.stringOr(
                    r, "package_sha256", null))) {
                deviceId = did;
                rec = r;
                break;
            }
        }
        if (rec == null) throw new HoldException("no matching generation");
        Map<String, Object> manifest = TransferState.child(rec, "manifest");
        byte[] pubkey = TransferCodec.hexBytes(
                TransferState.stringOr(manifest, "pubkey", null),
                32, "manifest.pubkey");
        TransferPackage.validateAck(msg, pubkey);
        if (!state.installId().equals(TransferState.stringOr(
                msg, "receiver_install_id", null))) {
            throw new IllegalArgumentException(
                    "ack names another installation");
        }
        Map<String, Object> closure = TransferState.child(rec, "closure");
        if (closure == null || !"committed_r2".equals(
                TransferState.stringOr(closure, "kind", null))) {
            throw new HoldException("ack before R2");
        }
        rec.put("ack_received", true);
        state.save();
        // Materials deletion happens only after the durable
        // acceptance above.
        File root = stagingRootOf(rec, deviceId);
        TransferIo.deleteTree(root);
    }

    // ---------------- abandon issuance / cancel token ----------------

    /**
     * Issue the cannot-confirm (abandon) response under the
     * design's terminal conditions: the trial's judgement
     * field does not show confirm, the trial stage is one of
     * (no trial / cancel complete / start-blocked), and — for
     * the permanent-refusal terminal — the caller has verified
     * the design's three conditions for the B-unobtainable
     * case separately. The refusal, the response's full value,
     * the trial stage "refused" and the restoration state are
     * saved in ONE transfer-record update; staging is not
     * discarded before that save.
     */
    public Map<String, Object> issueAbandon(String deviceId,
                                            long generation)
            throws Exception {
        TransferState state = state();
        if (!state.adopt()) state = state();
        Map<String, Object> rec = state.receiver(deviceId, generation);
        if (rec == null) throw new HoldException("no such generation");
        if (TransferState.child(rec, "closure") != null) {
            throw new HoldException("generation already closed");
        }
        Map<String, Object> trial = TransferState.child(rec, "trial");
        if (trial != null) {
            Map<String, Object> judgement =
                    TransferState.child(trial, "judgement");
            if (judgement != null && "confirm".equals(
                    TransferState.stringOr(judgement, "kind", null))) {
                throw new HoldException(
                        "a judged trial cannot be answered with abandon");
            }
            String stage = TransferState.stringOr(trial, "stage", null);
            boolean stageOk = "cancel_complete".equals(stage)
                    || "start_blocked".equals(stage)
                    || ("started".equals(stage)
                            && !TransferState.boolOr(
                                    trial, "plan_adopted", false));
            if (!stageOk) {
                throw new HoldException(
                        "trial stage " + stage
                                + " cannot issue an abandon response");
            }
        }
        File root = stagingRootOf(rec, deviceId);
        TransferStore.StagingRecord sr = TransferStore.loadStaging(root);
        if (sr == null) {
            throw new InconsistencyException(
                    "abandon issuance without the staged seed");
        }
        String permitHash = null;
        // Final check round 1, F-19: a ONE-SHOT trial's
        // permit_hash field carries the sentinel "oneshot",
        // not a permit hash — there is no permit in that
        // mode. Passing the sentinel onward made
        // buildAbandon throw "abandon: permit_sha256 form"
        // AFTER the trial had already been cancelled, so a
        // one-shot generation whose confirm was interrupted
        // could neither be answered nor re-confirmed (the
        // sender stayed frozen). Only a genuine full-value
        // hash (64 hex) is ever named in the response; the
        // one-shot answer is the NONE form, which the
        // sender's onAbandon accepts for exactly this case.
        if (trial != null && TransferCodec.isHex(
                TransferState.stringOr(trial, "permit_hash", ""),
                32)) {
            permitHash = TransferState.stringOr(trial, "permit_hash", null);
        } else {
            List<Object> history = TransferState.listOrCreate(
                    rec, "intake_history");
            for (Object o : history) {
                if (o instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> e = (Map<String, Object>) o;
                    if ("committed".equals(e.get("state"))) {
                        permitHash = (String) e.get("hash");
                    }
                }
            }
        }
        byte[] seed = unwrapSeed(sr);
        Map<String, Object> abandon = TransferPackage.buildAbandon(
                seed, generation,
                TransferState.stringOr(rec, "package_sha256", null),
                state.installId(), permitHash);
        // One update: refusal + full value + stage + restoration.
        Map<String, Object> closure = new LinkedHashMap<>();
        closure.put("kind", "refusal");
        closure.put("abandon", abandon);
        rec.put("closure", closure);
        if (trial != null) trial.put("stage", "refused");
        boolean moved = trial != null && TransferState.boolOr(
                trial, "plan_adopted", false)
                && !"cancel_complete".equals(
                        TransferState.stringOr(trial, "stage", null));
        rec.put("restoration", moved ? "pending" : "complete");
        state.save();
        return abandon;
    }

    /** Take in a cancel token (section 4.4): the closure is
     *  recorded durably in this side's transfer record FIRST;
     *  staging material is discarded only after that. */
    public void intakeCancelToken(Map<String, Object> msg)
            throws Exception {
        long gen = Json.asLong(msg.get("transfer_generation"));
        String pkg = Json.asString(msg.get("package_sha256"));
        TransferState state = state();
        String deviceId = null;
        Map<String, Object> rec = null;
        for (String did : state.identities().keySet()) {
            Map<String, Object> r = state.receiver(did, gen);
            if (r != null && pkg.equals(TransferState.stringOr(
                    r, "package_sha256", null))) {
                deviceId = did;
                rec = r;
                break;
            }
        }
        if (rec == null) {
            throw new HoldException("no staged generation matches");
        }
        Map<String, Object> manifest = TransferState.child(rec, "manifest");
        byte[] pubkey = TransferCodec.hexBytes(
                TransferState.stringOr(manifest, "pubkey", null),
                32, "manifest.pubkey");
        TransferPackage.validateCancelToken(msg, pubkey);
        Map<String, Object> closure = TransferState.child(rec, "closure");
        if (closure != null) {
            if ("cancel_token".equals(
                    TransferState.stringOr(closure, "kind", null))) {
                return; // idempotent re-presentation
            }
            throw new IllegalArgumentException("generation is closed");
        }
        Map<String, Object> trial = TransferState.child(rec, "trial");
        if (trial != null && !isTerminalTrial(trial)) {
            // A live trial implies a permit WAS issued, which
            // contradicts the token's claim: preserve as (c).
            throw new InconsistencyException(
                    "cancel token contradicts a live trial");
        }
        Map<String, Object> cl = new LinkedHashMap<>();
        cl.put("kind", "cancel_token");
        cl.put("token", msg);
        rec.put("closure", cl);
        if (!rec.containsKey("restoration")) {
            rec.put("restoration", "complete");
        }
        state.save();
        File root = stagingRootOf(rec, deviceId);
        TransferIo.deleteTree(root);
    }

    // ---------------- staging sweep (section 4.3) ----------------

    /**
     * Discard one staging under the sweep rules. Refused
     * (Hold) unless ALL hold: the generation is uncommitted,
     * NOT a notified R1 (notified stagings are discarded only
     * via a verified cancel token or a durably recorded
     * refusal + abandon), and no unconverged trial exists.
     */
    public void discardStaging(String deviceId, long generation)
            throws Exception {
        TransferState state = state();
        Map<String, Object> rec = state.receiver(deviceId, generation);
        if (rec == null) {
            // No receiver record: an expansion that never
            // reached the boundary (e.g. the boundary failed).
            // Discardable only when a staging record exists and
            // is unnotified.
            File genDir = new File(new File(
                    TransferStore.stagingArea(storeRoot), deviceId),
                    Long.toString(generation));
            File[] pkgs = genDir.listFiles();
            if (pkgs == null || pkgs.length == 0) {
                throw new HoldException("no such generation");
            }
            for (File pkgDir : pkgs) {
                TransferStore.StagingRecord sr =
                        TransferStore.loadStaging(pkgDir);
                if (sr != null && sr.notified) {
                    throw new HoldException(
                            "a notified R1 is never discarded without "
                                    + "a token or a recorded refusal");
                }
                TransferIo.deleteTree(pkgDir);
            }
            return;
        }
        if (TransferState.child(rec, "closure") != null) {
            throw new HoldException("generation is closed");
        }
        if (TransferState.boolOr(rec, "notified_r1", false)) {
            throw new HoldException(
                    "a notified R1 is never discarded without a token "
                            + "or a recorded refusal");
        }
        Map<String, Object> trial = TransferState.child(rec, "trial");
        if (trial != null && !isTerminalTrial(trial)) {
            throw new HoldException("an unconverged trial exists");
        }
        File root = stagingRootOf(rec, deviceId);
        TransferIo.deleteTree(root);
        Map<String, Object> idSection = state.identityOrCreate(deviceId);
        Map<String, Object> receivers =
                TransferState.child(idSection, "receivers");
        if (receivers != null) {
            receivers.remove(Long.toString(generation));
        }
        state.save();
    }

    /** The reverse-migration prefix condition (section 4.7):
     *  the reserve journal must be a strict prefix of the
     *  received journal by the checkpoint-hash chain, or equal
     *  to it. */
    public static boolean isPrefixByChain(JournalFile.Parsed reserve,
                                          JournalFile.Parsed received) {
        if (reserve.count() > received.count()) return false;
        if (!reserve.deviceId.equals(received.deviceId)) return false;
        for (int i = 0; i < reserve.count(); i++) {
            if (!java.util.Arrays.equals(
                    reserve.checkpoints.get(i).checkpointHash,
                    received.checkpoints.get(i).checkpointHash)) {
                return false;
            }
        }
        return true;
    }
}
