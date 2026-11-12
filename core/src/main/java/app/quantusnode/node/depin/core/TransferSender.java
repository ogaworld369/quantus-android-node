package app.quantusnode.node.depin.core;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sender side of the transfer (design section 4.2 state
 * machine S0/S1/S2, section 4.4 permit/receipt rules).
 *
 * The freeze protocol is the section's five steps, in order:
 * (1) the app's recording-freeze flag is raised via
 * FreezeControl and the trial/generation bookkeeping advances
 * (here: the new generation is fixed); (2) the app drains its
 * writer executor (FreezeControl.drainAndFreeze returns only
 * when recording is durably stopped — the core cannot drain
 * another component's executor itself); (3) the journal is
 * fsynced and its tail fixed by a full verified parse; (4) the
 * manifest and anchor are built, the generation advances, the
 * package is encrypted and written; (5) the S1 record is
 * fsynced BEFORE the package file and the transfer code are
 * presented to the user (the returned ExportResult is that
 * presentation point).
 *
 * While S1, the observation service must not run for this
 * identity — the writer gate (TransferInit.writerGate) denies
 * construction, and the app's freeze flag is a second,
 * independent stop.
 */
public final class TransferSender {

    /** App-side recording control: the design's freeze steps
     *  (1) and (2) belong to the recording component. */
    public interface FreezeControl {
        /** Raise the stop flag and drain the writer executor;
         *  returns only when no further journal appends can
         *  occur. */
        void drainAndFreeze() throws IOException;
        /** Release the freeze (after a cancel returns the
         *  identity to S0, recording may resume under the
         *  writer gate as usual). */
        void unfreeze();
    }

    /** Fresh finalized-block source for permit issuance
     *  (section 4.4: H is fixed from a value newly obtained at
     *  issuance; if none can be obtained, no permit issues). */
    public interface FinalizedSource {
        long readFinalized() throws Exception;
    }

    public interface Clock {
        long wallUnix();
    }

    public static final class ExportResult {
        public File packageFile;
        public String transferCode;      // shown once to the user
        public String transferCodeDisplay;
        public long generation;
        public String packageSha256;
    }

    /** Sender-side copy of an exported package (kept until the
     *  generation closes; re-sharing reads this file). */
    public static File outPackageFile(File storeRoot, long generation) {
        return new File(new File(new File(storeRoot,
                TransferStore.TRANSFER_OUT_NAME),
                Long.toString(generation)), "package.json");
    }

    private final File storeRoot;
    private final TransferVault vault;
    private final SecureRandom random;
    private final String senderApp;
    private final FreezeControl freeze;
    private final FinalizedSource finalizedSource;
    private final Clock clock;
    private final long permitValiditySec;
    private final long permitValidityBlocks;
    private final long permitSafetyMarginBlocks;

    public TransferSender(File storeRoot, TransferVault vault,
                          SecureRandom random, String senderApp,
                          FreezeControl freeze,
                          FinalizedSource finalizedSource, Clock clock,
                          long permitValiditySec, long permitValidityBlocks,
                          long permitSafetyMarginBlocks) {
        this.storeRoot = storeRoot;
        this.vault = vault;
        this.random = random;
        this.senderApp = senderApp;
        this.freeze = freeze;
        this.finalizedSource = finalizedSource;
        this.clock = clock;
        this.permitValiditySec = permitValiditySec;
        this.permitValidityBlocks = permitValidityBlocks;
        this.permitSafetyMarginBlocks = permitSafetyMarginBlocks;
    }

    private TransferState state() throws IOException {
        return TransferState.load(storeRoot);
    }

    private static String secretKey(long generation) {
        return TransferVault.senderKey(generation) + ":confirm";
    }

    // ---------------- export (S0 -> S1) ----------------

    /**
     * Freeze, build and write the transfer package, and enter
     * S1. The caller presents {@code result.packageFile} and
     * {@code result.transferCode} only after this returns (the
     * S1 record is durable by then — freeze step (5)).
     */
    public ExportResult exportPackage(byte[] seed) throws Exception {
        return exportInternal(seed, -1, false);
    }

    /**
     * The export body. A fixedGeneration of -1 is the ordinary
     * export (a new generation, no open record allowed); a
     * fixed generation is the one-shot RE-EXPORT from the
     * same frozen tip (E7): the open one-shot record for that
     * generation is replaced with a freshly built package
     * (new transfer code, new confirm secret), and the
     * previous package remains valid — it carries the same
     * journal and opens with its own code.
     *
     * One-shot mode (audit F2): the mode mark, the E4-required
     * marker and the confirmation key are NOT follow-up saves
     * after this method — they are persisted HERE, the key in
     * the vault puts that precede the record save and the
     * marks inside the S1 record itself, so the record, its
     * mode and its key become durable in the same step as the
     * freeze, before the package file exists. The earlier
     * shape (mark + key appended after the export returned)
     * left a crash window in which an E4 handover looked
     * pre-E4 (no key in the vault) and its seed could be
     * erased with no confirmation number at all.
     */
    private ExportResult exportInternal(byte[] seed,
                                        long fixedGeneration,
                                        boolean oneshotMode)
            throws Exception {
        TransferState state = state();
        if (!state.adopt()) {
            state = state(); // re-evaluate from durable content
        }
        Map<String, Object> active = state.active();
        if (active == null) {
            throw new IllegalStateException("hold: no active binding");
        }
        String deviceId = TransferState.stringOr(active, "device_id", null);
        if (!"holding".equals(
                TransferState.stringOr(active, "holding", null))) {
            throw new IllegalStateException("hold: not the holder");
        }
        if (fixedGeneration < 0 && state.sender(deviceId) != null) {
            throw new IllegalStateException(
                    "hold: a sender generation is already open");
        }
        byte[] pubkey = Ed25519.publickeyFromSeed(seed);
        if (!deviceId.equals(DepinCore.deviceIdFromPubkey(pubkey))) {
            throw new IllegalArgumentException(
                    "seed does not belong to the active identity");
        }
        Map<String, Object> idSection = state.identityOrCreate(deviceId);
        long generation = fixedGeneration >= 0
                ? fixedGeneration
                : Math.max(
                        TransferState.longOr(
                                idSection, "last_sender_generation", 0),
                        state.maxCommittedGeneration(deviceId)) + 1;
        // The manifest hash of the record this export replaces
        // (re-export only): confirmation keys stored by
        // earlier builds as bare strings were issued against
        // THAT manifest, and are paired with its hash when
        // migrated below (audit F3).
        String replacedManifestHash = null;
        if (fixedGeneration >= 0) {
            Map<String, Object> prev = state.sender(deviceId);
            Map<String, Object> pm = (prev == null) ? null
                    : TransferState.child(prev, "manifest");
            if (pm != null) {
                replacedManifestHash =
                        TransferCommitCode.manifestHash(pm);
            }
        }

        // Freeze steps (1)+(2): stop flag + executor drain,
        // performed by the recording component.
        if (freeze != null) freeze.drainAndFreeze();
        try {
            // (3) fsync the journal and fix the tail by a full
            // verified parse.
            File journal = TransferStore.journalFile(storeRoot);
            if (!journal.exists()) {
                throw new IllegalStateException(
                        "hold: no journal to export");
            }
            TransferIo.fsync(journal);
            byte[] journalBytes = TransferIo.readAll(
                    journal, TransferPackage.MAX_PACKAGE_BYTES);
            JournalFile.Parsed parsed =
                    JournalFile.parse(journalBytes, deviceId);
            if (parsed.count() == 0) {
                throw new IllegalStateException(
                        "hold: journal has no checkpoints to anchor");
            }
            // Archives: enumerate under archives/<id> and any
            // not-yet-migrated sidecars, parse each fully.
            List<TransferPackage.ArchiveEntry> archives = new ArrayList<>();
            List<byte[]> archiveBytes = new ArrayList<>();
            for (Map.Entry<String, String> e
                    : TransferInit.enumerateLive(storeRoot).entrySet()) {
                if (!e.getKey().startsWith("archives/")) continue;
                String id = e.getKey().substring("archives/".length());
                File f = TransferStore.archiveFile(storeRoot, id);
                if (!f.exists()) f = new File(storeRoot, id);
                byte[] bytes = TransferIo.readAll(
                        f, TransferPackage.MAX_PACKAGE_BYTES);
                JournalFile.Parsed ap = JournalFile.parse(bytes, deviceId);
                TransferPackage.ArchiveEntry ae =
                        new TransferPackage.ArchiveEntry();
                ae.id = id;
                ae.version = ap.version;
                ae.count = ap.count();
                ae.tailCheckpointHash = (ap.last() == null) ? null
                        : Hex.encode(ap.last().checkpointHash);
                ae.sha256 = Hex.encode(DepinCore.sha256(bytes));
                archives.add(ae);
                archiveBytes.add(bytes);
            }
            // (4) manifest + anchor, encrypt, write.
            long now = clock.wallUnix();
            // The anchor params pin the journal's OWN iteration
            // count (the value every checkpoint carries), so a
            // receiver reconstructs exactly this bundle.
            Map<String, Object> anchorBundle = JournalFile.buildAnchorBundle(
                    seed, parsed, now, parsed.last().iterations);
            Map<String, Object> anchorParams =
                    TransferState.child(anchorBundle, "params");
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("device_id", deviceId);
            manifest.put("pubkey", Hex.encode(pubkey));
            manifest.put("transfer_generation", generation);
            DepinCore.Checkpoint tail = parsed.last();
            manifest.put("tail_seq", tail.seq);
            manifest.put("tail_checkpoint_hash",
                    Hex.encode(tail.checkpointHash));
            manifest.put("tail_timestamp_unix", tail.timestampUnix);
            manifest.put("checkpoint_count", (long) parsed.count());
            List<Object> archiveList = new ArrayList<>();
            for (TransferPackage.ArchiveEntry ae : archives) {
                Map<String, Object> am = new LinkedHashMap<>();
                am.put("id", ae.id);
                am.put("version", ae.version);
                am.put("count", ae.count);
                am.put("tail_checkpoint_hash", ae.tailCheckpointHash);
                am.put("sha256", ae.sha256);
                archiveList.add(am);
            }
            manifest.put("archives", archiveList);
            manifest.put("anchor_bundle_hash",
                    anchorBundle.get("bundle_hash"));
            manifest.put("anchor_created_at_unix", now);
            manifest.put("anchor_params", anchorParams);
            manifest.put("created_at_unix", now);
            manifest.put("sender_app", senderApp);
            TransferPackage.validateManifest(manifest);

            String code = TransferCodec.generateCode(random);
            byte[] confirmSecret = new byte[16];
            random.nextBytes(confirmSecret);
            Map<String, Object> envelope = TransferPackage.buildPackage(
                    seed, manifest, confirmSecret, journalBytes,
                    archiveBytes, code, random);
            byte[] packageBytes = TransferCodec.jcs(envelope);
            TransferPackage.Envelope parsedEnv =
                    TransferPackage.parseEnvelope(packageBytes);
            String packageSha = parsedEnv.packageSha256();

            // Freeze BEFORE the file (design consultation E2):
            // the vault material and the S1 record — the
            // durable freeze, in the same store as the seed —
            // are committed first, and the package file is
            // written only after. A crash in between leaves a
            // frozen S1 with no file (cancellable, safe side),
            // never a live file with no freeze. The writer gate
            // itself fails closed on an unreadable record.
            //
            // Vault material for this (generation, sender):
            // the seed copy and the confirm_secret, the S1
            // material of section 4.2/4.4.
            vault.put(TransferVault.senderKey(generation), seed);
            vault.put(secretKey(generation), confirmSecret);
            if (oneshotMode) {
                // The confirmation key joins the SAME pre-save
                // vault batch as the seed copy (audit F2): it
                // is durable before the S1 record below, and
                // the record itself carries the mode and the
                // number-required marker, so no post-export
                // save is needed — and no crash window exists
                // in which an E4 handover looks pre-E4.
                storeCommitKeyPair(vault, generation, code,
                        TransferCommitCode.manifestHash(manifest),
                        replacedManifestHash);
            }

            // (5) S1 record, fsynced before the file exists.
            Map<String, Object> sender = new LinkedHashMap<>();
            sender.put("state", "S1");
            sender.put("generation", generation);
            sender.put("package_sha256", packageSha);
            sender.put("manifest", manifest);
            sender.put("sender_app", senderApp);
            sender.put("created_at_unix", now);
            sender.put("issued_permits", new ArrayList<>());
            if (oneshotMode) {
                sender.put("mode", "oneshot");
                sender.put("commit_code_required", Boolean.TRUE);
            }
            idSection.put("sender", sender);
            idSection.put("last_sender_generation", generation);
            state.setActive(TransferState.binding(
                    deviceId, generation, "sender", "holding"));
            // setActive saves; the sender record rides the
            // same single-record update.

            File outFile = outPackageFile(storeRoot, generation);
            outFile.getParentFile().mkdirs();
            TransferIo.writeFileSync(outFile, packageBytes);
            TransferIo.fsyncDir(outFile.getParentFile());
            TransferIo.fsyncAncestors(storeRoot, outFile.getParentFile());

            ExportResult r = new ExportResult();
            r.packageFile = outFile;
            r.transferCode = code;
            r.transferCodeDisplay = TransferCodec.codeForDisplay(code);
            r.generation = generation;
            r.packageSha256 = packageSha;
            return r;
        } catch (Exception e) {
            // Export failed. Release the app-side freeze only
            // when NO durable sender record exists for this
            // identity: if the failure came after the state
            // save (e.g. the package file write), the S1
            // record stands and the freeze must stand with it
            // (audit Low — the earlier unconditional release
            // relied on the writer gate alone to correct it).
            // An unreadable record also keeps the freeze
            // (fail closed).
            boolean durableSender = true;
            try {
                durableSender = state().sender(deviceId) != null;
            } catch (Exception ignored) {
                durableSender = true;
            }
            if (freeze != null && !durableSender) {
                freeze.unfreeze();
            }
            throw e;
        }
    }

    // ---------------- one-shot handover (simple mode) ---------

    /**
     * Export a package in one-shot mode (simple handover;
     * design note in the integration progress file, section
     * 10): the ordinary export — same encrypted package, same
     * freeze, same generation rules — with the sender record
     * marked "oneshot" and the confirmation key stored in the
     * SAME save as the S1 record itself (audit F2; see
     * exportInternal). The mark is what later authorises the
     * receipt-less close and cancel below; an unmarked
     * (strict) S1 can never use them, so the strict protocol's
     * receipt gate cannot be bypassed through this mode.
     */
    public ExportResult exportPackageOneshot(byte[] seed)
            throws Exception {
        return exportInternal(seed, -1, true);
    }

    /**
     * Re-export a one-shot handover from the same frozen tip
     * (E7 — the recovery for a lost file): a freshly built
     * package for the SAME generation with a new transfer
     * code. The previous package remains valid (same journal,
     * its own code), and its confirmation key is kept — as a
     * (key, manifest hash) pair, so the earlier file's number
     * still verifies after this export replaces the record's
     * manifest (audit F3).
     */
    public ExportResult exportPackageOneshotAgain(byte[] seed)
            throws Exception {
        String deviceId = DepinCore.deviceIdFromPubkey(
                Ed25519.publickeyFromSeed(seed));
        TransferState state = state();
        Map<String, Object> sender =
                openOneshotSender(state, deviceId);
        long generation = TransferState.longOr(
                sender, "generation", -1);
        return exportInternal(seed, generation, true);
    }

    // ---------------- confirmation numbers (E4) -------------

    private static String commitKeysKey(long generation) {
        return "commit-keys:" + generation;
    }

    /**
     * Store the confirmation key derived from an export's
     * transfer code (the code itself is never stored) as a
     * (key, manifest hash) PAIR. One pair per export of the
     * generation is kept, so a re-export does not invalidate
     * the previous file's intake (E7) — and pairing matters:
     * a re-export rebuilds the manifest with fresh timestamps
     * (a different hash), so a key later matched against the
     * CURRENT record's hash could never reproduce the number
     * the earlier file's receiver computed (audit F3).
     *
     * Keys stored by earlier builds are bare strings under
     * "keys"; they were issued against the manifest of the
     * record this export replaces, so on this write they are
     * migrated into pairs carrying that hash
     * ({@code replacedManifestHash}); without a replaced
     * record they are carried over un-migrated.
     */
    private void storeCommitKeyPair(TransferVault vault,
                                    long generation,
                                    String transferCode,
                                    String manifestHash,
                                    String replacedManifestHash)
            throws Exception {
        List<Object> entries = new ArrayList<>();
        List<Object> legacyKeys = new ArrayList<>();
        if (vault.has(commitKeysKey(generation))) {
            Map<String, Object> wrap = Json.parseObject(new String(
                    vault.get(commitKeysKey(generation)),
                    java.nio.charset.StandardCharsets.UTF_8));
            for (Object o : arrayOrEmpty(wrap.get("entries"))) {
                if (o instanceof Map) {
                    entries.add(o);
                }
            }
            for (Object o : arrayOrEmpty(wrap.get("keys"))) {
                if (!(o instanceof String)) {
                    continue;
                }
                if (replacedManifestHash != null) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("key", o);
                    e.put("manifest_hash", replacedManifestHash);
                    entries.add(e);
                } else {
                    legacyKeys.add(o);
                }
            }
        }
        String b64 = TransferCodec.b64Encode(
                TransferCommitCode.deriveKey(transferCode));
        boolean present = false;
        for (Object o : entries) {
            @SuppressWarnings("unchecked")
            Map<String, Object> e = (Map<String, Object>) o;
            if (b64.equals(e.get("key"))
                    && manifestHash.equals(e.get("manifest_hash"))) {
                present = true;
            }
        }
        if (!present) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("key", b64);
            e.put("manifest_hash", manifestHash);
            entries.add(e);
        }
        Map<String, Object> wrap = new LinkedHashMap<>();
        wrap.put("entries", entries);
        if (!legacyKeys.isEmpty()) {
            wrap.put("keys", legacyKeys);
        }
        vault.put(commitKeysKey(generation),
                TransferCodec.jcs(wrap));
    }

    /** Whether an entered confirmation number matches any of
     *  the generation's stored confirmation keys. Paired keys
     *  are matched against the manifest hash they were issued
     *  with (audit F3); bare legacy keys, written before
     *  pairing, against the open record's current hash.
     *
     *  Legacy fallback: a handover exported BEFORE the
     *  confirmation number existed stored no key, and the key
     *  cannot be recomputed — the transfer code was never
     *  persisted. For those records the close falls back to
     *  the pre-E4 behaviour (the dialog's explicit
     *  confirmation alone). The fallback is decided by the
     *  RECORD's marker, not by the vault's contents (audit
     *  F2): a record carrying commit_code_required with no
     *  stored key fails closed — that combination meant a
     *  key existed and was lost, never that none was due. */
    private boolean commitCodeMatches(Map<String, Object> sender,
                                      long generation,
                                      String enteredCode) {
        if (!vault.has(commitKeysKey(generation))) {
            // No key stored: allowed only for records that
            // predate the number requirement (no marker).
            return !Boolean.TRUE.equals(
                    sender.get("commit_code_required"));
        }
        if (enteredCode == null) {
            return false;
        }
        String entered = enteredCode.trim();
        Map<String, Object> manifest =
                TransferState.child(sender, "manifest");
        if (manifest == null) {
            return false;
        }
        String manifestHash =
                TransferCommitCode.manifestHash(manifest);
        try {
            Map<String, Object> wrap = Json.parseObject(new String(
                    vault.get(commitKeysKey(generation)),
                    java.nio.charset.StandardCharsets.UTF_8));
            for (Object o : arrayOrEmpty(wrap.get("entries"))) {
                if (!(o instanceof Map)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> e = (Map<String, Object>) o;
                Object k = e.get("key");
                Object h = e.get("manifest_hash");
                if (!(k instanceof String) || !(h instanceof String)) {
                    continue;
                }
                byte[] key = TransferCodec.b64DecodeStrict(
                        (String) k, "commit key");
                if (TransferCommitCode.codeFor(key, (String) h)
                        .equals(entered)) {
                    return true;
                }
            }
            for (Object o : arrayOrEmpty(wrap.get("keys"))) {
                if (!(o instanceof String)) {
                    continue;
                }
                byte[] key = TransferCodec.b64DecodeStrict(
                        (String) o, "commit key");
                if (TransferCommitCode.codeFor(key, manifestHash)
                        .equals(entered)) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    /** A JSON value as a list, or empty when it is not one
     *  (Json.asArray throws on null; both wrap lists are
     *  optional). */
    private static List<Object> arrayOrEmpty(Object v) {
        List<Object> out = new ArrayList<>();
        if (v instanceof List) {
            out.addAll((List<?>) v);
        }
        return out;
    }

    /** Locate the open one-shot sender record for an identity,
     *  refusing anything else: no record, a closed record, or
     *  a strict-mode S1 (whose closure stays receipt-gated). */
    private Map<String, Object> openOneshotSender(
            TransferState state, String deviceId) {
        Map<String, Object> sender = state.sender(deviceId);
        if (sender == null || !"S1".equals(TransferState.stringOr(
                sender, "state", null))) {
            throw new IllegalStateException(
                    "hold: no open S1 for this identity");
        }
        if (!"oneshot".equals(TransferState.stringOr(
                sender, "mode", null))) {
            throw new IllegalStateException(
                    "hold: the open handover is not a one-shot "
                            + "handover");
        }
        return sender;
    }

    /**
     * Close a one-shot handover on the user's confirmation
     * that the other device shows (and has taken in) this
     * identity: the S2-equivalent erasure of onReceipt,
     * without a receipt — the history entry carries state
     * "S2", the closure kind and the mode, but NO ack (none
     * exists; the ack re-presentation must not offer one),
     * the active binding is released when it names this
     * sender track, and the (generation, sender) vault
     * material is erased. The freeze is NOT lifted: this
     * installation no longer holds the identity, exactly as
     * after a strict S2.
     */
    public void closeOneshotHandover(String deviceId,
                                     String enteredCode)
            throws Exception {
        TransferState state = state();
        Map<String, Object> sender =
                openOneshotSender(state, deviceId);
        long gen = TransferState.longOr(sender, "generation", -1);
        // E4: the erasure is bound to the receiver's completed
        // intake by the confirmation number — the code the
        // receiver displayed after ITS confirm completed,
        // recomputed here from the stored derived key(s). A
        // bare tap (or a wrong number) erases nothing, so the
        // seed cannot be destroyed before the handover lands.
        if (!commitCodeMatches(sender, gen, enteredCode)) {
            throw new IllegalStateException(
                    "hold: confirmation number does not match");
        }
        Map<String, Object> closed = new LinkedHashMap<>(sender);
        closed.put("state", "S2");
        closed.put("closure", "oneshot_user_confirmed");
        Map<String, Object> idSection =
                state.identityOrCreate(deviceId);
        TransferState.listOrCreate(idSection, "sender_history")
                .add(closed);
        idSection.remove("sender");
        Map<String, Object> active = state.active();
        if (active != null && deviceId.equals(TransferState.stringOr(
                active, "device_id", null))
                && "sender".equals(TransferState.stringOr(
                        active, "role", null))) {
            state.doc().remove("active");
        }
        state.save();
        vault.delete(TransferVault.senderKey(gen));
        vault.delete(secretKey(gen));
        vault.delete(commitKeysKey(gen));
        eraseSupersededReceiverKeys(state, deviceId, gen);
    }

    /**
     * Cancel a one-shot handover whose file never reached the
     * other device (the user's attestation, asked for in the
     * UI's confirmation dialog): the S0 return of closeSender
     * — record to history with the closure kind, the held
     * binding restored — the generation's vault material
     * erased, and the freeze lifted so recording can resume.
     * If the file WAS taken in elsewhere, this device and the
     * other would both hold the identity; the dialog's
     * warning is the guard, as with any one-shot design.
     */
    public void cancelOneshotHandover(String deviceId)
            throws Exception {
        TransferState state = state();
        Map<String, Object> sender =
                openOneshotSender(state, deviceId);
        long gen = TransferState.longOr(sender, "generation", -1);
        closeSender(state, deviceId, sender,
                "oneshot_cancelled", null);
        state.save();
        vault.delete(secretKey(gen));
        vault.delete(TransferVault.senderKey(gen));
        vault.delete(commitKeysKey(gen));
        if (freeze != null) freeze.unfreeze();
    }

    // ---------------- notice / permit (section 4.4) ----------------

    /**
     * Verify a staging notice against the open S1 — BEFORE any
     * destination fixing (section 4.4): generation and package
     * hash must equal the S1 record, and the MAC must recompute
     * under the confirm_secret this side holds. A failed notice
     * fixes nothing. Returns the notice's receiver_install_id
     * on success.
     */
    public String verifyNotice(Map<String, Object> notice)
            throws Exception {
        TransferPackage.validateNotice(notice);
        TransferState state = state();
        Map<String, Object> sender = openSender(state, notice);
        long gen = TransferState.longOr(sender, "generation", -1);
        byte[] secret = vault.get(secretKey(gen));
        if (secret == null) {
            throw new IllegalStateException(
                    "hold: S1 material missing for generation " + gen);
        }
        if (!TransferPackage.verifyNoticeMac(notice, secret)) {
            throw new IllegalArgumentException(
                    "notice MAC does not verify");
        }
        return TransferState.stringOr(notice, "receiver_install_id", null);
    }

    /**
     * Issue a permit in response to a verified notice. The
     * destination is fixed AT issuance (and only then): after
     * fixing, permits issue to that installation only. The
     * issuance is durable — the full permit value is recorded
     * in the S1 "issued" substate and fsynced — BEFORE the
     * permit is returned for presentation. H is fixed from a
     * finalized value newly obtained here; if none can be
     * obtained, no permit issues.
     */
    public Map<String, Object> issuePermit(byte[] seed,
                                           Map<String, Object> notice)
            throws Exception {
        String dest = verifyNotice(notice);
        TransferState state = state();
        Map<String, Object> active = state.active();
        String deviceId = TransferState.stringOr(active, "device_id", null);
        Map<String, Object> sender = state.sender(deviceId);
        String fixed = TransferState.stringOr(
                sender, "dest_install_id", null);
        if (fixed != null && !fixed.equals(dest)) {
            throw new IllegalArgumentException(
                    "destination already fixed to another installation");
        }
        long gen = TransferState.longOr(sender, "generation", -1);
        String packageSha = TransferState.stringOr(
                sender, "package_sha256", null);
        long finalizedNow;
        try {
            finalizedNow = finalizedSource.readFinalized();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "hold: no fresh finalized value; permit not issued");
        }
        long now = clock.wallUnix();
        long expiresFinalized = finalizedNow + permitValidityBlocks
                + permitSafetyMarginBlocks;
        Map<String, Object> permit = TransferPackage.buildPermit(
                seed, gen, dest, packageSha, now,
                now + permitValiditySec, expiresFinalized);
        String hash = TransferCodec.fullValueHash(permit);
        // Durable issuance: destination fixing + full value in
        // the issued substate, one update, fsync — before the
        // permit may be presented.
        sender.put("dest_install_id", dest);
        List<Object> issued = TransferState.listOrCreate(
                sender, "issued_permits");
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("hash", hash);
        entry.put("permit", permit);
        issued.add(entry);
        state.save();
        return permit;
    }

    /** Re-present an issued permit by its full-value hash: the
     *  stored full value, verbatim, with no clock re-check
     *  (expiry judgement is the receiver's, section 4.4). */
    public Map<String, Object> rePresentPermit(String permitHash)
            throws IOException {
        TransferState state = state();
        Map<String, Object> active = state.active();
        if (active == null) return null;
        Map<String, Object> sender = state.sender(
                TransferState.stringOr(active, "device_id", null));
        if (sender == null) return null;
        return findIssued(sender, permitHash);
    }

    private static Map<String, Object> findIssued(
            Map<String, Object> sender, String permitHash) {
        Object v = sender.get("issued_permits");
        if (v instanceof List) {
            for (Object o : (List<?>) v) {
                if (o instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> e = (Map<String, Object>) o;
                    if (permitHash.equals(e.get("hash"))) {
                        return TransferState.child(e, "permit");
                    }
                }
            }
        }
        return null;
    }

    public static List<String> issuedPermitHashes(
            Map<String, Object> sender) {
        List<String> out = new ArrayList<>();
        Object v = sender.get("issued_permits");
        if (v instanceof List) {
            for (Object o : (List<?>) v) {
                if (o instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> e = (Map<String, Object>) o;
                    Object h = e.get("hash");
                    if (h instanceof String) out.add((String) h);
                }
            }
        }
        return out;
    }

    // ---------------- cancel before permit ----------------

    /**
     * Cancel an S1 for which NO permit has been issued yet
     * (free cancellation, section 4.4). The generation is
     * first durably recorded as "closed unissued" in this
     * side's own transfer record; only after that may the
     * cancel token be presented (the method returns it after
     * the record update). The generation's confirm_secret is
     * destroyed with the generation.
     */
    public Map<String, Object> cancelBeforePermit(byte[] seed)
            throws Exception {
        TransferState state = state();
        if (!state.adopt()) state = state();
        Map<String, Object> active = state.active();
        String deviceId = TransferState.stringOr(active, "device_id", null);
        Map<String, Object> sender = state.sender(deviceId);
        if (sender == null || !"S1".equals(
                TransferState.stringOr(sender, "state", null))) {
            throw new IllegalStateException("hold: no open S1");
        }
        if (!issuedPermitHashes(sender).isEmpty()) {
            throw new IllegalStateException(
                    "hold: a permit was already issued; time alone "
                            + "cannot cancel now");
        }
        long gen = TransferState.longOr(sender, "generation", -1);
        String packageSha = TransferState.stringOr(
                sender, "package_sha256", null);
        Map<String, Object> token = TransferPackage.buildCancelToken(
                seed, gen, packageSha);
        // Durable closure first, token presentation after.
        closeSender(state, deviceId, sender, "cancelled_pre_permit", null);
        vault.delete(secretKey(gen));
        vault.delete(TransferVault.senderKey(gen));
        // Final check round 4 (I-23): a one-shot S1 can be
        // closed here too; its commit keys go with it.
        vault.delete(commitKeysKey(gen));
        if (freeze != null) freeze.unfreeze();
        return token;
    }

    // ---------------- responses (receipt / abandon) ----------------

    /**
     * Take in a receipt (section 4.4 Phase 2b). On MAC match:
     * (1) the ack is generated while the seed is still in the
     * vault; (2) the S2 transition and the ack's full value are
     * persisted in the SAME record update; (3) the
     * (generation, sender) vault entries are erased; (4) the
     * caller presents the ack. Returns the ack.
     */
    public Map<String, Object> onReceipt(byte[] seed,
                                         Map<String, Object> receipt)
            throws Exception {
        TransferPackage.validateReceipt(receipt);
        TransferState state = state();
        Map<String, Object> sender = openSender(state, receipt);
        long gen = TransferState.longOr(sender, "generation", -1);
        String dest = TransferState.stringOr(
                sender, "dest_install_id", null);
        if (dest == null || !dest.equals(TransferState.stringOr(
                receipt, "receiver_install_id", null))) {
            throw new IllegalArgumentException(
                    "receipt: not from the fixed destination");
        }
        byte[] secret = vault.get(secretKey(gen));
        if (secret == null) {
            throw new IllegalStateException(
                    "hold: S1 material missing for generation " + gen);
        }
        if (!TransferPackage.verifyReceiptMac(receipt, secret)) {
            throw new IllegalArgumentException(
                    "receipt: confirmation MAC does not verify");
        }
        String deviceId = TransferState.stringOr(
                state.active(), "device_id", null);
        String packageSha = TransferState.stringOr(
                sender, "package_sha256", null);
        Map<String, Object> ack = TransferPackage.buildAck(
                seed, gen, packageSha, dest);
        // (2) S2 transition + ack full value, one update.
        sender.put("state", "S2");
        sender.put("ack", ack);
        sender.put("receipt_from", dest);
        Map<String, Object> idSection = state.identityOrCreate(deviceId);
        TransferState.listOrCreate(idSection, "sender_history")
                .add(new LinkedHashMap<>(sender));
        idSection.remove("sender");
        // This installation no longer holds the identity.
        state.doc().remove("active");
        state.save();
        // (3) erase the (generation, sender) vault material.
        vault.delete(TransferVault.senderKey(gen));
        vault.delete(secretKey(gen));
        // Final check round 4 (I-23): a one-shot S1 can be
        // closed here too; its commit keys go with it.
        vault.delete(commitKeysKey(gen));
        eraseSupersededReceiverKeys(state, deviceId, gen);
        return ack;
    }

    /**
     * Erase the receiver copies this send-onward superseded
     * (audit F8). When an identity this store RECEIVED is
     * sent onward and the send closes (S2), the received
     * copy under receiverKey(received generation) is the
     * same seed in a second envelope: leaving it behind
     * would keep a live copy here of an identity that now
     * lives elsewhere. Round 1's recovery allowance
     * (TransferReceiver.seedAbsenceExplained) already
     * tolerated that copy's absence after a later S2 — but
     * nothing ever erased it, so the explained state could
     * never occur and a copy genuinely lost read as
     * explained once any later send existed. The erasure is
     * paired with the S2 record: it runs only from the two
     * S2 closures, after the history entry is durable, and
     * only for generations BELOW the closed sender
     * generation whose receiver record carries a committed
     * closure — exactly the absence the allowance explains,
     * nothing wider. S0 closures (cancels, abandons) never
     * call this: the identity stays here and keeps its copy.
     * Audit F10 adds one qualification to "exactly": a copy
     * is erased only when its own bytes derive this
     * identity — the vault's generation-only namespace is
     * shared across identities, so the number alone cannot
     * prove the copy under it is this closure's to take.
     */
    private void eraseSupersededReceiverKeys(TransferState state,
                                             String deviceId,
                                             long senderGeneration)
            throws IOException {
        for (long rGen : state.receiverGenerations(deviceId)) {
            if (rGen >= senderGeneration) {
                continue;
            }
            Map<String, Object> rec = state.receiver(deviceId, rGen);
            Map<String, Object> closure = (rec == null) ? null
                    : TransferState.child(rec, "closure");
            String kind = (closure == null) ? null
                    : TransferState.stringOr(closure, "kind", null);
            if (!"committed_r2".equals(kind)
                    && !"committed_oneshot".equals(kind)) {
                continue;
            }
            // Audit F10: erase only a copy proven — from
            // its own bytes — to derive THIS identity. The
            // vault files copies under bare generation
            // numbers shared by every identity in the store,
            // so the entry under receiverKey(rGen) can be a
            // different lineage's copy that happens to share
            // the number; the generation comparison above
            // cannot tell them apart. An unproven (foreign
            // or unreadable) copy is left in place for the
            // recovery classification to surface, never
            // silently erased here.
            String key = TransferVault.receiverKey(rGen);
            if (vault.has(key)) {
                byte[] copy = null;
                boolean proven = false;
                try {
                    copy = vault.get(key);
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
                if (!proven) {
                    continue;
                }
            }
            vault.delete(key);
        }
    }

    /** Re-present the completion ack from the S2 history. */
    public Map<String, Object> rePresentAck(long generation)
            throws IOException {
        TransferState state = state();
        for (String deviceId : state.identities().keySet()) {
            Map<String, Object> id = state.identity(deviceId);
            Object hist = (id == null) ? null : id.get("sender_history");
            if (hist instanceof List) {
                for (Object o : (List<?>) hist) {
                    if (o instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> s = (Map<String, Object>) o;
                        if (TransferState.longOr(
                                s, "generation", -1) == generation
                                && "S2".equals(s.get("state"))) {
                            return TransferState.child(s, "ack");
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Take in a cannot-confirm (abandon) response (section
     * 4.4): common checks (a)-(e) — signature under the
     * manifest key, format, generation + package hash equal to
     * the open S1, responder equal to the fixed destination,
     * and the claim covering this installation + generation
     * (a permit_sha256 must name one of the permits actually
     * issued to that destination; the NONE form requires this
     * side to be pre-confirmation itself, which an open S1
     * always is). On success the sender returns to S0 and the
     * generation's secret is destroyed with it.
     */
    public void onAbandon(Map<String, Object> abandon) throws Exception {
        TransferState state = state();
        Map<String, Object> sender = openSender(state, abandon);
        Map<String, Object> manifest = TransferState.child(sender, "manifest");
        byte[] pubkey = TransferCodec.hexBytes(
                TransferState.stringOr(manifest, "pubkey", null),
                32, "manifest.pubkey");
        TransferPackage.validateAbandon(abandon, pubkey);
        String dest = TransferState.stringOr(
                sender, "dest_install_id", null);
        String responder = TransferState.stringOr(
                abandon, "receiver_install_id", null);
        Object permitSha = abandon.get("permit_sha256");
        if (permitSha instanceof String) {
            if (dest == null || !dest.equals(responder)) {
                throw new IllegalArgumentException(
                        "abandon: not from the fixed destination");
            }
            if (!issuedPermitHashes(sender).contains(permitSha)) {
                throw new IllegalArgumentException(
                        "abandon: permit_sha256 names no permit issued "
                                + "to this destination");
            }
        } else {
            // NONE form: acceptable from the fixed destination,
            // or — before any destination was fixed — from the
            // installation that staged (nothing was ever
            // permitted; claim (e) is this side's own
            // pre-confirmation state).
            if (dest != null && !dest.equals(responder)) {
                throw new IllegalArgumentException(
                        "abandon: not from the fixed destination");
            }
        }
        String deviceId = TransferState.stringOr(
                state.active(), "device_id", null);
        long gen = TransferState.longOr(sender, "generation", -1);
        closeSender(state, deviceId, sender, "abandoned", abandon);
        vault.delete(secretKey(gen));
        vault.delete(TransferVault.senderKey(gen));
        // Final check round 4 (I-23): a one-shot S1 can be
        // closed here too; its commit keys go with it.
        vault.delete(commitKeysKey(gen));
        if (freeze != null) freeze.unfreeze();
    }

    // ---------------- shared internals ----------------

    /** Locate the open S1 record matching a message's
     *  (generation, package hash) — design checks (c). */
    private Map<String, Object> openSender(TransferState state,
                                           Map<String, Object> msg)
            throws IOException {
        Map<String, Object> active = state.active();
        if (active == null) {
            throw new IllegalStateException("hold: no active binding");
        }
        String deviceId = TransferState.stringOr(active, "device_id", null);
        Map<String, Object> sender = state.sender(deviceId);
        if (sender == null || !"S1".equals(
                TransferState.stringOr(sender, "state", null))) {
            throw new IllegalStateException("hold: no open S1");
        }
        long gen = Json.asLong(msg.get("transfer_generation"));
        String pkg = Json.asString(msg.get("package_sha256"));
        if (gen != TransferState.longOr(sender, "generation", -1)
                || !pkg.equals(TransferState.stringOr(
                        sender, "package_sha256", null))) {
            throw new IllegalArgumentException(
                    "message does not match the open S1 generation/package");
        }
        return sender;
    }

    /** Move the open sender record into history with a closure
     *  kind and restore the held binding (S1 → S0). */
    private void closeSender(TransferState state, String deviceId,
                             Map<String, Object> sender, String closure,
                             Map<String, Object> response)
            throws IOException {
        Map<String, Object> closed = new LinkedHashMap<>(sender);
        closed.put("state", "S0");
        closed.put("closure", closure);
        if (response != null) closed.put("response", response);
        Map<String, Object> idSection = state.identityOrCreate(deviceId);
        TransferState.listOrCreate(idSection, "sender_history").add(closed);
        idSection.remove("sender");
        long held = TransferState.longOr(idSection, "held_generation", 0);
        state.setActive(TransferState.binding(
                deviceId, held, "sender", "holding"));
    }
}
