package app.quantusnode.node.depin;

import app.quantusnode.node.depin.core.Reverification;
import app.quantusnode.node.depin.core.TransferSender;
import app.quantusnode.node.depin.core.TransferState;
import app.quantusnode.node.depin.core.TransferStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * State derivation for the device-transfer UI (transfer UI
 * consolidation): the single source the settings screen reads
 * to decide, per role, where the flow stands and what the ONE
 * main action should be.
 *
 * Everything here is derived from durable facts on disk — the
 * initialisation record, the sender / receiver records, the
 * unnotified stagings under the staging area, and the durable
 * inbox — never from in-memory UI state. That is the fix for
 * the first production attempt's failure: an intake interrupted
 * after staging (a section 3.5 verification pause, an Activity
 * recreation, a process death) left a durable unnotified
 * staging that no UI action could find or complete, because
 * completion depended on volatile Activity fields. The staging
 * area is therefore enumerated here, and an unnotified staging
 * outranks every record state: resuming it is always the next
 * action.
 *
 * Record resolution follows the transfer service's state-based
 * rule (audit F3-M2): an open record first, then an R2 record,
 * then a refusal closure, then any other closure; ties broken
 * by device id in sorted order, then that identity's highest
 * generation — generations are per-identity sequences and are
 * never compared across identities.
 *
 * This class is app-layer wiring only: it reads state, it never
 * mutates it, and it constructs no transfer machine.
 */
public final class TransferFlow {
    private TransferFlow() {}

    public enum ReceiverState {
        NOT_INTRODUCED, INTRODUCING, READY, STAGED_UNNOTIFIED,
        STAGED_NOTIFIED, PERMIT_IN, PERMIT_EXPIRED, TRIAL_CLOSED,
        CONFIRMED, DONE, CLOSED_REFUSAL, CLOSED_CANCEL, CLOSED_OTHER
    }

    public enum SenderState {
        NOT_INTRODUCED, INTRODUCING, READY, FROZEN_WAIT_NOTICE,
        FROZEN_NOTICE_IN, PERMIT_ISSUED, FROZEN_ONESHOT, DONE,
        CLOSED
    }

    /** One unnotified staging on disk: the resume target. */
    public static final class StagingRef {
        public String deviceId;
        public long generation;
        public String packageSha256;
        public File stagingRoot;
        /** The retained original package copy inside the root. */
        public File packageFile;
        /** checkpoint_count from the manifest: an UNVERIFIED
         *  claim until intake verification completes, so the
         *  display caps it at MAX_DISPLAY_CHECKPOINTS (E11).
         *  The verified count is the IntakeResult's, and stage
         *  turns a mismatch into an error. */
        public long checkpointCount;
        /** Sum of the manifest's archive checkpoint counts
         *  (E11: progress shows the two formats separately). */
        public long archiveCheckpointCount;
        /** Section 3.5 progress on this staging's journal: the
         *  durable resume cursor's verified count, or -1 when
         *  no cursor exists yet or it cannot be read. Display
         *  only — the state derivation never acts on it. */
        public long verifiedCount = -1;
    }

    /** The checkpoint ceiling for unverified manifest totals
     *  (E11): nothing the app can hold exceeds it — the same
     *  ceiling the bundle export enforces. Final check round
     *  4 (I-19): DERIVED from that ceiling. It was a literal
     *  100,000, which stopped being "the same ceiling" when
     *  the export cap was raised to the journal's own
     *  (F-25): a staged history between 100,001 and 200,000
     *  checkpoints then showed its total — and its verified
     *  count — clipped to 100,000. */
    public static final long MAX_DISPLAY_CHECKPOINTS =
            app.quantusnode.node.depin.core.BundleExport.MAX_CHECKPOINTS;

