package app.quantusnode.node.depin.core;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Initialisation (design section 4.2) and the writer gate
 * (section 3.2 as amended by 4.2).
 *
 * Initialisation has three modes — "new" (a fresh identity is
 * generated here), "legacy_upgrade" (a genuine pre-transfer
 * install: initialisation record absent AND the journal is
 * positively confirmed to be in a legacy format), and
 * "first_receive" (no identity yet; the first R2 will bind
 * one). The step order is fixed for all modes:
 *   (1) create the init record in "preparing" and adopt it;
 *   (2) prepare the vault entry idempotently, bound to the
 *       init ID (new mode only; the other modes' key material
 *       already lives in the app vault or does not exist yet);
 *   (3) run the layout migration (below) and create the
 *       transfer record's initial state, and adopt it;
 *   (4) atomically update the init record to "complete" and
 *       adopt it;
 *   (5) only then may writers, notices and permits unlock —
 *       callers consult {@link #writerGate}.
 *
 * Layout migration (section 4.2, body rule added in rev.23):
 * performed while "preparing"; the completion judgement is
 * made against a durable pre-migration enumeration (every
 * item with its full-file SHA-256). The migration moves
 * JournalStore's sidecar archives (journal-archive-v*.jsonl
 * next to the journal) under archives/<id> per the section
 * 4.1 placement map, then re-enumerates and compares.
 */
public final class TransferInit {
    private TransferInit() {}

    public static final String MODE_NEW = "new";
    public static final String MODE_LEGACY = "legacy_upgrade";
    public static final String MODE_FIRST_RECEIVE = "first_receive";

    public static final String LAYOUT_FILE_NAME = "transfer-layout.json";

    /** Result of an initialisation run. */
    public static final class Result {
        public TransferState.InitRecord init;
        public boolean alreadyComplete;
        public String deviceId;   // null for first_receive
    }

    /**
     * Run (or resume) initialisation.
     *
     * @param seed for MODE_NEW: the freshly generated identity
     *        seed (32 bytes); the caller installs it into the
     *        app vault after this returns and the init vault
     *        entry is then retired by {@link #retireInitEntry}.
     *        For MODE_LEGACY: the existing identity seed (used
     *        only to fix the pubkey hash; it stays in the app
     *        vault). For MODE_FIRST_RECEIVE: null.
     */
    public static Result initialise(File storeRoot, TransferVault vault,
                                    String mode, byte[] seed,
                                    SecureRandom random)
            throws IOException, java.security.GeneralSecurityException {
        storeRoot.mkdirs();
        TransferState.InitRecord init = TransferState.loadInit(storeRoot);
        Result out = new Result();
        if (init != null && "complete".equals(init.state)) {
            out.init = init;
            out.alreadyComplete = true;
            out.deviceId = deviceIdOf(storeRoot);
            return out;
        }
        String deviceId = null;
        if (init == null) {
            // (1) init record, preparing.
            init = new TransferState.InitRecord();
            init.initId = TransferCodec.newInstallId(random);
            init.mode = mode;
            init.vaultRef = MODE_NEW.equals(mode)
                    ? TransferVault.initKey(init.initId) : "app";
            init.state = "preparing";
            TransferState.saveInit(storeRoot, init);
            init = TransferState.adoptInit(storeRoot);
            if (init == null || !"preparing".equals(init.state)) {
                throw new IOException(
                        "init record adoption failed after creation");
            }
        } else if (!mode.equals(init.mode)) {
            throw new IOException(
                    "init record mode does not match the requested mode");
        }
        // (2) vault entry preparation (new mode), idempotent
        // under the init ID binding.
        if (MODE_NEW.equals(mode)) {
            if (seed == null || seed.length != 32) {
                throw new IllegalArgumentException(
                        "new mode requires the 32-byte seed");
            }
            String key = TransferVault.initKey(init.initId);
            if (!vault.has(key)) {
                vault.put(key, seed);
            } else if (!java.util.Arrays.equals(vault.get(key), seed)) {
                throw new IOException(
                        "init vault entry exists for a different seed");
            }
            String pubHash = Hex.encode(DepinCore.sha256(
                    Ed25519.publickeyFromSeed(seed)));
            if (init.pubkeyHash == null) {
                init.pubkeyHash = pubHash;
                TransferState.saveInit(storeRoot, init);
                init = TransferState.adoptInit(storeRoot);
            } else if (!init.pubkeyHash.equals(pubHash)) {
                throw new IOException("init pubkey hash mismatch");
            }
            deviceId = DepinCore.deviceIdFromPubkey(
                    Ed25519.publickeyFromSeed(seed));
        } else if (MODE_LEGACY.equals(mode)) {
            if (seed == null || seed.length != 32) {
                throw new IllegalArgumentException(
                        "legacy mode requires the existing seed");
            }
            String pubHash = Hex.encode(DepinCore.sha256(
                    Ed25519.publickeyFromSeed(seed)));
            if (init.pubkeyHash == null) {
                init.pubkeyHash = pubHash;
                TransferState.saveInit(storeRoot, init);
            } else if (!init.pubkeyHash.equals(pubHash)) {
                throw new IOException("init pubkey hash mismatch");
            }
            deviceId = DepinCore.deviceIdFromPubkey(
                    Ed25519.publickeyFromSeed(seed));
        }
        // (3a) layout migration, judged against the durable
        // pre-migration enumeration.
        migrateLayout(storeRoot);
        // (3b) transfer record initial state.
        TransferState state = TransferState.load(storeRoot);
        if (state.installId() == null) {
            state.setInstallId(TransferCodec.newInstallId(random));
        }
        if (deviceId != null && state.identity(deviceId) == null) {
            Map<String, Object> id = state.identityOrCreate(deviceId);
            id.put("held_generation", 0L);
            id.put("max_committed_generation", 0L);
            state.setActive(TransferState.binding(
                    deviceId, 0, "sender", "holding"));
        }
        if (!state.adopt()) {
            // The durable content differs from what we just
            // wrote (a torn write above, or a concurrent
            // writer): re-evaluate once from the durable state;
            // if our initial state is not there, fail closed.
            state = TransferState.load(storeRoot);
            if (state.installId() == null) {
                throw new IOException(
                        "transfer record adoption failed at initialisation");
            }
        }
        // (4) completion.
        init.state = "complete";
        TransferState.saveInit(storeRoot, init);
        TransferState.InitRecord adopted = TransferState.adoptInit(storeRoot);
        if (adopted == null || !"complete".equals(adopted.state)) {
            throw new IOException("init completion adoption failed");
        }
        out.init = adopted;
        out.deviceId = deviceId != null ? deviceId : deviceIdOf(storeRoot);
        return out;
    }

