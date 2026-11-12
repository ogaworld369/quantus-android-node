package app.quantusnode.node.depin.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The on-disk layout of the transfer machinery inside the
 * DePIN store root (design section 4.1 placement map, section
 * 3.4 storage bounds), plus the two remaining durable record
 * types: the staging record (per staged generation) and the
 * live manifest.
 *
 * Layout under the store root (the app's files/depin/):
 * <pre>
 *   journal.json                      live journal
 *   archives/&lt;id&gt;                     live archives
 *   import-staging/&lt;device_id&gt;/&lt;generation&gt;/&lt;package_sha256&gt;/
 *       journal.json                  staged (expanded) journal
 *       archives/&lt;id&gt;                 staged archives
 *       package/                      the original package copy
 *       staging-record.json           staging record (this class)
 *   pre-promotion-backup/&lt;generation&gt;/&lt;trial_id&gt;/...
 *   shadow-quarantine/&lt;device_id&gt;/...  a foreign identity's
 *                                     placement, quarantined
 *                                     intact before a promotion
 *   promotion-journal.jsonl           promotion plan + progress
 *   live-manifest.json                live manifest
 *   transfer-state.json               transfer record
 *   transfer-init.json                initialisation record
 *   transfer-vault.json               transfer vault
 *   verification-budget.json          section 3.5 budget ledger
 *   verification-cursor.json          section 3.5 resume cursor
 * </pre>
 *
 * The staging record (design section 4.3) lives INSIDE the
 * staging root and carries: the manifest-fixed facts
 * (device_id, generation, package hash, manifest, plaintext
 * file hashes computed at expansion), the wrapped seed +
 * confirm_secret, the notified-R1 marker (the ONLY basis for
 * "notified" anywhere in the design), the established permit's
 * full value, and the attempt ledger (next attempt number,
 * after start: attempt ID, generation, bound permit canonical
 * hash, stage summary).
 */
public final class TransferStore {
    private TransferStore() {}

    /** Cap for hashing one live file into a manifest snapshot. */
    private static final long SNAPSHOT_CAP_BYTES = 512L * 1024 * 1024;

    // ---------------- layout ----------------

    /** Root-level layout names, defined HERE and nowhere
     *  else (audit M8): TransferInit's transfer-trace set is
     *  built from these constants, so the fail-closed trace
     *  check can never drift from the layout the store
     *  actually writes. */
    public static final String STAGING_AREA_NAME = "import-staging";
    public static final String SHADOW_QUARANTINE_NAME =
            "shadow-quarantine";
    public static final String PROMOTION_JOURNAL_NAME =
            "promotion-journal.jsonl";
    public static final String PRE_PROMOTION_BACKUP_NAME =
            "pre-promotion-backup";
    public static final String TRANSFER_OUT_NAME = "transfer-out";
    public static final String LIVE_MANIFEST_NAME =
            "live-manifest.json";

    public static File journalFile(File storeRoot) {
        return new File(storeRoot, JournalStore.FILE_NAME);
    }

    public static File archivesDir(File storeRoot) {
        return new File(storeRoot, "archives");
    }

    public static File archiveFile(File storeRoot, String archiveId) {
        return new File(archivesDir(storeRoot), archiveId);
    }

    public static File stagingArea(File storeRoot) {
        return new File(storeRoot, STAGING_AREA_NAME);
    }

    public static File stagingRoot(File storeRoot, String deviceId,
                                   long generation, String packageSha256) {
        return new File(new File(new File(stagingArea(storeRoot),
                deviceId), Long.toString(generation)), packageSha256);
    }

    public static File stagingJournal(File stagingRoot) {
        return new File(stagingRoot, JournalStore.FILE_NAME);
    }

    /** Directory a foreign identity's live placement is
     *  quarantined into before a promotion (design section 7.2
     *  supplement, F-21: the displaced identity's journal and
     *  archives are preserved INTACT here and are never used
     *  as the receiving identity's old placement; the
     *  receiving side starts from an empty placement). Files
     *  keep their live key paths inside the directory. */
    public static File shadowQuarantineDir(File storeRoot,
                                            String deviceId) {
        return new File(new File(storeRoot, SHADOW_QUARANTINE_NAME),
                deviceId);
    }

    public static File stagingArchive(File stagingRoot, String archiveId) {
        return new File(new File(stagingRoot, "archives"), archiveId);
    }

    public static File stagingPackageDir(File stagingRoot) {
        return new File(stagingRoot, "package");
    }

    public static File stagingPackageFile(File stagingRoot) {
        return new File(stagingPackageDir(stagingRoot), "package.json");
    }

    public static File stagingRecordFile(File stagingRoot) {
        return new File(stagingRoot, "staging-record.json");
    }

    public static File backupRoot(File storeRoot, long generation,
                                  String trialId) {
        return new File(new File(new File(storeRoot,
                PRE_PROMOTION_BACKUP_NAME), Long.toString(generation)),
                trialId);
    }

    public static File promotionJournal(File storeRoot) {
        return new File(storeRoot, PROMOTION_JOURNAL_NAME);
    }

    public static File liveManifestFile(File storeRoot) {
        return new File(storeRoot, LIVE_MANIFEST_NAME);
    }

    // ---------------- staging record ----------------

    /** The explicit value {@link StagingRecord#wrappedSeedB64}
     *  carries once the audit M10 sweep has invalidated the
     *  wrapped seed: the record itself stays (a committed_r2
     *  generation still awaiting its ack needs its
     *  confirm_secret to re-issue the receipt), but the seed
     *  wrap is gone for good. A real wrap is Base64 of
     *  (12-byte IV || ciphertext) — at least 28 bytes, at
     *  least 40 characters — so this short sentinel can never
     *  collide with one. fromMap accepts it as a COMPLETE
     *  record (the seed is deliberately absent, not missing);
     *  TransferReceiver.unwrapSeed refuses it explicitly. */
    public static final String WRAPPED_SEED_ERASED = "erased";

    public static final class StagingRecord {
        public String deviceId;
        public long generation;
        public String packageSha256;
        /** Full manifest map (fixed facts from the package). */
        public Map<String, Object> manifest;
        /** Wrapped seed (Base64) and confirm_secret (Base64),
         *  under THIS installation's own vault wrap key. */
        public String wrappedSeedB64;
        public String wrappedSecretB64;
        /** Verified hashes from expansion: journal file SHA-256
         *  and per-archive file SHA-256 (archive id → hex). */
        public String journalSha256;
        public Map<String, String> archiveSha256 = new LinkedHashMap<>();
        /** Final check round 3, H-10: the file SHA-256 values
         *  whose FULL signature verification has completed for
         *  the staged journal / archives. verifyStagedEntities
         *  re-runs on every stagePackage call — including
         *  every wait-absorption re-issue — and a full
         *  JournalFile.parse verifies every entry signature
         *  (O(n) Ed25519 verifies, unbudgeted). When a file's
         *  current SHA-256 equals the value recorded here,
         *  its bytes are exactly the ones already fully
         *  verified, so the re-parse may skip the signature
         *  step (structure, sequence, linkage and checkpoint
         *  hashes are still checked in full). Any byte change
         *  changes the SHA and forces a full parse, which
         *  then replaces the recorded value. Null/absent in
         *  records written before this field existed. */
        public String journalSigVerifiedSha256;
        public Map<String, String> archiveSigVerifiedSha256 =
                new LinkedHashMap<>();
        /** True once THIS staging completed intake step 6's
         *  notification-side recording (the record in the
         *  transfer record is the design's basis; this mirror
         *  lets recovery find the staging root from the
         *  transfer record's pointer). */
        public boolean notified;
        /** Established permit full value (permit_intake phase
         *  "prepared" writes it here first; commit moves the
         *  intake history in the transfer record). */
        public Map<String, Object> permitFull;
        /** Attempt ledger (design section 4.3). */
        public long nextAttempt;
        public Map<String, Object> currentAttempt; // nullable
        /** Section 3.5 cursor mirror for the live-journal
         *  re-verification performed at intake (the durable
         *  cursor record itself is verification-cursor.json;
         *  this is the staging-local summary). */
        public long verifiedCount;
        /** The one-shot confirmation key derived from the
         *  transfer code at stage time (E4), Base64; null for
         *  stagings created before it existed. A one-way
         *  derivative — the code itself is never persisted.
         *  This is the IN-MEMORY / legacy form: on disk the
         *  key is kept wrapped ({@link #commitKeyWrappedB64}),
         *  like the seed and confirm_secret above — it was
         *  the one secret-adjacent value the record persisted
         *  in plaintext (audit Low). */
        public String commitKeyB64;
        /** Wrapped commit key: Base64 of (12-byte IV ||
         *  ciphertext) under the installation's vault wrap
         *  key, the AesGcmSeedBox blob shape used for the
         *  wrapped seed. Null in records written before
         *  wrapping (their plaintext commit_key is read
         *  instead, and re-sealed on the next save through
         *  the receiver). */
        public String commitKeyWrappedB64;
        /** Audit F7: the M3 rescue ledger — how many times
         *  the re-presentation rescue (re-deriving a missing
         *  confirmation key from a presented transfer code)
         *  has fired for THIS staging, and when it last did.
         *  The ledger lives in this record so the firing and
         *  the rescued key are persisted in the same atomic
         *  write; a rescue that left no trace here never
         *  happened as far as any later audit can tell. Zero
         *  in records written before the ledger existed. */
        public long commitKeyRescueCount;
        public long commitKeyRescueLastUnix;

        /** True when the staging carries a confirmation key
         *  in either storage form. */
        public boolean hasCommitKeyMaterial() {
            return commitKeyB64 != null || commitKeyWrappedB64 != null;
        }

        /** True once the audit M10 sweep has invalidated
         *  this record's wrapped seed (the seed is gone;
         *  the rest of the record — confirm_secret included
         *  — is untouched). */
        public boolean seedErased() {
            return WRAPPED_SEED_ERASED.equals(wrappedSeedB64);
        }

        /** Invalidate the wrapped seed (audit M10 sweep):
         *  replaces the wrap with the explicit erased
         *  marker. The caller persists the record. */
        public void eraseWrappedSeed() {
            wrappedSeedB64 = WRAPPED_SEED_ERASED;
        }

        /** Wrap the in-memory commit key for persistence and
         *  drop the plaintext form. No-op without a key or a
         *  wrap key. */
        public void sealCommitKey(javax.crypto.SecretKey wrapKey)
                throws java.security.GeneralSecurityException {
            if (commitKeyB64 == null || wrapKey == null) {
                return;
            }
            byte[] key = TransferCodec.b64DecodeStrict(
                    commitKeyB64, "commit key");
            AesGcmSeedBox.Wrapped w = AesGcmSeedBox.wrap(wrapKey, key);
            byte[] blob = new byte[w.iv.length + w.ciphertext.length];
            System.arraycopy(w.iv, 0, blob, 0, w.iv.length);
            System.arraycopy(w.ciphertext, 0, blob, w.iv.length,
                    w.ciphertext.length);
            commitKeyWrappedB64 = TransferCodec.b64Encode(blob);
            commitKeyB64 = null;
        }

        /** The derived commit key (Base64 of the raw key) from
         *  either storage form; null when the staging carries
         *  none, or when only the wrapped form exists and no
         *  wrap key is supplied. */
        public String derivedCommitKeyB64(
                javax.crypto.SecretKey wrapKey)
                throws java.security.GeneralSecurityException {
            if (commitKeyB64 != null) {
                return commitKeyB64;
            }
            if (commitKeyWrappedB64 == null || wrapKey == null) {
                return null;
            }
            byte[] blob = TransferCodec.b64DecodeStrict(
                    commitKeyWrappedB64, "wrapped commit key");
            if (blob.length <= 12) {
                throw new java.security.GeneralSecurityException(
                        "wrapped commit key is truncated");
            }
            byte[] iv = new byte[12];
            byte[] ct = new byte[blob.length - 12];
            System.arraycopy(blob, 0, iv, 0, 12);
            System.arraycopy(blob, 12, ct, 0, ct.length);
            return TransferCodec.b64Encode(AesGcmSeedBox.unwrap(
                    wrapKey, new AesGcmSeedBox.Wrapped(iv, ct)));
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("format", "quantus-depin-staging-record");
            m.put("version", 1L);
            m.put("device_id", deviceId);
            m.put("transfer_generation", generation);
            m.put("package_sha256", packageSha256);
            m.put("manifest", manifest);
            m.put("wrapped_seed", wrappedSeedB64);
            m.put("wrapped_secret", wrappedSecretB64);
            m.put("journal_sha256", journalSha256);
            Map<String, Object> am = new LinkedHashMap<>();
            am.putAll(archiveSha256);
            m.put("archive_sha256", am);
            if (journalSigVerifiedSha256 != null) {
                m.put("journal_sig_verified_sha256",
                        journalSigVerifiedSha256);
            }
            if (!archiveSigVerifiedSha256.isEmpty()) {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.putAll(archiveSigVerifiedSha256);
                m.put("archive_sig_verified_sha256", sm);
            }
            m.put("notified", notified);
            if (permitFull != null) m.put("permit_full", permitFull);
            Map<String, Object> ledger = new LinkedHashMap<>();
            ledger.put("next_attempt", nextAttempt);
            if (currentAttempt != null) {
                ledger.put("current", currentAttempt);
            }
            m.put("attempt_ledger", ledger);
            m.put("verified_count", verifiedCount);
            m.put("commit_key_rescue_count", commitKeyRescueCount);
            m.put("commit_key_rescue_last_unix",
                    commitKeyRescueLastUnix);
            if (commitKeyWrappedB64 != null) {
                m.put("commit_key_wrapped", commitKeyWrappedB64);
            } else if (commitKeyB64 != null) {
                // Legacy passthrough only: a record loaded from
                // a pre-wrapping build and re-saved without the
                // receiver (which seals) keeps its key rather
                // than losing it.
                m.put("commit_key", commitKeyB64);
            }
            return m;
        }

        public static StagingRecord fromMap(Map<String, Object> m) {
            if (!"quantus-depin-staging-record".equals(m.get("format"))) {
                throw new IllegalArgumentException(
                        "staging record format mismatch");
            }
            StagingRecord r = new StagingRecord();
            r.deviceId = TransferState.stringOr(m, "device_id", null);
            r.generation = TransferState.longOr(
                    m, "transfer_generation", -1);
            r.packageSha256 = TransferState.stringOr(
                    m, "package_sha256", null);
            r.manifest = TransferState.child(m, "manifest");
            r.wrappedSeedB64 = TransferState.stringOr(
                    m, "wrapped_seed", null);
            r.wrappedSecretB64 = TransferState.stringOr(
                    m, "wrapped_secret", null);
            r.journalSha256 = TransferState.stringOr(
                    m, "journal_sha256", null);
            Map<String, Object> am = TransferState.child(
                    m, "archive_sha256");
            if (am != null) {
                for (Map.Entry<String, Object> e : am.entrySet()) {
                    if (e.getValue() instanceof String) {
                        r.archiveSha256.put(e.getKey(),
                                (String) e.getValue());
                    }
                }
            }
            r.journalSigVerifiedSha256 = TransferState.stringOr(
                    m, "journal_sig_verified_sha256", null);
            Map<String, Object> sm = TransferState.child(
                    m, "archive_sig_verified_sha256");
            if (sm != null) {
                for (Map.Entry<String, Object> e : sm.entrySet()) {
                    if (e.getValue() instanceof String) {
                        r.archiveSigVerifiedSha256.put(e.getKey(),
                                (String) e.getValue());
                    }
                }
            }
            r.notified = TransferState.boolOr(m, "notified", false);
            r.permitFull = TransferState.child(m, "permit_full");
            Map<String, Object> ledger = TransferState.child(
                    m, "attempt_ledger");
            if (ledger != null) {
                r.nextAttempt = TransferState.longOr(
                        ledger, "next_attempt", 0);
                r.currentAttempt = TransferState.child(ledger, "current");
            }
            r.verifiedCount = TransferState.longOr(
                    m, "verified_count", 0);
            r.commitKeyB64 = TransferState.stringOr(
                    m, "commit_key", null);
            r.commitKeyWrappedB64 = TransferState.stringOr(
                    m, "commit_key_wrapped", null);
            r.commitKeyRescueCount = TransferState.longOr(
                    m, "commit_key_rescue_count", 0);
            r.commitKeyRescueLastUnix = TransferState.longOr(
                    m, "commit_key_rescue_last_unix", 0);
            if (r.deviceId == null || r.packageSha256 == null
                    || r.manifest == null || r.wrappedSeedB64 == null
                    || r.wrappedSecretB64 == null) {
                throw new IllegalArgumentException(
                        "staging record is incomplete");
            }
            return r;
        }

        /** The canonical hash of the currently bound permit, or
         *  null before any attempt started. */
        public String boundPermitHash() {
            if (currentAttempt == null) return null;
            return TransferState.stringOr(
                    currentAttempt, "permit_hash", null);
        }
    }

    public static StagingRecord loadStaging(File stagingRoot)
            throws IOException {
        File f = stagingRecordFile(stagingRoot);
        if (!f.exists()) return null;
        return StagingRecord.fromMap(Json.parseObject(new String(
                TransferIo.readAll(f, 64L * 1024 * 1024),
                java.nio.charset.StandardCharsets.UTF_8)));
    }

    public static void saveStaging(File stagingRoot, StagingRecord r)
            throws IOException {
        stagingRoot.mkdirs();
        TransferIo.writeRecordAtomic(
                stagingRecordFile(stagingRoot),
                TransferCodec.jcs(r.toMap()));
    }

    /** Adoption for a staging record: fsync + re-read; returns
     *  the durable record (never null when the file exists). */
    public static StagingRecord adoptStaging(File stagingRoot)
            throws IOException {
        File f = stagingRecordFile(stagingRoot);
        if (!f.exists()) return null;
        byte[] reread = TransferIo.adopt(f);
        return StagingRecord.fromMap(Json.parseObject(new String(
                reread, java.nio.charset.StandardCharsets.UTF_8)));
    }

    // ---------------- live manifest ----------------

    /**
     * The live manifest (design section 4.3): an independent
     * durable record of what the live placement currently is —
     * file list with SHA-256, origin ("existing" /
     * "transfer:&lt;generation&gt;" / "after_loss"), and a
     * monotonically increasing placement sequence. Updated at
     * exactly the four design points.
     */
    public static final class LiveManifest {
        public Map<String, String> files = new LinkedHashMap<>();
        public String origin;
        public long sequence;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("format", "quantus-depin-live-manifest");
            m.put("version", 1L);
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.putAll(files);
            m.put("files", fm);
            m.put("origin", origin);
            m.put("sequence", sequence);
            return m;
        }

        public static LiveManifest fromMap(Map<String, Object> m) {
            if (!"quantus-depin-live-manifest".equals(m.get("format"))) {
                throw new IllegalArgumentException(
                        "live manifest format mismatch");
            }
            LiveManifest lm = new LiveManifest();
            Map<String, Object> fm = TransferState.child(m, "files");
            if (fm != null) {
                for (Map.Entry<String, Object> e : fm.entrySet()) {
                    if (e.getValue() instanceof String) {
                        lm.files.put(e.getKey(), (String) e.getValue());
                    }
                }
            }
            lm.origin = TransferState.stringOr(m, "origin", null);
            lm.sequence = TransferState.longOr(m, "sequence", 0);
            return lm;
        }

        public String sha256Hex() {
            return Hex.encode(DepinCore.sha256(TransferCodec.jcs(toMap())));
        }
    }

    public static LiveManifest loadLiveManifest(File storeRoot)
            throws IOException {
        File f = liveManifestFile(storeRoot);
        if (!f.exists()) return null;
        return LiveManifest.fromMap(Json.parseObject(new String(
                TransferIo.readAll(f, 64L * 1024 * 1024),
                java.nio.charset.StandardCharsets.UTF_8)));
    }

    public static void saveLiveManifest(File storeRoot, LiveManifest lm)
            throws IOException {
        storeRoot.mkdirs();
        TransferIo.writeRecordAtomic(
                liveManifestFile(storeRoot), TransferCodec.jcs(lm.toMap()));
    }

    /**
     * Snapshot the actual live placement (journal + archives)
     * as a manifest over the files that exist right now:
     * "journal.json" and "archives/&lt;id&gt;" keys.
     */
    public static LiveManifest snapshotLive(File storeRoot, String origin,
                                            long sequence)
            throws IOException {
        LiveManifest lm = new LiveManifest();
        lm.origin = origin;
        lm.sequence = sequence;
        File j = journalFile(storeRoot);
        if (j.exists()) {
            lm.files.put(JournalStore.FILE_NAME, Hex.encode(DepinCore.sha256(
                    TransferIo.readAll(j, SNAPSHOT_CAP_BYTES))));
        }
        File ad = archivesDir(storeRoot);
        File[] archives = (ad == null) ? null : ad.listFiles();
        if (archives != null) {
            java.util.Arrays.sort(archives);
            for (File a : archives) {
                if (a.isFile()) {
                    lm.files.put("archives/" + a.getName(),
                            Hex.encode(DepinCore.sha256(TransferIo.readAll(
                                    a, SNAPSHOT_CAP_BYTES))));
                }
            }
        }
        // Pre-migration installs keep JournalStore's sidecar
        // archives next to the journal; they are part of the
        // placement too, under the same logical key (the section
        // 4.2 layout migration moves them under archives/, after
        // which only the loop above finds them).
        for (String sidecar : new String[]{
                JournalStore.ARCHIVE_V04_FILE_NAME,
                JournalStore.ARCHIVE_FILE_NAME}) {
            File a = new File(storeRoot, sidecar);
            if (a.isFile() && !lm.files.containsKey("archives/" + sidecar)) {
                lm.files.put("archives/" + sidecar,
                        Hex.encode(DepinCore.sha256(TransferIo.readAll(
                                a, SNAPSHOT_CAP_BYTES))));
            }
        }
        return lm;
    }

    /** Compare the live placement against a manifest: returns
     *  true only when the file sets and hashes match exactly. */
    public static boolean liveMatches(File storeRoot, LiveManifest lm)
            throws IOException {
        if (lm == null) return false;
        LiveManifest now = snapshotLive(storeRoot, lm.origin, lm.sequence);
        return now.files.equals(lm.files);
    }

    // ---------------- capacity (section 3.4) ----------------

    /** Total bytes of every staging root that exists (all
     *  generations count — design section 4.3 sweep rule (3)). */
    public static long stagingBytes(File storeRoot) {
        return TransferIo.treeSize(stagingArea(storeRoot));
    }

    public static boolean stagingWithinCap(File storeRoot) {
        return stagingBytes(storeRoot) <= TransferPackage.MAX_STAGING_BYTES;
    }

    /** The design section 3.4 free-space floor for the
     *  DePIN store volume: 2 GiB. One constant, shared by the
     *  probe below, the receiver's write gates and the
     *  journal store's append gate (H-11). */
    public static final long FREE_FLOOR_BYTES = 2L * 1024 * 1024 * 1024;

    /** The 2 GiB free-space floor for the DePIN store volume:
     *  checked before write batches; only DePIN's own writes
     *  are constrained. */
    public static boolean freeFloorRespected(File storeRoot) {
        return freeFloorRespected(storeRoot, FREE_FLOOR_BYTES);
    }

    /** The floor check with an explicit floor (final check
     *  round 3, H-11): until this round the no-arg form above
     *  was a probe nobody called — the design's 2 GiB floor
     *  constrained nothing. The receiver's expansion /
     *  promotion gates and the journal append path now call
     *  the check with the floor the app layer installed.
     *  This two-argument form is the no-incoming form: it
     *  delegates to the three-argument form below with an
     *  incoming byte count of 0. */
    public static boolean freeFloorRespected(File storeRoot,
                                             long floorBytes) {
        return freeFloorRespected(storeRoot, floorBytes, 0);
    }

    /** The floor check accounting for the write about to
     *  happen (self-check 2026-11-11, S-04): the floor is
     *  respected only when the usable space still covers
     *  the floor PLUS the incoming bytes, i.e. the floor is
     *  what remains AFTER the write lands. */
    public static boolean freeFloorRespected(File storeRoot,
                                             long floorBytes,
                                             long incomingBytes) {
        File probe = storeRoot;
        while (probe != null && !probe.exists()) probe = probe.getParentFile();
        if (probe == null) return false;
        return probe.getUsableSpace() >= floorBytes + incomingBytes;
    }

    /** All staging roots that currently exist, oldest path
     *  order (device, generation, package). */
    public static List<File> stagingRoots(File storeRoot) {
        List<File> out = new ArrayList<>();
        File area = stagingArea(storeRoot);
        File[] devices = area.listFiles();
        if (devices == null) return out;
        java.util.Arrays.sort(devices);
        for (File d : devices) {
            File[] gens = d.listFiles();
            if (gens == null) continue;
            java.util.Arrays.sort(gens);
            for (File g : gens) {
                File[] pkgs = g.listFiles();
                if (pkgs == null) continue;
                java.util.Arrays.sort(pkgs);
                for (File p : pkgs) {
                    if (p.isDirectory()) out.add(p);
                }
            }
        }
        return out;
    }

    /** Write bytes to a file creating parent dirs, with file +
     *  parent fsync (used for staged payloads and retained
     *  copies). */
    public static void writeSynced(File f, byte[] bytes) throws IOException {
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
            out.flush();
            out.getFD().sync();
        }
        if (parent != null) TransferIo.fsyncDir(parent);
    }
}