    public static final class Snapshot {
        public ReceiverState receiver = ReceiverState.NOT_INTRODUCED;
        public SenderState sender = SenderState.NOT_INTRODUCED;
        public String receiverDeviceId;
        public long receiverGeneration = -1;
        /** Package hash of the record the receiver state was
         *  derived from (the UI's export hints match on it). */
        public String receiverPackageSha256;
        /** The one-shot confirmation number (E4) of a completed
         *  one-shot intake, kept visible in the DONE state: the
         *  user reads it to the sender side, whose erasure
         *  requires it. Null otherwise. */
        public String receiverCommitCode;
        public String senderDeviceId;
        public long senderGeneration = -1;
        /** Generation of the completed (S2) send whose ack can
         *  be re-presented from history; -1 when none. */
        public long completedSenderGeneration = -1;
        /** The open sender record is a one-shot handover. */
        public boolean senderOneshot;
        /** Checkpoint count from the open one-shot sender
         *  record's manifest (for the frozen state text);
         *  -1 when unknown. */
        public long senderCheckpointCount = -1;
        public StagingRef unnotifiedStaging;
        /** The transfer state's active binding identity (the
         *  identity holding the live placement), or null when
         *  no binding exists. The settings screen compares it
         *  with the observer status's identity: when they
         *  disagree, the status describes a displaced identity
         *  and its binding must not be sent as a command
         *  expectation. */
        public String activeDeviceId;
        /** Audit M1: a staged/open receiver track names an
         *  identity DIFFERENT from the live one. E8 refuses
         *  the one-shot confirm in exactly this state, so the
         *  UI learns it HERE, at stage time, and does not
         *  offer the one-shot choice in the confirmation
         *  dialog at all — the strict path (which quarantines
         *  the foreign placement) is the way through. */
        public boolean oneshotBlockedByLiveIdentity;
        /** The store could not be read at all; the UI shows the
         *  failure instead of a derived state. */
        public boolean loadFailed;
    }

    public static Snapshot derive(File storeRoot) {
        Snapshot s = new Snapshot();
        TransferState.InitRecord init;
        try {
            init = TransferState.loadInit(storeRoot);
        } catch (Throwable t) {
            s.loadFailed = true;
            return s;
        }
        if (init == null) {
            return s; // both roles NOT_INTRODUCED
        }
        if (!"complete".equals(init.state)) {
            // An introduction interrupted partway (the record is
            // saved in "preparing" state before the work runs).
            // The next operation resumes it — the service's
            // ensureInitialised now keys on completeness, not
            // existence — so the UI offers the flow's first
            // action rather than a dead end.
            s.receiver = ReceiverState.INTRODUCING;
            s.sender = SenderState.INTRODUCING;
            return s;
        }
        TransferState st;
        try {
            st = TransferState.load(storeRoot);
        } catch (Throwable t) {
            s.loadFailed = true;
            return s;
        }
        Map<String, Object> active = st.active();
        s.activeDeviceId = active == null ? null
                : TransferState.stringOr(active, "device_id", null);
        s.unnotifiedStaging = findUnnotifiedStaging(storeRoot, st);
        classifyReceiver(st, s);
        classifySender(storeRoot, st, s);
        if (s.unnotifiedStaging != null) {
            // An interrupted intake outranks every record state:
            // completing (or discarding) it is the next action.
            s.receiver = ReceiverState.STAGED_UNNOTIFIED;
            s.receiverDeviceId = s.unnotifiedStaging.deviceId;
            s.receiverGeneration = s.unnotifiedStaging.generation;
        }
        if (s.activeDeviceId != null && s.receiverDeviceId != null
                && !s.activeDeviceId.equals(s.receiverDeviceId)) {
            s.oneshotBlockedByLiveIdentity = true;
        }
        return s;
    }

    // ---------------- receiver ----------------

