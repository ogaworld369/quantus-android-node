package app.quantusnode.node.depin.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * JVM harness for the transfer machinery (integration design
 * section 4, build step 4): package codec strictness, the
 * section 4.6 rejection corpus, the full sender→receiver cycle
 * (export → intake → notice → permit → confirm → receipt →
 * ack), cancellation paths, the section 3.2 writer gate, the
 * pre-notification sync boundary under fsync failure, crash
 * recovery classification, and the section 3.5 budget ledger.
 *
 * All identities are throwaway test identities; no real
 * journal is ever touched.
 */
public final class TransferHarnessMain {
    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("PASS " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static boolean throwsAny(ThrowingRunnable r) {
        try {
            r.run();
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean throwsType(ThrowingRunnable r,
                                      Class<?> type) {
        try {
            r.run();
            return false;
        } catch (Exception e) {
            return type.isInstance(e);
        }
    }

    // ---------------- scripted environment ----------------

    private static final SecureRandom RANDOM = new SecureRandom();

    private static SecretKey testWrapKey() {
        byte[] k = new byte[32];
        for (int i = 0; i < 32; i++) k[i] = (byte) (0xA0 + i);
        return new SecretKeySpec(k, "AES");
    }

    private static final class TestClock
            implements TransferSender.Clock, TransferReceiver.Clock {
        long wall = 1762003600L;
        public long wallUnix() { return wall; }
        public long monoNanos() { return System.nanoTime(); }
    }

    private static final class TestFinalized
            implements TransferSender.FinalizedSource,
                    TransferReceiver.FinalizedSource {
        long value = 400000L;
        boolean fail = false;
        public long readFinalized() throws Exception {
            if (fail) throw new Exception("scripted RPC failure");
            return value;
        }
    }

    /** JVM counterpart of the apps' AndroidTimeSource: the
     *  thread-CPU quantity is read through ThreadMXBean here
     *  (JDK-only API; ART has no java.lang.management, so the
     *  Android implementation reads
     *  SystemClock.currentThreadTimeMillis() instead — audit
     *  C-1). Both are monotonic per thread and the section 3.5
     *  budget only reads differences. */
    private static final class TestTimeSource
            implements Reverification.TimeSource {
        public String bootId() { return "boot-test"; }
        public long monoMs() {
            return System.nanoTime() / 1_000_000L;
        }
        public long threadCpuMs() {
            return java.lang.management.ManagementFactory
                    .getThreadMXBean().getCurrentThreadCpuTime()
                    / 1_000_000L;
        }
    }

    private static final class TestFreeze
            implements TransferSender.FreezeControl {
        int freezes = 0;
        int unfreezes = 0;
        public void drainAndFreeze() { freezes++; }
        public void unfreeze() { unfreezes++; }
    }

    private static final class TestForeignFreeze
            implements TransferReceiver.ForeignWriterFreeze {
        int freezes = 0;
        String lastDeviceId;
        public void freezeForeignWriter(String activeDeviceId) {
            freezes++;
            lastDeviceId = activeDeviceId;
        }
    }

    private static byte[] testSeed() {
        byte[] seed = new byte[32];
        for (int i = 0; i < 32; i++) seed[i] = (byte) (i + 1);
        return seed;
    }

    private static byte[] otherSeed() {
        byte[] seed = new byte[32];
        for (int i = 0; i < 32; i++) seed[i] = (byte) (0x40 + i);
        return seed;
    }

    private static List<DepinCore.Observation> syntheticObservations(
            int count) {
        List<DepinCore.Observation> obs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long number = 350000L + i * 100L;
            DepinCore.Observation o = new DepinCore.Observation();
            o.timestampUnix = 1762000000L + i * 600L;
            o.bestBlockNumber = number;
            o.bestBlockHash = DepinCore.sha256(
                    ("quantus-synthetic-block-" + number)
                            .getBytes(StandardCharsets.US_ASCII));
            o.observedPeers = 8 + (i % 5);
            o.finalizedBlockNumber = number - 10;
            obs.add(o);
        }
        return obs;
    }

    private static JournalStore.EntrySigner signer(byte[] seed) {
        return (hash, seq) -> DepinCore.journalEntrySig(seed, hash, seq);
    }

    private static JournalStore.EntryVerifier verifier(byte[] pub) {
        return (hash, seq, sig) ->
                DepinCore.verifyJournalEntrySig(pub, hash, seq, sig);
    }

    private static File tmpDir(String name) {
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "depin-transfer-" + name + "-" + System.nanoTime());
        dir.mkdirs();
        return dir;
    }

    /** Write a journal of {@code count} checkpoints (current
     *  format) into a store root. */
    private static void writeJournal(File storeRoot, byte[] seed,
                                     int count) throws Exception {
        byte[] pub = Ed25519.publickeyFromSeed(seed);
        String deviceId = DepinCore.deviceIdFromPubkey(pub);
        JournalStore j = JournalStore.forFile(
                new File(storeRoot, JournalStore.FILE_NAME), deviceId,
                Hex.encode(pub), signer(seed), verifier(pub));
        j.bindIdentity(deviceId, Hex.encode(pub));
        List<DepinCore.Observation> obs = syntheticObservations(count);
        for (int i = 0; i < count; i++) {
            j.append(DepinCore.makeCheckpoint(i, obs.get(i),
                    j.nextPrevState(), j.nextPrevCheckpointHash(), 50, pub));
        }
    }

    /** Write a journal whose chain matches the synthetic one
     *  up to {@code forkFrom} and diverges afterwards (same
     *  identity, sibling history) — the fork fixture. */
    private static void writeJournalForked(File storeRoot, byte[] seed,
                                           int count, int forkFrom)
            throws Exception {
        byte[] pub = Ed25519.publickeyFromSeed(seed);
        String deviceId = DepinCore.deviceIdFromPubkey(pub);
        JournalStore j = JournalStore.forFile(
                new File(storeRoot, JournalStore.FILE_NAME), deviceId,
                Hex.encode(pub), signer(seed), verifier(pub));
        j.bindIdentity(deviceId, Hex.encode(pub));
        List<DepinCore.Observation> obs = syntheticObservations(count);
        for (int i = forkFrom; i < count; i++) {
            DepinCore.Observation o = obs.get(i);
            o.bestBlockNumber = o.bestBlockNumber + 7;
            o.bestBlockHash = DepinCore.sha256(
                    ("quantus-forked-block-" + o.bestBlockNumber)
                            .getBytes(StandardCharsets.US_ASCII));
            o.finalizedBlockNumber = o.bestBlockNumber - 10;
        }
        for (int i = 0; i < count; i++) {
            j.append(DepinCore.makeCheckpoint(i, obs.get(i),
                    j.nextPrevState(), j.nextPrevCheckpointHash(), 50, pub));
        }
    }

    /** Write a legacy (v0.4) archive as a sidecar file, in the
     *  shape JournalStore keeps: header + signed entry lines. */
    private static void writeLegacyArchive(File storeRoot, byte[] seed,
                                           int count) throws Exception {
        byte[] pub = Ed25519.publickeyFromSeed(seed);
        String deviceId = DepinCore.deviceIdFromPubkey(pub);
        StringBuilder sb = new StringBuilder();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "header");
        header.put("format", "quantus-depin-journal");
        header.put("version", "0.4");
        header.put("device_id", deviceId);
        header.put("pubkey", Hex.encode(pub));
        sb.append(new String(TransferCodec.jcs(header),
                StandardCharsets.UTF_8)).append('\n');
        byte[] prevState = DepinCore.ZERO32;
        byte[] prevHash = DepinCore.ZERO32;
        List<DepinCore.Observation> obs = syntheticObservations(count);
        for (int i = 0; i < count; i++) {
            DepinCore.Checkpoint cp = DepinCore.makeCheckpoint(
                    DepinCore.FMT_V04, i, obs.get(i), prevState, prevHash,
                    50, pub);
            Map<String, Object> line = new LinkedHashMap<>(cp.toMap());
            line.put("entry_sig", Hex.encode(
                    DepinCore.journalEntrySig(seed, cp.checkpointHash, i)));
            sb.append(new String(TransferCodec.jcs(line),
                    StandardCharsets.UTF_8)).append('\n');
            prevState = cp.chainState;
            prevHash = cp.checkpointHash;
        }
        Files.write(new File(storeRoot, JournalStore.ARCHIVE_V04_FILE_NAME)
                .toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static final class Pair {
        File senderRoot;
        File receiverRoot;
        byte[] seed;
        String deviceId;
        TransferVault senderVault;
        TransferVault receiverVault;
        TestClock clock = new TestClock();
        TestFinalized senderFin = new TestFinalized();
        TestFinalized receiverFin = new TestFinalized();
        TestFreeze freeze = new TestFreeze();
        TransferSender sender;
        TransferReceiver receiver;

        TransferSender newSender(long validityBlocks) {
            return new TransferSender(senderRoot, senderVault, RANDOM,
                    "test-app", freeze, senderFin, clock,
                    24L * 3600, validityBlocks, 100);
        }

        TransferReceiver newReceiver() {
            return new TransferReceiver(receiverRoot, receiverVault,
                    testWrapKey(), receiverFin, clock,
                    new TestTimeSource());
        }
    }

    /** A sender store with a grown journal (+ legacy archive)
     *  and an empty first-receive receiver store. */
    private static Pair newPair(boolean withArchive) throws Exception {
        Pair p = new Pair();
        p.seed = testSeed();
        p.deviceId = DepinCore.deviceIdFromPubkey(
                Ed25519.publickeyFromSeed(p.seed));
        p.senderRoot = tmpDir("sender");
        p.receiverRoot = tmpDir("receiver");
        writeJournal(p.senderRoot, p.seed, 6);
        if (withArchive) writeLegacyArchive(p.senderRoot, p.seed, 3);
        p.senderVault = new TransferVault(p.senderRoot, testWrapKey());
        TransferInit.initialise(p.senderRoot, p.senderVault,
                TransferInit.MODE_NEW, p.seed, RANDOM);
        p.receiverVault = new TransferVault(p.receiverRoot, testWrapKey());
        TransferInit.initialise(p.receiverRoot, p.receiverVault,
                TransferInit.MODE_FIRST_RECEIVE, null, RANDOM);
        p.sender = p.newSender(1000);
        p.receiver = p.newReceiver();
        return p;
    }

    // ---------------- tests ----------------

    private static void testCodec() throws Exception {
        // JCS rejects non-integer numbers (JCS form for this
        // protocol carries integers only).
        check("codec: JCS rejects a double",
                throwsAny(() -> TransferCodec.jcs(
                        java.util.Collections.singletonMap("x", 1.5d))));
        // Strict Base64: canonical re-encode equality — a
        // variant with non-zero trailing bits is rejected.
        check("codec: strict b64 accepts canonical",
                TransferCodec.b64DecodeStrict("QQ==", "t").length == 1);
        check("codec: strict b64 rejects trailing-bit variant",
                throwsAny(() -> TransferCodec.b64DecodeStrict("QR==", "t")));
        check("codec: strict b64 rejects whitespace",
                throwsAny(() -> TransferCodec.b64DecodeStrict("QQ==\n", "t")));
        // Transfer code: alphabet, display grouping, hyphen-only
        // normalisation (no case folding).
        String code = TransferCodec.generateCode(RANDOM);
        check("codec: code is 12 chars from the alphabet",
                code.length() == 12 && code.chars().allMatch(
                        c -> TransferCodec.CODE_ALPHABET.indexOf(c) >= 0));
        check("codec: display form groups by 4",
                TransferCodec.codeForDisplay(code).length() == 14);
        check("codec: normalize strips hyphens only",
                TransferCodec.normalizeCode(
                        TransferCodec.codeForDisplay(code)).equals(code));
        check("codec: normalize rejects lowercase (no folding)",
                TransferCodec.normalizeCode(
                        code.toLowerCase(java.util.Locale.ROOT)) == null);
        // Seal/open roundtrip; wrong code fails the GCM tag.
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        javax.crypto.SecretKey key = TransferCodec.deriveKey(code, salt);
        byte[] aad = "manifest".getBytes(StandardCharsets.UTF_8);
        byte[] sealed = TransferCodec.sealPayload(
                key, iv, aad, "hello".getBytes(StandardCharsets.UTF_8));
        check("codec: seal/open roundtrip",
                "hello".equals(new String(TransferCodec.openPayload(
                        key, iv, aad, sealed), StandardCharsets.UTF_8)));
        String wrong = TransferCodec.generateCode(RANDOM);
        check("codec: wrong code fails",
                throwsAny(() -> TransferCodec.openPayload(
                        TransferCodec.deriveKey(wrong, salt), iv, aad,
                        sealed)));
    }

    private static void testWriterGate() throws Exception {
        Pair p = newPair(false);
        TransferInit.Gate g0 = TransferInit.writerGate(
                p.receiverRoot, true);
        check("gate: first-receive store has no binding",
                !g0.allowed && "no_active_binding".equals(g0.reason));
        TransferInit.Gate g1 = TransferInit.writerGate(p.senderRoot, false);
        check("gate: explicit ON required (identity-bound)",
                !g1.allowed && "not_switched_on".equals(g1.reason));
        TransferInit.Gate g2 = TransferInit.writerGate(p.senderRoot, true);
        check("gate: holder + ON opens the writer",
                g2.allowed && p.deviceId.equals(g2.deviceId));
    }

    /** The full cycle of design section 4.6, happy path. */
    private static TransferSender.ExportResult testFullCycle(Pair p)
            throws Exception {
        TransferSender.ExportResult ex = p.sender.exportPackage(p.seed);
        check("cycle: freeze was drained for export", p.freeze.freezes == 1);
        check("cycle: generation 1, package written",
                ex.generation == 1 && ex.packageFile.exists());
        TransferState sState = TransferState.load(p.senderRoot);
        check("cycle: S1 recorded with the package hash",
                sState.sender(p.deviceId) != null
                        && ex.packageSha256.equals(TransferState.stringOr(
                                sState.sender(p.deviceId),
                                "package_sha256", null)));
        check("cycle: writer gate denies while S1 (frozen)",
                !TransferInit.writerGate(p.senderRoot, true).allowed);
        // Intake.
        TransferReceiver.IntakeResult in = p.receiver.intakePackage(
                ex.packageFile, ex.transferCode);
        check("cycle: intake reaches notified R1",
                in.checkpointCount == 6 && in.generation == 1);
        TransferState rState = TransferState.load(p.receiverRoot);
        Map<String, Object> rec = rState.receiver(p.deviceId, 1);
        check("cycle: notified R1 + NONE recorded together",
                rec != null && TransferState.boolOr(rec, "notified_r1", false)
                        && TransferState.child(rec, "intake_none") != null);
        check("cycle: pre-promotion manifest fixed (empty placement)",
                TransferStore.loadLiveManifest(p.receiverRoot) != null);
        // Notice → verify → permit.
        Map<String, Object> notice = p.receiver.buildNotice(p.deviceId, 1);
        String dest = p.sender.verifyNotice(notice);
        check("cycle: notice verifies, destination returned",
                dest != null && dest.equals(rState.installId()));
        Map<String, Object> permit = p.sender.issuePermit(p.seed, notice);
        check("cycle: permit issued and durably recorded",
                TransferSender.issuedPermitHashes(
                        TransferState.load(p.senderRoot)
                                .sender(p.deviceId))
                        .contains(TransferCodec.fullValueHash(permit)));
        check("cycle: permit intake commits",
                p.receiver.intakePermit(permit)
                        == TransferReceiver.PermitIntakeOutcome.COMMITTED);
        // Confirm → R2.
        p.receiver.confirmTrial(p.deviceId, 1);
        rState = TransferState.load(p.receiverRoot);
        rec = rState.receiver(p.deviceId, 1);
        Map<String, Object> closure = TransferState.child(rec, "closure");
        check("cycle: R2 closure recorded",
                closure != null && "committed_r2".equals(
                        TransferState.stringOr(closure, "kind", null)));
        byte[] senderJournal = Files.readAllBytes(
                TransferStore.journalFile(p.senderRoot).toPath());
        byte[] receiverJournal = Files.readAllBytes(
                TransferStore.journalFile(p.receiverRoot).toPath());
        check("cycle: live journal is the transferred journal",
                Arrays.equals(senderJournal, receiverJournal));
        TransferStore.LiveManifest lm =
                TransferStore.loadLiveManifest(p.receiverRoot);
        check("cycle: live manifest origin is the transfer",
                lm != null && "transfer:1".equals(lm.origin));
        check("cycle: seed vaulted under (1, receiver)",
                p.receiverVault.has(TransferVault.receiverKey(1))
                        && Arrays.equals(p.receiverVault.get(
                                TransferVault.receiverKey(1)), p.seed));
        check("cycle: writer gate needs the NEW identity's own ON",
                !TransferInit.writerGate(p.receiverRoot, false).allowed
                        && TransferInit.writerGate(
                                p.receiverRoot, true).allowed);
        // Receipt → S2 → ack.
        Map<String, Object> receipt =
                p.receiver.buildReceipt(p.deviceId, 1);
        Map<String, Object> ack = p.sender.onReceipt(p.seed, receipt);
        sState = TransferState.load(p.senderRoot);
        check("cycle: sender reached S2 (binding released)",
                sState.sender(p.deviceId) == null
                        && sState.active() == null);
        check("cycle: sender vault material erased at S2",
                !p.senderVault.has(TransferVault.senderKey(1)));
        check("cycle: ack re-presentable from S2 history",
                TransferCodec.jcsEquals(p.sender.rePresentAck(1), ack));
        File stagingRoot = TransferStore.stagingRoot(
                p.receiverRoot, p.deviceId, 1, ex.packageSha256);
        p.receiver.intakeAck(ack);
        rState = TransferState.load(p.receiverRoot);
        check("cycle: ack accepted, then materials deleted",
                TransferState.boolOr(rState.receiver(p.deviceId, 1),
                        "ack_received", false)
                        && !stagingRoot.exists());
        return ex;
    }

    /** A sender store grown to a production-scale journal
     *  (plus a legacy archive), and an empty first-receive
     *  receiver store — the shape of the real companion journal
     *  (227 checkpoints) the production migration carries. */
    private static Pair newLargePair(int checkpoints) throws Exception {
        Pair p = new Pair();
        p.seed = testSeed();
        p.deviceId = DepinCore.deviceIdFromPubkey(
                Ed25519.publickeyFromSeed(p.seed));
        p.senderRoot = tmpDir("large-sender");
        p.receiverRoot = tmpDir("large-receiver");
        writeJournal(p.senderRoot, p.seed, checkpoints);
        writeLegacyArchive(p.senderRoot, p.seed, 3);
        p.senderVault = new TransferVault(p.senderRoot, testWrapKey());
        TransferInit.initialise(p.senderRoot, p.senderVault,
                TransferInit.MODE_NEW, p.seed, RANDOM);
        p.receiverVault = new TransferVault(p.receiverRoot, testWrapKey());
        TransferInit.initialise(p.receiverRoot, p.receiverVault,
                TransferInit.MODE_FIRST_RECEIVE, null, RANDOM);
        p.sender = p.newSender(1000);
        p.receiver = p.newReceiver();
        return p;
    }

    /** A time source whose thread-CPU clock races ahead in
     *  fixed steps, so every section 3.5 slice is exhausted
     *  after a few checkpoints and the stage MUST pause —
     *  deterministically, on any machine. The monotonic clock
     *  is virtual too and is advanced by the test between
     *  attempts, which fulfils each settled slice's wait
     *  obligation exactly as wall-clock time between two user
     *  actions would. */
    private static final class FastCpuTimeSource
            implements Reverification.TimeSource {
        long mono = 1_000_000L;
        long cpu = 0L;
        public String bootId() { return "boot-test"; }
        public long monoMs() { return mono; }
        public long threadCpuMs() { cpu += 500L; return cpu; }
    }

    /** Production-scale cycle through the SPLIT intake the apps
     *  use (stage, then complete), driving the section 3.5
     *  pause/resume exactly as the UI does: a paused stage is
     *  re-issued — from the RETAINED staging copy, with no
     *  re-expansion — until it returns, and only then is the
     *  intake completed. This is the path whose UI wiring failed
     *  in the first production attempt (a paused or interrupted
     *  stage left a durable unnotified staging that no UI action
     *  could complete). The stage phase runs under FastCpuTimeSource
     *  so the pause/resume loop is exercised on every run; each
     *  attempt constructs a fresh receiver, as the apps'
     *  per-operation runtime construction does. */
    private static void testLargeJournalCycle() throws Exception {
        final int n = 250;
        Pair p = newLargePair(n);
        TransferSender.ExportResult ex = p.sender.exportPackage(p.seed);
        check("large: export over " + n + " checkpoints",
                ex.generation == 1 && ex.packageFile.exists());
        File stagingRoot = TransferStore.stagingRoot(
                p.receiverRoot, p.deviceId, 1, ex.packageSha256);
        File retainedCopy = TransferStore.stagingPackageFile(stagingRoot);
        FastCpuTimeSource fastTs = new FastCpuTimeSource();
        TransferReceiver.IntakeResult in = null;
        int pauses = 0;
        boolean unnotifiedDurableSeen = false;
        for (int attempt = 0; attempt < 400 && in == null; attempt++) {
            TransferReceiver r = new TransferReceiver(p.receiverRoot,
                    p.receiverVault, testWrapKey(), p.receiverFin,
                    p.clock, fastTs);
            try {
                // First attempt stages the picked file; every
                // resume stages the retained copy (the UI's
                // resume route — the code is not consulted on
                // resume, so an empty one is passed here too).
                in = r.stagePackage(
                        attempt == 0 ? ex.packageFile : retainedCopy,
                        attempt == 0 ? ex.transferCode : "");
            } catch (TransferReceiver.VerificationPausedException paused) {
                pauses++;
                // Mid-pause, the staging must already be durable
                // and UNNOTIFIED — that record is what the UI's
                // state derivation finds and resumes from.
                TransferStore.StagingRecord sr =
                        TransferStore.loadStaging(stagingRoot);
                if (sr != null && !sr.notified
                        && retainedCopy.exists()) {
                    unnotifiedDurableSeen = true;
                }
                // Fulfil the settled slice's wait obligation by
                // advancing the virtual monotonic clock, as the
                // wall-clock gap before a user's resume tap does.
                fastTs.mono += 10_000L;
            }
        }
        check("large: stage completed after pauses (pauses=" + pauses + ")",
                in != null);
        check("large: stage really paused under the budget (pauses >= 3)",
                pauses >= 3);
        check("large: unnotified staging durable across a pause",
                unnotifiedDurableSeen);
        check("large: staged count matches the grown journal",
                in != null && in.checkpointCount == n);
        // Re-staging a completed stage from the retained copy is
        // the resume route's idempotent form: same intake result.
        TransferReceiver.IntakeResult again = p.receiver.stagePackage(
                retainedCopy, "");
        check("large: re-stage from retained copy is idempotent",
                again.checkpointCount == n
                        && ex.packageSha256.equals(again.packageSha256));
        // Complete: the notified R1 the next operations resolve.
        p.receiver.completeIntake(in.deviceId, in.generation,
                in.packageSha256);
        TransferState rState = TransferState.load(p.receiverRoot);
        Map<String, Object> rec = rState.receiver(p.deviceId, 1);
        check("large: notified R1 durable after complete",
                rec != null
                        && TransferState.boolOr(rec, "notified_r1", false));
        check("large: staging mirror marked notified",
                TransferStore.loadStaging(stagingRoot).notified);
        // Notice -> permit -> confirm -> receipt -> ack.
        Map<String, Object> notice = p.receiver.buildNotice(p.deviceId, 1);
        check("large: notice verifies",
                p.sender.verifyNotice(notice) != null);
        Map<String, Object> permit = p.sender.issuePermit(p.seed, notice);
        check("large: permit intake commits",
                p.receiver.intakePermit(permit)
                        == TransferReceiver.PermitIntakeOutcome.COMMITTED);
        p.receiver.confirmTrial(p.deviceId, 1);
        rState = TransferState.load(p.receiverRoot);
        rec = rState.receiver(p.deviceId, 1);
        Map<String, Object> closure = TransferState.child(rec, "closure");
        check("large: R2 closure recorded",
                closure != null && "committed_r2".equals(
                        TransferState.stringOr(closure, "kind", null)));
        byte[] senderJournal = Files.readAllBytes(
                TransferStore.journalFile(p.senderRoot).toPath());
        byte[] receiverJournal = Files.readAllBytes(
                TransferStore.journalFile(p.receiverRoot).toPath());
        check("large: live journal is the transferred journal",
                Arrays.equals(senderJournal, receiverJournal));
        Map<String, Object> receipt =
                p.receiver.buildReceipt(p.deviceId, 1);
        Map<String, Object> ack = p.sender.onReceipt(p.seed, receipt);
        check("large: sender reached S2",
                TransferState.load(p.senderRoot).sender(p.deviceId) == null);
        p.receiver.intakeAck(ack);
        rState = TransferState.load(p.receiverRoot);
        check("large: ack accepted, then materials deleted",
                TransferState.boolOr(rState.receiver(p.deviceId, 1),
                        "ack_received", false)
                        && !stagingRoot.exists());
    }

    private static void testRejectionCorpus() throws Exception {
        // Wrong transfer code.
        Pair p1 = newPair(false);
        TransferSender.ExportResult ex1 = p1.sender.exportPackage(p1.seed);
        String wrongCode = TransferCodec.generateCode(RANDOM);
        check("corpus: wrong transfer code rejected",
                throwsType(() -> p1.receiver.intakePackage(
                        ex1.packageFile, wrongCode),
                        java.security.GeneralSecurityException.class));
        // Tampered payload byte (valid Base64 char swap inside
        // the payload string).
        Pair p2 = newPair(false);
        TransferSender.ExportResult ex2 = p2.sender.exportPackage(p2.seed);
        String content = new String(Files.readAllBytes(
                ex2.packageFile.toPath()), StandardCharsets.UTF_8);
        int idx = content.indexOf("\"payload\":\"") + 11;
        char orig = content.charAt(idx + 30);
        char repl = (orig == 'A') ? 'B' : 'A';
        String tampered = content.substring(0, idx + 30) + repl
                + content.substring(idx + 31);
        File tamperedFile = new File(p2.receiverRoot, "tampered.json");
        Files.write(tamperedFile.toPath(),
                tampered.getBytes(StandardCharsets.UTF_8));
        check("corpus: tampered payload rejected",
                throwsAny(() -> p2.receiver.intakePackage(
                        tamperedFile, ex2.transferCode)));
        // Generation rollback: after gen 1 committed, a fresh
        // package for the same generation is refused.
        Pair p3 = newPair(false);
        TransferSender.ExportResult ex3 = testFullCycle(p3);
        check("corpus: generation at/below committed max rejected",
                throwsAny(() -> p3.receiver.intakePackage(
                        ex3.packageFile, ex3.transferCode)));
        // Permit addressed to another installation.
        Pair p4 = newPair(false);
        TransferSender.ExportResult ex4 = p4.sender.exportPackage(p4.seed);
        p4.receiver.intakePackage(ex4.packageFile, ex4.transferCode);
        Map<String, Object> notice4 = p4.receiver.buildNotice(
                p4.deviceId, 1);
        Map<String, Object> permit4 = p4.sender.issuePermit(
                p4.seed, notice4);
        Pair p5 = newPair(false);
        p5.receiver.intakePackage(ex4.packageFile, ex4.transferCode);
        check("corpus: permit for another installation rejected",
                throwsAny(() -> p5.receiver.intakePermit(permit4)));
        // Expired permit at intake (finalized already past H).
        Pair p6 = newPair(false);
        TransferSender sender6 = p6.newSender(10);
        TransferSender.ExportResult ex6 = sender6.exportPackage(p6.seed);
        p6.receiver.intakePackage(ex6.packageFile, ex6.transferCode);
        Map<String, Object> notice6 = p6.receiver.buildNotice(
                p6.deviceId, 1);
        Map<String, Object> permit6 = sender6.issuePermit(p6.seed, notice6);
        p6.receiverFin.value = 400000L + 10 + 100; // == H
        check("corpus: expired permit latches at intake",
                p6.receiver.intakePermit(permit6)
                        == TransferReceiver.PermitIntakeOutcome
                                .EXPIRED_LATCHED);
        check("corpus: latched permit cannot start a trial",
                throwsType(() -> p6.receiver.confirmTrial(p6.deviceId, 1),
                        TransferReceiver.HoldException.class));
    }

    /** Judgement-step expiry cancels the promotion back to R1;
     *  a second permit then confirms under a new trial ID. */
    private static void testJudgementExpiryAndSecondPermit()
            throws Exception {
        Pair p = newPair(true);
        TransferSender.ExportResult ex = p.sender.exportPackage(p.seed);
        p.receiver.intakePackage(ex.packageFile, ex.transferCode);
        Map<String, Object> notice = p.receiver.buildNotice(p.deviceId, 1);
        Map<String, Object> permit = p.sender.issuePermit(p.seed, notice);
        check("expiry: first permit commits",
                p.receiver.intakePermit(permit)
                        == TransferReceiver.PermitIntakeOutcome.COMMITTED);
        // Finalized reaches H before the judgement step.
        p.receiverFin.value = 400000L + 1000 + 100;
        p.receiver.confirmTrial(p.deviceId, 1);
        TransferState rState = TransferState.load(p.receiverRoot);
        Map<String, Object> rec = rState.receiver(p.deviceId, 1);
        Map<String, Object> trial = TransferState.child(rec, "trial");
        check("expiry: promotion cancelled back to R1",
                trial != null && "cancel_complete".equals(
                        TransferState.stringOr(trial, "stage", null))
                        && TransferState.child(rec, "closure") == null);
        check("expiry: latch recorded under the permit hash",
                rState.hasLatch(p.deviceId,
                        TransferCodec.fullValueHash(permit)));
        check("expiry: live placement restored (no journal)",
                !TransferStore.journalFile(p.receiverRoot).exists());
        check("expiry: staged journal returned to staging",
                TransferStore.stagingJournal(TransferStore.stagingRoot(
                        p.receiverRoot, p.deviceId, 1,
                        ex.packageSha256)).exists());
        // Second permit, fresh clocks → R2 under a new trial ID.
        // (Advance the wall clock: with identical inputs the
        // permit would be the identical full value — the one
        // that already ended.)
        p.clock.wall += 60;
        p.receiverFin.value = 400000L;
        Map<String, Object> permit2 = p.sender.issuePermit(p.seed, notice);
        check("expiry: second permit commits",
                p.receiver.intakePermit(permit2)
                        == TransferReceiver.PermitIntakeOutcome.COMMITTED);
        p.receiver.confirmTrial(p.deviceId, 1);
        rState = TransferState.load(p.receiverRoot);
        rec = rState.receiver(p.deviceId, 1);
        check("expiry: second trial confirms R2",
                TransferState.child(rec, "closure") != null);
        Map<String, Object> trial2 = TransferState.child(rec, "trial");
        check("expiry: new trial has a new attempt ID",
                trial2 != null && "1-1".equals(
                        TransferState.stringOr(trial2, "trial_id", null)));
    }

    /** Export → intake → notice → permit → confirm, stopping
     *  at R2 (no receipt/ack yet). */
    private static TransferSender.ExportResult testConfirmOnly(Pair p)
            throws Exception {
        TransferSender.ExportResult ex = p.sender.exportPackage(p.seed);
        p.receiver.intakePackage(ex.packageFile, ex.transferCode);
        Map<String, Object> notice = p.receiver.buildNotice(p.deviceId, 1);
        Map<String, Object> permit = p.sender.issuePermit(p.seed, notice);
        p.receiver.intakePermit(permit);
        p.receiver.confirmTrial(p.deviceId, 1);
        return ex;
    }

    private static void testCancelBeforePermit() throws Exception {
        Pair p = newPair(false);
        TransferSender.ExportResult ex = p.sender.exportPackage(p.seed);
        p.receiver.intakePackage(ex.packageFile, ex.transferCode);
        Map<String, Object> token = p.sender.cancelBeforePermit(p.seed);
        check("cancel: sender returns to S0 after token",
                TransferInit.writerGate(p.senderRoot, true).allowed);
        p.receiver.intakeCancelToken(token);
        TransferState rState = TransferState.load(p.receiverRoot);
        Map<String, Object> rec = rState.receiver(p.deviceId, 1);
        check("cancel: token closure recorded before discard",
                rec != null && "cancel_token".equals(TransferState.stringOr(
                        TransferState.child(rec, "closure"), "kind", null)));
        check("cancel: staging discarded after closure",
                !TransferStore.stagingRoot(p.receiverRoot, p.deviceId, 1,
                        ex.packageSha256).exists());
        check("cancel: token re-presentation is idempotent",
                !throwsAny(() -> p.receiver.intakeCancelToken(token)));
        check("cancel: after permit issuance, free cancel is refused",
                throwsAny(() -> {
                    Pair q = newPair(false);
                    TransferSender.ExportResult exq =
                            q.sender.exportPackage(q.seed);
                    q.receiver.intakePackage(
                            exq.packageFile, exq.transferCode);
                    Map<String, Object> nq = q.receiver.buildNotice(
                            q.deviceId, 1);
                    q.sender.issuePermit(q.seed, nq);
                    q.sender.cancelBeforePermit(q.seed);
                }));
    }

    private static void testAbandonFlows() throws Exception {
        // NONE form: no permit was ever taken in; the abandon
        // carries NO permit_sha256 key at all.
        Pair p = newPair(false);
        TransferSender.ExportResult ex = p.sender.exportPackage(p.seed);
        p.receiver.intakePackage(ex.packageFile, ex.transferCode);
        Map<String, Object> abandon = p.receiver.issueAbandon(p.deviceId, 1);
        check("abandon: NONE form omits permit_sha256 entirely",
                !abandon.containsKey("permit_sha256"));
        p.sender.onAbandon(abandon);
        check("abandon: sender returns to S0 (NONE form)",
                TransferState.load(p.senderRoot).sender(p.deviceId) == null
                        && TransferInit.writerGate(
                                p.senderRoot, true).allowed);
        // Permit form: abandon names the committed permit.
        Pair q = newPair(false);
        TransferSender.ExportResult exq = q.sender.exportPackage(q.seed);
        q.receiver.intakePackage(exq.packageFile, exq.transferCode);
        Map<String, Object> nq = q.receiver.buildNotice(q.deviceId, 1);
        Map<String, Object> pq = q.sender.issuePermit(q.seed, nq);
        q.receiver.intakePermit(pq);
        Map<String, Object> abandon2 = q.receiver.issueAbandon(
                q.deviceId, 1);
        check("abandon: permit form names the permit hash",
                TransferCodec.fullValueHash(pq).equals(
                        abandon2.get("permit_sha256")));
        q.sender.onAbandon(abandon2);
        check("abandon: sender returns to S0 (permit form)",
                TransferState.load(q.senderRoot).sender(q.deviceId) == null);
        TransferState qr = TransferState.load(q.receiverRoot);
        check("abandon: refusal + restoration saved in one record",
                "refusal".equals(TransferState.stringOr(
                        TransferState.child(
                                qr.receiver(q.deviceId, 1), "closure"),
                        "kind", null))
                        && "complete".equals(TransferState.stringOr(
                                qr.receiver(q.deviceId, 1),
                                "restoration", null)));
    }

    /** F3-H1 regression: the abandon COMPOSITION
     *  (TransferAbandon.issueReceiverAbandon) routes on the
     *  recorded state — (1) re-presentation of a recorded
     *  refusal, (2) cancel-then-issue for an adopted-plan
     *  trial, (3) NO cancel when issuance refuses with an
     *  inconsistency (classification (c): preserve
     *  everything). The previous app-layer composition
     *  branched on the core's exception types: HoldException
     *  (a sibling of the caught InconsistencyException) meant
     *  paths (1) and (2) always failed, and path (3) was the
     *  only one that ever cancelled. */
    private static void testAbandonComposition() throws Exception {
        // (1) Re-presentation: the second call returns the
        // recorded refusal's full value instead of refusing.
        Pair p = newPair(false);
        TransferSender.ExportResult ex = p.sender.exportPackage(p.seed);
        p.receiver.intakePackage(ex.packageFile, ex.transferCode);
        Map<String, Object> first = TransferAbandon
                .issueReceiverAbandon(p.receiver, p.receiverRoot,
                        p.deviceId, 1);
        Map<String, Object> second = TransferAbandon
                .issueReceiverAbandon(p.receiver, p.receiverRoot,
                        p.deviceId, 1);
        check("abandonflow: re-presentation returns the stored refusal",
                TransferCodec.fullValueHash(first).equals(
                        TransferCodec.fullValueHash(second)));
        p.sender.onAbandon(second);
        check("abandonflow: sender accepts the re-presented refusal",
                TransferState.load(p.senderRoot).sender(p.deviceId)
                        == null);
        // A cancel-token closure is NOT re-presented: the
        // core's Hold governs and nothing is cancelled around.
        Pair c = newPair(false);
        TransferSender.ExportResult exc = c.sender.exportPackage(c.seed);
        c.receiver.intakePackage(exc.packageFile, exc.transferCode);
        Map<String, Object> token = c.sender.cancelBeforePermit(c.seed);
        c.receiver.intakeCancelToken(token);
        check("abandonflow: cancel-token closure refuses with Hold",
                throwsType(() -> TransferAbandon.issueReceiverAbandon(
                                c.receiver, c.receiverRoot,
                                c.deviceId, 1),
                        TransferReceiver.HoldException.class));

        // (2) Adopted-plan trial: the composition cancels the
        // trial (step 9) and then issues. Fabricated exactly
        // like testPromotionInconsistencyStop, except the live
        // placement is NOT changed after adoption, so the
        // cancellation can complete.
        File senderRoot = tmpDir("flow-sender");
        File receiverRoot = tmpDir("flow-receiver");
        byte[] seed = testSeed();
        String deviceId = DepinCore.deviceIdFromPubkey(
                Ed25519.publickeyFromSeed(seed));
        writeJournal(senderRoot, seed, 6);
        TransferVault senderVault =
                new TransferVault(senderRoot, testWrapKey());
        TransferInit.initialise(senderRoot, senderVault,
                TransferInit.MODE_NEW, seed, RANDOM);
        TestClock clock = new TestClock();
        TestFinalized senderFin = new TestFinalized();
        TestFinalized receiverFin = new TestFinalized();
        TransferSender sender = new TransferSender(senderRoot,
                senderVault, RANDOM, "test-app", new TestFreeze(),
                senderFin, clock, 24L * 3600, 1000, 100);
        writeJournal(receiverRoot, seed, 3);
        TransferVault receiverVault =
                new TransferVault(receiverRoot, testWrapKey());
        TransferInit.initialise(receiverRoot, receiverVault,
                TransferInit.MODE_NEW, seed, RANDOM);
        TransferReceiver receiver = new TransferReceiver(receiverRoot,
                receiverVault, testWrapKey(), receiverFin, clock,
                new TestTimeSource());
        TransferSender.ExportResult ex2 = sender.exportPackage(seed);
        receiver.intakePackage(ex2.packageFile, ex2.transferCode);
        Map<String, Object> notice = receiver.buildNotice(deviceId, 1);
        Map<String, Object> permit = sender.issuePermit(seed, notice);
        receiver.intakePermit(permit);
        File stagingRoot = TransferStore.stagingRoot(receiverRoot,
                deviceId, 1, ex2.packageSha256);
        String stagedSha = Hex.encode(DepinCore.sha256(
                TransferIo.readAll(TransferStore.stagingJournal(
                        stagingRoot),
                        TransferPackage.MAX_PACKAGE_BYTES)));
        String liveSha = Hex.encode(DepinCore.sha256(
                TransferIo.readAll(TransferStore.journalFile(
                        receiverRoot),
                        TransferPackage.MAX_PACKAGE_BYTES)));
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("generation", 1L);
        plan.put("package_sha256", ex2.packageSha256);
        plan.put("backup_dir", "pre-promotion-backup/1/1-0");
        List<Object> files = new ArrayList<>();
        Map<String, Object> fe = new LinkedHashMap<>();
        fe.put("key", JournalStore.FILE_NAME);
        fe.put("action", "promote");
        fe.put("expect_sha256", stagedSha);
        files.add(fe);
        plan.put("files", files);
        Map<String, Object> oldM = new LinkedHashMap<>();
        Map<String, Object> oldFiles = new LinkedHashMap<>();
        oldFiles.put(JournalStore.FILE_NAME, liveSha);
        oldM.put("files", oldFiles);
        oldM.put("origin", "existing");
        oldM.put("sequence", 1L);
        plan.put("old_manifest", oldM);
        Map<String, Object> newM = new LinkedHashMap<>();
        Map<String, Object> newFiles = new LinkedHashMap<>();
        newFiles.put(JournalStore.FILE_NAME, stagedSha);
        newM.put("files", newFiles);
        newM.put("origin", "transfer:1");
        newM.put("sequence", 2L);
        plan.put("new_manifest", newM);
        String planHash = Hex.encode(DepinCore.sha256(
                TransferCodec.jcs(plan)));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("kind", "plan");
        entry.put("trial_id", "1-0");
        entry.put("plan_hash", planHash);
        entry.put("plan", plan);
        TransferIo.appendFramed(
                TransferStore.promotionJournal(receiverRoot), entry);
        TransferState fState = TransferState.load(receiverRoot);
        Map<String, Object> frec = fState.receiver(deviceId, 1);
        Map<String, Object> ftrial = new LinkedHashMap<>();
        ftrial.put("trial_id", "1-0");
        ftrial.put("generation", 1L);
        ftrial.put("package_sha256", ex2.packageSha256);
        ftrial.put("permit_hash", TransferCodec.fullValueHash(permit));
        ftrial.put("stage", "planned");
        ftrial.put("plan_adopted", true);
        ftrial.put("plan_hash", planHash);
        Map<String, Object> jud = new LinkedHashMap<>();
        jud.put("kind", "undecided");
        ftrial.put("judgement", jud);
        frec.put("trial", ftrial);
        fState.save();
        Map<String, Object> abandon = TransferAbandon
                .issueReceiverAbandon(receiver, receiverRoot,
                        deviceId, 1);
        check("abandonflow: planned trial is cancelled then refused",
                TransferCodec.fullValueHash(permit).equals(
                        abandon.get("permit_sha256")));
        TransferState rState = TransferState.load(receiverRoot);
        Map<String, Object> rec = rState.receiver(deviceId, 1);
        Map<String, Object> trial = TransferState.child(rec, "trial");
        check("abandonflow: refusal recorded after the cancel",
                "refusal".equals(TransferState.stringOr(
                        TransferState.child(rec, "closure"),
                        "kind", null))
                        && trial != null && "refused".equals(
                                TransferState.stringOr(
                                        trial, "stage", null)));
        JournalFile.Parsed live = JournalFile.parse(
                TransferStore.journalFile(receiverRoot), deviceId);
        check("abandonflow: live placement restored intact (3 cps)",
                live.count() == 3);
        sender.onAbandon(abandon);
        check("abandonflow: sender returns to S0 (planned path)",
                TransferState.load(senderRoot).sender(deviceId)
                        == null);

        // (3) Classification (c): a trial stands in an
        // issuable stage, but the staged seed record is gone,
        // so issuance refuses with an inconsistency. The
        // composition must surface it WITHOUT committing a
        // cancel (the old composition cancelled exactly here).
        // The trial record is written directly, in the shape
        // confirmTrial's first step leaves (intakePermit alone
        // does not create a trial yet).
        Pair q = newPair(false);
        TransferSender.ExportResult exq = q.sender.exportPackage(q.seed);
        q.receiver.intakePackage(exq.packageFile, exq.transferCode);
        Map<String, Object> nq = q.receiver.buildNotice(q.deviceId, 1);
        Map<String, Object> pq = q.sender.issuePermit(q.seed, nq);
        check("abandonflow: (c) setup permit commits",
                q.receiver.intakePermit(pq)
                        == TransferReceiver.PermitIntakeOutcome
                                .COMMITTED);
        TransferState qSetup = TransferState.load(q.receiverRoot);
        Map<String, Object> qRec0 = qSetup.receiver(q.deviceId, 1);
        Map<String, Object> qTrial0 = new LinkedHashMap<>();
        qTrial0.put("trial_id", "1-0");
        qTrial0.put("generation", 1L);
        qTrial0.put("package_sha256", exq.packageSha256);
        qTrial0.put("permit_hash",
                TransferCodec.fullValueHash(pq));
        qTrial0.put("stage", "started");
        Map<String, Object> qJud0 = new LinkedHashMap<>();
        qJud0.put("kind", "undecided");
        qTrial0.put("judgement", qJud0);
        qRec0.put("trial", qTrial0);
        qSetup.save();
        File sroot = TransferStore.stagingRoot(q.receiverRoot,
                q.deviceId, 1, exq.packageSha256);
        Files.delete(TransferStore.stagingRecordFile(sroot).toPath());
        check("abandonflow: (c) inconsistency surfaces unchanged",
                throwsType(() -> TransferAbandon.issueReceiverAbandon(
                                q.receiver, q.receiverRoot,
                                q.deviceId, 1),
                        TransferReceiver.InconsistencyException.class));
        TransferState qState = TransferState.load(q.receiverRoot);
        Map<String, Object> qRec = qState.receiver(q.deviceId, 1);
        Map<String, Object> qTrial = TransferState.child(qRec, "trial");
        Map<String, Object> qJud = qTrial == null ? null
                : TransferState.child(qTrial, "judgement");
        check("abandonflow: (c) no cancel was committed",
                qTrial != null && "started".equals(
                        TransferState.stringOr(qTrial, "stage", null))
                        && TransferState.child(qRec, "closure") == null
                        && (qJud == null || !"cancel".equals(
                                TransferState.stringOr(
                                        qJud, "kind", null))));
    }

    private static void testSyncBoundaryFailure() throws Exception {
        Pair p = newPair(false);
        TransferSender.ExportResult ex = p.sender.exportPackage(p.seed);
        final boolean[] failedOnce = {false};
        TransferIo.Syncer real = TransferIo.REAL_SYNCER;
        TransferIo.setSyncerForTest(f -> {
            String path = f.getAbsolutePath();
            if (!failedOnce[0] && path.contains("import-staging")
                    && f.getName().equals(JournalStore.FILE_NAME)) {
                failedOnce[0] = true;
                throw new java.io.IOException("injected fsync failure");
            }
            real.sync(f);
        });
        boolean threw = throwsAny(() -> p.receiver.intakePackage(
                ex.packageFile, ex.transferCode));
        TransferIo.setSyncerForTest(real);
        check("boundary: fsync failure aborts intake", threw);
        TransferState rState = TransferState.load(p.receiverRoot);
        check("boundary: failure leaves NO notified R1 record",
                rState.receiver(p.deviceId, 1) == null
                        || !TransferState.boolOr(
                                rState.receiver(p.deviceId, 1),
                                "notified_r1", false));
        File root = TransferStore.stagingRoot(
                p.receiverRoot, p.deviceId, 1, ex.packageSha256);
        check("boundary: unnotified staging exists and is discardable",
                root.exists());
        p.receiver.discardStaging(p.deviceId, 1);
        check("boundary: discard removes the unnotified staging",
                !root.exists());
        TransferReceiver.IntakeResult in = p.receiver.intakePackage(
                ex.packageFile, ex.transferCode);
        check("boundary: retry after failure reaches R1",
                in.checkpointCount == 6);
        check("boundary: notified R1 cannot be discarded directly",
                throwsType(() -> p.receiver.discardStaging(p.deviceId, 1),
                        TransferReceiver.HoldException.class));
    }

    private static void testRecovery() throws Exception {
        // Route (d): intake only → recovery finds R1 ready.
        Pair p = newPair(false);
        TransferSender.ExportResult ex = p.sender.exportPackage(p.seed);
        p.receiver.intakePackage(ex.packageFile, ex.transferCode);
        List<String> notes = p.newReceiver().recover();
        check("recovery: route (d) R1 ready",
                notes.stream().anyMatch(n -> n.contains("R1 ready")));
        // Route (b): after a full confirm, recovery verifies R2.
        Pair q = newPair(false);
        testConfirmOnly(q);
        List<String> notes2 = q.newReceiver().recover();
        check("recovery: route (b) R2 verified",
                notes2.stream().anyMatch(n -> n.contains("R2")));
        // Judgement materialisation: erase the closure by hand
        // (simulating a crash before the final save) — recovery
        // must re-materialise from the judgement field.
        TransferState qr = TransferState.load(q.receiverRoot);
        Map<String, Object> rec = qr.receiver(q.deviceId, 1);
        rec.remove("closure");
        rec.remove("r2");
        TransferState.child(rec, "trial").put("stage", "entry_saved");
        qr.doc().remove("active");
        qr.save();
        List<String> notes3 = q.newReceiver().recover();
        TransferState qr2 = TransferState.load(q.receiverRoot);
        check("recovery: (a) materialises R2 from the judgement field",
                TransferState.child(qr2.receiver(q.deviceId, 1),
                        "closure") != null
                        && notes3.stream().anyMatch(
                                n -> n.contains("materialised")));
        // Torn tail repair.
        File pj = TransferStore.promotionJournal(q.receiverRoot);
        Files.write(pj.toPath(), "torn-garbage".getBytes(
                StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
        List<String> notes4 = q.newReceiver().recover();
        check("recovery: torn tail truncated and logged",
                notes4.stream().anyMatch(
                        n -> n.contains("torn tail repaired")));
        // Classification (c): undecided judgement + a completion
        // candidate.
        Pair r = newPair(false);
        TransferSender.ExportResult exr = r.sender.exportPackage(r.seed);
        r.receiver.intakePackage(exr.packageFile, exr.transferCode);
        Map<String, Object> nr = r.receiver.buildNotice(r.deviceId, 1);
        Map<String, Object> pr = r.sender.issuePermit(r.seed, nr);
        r.receiver.intakePermit(pr);
        TransferState rr = TransferState.load(r.receiverRoot);
        Map<String, Object> rrec = rr.receiver(r.deviceId, 1);
        Map<String, Object> trial = new LinkedHashMap<>();
        trial.put("trial_id", "1-0");
        trial.put("generation", 1L);
        trial.put("package_sha256", exr.packageSha256);
        trial.put("permit_hash", TransferCodec.fullValueHash(pr));
        trial.put("stage", "started");
        Map<String, Object> jud = new LinkedHashMap<>();
        jud.put("kind", "undecided");
        trial.put("judgement", jud);
        rrec.put("trial", trial);
        rr.save();
        Map<String, Object> fakeComplete = new LinkedHashMap<>();
        fakeComplete.put("kind", "complete");
        fakeComplete.put("trial_id", "1-0");
        TransferIo.appendFramed(
                TransferStore.promotionJournal(r.receiverRoot),
                fakeComplete);
        check("recovery: (c) completion candidate under undecided "
                        + "judgement stops recovery",
                throwsType(() -> r.newReceiver().recover(),
                        TransferReceiver.InconsistencyException.class));
    }

    private static void testBudgetLedger() throws Exception {
        File root = tmpDir("budget");
        Reverification.TimeSource ts = new TestTimeSource();
        Reverification.BudgetLedger ledger =
                new Reverification.BudgetLedger(root, ts);
        Map<String, Object> res = ledger.reserve();
        check("budget: reserve succeeds with a fresh window",
                res != null);
        ledger.settle(res, 1500);
        check("budget: settled consumption is accounted",
                ledger.windowUsedMs() >= 1500);
        // An unsettled reservation is recovered as fully
        // consumed by a fresh instance over the same store.
        Reverification.BudgetLedger ledger2 =
                new Reverification.BudgetLedger(root, ts);
        Map<String, Object> res2 = ledger2.reserve();
        check("budget: second reserve succeeds", res2 != null);
        Reverification.BudgetLedger ledger3 =
                new Reverification.BudgetLedger(root, ts);
        ledger3.stamp();
        check("budget: unsettled reservation recovered as consumed",
                ledger3.windowUsedMs() >= 1500 + 2000);
    }

    private static void testReversePrefix() throws Exception {
        Pair p = newPair(false);
        JournalFile.Parsed full = JournalFile.parse(
                TransferStore.journalFile(p.senderRoot), p.deviceId);
        File dir = tmpDir("prefix");
        writeJournal(dir, p.seed, 3);
        JournalFile.Parsed prefix = JournalFile.parse(
                TransferStore.journalFile(dir), p.deviceId);
        check("reverse: strict prefix by chain accepted",
                TransferReceiver.isPrefixByChain(prefix, full));
        check("reverse: longer reserve rejected",
                !TransferReceiver.isPrefixByChain(full, prefix));
        File dir2 = tmpDir("prefix2");
        writeJournal(dir2, otherSeed(), 3);
        JournalFile.Parsed foreign = JournalFile.parse(
                TransferStore.journalFile(dir2),
                DepinCore.deviceIdFromPubkey(
                        Ed25519.publickeyFromSeed(otherSeed())));
        check("reverse: foreign journal rejected",
                !TransferReceiver.isPrefixByChain(foreign, full));
    }

    // ---------------- audit fix-round regressions ------------

    /** Append one more checkpoint to an existing journal. */
    private static void appendOneCheckpoint(File storeRoot, byte[] seed)
            throws Exception {
        byte[] pub = Ed25519.publickeyFromSeed(seed);
        String deviceId = DepinCore.deviceIdFromPubkey(pub);
        JournalStore j = JournalStore.forFile(
                new File(storeRoot, JournalStore.FILE_NAME), deviceId,
                Hex.encode(pub), signer(seed), verifier(pub));
        j.bindIdentity(deviceId, Hex.encode(pub));
        int n = j.checkpoints().size();
        DepinCore.Observation o = syntheticObservations(n + 1).get(n);
        j.append(DepinCore.makeCheckpoint(n, o, j.nextPrevState(),
                j.nextPrevCheckpointHash(), 50, pub));
    }

    /** The integrated app's step-3 soak shape: the receiver
     *  store's live placement belongs to a DIFFERENT (shadow)
     *  identity holding the active binding, while a transfer
     *  for another identity arrives (audit C-2). */
    private static final class ForeignPair {
        File senderRoot;
        File receiverRoot;
        byte[] seed = testSeed();
        byte[] shadowSeed = otherSeed();
        String deviceId;
        String shadowId;
        TransferVault senderVault;
        TransferVault receiverVault;
        TestClock clock = new TestClock();
        TestFinalized senderFin = new TestFinalized();
        TestFinalized receiverFin = new TestFinalized();
        TransferSender sender;
        TransferReceiver receiver;
        int freezeCalls = 0;

        TransferReceiver newReceiver() {
            TransferReceiver r = new TransferReceiver(receiverRoot,
                    receiverVault, testWrapKey(), receiverFin, clock,
                    new TestTimeSource());
            r.setForeignWriterFreeze(id -> freezeCalls++);
            return r;
        }
    }

    private static ForeignPair newForeignPair() throws Exception {
        ForeignPair p = new ForeignPair();
        p.deviceId = DepinCore.deviceIdFromPubkey(
                Ed25519.publickeyFromSeed(p.seed));
        p.shadowId = DepinCore.deviceIdFromPubkey(
                Ed25519.publickeyFromSeed(p.shadowSeed));
        p.senderRoot = tmpDir("fq-sender");
        writeJournal(p.senderRoot, p.seed, 6);
        p.senderVault = new TransferVault(p.senderRoot, testWrapKey());
        TransferInit.initialise(p.senderRoot, p.senderVault,
                TransferInit.MODE_NEW, p.seed, RANDOM);
        p.sender = new TransferSender(p.senderRoot, p.senderVault,
                RANDOM, "test-app", new TestFreeze(), p.senderFin,
                p.clock, 24L * 3600, 1000, 100);
        p.receiverRoot = tmpDir("fq-receiver");
        writeJournal(p.receiverRoot, p.shadowSeed, 4);
        writeLegacyArchive(p.receiverRoot, p.shadowSeed, 3);
        p.receiverVault = new TransferVault(p.receiverRoot, testWrapKey());
        TransferInit.initialise(p.receiverRoot, p.receiverVault,
                TransferInit.MODE_LEGACY, p.shadowSeed, RANDOM);
        p.receiver = p.newReceiver();
        return p;
    }

    private static Map<String, Object> planOf(File root) throws Exception {
        for (Map<String, Object> e : TransferIo.readFramed(
                TransferStore.promotionJournal(root)).entries) {
            if ("plan".equals(e.get("kind"))) {
                return TransferState.child(e, "plan");
            }
        }
        return null;
    }

    /** C-2, commit path: the plan's old placement is EMPTY,
     *  the shadow journal is quarantined byte-identical (not
     *  retired into the promotion backup), the foreign writer
     *  was frozen first, and R2 retains the quarantine. */
    private static void testForeignQuarantineCommit() throws Exception {
        ForeignPair p = newForeignPair();
        byte[] shadowJournal = Files.readAllBytes(
                TransferStore.journalFile(p.receiverRoot).toPath());
        TransferSender.ExportResult ex = p.sender.exportPackage(p.seed);
        TransferReceiver.IntakeResult in = p.receiver.intakePackage(
                ex.packageFile, ex.transferCode);
        check("quarantine: intake reaches R1 over a foreign placement",
                in.checkpointCount == 6);
        Map<String, Object> notice = p.receiver.buildNotice(p.deviceId, 1);
        Map<String, Object> permit = p.sender.issuePermit(p.seed, notice);
        check("quarantine: permit commits",
                p.receiver.intakePermit(permit)
                        == TransferReceiver.PermitIntakeOutcome.COMMITTED);
        p.receiver.confirmTrial(p.deviceId, 1);
        check("quarantine: foreign writer frozen before the moves",
                p.freezeCalls >= 1);
        TransferState rState = TransferState.load(p.receiverRoot);
        Map<String, Object> rec = rState.receiver(p.deviceId, 1);
        check("quarantine: R2 committed",
                TransferState.child(rec, "closure") != null);
        Map<String, Object> plan = planOf(p.receiverRoot);
        Map<String, Object> oldFiles = plan == null ? null
                : TransferState.child(
                        TransferState.child(plan, "old_manifest"),
                        "files");
        check("quarantine: plan old placement is EMPTY (F-21)",
                oldFiles != null && oldFiles.isEmpty());
        File qJournal = new File(TransferStore.shadowQuarantineDir(
                p.receiverRoot, p.shadowId), JournalStore.FILE_NAME);
        check("quarantine: shadow journal quarantined byte-identical",
                qJournal.isFile() && Arrays.equals(shadowJournal,
                        Files.readAllBytes(qJournal.toPath())));
        check("quarantine: shadow journal NOT in the promotion backup",
                !new File(p.receiverRoot,
                        "pre-promotion-backup/1/1-0/journal.json")
                        .exists());
        byte[] senderJournal = Files.readAllBytes(
                TransferStore.journalFile(p.senderRoot).toPath());
        byte[] liveJournal = Files.readAllBytes(
                TransferStore.journalFile(p.receiverRoot).toPath());
        check("quarantine: live journal is the transferred journal",
                Arrays.equals(senderJournal, liveJournal));
        Map<String, Object> fq = TransferState.child(
                rec, "foreign_quarantine");
        check("quarantine: entry retained at R2",
                fq != null && "retained".equals(TransferState.stringOr(
                        fq, "state", null)));
        check("quarantine: active binding is the received identity",
                p.deviceId.equals(TransferState.stringOr(
                        rState.active(), "device_id", null)));
    }

    /** The production shape of 2026-11-09 (progress file
     *  section 9): the receiving install already holds a live
     *  shadow identity AND an open S1 sender track for it (a
     *  rehearsal export that was never completed) when a
     *  foreign identity is received end to end. After the
     *  ack, the writer gate must open for the RECEIVED
     *  identity — the displaced identity's frozen sender
     *  track must not gate it — and the gate must have been
     *  frozen_s1 for the shadow while its S1 was the active
     *  binding (the state the apps' observer kept re-reading
     *  after the transfer completed). The core side of the
     *  internal.12 fix; the app-layer half is covered by the
     *  transfer-flow verify mains. */
    /** One-shot handover, full cycle (progress file section
     *  10): the sender exports in one-shot mode (the record is
     *  marked, the freeze is the ordinary export freeze), the
     *  receiver stages, completes the intake and confirms with
     *  NO notice/permit/receipt/ack — the closure kind is
     *  "committed_oneshot", the live journal is the
     *  transferred one, the writer gate opens for the received
     *  identity, no receipt can be built — and the sender then
     *  closes the handover by user confirmation alone: vault
     *  material erased, binding released, history S2 with the
     *  one-shot closure, and recovery over both stores runs
     *  clean afterwards. */
    private static void testOneshotCycle() throws Exception {
        Pair p = newPair(true);
        TransferSender.ExportResult ex =
                p.sender.exportPackageOneshot(p.seed);
        TransferState sState = TransferState.load(p.senderRoot);
        Map<String, Object> sRec = sState.sender(p.deviceId);
        check("oneshot: sender record marked oneshot",
                sRec != null && "oneshot".equals(
                        TransferState.stringOr(sRec, "mode", null)));
        check("oneshot: gate frozen while the file is out",
                !TransferInit.writerGate(p.senderRoot, true).allowed);
        TransferReceiver.IntakeResult in =
                p.receiver.intakePackage(ex.packageFile, ex.transferCode);
        p.receiver.completeIntake(in.deviceId, in.generation,
                in.packageSha256);
        p.receiver.confirmOneshot(p.deviceId, 1);
        TransferState rState = TransferState.load(p.receiverRoot);
        Map<String, Object> rec = rState.receiver(p.deviceId, 1);
        Map<String, Object> closure = TransferState.child(rec, "closure");
        check("oneshot: closure kind is committed_oneshot",
                closure != null && "committed_oneshot".equals(
                        TransferState.stringOr(closure, "kind", null)));
        check("oneshot: r2 present",
                rec.get("r2") != null);
        byte[] senderJournal = Files.readAllBytes(
                TransferStore.journalFile(p.senderRoot).toPath());
        byte[] receiverJournal = Files.readAllBytes(
                TransferStore.journalFile(p.receiverRoot).toPath());
        check("oneshot: live journal is the transferred journal",
                Arrays.equals(senderJournal, receiverJournal));
        TransferInit.Gate g =
                TransferInit.writerGate(p.receiverRoot, true);
        check("oneshot: gate opens for the received identity",
                g.allowed && p.deviceId.equals(g.deviceId));
        check("oneshot: no receipt can be built",
                throwsAny(() -> p.receiver.buildReceipt(p.deviceId, 1)));
        // E4: the receiver's record carries the confirmation
        // number, equal to the value derived from the code.
        String recordedCode =
                TransferState.stringOr(rec, "commit_code", null);
        String expectedCode = TransferCommitCode.codeFor(
                TransferCommitCode.deriveKey(ex.transferCode),
                in.manifestHash);
        check("oneshot-e4: receiver record carries the "
                        + "confirmation number",
                expectedCode != null && expectedCode.equals(recordedCode));
        String wrongCode = (expectedCode.startsWith("0") ? "1" : "0")
                + expectedCode.substring(1);
        check("oneshot-e4: a wrong number refuses the erasure",
                throwsAny(() -> p.sender.closeOneshotHandover(
                        p.deviceId, wrongCode)));
        check("oneshot-e4: seed still present after the refusal",
                p.senderVault.has(TransferVault.senderKey(1)));
        // Sender-side close with the number the receiver shows.
        p.sender.closeOneshotHandover(p.deviceId, expectedCode);
        sState = TransferState.load(p.senderRoot);
        check("oneshot: sender record closed",
                sState.sender(p.deviceId) == null);
        check("oneshot: sender vault material erased",
                !p.senderVault.has(TransferVault.senderKey(1)));
        check("oneshot: sender binding released",
                sState.active() == null);
        Map<String, Object> idSec = sState.identity(p.deviceId);
        boolean histOk = false;
        Object hist = idSec == null ? null : idSec.get("sender_history");
        if (hist instanceof List) {
            for (Object o : (List<?>) hist) {
                if (!(o instanceof Map)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> e = (Map<String, Object>) o;
                if ("S2".equals(e.get("state"))
                        && "oneshot_user_confirmed".equals(
                                TransferState.stringOr(e, "closure", null))
                        && "oneshot".equals(
                                TransferState.stringOr(e, "mode", null))) {
                    histOk = true;
                }
            }
        }
        check("oneshot: history carries the S2 one-shot closure",
                histOk);
        boolean recoverOk = true;
        try {
            p.receiver.recover();
            new TransferReceiver(p.senderRoot, p.senderVault,
                    testWrapKey(), p.senderFin, p.clock,
                    new TestTimeSource()).recover();
        } catch (Exception e) {
            recoverOk = false;
        }
        check("oneshot: recovery runs clean on both stores",
                recoverOk);
    }

    /** One-shot cancellation returns the sender to S0 (record
     *  to history, binding restored, vault material erased,
     *  gate open again), and the one-shot close/cancel REFUSE
     *  a strict-mode S1: the receipt gate of the strict
     *  protocol cannot be bypassed through the simple mode. */
    private static void testOneshotCancelAndStrictRefusal()
            throws Exception {
        Pair p = newPair(false);
        p.sender.exportPackageOneshot(p.seed);
        p.sender.cancelOneshotHandover(p.deviceId);
        TransferState sState = TransferState.load(p.senderRoot);
        check("oneshot-cancel: sender record closed",
                sState.sender(p.deviceId) == null);
        Map<String, Object> active = sState.active();
        check("oneshot-cancel: binding restored to the identity",
                active != null && p.deviceId.equals(
                        TransferState.stringOr(
                                active, "device_id", null)));
        TransferInit.Gate g =
                TransferInit.writerGate(p.senderRoot, true);
        check("oneshot-cancel: gate opens for the identity again",
                g.allowed && p.deviceId.equals(g.deviceId));
        check("oneshot-cancel: sender vault material erased",
                !p.senderVault.has(TransferVault.senderKey(1)));
        // Strict S1: both one-shot exits refuse.
        Pair q = newPair(false);
        q.sender.exportPackage(q.seed);
        check("oneshot: close refuses a strict S1",
                throwsAny(() -> q.sender.closeOneshotHandover(
                        q.deviceId, "12345678")));
        check("oneshot: cancel refuses a strict S1",
                throwsAny(() -> q.sender.cancelOneshotHandover(
                        q.deviceId)));
        check("oneshot: strict S1 untouched by the refusals",
                TransferState.load(q.senderRoot)
                        .sender(q.deviceId) != null);
    }

    /** E7 + E1: re-export from the frozen tip issues a new
     *  code for the same generation; the previous file stays
     *  valid on a fresh receiver and its confirmation number
     *  still closes the sender (its key was kept). A consumed
     *  export refuses re-import on the consuming receiver —
     *  the original AND the re-export alike. */
    private static void testOneshotReexportAndTombstone()
            throws Exception {
        Pair p = newPair(false);
        TransferSender.ExportResult ex1 =
                p.sender.exportPackageOneshot(p.seed);
        TransferReceiver.IntakeResult in1 =
                p.receiver.intakePackage(ex1.packageFile, ex1.transferCode);
        p.receiver.completeIntake(in1.deviceId, in1.generation,
                in1.packageSha256);
        p.receiver.confirmOneshot(p.deviceId, 1);
        String code1 = TransferCommitCode.codeFor(
                TransferCommitCode.deriveKey(ex1.transferCode),
                in1.manifestHash);
        check("oneshot-e1: re-import of a consumed export refuses",
                throwsAny(() -> p.receiver.intakePackage(
                        ex1.packageFile, ex1.transferCode)));
        TransferSender.ExportResult ex2 =
                p.sender.exportPackageOneshotAgain(p.seed);
        check("oneshot-e7: re-export issues a new code",
                !ex2.transferCode.equals(ex1.transferCode));
        check("oneshot-e7: re-export keeps the generation",
                ex2.generation == 1);
        check("oneshot-e1: the re-export also refuses on the "
                        + "consuming receiver",
                throwsAny(() -> p.receiver.intakePackage(
                        ex2.packageFile, ex2.transferCode)));
        Pair q = newPair(false);
        TransferReceiver.IntakeResult in2 =
                q.receiver.intakePackage(ex2.packageFile, ex2.transferCode);
        q.receiver.completeIntake(in2.deviceId, in2.generation,
                in2.packageSha256);
        q.receiver.confirmOneshot(p.deviceId, 1);
        Map<String, Object> rec2 =
                TransferState.load(q.receiverRoot).receiver(p.deviceId, 1);
        String code2 = TransferState.stringOr(rec2, "commit_code", null);
        check("oneshot-e7: the second intake's number differs",
                code2 != null && !code2.equals(code1));
        p.sender.closeOneshotHandover(p.deviceId, code1);
        check("oneshot-e7: sender closed with the first number",
                TransferState.load(p.senderRoot)
                        .sender(p.deviceId) == null);
        check("oneshot-e7: commit keys erased with the close",
                !p.senderVault.has("commit-keys:1"));
    }

    /** E8: a one-shot confirm never displaces a DIFFERENT live
     *  identity; the refusal leaves the live identity intact. */
    private static void testOneshotRefusesForeignLive()
            throws Exception {
        Pair p = newPair(false);
        File rRoot = tmpDir("foreign-receiver");
        byte[] seedB = otherSeed();
        writeJournal(rRoot, seedB, 3);
        TransferVault vB = new TransferVault(rRoot, testWrapKey());
        TransferInit.initialise(rRoot, vB,
                TransferInit.MODE_NEW, seedB, RANDOM);
        String deviceB = DepinCore.deviceIdFromPubkey(
                Ed25519.publickeyFromSeed(seedB));
        TransferReceiver foreign = new TransferReceiver(
                rRoot, vB, testWrapKey(), p.receiverFin, p.clock,
                new TestTimeSource());
        TransferSender.ExportResult ex =
                p.sender.exportPackageOneshot(p.seed);
        TransferReceiver.IntakeResult in =
                foreign.intakePackage(ex.packageFile, ex.transferCode);
        foreign.completeIntake(in.deviceId, in.generation,
                in.packageSha256);
        check("oneshot-e8: confirm refuses a different live identity",
                throwsAny(() -> foreign.confirmOneshot(p.deviceId, 1)));
        TransferInit.Gate g = TransferInit.writerGate(rRoot, true);
        check("oneshot-e8: the live identity is untouched",
                g.allowed && deviceB.equals(g.deviceId));
    }

    /** E5: the modes do not cross mid-flight — a generation
     *  with a committed permit refuses the one-shot confirm,
     *  and a record a one-shot confirm took over refuses the
     *  strict confirm. */
    private static void testOneshotCrossModeGuards() throws Exception {
        Pair p = newPair(false);
        TransferSender.ExportResult ex =
                p.sender.exportPackageOneshot(p.seed);
        TransferReceiver.IntakeResult in =
                p.receiver.intakePackage(ex.packageFile, ex.transferCode);
        p.receiver.completeIntake(in.deviceId, in.generation,
                in.packageSha256);
        Map<String, Object> notice = p.receiver.buildNotice(p.deviceId, 1);
        Map<String, Object> permit = p.sender.issuePermit(p.seed, notice);
        check("oneshot-e5: permit intake commits (strict in flight)",
                p.receiver.intakePermit(permit)
                        == TransferReceiver.PermitIntakeOutcome.COMMITTED);
        check("oneshot-e5: one-shot confirm refuses a committed permit",
                throwsAny(() -> p.receiver.confirmOneshot(p.deviceId, 1)));
        // The strict confirm completes the generation as usual.
        p.receiver.confirmTrial(p.deviceId, 1);
        Map<String, Object> rec = TransferState.load(p.receiverRoot)
                .receiver(p.deviceId, 1);
        check("oneshot-e5: strict confirm still completes",
                TransferState.child(rec, "closure") != null);
        // Reverse direction: a record marked one-shot refuses
        // the strict confirm.
        Pair q = newPair(false);
        TransferSender.ExportResult exq =
                q.sender.exportPackageOneshot(q.seed);
        TransferReceiver.IntakeResult inq =
                q.receiver.intakePackage(exq.packageFile, exq.transferCode);
        q.receiver.completeIntake(inq.deviceId, inq.generation,
                inq.packageSha256);
        TransferState qState = TransferState.load(q.receiverRoot);
        Map<String, Object> qRec = qState.receiver(q.deviceId, 1);
        qRec.put("mode", "oneshot");
        qState.save();
        check("oneshot-e5: strict confirm refuses a one-shot record",
                throwsAny(() -> q.receiver.confirmTrial(q.deviceId, 1)));
    }

    /** E6 anchors: after A sent the identity away and closed,
     *  a return package whose journal does not contain the
     *  frozen tip is refused at confirm; an extending return
     *  is accepted and lands as the live journal. */
    private static void testOneshotReturnAnchors() throws Exception {
        Pair p = newPair(false);
        TransferSender.ExportResult ex =
                p.sender.exportPackageOneshot(p.seed);
        TransferReceiver.IntakeResult in =
                p.receiver.intakePackage(ex.packageFile, ex.transferCode);
        p.receiver.completeIntake(in.deviceId, in.generation,
                in.packageSha256);
        p.receiver.confirmOneshot(p.deviceId, 1);
        p.sender.closeOneshotHandover(p.deviceId,
                TransferCommitCode.codeFor(
                        TransferCommitCode.deriveKey(ex.transferCode),
                        in.manifestHash));
        // A forked sibling history of the same identity tries
        // to come back.
        File forkRoot = tmpDir("fork-sender");
        writeJournalForked(forkRoot, p.seed, 6, 2);
        TransferVault forkVault =
                new TransferVault(forkRoot, testWrapKey());
        TransferInit.initialise(forkRoot, forkVault,
                TransferInit.MODE_NEW, p.seed, RANDOM);
        TransferSender forkSender = new TransferSender(
                forkRoot, forkVault, RANDOM, "test-app",
                new TestFreeze(), p.senderFin, p.clock,
                24L * 3600, 1000, 100);
        TransferSender.ExportResult forkEx =
                forkSender.exportPackageOneshot(p.seed);
        TransferReceiver backIn = new TransferReceiver(
                p.senderRoot, p.senderVault, testWrapKey(),
                p.senderFin, p.clock, new TestTimeSource());
        TransferReceiver.IntakeResult forkIn =
                backIn.intakePackage(forkEx.packageFile, forkEx.transferCode);
        backIn.completeIntake(forkIn.deviceId, forkIn.generation,
                forkIn.packageSha256);
        check("oneshot-e6: a forked return is refused at confirm",
                throwsAny(() -> backIn.confirmOneshot(p.deviceId, 1)));
        check("oneshot-e6: refusal leaves no closure",
                TransferState.child(TransferState.load(p.senderRoot)
                        .receiver(p.deviceId, 1), "closure") == null);
        // The legitimate holder extends the journal (two more
        // checkpoints appended to the live journal, as
        // recording would) and sends it back: accepted.
        byte[] pub = Ed25519.publickeyFromSeed(p.seed);
        JournalStore liveJ = JournalStore.forFile(
                TransferStore.journalFile(p.receiverRoot), p.deviceId,
                Hex.encode(pub), signer(p.seed), verifier(pub));
        liveJ.bindIdentity(p.deviceId, Hex.encode(pub));
        List<DepinCore.Observation> obs8 = syntheticObservations(8);
        for (int i = 6; i < 8; i++) {
            liveJ.append(DepinCore.makeCheckpoint(i, obs8.get(i),
                    liveJ.nextPrevState(),
                    liveJ.nextPrevCheckpointHash(), 50, pub));
        }
        TransferSender backSender = new TransferSender(
                p.receiverRoot, p.receiverVault, RANDOM, "test-app",
                new TestFreeze(), p.receiverFin, p.clock,
                24L * 3600, 1000, 100);
        TransferSender.ExportResult backEx =
                backSender.exportPackageOneshot(p.seed);
        check("oneshot-e6: the return export is generation 2",
                backEx.generation == 2);
        TransferReceiver.IntakeResult backIn2 =
                backIn.intakePackage(backEx.packageFile, backEx.transferCode);
        backIn.completeIntake(backIn2.deviceId, backIn2.generation,
                backIn2.packageSha256);
        backIn.confirmOneshot(p.deviceId, 2);
        JournalFile.Parsed live = JournalFile.parse(
                TransferStore.journalFile(p.senderRoot), p.deviceId);
        check("oneshot-e6: the extending return lands live",
                live.count() == 8);
    }

    /** Legacy fallback (E4 migration): a handover exported
     *  before confirmation numbers existed stored no key;
     *  its close falls back to the explicit confirmation
     *  alone (the key cannot be recomputed — the code was
     *  never stored). Simulated by reproducing the pre-E4
     *  on-disk shape exactly: no stored key AND no
     *  commit_code_required marker on the record. The
     *  marker — not the vault's contents — is what decides
     *  the fallback (audit F2): an E4-era record whose key
     *  was lost must fail closed, never fall back. */
    private static void testOneshotLegacyCloseFallback()
            throws Exception {
        Pair p = newPair(false);
        p.sender.exportPackageOneshot(p.seed);
        p.senderVault.delete("commit-keys:1");
        TransferState st = TransferState.load(p.senderRoot);
        st.sender(p.deviceId).remove("commit_code_required");
        st.save();
        p.sender.closeOneshotHandover(p.deviceId, null);
        check("oneshot-e4-legacy: pre-E4 handover still closes",
                TransferState.load(p.senderRoot)
                        .sender(p.deviceId) == null);
        check("oneshot-e4-legacy: seed material erased",
                !p.senderVault.has(TransferVault.senderKey(1)));
    }

    /** Audit F2, the crash window as a regression: with the
     *  mark, the marker and the key persisted inside the
     *  export's own save, an E4 record whose vault key is
     *  deleted afterwards (the state the old post-export
     *  saves could leave behind when the process died
     *  between them) must NOT fall back — the close refuses,
     *  with or without a number, and the seed survives. The
     *  same export also proves the positive shape: mode,
     *  marker and a stored (key, manifest hash) pair are all
     *  present the moment the export returns. */
    private static void testOneshotF2NoFallbackWithMarker()
            throws Exception {
        Pair p = newPair(false);
        p.sender.exportPackageOneshot(p.seed);
        TransferState st = TransferState.load(p.senderRoot);
        Map<String, Object> sr = st.sender(p.deviceId);
        check("f2: record carries the oneshot mode at export return",
                "oneshot".equals(TransferState.stringOr(
                        sr, "mode", null)));
        check("f2: record carries the number-required marker",
                Boolean.TRUE.equals(sr.get("commit_code_required")));
        check("f2: a commit key is stored at export return",
                p.senderVault.has("commit-keys:1"));
        p.senderVault.delete("commit-keys:1");
        boolean refused = false;
        try {
            p.sender.closeOneshotHandover(p.deviceId, null);
        } catch (IllegalStateException e) {
            refused = true;
        }
        check("f2: keyless close of a marked record refuses",
                refused);
        check("f2: seed survives the refused close",
                p.senderVault.has(TransferVault.senderKey(1))
                        && TransferState.load(p.senderRoot)
                                .sender(p.deviceId) != null);
    }

    private static void testGateAfterProductionShapedReceive()
            throws Exception {
        ForeignPair p = newForeignPair();
        // The shadow acts as sender for itself first: an S1
        // opens over the receiver store and is never completed.
        TransferSender shadowSender = new TransferSender(
                p.receiverRoot, p.receiverVault, RANDOM, "test-app",
                new TestFreeze(), p.receiverFin, p.clock,
                24L * 3600, 1000, 100);
        shadowSender.exportPackage(p.shadowSeed);
        TransferState midState = TransferState.load(p.receiverRoot);
        check("prodshape: shadow S1 open over the receiver store",
                midState.sender(p.shadowId) != null
                        && "S1".equals(TransferState.stringOr(
                                midState.sender(p.shadowId),
                                "state", null)));
        check("prodshape: gate frozen_s1 while the shadow is active",
                "frozen_s1".equals(TransferInit.writerGate(
                        p.receiverRoot, true).reason));
        // The foreign identity is received end to end.
        TransferSender.ExportResult ex = p.sender.exportPackage(p.seed);
        TransferReceiver receiver = p.newReceiver();
        receiver.intakePackage(ex.packageFile, ex.transferCode);
        Map<String, Object> notice = receiver.buildNotice(p.deviceId, 1);
        Map<String, Object> permit = p.sender.issuePermit(p.seed, notice);
        check("prodshape: permit commits",
                receiver.intakePermit(permit)
                        == TransferReceiver.PermitIntakeOutcome.COMMITTED);
        receiver.confirmTrial(p.deviceId, 1);
        Map<String, Object> receipt = receiver.buildReceipt(p.deviceId, 1);
        Map<String, Object> ack = p.sender.onReceipt(p.seed, receipt);
        receiver.intakeAck(ack);
        TransferState doneState = TransferState.load(p.receiverRoot);
        check("prodshape: shadow S1 still open after the receive",
                doneState.sender(p.shadowId) != null);
        TransferInit.Gate g = TransferInit.writerGate(
                p.receiverRoot, true);
        check("prodshape: gate opens for the received identity",
                g.allowed && p.deviceId.equals(g.deviceId));
        check("prodshape: gate still needs the received identity's ON",
                !TransferInit.writerGate(p.receiverRoot, false).allowed);
    }

    /** C-2, cancel path: an expired permit cancels the trial
     *  and the quarantined shadow placement returns to the
     *  live placement byte-identical. */
    private static void testForeignQuarantineCancel() throws Exception {
        ForeignPair p = newForeignPair();
        byte[] shadowJournal = Files.readAllBytes(
                TransferStore.journalFile(p.receiverRoot).toPath());
        byte[] shadowArchive = Files.readAllBytes(TransferStore
                .archiveFile(p.receiverRoot,
                        JournalStore.ARCHIVE_V04_FILE_NAME).toPath());
        TransferSender.ExportResult ex = p.sender.exportPackage(p.seed);
        p.receiver.intakePackage(ex.packageFile, ex.transferCode);
        Map<String, Object> notice = p.receiver.buildNotice(p.deviceId, 1);
        Map<String, Object> permit = p.sender.issuePermit(p.seed, notice);
        check("quarantine-cancel: permit commits",
                p.receiver.intakePermit(permit)
                        == TransferReceiver.PermitIntakeOutcome.COMMITTED);
        p.receiverFin.value = 400000L + 1000 + 100; // permit expired
        p.receiver.confirmTrial(p.deviceId, 1);
        TransferState rState = TransferState.load(p.receiverRoot);
        Map<String, Object> rec = rState.receiver(p.deviceId, 1);
        Map<String, Object> trial = TransferState.child(rec, "trial");
        check("quarantine-cancel: trial cancelled, no closure",
                trial != null && "cancel_complete".equals(
                        TransferState.stringOr(trial, "stage", null))
                        && TransferState.child(rec, "closure") == null);
        check("quarantine-cancel: shadow journal restored intact",
                Arrays.equals(shadowJournal, Files.readAllBytes(
                        TransferStore.journalFile(p.receiverRoot)
                                .toPath())));
        check("quarantine-cancel: shadow archive restored intact",
                Arrays.equals(shadowArchive, Files.readAllBytes(
                        TransferStore.archiveFile(p.receiverRoot,
                                JournalStore.ARCHIVE_V04_FILE_NAME)
                                .toPath())));
        Map<String, Object> fq = TransferState.child(
                rec, "foreign_quarantine");
        check("quarantine-cancel: entry marked restored",
                fq != null && "restored".equals(TransferState.stringOr(
                        fq, "state", null)));
        TransferStore.LiveManifest lm =
                TransferStore.loadLiveManifest(p.receiverRoot);
        check("quarantine-cancel: live manifest describes the shadow",
                lm != null && lm.files.containsKey(
                        JournalStore.FILE_NAME));
    }

    /** C-2, promotion strictness: when the live placement
     *  changes after the plan is adopted, executePromotion
     *  stops with an inconsistency instead of silently
     *  overwriting the changed file without a backup. The
     *  adopted-plan state is fabricated the way the recovery
     *  corpus fabricates crash states. */
    private static void testPromotionInconsistencyStop()
            throws Exception {
        // Receiver holds identity A's OWN earlier journal (3
        // checkpoints); the sender holds the same identity's
        // longer journal (6).
        File senderRoot = tmpDir("inc-sender");
        File receiverRoot = tmpDir("inc-receiver");
        byte[] seed = testSeed();
        String deviceId = DepinCore.deviceIdFromPubkey(
                Ed25519.publickeyFromSeed(seed));
        writeJournal(senderRoot, seed, 6);
        TransferVault senderVault =
                new TransferVault(senderRoot, testWrapKey());
        TransferInit.initialise(senderRoot, senderVault,
                TransferInit.MODE_NEW, seed, RANDOM);
        TestClock clock = new TestClock();
        TestFinalized senderFin = new TestFinalized();
        TestFinalized receiverFin = new TestFinalized();
        TransferSender sender = new TransferSender(senderRoot,
                senderVault, RANDOM, "test-app", new TestFreeze(),
                senderFin, clock, 24L * 3600, 1000, 100);
        writeJournal(receiverRoot, seed, 3);
        TransferVault receiverVault =
                new TransferVault(receiverRoot, testWrapKey());
        TransferInit.initialise(receiverRoot, receiverVault,
                TransferInit.MODE_NEW, seed, RANDOM);
        TransferReceiver receiver = new TransferReceiver(receiverRoot,
                receiverVault, testWrapKey(), receiverFin, clock,
                new TestTimeSource());
        TransferSender.ExportResult ex = sender.exportPackage(seed);
        receiver.intakePackage(ex.packageFile, ex.transferCode);
        Map<String, Object> notice = receiver.buildNotice(deviceId, 1);
        Map<String, Object> permit = sender.issuePermit(seed, notice);
        receiver.intakePermit(permit);
        // Fabricate the adopted plan over the CURRENT live
        // placement (3 checkpoints), as buildPlan would.
        File stagingRoot = TransferStore.stagingRoot(receiverRoot,
                deviceId, 1, ex.packageSha256);
        String stagedSha = Hex.encode(DepinCore.sha256(
                TransferIo.readAll(TransferStore.stagingJournal(
                        stagingRoot),
                        TransferPackage.MAX_PACKAGE_BYTES)));
        String liveSha = Hex.encode(DepinCore.sha256(
                TransferIo.readAll(TransferStore.journalFile(
                        receiverRoot),
                        TransferPackage.MAX_PACKAGE_BYTES)));
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("generation", 1L);
        plan.put("package_sha256", ex.packageSha256);
        plan.put("backup_dir", "pre-promotion-backup/1/1-0");
        List<Object> files = new ArrayList<>();
        Map<String, Object> fe = new LinkedHashMap<>();
        fe.put("key", JournalStore.FILE_NAME);
        fe.put("action", "promote");
        fe.put("expect_sha256", stagedSha);
        files.add(fe);
        plan.put("files", files);
        Map<String, Object> oldM = new LinkedHashMap<>();
        Map<String, Object> oldFiles = new LinkedHashMap<>();
        oldFiles.put(JournalStore.FILE_NAME, liveSha);
        oldM.put("files", oldFiles);
        oldM.put("origin", "existing");
        oldM.put("sequence", 1L);
        plan.put("old_manifest", oldM);
        Map<String, Object> newM = new LinkedHashMap<>();
        Map<String, Object> newFiles = new LinkedHashMap<>();
        newFiles.put(JournalStore.FILE_NAME, stagedSha);
        newM.put("files", newFiles);
        newM.put("origin", "transfer:1");
        newM.put("sequence", 2L);
        plan.put("new_manifest", newM);
        String planHash = Hex.encode(DepinCore.sha256(
                TransferCodec.jcs(plan)));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("kind", "plan");
        entry.put("trial_id", "1-0");
        entry.put("plan_hash", planHash);
        entry.put("plan", plan);
        TransferIo.appendFramed(
                TransferStore.promotionJournal(receiverRoot), entry);
        TransferState rState = TransferState.load(receiverRoot);
        Map<String, Object> rec = rState.receiver(deviceId, 1);
        Map<String, Object> trial = new LinkedHashMap<>();
        trial.put("trial_id", "1-0");
        trial.put("generation", 1L);
        trial.put("package_sha256", ex.packageSha256);
        trial.put("permit_hash", TransferCodec.fullValueHash(permit));
        trial.put("stage", "planned");
        trial.put("plan_adopted", true);
        trial.put("plan_hash", planHash);
        Map<String, Object> jud = new LinkedHashMap<>();
        jud.put("kind", "undecided");
        trial.put("judgement", jud);
        rec.put("trial", trial);
        rState.save();
        // The live placement changes AFTER adoption.
        appendOneCheckpoint(receiverRoot, seed);
        check("promotion: changed live placement stops recovery "
                        + "with an inconsistency",
                throwsType(() -> receiver.recover(),
                        TransferReceiver.InconsistencyException.class));
        JournalFile.Parsed live = JournalFile.parse(
                TransferStore.journalFile(receiverRoot), deviceId);
        check("promotion: changed journal left untouched (4 cps)",
                live.count() == 4);
        check("promotion: no backup was fabricated for it",
                !new File(receiverRoot,
                        "pre-promotion-backup/1/1-0/journal.json")
                        .exists());
    }

    /** H-4: a failed intake leaves no plaintext expansion
     *  debris, and recovery sweeps debris even from staging
     *  roots that have no staging record. */
    private static void testExpansionDebris() throws Exception {
        Pair p = newPair(false);
        TransferSender.ExportResult ex = p.sender.exportPackage(p.seed);
        String content = new String(Files.readAllBytes(
                ex.packageFile.toPath()), StandardCharsets.UTF_8);
        int idx = content.indexOf("\"payload\":\"") + 11;
        char orig = content.charAt(idx + 30);
        char repl = (orig == 'A') ? 'B' : 'A';
        String tampered = content.substring(0, idx + 30) + repl
                + content.substring(idx + 31);
        File tamperedFile = new File(p.receiverRoot, "tampered.json");
        Files.write(tamperedFile.toPath(),
                tampered.getBytes(StandardCharsets.UTF_8));
        check("debris: tampered package rejected",
                throwsAny(() -> p.receiver.intakePackage(
                        tamperedFile, ex.transferCode)));
        String tamperedSha = TransferPackage.parseEnvelope(
                TransferIo.readAll(tamperedFile,
                        TransferPackage.MAX_PACKAGE_BYTES))
                .packageSha256();
        File root = TransferStore.stagingRoot(p.receiverRoot,
                p.deviceId, 1, tamperedSha);
        check("debris: no plaintext debris after the failed intake",
                !new File(root, ".plaintext.tmp").exists()
                        && !new File(root, ".seed.tmp").exists()
                        && !new File(root, ".secret.tmp").exists());
        File fakeRoot = TransferStore.stagingRoot(p.receiverRoot,
                "qdp1-000000000000000000000000", 9, "00");
        fakeRoot.mkdirs();
        for (String name : new String[]{
                ".plaintext.tmp", ".seed.tmp", ".secret.tmp"}) {
            Files.write(new File(fakeRoot, name).toPath(),
                    "debris".getBytes(StandardCharsets.UTF_8));
        }
        List<String> notes = p.newReceiver().recover();
        check("debris: recovery sweeps record-less staging roots",
                !new File(fakeRoot, ".seed.tmp").exists()
                        && !new File(fakeRoot, ".secret.tmp").exists()
                        && !new File(fakeRoot, ".plaintext.tmp").exists());
        check("debris: the sweep is noted",
                notes.stream().anyMatch(n -> n.contains("debris")));
    }

    /** Audit F3: the confirmation key is stored as a
     *  (key, manifest hash) PAIR. With an ADVANCING clock —
     *  the fixed TestClock of the older tests made the two
     *  manifests identical, which is exactly why this
     *  escaped — a re-export rebuilds the manifest with new
     *  timestamps, and BOTH files' numbers must still close
     *  the sender afterwards. */
    private static void testOneshotF3AdvancingClock()
            throws Exception {
        // (a) the FIRST file's number, after a later re-export.
        Pair p = newPair(false);
        TransferSender.ExportResult ex1 =
                p.sender.exportPackageOneshot(p.seed);
        TransferReceiver.IntakeResult in1 =
                p.receiver.intakePackage(ex1.packageFile, ex1.transferCode);
        p.receiver.completeIntake(in1.deviceId, in1.generation,
                in1.packageSha256);
        p.receiver.confirmOneshot(p.deviceId, 1);
        String code1 = TransferCommitCode.codeFor(
                TransferCommitCode.deriveKey(ex1.transferCode),
                in1.manifestHash);
        p.clock.wall += 3600;
        TransferSender.ExportResult ex2 =
                p.sender.exportPackageOneshotAgain(p.seed);
        Map<String, Object> sr = TransferState.load(p.senderRoot)
                .sender(p.deviceId);
        String hash2 = TransferCommitCode.manifestHash(
                TransferState.child(sr, "manifest"));
        check("f3: the advanced clock really changed the manifest",
                !hash2.equals(in1.manifestHash));
        p.sender.closeOneshotHandover(p.deviceId, code1);
        check("f3: the first file's number still closes after "
                        + "the re-export",
                TransferState.load(p.senderRoot)
                        .sender(p.deviceId) == null);
        // (b) the SECOND file's number, on a fresh pair.
        Pair q = newPair(false);
        TransferSender.ExportResult qEx1 =
                q.sender.exportPackageOneshot(q.seed);
        q.clock.wall += 7200;
        TransferSender.ExportResult qEx2 =
                q.sender.exportPackageOneshotAgain(q.seed);
        Pair r = newPair(false);
        TransferReceiver.IntakeResult in2 =
                r.receiver.intakePackage(qEx2.packageFile,
                        qEx2.transferCode);
        r.receiver.completeIntake(in2.deviceId, in2.generation,
                in2.packageSha256);
        r.receiver.confirmOneshot(q.deviceId, 1);
        String code2 = TransferState.stringOr(
                TransferState.load(r.receiverRoot)
                        .receiver(q.deviceId, 1),
                "commit_code", null);
        check("f3: the second intake produced a number",
                code2 != null);
        q.sender.closeOneshotHandover(q.deviceId, code2);
        check("f3: the second file's number closes the sender",
                TransferState.load(q.senderRoot)
                        .sender(q.deviceId) == null);
    }

    /** Audit F1: a store refuses its OWN export — while the
     *  S1 is still open (open-record side of the tombstone)
     *  and after the handover closed (sender_history side).
     *  Before the fix the tombstone consulted only the
     *  receiver track, so the sender store took its own file
     *  in and held the identity twice. */
    private static void testF1SelfImportRefusal() throws Exception {
        Pair p = newPair(false);
        TransferSender.ExportResult ex =
                p.sender.exportPackageOneshot(p.seed);
        TransferReceiver self = new TransferReceiver(
                p.senderRoot, p.senderVault, testWrapKey(),
                p.senderFin, p.clock, new TestTimeSource());
        check("f1: self-intake refuses while the S1 is open",
                throwsAny(() -> self.intakePackage(
                        ex.packageFile, ex.transferCode)));
        // The real handover completes on the other store.
        TransferReceiver.IntakeResult in =
                p.receiver.intakePackage(ex.packageFile, ex.transferCode);
        p.receiver.completeIntake(in.deviceId, in.generation,
                in.packageSha256);
        p.receiver.confirmOneshot(p.deviceId, 1);
        String code = TransferCommitCode.codeFor(
                TransferCommitCode.deriveKey(ex.transferCode),
                in.manifestHash);
        p.sender.closeOneshotHandover(p.deviceId, code);
        check("f1: self-intake refuses after the S2 closure too",
                throwsAny(() -> self.intakePackage(
                        ex.packageFile, ex.transferCode)));
        // Strict mode: same refusal via the strict history.
        Pair q = newPair(false);
        TransferSender.ExportResult qEx =
                q.sender.exportPackage(q.seed);
        TransferReceiver.IntakeResult qIn =
                q.receiver.intakePackage(qEx.packageFile,
                        qEx.transferCode);
        q.receiver.completeIntake(qIn.deviceId, qIn.generation,
                qIn.packageSha256);
        Map<String, Object> notice = q.receiver.buildNotice(q.deviceId, 1);
        Map<String, Object> permit = q.sender.issuePermit(q.seed, notice);
        q.receiver.intakePermit(permit);
        q.receiver.confirmTrial(q.deviceId, 1);
        Map<String, Object> receipt = q.receiver.buildReceipt(q.deviceId, 1);
        q.sender.onReceipt(q.seed, receipt);
        TransferReceiver qSelf = new TransferReceiver(
                q.senderRoot, q.senderVault, testWrapKey(),
                q.senderFin, q.clock, new TestTimeSource());
        check("f1: strict self-intake refuses after the receipt "
                        + "closure",
                throwsAny(() -> qSelf.intakePackage(
                        qEx.packageFile, qEx.transferCode)));
    }

    /** Audit F5 (receiver recovery): a committed generation
     *  whose vault seed copy is gone is an inconsistency —
     *  UNLESS the identity was later sent onward from this
     *  store (an S2 at a higher generation), which explains
     *  the absence. */
    private static void testF5RecoveryTolerance() throws Exception {
        Pair p = newPair(false);
        TransferSender.ExportResult ex =
                p.sender.exportPackageOneshot(p.seed);
        TransferReceiver.IntakeResult in =
                p.receiver.intakePackage(ex.packageFile, ex.transferCode);
        p.receiver.completeIntake(in.deviceId, in.generation,
                in.packageSha256);
        p.receiver.confirmOneshot(p.deviceId, 1);
        // The receiving store sends the identity onward.
        TransferSender onward = new TransferSender(
                p.receiverRoot, p.receiverVault, RANDOM, "test-app",
                new TestFreeze(), p.receiverFin, p.clock,
                24L * 3600, 1000, 100);
        TransferSender.ExportResult ex2 =
                onward.exportPackageOneshot(p.seed);
        Pair third = newPair(false);
        TransferReceiver.IntakeResult in2 =
                third.receiver.intakePackage(ex2.packageFile,
                        ex2.transferCode);
        third.receiver.completeIntake(in2.deviceId, in2.generation,
                in2.packageSha256);
        third.receiver.confirmOneshot(p.deviceId, 2);
        String code2 = TransferCommitCode.codeFor(
                TransferCommitCode.deriveKey(ex2.transferCode),
                in2.manifestHash);
        onward.closeOneshotHandover(p.deviceId, code2);
        // The F5 vault state: the received copy's key is gone.
        p.receiverVault.delete(TransferVault.receiverKey(1));
        List<String> notes = p.newReceiver().recover();
        check("f5: recovery tolerates the explained absence",
                notes.stream().anyMatch(
                        n -> n.contains("sent onward")));
        // Negative control: no onward send — still an
        // inconsistency, exactly as before.
        Pair q = newPair(false);
        TransferSender.ExportResult qEx =
                q.sender.exportPackageOneshot(q.seed);
        TransferReceiver.IntakeResult qIn =
                q.receiver.intakePackage(qEx.packageFile,
                        qEx.transferCode);
        q.receiver.completeIntake(qIn.deviceId, qIn.generation,
                qIn.packageSha256);
        q.receiver.confirmOneshot(q.deviceId, 1);
        q.receiverVault.delete(TransferVault.receiverKey(1));
        check("f5: an unexplained absence still stops recovery",
                throwsType(() -> q.newReceiver().recover(),
                        TransferReceiver.InconsistencyException.class));
    }

    /** Audit M3: a staging with no confirmation key (created
     *  before E4) cannot be confirmed numberlessly — the
     *  confirm refuses with a hold BEFORE the trial starts —
     *  and re-presenting the file with its transfer code
     *  re-derives the key (wrapped at rest), after which the
     *  confirm proceeds and commits the number. */
    private static void testM3MissingCommitKey() throws Exception {
        Pair p = newPair(false);
        TransferSender.ExportResult ex =
                p.sender.exportPackageOneshot(p.seed);
        TransferReceiver.IntakeResult in =
                p.receiver.stagePackage(ex.packageFile, ex.transferCode);
        p.receiver.completeIntake(in.deviceId, in.generation,
                in.packageSha256);
        File root = TransferStore.stagingRoot(p.receiverRoot,
                p.deviceId, 1, in.packageSha256);
        TransferStore.StagingRecord sr =
                TransferStore.loadStaging(root);
        sr.commitKeyB64 = null;
        sr.commitKeyWrappedB64 = null;
        TransferStore.saveStaging(root, sr);
        check("m3: the keyless confirm refuses with a hold",
                throwsType(() -> p.receiver.confirmOneshot(p.deviceId, 1),
                        TransferReceiver.HoldException.class));
        check("m3: no trial was started by the refusal",
                TransferState.child(TransferState.load(p.receiverRoot)
                        .receiver(p.deviceId, 1), "trial") == null);
        // The rescue: re-present the same file with its code.
        p.receiver.stagePackage(ex.packageFile, ex.transferCode);
        TransferStore.StagingRecord sr2 =
                TransferStore.loadStaging(root);
        check("m3: re-presentation restores the key",
                sr2.hasCommitKeyMaterial());
        String raw = new String(Files.readAllBytes(new File(root,
                "staging-record.json").toPath()),
                StandardCharsets.UTF_8);
        check("m3: the key rests wrapped, never plaintext",
                raw.contains("\"commit_key_wrapped\"")
                        && !raw.contains("\"commit_key\":"));
        p.receiver.confirmOneshot(p.deviceId, 1);
        Map<String, Object> rec = TransferState.load(p.receiverRoot)
                .receiver(p.deviceId, 1);
        check("m3: the rescued confirm commits the number",
                TransferState.stringOr(rec, "commit_code", null)
                        != null);
    }

    /** Audit M4: promoting over a SAME-identity live
     *  placement freezes its writer through the hook before
     *  the plan snapshot — the foreign-only quarantine left
     *  this case unfrozen. */
    private static void testM4SameIdentityFreeze() throws Exception {
        Pair p = newPair(false);
        TransferSender.ExportResult ex1 =
                p.sender.exportPackageOneshot(p.seed);
        TransferReceiver.IntakeResult in1 =
                p.receiver.intakePackage(ex1.packageFile, ex1.transferCode);
        p.receiver.completeIntake(in1.deviceId, in1.generation,
                in1.packageSha256);
        p.receiver.confirmOneshot(p.deviceId, 1);
        // A second store of the same identity (the divergent
        // copy, its journal GROWN past the received tip)
        // exports generation 2.
        File dRoot = tmpDir("m4-divergent");
        writeJournal(dRoot, p.seed, 9);
        TransferVault dVault = new TransferVault(dRoot, testWrapKey());
        TransferInit.initialise(dRoot, dVault,
                TransferInit.MODE_NEW, p.seed, RANDOM);
        TransferSender dSender = new TransferSender(
                dRoot, dVault, RANDOM, "test-app", new TestFreeze(),
                p.senderFin, p.clock, 24L * 3600, 1000, 100);
        dSender.exportPackageOneshot(p.seed);
        dSender.cancelOneshotHandover(p.deviceId);
        TransferSender.ExportResult ex2 =
                dSender.exportPackageOneshot(p.seed);
        check("m4: the divergent export is generation 2",
                ex2.generation == 2);
        List<String> frozen = new ArrayList<>();
        TransferReceiver hooked = new TransferReceiver(
                p.receiverRoot, p.receiverVault, testWrapKey(),
                p.receiverFin, p.clock, new TestTimeSource());
        hooked.setForeignWriterFreeze(frozen::add);
        TransferReceiver.IntakeResult in2 =
                hooked.intakePackage(ex2.packageFile, ex2.transferCode);
        hooked.completeIntake(in2.deviceId, in2.generation,
                in2.packageSha256);
        hooked.confirmOneshot(p.deviceId, 2);
        check("m4: the same-identity writer was frozen by the hook",
                frozen.contains(p.deviceId));
        TransferInit.Gate g = TransferInit.writerGate(
                p.receiverRoot, true);
        check("m4: the promoted identity is live afterwards",
                g.allowed && p.deviceId.equals(g.deviceId));
    }

    /** Audit F5 helpers: transfer traces distinguish a
     *  damaged store from a pre-transfer one, and the
     *  remediation decision fires exactly for a completed
     *  send whose home copy is no longer the held sender. */
    private static void testTracesAndRemediation() throws Exception {
        File empty = tmpDir("traces");
        check("f5: an untouched store has no transfer traces",
                !TransferInit.hasTransferTraces(empty));
        Files.write(new File(empty, "transfer-state.json").toPath(),
                "{}".getBytes(StandardCharsets.UTF_8));
        check("f5: a transfer-state file is a trace",
                TransferInit.hasTransferTraces(empty));
        // Full one-shot cycle on the sender store: S2 in
        // history, no open record, no binding.
        Pair p = newPair(false);
        TransferSender.ExportResult ex =
                p.sender.exportPackageOneshot(p.seed);
        TransferReceiver.IntakeResult in =
                p.receiver.intakePackage(ex.packageFile, ex.transferCode);
        p.receiver.completeIntake(in.deviceId, in.generation,
                in.packageSha256);
        p.receiver.confirmOneshot(p.deviceId, 1);
        String code = TransferCommitCode.codeFor(
                TransferCommitCode.deriveKey(ex.transferCode),
                in.manifestHash);
        p.sender.closeOneshotHandover(p.deviceId, code);
        check("f5: the completed send awaits home-seed erasure",
                p.deviceId.equals(TransferRemediation
                        .sentOutIdentityAwaitingErasure(
                                TransferState.load(p.senderRoot),
                                p.deviceId)));
        // An open S1 blocks it outright.
        Pair q = newPair(false);
        q.sender.exportPackageOneshot(q.seed);
        check("f5: an open S1 never triggers the erasure",
                TransferRemediation.sentOutIdentityAwaitingErasure(
                        TransferState.load(q.senderRoot),
                        q.deviceId) == null);
        // Home again as the held sender (a later return):
        // the home copy is the operative one — keep it.
        TransferState st = TransferState.load(p.senderRoot);
        st.setActive(TransferState.binding(
                p.deviceId, 2, "sender", "holding"));
        st.save();
        check("f5: home-again-as-sender keeps the home seed",
                TransferRemediation.sentOutIdentityAwaitingErasure(
                        TransferState.load(p.senderRoot),
                        p.deviceId) == null);
        // Back as a RECEIVER: the operative copy is the
        // vault's — the home copy is redundant, erasure due.
        st.setActive(TransferState.binding(
                p.deviceId, 3, "receiver", "holding"));
        st.save();
        check("f5: back-as-receiver still awaits erasure",
                p.deviceId.equals(TransferRemediation
                        .sentOutIdentityAwaitingErasure(
                                TransferState.load(p.senderRoot),
                                p.deviceId)));
    }

    /** Audit F6: the sent-out marker closes the gate ONLY
     *  for its own identity on the home-seed path. A received
     *  copy of the same identity (a return as a new
     *  generation) and any different identity stay
     *  recordable; the home path without a received copy
     *  stays closed. (The companion app layer consults this
     *  decision with the marker id and the vault fact.) */
    private static void testF6MarkerScope() {
        check("f6: the marker closes its own identity's home path",
                TransferRemediation.sentOutMarkerClosesGate(
                        "qdp1-aaa", "qdp1-aaa", false));
        check("f6: a returned generation with a receiver copy "
                        + "stays open",
                !TransferRemediation.sentOutMarkerClosesGate(
                        "qdp1-aaa", "qdp1-aaa", true));
        check("f6: a different received identity stays open",
                !TransferRemediation.sentOutMarkerClosesGate(
                        "qdp1-aaa", "qdp1-bbb", false));
        check("f6: no marker closes nothing",
                !TransferRemediation.sentOutMarkerClosesGate(
                        null, "qdp1-aaa", false));
    }

    /** Audit M8: the transfer-trace set is built from
     *  TransferStore's layout constants (one definition), and
     *  EVERY name in it — the promotion journal and the
     *  shadow quarantine included — makes a store count as
     *  traced on its own, so the app layers' fail-closed
     *  resolution (HELD / closed gate) engages for a store
     *  carrying only that one remnant. */
    private static void testM8TraceConsistency() throws Exception {
        String[] names = TransferInit.transferTraceNames();
        check("m8: nine trace names", names.length == 9);
        check("m8: the set is built from the layout constants",
                Arrays.asList(names).containsAll(Arrays.asList(
                        TransferState.FILE_NAME,
                        TransferVault.FILE_NAME,
                        TransferInit.LAYOUT_FILE_NAME,
                        TransferStore.STAGING_AREA_NAME,
                        TransferStore.TRANSFER_OUT_NAME,
                        TransferStore.PROMOTION_JOURNAL_NAME,
                        TransferStore.SHADOW_QUARANTINE_NAME,
                        TransferStore.PRE_PROMOTION_BACKUP_NAME,
                        TransferStore.LIVE_MANIFEST_NAME)));
        for (String n : names) {
            File root = tmpDir("m8-only");
            new File(root, n).getParentFile().mkdirs();
            Files.write(new File(root, n).toPath(), new byte[0]);
            check("m8: a store with only " + n + " is traced",
                    TransferInit.hasTransferTraces(root));
        }
        File pj = tmpDir("m8-pj");
        Files.write(new File(pj,
                TransferStore.PROMOTION_JOURNAL_NAME).toPath(),
                new byte[0]);
        check("m8: promotion-journal alone engages the traces",
                TransferInit.hasTransferTraces(pj));
        File sq = tmpDir("m8-sq");
        new File(sq, TransferStore.SHADOW_QUARANTINE_NAME).mkdirs();
        check("m8: shadow-quarantine alone engages the traces",
                TransferInit.hasTransferTraces(sq));
    }

    /** Audit F7: the M3 commit-key rescue is a counted,
     *  timestamped, single-flight operation. The ledger lives
     *  in the staging record itself, written atomically with
     *  the rescued key, and accumulates if the key material
     *  is ever lost again. */
    private static void testF7CommitKeyRescueLedger()
            throws Exception {
        Pair p = newPair(false);
        TransferSender.ExportResult ex =
                p.sender.exportPackageOneshot(p.seed);
        TransferReceiver.IntakeResult in =
                p.receiver.stagePackage(ex.packageFile, ex.transferCode);
        p.receiver.completeIntake(in.deviceId, in.generation,
                in.packageSha256);
        File root = TransferStore.stagingRoot(p.receiverRoot,
                p.deviceId, 1, in.packageSha256);
        TransferStore.StagingRecord sr =
                TransferStore.loadStaging(root);
        sr.commitKeyB64 = null;
        sr.commitKeyWrappedB64 = null;
        TransferStore.saveStaging(root, sr);
        p.clock.wall = 1762009999L;
        p.receiver.stagePackage(ex.packageFile, ex.transferCode);
        TransferStore.StagingRecord after1 =
                TransferStore.loadStaging(root);
        check("f7: the rescue fires once and is counted",
                after1.commitKeyRescueCount == 1
                        && after1.hasCommitKeyMaterial());
        check("f7: the rescue is timestamped in the record",
                after1.commitKeyRescueLastUnix == 1762009999L);
        // A keyed staging never re-fires.
        p.receiver.stagePackage(ex.packageFile, ex.transferCode);
        check("f7: a keyed staging does not re-fire the rescue",
                TransferStore.loadStaging(root)
                        .commitKeyRescueCount == 1);
        // Lose the material again: the ledger accumulates
        // instead of restarting, and the stamp advances.
        TransferStore.StagingRecord stripped =
                TransferStore.loadStaging(root);
        stripped.commitKeyB64 = null;
        stripped.commitKeyWrappedB64 = null;
        TransferStore.saveStaging(root, stripped);
        p.clock.wall = 1762011111L;
        p.receiver.stagePackage(ex.packageFile, ex.transferCode);
        TransferStore.StagingRecord after2 =
                TransferStore.loadStaging(root);
        check("f7: a second rescue accumulates in the ledger",
                after2.commitKeyRescueCount == 2
                        && after2.commitKeyRescueLastUnix
                                == 1762011111L);
        // Single-flight: concurrent rescues of one keyless
        // staging land exactly one firing.
        Pair q = newPair(false);
        TransferSender.ExportResult qEx =
                q.sender.exportPackageOneshot(q.seed);
        TransferReceiver.IntakeResult qIn =
                q.receiver.stagePackage(qEx.packageFile,
                        qEx.transferCode);
        q.receiver.completeIntake(qIn.deviceId, qIn.generation,
                qIn.packageSha256);
        File qRoot = TransferStore.stagingRoot(q.receiverRoot,
                q.deviceId, 1, qIn.packageSha256);
        TransferStore.StagingRecord qSr =
                TransferStore.loadStaging(qRoot);
        qSr.commitKeyB64 = null;
        qSr.commitKeyWrappedB64 = null;
        TransferStore.saveStaging(qRoot, qSr);
        List<Throwable> errors =
                java.util.Collections.synchronizedList(
                        new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(() -> {
                try {
                    TransferReceiver.rescueCommitKeyIfAbsent(
                            qRoot, qEx.transferCode,
                            TransferPackage.parseEnvelope(
                                    Files.readAllBytes(
                                            qEx.packageFile.toPath())),
                            qEx.packageFile.length(),
                            testWrapKey(), q.clock);
                } catch (Exception e) {
                    errors.add(e);
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();
        TransferStore.StagingRecord qAfter =
                TransferStore.loadStaging(qRoot);
        check("f7: concurrent rescues are single-flight",
                errors.isEmpty()
                        && qAfter.commitKeyRescueCount == 1
                        && qAfter.hasCommitKeyMaterial());
    }

    /** Audit F8: sending a RECEIVED identity onward and
     *  closing the send (S2, either route) erases the
     *  received copy the send superseded — the absence round
     *  1's recovery allowance (seedAbsenceExplained) explains
     *  is now a state the code actually produces. A cancelled
     *  send (S0) keeps the copy: the identity stays here. */
    private static void testF8ReceiverKeyErasureOnOnwardSend()
            throws Exception {
        // One-shot route.
        Pair p = newPair(false);
        TransferSender.ExportResult ex =
                p.sender.exportPackageOneshot(p.seed);
        TransferReceiver.IntakeResult in =
                p.receiver.intakePackage(ex.packageFile,
                        ex.transferCode);
        p.receiver.completeIntake(in.deviceId, in.generation,
                in.packageSha256);
        p.receiver.confirmOneshot(p.deviceId, 1);
        check("f8: the received copy exists before the onward send",
                p.receiverVault.has(TransferVault.receiverKey(1)));
        TransferSender onward = new TransferSender(
                p.receiverRoot, p.receiverVault, RANDOM, "test-app",
                new TestFreeze(), p.receiverFin, p.clock,
                24L * 3600, 1000, 100);
        TransferSender.ExportResult ex2 =
                onward.exportPackageOneshot(p.seed);
        Pair third = newPair(false);
        TransferReceiver.IntakeResult in2 =
                third.receiver.intakePackage(ex2.packageFile,
                        ex2.transferCode);
        third.receiver.completeIntake(in2.deviceId, in2.generation,
                in2.packageSha256);
        third.receiver.confirmOneshot(p.deviceId, 2);
        String code2 = TransferCommitCode.codeFor(
                TransferCommitCode.deriveKey(ex2.transferCode),
                in2.manifestHash);
        onward.closeOneshotHandover(p.deviceId, code2);
        check("f8: the one-shot S2 closure erases the received copy",
                !p.receiverVault.has(TransferVault.receiverKey(1)));
        List<String> notes = p.newReceiver().recover();
        check("f8: recovery tolerates the absence the closure made",
                notes.stream().anyMatch(
                        n -> n.contains("sent onward")));
        // Strict route.
        Pair q = newPair(false);
        TransferSender.ExportResult qEx =
                q.sender.exportPackage(q.seed);
        TransferReceiver.IntakeResult qIn =
                q.receiver.intakePackage(qEx.packageFile,
                        qEx.transferCode);
        q.receiver.completeIntake(qIn.deviceId, qIn.generation,
                qIn.packageSha256);
        Map<String, Object> notice =
                q.receiver.buildNotice(q.deviceId, 1);
        Map<String, Object> permit =
                q.sender.issuePermit(q.seed, notice);
        q.receiver.intakePermit(permit);
        q.receiver.confirmTrial(q.deviceId, 1);
        Map<String, Object> receipt =
                q.receiver.buildReceipt(q.deviceId, 1);
        q.sender.onReceipt(q.seed, receipt);
        check("f8: the strict receive holds its copy",
                q.receiverVault.has(TransferVault.receiverKey(1)));
        TransferSender qOnward = new TransferSender(
                q.receiverRoot, q.receiverVault, RANDOM, "test-app",
                new TestFreeze(), q.receiverFin, q.clock,
                24L * 3600, 1000, 100);
        TransferSender.ExportResult qEx2 =
                qOnward.exportPackage(q.seed);
        Pair r = newPair(false);
        TransferReceiver.IntakeResult rIn =
                r.receiver.intakePackage(qEx2.packageFile,
                        qEx2.transferCode);
        r.receiver.completeIntake(rIn.deviceId, rIn.generation,
                rIn.packageSha256);
        Map<String, Object> rNotice =
                r.receiver.buildNotice(q.deviceId, 2);
        Map<String, Object> rPermit =
                qOnward.issuePermit(q.seed, rNotice);
        r.receiver.intakePermit(rPermit);
        r.receiver.confirmTrial(q.deviceId, 2);
        Map<String, Object> rReceipt =
                r.receiver.buildReceipt(q.deviceId, 2);
        qOnward.onReceipt(q.seed, rReceipt);
        check("f8: the strict S2 closure erases the received copy",
                !q.receiverVault.has(TransferVault.receiverKey(1)));
        List<String> qNotes = q.newReceiver().recover();
        check("f8: strict recovery tolerates the made absence",
                qNotes.stream().anyMatch(
                        n -> n.contains("sent onward")));
        // Negative control: a cancelled onward send keeps it.
        Pair s = newPair(false);
        TransferSender.ExportResult sEx =
                s.sender.exportPackageOneshot(s.seed);
        TransferReceiver.IntakeResult sIn =
                s.receiver.intakePackage(sEx.packageFile,
                        sEx.transferCode);
        s.receiver.completeIntake(sIn.deviceId, sIn.generation,
                sIn.packageSha256);
        s.receiver.confirmOneshot(s.deviceId, 1);
        TransferSender sOnward = new TransferSender(
                s.receiverRoot, s.receiverVault, RANDOM, "test-app",
                new TestFreeze(), s.receiverFin, s.clock,
                24L * 3600, 1000, 100);
        sOnward.exportPackageOneshot(s.seed);
        sOnward.cancelOneshotHandover(s.deviceId);
        check("f8: a cancelled onward send keeps the received copy",
                s.receiverVault.has(TransferVault.receiverKey(1)));
    }

    /** Audit F9: the journal path decision. With a gate
     *  present, construction is answered ONLY by the
     *  identity the gate names — its received copy at its
     *  own generation, or the home seed when a non-creating
     *  read shows the home seed derives that identity.
     *  Everything else fails closed; only the gateless
     *  (pre-transfer) store keeps the creating home path.
     *  (The companion app layer wires this decision with
     *  homeSeedDeviceId / the generation-scoped
     *  receiverSeedExists, which throws on unreadable
     *  instead of answering false.) */
    private static void testF9JournalPathDecision() {
        check("f9: (a) a surviving home copy of a sent-out "
                        + "identity never serves a received "
                        + "identity's gate",
                TransferRemediation.journalPath(
                        true, "qdp1-bbb", "qdp1-aaa", false)
                        == TransferRemediation.JOURNAL_FAIL_CLOSED);
        check("f9: (b) an erased home seed mints no new "
                        + "identity under a received gate",
                TransferRemediation.journalPath(
                        true, "qdp1-bbb", null, false)
                        == TransferRemediation.JOURNAL_FAIL_CLOSED);
        check("f9: (c) no gate keeps the legacy home path",
                TransferRemediation.journalPath(
                        false, null, null, false)
                        == TransferRemediation.JOURNAL_LEGACY_HOME);
        check("f9: (d) the gate naming the home identity "
                        + "takes the gated home path",
                TransferRemediation.journalPath(
                        true, "qdp1-aaa", "qdp1-aaa", false)
                        == TransferRemediation.JOURNAL_GATED_HOME);
        check("f9: a received copy at the gate generation "
                        + "takes the received path",
                TransferRemediation.journalPath(
                        true, "qdp1-bbb", "qdp1-aaa", true)
                        == TransferRemediation.JOURNAL_RECEIVED);
        check("f9: a gate without an identity fails closed",
                TransferRemediation.journalPath(
                        true, null, "qdp1-aaa", false)
                        == TransferRemediation.JOURNAL_FAIL_CLOSED);
    }

    /** A canonical-form code differing from the given one
     *  in its first character: passes normalisation, fails
     *  the package's AEAD open. */
    private static String wrongCode(String code) {
        String alphabet = TransferCodec.CODE_ALPHABET;
        char first = code.charAt(0);
        char replacement = alphabet.charAt(
                (alphabet.indexOf(first) + 1) % alphabet.length());
        return replacement + code.substring(1);
    }

    /** Audit M11: the M3 rescue verifies the presented
     *  transfer code against the re-presented package
     *  (an AEAD open) BEFORE deriving or persisting
     *  anything. A wrong code pins no key and leaves no
     *  ledger entry, so the rescue remains available; the
     *  right code afterwards rescues a key whose number
     *  the sender accepts. */
    private static void testM11RescueVerifiesCode()
            throws Exception {
        Pair p = newPair(false);
        TransferSender.ExportResult ex =
                p.sender.exportPackageOneshot(p.seed);
        TransferReceiver.IntakeResult in =
                p.receiver.stagePackage(ex.packageFile,
                        ex.transferCode);
        p.receiver.completeIntake(in.deviceId, in.generation,
                in.packageSha256);
        File root = TransferStore.stagingRoot(p.receiverRoot,
                p.deviceId, 1, in.packageSha256);
        TransferStore.StagingRecord sr =
                TransferStore.loadStaging(root);
        sr.commitKeyB64 = null;
        sr.commitKeyWrappedB64 = null;
        TransferStore.saveStaging(root, sr);
        String wrong = wrongCode(ex.transferCode);
        check("m11: a wrong code at re-presentation is "
                        + "refused",
                throwsType(() -> p.receiver.stagePackage(
                        ex.packageFile, wrong),
                        IllegalArgumentException.class));
        TransferStore.StagingRecord afterWrong =
                TransferStore.loadStaging(root);
        check("m11: no key is pinned by the wrong code",
                !afterWrong.hasCommitKeyMaterial());
        check("m11: the ledger records no refused firing",
                afterWrong.commitKeyRescueCount == 0);
        TransferPackage.Envelope env =
                TransferPackage.parseEnvelope(Files.readAllBytes(
                        ex.packageFile.toPath()));
        check("m11: the rescue itself refuses a wrong code",
                throwsType(() -> TransferReceiver
                        .rescueCommitKeyIfAbsent(root, wrong, env,
                                ex.packageFile.length(),
                                testWrapKey(), p.clock),
                        IllegalArgumentException.class));
        check("m11: still keyless after the direct refusal",
                !TransferStore.loadStaging(root)
                        .hasCommitKeyMaterial());
        p.receiver.stagePackage(ex.packageFile, ex.transferCode);
        TransferStore.StagingRecord afterRight =
                TransferStore.loadStaging(root);
        check("m11: the right code rescues after the refusal",
                afterRight.hasCommitKeyMaterial()
                        && afterRight.commitKeyRescueCount == 1);
        p.receiver.confirmOneshot(p.deviceId, 1);
        byte[] rescuedKey = TransferCodec.b64DecodeStrict(
                afterRight.derivedCommitKeyB64(testWrapKey()),
                "rescued commit key");
        String number = TransferCommitCode.codeFor(
                rescuedKey, in.manifestHash);
        boolean closed = true;
        try {
            p.sender.closeOneshotHandover(p.deviceId, number);
        } catch (Exception e) {
            closed = false;
        }
        check("m11: the rescued key's number closes the "
                + "handover", closed);
    }

    /** Audit M10: the idempotent superseded-copy sweep.
     *  The F8 closures erase the vault copy but (round 2)
     *  left the staging record's wrap of the SAME seed —
     *  and a crash between the S2 save and the erasure
     *  left both copies with no retry. The fixtures below
     *  rebuild exactly that crash window (the closure's
     *  erasure already ran; the vault copy is then put
     *  back by hand, as a crash before the erasure would
     *  have left it) and let recovery's sweep finish it. */
    private static void testM10SupersededSweep()
            throws Exception {
        // ---- one-shot shape ----
        Pair p = newPair(false);
        TransferSender.ExportResult ex =
                p.sender.exportPackageOneshot(p.seed);
        TransferReceiver.IntakeResult in =
                p.receiver.intakePackage(ex.packageFile,
                        ex.transferCode);
        p.receiver.completeIntake(in.deviceId, in.generation,
                in.packageSha256);
        p.receiver.confirmOneshot(p.deviceId, 1);
        TransferSender onward = new TransferSender(
                p.receiverRoot, p.receiverVault, RANDOM,
                "test-app", new TestFreeze(), p.receiverFin,
                p.clock, 24L * 3600, 1000, 100);
        TransferSender.ExportResult ex2 =
                onward.exportPackageOneshot(p.seed);
        Pair third = newPair(false);
        TransferReceiver.IntakeResult in2 =
                third.receiver.intakePackage(ex2.packageFile,
                        ex2.transferCode);
        third.receiver.completeIntake(in2.deviceId,
                in2.generation, in2.packageSha256);
        third.receiver.confirmOneshot(p.deviceId, 2);
        String code2 = TransferCommitCode.codeFor(
                TransferCommitCode.deriveKey(ex2.transferCode),
                in2.manifestHash);
        onward.closeOneshotHandover(p.deviceId, code2);
        File pRoot = TransferStore.stagingRoot(p.receiverRoot,
                p.deviceId, 1, in.packageSha256);
        check("m10: the staging seed wrap survives the F8 "
                        + "closure (the gap)",
                !TransferStore.loadStaging(pRoot).seedErased()
                        && !p.receiverVault.has(
                                TransferVault.receiverKey(1)));
        // The crash window, rebuilt: S2 durable, erasure
        // never ran.
        p.receiverVault.put(TransferVault.receiverKey(1),
                p.seed);
        List<String> pNotes = p.newReceiver().recover();
        check("m10: the sweep removes the surviving vault "
                        + "copy",
                !p.receiverVault.has(
                        TransferVault.receiverKey(1)));
        check("m10: the sweep invalidates the staging seed "
                        + "wrap",
                TransferStore.loadStaging(pRoot).seedErased());
        check("m10: the sweep is noted",
                pNotes.stream().anyMatch(
                        n -> n.contains("M10")));
        byte[] pVault = Files.readAllBytes(new File(
                p.receiverRoot, TransferVault.FILE_NAME)
                .toPath());
        byte[] pStaging = Files.readAllBytes(
                TransferStore.stagingRecordFile(pRoot).toPath());
        byte[] pState = Files.readAllBytes(new File(
                p.receiverRoot, TransferState.FILE_NAME)
                .toPath());
        List<String> pNotes2 = p.newReceiver().recover();
        check("m10: a second sweep is a byte-identical "
                        + "no-op",
                Arrays.equals(pVault, Files.readAllBytes(
                        new File(p.receiverRoot,
                                TransferVault.FILE_NAME).toPath()))
                        && Arrays.equals(pStaging,
                                Files.readAllBytes(TransferStore
                                        .stagingRecordFile(pRoot)
                                        .toPath()))
                        && Arrays.equals(pState,
                                Files.readAllBytes(new File(
                                        p.receiverRoot,
                                        TransferState.FILE_NAME)
                                        .toPath()))
                        && pNotes2.stream().noneMatch(
                                n -> n.contains("M10")));

        // ---- strict shape, ack still outstanding ----
        Pair q = newPair(false);
        TransferSender.ExportResult qEx =
                q.sender.exportPackage(q.seed);
        TransferReceiver.IntakeResult qIn =
                q.receiver.intakePackage(qEx.packageFile,
                        qEx.transferCode);
        q.receiver.completeIntake(qIn.deviceId, qIn.generation,
                qIn.packageSha256);
        Map<String, Object> notice =
                q.receiver.buildNotice(q.deviceId, 1);
        Map<String, Object> permit =
                q.sender.issuePermit(q.seed, notice);
        q.receiver.intakePermit(permit);
        q.receiver.confirmTrial(q.deviceId, 1);
        Map<String, Object> receipt =
                q.receiver.buildReceipt(q.deviceId, 1);
        q.sender.onReceipt(q.seed, receipt);
        TransferSender qOnward = new TransferSender(
                q.receiverRoot, q.receiverVault, RANDOM,
                "test-app", new TestFreeze(), q.receiverFin,
                q.clock, 24L * 3600, 1000, 100);
        TransferSender.ExportResult qEx2 =
                qOnward.exportPackage(q.seed);
        Pair r = newPair(false);
        TransferReceiver.IntakeResult rIn =
                r.receiver.intakePackage(qEx2.packageFile,
                        qEx2.transferCode);
        r.receiver.completeIntake(rIn.deviceId, rIn.generation,
                rIn.packageSha256);
        Map<String, Object> rNotice =
                r.receiver.buildNotice(q.deviceId, 2);
        Map<String, Object> rPermit =
                qOnward.issuePermit(q.seed, rNotice);
        r.receiver.intakePermit(rPermit);
        r.receiver.confirmTrial(q.deviceId, 2);
        Map<String, Object> rReceipt =
                r.receiver.buildReceipt(q.deviceId, 2);
        qOnward.onReceipt(q.seed, rReceipt);
        File qRoot = TransferStore.stagingRoot(q.receiverRoot,
                q.deviceId, 1, qIn.packageSha256);
        check("m10: the ack-pending staging wrap survives "
                        + "the strict closure (the gap)",
                !TransferStore.loadStaging(qRoot).seedErased());
        q.receiverVault.put(TransferVault.receiverKey(1),
                q.seed);
        q.newReceiver().recover();
        check("m10: the sweep removes the ack-pending "
                        + "generation's vault copy",
                !q.receiverVault.has(
                        TransferVault.receiverKey(1)));
        TransferStore.StagingRecord qSr =
                TransferStore.loadStaging(qRoot);
        check("m10: the ack-pending record is kept, its "
                        + "seed invalidated",
                qSr != null && qSr.seedErased());
        Map<String, Object> reissued =
                q.newReceiver().buildReceipt(q.deviceId, 1);
        check("m10: the receipt stays re-issuable from the "
                        + "kept record",
                reissued != null && TransferState.longOr(
                        reissued, "transfer_generation", -1)
                        == 1L);

        // ---- negative control: never sent onward ----
        Pair s = newPair(false);
        TransferSender.ExportResult sEx =
                s.sender.exportPackageOneshot(s.seed);
        TransferReceiver.IntakeResult sIn =
                s.receiver.intakePackage(sEx.packageFile,
                        sEx.transferCode);
        s.receiver.completeIntake(sIn.deviceId, sIn.generation,
                sIn.packageSha256);
        s.receiver.confirmOneshot(s.deviceId, 1);
        List<String> sNotes = s.newReceiver().recover();
        File sRoot = TransferStore.stagingRoot(s.receiverRoot,
                s.deviceId, 1, sIn.packageSha256);
        check("m10: an identity that stayed home keeps its "
                        + "vault copy",
                s.receiverVault.has(TransferVault.receiverKey(1))
                        && sNotes.stream().noneMatch(
                                n -> n.contains("M10")));
        check("m10: an identity that stayed home keeps its "
                        + "staging seed",
                !TransferStore.loadStaging(sRoot).seedErased());
    }

    /** Audit F10: the superseded-copy sweep (and the
     *  closure-time erasure it retries) classify copies by
     *  generation number, but the vault files copies under
     *  bare generation numbers shared by EVERY identity in
     *  the store. This fixture builds the collision through
     *  real flows: identity X is received at generation 1
     *  and sent onward (S2 at generation 2); identity Y is
     *  then received at generation 1 of its own chain, and
     *  its materialisation lands in the SAME vault slot,
     *  receiverKey(1). X's generation-1 record is now
     *  "explained" by the numbers — and the copy under its
     *  number is Y's live seed. The sweep must leave that
     *  copy exactly where it is instead of silently erasing
     *  Y's only copy on X's numbers. Round 4 (audit M13)
     *  refined the two expectations round 3 pinned here:
     *  X's OWN staging wrap is no longer left behind by the
     *  F10 skip — the sweep proves the wrap from its own
     *  bytes and invalidates it — and the recovery
     *  classification treats the explained absence plus a
     *  copy deriving another in-store record as the
     *  legitimate occupancy it is (noted, allowed), while
     *  a copy no record claims still stops it by name. The
     *  second shape aims the same test at the closure-time
     *  erasure (F8) itself. */
    private static void testF10SweepDerivationProof()
            throws Exception {
        Pair p = newPair(false);   // identity X
        // Identity Y: a standalone sender store on the
        // harness's second seed (every newPair shares the
        // first seed, so Y is built the way the foreign-live
        // test builds its second identity).
        byte[] seedY = otherSeed();
        String deviceY = DepinCore.deviceIdFromPubkey(
                Ed25519.publickeyFromSeed(seedY));
        File yRoot = tmpDir("f10-y-sender");
        writeJournal(yRoot, seedY, 3);
        TransferVault yVault = new TransferVault(
                yRoot, testWrapKey());
        TransferInit.initialise(yRoot, yVault,
                TransferInit.MODE_NEW, seedY, RANDOM);
        TransferSender ySender = new TransferSender(
                yRoot, yVault, RANDOM, "test-app",
                new TestFreeze(), p.receiverFin, p.clock,
                24L * 3600, 1000, 100);
        // X arrives at generation 1 and is confirmed.
        TransferSender.ExportResult ex =
                p.sender.exportPackageOneshot(p.seed);
        TransferReceiver.IntakeResult in =
                p.receiver.intakePackage(ex.packageFile,
                        ex.transferCode);
        p.receiver.completeIntake(in.deviceId, in.generation,
                in.packageSha256);
        p.receiver.confirmOneshot(p.deviceId, 1);
        // X is sent onward from the receiving store and the
        // handover closes (S2 at generation 2): the F8
        // erasure removes X's own generation-1 copy.
        TransferSender onward = new TransferSender(
                p.receiverRoot, p.receiverVault, RANDOM,
                "test-app", new TestFreeze(), p.receiverFin,
                p.clock, 24L * 3600, 1000, 100);
        TransferSender.ExportResult ex2 =
                onward.exportPackageOneshot(p.seed);
        Pair third = newPair(false);
        TransferReceiver.IntakeResult in2 =
                third.receiver.intakePackage(ex2.packageFile,
                        ex2.transferCode);
        third.receiver.completeIntake(in2.deviceId,
                in2.generation, in2.packageSha256);
        third.receiver.confirmOneshot(p.deviceId, 2);
        String code2 = TransferCommitCode.codeFor(
                TransferCommitCode.deriveKey(ex2.transferCode),
                in2.manifestHash);
        onward.closeOneshotHandover(p.deviceId, code2);
        check("f10: the onward closure erased X's own copy",
                !p.receiverVault.has(
                        TransferVault.receiverKey(1)));
        // Y arrives at generation 1 of its own chain into
        // the SAME store: its copy lands in the same vault
        // slot, receiverKey(1).
        TransferSender.ExportResult yEx =
                ySender.exportPackageOneshot(seedY);
        TransferReceiver.IntakeResult yIn =
                p.receiver.intakePackage(yEx.packageFile,
                        yEx.transferCode);
        p.receiver.completeIntake(yIn.deviceId,
                yIn.generation, yIn.packageSha256);
        p.receiver.confirmOneshot(deviceY, 1);
        byte[] yCopy = p.receiverVault.get(
                TransferVault.receiverKey(1));
        check("f10: the shared slot now holds Y's seed",
                yCopy != null && TransferRemediation
                        .seedDerivesIdentity(yCopy, deviceY));
        if (yCopy != null) {
            java.util.Arrays.fill(yCopy, (byte) 0);
        }
        File xRoot = TransferStore.stagingRoot(p.receiverRoot,
                p.deviceId, 1, in.packageSha256);
        // The standalone sweep must skip, note the skip, and
        // touch nothing.
        List<String> notes = p.newReceiver()
                .sweepSupersededSeedCopies();
        check("f10: the sweep notes the unproven copy",
                notes.stream().anyMatch(
                        n -> n.contains("F10")));
        check("f10: the sweep leaves the foreign copy in "
                        + "place",
                p.receiverVault.has(
                        TransferVault.receiverKey(1)));
        // Audit M13: the FOREIGN vault copy stays exactly
        // where it was (above), but X's OWN staging wrap is
        // a different matter — it is X's second envelope,
        // and the sweep proves it from the wrap's own bytes
        // (the wrapped seed derives X's staging record's
        // identity) and invalidates it, instead of leaving
        // a superseded identity's seed in place forever
        // behind the F10 skip.
        check("m13: the sweep invalidates X's own staging "
                        + "wrap",
                TransferStore.loadStaging(xRoot).seedErased());
        check("m13: the wrap invalidation is noted",
                notes.stream().anyMatch(
                        n -> n.contains("M13")));
        // Recovery then treats the state as what it
        // legitimately is (audit M13): X's absence is
        // explained (X was sent onward), and the copy under
        // X's generation derives Y — an identity with its
        // own generation-1 record in this store. That is a
        // lawful occupancy, noted and allowed, not the
        // inconsistency round 3 pinned here.
        List<String> rNotes = p.newReceiver().recover();
        check("m13: recovery allows the legitimate "
                        + "occupancy, noted",
                rNotes.stream().anyMatch(
                        n -> n.contains("M13")));
        byte[] yCopy2 = p.receiverVault.get(
                TransferVault.receiverKey(1));
        check("f10: Y's live copy survives recovery intact",
                yCopy2 != null && TransferRemediation
                        .seedDerivesIdentity(yCopy2, deviceY));
        if (yCopy2 != null) {
            java.util.Arrays.fill(yCopy2, (byte) 0);
        }
        // The sweep has converged for X's wrap: a second
        // run reports no further M13 action.
        List<String> notes2 = p.newReceiver()
                .sweepSupersededSeedCopies();
        check("m13: the sweep converges",
                notes2.stream().noneMatch(
                        n -> n.contains("M13")));
        // A copy deriving NO in-store record is not a
        // legitimate occupancy: with a stranger's seed in
        // the slot, recovery still stops on the mismatch,
        // by name.
        byte[] stranger = new byte[32];
        for (int i = 0; i < 32; i++) {
            stranger[i] = (byte) (0x70 + i);
        }
        p.receiverVault.put(TransferVault.receiverKey(1),
                stranger);
        String mismatch = null;
        try {
            p.newReceiver().recover();
        } catch (TransferReceiver.InconsistencyException e) {
            mismatch = e.getMessage();
        }
        check("m13: a copy no record claims still stops "
                        + "recovery",
                mismatch != null && mismatch.contains(
                        "does not derive the identity"));

        // ---- closure-time shape: the F8 erasure itself ----
        Pair r = newPair(false);
        TransferSender.ExportResult rEx =
                r.sender.exportPackageOneshot(r.seed);
        TransferReceiver.IntakeResult rIn =
                r.receiver.intakePackage(rEx.packageFile,
                        rEx.transferCode);
        r.receiver.completeIntake(rIn.deviceId,
                rIn.generation, rIn.packageSha256);
        r.receiver.confirmOneshot(r.deviceId, 1);
        TransferSender rOnward = new TransferSender(
                r.receiverRoot, r.receiverVault, RANDOM,
                "test-app", new TestFreeze(), r.receiverFin,
                r.clock, 24L * 3600, 1000, 100);
        TransferSender.ExportResult rEx2 =
                rOnward.exportPackageOneshot(r.seed);
        Pair rThird = newPair(false);
        TransferReceiver.IntakeResult rIn2 =
                rThird.receiver.intakePackage(rEx2.packageFile,
                        rEx2.transferCode);
        rThird.receiver.completeIntake(rIn2.deviceId,
                rIn2.generation, rIn2.packageSha256);
        rThird.receiver.confirmOneshot(r.deviceId, 2);
        // A foreign lineage's seed lands in the shared slot
        // (the collision, arrived at directly here): the
        // closure's erasure must not take it.
        r.receiverVault.put(TransferVault.receiverKey(1),
                seedY);
        String rCode2 = TransferCommitCode.codeFor(
                TransferCommitCode.deriveKey(rEx2.transferCode),
                rIn2.manifestHash);
        rOnward.closeOneshotHandover(r.deviceId, rCode2);
        byte[] survivor = r.receiverVault.get(
                TransferVault.receiverKey(1));
        check("f10: the closure erasure leaves a foreign "
                        + "copy in place",
                survivor != null && TransferRemediation
                        .seedDerivesIdentity(survivor, deviceY));
        if (survivor != null) {
            java.util.Arrays.fill(survivor, (byte) 0);
        }
    }

    /** The adopted promotion plan for a generation, read
     *  from the promotion journal (null when none). */
    private static Map<String, Object> findPlanFor(
            File storeRoot, long generation) throws Exception {
        for (Map<String, Object> e : TransferIo.readFramed(
                TransferStore.promotionJournal(storeRoot))
                .entries) {
            if ("plan".equals(e.get("kind"))
                    && TransferState.longOr(
                            e, "generation", -1) == generation) {
                return TransferState.child(e, "plan");
            }
        }
        return null;
    }

    /** Audit F11: the vault files receiver copies under
     *  bare generation numbers shared by every lineage in
     *  the store (audit F10), and two intake decisions
     *  trusted the NUMBER: buildPlan computed seed_needed
     *  from existence alone, and step 8-2 skipped the seed
     *  placement on the same evidence. A second identity
     *  arriving at the same generation while the first
     *  identity's copy occupied the slot therefore planned
     *  as "seed already placed", completed its intake,
     *  issued its notice, and — once the sender closed and
     *  erased — could never be materialised: its seed was
     *  never in the vault, and the copy under its
     *  generation belonged to someone else. The slot's
     *  content must now prove itself against the identity
     *  at every decision: the intake refuses BEFORE the
     *  notified R1 is recorded when the slot holds another
     *  lineage's key; the plan's seed_needed means "this
     *  identity's key is not in the slot"; and step 8-2
     *  re-proves the slot at the point of use and never
     *  overwrites a foreign copy. The root fix — a vault
     *  namespace carrying the device id — is deliberately
     *  NOT taken in this round: no record anywhere says
     *  which identity an existing slot's content belongs
     *  to, so a migration would have to guess at exactly
     *  the stores it must protect (progress section 18);
     *  the slot proof closes the harm, because every
     *  placement and every reliance now derives identity
     *  from the key material itself. */
    private static void testF11SlotLineageCheck()
            throws Exception {
        // ---- shape 1: a foreign occupant refuses the
        // intake before any notice can exist ----
        Pair p = newPair(false);   // identity X
        TransferSender.ExportResult ex =
                p.sender.exportPackageOneshot(p.seed);
        TransferReceiver.IntakeResult in =
                p.receiver.intakePackage(ex.packageFile,
                        ex.transferCode);
        p.receiver.completeIntake(in.deviceId, in.generation,
                in.packageSha256);
        p.receiver.confirmOneshot(p.deviceId, 1);
        check("f11: X's copy occupies the generation-1 "
                        + "slot",
                p.receiverVault.has(
                        TransferVault.receiverKey(1)));
        byte[] seedY = otherSeed();
        String deviceY = DepinCore.deviceIdFromPubkey(
                Ed25519.publickeyFromSeed(seedY));
        File yRoot = tmpDir("f11-y-sender");
        writeJournal(yRoot, seedY, 3);
        TransferVault yVault = new TransferVault(
                yRoot, testWrapKey());
        TransferInit.initialise(yRoot, yVault,
                TransferInit.MODE_NEW, seedY, RANDOM);
        TransferSender ySender = new TransferSender(
                yRoot, yVault, RANDOM, "test-app",
                new TestFreeze(), p.receiverFin, p.clock,
                24L * 3600, 1000, 100);
        TransferSender.ExportResult yEx =
                ySender.exportPackage(seedY);
        String refusal = null;
        try {
            p.receiver.intakePackage(yEx.packageFile,
                    yEx.transferCode);
        } catch (TransferReceiver.HoldException e) {
            // Audit L24: the refusal's TYPE is part of the
            // expectation — a hold, not an inconsistency.
            // Its semantics are "not now" (the staging is
            // left un-notified and completes once the slot
            // frees), and the error report classifies by
            // type alone; rounds 5–6 threw
            // InconsistencyException here, so the same wait
            // classified differently by route.
            refusal = e.getMessage();
        }
        check("f11: the intake refuses a foreign slot "
                        + "occupant, by name, as a hold (L24)",
                refusal != null && refusal.contains("F11"));
        Map<String, Object> yRec = TransferState.load(
                p.receiverRoot).receiver(deviceY, 1);
        check("f11: no notified R1 was recorded for Y",
                yRec == null || !TransferState.boolOr(
                        yRec, "notified_r1", false));
        check("f11: no notice can be issued for Y",
                throwsType(() -> p.receiver.buildNotice(
                                deviceY, 1),
                        TransferReceiver.HoldException.class));
        byte[] xCopy = p.receiverVault.get(
                TransferVault.receiverKey(1));
        check("f11: X's copy is untouched by the refusal",
                xCopy != null && TransferRemediation
                        .seedDerivesIdentity(xCopy, p.deviceId));
        if (xCopy != null) {
            java.util.Arrays.fill(xCopy, (byte) 0);
        }

        // ---- shape 2: the slot already holds THIS
        // identity's key — seed_needed is false, correctly,
        // and the promotion completes on the placed copy --
        Pair q = newPair(false);
        TransferSender.ExportResult qEx =
                q.sender.exportPackage(q.seed);
        TransferReceiver.IntakeResult qIn =
                q.receiver.intakePackage(qEx.packageFile,
                        qEx.transferCode);
        q.receiver.completeIntake(qIn.deviceId, qIn.generation,
                qIn.packageSha256);
        // The copy lands in the slot ahead of the plan —
        // the state the existence-only check misread when
        // the copy was a foreign one.
        q.receiverVault.put(TransferVault.receiverKey(1),
                q.seed);
        Map<String, Object> notice =
                q.receiver.buildNotice(q.deviceId, 1);
        Map<String, Object> permit =
                q.sender.issuePermit(q.seed, notice);
        q.receiver.intakePermit(permit);
        q.receiver.confirmTrial(q.deviceId, 1);
        Map<String, Object> plan = findPlanFor(
                q.receiverRoot, 1);
        check("f11: an own key in the slot plans "
                        + "seed_needed false",
                plan != null && !TransferState.boolOr(
                        plan, "seed_needed", true));
        Map<String, Object> qRec = TransferState.load(
                q.receiverRoot).receiver(q.deviceId, 1);
        check("f11: the promotion completes on the placed "
                        + "copy",
                TransferState.child(qRec, "closure") != null);

        // ---- shape 3: an empty slot — seed_needed is
        // true and the placement proceeds (the ordinary
        // cycle, pinned at the plan) ----
        Pair s = newPair(false);
        TransferSender.ExportResult sEx =
                s.sender.exportPackage(s.seed);
        TransferReceiver.IntakeResult sIn =
                s.receiver.intakePackage(sEx.packageFile,
                        sEx.transferCode);
        s.receiver.completeIntake(sIn.deviceId, sIn.generation,
                sIn.packageSha256);
        Map<String, Object> sNotice =
                s.receiver.buildNotice(s.deviceId, 1);
        Map<String, Object> sPermit =
                s.sender.issuePermit(s.seed, sNotice);
        s.receiver.intakePermit(sPermit);
        s.receiver.confirmTrial(s.deviceId, 1);
        Map<String, Object> sPlan = findPlanFor(
                s.receiverRoot, 1);
        check("f11: an empty slot plans seed_needed true",
                sPlan != null && TransferState.boolOr(
                        sPlan, "seed_needed", false));
        byte[] placed = s.receiverVault.get(
                TransferVault.receiverKey(1));
        check("f11: the placement stored this identity's "
                        + "seed",
                placed != null && Arrays.equals(placed, s.seed));
        if (placed != null) {
            java.util.Arrays.fill(placed, (byte) 0);
        }
    }

    /** Audit M12 (predicate level): the structural
     *  derivation check the GATED_HOME journal construction
     *  and the F10 sweep now share — key material proves
     *  its own identity, or it is not believed. */
    private static void testSeedDerivesIdentityPredicate()
            throws Exception {
        Pair p = newPair(false);
        check("m12: a seed derives its own identity",
                TransferRemediation.seedDerivesIdentity(
                        p.seed, p.deviceId));
        check("m12: a foreign seed does not derive the "
                        + "identity",
                !TransferRemediation.seedDerivesIdentity(
                        otherSeed(), p.deviceId));
        check("m12: a missing seed proves nothing",
                !TransferRemediation.seedDerivesIdentity(
                        null, p.deviceId));
        check("m12: a missing identity proves nothing",
                !TransferRemediation.seedDerivesIdentity(
                        p.seed, null));
        check("m12: a malformed seed proves nothing",
                !TransferRemediation.seedDerivesIdentity(
                        new byte[10], p.deviceId));
    }

    /** Audit L13: the M11 rescue probe is a full in-memory
     *  open of the payload; above the stated cap the rescue
     *  refuses with a named error and persists nothing, so
     *  an oversized package cannot make re-presentation
     *  buffer a second full copy. At the cap the probe
     *  still runs (a wrong code there is refused by the
     *  probe, not by the size check). */
    private static void testL13RescueSizeCap()
            throws Exception {
        Pair p = newPair(false);
        TransferSender.ExportResult ex =
                p.sender.exportPackageOneshot(p.seed);
        TransferReceiver.IntakeResult in =
                p.receiver.stagePackage(ex.packageFile,
                        ex.transferCode);
        p.receiver.completeIntake(in.deviceId, in.generation,
                in.packageSha256);
        File root = TransferStore.stagingRoot(p.receiverRoot,
                p.deviceId, 1, in.packageSha256);
        TransferStore.StagingRecord sr =
                TransferStore.loadStaging(root);
        sr.commitKeyB64 = null;
        sr.commitKeyWrappedB64 = null;
        TransferStore.saveStaging(root, sr);
        TransferPackage.Envelope env =
                TransferPackage.parseEnvelope(Files.readAllBytes(
                        ex.packageFile.toPath()));
        long cap = TransferReceiver
                .COMMIT_KEY_RESCUE_MAX_PACKAGE_BYTES;
        String overMsg = null;
        try {
            TransferReceiver.rescueCommitKeyIfAbsent(root,
                    ex.transferCode, env, cap + 1,
                    testWrapKey(), p.clock);
        } catch (IllegalArgumentException e) {
            overMsg = e.getMessage();
        }
        check("l13: an over-cap package is refused by name",
                overMsg != null && overMsg.contains(
                        "limited to packages of at most"));
        TransferStore.StagingRecord after =
                TransferStore.loadStaging(root);
        check("l13: the refusal persists nothing",
                !after.hasCommitKeyMaterial()
                        && after.commitKeyRescueCount == 0);
        String wrong = wrongCode(ex.transferCode);
        String probeMsg = null;
        try {
            TransferReceiver.rescueCommitKeyIfAbsent(root,
                    wrong, env, cap, testWrapKey(), p.clock);
        } catch (IllegalArgumentException e) {
            probeMsg = e.getMessage();
        }
        check("l13: at the cap the probe still runs",
                probeMsg != null && probeMsg.contains(
                        "does not open this package"));
        check("l13: the at-cap probe refusal also persists "
                        + "nothing",
                !TransferStore.loadStaging(root)
                        .hasCommitKeyMaterial());
    }

    /** Audit L15 (predicate level): the signing-time seed
     *  proof the three app-layer signers (companion
     *  RECEIVED + GATED_HOME, Node RECEIVED) now run on
     *  every signature. Construction proves the loaded seed
     *  once (audit M12); the signers RE-READ the seed per
     *  checkpoint, and round 3 checked nothing about the
     *  re-read — a copy whose lineage changed under its
     *  generation (audit F10) would have signed entries the
     *  journal's own verifier rejects. The proof itself is
     *  what is pinned here; the wiring is app-layer and
     *  verified by build (progress section 18). */
    private static void testL15SigningSeedCheck()
            throws Exception {
        Pair p = newPair(false);
        byte[] pub = Ed25519.publickeyFromSeed(p.seed);
        check("l15: the journal's own seed may sign",
                !throwsAny(() -> TransferRemediation
                        .checkSigningSeed(p.seed, pub)));
        check("l15: a foreign seed is refused at signing "
                        + "time",
                throwsType(() -> TransferRemediation
                                .checkSigningSeed(
                                        otherSeed(), pub),
                        IllegalStateException.class));
        check("l15: a missing seed is refused",
                throwsType(() -> TransferRemediation
                                .checkSigningSeed(null, pub),
                        IllegalStateException.class));
        check("l15: a malformed seed is refused",
                throwsType(() -> TransferRemediation
                                .checkSigningSeed(
                                        new byte[10], pub),
                        IllegalStateException.class));
        check("l15: a missing journal key refuses "
                        + "everything",
                throwsType(() -> TransferRemediation
                                .checkSigningSeed(p.seed, null),
                        IllegalStateException.class));
    }

    /** Audit L16 (decision level): the home-seed erasure
     *  stage machine's classifier and its two allowances.
     *  Round 3 branched on string equality for two stages
     *  and let everything else — a "complete" record whose
     *  seed was still present, a stage string no build
     *  ever wrote — fall into the erasure branch. The
     *  decisions are pinned here; the companion's resume
     *  logic is a thin switch over them (app-layer, build
     *  verified — progress section 18). */
    private static void testL16ErasureStageMachine() {
        check("l16: the three stages classify",
                TransferRemediation.erasureStage("pending")
                        == TransferRemediation
                                .ERASURE_STAGE_PENDING
                        && TransferRemediation.erasureStage(
                                "seed_erased")
                        == TransferRemediation
                                .ERASURE_STAGE_SEED_ERASED
                        && TransferRemediation.erasureStage(
                                "complete")
                        == TransferRemediation
                                .ERASURE_STAGE_COMPLETE);
        check("l16: an unknown stage classifies unknown",
                TransferRemediation.erasureStage("half_done")
                        == TransferRemediation
                                .ERASURE_STAGE_UNKNOWN
                        && TransferRemediation.erasureStage("")
                        == TransferRemediation
                                .ERASURE_STAGE_UNKNOWN
                        && TransferRemediation.erasureStage(null)
                        == TransferRemediation
                                .ERASURE_STAGE_UNKNOWN);
        check("l16: the seed may be erased only from "
                        + "pending",
                TransferRemediation.erasureSeedMayBeErased(
                        TransferRemediation.ERASURE_STAGE_PENDING)
                        && !TransferRemediation
                                .erasureSeedMayBeErased(
                                        TransferRemediation
                                                .ERASURE_STAGE_SEED_ERASED)
                        && !TransferRemediation
                                .erasureSeedMayBeErased(
                                        TransferRemediation
                                                .ERASURE_STAGE_COMPLETE)
                        && !TransferRemediation
                                .erasureSeedMayBeErased(
                                        TransferRemediation
                                                .ERASURE_STAGE_UNKNOWN));
        check("l16: the marker needs a post-erasure stage "
                        + "and a verified absence",
                TransferRemediation.erasureMarkerMayBeWritten(
                        TransferRemediation
                                .ERASURE_STAGE_SEED_ERASED,
                        false, false)
                        && TransferRemediation
                                .erasureMarkerMayBeWritten(
                                        TransferRemediation
                                                .ERASURE_STAGE_COMPLETE,
                                        false, false)
                        && !TransferRemediation
                                .erasureMarkerMayBeWritten(
                                        TransferRemediation
                                                .ERASURE_STAGE_SEED_ERASED,
                                        true, false)
                        && !TransferRemediation
                                .erasureMarkerMayBeWritten(
                                        TransferRemediation
                                                .ERASURE_STAGE_SEED_ERASED,
                                        false, true)
                        && !TransferRemediation
                                .erasureMarkerMayBeWritten(
                                        TransferRemediation
                                                .ERASURE_STAGE_COMPLETE,
                                        true, false)
                        && !TransferRemediation
                                .erasureMarkerMayBeWritten(
                                        TransferRemediation
                                                .ERASURE_STAGE_PENDING,
                                        false, false)
                        && !TransferRemediation
                                .erasureMarkerMayBeWritten(
                                        TransferRemediation
                                                .ERASURE_STAGE_UNKNOWN,
                                        false, false));
    }

    /** Audit L17 (parse level): the erasure record's
     *  parser. A torn record is a parse FAILURE the
     *  companion's reader now records durably (audit L17)
     *  — never the silent "no record" round 3 returned,
     *  which froze the stage machine mid-erasure with the
     *  seed possibly gone and the marker unwritten. */
    private static void testL17ErasureRecordParse()
            throws Exception {
        Pair p = newPair(false);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", "quantus-depin-home-seed-erasure");
        m.put("device_id", p.deviceId);
        m.put("generation", 2L);
        m.put("stage", "seed_erased");
        m.put("wall_unix", 1762003600L);
        byte[] good = TransferCodec.jcs(m);
        TransferRemediation.ErasureRecordData d =
                TransferRemediation.parseErasureRecord(good);
        check("l17: a well-formed record parses",
                d != null && p.deviceId.equals(d.deviceId)
                        && d.generation == 2L
                        && "seed_erased".equals(d.stage));
        check("l17: a torn record is a parse failure, not "
                        + "a record",
                TransferRemediation.parseErasureRecord(
                        Arrays.copyOf(good, good.length / 2))
                        == null);
        Map<String, Object> noStage = new LinkedHashMap<>(m);
        noStage.remove("stage");
        check("l17: a record without a stage is a parse "
                        + "failure",
                TransferRemediation.parseErasureRecord(
                        TransferCodec.jcs(noStage)) == null);
        Map<String, Object> noId = new LinkedHashMap<>(m);
        noId.remove("device_id");
        check("l17: a record without an identity is a "
                        + "parse failure",
                TransferRemediation.parseErasureRecord(
                        TransferCodec.jcs(noId)) == null);
        check("l17: absent content is a parse failure",
                TransferRemediation.parseErasureRecord(null)
                        == null
                        && TransferRemediation
                                .parseErasureRecord(new byte[0])
                        == null);
    }

    /** Remove a trial's completion entries from the
     *  promotion journal (a rewound-state fabrication aid). */
    private static void dropCompletionEntries(File storeRoot,
                                              String trialId)
            throws Exception {
        File pj = TransferStore.promotionJournal(storeRoot);
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> e
                : TransferIo.readFramed(pj).entries) {
            if (!("complete".equals(e.get("kind"))
                    && trialId.equals(e.get("trial_id")))) {
                kept.add(e);
            }
        }
        Files.delete(pj.toPath());
        for (Map<String, Object> e : kept) {
            TransferIo.appendFramed(pj, e);
        }
    }

    /** Rewind a confirmed generation to the state a crash
     *  between plan adoption and materialisation leaves:
     *  plan adopted, judgement undecided, no closure, no
     *  completion entries, no active binding. The vault is
     *  the caller's to adjust (the crash window's point is
     *  exactly what it does or does not hold). */
    private static void rewindToAdoptedUndecided(
            File storeRoot, String deviceId, long generation)
            throws Exception {
        TransferState st = TransferState.load(storeRoot);
        Map<String, Object> rec = st.receiver(deviceId, generation);
        rec.remove("closure");
        rec.remove("r2");
        Map<String, Object> trial =
                TransferState.child(rec, "trial");
        Map<String, Object> jud = new LinkedHashMap<>();
        jud.put("kind", "undecided");
        trial.put("judgement", jud);
        trial.put("stage", "planned");
        st.doc().remove("active");
        st.save();
        dropCompletionEntries(storeRoot,
                TransferState.stringOr(trial, "trial_id", null));
    }

    private static boolean notified(Pair store, String deviceId,
                                    long generation)
            throws Exception {
        Map<String, Object> rec = TransferState.load(
                store.receiverRoot).receiver(deviceId, generation);
        return rec != null && TransferState.boolOr(
                rec, "notified_r1", false);
    }

    /** The adopted plan a promotion journal holds for an
     *  identity (null when none). */
    private static Map<String, Object> planForIdentity(
            File storeRoot, String deviceId) throws Exception {
        for (Map<String, Object> e : TransferIo.readFramed(
                TransferStore.promotionJournal(storeRoot))
                .entries) {
            if ("plan".equals(e.get("kind"))) {
                Map<String, Object> plan =
                        TransferState.child(e, "plan");
                if (plan != null && deviceId.equals(
                        TransferState.stringOr(
                                plan, "seed_device_id", null))) {
                    return plan;
                }
            }
        }
        return null;
    }

    /** Write a notified R1 record for a staged intake the
     *  way completeIntake would have — the fabrication aid
     *  for the two-notified state round 6's entrance check
     *  no longer lets two identities reach concurrently. */
    private static void fabricateNotified(
            File storeRoot, TransferReceiver.IntakeResult in)
            throws Exception {
        File root = TransferStore.stagingRoot(storeRoot,
                in.deviceId, in.generation, in.packageSha256);
        TransferPackage.Envelope env =
                TransferPackage.parseEnvelope(TransferIo.readAll(
                        TransferStore.stagingPackageFile(root),
                        TransferPackage.MAX_PACKAGE_BYTES));
        TransferState st = TransferState.load(storeRoot);
        Map<String, Object> rec = st.receiverOrCreate(
                in.deviceId, in.generation);
        rec.put("package_sha256", in.packageSha256);
        rec.put("staging_root", TransferStore.STAGING_AREA_NAME
                + "/" + in.deviceId + "/" + in.generation
                + "/" + in.packageSha256);
        rec.put("manifest", env.manifest.raw);
        rec.put("notified_r1", true);
        Map<String, Object> none = new LinkedHashMap<>();
        none.put("transfer_generation", in.generation);
        none.put("package_sha256", in.packageSha256);
        none.put("receiver_install_id", st.installId());
        rec.put("intake_none", none);
        rec.put("intake_history", new ArrayList<>());
        st.save();
        TransferStore.StagingRecord sr =
                TransferStore.loadStaging(root);
        sr.notified = true;
        TransferStore.saveStaging(root, sr);
    }

    /** Add a started, undecided, plan-less one-shot trial
     *  to a notified intake — the state a crash just after
     *  the trial-start commit leaves, whose re-entry is
     *  recovery's re-plan through planAndPromote. */
    private static void fabricateStartedTrial(
            File storeRoot, TransferReceiver.IntakeResult in)
            throws Exception {
        File root = TransferStore.stagingRoot(storeRoot,
                in.deviceId, in.generation, in.packageSha256);
        TransferState st = TransferState.load(storeRoot);
        Map<String, Object> rec = st.receiver(
                in.deviceId, in.generation);
        Map<String, Object> trial = new LinkedHashMap<>();
        trial.put("trial_id", in.generation + "-1");
        trial.put("generation", in.generation);
        trial.put("package_sha256", in.packageSha256);
        trial.put("stage", "started");
        trial.put("mode", "oneshot");
        Map<String, Object> judgement = new LinkedHashMap<>();
        judgement.put("kind", "undecided");
        trial.put("judgement", judgement);
        rec.put("trial", trial);
        st.save();
        TransferStore.StagingRecord sr =
                TransferStore.loadStaging(root);
        Map<String, Object> attempt = new LinkedHashMap<>();
        attempt.put("attempt_id", in.generation + "-1");
        attempt.put("generation", in.generation);
        attempt.put("stage_summary", "started");
        sr.currentAttempt = attempt;
        sr.nextAttempt = 2;
        TransferStore.saveStaging(root, sr);
    }

    /** A standalone sender fixture on an arbitrary seed
     *  (the F10/F11 pattern), for multi-identity stores. */
    private static TransferSender standaloneSender(
            String name, byte[] seed, Pair store)
            throws Exception {
        File root = tmpDir(name);
        writeJournal(root, seed, 3);
        TransferVault vault = new TransferVault(
                root, testWrapKey());
        TransferInit.initialise(root, vault,
                TransferInit.MODE_NEW, seed, RANDOM);
        return new TransferSender(root, vault, RANDOM,
                "test-app", new TestFreeze(), store.receiverFin,
                store.clock, 24L * 3600, 1000, 100);
    }

    private static byte[] thirdSeed() {
        byte[] seed = new byte[32];
        for (int i = 0; i < 32; i++) seed[i] = (byte) (0x70 + i);
        return seed;
    }

    /** Audit M14 (b): step 8-2's placement existed only
     *  inside planAndPromote — whose plan_adopted branch no
     *  caller can reach — while the actual re-entry for an
     *  adopted plan (recoverUndecidedTrial) promoted with
     *  no placement and no proof. A crash between the
     *  adoption save and the vault put therefore recovered
     *  into a committed generation with no seed in the
     *  vault, and a slot that changed hands before the
     *  confirmation recovered over a foreign key. The
     *  placement now precedes every executePromotion on
     *  the undecided route. */
    private static void testM14RecoverySeedPlacement()
            throws Exception {
        // ---- shape (i): crash between adoption and the
        // vault put; recovery places the seed first ----
        Pair p = newPair(false);
        testConfirmOnly(p);
        p.receiverVault.delete(TransferVault.receiverKey(1));
        rewindToAdoptedUndecided(p.receiverRoot, p.deviceId, 1);
        List<String> notes = p.newReceiver().recover();
        Map<String, Object> recAfter = TransferState.load(
                p.receiverRoot).receiver(p.deviceId, 1);
        check("m14: recovery completes the rewound trial",
                TransferState.child(recAfter, "closure") != null);
        byte[] placed = p.receiverVault.get(
                TransferVault.receiverKey(1));
        check("m14: recovery placed the seed before "
                        + "promoting (no seedless committed "
                        + "generation)",
                placed != null && Arrays.equals(placed, p.seed));
        if (placed != null) {
            java.util.Arrays.fill(placed, (byte) 0);
        }
        check("m14: the resume is noted",
                notes.stream().anyMatch(
                        n -> n.contains("resumed and judged")));

        // ---- shape (ii): the slot changed to a foreign
        // lineage before the confirmation; recovery stops --
        Pair q = newPair(false);
        testConfirmOnly(q);
        byte[] foreign = otherSeed();
        q.receiverVault.put(TransferVault.receiverKey(1),
                foreign);
        rewindToAdoptedUndecided(q.receiverRoot, q.deviceId, 1);
        check("m14: recovery stops when the slot changed "
                        + "to a foreign lineage before "
                        + "confirmation",
                throwsType(() -> q.newReceiver().recover(),
                        TransferReceiver.InconsistencyException.class));
        Map<String, Object> qRec = TransferState.load(
                q.receiverRoot).receiver(q.deviceId, 1);
        check("m14: the stopped recovery committed nothing",
                TransferState.child(qRec, "closure") == null);
        byte[] survivor = q.receiverVault.get(
                TransferVault.receiverKey(1));
        check("m14: the foreign copy is intact",
                survivor != null
                        && Arrays.equals(survivor, foreign));
        if (survivor != null) {
            java.util.Arrays.fill(survivor, (byte) 0);
        }
    }

    /** Audit M14 (a), entrance half: two identities could
     *  both be notified for the same generation while the
     *  slot was still empty (stagePackage's duplicate
     *  checks are per-identity), and the second promotion
     *  then raced the first one's placement. completeIntake
     *  now holds a second identity's intake while the
     *  first is un-closed; once the first closes, the
     *  refusal the second meets is the slot's own F11
     *  refusal, in the corrected "cannot be placed while"
     *  wording (the staging stays un-notified and
     *  retryable, so "never" was the wrong word). */
    private static void testM14ConcurrentIntake()
            throws Exception {
        Pair store = newPair(false);
        byte[] seedY = otherSeed();
        String deviceY = DepinCore.deviceIdFromPubkey(
                Ed25519.publickeyFromSeed(seedY));
        TransferSender ySender = standaloneSender(
                "m14-y-sender", seedY, store);
        byte[] seedZ = thirdSeed();
        String deviceZ = DepinCore.deviceIdFromPubkey(
                Ed25519.publickeyFromSeed(seedZ));
        TransferSender zSender = standaloneSender(
                "m14-z-sender", seedZ, store);
        TransferSender.ExportResult yEx =
                ySender.exportPackageOneshot(seedY);
        TransferReceiver.IntakeResult yIn =
                store.receiver.intakePackage(yEx.packageFile,
                        yEx.transferCode);
        store.receiver.completeIntake(yIn.deviceId,
                yIn.generation, yIn.packageSha256);
        check("m14: the first intake at the generation "
                        + "notifies",
                notified(store, deviceY, 1));
        TransferSender.ExportResult zEx =
                zSender.exportPackageOneshot(seedZ);
        // intakePackage stages and then completes, so the
        // hold surfaces from the intake itself; the staging
        // stays in place for the retry below.
        check("m14: a second identity's intake at the same "
                        + "generation is held while the first "
                        + "is open",
                throwsType(() -> store.receiver.intakePackage(
                                zEx.packageFile, zEx.transferCode),
                        TransferReceiver.HoldException.class));
        check("m14: the held intake recorded no notified R1",
                !notified(store, deviceZ, 1));
        store.receiver.confirmOneshot(deviceY, 1);
        String refusal = null;
        try {
            store.receiver.continueIntake(deviceZ, 1,
                    zEx.packageSha256);
        } catch (TransferReceiver.HoldException e) {
            // Audit L24: the F11 refusal is a hold since
            // round 7 — the corrected "can be completed
            // later" wording and the exception type now
            // agree (the error report classifies by type
            // alone, so an InconsistencyException here
            // would surface this wait as "inconsistent").
            refusal = e.getMessage();
        }
        check("m14: after the first intake closes, the "
                        + "slot refusal is a hold in the corrected "
                        + "wording (L24)",
                refusal != null && refusal.contains(
                        "cannot be placed while the slot is "
                                + "held"));
        check("m14: the refused intake still recorded no "
                        + "notified R1",
                !notified(store, deviceZ, 1));
    }

    /** Audit M14 (a), promotion half: with two identities
     *  notified for one generation (a state an older build
     *  could leave; fabricated here because round 6's
     *  entrance check closes it), the second promotion
     *  used to freeze, quarantine the first identity's
     *  live placement and adopt its plan before step 8-2
     *  stopped it. The slot proof now opens planAndPromote:
     *  a hold, with nothing moved and nothing adopted. */
    private static void testM14PlanPromoteTopCheck()
            throws Exception {
        Pair store = newPair(false);
        byte[] seedY = otherSeed();
        String deviceY = DepinCore.deviceIdFromPubkey(
                Ed25519.publickeyFromSeed(seedY));
        TransferSender ySender = standaloneSender(
                "m14t-y-sender", seedY, store);
        byte[] seedZ = thirdSeed();
        String deviceZ = DepinCore.deviceIdFromPubkey(
                Ed25519.publickeyFromSeed(seedZ));
        TransferSender zSender = standaloneSender(
                "m14t-z-sender", seedZ, store);
        // Z goes first and fully confirms: its key occupies
        // the slot and its journal is live.
        TransferSender.ExportResult zEx =
                zSender.exportPackageOneshot(seedZ);
        TransferReceiver.IntakeResult zIn =
                store.receiver.intakePackage(zEx.packageFile,
                        zEx.transferCode);
        store.receiver.completeIntake(zIn.deviceId,
                zIn.generation, zIn.packageSha256);
        store.receiver.confirmOneshot(deviceZ, 1);
        // Y is staged only (stagePackage does not complete
        // the intake); its notified record is fabricated, as
        // in the F11 test.
        TransferSender.ExportResult yEx =
                ySender.exportPackageOneshot(seedY);
        TransferReceiver.IntakeResult yIn =
                store.receiver.stagePackage(yEx.packageFile,
                        yEx.transferCode);
        fabricateNotified(store.receiverRoot, yIn);
        fabricateStartedTrial(store.receiverRoot, yIn);
        // Production installs a foreign-writer freeze hook
        // (DepinTransferRuntime); without one, the
        // quarantine's own hold would mask the ordering
        // this test pins. With one installed, an unordered
        // planAndPromote would freeze the live writer,
        // quarantine its placement and adopt the plan
        // before the slot proof stopped it.
        TestForeignFreeze freeze = new TestForeignFreeze();
        store.receiver.setForeignWriterFreeze(freeze);
        Map<String, String> liveBefore =
                TransferStore.snapshotLive(store.receiverRoot,
                        "existing", 0).files;
        // The re-entry route exercised here is recovery: a
        // one-shot confirm never reaches planAndPromote in
        // this shape (the E8 guard holds it earlier, before
        // any trial exists), while recovery re-plans a
        // started trial through planAndPromote itself. The
        // hold may surface as the exception or as a recovery
        // stop; what M14 (a) requires is that nothing moved
        // and nothing was adopted either way.
        try {
            store.receiver.recover();
        } catch (TransferReceiver.HoldException held) {
            // Surfaced as the hold itself; the invariants
            // below are the substance.
        }
        check("m14: the held promotion froze no writer",
                freeze.freezes == 0);
        Map<String, Object> yRec = TransferState.load(
                store.receiverRoot).receiver(deviceY, 1);
        Map<String, Object> yTrial =
                TransferState.child(yRec, "trial");
        check("m14: no plan was adopted for the held "
                        + "promotion",
                yTrial != null && !TransferState.boolOr(
                        yTrial, "plan_adopted", false)
                        && planForIdentity(store.receiverRoot,
                                deviceY) == null);
        check("m14: the held promotion quarantined nothing",
                TransferState.child(
                        yRec, "foreign_quarantine") == null);
        check("m14: the held promotion judged nothing",
                TransferState.child(yRec, "closure") == null
                        && yTrial != null
                        && "undecided".equals(TransferState
                                .stringOr(TransferState.child(
                                        yTrial, "judgement"),
                                        "kind", null)));
        check("m14: the live placement is untouched",
                TransferStore.snapshotLive(store.receiverRoot,
                        "existing", 0).files.equals(liveBefore));
    }

    /** Audit L19: cancellation used to leave the key step
     *  8-2 placed in the slot, and M13's occupancy
     *  allowance read any other identity's record as a
     *  legitimate holder — so a cancelled identity's
     *  leftover key both lingered and was undetectable.
     *  Cancellation now removes the key it placed (proved,
     *  as the placement was), and the allowance only
     *  covers records that should hold the seed: committed
     *  with unexplained absence, or an adopted trial in
     *  progress. */
    private static void testL19CancelAndOccupancy()
            throws Exception {
        Pair p = newPair(false);   // identity X
        byte[] seedY = otherSeed();
        String deviceY = DepinCore.deviceIdFromPubkey(
                Ed25519.publickeyFromSeed(seedY));
        TransferSender ySender = standaloneSender(
                "l19-y-sender", seedY, p);
        // X arrives (one-shot), confirms, is sent onward
        // and closes: X's generation-1 absence is explained
        // and its own copy was erased at the closure.
        TransferSender.ExportResult ex =
                p.sender.exportPackageOneshot(p.seed);
        TransferReceiver.IntakeResult in =
                p.receiver.intakePackage(ex.packageFile,
                        ex.transferCode);
        p.receiver.completeIntake(in.deviceId, in.generation,
                in.packageSha256);
        p.receiver.confirmOneshot(p.deviceId, 1);
        TransferSender onward = new TransferSender(
                p.receiverRoot, p.receiverVault, RANDOM,
                "test-app", new TestFreeze(), p.receiverFin,
                p.clock, 24L * 3600, 1000, 100);
        TransferSender.ExportResult ex2 =
                onward.exportPackageOneshot(p.seed);
        Pair third = newPair(false);
        TransferReceiver.IntakeResult in2 =
                third.receiver.intakePackage(ex2.packageFile,
                        ex2.transferCode);
        third.receiver.completeIntake(in2.deviceId,
                in2.generation, in2.packageSha256);
        third.receiver.confirmOneshot(p.deviceId, 2);
        String code2 = TransferCommitCode.codeFor(
                TransferCommitCode.deriveKey(ex2.transferCode),
                in2.manifestHash);
        onward.closeOneshotHandover(p.deviceId, code2);
        // Y arrives strict at generation 1 and confirms;
        // its plan needed the seed (the slot was empty), so
        // step 8-2 placed Y's key.
        TransferSender.ExportResult yEx =
                ySender.exportPackage(seedY);
        TransferReceiver.IntakeResult yIn =
                p.receiver.intakePackage(yEx.packageFile,
                        yEx.transferCode);
        p.receiver.completeIntake(yIn.deviceId,
                yIn.generation, yIn.packageSha256);
        Map<String, Object> yNotice =
                p.receiver.buildNotice(deviceY, 1);
        Map<String, Object> yPermit =
                ySender.issuePermit(seedY, yNotice);
        p.receiver.intakePermit(yPermit);
        p.receiver.confirmTrial(deviceY, 1);
        check("l19: Y's confirmed trial placed its key",
                p.receiverVault.has(
                        TransferVault.receiverKey(1)));
        // Rewind Y to an adopted, undecided trial (the
        // state in which a cancellation is still possible)
        // and cancel it: the key the trial placed comes
        // back out of the slot.
        rewindToAdoptedUndecided(p.receiverRoot, deviceY, 1);
        p.receiver.cancelTrial(deviceY, 1);
        check("l19: cancellation removes the key the "
                        + "trial placed",
                !p.receiverVault.has(
                        TransferVault.receiverKey(1)));
        // A leftover key from before that removal (a store
        // written by the older build) must not read as a
        // legitimate occupancy for X: X's classification
        // stops on it, by name.
        p.receiverVault.put(TransferVault.receiverKey(1),
                seedY);
        String mismatch = null;
        try {
            p.newReceiver().recover();
        } catch (TransferReceiver.InconsistencyException e) {
            mismatch = e.getMessage();
        }
        check("l19: a cancelled identity's leftover key is "
                        + "not a legitimate occupancy",
                mismatch != null && mismatch.contains(
                        "does not derive the identity"));
    }

    /** Audit L18: the sweep's notes are classified at the
     *  source. Mutations (a vault copy swept, a staging
     *  wrap invalidated) are change notes; deliberate
     *  non-actions in a persisting state (a foreign vault
     *  copy left, an unprovable wrap left) are steady
     *  notes, so the app layers can persist the former
     *  unconditionally and deduplicate the latter instead
     *  of re-recording them on every run. The scenario is
     *  the F10/M13 collision: X superseded and explained,
     *  Y's copy in the shared slot. */
    private static void testL18SweepNoteClassification()
            throws Exception {
        Pair p = newPair(false);   // identity X
        byte[] seedY = otherSeed();
        String deviceY = DepinCore.deviceIdFromPubkey(
                Ed25519.publickeyFromSeed(seedY));
        TransferSender ySender = standaloneSender(
                "l18-y-sender", seedY, p);
        TransferSender.ExportResult ex =
                p.sender.exportPackageOneshot(p.seed);
        TransferReceiver.IntakeResult in =
                p.receiver.intakePackage(ex.packageFile,
                        ex.transferCode);
        p.receiver.completeIntake(in.deviceId, in.generation,
                in.packageSha256);
        p.receiver.confirmOneshot(p.deviceId, 1);
        TransferSender onward = new TransferSender(
                p.receiverRoot, p.receiverVault, RANDOM,
                "test-app", new TestFreeze(), p.receiverFin,
                p.clock, 24L * 3600, 1000, 100);
        TransferSender.ExportResult ex2 =
                onward.exportPackageOneshot(p.seed);
        Pair third = newPair(false);
        TransferReceiver.IntakeResult in2 =
                third.receiver.intakePackage(ex2.packageFile,
                        ex2.transferCode);
        third.receiver.completeIntake(in2.deviceId,
                in2.generation, in2.packageSha256);
        third.receiver.confirmOneshot(p.deviceId, 2);
        String code2 = TransferCommitCode.codeFor(
                TransferCommitCode.deriveKey(ex2.transferCode),
                in2.manifestHash);
        onward.closeOneshotHandover(p.deviceId, code2);
        TransferSender.ExportResult yEx =
                ySender.exportPackageOneshot(seedY);
        TransferReceiver.IntakeResult yIn =
                p.receiver.intakePackage(yEx.packageFile,
                        yEx.transferCode);
        p.receiver.completeIntake(yIn.deviceId,
                yIn.generation, yIn.packageSha256);
        p.receiver.confirmOneshot(deviceY, 1);
        TransferReceiver.SweepNotes first = p.newReceiver()
                .sweepSupersededSeedCopiesClassified();
        check("l18: the wrap invalidation is a change note",
                first.changes.stream().anyMatch(
                        n -> n.contains("M13")));
        check("l18: the foreign vault copy left in place "
                        + "is a steady note",
                first.steady.stream().anyMatch(
                        n -> n.contains("F10")));
        check("l18: no steady note is reported as a change",
                first.changes.stream().noneMatch(
                        n -> n.contains("left the vault copy")));
        TransferReceiver.SweepNotes second = p.newReceiver()
                .sweepSupersededSeedCopiesClassified();
        check("l18: a converged sweep reports no further "
                        + "changes",
                second.changes.isEmpty());
        check("l18: the steady state is still reported "
                        + "(the app layer's dedup decides)",
                second.steady.stream().anyMatch(
                        n -> n.contains("F10")));
    }

    /** Copy a whole directory tree (test fabrication). */
    private static void copyTree(File from, File to)
            throws Exception {
        List<java.nio.file.Path> paths = new ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> walk =
                     Files.walk(from.toPath())) {
            walk.forEach(paths::add);
        }
        for (java.nio.file.Path p : paths) {
            java.nio.file.Path dest = to.toPath().resolve(
                    from.toPath().relativize(p));
            if (Files.isDirectory(p)) {
                Files.createDirectories(dest);
            } else {
                Files.createDirectories(dest.getParent());
                Files.copy(p, dest,
                        java.nio.file.StandardCopyOption
                                .REPLACE_EXISTING);
            }
        }
    }

    /** Delete a whole directory tree (test fabrication). */
    private static void deleteTree(File root) throws Exception {
        if (!root.exists()) {
            return;
        }
        List<java.nio.file.Path> paths = new ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> walk =
                     Files.walk(root.toPath())) {
            walk.forEach(paths::add);
        }
        paths.sort((a, b) -> Integer.compare(
                b.getNameCount(), a.getNameCount()));
        for (java.nio.file.Path p : paths) {
            Files.delete(p);
        }
    }

    /** Audit L25 (2): the recovery RECONSTRUCTION branch
     *  (an unadopted plan entry in the promotion journal,
     *  verified against a canonical rebuild) used to adopt
     *  the plan first and meet the slot proof afterwards,
     *  inside placePromotedSeed — so a slot that changed
     *  lineage after the plan was written stopped the
     *  branch only after adoption ("adopt, then stop"),
     *  the ordering M14 (a) removed everywhere else. The
     *  branch now passes the same slot-lineage hold as
     *  planAndPromote BEFORE the adoption save.
     *
     *  Fabrication: the state is produced by a genuine
     *  recovery run (which appends the trial's real plan
     *  entry and promotes), then the store directory is
     *  restored from a snapshot taken just before that run
     *  and only the genuine plan entry is kept in the
     *  promotion journal — exactly the durable state a
     *  crash between the plan append and the adoption save
     *  leaves. A foreign key is then placed in the slot. */
    private static void testL25ReconstructionSlotGate()
            throws Exception {
        Pair p = newPair(false);   // identity X
        TransferSender.ExportResult ex =
                p.sender.exportPackageOneshot(p.seed);
        TransferReceiver.IntakeResult in =
                p.receiver.intakePackage(ex.packageFile,
                        ex.transferCode);
        p.receiver.completeIntake(in.deviceId, in.generation,
                in.packageSha256);
        fabricateStartedTrial(p.receiverRoot, in);
        // A genuine one-shot trial carries permit_hash
        // "oneshot" (confirmOneshot writes it); the
        // fabrication helper omits it because the M14 tests
        // never reach the judgement step.
        TransferState st0 = TransferState.load(p.receiverRoot);
        Map<String, Object> rec0 =
                st0.receiver(p.deviceId, 1);
        TransferState.child(rec0, "trial")
                .put("permit_hash", "oneshot");
        st0.save();
        File snapshot = tmpDir("l25-snapshot");
        deleteTree(snapshot);
        copyTree(p.receiverRoot, snapshot);
        // The genuine run: re-plans, adopts, promotes,
        // judges. Only its plan entry is kept.
        p.receiver.recover();
        Map<String, Object> planEntry = null;
        for (Map<String, Object> e : TransferIo.readFramed(
                TransferStore.promotionJournal(p.receiverRoot))
                .entries) {
            if ("plan".equals(e.get("kind"))
                    && "1-1".equals(e.get("trial_id"))) {
                planEntry = e;
            }
        }
        check("l25: the genuine run appended the trial's "
                        + "plan entry",
                planEntry != null);
        // Restore the pre-run store and give it the plan
        // entry — the crash window's durable state.
        deleteTree(p.receiverRoot);
        copyTree(snapshot, p.receiverRoot);
        TransferIo.appendFramed(
                TransferStore.promotionJournal(p.receiverRoot),
                planEntry);
        Map<String, Object> recBefore = TransferState.load(
                p.receiverRoot).receiver(p.deviceId, 1);
        Map<String, Object> trialBefore =
                TransferState.child(recBefore, "trial");
        check("l25: the fabricated state is an unadopted "
                        + "trial with a plan entry",
                trialBefore != null && !TransferState.boolOr(
                        trialBefore, "plan_adopted", false));
        // The slot changed lineage after the plan was
        // written.
        byte[] foreign = otherSeed();
        p.receiverVault.put(TransferVault.receiverKey(1),
                foreign);
        check("l25: recovery holds at the reconstruction "
                        + "branch when the slot changed lineage",
                throwsType(() -> p.newReceiver().recover(),
                        TransferReceiver.HoldException.class));
        Map<String, Object> recAfter = TransferState.load(
                p.receiverRoot).receiver(p.deviceId, 1);
        Map<String, Object> trialAfter =
                TransferState.child(recAfter, "trial");
        check("l25: the hold adopted nothing (no plan "
                        + "marker, no plan hash, no closure)",
                trialAfter != null && !TransferState.boolOr(
                        trialAfter, "plan_adopted", false)
                        && TransferState.stringOr(trialAfter,
                                "plan_hash", null) == null
                        && TransferState.child(
                                recAfter, "closure") == null);
        byte[] survivor = p.receiverVault.get(
                TransferVault.receiverKey(1));
        check("l25: the foreign copy is intact",
                survivor != null
                        && Arrays.equals(survivor, foreign));
        if (survivor != null) {
            java.util.Arrays.fill(survivor, (byte) 0);
        }
    }

    /** Audit L23: the crash log's read-back helpers behind
     *  the app layers' steady-note deduplication and the
     *  companion's "last recorded error" summary.
     *  messagesOfKind returns the recorded SET (round 6
     *  compared against only the last note, so a sweep with
     *  two steady notes re-recorded both on every run);
     *  lastEntryExcludingKinds keeps the sweep's entries —
     *  action records and steady notes alike, neither of
     *  which is an error — out of the error summary while
     *  leaving them in the log. */
    private static void testL23CrashLogReadBack()
            throws Exception {
        String text =
                "=== 2026-11-10 00:00:01 [real_error]\n"
                        + "java.io.IOException: boom\n"
                        + "=== 2026-11-10 00:00:02 "
                        + "[superseded_sweep_steady]\n"
                        + "gen 1: steady A\n"
                        + "=== 2026-11-10 00:00:03 "
                        + "[superseded_sweep_steady]\n"
                        + "gen 2: steady B\n"
                        + "=== 2026-11-10 00:00:04 "
                        + "[superseded_sweep]\n"
                        + "gen 1: swept a copy\n";
        List<String> steady = CrashLog.messagesOfKind(
                text, "superseded_sweep_steady");
        check("l23: messagesOfKind returns every recorded "
                        + "steady body, in order",
                steady.equals(List.of(
                        "gen 1: steady A", "gen 2: steady B")));
        check("l23: the changes kind does not cross-match "
                        + "the steady kind",
                CrashLog.messagesOfKind(text, "superseded_sweep")
                        .equals(List.of("gen 1: swept a copy")));
        check("l23: messagesOfKind of a missing kind / "
                        + "null text is empty",
                CrashLog.messagesOfKind(text, "no_such_kind")
                                .isEmpty()
                        && CrashLog.messagesOfKind(
                                null, "superseded_sweep_steady")
                                .isEmpty());
        // The wrappers' dedup, simulated exactly: the
        // recorded set absorbs a repeated [A, B] sweep
        // whole, and admits a genuinely new note once.
        java.util.Set<String> recorded =
                new java.util.HashSet<>(steady);
        check("l23: a repeated two-note steady set records "
                        + "nothing new (the round-6 defect)",
                !recorded.add("gen 1: steady A")
                        && !recorded.add("gen 2: steady B"));
        check("l23: a new steady note is admitted once",
                recorded.add("gen 3: steady C")
                        && !recorded.add("gen 3: steady C"));
        String lastError = CrashLog.lastEntryExcludingKinds(
                text, "superseded_sweep",
                "superseded_sweep_steady");
        check("l23: the error summary skips both sweep "
                        + "kinds to the last real error",
                lastError != null
                        && lastError.contains("boom")
                        && lastError.contains("[real_error]"));
        String onlySweep =
                "=== 2026-11-10 00:00:02 "
                        + "[superseded_sweep_steady]\n"
                        + "gen 1: steady A\n";
        check("l23: a log of only sweep entries has no "
                        + "error entry",
                CrashLog.lastEntryExcludingKinds(onlySweep,
                        "superseded_sweep",
                        "superseded_sweep_steady") == null
                        && CrashLog.lastEntryExcludingKinds(
                                null, "superseded_sweep")
                                == null);
    }

    private static int indexOfBytes(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static void collectFiles(File f, List<File> out) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) collectFiles(k, out);
            }
        } else {
            out.add(f);
        }
    }

    /** H-07 (final check round 3): the receiver's expansion
     *  of a transfer package used to write the decrypted
     *  payload — whose first entry is the seed — to a
     *  plaintext temp file, and a process death mid-expansion
     *  left it on disk (the .29 tree's recover() wiring
     *  gap meant the debris sweep that would have removed
     *  it was never called; see also testExpansionDebris).
     *  The expansion is in memory now and the plaintext
     *  file path is deleted: after a full intake, no file
     *  anywhere in the receiver store may contain the raw
     *  seed, and no plaintext temp name may exist. */
    private static void testH07NoPlaintextSeedInStaging()
            throws Exception {
        Pair p = newPair(true);
        TransferSender.ExportResult ex = p.sender.exportPackage(p.seed);
        p.receiver.intakePackage(ex.packageFile, ex.transferCode);
        List<File> files = new ArrayList<>();
        collectFiles(p.receiverRoot, files);
        boolean tmpFound = false;
        boolean seedFound = false;
        for (File f : files) {
            String n = f.getName();
            if (n.equals(".plaintext.tmp") || n.equals(".seed.tmp")
                    || n.equals(".secret.tmp")) {
                tmpFound = true;
            }
            if (indexOfBytes(Files.readAllBytes(f.toPath()),
                    p.seed) >= 0) {
                seedFound = true;
            }
        }
        check("h07: no plaintext temp files in the receiver store",
                !tmpFound);
        check("h07: the raw seed appears in no receiver file",
                !seedFound);
    }

    /** H-08 (final check round 3): a staged journal whose
     *  checkpoints carry a below-production sequential-chain
     *  iteration count was accepted as-is, so a package
     *  built with cheap chains verified as if it had paid
     *  the production cost. The receiver now refuses at
     *  verification time when the app layer has installed
     *  the production expectation (the harness journals use
     *  50 iterations). */
    private static void testH08IterationsGate() throws Exception {
        Pair p = newPair(false);
        TransferSender.ExportResult ex = p.sender.exportPackage(p.seed);
        TransferReceiver strict = p.newReceiver();
        strict.setExpectedIterations(DepinCore.PRODUCTION_ITERATIONS);
        check("h08: a cheap-chain journal is refused at staging",
                throwsAny(() -> strict.intakePackage(
                        ex.packageFile, ex.transferCode)));
        Map<String, Object> rec = TransferState.load(
                p.receiverRoot).receiver(p.deviceId, 1);
        check("h08: the refusal left nothing notified",
                rec == null || !TransferState.boolOr(
                        rec, "notified_r1", false));
        TransferReceiver matching = p.newReceiver();
        matching.setExpectedIterations(50);
        TransferReceiver.IntakeResult in = matching.intakePackage(
                ex.packageFile, ex.transferCode);
        check("h08: a journal meeting the expectation stages",
                in.checkpointCount == 6);
    }

    /** H-10 (final check round 3): staged verification
     *  re-ran the FULL signature-verifying parse at every
     *  section boundary, and the re-verification budget
     *  ledger never saw that CPU (the audit measured ~46%
     *  of the intake CPU outside the ledger). The staging
     *  record now remembers the SHA-256 of the journal and
     *  archives at the moment their signatures verified;
     *  unchanged bytes are re-checked with the light parse
     *  (structure, sequence, chain — no signatures), and
     *  any byte change falls back to the full parse, which
     *  still refuses a bad signature. */
    private static void testH10SigVerifiedCache() throws Exception {
        Pair p = newPair(true);
        TransferSender.ExportResult ex = p.sender.exportPackage(p.seed);
        TransferReceiver.IntakeResult in = p.receiver.intakePackage(
                ex.packageFile, ex.transferCode);
        File root = TransferStore.stagingRoot(p.receiverRoot,
                p.deviceId, in.generation, in.packageSha256);
        TransferStore.StagingRecord sr = TransferStore.loadStaging(root);
        File stagedJournal = TransferStore.stagingJournal(root);
        String sha = Hex.encode(DepinCore.sha256(
                Files.readAllBytes(stagedJournal.toPath())));
        check("h10: full verification records the sig-verified journal hash",
                sha.equals(sr.journalSigVerifiedSha256));
        check("h10: archive sig-verified hashes are recorded",
                sr.archiveSigVerifiedSha256 != null
                        && !sr.archiveSigVerifiedSha256.isEmpty());
        TransferReceiver.IntakeResult in2 =
                p.newReceiver().intakePackage(
                        ex.packageFile, ex.transferCode);
        check("h10: re-intake over verified staging succeeds",
                in2.checkpointCount == in.checkpointCount);
        // Flip one hex character inside the last entry's
        // signature: the bytes no longer match the recorded
        // hash, the full parse runs, and the signature check
        // refuses the staging.
        String js = new String(Files.readAllBytes(
                stagedJournal.toPath()), StandardCharsets.UTF_8);
        int idx = js.lastIndexOf("\"sig\":\"");
        int pos = idx + 7;
        char repl = js.charAt(pos) == 'a' ? 'b' : 'a';
        String tampered = js.substring(0, pos) + repl
                + js.substring(pos + 1);
        Files.write(stagedJournal.toPath(),
                tampered.getBytes(StandardCharsets.UTF_8));
        check("h10: a tampered staged journal is refused on re-verification",
                throwsAny(() -> p.newReceiver().intakePackage(
                        ex.packageFile, ex.transferCode)));
    }

    /** F-19 (final check round 1, carried into round 3's
     *  scope): a one-shot trial's permit_hash field carries
     *  the sentinel "oneshot", not a permit hash. Issuing
     *  the abandon response passed the sentinel onward and
     *  buildAbandon threw AFTER the trial was cancelled, so
     *  an interrupted one-shot generation could be neither
     *  answered nor re-confirmed and the sender stayed
     *  frozen. The one-shot answer is the NONE form. */
    private static void testF19OneshotAbandonNoneForm()
            throws Exception {
        Pair p = newPair(false);
        TransferSender.ExportResult ex =
                p.sender.exportPackageOneshot(p.seed);
        TransferReceiver.IntakeResult in = p.receiver.intakePackage(
                ex.packageFile, ex.transferCode);
        p.receiver.completeIntake(in.deviceId, in.generation,
                in.packageSha256);
        // The interrupted-confirm state: a one-shot trial
        // whose cancellation completed, judgement undecided,
        // sentinel in the permit_hash field.
        TransferState st = TransferState.load(p.receiverRoot);
        Map<String, Object> rec = st.receiver(p.deviceId, 1);
        Map<String, Object> trial = new LinkedHashMap<>();
        trial.put("trial_id", "1-1");
        trial.put("generation", 1L);
        trial.put("package_sha256", in.packageSha256);
        trial.put("stage", "cancel_complete");
        trial.put("mode", "oneshot");
        trial.put("permit_hash", "oneshot");
        Map<String, Object> jud = new LinkedHashMap<>();
        jud.put("kind", "undecided");
        trial.put("judgement", jud);
        rec.put("trial", trial);
        st.save();
        Map<String, Object> abandon =
                p.newReceiver().issueAbandon(p.deviceId, 1);
        check("f19: a one-shot abandon is issued in the NONE form",
                abandon != null
                        && !abandon.containsKey("permit_sha256"));
        p.sender.onAbandon(abandon);
        check("f19: the sender leaves S1 on the NONE-form answer",
                TransferState.load(p.senderRoot)
                        .sender(p.deviceId) == null);
    }

    /** F-06 (final check round 1): the writer gate read
     *  only the ACTIVE identity's own record, so while a
     *  promotion for another identity had adopted its plan
     *  (or sat mid-quarantine), the gate opened the active
     *  identity's writer over the half-moved store. The
     *  gate now scans every identity: any open displacement
     *  closes it with "promotion_in_progress". */
    private static void testF06WriterGateDisplacement()
            throws Exception {
        Pair p = newPair(false);
        check("f06: the gate opens for the holder with no promotion open",
                TransferInit.writerGate(p.senderRoot, true).allowed);
        TransferState st = TransferState.load(p.senderRoot);
        Map<String, Object> rec = st.receiverOrCreate(
                "qdp1-000000000000000000000000", 1);
        Map<String, Object> trial = new LinkedHashMap<>();
        trial.put("trial_id", "1-1");
        trial.put("generation", 1L);
        trial.put("stage", "planned");
        trial.put("plan_adopted", true);
        Map<String, Object> jud = new LinkedHashMap<>();
        jud.put("kind", "undecided");
        trial.put("judgement", jud);
        rec.put("trial", trial);
        st.save();
        TransferInit.Gate g = TransferInit.writerGate(
                p.senderRoot, true);
        check("f06: another identity's open promotion closes the gate",
                !g.allowed
                        && "promotion_in_progress".equals(g.reason));
    }

    /** F-07 (final check round 1): a judged-but-unmaterialised
     *  trial of an older generation, recovered after a newer
     *  generation committed, materialised OVER the newer
     *  commit — held_generation and the active binding
     *  rewound. Recovery now closes such a trial as
     *  superseded without materialising (the confirm
     *  entrances and the materialise choke point carry the
     *  same rule; the m14 shape pins the choke point's
     *  foreign-lineage half). */
    private static void testF07SupersededRecovery()
            throws Exception {
        Pair p = newPair(false);
        testConfirmOnly(p);
        // Rewind gen 1 to judged-but-unmaterialised, keeping
        // its REAL judgement, and record a newer committed
        // generation for the same identity.
        TransferState st = TransferState.load(p.receiverRoot);
        Map<String, Object> rec = st.receiver(p.deviceId, 1);
        rec.remove("closure");
        rec.remove("r2");
        Map<String, Object> trial =
                TransferState.child(rec, "trial");
        trial.put("stage", "planned");
        st.identity(p.deviceId).put(
                "max_committed_generation", 2L);
        Map<String, Object> rec2 = st.receiverOrCreate(
                p.deviceId, 2);
        Map<String, Object> closure2 = new LinkedHashMap<>();
        closure2.put("kind", "committed_r2");
        rec2.put("closure", closure2);
        st.save();
        p.receiverVault.put(TransferVault.receiverKey(2), p.seed);
        List<String> notes = p.newReceiver().recover();
        Map<String, Object> recAfter = TransferState.load(
                p.receiverRoot).receiver(p.deviceId, 1);
        Map<String, Object> trialAfter =
                TransferState.child(recAfter, "trial");
        check("f07: recovery closes the judged trial as superseded",
                trialAfter != null && "superseded".equals(
                        TransferState.stringOr(
                                trialAfter, "stage", null)));
        check("f07: the superseded generation is not re-materialised",
                TransferState.child(recAfter, "closure") == null);
        check("f07: the superseding is noted",
                notes.stream().anyMatch(
                        n -> n.contains("superseded")));
    }

    /** F-20 (i) (final check round 1): a crash after the
     *  LAST rename of the foreign-quarantine move phase but
     *  before the "quarantined" save leaves the entry
     *  "moving" with the live placement already empty —
     *  every anchored file already in the quarantine
     *  directory. That is a completed move whose record
     *  lagged; the next promotion must verify the anchored
     *  files in quarantine and finish the entry, not fall
     *  through to the move machinery (which would try to
     *  move files that are no longer live). */
    private static void testF20QuarantineLaggingRecord()
            throws Exception {
        ForeignPair p = newForeignPair();
        byte[] shadowJournal = Files.readAllBytes(
                TransferStore.journalFile(p.receiverRoot).toPath());
        // Anchor the shadow placement, then move it into
        // quarantine by hand — the crash window's end state.
        Map<String, String> anchor = TransferStore.snapshotLive(
                p.receiverRoot, "existing", 0).files;
        File qDir = TransferStore.shadowQuarantineDir(
                p.receiverRoot, p.shadowId);
        //noinspection ResultOfMethodCallIgnored
        qDir.mkdirs();
        for (String name : anchor.keySet()) {
            File target = new File(qDir, name);
            //noinspection ResultOfMethodCallIgnored
            target.getParentFile().mkdirs();
            Files.move(new File(p.receiverRoot, name).toPath(),
                    target.toPath());
        }
        TransferSender.ExportResult ex = p.sender.exportPackage(p.seed);
        TransferReceiver.IntakeResult in = p.receiver.intakePackage(
                ex.packageFile, ex.transferCode);
        check("f20: intake reaches R1 over the emptied placement",
                in.checkpointCount == 6);
        TransferState st = TransferState.load(p.receiverRoot);
        Map<String, Object> rec = st.receiver(p.deviceId, 1);
        Map<String, Object> fq = new LinkedHashMap<>();
        fq.put("device_id", p.shadowId);
        Map<String, Object> fqFiles = new LinkedHashMap<>();
        fqFiles.putAll(anchor);
        fq.put("files", fqFiles);
        fq.put("state", "moving");
        rec.put("foreign_quarantine", fq);
        st.save();
        Map<String, Object> notice = p.receiver.buildNotice(
                p.deviceId, 1);
        Map<String, Object> permit = p.sender.issuePermit(
                p.seed, notice);
        check("f20: permit commits",
                p.receiver.intakePermit(permit)
                        == TransferReceiver.PermitIntakeOutcome
                                .COMMITTED);
        p.newReceiver().confirmTrial(p.deviceId, 1);
        TransferState rState = TransferState.load(p.receiverRoot);
        Map<String, Object> recAfter = rState.receiver(
                p.deviceId, 1);
        check("f20: the promotion completes over the lagging record",
                TransferState.child(recAfter, "closure") != null);
        File qJournal = new File(qDir, JournalStore.FILE_NAME);
        check("f20: the shadow journal is intact in quarantine",
                qJournal.isFile() && Arrays.equals(shadowJournal,
                        Files.readAllBytes(qJournal.toPath())));
        byte[] senderJournal = Files.readAllBytes(
                TransferStore.journalFile(p.senderRoot).toPath());
        byte[] liveJournal = Files.readAllBytes(
                TransferStore.journalFile(p.receiverRoot).toPath());
        check("f20: live journal is the transferred journal",
                Arrays.equals(senderJournal, liveJournal));
    }

    public static void main(String[] args) throws Exception {
        testCodec();
        testWriterGate();
        testFullCycle(newPair(true));
        testLargeJournalCycle();
        testRejectionCorpus();
        testJudgementExpiryAndSecondPermit();
        testCancelBeforePermit();
        testAbandonFlows();
        testAbandonComposition();
        testSyncBoundaryFailure();
        testRecovery();
        testBudgetLedger();
        testReversePrefix();
        testForeignQuarantineCommit();
        testForeignQuarantineCancel();
        testGateAfterProductionShapedReceive();
        testOneshotCycle();
        testOneshotCancelAndStrictRefusal();
        testOneshotReexportAndTombstone();
        testOneshotRefusesForeignLive();
        testOneshotCrossModeGuards();
        testOneshotReturnAnchors();
        testOneshotLegacyCloseFallback();
        testOneshotF2NoFallbackWithMarker();
        testOneshotF3AdvancingClock();
        testF1SelfImportRefusal();
        testF5RecoveryTolerance();
        testM3MissingCommitKey();
        testM4SameIdentityFreeze();
        testTracesAndRemediation();
        testF6MarkerScope();
        testM8TraceConsistency();
        testF7CommitKeyRescueLedger();
        testF8ReceiverKeyErasureOnOnwardSend();
        testF9JournalPathDecision();
        testM11RescueVerifiesCode();
        testM10SupersededSweep();
        testF10SweepDerivationProof();
        testF11SlotLineageCheck();
        testSeedDerivesIdentityPredicate();
        testL13RescueSizeCap();
        testL15SigningSeedCheck();
        testL16ErasureStageMachine();
        testL17ErasureRecordParse();
        testM14RecoverySeedPlacement();
        testM14ConcurrentIntake();
        testM14PlanPromoteTopCheck();
        testL19CancelAndOccupancy();
        testL18SweepNoteClassification();
        testL25ReconstructionSlotGate();
        testL23CrashLogReadBack();
        testPromotionInconsistencyStop();
        testExpansionDebris();
        testH07NoPlaintextSeedInStaging();
        testH08IterationsGate();
        testH10SigVerifiedCache();
        testF19OneshotAbandonNoneForm();
        testF06WriterGateDisplacement();
        testF07SupersededRecovery();
        testF20QuarantineLaggingRecord();
        System.out.println("----");
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }
}