    /** After the caller has installed the new identity's seed
     *  into the app vault (post-completion), retire the init
     *  vault entry. Erasure is adoption-gated (section 4.2). */
    public static void retireInitEntry(File storeRoot, TransferVault vault)
            throws IOException, java.security.GeneralSecurityException {
        TransferState.InitRecord init = TransferState.adoptInit(storeRoot);
        if (init == null || !"complete".equals(init.state)) {
            throw new IOException("init not complete; cannot retire");
        }
        TransferState state = TransferState.load(storeRoot);
        if (!state.adopt()) {
            throw new IOException("transfer record not durable; cannot retire");
        }
        String key = TransferVault.initKey(init.initId);
        if (vault.has(key)) vault.delete(key);
    }

    private static String deviceIdOf(File storeRoot) throws IOException {
        TransferState state = TransferState.load(storeRoot);
        Map<String, Object> active = state.active();
        return (active == null) ? null
                : TransferState.stringOr(active, "device_id", null);
    }

    // ---------------- layout migration ----------------

    /** Enumerate the live placement (journal + archives in
     *  both physical locations) with full-file hashes. Keys are
     *  logical placement keys ("journal.json",
     *  "archives/<id>"). */
    public static Map<String, String> enumerateLive(File storeRoot)
            throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        TransferStore.LiveManifest snap =
                TransferStore.snapshotLive(storeRoot, "existing", 0);
        out.putAll(snap.files);
        return out;
    }

    private static void migrateLayout(File storeRoot) throws IOException {
        File layoutFile = new File(storeRoot, LAYOUT_FILE_NAME);
        Map<String, String> before;
        if (layoutFile.exists()) {
            Map<String, Object> doc = Json.parseObject(new String(
                    TransferIo.readAll(layoutFile, 16L * 1024 * 1024),
                    java.nio.charset.StandardCharsets.UTF_8));
            if (!"complete".equals(doc.get("state"))) {
                before = new LinkedHashMap<>();
                Map<String, Object> e = TransferState.child(doc, "before");
                if (e != null) {
                    for (Map.Entry<String, Object> en : e.entrySet()) {
                        if (en.getValue() instanceof String) {
                            before.put(en.getKey(), (String) en.getValue());
                        }
                    }
                } else {
                    before = enumerateLive(storeRoot);
                }
            } else {
                return; // migration already judged complete
            }
        } else {
            before = enumerateLive(storeRoot);
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("format", "quantus-depin-transfer-layout");
            doc.put("version", 1L);
            doc.put("state", "preparing");
            Map<String, Object> e = new LinkedHashMap<>();
            e.putAll(before);
            doc.put("before", e);
            TransferIo.writeRecordAtomic(
                    layoutFile, TransferCodec.jcs(doc));
        }
        // Move sidecar archives under archives/<id>.
        for (String sidecar : new String[]{
                JournalStore.ARCHIVE_V04_FILE_NAME,
                JournalStore.ARCHIVE_FILE_NAME}) {
            File src = new File(storeRoot, sidecar);
            File dst = TransferStore.archiveFile(storeRoot, sidecar);
            if (src.isFile() && !dst.exists()) {
                TransferStore.archivesDir(storeRoot).mkdirs();
                if (!src.renameTo(dst)) {
                    throw new IOException(
                            "layout migration: cannot move " + sidecar);
                }
                TransferIo.fsyncDir(storeRoot);
                TransferIo.fsyncDir(TransferStore.archivesDir(storeRoot));
            }
        }
        Map<String, String> after = enumerateLive(storeRoot);
        if (!after.equals(before)) {
            throw new IOException(
                    "layout migration: post-migration enumeration differs");
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("format", "quantus-depin-transfer-layout");
        doc.put("version", 1L);
        doc.put("state", "complete");
        Map<String, Object> e = new LinkedHashMap<>();
        e.putAll(before);
        doc.put("before", e);
        TransferIo.writeRecordAtomic(layoutFile, TransferCodec.jcs(doc));
    }

    // ---------------- writer gate (section 3.2) ----------------

    public static final class Gate {
        public boolean allowed;
        public String reason;
        public String deviceId;
        public long generation;
        public String role;
    }

    /**
     * The writer gate, in the design's order: transfer state →
     * active binding → writer construction. A writer may be
     * constructed only when ALL of the following hold:
     * introduction is complete; the transfer record adopts
     * cleanly; an active binding exists and names a holder; no
     * sender freeze (S1) covers the active identity; no
     * restoration is pending and no closed generation lacks a
     * restoration state; the loss path (if it governs the
     * current placement) has reached "empty adopted"; and the
     * user has explicitly switched recording ON for THIS
     * identity (the flag is identity-bound in app settings —
     * a shadow identity's ON is never inherited, section
     * 3.2/R13-6).
     *
     * @param explicitOnForActiveIdentity the app-settings flag
     *        for the ACTIVE identity specifically.
     */
    public static Gate writerGate(File storeRoot,
                                  boolean explicitOnForActiveIdentity) {
        Gate g = new Gate();
        try {
            TransferState.InitRecord init =
                    TransferState.loadInit(storeRoot);
            if (init == null || !"complete".equals(init.state)) {
                g.reason = "introduction_not_complete";
                return g;
            }
            TransferState state = TransferState.load(storeRoot);
            if (!state.existsOnDisk()) {
                g.reason = "transfer_record_missing";
                return g;
            }
            if (!state.adopt()) {
                g.reason = "transfer_record_rolled_back";
                return g;
            }
            Map<String, Object> active = state.active();
            if (active == null) {
                g.reason = "no_active_binding";
                return g;
            }
            String deviceId = TransferState.stringOr(
                    active, "device_id", null);
            String holding = TransferState.stringOr(
                    active, "holding", null);
            if (deviceId == null || !"holding".equals(holding)) {
                g.reason = "not_holder";
                return g;
            }
            Map<String, Object> sender = state.sender(deviceId);
            if (sender != null && "S1".equals(
                    TransferState.stringOr(sender, "state", null))) {
                g.reason = "frozen_s1";
                return g;
            }
            // Restoration gates (section 4.3): any generation
            // with restoration pending closes the writer gate;
            // a closed generation without a restoration state
            // is "restoration unknown" and also closes it.
            for (long gen : state.receiverGenerations(deviceId)) {
                Map<String, Object> r = state.receiver(deviceId, gen);
                Map<String, Object> closure =
                        TransferState.child(r, "closure");
                String restoration =
                        TransferState.stringOr(r, "restoration", null);
                if ("pending".equals(restoration)) {
                    g.reason = "restoration_pending";
                    return g;
                }
                if (closure != null && restoration == null) {
                    g.reason = "restoration_unknown";
                    return g;
                }
                Map<String, Object> loss = TransferState.child(r, "loss");
                if (loss != null && !"empty_adopted".equals(
                        TransferState.stringOr(loss, "state", null))) {
                    g.reason = "loss_path_open";
                    return g;
                }
            }
            // Displacement gate (final check round 1, F-06):
            // the loop above reads only the ACTIVE identity's
            // own records. A strict confirm of ANOTHER
            // identity displaces the active identity's live
            // placement (its journal is quarantined intact,
            // then the promotion writes over the live slot)
            // — and a crash anywhere in that window used to
            // leave this gate reading the displaced identity's
            // untouched record and opening its writer over a
            // half-moved store: the writer's own load then
            // quarantined the promoted journal as damaged
            // (bytes preserved, but the store was wedged).
            // The gate therefore also closes while ANY
            // identity in the store has an unfinished trial
            // that has moved, or adopted a plan to move, the
            // live placement: a foreign quarantine in
            // "moving"/"quarantined", or a plan-adopted trial
            // that is not terminal. Trials that never touched
            // the live placement (staged, started, planned
            // without adoption) do not close the gate, and
            // neither do closed generations.
            for (String otherId : state.identities().keySet()) {
                for (long gen : state.receiverGenerations(otherId)) {
                    Map<String, Object> r = state.receiver(otherId, gen);
                    if (r == null
                            || TransferState.child(r, "closure") != null) {
                        continue;
                    }
                    Map<String, Object> t =
                            TransferState.child(r, "trial");
                    if (t == null) {
                        continue;
                    }
                    Map<String, Object> fq = TransferState.child(
                            r, "foreign_quarantine");
                    String fqState = fq == null ? null
                            : TransferState.stringOr(fq, "state", null);
                    boolean displacing = "moving".equals(fqState)
                            || "quarantined".equals(fqState);
                    boolean planOpen = TransferState.boolOr(
                            t, "plan_adopted", false)
                            && !TransferReceiver.isTerminalTrial(t);
                    if (displacing || planOpen) {
                        g.reason = "promotion_in_progress";
                        return g;
                    }
                }
            }
            if (!explicitOnForActiveIdentity) {
                g.reason = "not_switched_on";
                return g;
            }
            g.allowed = true;
            g.reason = "ok";
            g.deviceId = deviceId;
            g.generation = TransferState.longOr(active, "generation", 0);
            g.role = TransferState.stringOr(active, "role", null);
            return g;
        } catch (Exception e) {
            g.allowed = false;
            g.reason = "unreadable: " + e.getMessage();
            return g;
        }
    }

    /** Names that exist in a store root ONLY because a
     *  transfer was set up or run there. The layout names are
     *  NOT re-spelled here (audit M8): they are
     *  {@link TransferStore}'s own constants, so this set and
     *  the layout the store writes share one definition and
     *  cannot drift apart; the harness pins the set's
     *  contents, the promotion journal and the shadow
     *  quarantine included.
     *
     *  Audit L9: the live manifest joined the set. It is
     *  written only by the receiver's promotion /
     *  materialisation points — a pre-transfer store never
     *  has one — so its presence without an init record is
     *  the same damaged-store evidence as the other eight.
     *  The app layers' "transfer-io" inbox directory was
     *  considered and left out: it is created by the inbox
     *  accessor itself (reads included, on mere UI
     *  navigation), so its presence proves only that a
     *  transfer screen was opened, and every flow that
     *  actually moves material also writes core traces. */
    private static final String[] TRANSFER_TRACE_NAMES = {
            TransferState.FILE_NAME, TransferVault.FILE_NAME,
            LAYOUT_FILE_NAME, TransferStore.STAGING_AREA_NAME,
            TransferStore.TRANSFER_OUT_NAME,
            TransferStore.PROMOTION_JOURNAL_NAME,
            TransferStore.SHADOW_QUARANTINE_NAME,
            TransferStore.PRE_PROMOTION_BACKUP_NAME,
            TransferStore.LIVE_MANIFEST_NAME};

    /** The transfer-trace name set (test/audit surface). */
    public static String[] transferTraceNames() {
        return TRANSFER_TRACE_NAMES.clone();
    }

    /**
     * Whether the store carries any trace of the transfer
     * machinery. The app layers use this to fail CLOSED when
     * the init record itself is missing (audit F5): a store
     * WITH traces but no init record is not a fresh legacy
     * store — resolving it as one would hand the journal to
     * the ungated legacy path and let a seed that transfer
     * state was holding back record again. Only a store with
     * neither init nor traces is genuinely pre-transfer.
     */
    public static boolean hasTransferTraces(File storeRoot) {
        if (storeRoot == null) {
            return false;
        }
        for (String n : TRANSFER_TRACE_NAMES) {
            if (new File(storeRoot, n).exists()) {
                return true;
            }
        }
        return false;
    }

    /** Whether the shared vault wrap key is still referenced
     *  by ANY transfer material (section 4.2 common rule): the
     *  caller must not destroy the wrap key while this returns
     *  true. Conservative: any doubt counts as referenced. */
    public static boolean wrapKeyReferenced(File storeRoot,
                                            TransferVault vault) {
        try {
            if (!vault.keys().isEmpty()) return true;
            TransferState.InitRecord init = TransferState.loadInit(storeRoot);
            if (init != null && !"complete".equals(init.state)) return true;
            TransferState state = TransferState.load(storeRoot);
            if (state.active() != null) return true;
            for (String deviceId : state.identities().keySet()) {
                if (state.sender(deviceId) != null) return true;
                if (!state.receiverGenerations(deviceId).isEmpty()) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }
}