    private static void classifyReceiver(TransferState st, Snapshot s) {
        List<String> ids = new ArrayList<>(st.identities().keySet());
        Collections.sort(ids);
        // Pass order mirrors the service's REF chains, extended
        // with the display/terminal classes: open, R2, refusal,
        // cancel, any other closure.
        for (int pass = 0; pass < 5; pass++) {
            for (String deviceId : ids) {
                List<Long> gens = st.receiverGenerations(deviceId);
                for (int i = gens.size() - 1; i >= 0; i--) {
                    Map<String, Object> rec =
                            st.receiver(deviceId, gens.get(i));
                    if (rec == null) {
                        continue;
                    }
                    Map<String, Object> closure =
                            TransferState.child(rec, "closure");
                    String kind = closure == null ? null
                            : TransferState.stringOr(
                                    closure, "kind", null);
                    ReceiverState state = null;
                    if (pass == 0 && closure == null) {
                        Map<String, Object> trial =
                                TransferState.child(rec, "trial");
                        if (trial == null) {
                            state = committedPermitState(
                                    st, deviceId, rec);
                        } else {
                            String stage = TransferState.stringOr(
                                    trial, "stage", null);
                            state = isAbandonTerminal(stage)
                                    ? ReceiverState.TRIAL_CLOSED
                                    : ReceiverState.PERMIT_IN;
                        }
                    } else if (pass == 1 && rec.get("r2") != null) {
                        // A one-shot completion needs no ack:
                        // it is DONE at once.
                        state = (TransferState.boolOr(
                                rec, "ack_received", false)
                                || "committed_oneshot".equals(kind))
                                ? ReceiverState.DONE
                                : ReceiverState.CONFIRMED;
                    } else if (pass == 2
                            && "refusal".equals(kind)) {
                        state = ReceiverState.CLOSED_REFUSAL;
                    } else if (pass == 3
                            && "cancel_token".equals(kind)) {
                        state = ReceiverState.CLOSED_CANCEL;
                    } else if (pass == 4 && closure != null) {
                        state = ReceiverState.CLOSED_OTHER;
                    }
                    if (state != null) {
                        s.receiver = state;
                        s.receiverDeviceId = deviceId;
                        s.receiverGeneration = gens.get(i);
                        s.receiverPackageSha256 = TransferState.stringOr(
                                rec, "package_sha256", null);
                        s.receiverCommitCode = (pass == 1
                                && "committed_oneshot".equals(kind))
                                ? TransferState.stringOr(
                                        rec, "commit_code", null)
                                : null;
                        return;
                    }
                }
            }
        }
        s.receiver = ReceiverState.READY;
    }

    /** Classification of an open, notified record that has no
     *  trial yet, derived from its intake history — the durable
     *  record of step 7 (intakePermit commits the permit there
     *  and in the staging record; a trial is only created
     *  later, by the confirm). A committed permit whose expiry
     *  has not been observed (no latch under its full-value
     *  hash in the identity's latch record) is a permit in
     *  hand: PERMIT_IN, exactly as if the trial already
     *  existed. (This is the fix for the consolidation's
     *  deadlock: with only the trial recognised, an intaken
     *  permit left the derivation at STAGED_NOTIFIED forever,
     *  and the notice-export session hint kept offering the
     *  permit intake — whose re-run is a silent
     *  ALREADY_COMMITTED — instead of the confirm.) If every
     *  committed permit has been latched, the flow waits for a
     *  fresh permit from the sender: PERMIT_EXPIRED. A permit
     *  that silently expired without an observation is NOT
     *  latched and still classifies PERMIT_IN — the confirm's
     *  own expiry judgement is authoritative for it. */
    private static ReceiverState committedPermitState(
            TransferState st, String deviceId,
            Map<String, Object> rec) {
        boolean anyCommitted = false;
        Object v = rec.get("intake_history");
        if (v instanceof List) {
            for (Object o : (List<?>) v) {
                if (!(o instanceof Map)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> e = (Map<String, Object>) o;
                if (!"committed".equals(e.get("state"))) {
                    continue;
                }
                Object h = e.get("hash");
                if (!(h instanceof String)) {
                    continue;
                }
                anyCommitted = true;
                if (!st.hasLatch(deviceId, (String) h)) {
                    return ReceiverState.PERMIT_IN;
                }
            }
        }
        return anyCommitted
                ? ReceiverState.PERMIT_EXPIRED
                : ReceiverState.STAGED_NOTIFIED;
    }

    /** Trial stages after which the trial can no longer be
     *  confirmed and the cannot-confirm response is the way
     *  forward. ("confirmed" is deliberately absent: a trial
     *  latched confirmed with no R2 closure yet is a confirm
     *  caught mid-promotion, and re-issuing the confirm resumes
     *  it.) */
    private static boolean isAbandonTerminal(String stage) {
        return "cancel_complete".equals(stage)
                || "start_blocked".equals(stage)
                || "orphaned".equals(stage)
                || "refused".equals(stage);
    }

    // ---------------- sender ----------------

    private static void classifySender(File storeRoot, TransferState st,
                                       Snapshot s) {
        List<String> ids = new ArrayList<>(st.identities().keySet());
        Collections.sort(ids);
        // Audit M7: the ACTIVE identity's open record wins.
        // Leftover S1 records of other identities (a shadow's
        // practice track is the production example) otherwise
        // hijacked the classification whenever their id sorted
        // first, and the UI described — and offered actions
        // for — the wrong track.
        if (s.activeDeviceId != null && ids.remove(s.activeDeviceId)) {
            ids.add(0, s.activeDeviceId);
        }
        for (String deviceId : ids) {
            Map<String, Object> sender = st.sender(deviceId);
            if (sender == null) {
                continue;
            }
            s.senderDeviceId = deviceId;
            s.senderGeneration =
                    TransferState.longOr(sender, "generation", -1);
            if ("oneshot".equals(TransferState.stringOr(
                    sender, "mode", null))) {
                // A one-shot handover never issues permits and
                // never takes a notice in: it has its own frozen
                // state, whose actions are the seed erasure and
                // the cancellation.
                s.sender = SenderState.FROZEN_ONESHOT;
                s.senderOneshot = true;
                Map<String, Object> mf =
                        TransferState.child(sender, "manifest");
                s.senderCheckpointCount = mf == null ? -1
                        : TransferState.longOr(
                                mf, "checkpoint_count", -1);
                return;
            }
            boolean issued = !TransferSender.issuedPermitHashes(sender)
                    .isEmpty();
            if (issued) {
                s.sender = SenderState.PERMIT_ISSUED;
                return;
            }
            boolean noticeIn = TransferState.stringOr(
                    sender, "dest_install_id", null) != null
                    || new File(new File(storeRoot, "transfer-io"),
                            "notice.json").exists();
            s.sender = noticeIn
                    ? SenderState.FROZEN_NOTICE_IN
                    : SenderState.FROZEN_WAIT_NOTICE;
            return;
        }
        // No open sender record: a completed send (S2 in the
        // history) outranks a cancelled one (S0), matching the
        // status the apps have always shown.
        boolean anyHistory = false;
        for (String deviceId : ids) {
            Map<String, Object> idn = st.identity(deviceId);
            Object hist = idn == null ? null : idn.get("sender_history");
            if (!(hist instanceof List)) {
                continue;
            }
            long bestS2 = -1;
            boolean anyS2 = false;
            for (Object o : (List<?>) hist) {
                if (o instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> e = (Map<String, Object>) o;
                    anyHistory = true;
                    if ("S2".equals(e.get("state"))) {
                        // Any S2 — strict or one-shot — means
                        // the identity left this device: DONE.
                        anyS2 = true;
                        // But only a strict closure has an ack
                        // to re-present, so only it counts
                        // toward completedSenderGeneration.
                        if (!"oneshot".equals(
                                TransferState.stringOr(
                                        e, "mode", null))) {
                            bestS2 = Math.max(bestS2,
                                    TransferState.longOr(
                                            e, "generation", -1));
                        }
                    }
                }
            }
            if (anyS2 && s.sender != SenderState.DONE) {
                s.sender = SenderState.DONE;
                s.senderDeviceId = deviceId;
                if (bestS2 >= 0) {
                    s.completedSenderGeneration = bestS2;
                }
            }
        }
        if (s.sender == SenderState.DONE) {
            return;
        }
        s.sender = anyHistory ? SenderState.CLOSED : SenderState.READY;
    }

    // ---------------- staging enumeration ----------------

    /** The first unnotified staging on disk, in deterministic
     *  order (device id sorted, generation descending, package
     *  hash sorted), or null. A staging whose receiver record
     *  already carries notified_r1 is NOT unnotified — the
     *  staging mirror flag is written after the record, and a
     *  crash between the two must not resurrect a completed
     *  intake as a resumable one. */
    private static StagingRef findUnnotifiedStaging(File storeRoot,
                                                    TransferState st) {
        File area = TransferStore.stagingArea(storeRoot);
        File[] deviceDirs = area.listFiles();
        if (deviceDirs == null) {
            return null;
        }
        List<String> deviceIds = new ArrayList<>();
        for (File d : deviceDirs) {
            if (d.isDirectory()) {
                deviceIds.add(d.getName());
            }
        }
        Collections.sort(deviceIds);
        for (String deviceId : deviceIds) {
            File deviceDir = new File(area, deviceId);
            File[] genDirs = deviceDir.listFiles();
            if (genDirs == null) {
                continue;
            }
            List<Long> gens = new ArrayList<>();
            for (File g : genDirs) {
                if (!g.isDirectory()) {
                    continue;
                }
                try {
                    gens.add(Long.parseLong(g.getName()));
                } catch (NumberFormatException ignored) {
                    // not a generation directory; recovery's concern
                }
            }
            gens.sort(Collections.reverseOrder());
            for (long gen : gens) {
                File genDir = new File(deviceDir, String.valueOf(gen));
                File[] shaDirs = genDir.listFiles();
                if (shaDirs == null) {
                    continue;
                }
                List<String> shas = new ArrayList<>();
                for (File f : shaDirs) {
                    if (f.isDirectory()) {
                        shas.add(f.getName());
                    }
                }
                Collections.sort(shas);
                for (String sha : shas) {
                    File root = new File(genDir, sha);
                    TransferStore.StagingRecord sr;
                    try {
                        sr = TransferStore.loadStaging(root);
                    } catch (Throwable t) {
                        continue; // unreadable: recovery's concern
                    }
                    if (sr == null || sr.notified) {
                        continue;
                    }
                    Map<String, Object> rec =
                            st.receiver(sr.deviceId, sr.generation);
                    if (rec != null && TransferState.boolOr(
                            rec, "notified_r1", false)) {
                        continue;
                    }
                    StagingRef ref = new StagingRef();
                    ref.deviceId = sr.deviceId;
                    ref.generation = sr.generation;
                    ref.packageSha256 = sr.packageSha256;
                    ref.stagingRoot = root;
                    ref.packageFile =
                            TransferStore.stagingPackageFile(root);
                    long mfCount = sr.manifest == null ? -1
                            : TransferState.longOr(
                                    sr.manifest, "checkpoint_count", -1);
                    ref.checkpointCount = mfCount < 0 ? -1
                            : Math.min(mfCount, MAX_DISPLAY_CHECKPOINTS);
                    long archSum = 0;
                    if (sr.manifest != null) {
                        Object archives = sr.manifest.get("archives");
                        if (archives instanceof List) {
                            for (Object a : (List<?>) archives) {
                                if (a instanceof Map) {
                                    // Audit M2: the manifest's
                                    // archive entries carry the
                                    // key "count" (the sender's
                                    // manifest construction) —
                                    // this sum read
                                    // "checkpoint_count" and so
                                    // always totalled 0. Each
                                    // entry and the running sum
                                    // are capped as they go: the
                                    // counts are unverified
                                    // claims, and uncapped
                                    // addition could overflow
                                    // before the ceiling below
                                    // ever applied (audit Low).
                                    long c = TransferState.longOr(
                                            (Map<String, Object>) a,
                                            "count", 0);
                                    archSum += Math.max(0L, Math.min(
                                            c, MAX_DISPLAY_CHECKPOINTS));
                                    if (archSum
                                            > MAX_DISPLAY_CHECKPOINTS) {
                                        archSum =
                                                MAX_DISPLAY_CHECKPOINTS;
                                    }
                                }
                            }
                        }
                    }
                    ref.archiveCheckpointCount = Math.min(
                            archSum, MAX_DISPLAY_CHECKPOINTS);
                    try {
                        Reverification.Cursor cursor =
                                Reverification.Cursor.load(
                                        root, sr.packageSha256);
                        if (cursor != null) {
                            // Audit Low: the verified count is
                            // capped like the totals it is shown
                            // against — the raw cursor value
                            // could exceed both the display
                            // ceiling and the manifest total.
                            long v = cursor.verifiedCount;
                            if (v > MAX_DISPLAY_CHECKPOINTS) {
                                v = MAX_DISPLAY_CHECKPOINTS;
                            }
                            if (ref.checkpointCount >= 0
                                    && v > ref.checkpointCount) {
                                v = ref.checkpointCount;
                            }
                            ref.verifiedCount = v;
                        }
                    } catch (Throwable ignored) {
                        // Progress is display-only: an unreadable
                        // cursor leaves the count unknown rather
                        // than failing the whole derivation.
                    }
                    return ref;
                }
            }
        }
        return null;
    }
}
