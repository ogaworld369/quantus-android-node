package app.quantusnode.node.depin;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import app.quantusnode.node.depin.core.DepinCore;
import app.quantusnode.node.depin.core.Ed25519;
import app.quantusnode.node.depin.core.JournalFile;
import app.quantusnode.node.depin.core.Json;
import app.quantusnode.node.depin.core.RpcClient;
import app.quantusnode.node.depin.core.TransferAbandon;
import app.quantusnode.node.depin.core.TransferCodec;
import app.quantusnode.node.depin.core.TransferInit;
import app.quantusnode.node.depin.core.TransferIo;
import app.quantusnode.node.depin.core.TransferPackage;
import app.quantusnode.node.depin.core.TransferReceiver;
import app.quantusnode.node.depin.core.TransferSender;
import app.quantusnode.node.depin.core.TransferState;
import app.quantusnode.node.depin.core.TransferVault;
import app.quantusnode.node.depin.core.Reverification;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Map;

/**
 * Android-side wiring for the transfer machinery (integration
 * design section 4, build step 4) in the Node app: construction
 * of the core sender / receiver machines over files/depin (the
 * DePIN store root), the section 3.2 writer resolution that
 * DepinRuntime consults before building a writable journal, and
 * the durable inbox for message files (section 4.4 (b): an
 * input that cannot be processed yet is retained, never
 * discarded).
 *
 * Identity model: this app's own vault (AndroidSeedVault) holds
 * the shadow identity the integrated observer minted in build
 * step 2. A transferred-in identity's seed lives in the
 * transfer vault under its (generation, receiver) entry; the
 * writer resolution below decides which of the two may write,
 * following the design's order — transfer state, then the
 * active binding, then writer construction.
 *
 * Process placement (audit H-1, resolved): this class executes
 * ONLY inside the :depin process — hosted by
 * DepinTransferService for UI-requested operations, and by the
 * observer's own runtime for the writer resolution. The main
 * process reaches it exclusively through the DepinIpc transfer
 * protocol (DepinTransferClient); it never loads a seed, a
 * vault, or a transfer machine itself.
 */
public final class DepinTransferRuntime {
    private DepinTransferRuntime() {}

    /** Permit validity parameters (section 4.4; see the
     *  companion's TransferRuntime for the same constants). */
    public static final long PERMIT_VALIDITY_SEC = 24L * 3600;
    public static final long PERMIT_VALIDITY_BLOCKS = 7200;
    public static final long PERMIT_SAFETY_MARGIN_BLOCKS = 1000;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** The most recent export in this process (sender role),
     *  for the UI's one-time display of the transfer code. */
    public static volatile TransferSender.ExportResult lastExport;

    public static File storeRoot(Context context) {
        return DepinFiles.depinDir(context);
    }

    public static TransferVault vault(Context context) throws Exception {
        return new TransferVault(storeRoot(context),
                new AndroidSeedVault(context).wrapKeyForTransfer());
    }

    // ---------------- writer resolution (section 3.2) ----------------

    public static final class WriterResolution {
        public static final int SHADOW = 0;
        public static final int HELD = 1;
        public static final int RECEIVED = 2;
        public int kind;
        public String reason;
        public String deviceId;
        public long generation;
    }

    /**
     * Decide what DepinRuntime may construct:
     * SHADOW — no transfer initialisation exists, or the active
     * binding names the shadow identity itself (the step-2
     * behaviour, unchanged);
     * HELD — the transfer state forbids a writer (introduction
     * incomplete, S1 freeze, restoration pending, the identity
     * not switched on, …); the reason is the gate's;
     * RECEIVED — the binding names a transferred-in identity
     * whose seed is in the transfer vault; the journal must be
     * built under THAT identity.
     *
     * The explicit-ON flag is read from DepinSettings and must
     * be bound to the transfer-active identity itself: a shadow
     * identity's ON is never inherited by a received identity
     * (section 3.2 / R13-6).
     */
    public static WriterResolution resolveWriter(Context context,
                                                 String shadowDeviceId) {
        WriterResolution out = new WriterResolution();
        File root = storeRoot(context);
        TransferState.InitRecord init;
        try {
            init = TransferState.loadInit(root);
        } catch (Exception e) {
            out.kind = WriterResolution.HELD;
            out.reason = "transfer state unreadable; journal held: "
                    + e.getMessage();
            return out;
        }
        if (init == null) {
            // Audit F5: "no init record" means SHADOW only for
            // a store that has never seen the transfer
            // machinery. A store WITH transfer traces but no
            // init record is damaged, not fresh — resolving
            // it as SHADOW would hand the journal to the
            // ungated legacy path, and a seed that transfer
            // state was holding back (an identity already
            // sent onward) would record again.
            if (TransferInit.hasTransferTraces(root)) {
                out.kind = WriterResolution.HELD;
                out.reason = "transfer init record missing "
                        + "but transfer data exists; journal held";
                return out;
            }
            out.kind = WriterResolution.SHADOW;
            return out;
        }
        boolean explicitOn = false;
        try {
            TransferState st = TransferState.load(root);
            Map<String, Object> active = st.active();
            String enabledId = DepinSettings.enabledDeviceId(context);
            if (active != null && DepinSettings.isEnabled(context)
                    && enabledId != null && enabledId.equals(
                            TransferState.stringOr(
                                    active, "device_id", null))) {
                explicitOn = true;
            }
        } catch (Exception e) {
            out.kind = WriterResolution.HELD;
            out.reason = "transfer state unreadable; journal held: "
                    + e.getMessage();
            return out;
        }
        TransferInit.Gate gate = TransferInit.writerGate(root, explicitOn);
        if (!gate.allowed) {
            out.kind = WriterResolution.HELD;
            out.reason = gate.reason;
            return out;
        }
        if (gate.deviceId != null
                && gate.deviceId.equals(shadowDeviceId)) {
            out.kind = WriterResolution.SHADOW;
            return out;
        }
        out.kind = WriterResolution.RECEIVED;
        out.deviceId = gate.deviceId;
        out.generation = gate.generation;
        return out;
    }

    /**
     * Display sync for the settings binding (audit M6). The
     * observer rebinds DepinSettings to the identity it
     * actually loaded — but only when the observer STARTS.
     * After an app update that lands on a completed transfer,
     * the settings screen kept showing the displaced shadow
     * identity (and its stale self-stop) until the user next
     * pressed record. This sync performs the same rebind —
     * identity and generation bookkeeping only, never the
     * enable authorisation — from the transfer state's active
     * binding, so any screen can call it on open. No-op when
     * the transfer layer is not initialised, when no binding
     * is active, or when the bindings already agree.
     * Best-effort by design: every strict path re-reads the
     * durable state itself and fails closed there.
     */
    public static void syncSettingsBindingToTransferActive(
            Context context) {
        try {
            File root = storeRoot(context);
            if (TransferState.loadInit(root) == null) {
                return;
            }
            TransferState st = TransferState.load(root);
            Map<String, Object> active = st.active();
            if (active == null) {
                return;
            }
            String activeId = TransferState.stringOr(
                    active, "device_id", null);
            if (activeId == null) {
                return;
            }
            String bound = DepinSettings.activeDeviceId(context);
            if (activeId.equals(bound)) {
                return;
            }
            if (bound == null) {
                long existing =
                        DepinSettings.identityGeneration(context);
                DepinSettings.bindActiveIdentity(context, activeId,
                        existing != 0 ? existing : 1L);
            } else {
                DepinSettings.bindActiveIdentity(context, activeId,
                        DepinSettings.identityGeneration(context) + 1);
                DepinSettings.clearSelfStopRecord(context);
            }
        } catch (Exception ignored) {
            // Display sync only.
        }
    }

    /**
     * The device id the transfer state's active binding names
     * (the identity that currently holds the live placement),
     * or null when the transfer layer has not been introduced
     * or no binding exists. The observer service reads this to
     * know which identity is live after a transfer moved the
     * placement: its own settings binding is a cache of what
     * it last loaded and can name a displaced identity.
     * Read-only; an unreadable state reads as "not introduced"
     * here because the writer resolution performs the strict
     * (fail-closed) reading separately.
     */
    public static String activeBindingDeviceId(Context context) {
        try {
            File root = storeRoot(context);
            if (TransferState.loadInit(root) == null) {
                return null;
            }
            Map<String, Object> active =
                    TransferState.load(root).active();
            return active == null ? null
                    : TransferState.stringOr(active, "device_id", null);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- initialisation ----------------

    /**
     * Introduce the transfer layer over the step-2 store
     * (legacy_upgrade mode). The positive confirmation the
     * design requires for that mode is performed here first:
     * the existing journal must parse and fully verify under
     * the shadow identity before the mode is claimed.
     */
    public static TransferState.InitRecord initialise(Context context)
            throws Exception {
        File root = storeRoot(context);
        AndroidSeedVault vault = new AndroidSeedVault(context);
        byte[] seed = vault.loadOrCreateSeed();
        try {
            String deviceId = DepinCore.deviceIdFromPubkey(
                    Ed25519.publickeyFromSeed(seed));
            File journal = DepinFiles.journalFile(context);
            if (journal.exists()) {
                JournalFile.parse(journal, deviceId);
            }
            TransferInit.Result r = TransferInit.initialise(
                    root, vault(context),
                    TransferInit.MODE_LEGACY, seed, RANDOM);
            return r.init;
        } finally {
            java.util.Arrays.fill(seed, (byte) 0);
        }
    }

    // ---------------- machines ----------------

    private static long finalizedNow() throws Exception {
        RpcClient.NodeObservation o =
                new RpcClient(RpcClient.DEFAULT_ENDPOINT)
                        .fetchObservation();
        if (o.observation.finalizedBlockNumber == null) {
            throw new IllegalStateException(
                    "node observation has no finalized block");
        }
        return o.observation.finalizedBlockNumber;
    }

    /**
     * Stop the observer and wait until its published state is
     * drained (H-1 IPC: this runtime now executes only inside
     * the :depin process, so the freeze can no longer go
     * through DepinModule — that facade belongs to the main
     * process, and an instance of it here would write the
     * settings flag directly without ever stopping the live
     * service beside us). The command path is the same one
     * DepinModule.disable uses from the other side: an
     * ACTION_DISABLE intent carrying the expected binding,
     * which the service applies under its section 3.1 command
     * discipline. The drain is observed on the status file —
     * the transport the service keeps current on every state
     * change, and the same snapshot DepinModule falls back to
     * whenever it is not bound — under the freshness rule
     * documented in the method (audit F3-H2): only a snapshot
     * written after this freeze's own request confirms it.
     */
    private static void freezeObserver(Context app, String id,
                                       long gen, String what)
            throws IOException {
        // Audit F3-H2: a snapshot that already existed BEFORE
        // this request must never be accepted as the drain's
        // confirmation. Right after the user enables DePIN,
        // the status file can still hold the previous OFF
        // while the ENABLE command is still being processed
        // (initialisation verifies the whole journal and can
        // take seconds); accepting that stale OFF let the
        // transfer read the journal while the observer was
        // about to start writing into it. So: capture the
        // pre-request snapshot's stamp first, send the
        // disable, and accept only a snapshot stamped LATER
        // than the pre-request one (and not earlier than the
        // request itself), in a stopped state, with the
        // enable flag cleared. A missing or unreadable file
        // is "unconfirmed", never OFF. The disable is sent
        // unconditionally (with whatever expectation the
        // caller holds): when the observer is not running at
        // all, the delivery itself produces the fresh OFF
        // snapshot through the service's first-command
        // disable path, so the wait below is answerable in
        // every state.
        DepinStatus before = readObserverStatus(app);
        long beforeStamp = before == null ? -1 : before.updatedWallMs;
        long requestMs = System.currentTimeMillis();
        Intent intent = new Intent(app, DepinObserverService.class)
                .setAction(DepinIpc.ACTION_DISABLE);
        if (id != null) {
            intent.putExtra(DepinIpc.EXTRA_EXPECTED_DEVICE_ID, id);
            intent.putExtra(DepinIpc.EXTRA_EXPECTED_GENERATION, gen);
        }
        try {
            app.startService(intent);
        } catch (Throwable ignored) {
            // A service that cannot even be addressed is
            // judged by its published state below.
        }
        long deadline = System.currentTimeMillis() + 15_000;
        while (true) {
            DepinStatus s = readObserverStatus(app);
            if (s != null
                    && s.updatedWallMs > beforeStamp
                    && s.updatedWallMs >= requestMs
                    && (s.state == DepinStatus.STATE_OFF
                            || s.state == DepinStatus.STATE_UNAVAILABLE
                            || s.state == DepinStatus.STATE_SELF_STOPPED)
                    && !DepinSettings.isEnabled(app)) {
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IOException(
                        "observer did not stop for the " + what);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(
                        "interrupted while freezing", e);
            }
        }
    }

    /** The observer's last published status from its status
     *  file, or null when no snapshot exists or parses. A
     *  missing file is NOT read as OFF here: for the freeze's
     *  freshness judgement, "nothing published" is
     *  "unconfirmed" (audit F3-H2). */
    private static DepinStatus readObserverStatus(Context app) {
        try {
            File f = DepinFiles.statusFile(app);
            if (!f.exists()) {
                return null;
            }
            byte[] bytes = TransferIo.readAll(f, 256L * 1024);
            Map<String, Object> m = Json.parseObject(new String(
                    bytes, StandardCharsets.UTF_8));
            return DepinStatus.fromMap(m);
        } catch (Throwable t) {
            return null;
        }
    }

    private static TransferSender.FreezeControl freezeControl(
            Context context) {
        final Context app = context.getApplicationContext();
        return new TransferSender.FreezeControl() {
            @Override
            public void drainAndFreeze() throws IOException {
                String id = DepinSettings.activeDeviceId(app);
                long gen = DepinSettings.identityGeneration(app);
                freezeObserver(app, id, gen, "transfer freeze");
            }

            @Override
            public void unfreeze() {
                // Recording restarts only by the user's explicit
                // toggle, as before.
            }
        };
    }

    private static final TransferSender.Clock SENDER_CLOCK =
            () -> System.currentTimeMillis() / 1000L;

    private static final TransferReceiver.Clock RECEIVER_CLOCK =
            new TransferReceiver.Clock() {
                @Override
                public long wallUnix() {
                    return System.currentTimeMillis() / 1000L;
                }

                @Override
                public long monoNanos() {
                    return System.nanoTime();
                }
            };

    /** Section 3.5 time source (boot identity + monotonic +
     *  thread CPU), same construction as the companion's.
     *  threadCpuMs reads SystemClock.currentThreadTimeMillis():
     *  ART has no java.lang.management package, so the JDK's
     *  ThreadMXBean route does not exist on device (audit C-1).
     *  The JVM harness's TestTimeSource reads the same quantity
     *  through ThreadMXBean; the budget only reads differences
     *  and both are monotonic per thread. */
    public static final class AndroidTimeSource
            implements Reverification.TimeSource {
        private final String bootId;

        public AndroidTimeSource(Context context) {
            String id = null;
            try {
                id = new String(Files.readAllBytes(new File(
                        "/proc/sys/kernel/random/boot_id").toPath()),
                        StandardCharsets.UTF_8).trim();
            } catch (Exception ignored) {
                // fall through to the persisted ID
            }
            if (id == null || id.isEmpty()) {
                id = persistedBootId(context);
            }
            this.bootId = id;
        }

        private static String persistedBootId(Context context) {
            File f = new File(storeRoot(context), "transfer-boot-id");
            try {
                if (f.exists()) {
                    return new String(Files.readAllBytes(f.toPath()),
                            StandardCharsets.UTF_8).trim();
                }
                String id = TransferCodec.newInstallId(RANDOM);
                TransferIo.writeFileSync(f,
                        id.getBytes(StandardCharsets.UTF_8));
                return id;
            } catch (Exception e) {
                return "unknown-boot";
            }
        }

        @Override
        public String bootId() {
            return bootId;
        }

        @Override
        public long monoMs() {
            return System.nanoTime() / 1_000_000L;
        }

        @Override
        public long threadCpuMs() {
            return SystemClock.currentThreadTimeMillis();
        }
    }

    public static TransferSender sender(Context context) throws Exception {
        return new TransferSender(storeRoot(context), vault(context),
                RANDOM, "quantus-android-node", freezeControl(context),
                DepinTransferRuntime::finalizedNow, SENDER_CLOCK,
                PERMIT_VALIDITY_SEC, PERMIT_VALIDITY_BLOCKS,
                PERMIT_SAFETY_MARGIN_BLOCKS);
    }

    public static TransferReceiver receiver(Context context)
            throws Exception {
        TransferReceiver r = new TransferReceiver(storeRoot(context),
                vault(context),
                new AndroidSeedVault(context).wrapKeyForTransfer(),
                DepinTransferRuntime::finalizedNow, RECEIVER_CLOCK,
                new AndroidTimeSource(context));
        // C-2: when a promotion displaces the shadow identity's
        // live placement, the observer (its only writer) is
        // frozen through the same disable-and-wait discipline
        // the sender-side freeze uses, so the core can
        // quarantine the drained placement intact.
        // Production wiring (final check round 3, H-08 /
        // H-11): the staged-journal re-verification proves
        // every checkpoint's VDF iteration count against the
        // production parameter (a package whose proofs were
        // computed cheaper is refused at intake, not
        // discovered after promotion), and the receiver's
        // write paths honour the design section 3.4
        // free-space floor. The core defaults stay 0 =
        // disabled: the JVM harness constructs receivers
        // directly and sets both explicitly per fixture
        // (small iteration counts, small volumes).
        r.setExpectedIterations(DepinCore.PRODUCTION_ITERATIONS);
        r.setFreeFloorBytes(
                app.quantusnode.node.depin.core.TransferStore
                        .FREE_FLOOR_BYTES);
        final Context app = context.getApplicationContext();
        r.setForeignWriterFreeze(activeDeviceId -> {
            // Audit F3-L1: the disable's expected generation
            // must be the OBSERVER's binding counter
            // (DepinSettings.identityGeneration — the value
            // the service's bindingMatches checks), exactly as
            // the sender-side freeze passes it. The transfer
            // record's binding generation is a different
            // counter; passing it made the service discard the
            // command (and the freeze time out, fail-closed)
            // whenever a received identity held the live
            // placement.
            long gen = DepinSettings.identityGeneration(app);
            freezeObserver(app, activeDeviceId, gen,
                    "foreign-writer freeze");
        });
        return r;
    }

    // ---------------- intake wait absorption ----------------

    /** Wall-clock cap for one UI-requested intake operation's
     *  wait-absorption loop: below the core's per-call session
     *  wall budget (TransferReceiver.SESSION_WALL_BUDGET_NANOS,
     *  30 min), so one tap can never occupy the operation
     *  longer than the design's own session scale. Reaching it
     *  returns the pause as-is (a resumable stop), never a
     *  failure and never a budget bypass. */
    public static final long INTAKE_LOOP_WALL_CAP_NANOS =
            25L * 60 * 1_000_000_000L;

    /** Idle after a PAUSED_WAIT outcome: the wait obligation a
     *  settled slice owes equals its actual CPU, capped at
     *  SLICE_MAX_CPU_MS, and the ledger accumulates it from
     *  monotonic elapsed time between stamps — so sleeping the
     *  cap plus a margin lets the next stage call's first
     *  stamp() see the obligation fulfilled. The budget itself
     *  is NOT touched: settled consumption stays in the
     *  one-hour window exactly as the core accounts it, and a
     *  PAUSED_BUDGET outcome (window spent) stops the loop at
     *  once instead of being waited around. */
    private static final long WAIT_ABSORB_SLEEP_MS =
            Reverification.SLICE_MAX_CPU_MS + 250L;

    /** True only for the wait-obligation pause (the core's
     *  message names the slice outcome). The budget pause and
     *  the session-wall pause keep their own meanings and are
     *  never retried past. */
    private static boolean isWaitPause(
            TransferReceiver.VerificationPausedException p) {
        String m = p.getMessage();
        return m != null && m.endsWith("PAUSED_WAIT");
    }

    /**
     * Stage a package, absorbing section 3.5 WAIT pauses in an
     * app-layer loop (transfer UX fix, internal.10). One call
     * of the core's stage advances the sequential
     * re-verification by at most one 2 s CPU slice and then
     * pauses on that slice's wait obligation, so a
     * production-scale journal (hundreds of checkpoints) used
     * to need one user tap per slice — each tap looking like
     * "no progress", because verified counts were not shown
     * either. Here a PAUSED_WAIT is answered with an idle sleep
     * of the owed wait and an automatic re-issue of the same
     * stage: the core's durable cursor carries the progress
     * between calls, and the ledger's rules (600 s CPU per
     * one-hour window; wait equal to the slice's length) are
     * honoured exactly — nothing is reset, skipped or
     * re-judged, and the loop adds no verification of its own.
     * PAUSED_BUDGET and the session-wall pause are rethrown
     * unchanged, as is a wait pause that arrives after the
     * loop's own wall-clock cap.
     */
    public static TransferReceiver.IntakeResult stagePackageResuming(
            Context context, File packageFile, String transferCode)
            throws Exception {
        TransferReceiver receiver = receiver(context);
        long deadline = System.nanoTime()
                + INTAKE_LOOP_WALL_CAP_NANOS;
        while (true) {
            try {
                return receiver.stagePackage(
                        packageFile, transferCode);
            } catch (TransferReceiver.VerificationPausedException p) {
                if (!isWaitPause(p)
                        || System.nanoTime() >= deadline) {
                    throw p;
                }
                try {
                    Thread.sleep(WAIT_ABSORB_SLEEP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw p;
                }
            }
        }
    }

    /**
     * Issue the cannot-confirm response (abandon) for the
     * receiver trial of (deviceId, generation), for the UI's
     * "export abandon" action. The composition itself — the
     * state-based routing between re-presenting a recorded
     * refusal, the design's explicit cancel-then-issue for an
     * open trial (sections 4.3 step 9 and the pre-plan
     * explicit cancel), and the core's own refusals — lives in
     * the core helper {@link TransferAbandon}, so it is
     * covered by the JVM transfer harness; this method only
     * constructs the receiver and delegates. (Audit F3-H1:
     * the previous app-layer composition branched on the
     * core's exception types and misrouted two of the three
     * paths.)
     */
    public static Map<String, Object> issueReceiverAbandon(
            Context context, String deviceId, long generation)
            throws Exception {
        return TransferAbandon.issueReceiverAbandon(
                receiver(context), storeRoot(context),
                deviceId, generation);
    }

    /**
     * The seed of the identity this app currently holds as
     * sender (audit H-2). The resolution follows the identity
     * half of the section 3.2 writer resolution — the same
     * rule DepinBundleExport applies: while the shadow
     * identity is the active binding, the sender seed is the
     * app vault's seed; once a RECEIVED identity is the active
     * binding, the sender seed is that identity's transfer-
     * vault entry, so this app can itself become the sender
     * of the identity it received. The writer GATE (freeze
     * state, explicit ON) is deliberately NOT applied here:
     * the message operations that use this seed (permit
     * issuance, receipt intake, pre-permit cancellation) are
     * legitimate while this app sits in S1 as a sender, where
     * the gate is closed by design. A completed initialisation
     * with no active binding, or a binding whose vault entry
     * is gone, fails closed instead of silently falling back
     * to the shadow seed. Callers zero the returned seed after
     * use.
     */
    public static byte[] loadSenderSeed(Context context) throws Exception {
        AndroidSeedVault appVault = new AndroidSeedVault(context);
        TransferState.InitRecord init =
                TransferState.loadInit(storeRoot(context));
        if (init == null || !"complete".equals(init.state)) {
            // Not (yet) initialised: the shadow identity is the
            // only identity this app can hold.
            return appVault.loadOrCreateSeed();
        }
        TransferState state = TransferState.load(storeRoot(context));
        Map<String, Object> active = state.active();
        if (active == null) {
            throw new IllegalStateException(
                    "no active identity to send from");
        }
        String activeId = TransferState.stringOr(
                active, "device_id", null);
        // G-07/F-25: the shadow comparison reads the home
        // seed CREATE-FREE once the transfer layer exists.
        // loadOrCreateSeed here would resurrect — as a fresh,
        // unrelated identity — a home seed that was erased
        // after its identity was sent out, on every sender
        // operation of the received identity that followed.
        byte[] shadowSeed = appVault.loadSeedIfPresent();
        if (shadowSeed != null) {
            try {
                String shadowId = DepinCore.deviceIdFromPubkey(
                        Ed25519.publickeyFromSeed(shadowSeed));
                if (shadowId.equals(activeId)) {
                    return shadowSeed;
                }
            } catch (Exception e) {
                java.util.Arrays.fill(shadowSeed, (byte) 0);
                throw e;
            }
            java.util.Arrays.fill(shadowSeed, (byte) 0);
        }
        // Audit F4: resolve by the binding's ROLE, not by a
        // fixed receiverKey(active.generation) read. Once this
        // app has sent a received identity onward, the active
        // binding names the SEND generation while the seed
        // still sits under the RECEIVED generation's key (the
        // two counters are independent) — the old read missed
        // in exactly the states the re-export (E7), permit
        // issuance and pre-permit cancel paths run in, and an
        // unrelated same-numbered key could even have been
        // picked up silently. Candidates are tried in role
        // order and every hit is verified by derivation
        // against the active identity; anything else fails
        // closed.
        TransferVault tv = vault(context);
        String role = TransferState.stringOr(active, "role", null);
        long bindingGen = TransferState.longOr(
                active, "generation", -1);
        // ROLE-SCOPED candidates (audit M9, companion form
        // first): sender keys are consulted only under a
        // sender binding (the binding's own generation, and
        // an open S1's when it differs); receiver keys only
        // through the received generations, committed first,
        // the scan capped at two. The previous list also
        // tried the binding's sender key under a RECEIVER
        // binding, scanned every received generation, and
        // walked the sender HISTORY's keys — entries every
        // closure (S2 and S0 alike) deletes, so only a
        // crash-orphaned copy could ever have answered, and
        // seed resolution is not that crash's recovery path.
        // The receiver copies stay reachable under a sender
        // binding because a cancel RESTORES the binding as
        // (sender, held generation) while the identity's
        // operative copy still sits under its receiver key —
        // the eras are scoped by role, not confused by it:
        // every candidate is still verified by derivation
        // against the active identity below.
        java.util.List<String> candidates = new java.util.ArrayList<>();
        if ("sender".equals(role)) {
            candidates.add(TransferVault.senderKey(bindingGen));
            Map<String, Object> openSender = state.sender(activeId);
            if (openSender != null) {
                long openGen = TransferState.longOr(
                        openSender, "generation", -1);
                if (openGen != bindingGen) {
                    candidates.add(TransferVault.senderKey(openGen));
                }
            }
        }
        // Audit L10: the receiver scan's head and eligibility
        // are explicit. Under a RECEIVER binding the binding
        // generation's own key leads the list — the previous
        // form reached it only through the coincidence
        // bindingGen == maxCommittedGeneration — and the scan
        // behind it visits only generations that can hold a
        // key at all (receiverKeyEligible): the second slot
        // could be consumed by a newer refusal / cancel-token
        // generation, which never held a key, stranding an
        // older committed copy below it. The two-candidate
        // cap is unchanged.
        java.util.List<String> receiverCandidates =
                new java.util.ArrayList<>();
        if ("receiver".equals(role) && bindingGen > 0) {
            receiverCandidates.add(
                    TransferVault.receiverKey(bindingGen));
        }
        long committedGen = state.maxCommittedGeneration(activeId);
        if (committedGen > 0 && !receiverCandidates.contains(
                TransferVault.receiverKey(committedGen))) {
            receiverCandidates.add(
                    TransferVault.receiverKey(committedGen));
        }
        java.util.List<Long> rGens = state.receiverGenerations(activeId);
        for (int i = rGens.size() - 1; i >= 0; i--) {
            long g = rGens.get(i);
            String key = TransferVault.receiverKey(g);
            if (receiverCandidates.contains(key)
                    || !receiverKeyEligible(state, activeId, g)) {
                continue;
            }
            receiverCandidates.add(key);
        }
        int receiverBudget = 2;
        for (String key : receiverCandidates) {
            if (receiverBudget <= 0) {
                break;
            }
            candidates.add(key);
            receiverBudget--;
        }
        for (String key : candidates) {
            byte[] seed = tv.get(key);
            if (seed == null) {
                continue;
            }
            if (activeId.equals(DepinCore.deviceIdFromPubkey(
                    Ed25519.publickeyFromSeed(seed)))) {
                return seed;
            }
            java.util.Arrays.fill(seed, (byte) 0);
        }
        throw new IllegalStateException(
                "the active identity's seed is not in the "
                        + "transfer vault");
    }

    /** Whether a received generation can hold a receiver
     *  key at all (audit L10): it committed (either closure
     *  kind), or its trial adopted a plan — intake step 8-2
     *  places the seed in the vault at plan adoption, before
     *  the commit lands. Refusals and cancel-token closures
     *  never held one, and an unnotified staging's seed lives
     *  only in its staging record. */
    private static boolean receiverKeyEligible(TransferState state,
                                               String deviceId,
                                               long generation) {
        Map<String, Object> rec =
                state.receiver(deviceId, generation);
        if (rec == null) {
            return false;
        }
        Map<String, Object> closure =
                TransferState.child(rec, "closure");
        if (closure != null) {
            String kind = TransferState.stringOr(
                    closure, "kind", null);
            return "committed_r2".equals(kind)
                    || "committed_oneshot".equals(kind);
        }
        Map<String, Object> trial =
                TransferState.child(rec, "trial");
        return trial != null && TransferState.boolOr(
                trial, "plan_adopted", false);
    }

    /** Run the audit M10 superseded-copy sweep over this
     *  installation's store (core:
     *  TransferReceiver.sweepSupersededSeedCopies). Called
     *  by the transfer service ahead of each operation, so a
     *  closure whose erasure was cut off (crash between the
     *  state save and the copy erasure) is retried without
     *  waiting for a recovery path nothing invokes at
     *  runtime. Idempotent — a state read when nothing is
     *  due. Failures are recorded durably and never fail the
     *  operation that triggered the sweep. The sweep's
     *  notes are persisted to the durable error log as
     *  well (audit M13). */
    public static void sweepSupersededSeedCopies(Context context) {
        try {
            if (TransferState.loadInit(storeRoot(context)) == null) {
                return;
            }
            TransferReceiver.SweepNotes outcome =
                    receiver(context)
                            .sweepSupersededSeedCopiesClassified();
            // Audit M13: the sweep's outcome is part of the
            // durable record, not a return value nobody
            // reads. Round 3 discarded these notes at this
            // exact line (both apps) — including the
            // invalidation of a superseded identity's
            // staging wrap, the one mutation the sweep
            // performs — so the action was findable only in
            // the staging record's own state. Every note is
            // now persisted to the same error log the
            // transfer error report draws from.
            for (String note : outcome.changes) {
                app.quantusnode.node.depin.core.CrashLog
                        .recordMessageAt(
                                DepinFiles.crashLogFile(context),
                                "superseded_sweep", note);
            }
            // Audit L18: the steady-state notes (a foreign
            // copy deliberately left, an unprovable wrap
            // deliberately left) repeat verbatim for as
            // long as the collision lasts, and this wrapper
            // runs ahead of EVERY transfer operation — as
            // error entries they buried the most recent
            // real error and grew the log with no new
            // information. They are recorded under their
            // own kind, and only when they are not already
            // on the record: a change in the steady state
            // is still recorded, a repetition is not.
            //
            // Audit L23: round 6 deduplicated against only
            // the LAST recorded steady note. A sweep whose
            // steady set is [A, B] then re-recorded both on
            // EVERY sweep — A differs from the last (B), B
            // differs from the just-recorded A — the exact
            // alternating case the L18 review point raised.
            // The comparison point is now the recorded SET
            // (every body already persisted under the
            // steady kind): a note is written only when it
            // is not in the set, and joins the set as it is
            // written, so duplicates inside one sweep are
            // written once too.
            java.util.Set<String> recordedSteady =
                    new java.util.HashSet<>(
                            app.quantusnode.node.depin.core.CrashLog
                                    .messagesOfKind(
                                            app.quantusnode.node
                                                    .depin.core
                                                    .CrashLog
                                                    .readAllAt(
                                                            DepinFiles
                                                                    .crashLogFile(
                                                                            context)),
                                            "superseded_sweep_steady"));
            for (String note : outcome.steady) {
                if (recordedSteady.add(note)) {
                    app.quantusnode.node.depin.core.CrashLog
                            .recordMessageAt(
                                    DepinFiles.crashLogFile(context),
                                    "superseded_sweep_steady",
                                    note);
                }
            }
        } catch (Exception e) {
            DepinRuntime.recordError(context, "superseded_sweep", e);
        }
    }

    // ---------------- crash recovery (final check F-05) ----

    /** Run the receiver's crash recovery (core:
     *  TransferReceiver.recover) plus the expansion-debris
     *  sweep. Final check round 1 (F-05) found recover()
     *  fully implemented and unit-proven but invoked from
     *  NOWHERE in either app: a crash in the window between
     *  a trial's judgement and its materialisation (a window
     *  this process has really died in — the OS killed the
     *  :depin process mid-recording on 2026-11-10) left the
     *  trial unrecoverable through the UI: re-confirmation
     *  refuses ("a trial is in progress"), the
     *  cannot-confirm response refuses, and only recover()
     *  completes it. The transfer service now calls this
     *  ahead of every operation, under the same contract as
     *  the sweep above: idempotent, its notes persisted to
     *  the durable log (kind "recovery"), its failures
     *  recorded and NEVER failing the operation that
     *  triggered it — a recovery that itself hits an
     *  inconsistency leaves the state exactly as it found
     *  it and says so on the record.
     *
     *  Order (self-check S-08): recover() runs FIRST and
     *  its notes are persisted before the expansion-debris
     *  sweep and its notes. The debris sweep's note saving
     *  must never be able to abort this call's recover():
     *  a failure persisting a debris note is a logging
     *  failure, not a reason to leave a judged trial
     *  unmaterialised for another cycle. */
    public static void runRecovery(Context context) {
        try {
            if (TransferState.loadInit(storeRoot(context)) == null) {
                return;
            }
            TransferReceiver r = receiver(context);
            // Final check round 4 (I-10): the two stages are
            // independent. S-08 put recover() first so a
            // debris-note failure could not abort it — but
            // with one shared try, a recover() that throws
            // (a classification (c) stop, a promotion held at
            // the free-space floor: states that persist) then
            // skipped the debris sweep on every call, for as
            // long as the state lasted.
            try {
                for (String note : r.recover()) {
                    app.quantusnode.node.depin.core.CrashLog
                            .recordMessageAt(
                                    DepinFiles.crashLogFile(context),
                                    "recovery", note);
                }
            } catch (Exception e) {
                DepinRuntime.recordError(context, "recovery", e);
            }
            for (String note : r.sweepExpansionDebris()) {
                app.quantusnode.node.depin.core.CrashLog
                        .recordMessageAt(
                                DepinFiles.crashLogFile(context),
                                "recovery", note);
            }
        } catch (Exception e) {
            DepinRuntime.recordError(context, "recovery", e);
        }
    }

    // ---------------- sent-out marker (audit F5) -----------

    private static final String SENT_OUT_MARKER =
            "home-seed-sent-out.json";

    /** The sent-out marker record, or null when absent or
     *  unreadable (an unreadable marker fails safe: the
     *  transfer state itself still holds the line, and the
     *  remediation re-derives the truth from it). */
    public static java.util.Map<String, Object> sentOutMarker(
            Context context) {
        File f = new File(storeRoot(context), SENT_OUT_MARKER);
        if (!f.exists()) {
            return null;
        }
        try {
            return Json.parseObject(new String(
                    Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeSentOutMarker(Context context,
                                           String deviceId,
                                           long generation)
            throws IOException {
        java.util.Map<String, Object> m =
                new java.util.LinkedHashMap<>();
        m.put("format", "quantus-depin-home-seed-sent-out");
        m.put("device_id", deviceId);
        m.put("generation", generation);
        m.put("wall_unix", System.currentTimeMillis() / 1000L);
        // Atomic rewrite (audit L17, companion form): the
        // marker is a claim other readers act on, and a torn
        // marker is worse than a late one.
        TransferIo.writeRecordAtomic(
                new File(storeRoot(context), SENT_OUT_MARKER),
                TransferCodec.jcs(m));
    }

    /** The device id the HOME seed derives, read without
     *  creating a seed; null when no home seed exists or it
     *  cannot be read. */
    public static String homeSeedDeviceId(Context context) {
        try {
            byte[] seed = new AndroidSeedVault(context)
                    .loadSeedIfPresent();
            if (seed == null) {
                return null;
            }
            try {
                return DepinCore.deviceIdFromPubkey(
                        Ed25519.publickeyFromSeed(seed));
            } finally {
                java.util.Arrays.fill(seed, (byte) 0);
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- home-seed erasure (G-07) -------------

    /** The outcome of an {@link #eraseHomeSeedIfSentOut}
     *  call (audit L3): whether an erasure was DUE, whether
     *  the seed is actually GONE, whether the sent-out
     *  marker was left, and — when any of it failed — what
     *  failed. Before this outcome type existed (in the
     *  companion first), "not due" and "due but the deletion
     *  failed" were indistinguishable and the UI announced
     *  erasures that never happened.
     *
     *  {@code checkFailed} separates "the due check itself
     *  could not run" from "not due" (audit L6);
     *  {@code pendingDiscarded} reports an interrupted
     *  erasure's record dropped without being acted on,
     *  with the drop itself on the durable record (audit
     *  L12); {@code held} separates the stops that have no
     *  verified erasure to report — an unknown stage, or a
     *  seed present/unreadable at a post-erasure stage —
     *  from a deletion that was attempted and failed, and
     *  never stands together with {@code erased} (audit
     *  L20/L22). */
    public static final class HomeSeedErasure {
        public boolean due;
        public boolean erased;
        public boolean markerWritten;
        public boolean checkFailed;
        public boolean held;
        public boolean pendingDiscarded;
        public String deviceId;
        public String discardedPendingId;
        public String failure;

        public boolean completed() {
            return due && erased && markerWritten;
        }
    }

    // ---------------- erasure record (audit L6, L14) ------

    private static final String ERASURE_RECORD =
            "home-seed-erasure.json";

    /** Round 3's pending file, kept only as an adoption
     *  source: a store that interrupted an erasure under
     *  the old shape resumes under the record below. */
    private static final String ERASURE_PENDING =
            "home-seed-erasure-pending.json";

    /**
     * The ONE durable record of an in-flight home-seed
     * erasure (audit L14). The erasure is a single record
     * carrying a stage:
     *
     *   "pending"     — the target (device id + S2
     *                   generation) is durable; the seed
     *                   has not been erased yet.
     *   "seed_erased" — the seed is verified gone; the
     *                   sent-out marker has not landed.
     *   "complete"    — the marker is on disk; only the
     *                   record's own removal remains.
     *
     * Convergence rule: every stage transition is a single
     * ATOMIC rewrite of this record (temp file, fsync,
     * rename — audit L17), and every stage's action is
     * idempotent, so a crash leaves the record at the last
     * durable stage and the next call resumes from exactly
     * there. The seed is erased ONLY in the pending stage,
     * and only with the record already on disk — an erasure
     * with no resumable record cannot happen. The marker
     * is written ONLY from the seed_erased stage (or
     * re-derived from complete when it is missing), and
     * only after the seed's absence is re-verified on that
     * entry (audit L16) — a marker claiming an erasure that
     * never happened cannot happen either. The record is
     * deleted only from the complete stage, with the
     * marker on disk. A stage string outside the three is
     * not a stage at all: it is recorded and the machine
     * stops (audit L16).
     */
    private static final class ErasureRecord {
        String deviceId;
        long generation;
        String stage;
    }

    private static ErasureRecord erasureRecord(Context context) {
        File f = new File(storeRoot(context), ERASURE_RECORD);
        if (!f.exists()) {
            return null;
        }
        // Audit L17: a record that EXISTS but cannot be
        // read or parsed is not the same as no record. The
        // failure is recorded durably; the caller still
        // treats the record as absent, but the fact that it
        // is THERE and unreadable is on the record now.
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(f.toPath());
        } catch (Exception e) {
            DepinRuntime.recordError(context,
                    "home_seed_erasure", e);
            return null;
        }
        app.quantusnode.node.depin.core.TransferRemediation
                .ErasureRecordData d =
                app.quantusnode.node.depin.core.TransferRemediation
                        .parseErasureRecord(bytes);
        if (d == null) {
            DepinRuntime.recordError(context,
                    "home_seed_erasure", new IOException(
                            "the erasure record exists but "
                                    + "could not be parsed; "
                                    + "it is treated as absent "
                                    + "and the erasure is "
                                    + "re-derived from the "
                                    + "transfer state (audit "
                                    + "L17)"));
            return null;
        }
        ErasureRecord r = new ErasureRecord();
        r.deviceId = d.deviceId;
        r.generation = d.generation;
        r.stage = d.stage;
        return r;
    }

    private static void writeErasureRecord(Context context,
                                           String deviceId,
                                           long generation,
                                           String stage)
            throws IOException {
        java.util.Map<String, Object> m =
                new java.util.LinkedHashMap<>();
        m.put("format", "quantus-depin-home-seed-erasure");
        m.put("device_id", deviceId);
        m.put("generation", generation);
        m.put("stage", stage);
        m.put("wall_unix", System.currentTimeMillis() / 1000L);
        // Audit L17: the record is rewritten atomically
        // (temp file, fsync, rename) like every other core
        // record — a plain fsynced write torn by a crash
        // left a record the reader below could not parse,
        // and the erasure silently read as "not due".
        TransferIo.writeRecordAtomic(
                new File(storeRoot(context), ERASURE_RECORD),
                TransferCodec.jcs(m));
    }

    private static void clearErasureRecord(Context context) {
        try {
            new File(storeRoot(context), ERASURE_RECORD).delete();
        } catch (Exception ignored) {
            // A leftover record is re-evaluated against the
            // state on the next call; a "complete" one whose
            // marker is on disk simply removes itself then.
        }
    }

    /** The current erasure record, adopting a round-3
     *  pending file as a stage-"pending" record when only
     *  that exists. The legacy file carries everything the
     *  record needs (device id + generation); it is
     *  removed once the record is durable. */
    private static ErasureRecord loadOrAdoptErasureRecord(
            Context context) throws IOException {
        ErasureRecord r = erasureRecord(context);
        if (r != null) {
            return r;
        }
        File legacy = new File(storeRoot(context),
                ERASURE_PENDING);
        if (!legacy.exists()) {
            return null;
        }
        java.util.Map<String, Object> m;
        try {
            m = Json.parseObject(new String(
                    Files.readAllBytes(legacy.toPath()),
                    StandardCharsets.UTF_8));
        } catch (Exception e) {
            // Audit L17: an unparseable legacy record is
            // recorded, not silently dropped (the same
            // treatment the current record gets above).
            DepinRuntime.recordError(context,
                    "home_seed_erasure", e);
            return null;
        }
        String id = TransferState.stringOr(m, "device_id", null);
        if (id == null) {
            DepinRuntime.recordError(context,
                    "home_seed_erasure", new IOException(
                            "the legacy erasure record "
                                    + "carries no device id; "
                                    + "it was not adopted "
                                    + "(audit L17)"));
            return null;
        }
        long gen = TransferState.longOr(m, "generation", -1);
        writeErasureRecord(context, id, gen, "pending");
        try {
            legacy.delete();
        } catch (Exception ignored) {
            // The record is durable; a leftover legacy file
            // is shadowed by it on every later read.
        }
        ErasureRecord adopted = new ErasureRecord();
        adopted.deviceId = id;
        adopted.generation = gen;
        adopted.stage = "pending";
        return adopted;
    }

    /**
     * The G-07 erasure (companion remediation F5, ported):
     * when the transfer state says the home seed's identity
     * has been sent onward for good (core decision:
     * {@link app.quantusnode.node.depin.core.TransferRemediation}),
     * erase the home seed copy and leave the sent-out
     * marker the legacy paths honour. Final check round 2
     * (G-07) found the node app had NO such path at all:
     * the core's closure erased the sender-vault copies on
     * receipt / handover close, the completion display
     * derived from that state said the seed was erased —
     * and identity.enc still held it, indefinitely.
     *
     * Safe to call ahead of every transfer operation: the
     * decision re-checks the open-S1 and home-again
     * conditions each time, and a different identity's seed
     * is never touched (the decision keys on the home
     * seed's OWN derived id). Failures are VISIBLE (audit
     * L3): a failed deletion leaves the marker unwritten
     * (the marker asserts the seed is gone; writing it over
     * a surviving seed would lie to every later reader),
     * is recorded in the persistent error log, and is
     * reported in the outcome; the next call retries.
     */
    public static HomeSeedErasure eraseHomeSeedIfSentOut(
            Context context) {
        HomeSeedErasure out = new HomeSeedErasure();
        try {
            // Audit L6/L14: an earlier call may have been
            // cut off anywhere inside the erasure. Its stage
            // record was made durable BEFORE the seed went,
            // so the machine can be resumed here even though
            // the home seed — the fresh due check's key —
            // may already be gone.
            ErasureRecord rec = loadOrAdoptErasureRecord(context);
            if (rec != null) {
                resumeErasure(context, rec, out);
                return out;
            }
            AndroidSeedVault vault = new AndroidSeedVault(context);
            String homeId = homeSeedDeviceId(context);
            if (homeId == null) {
                if (vault.hasSeedFile()) {
                    // A seed file EXISTS but cannot be read:
                    // the due check cannot run. The check
                    // failure is part of the outcome (audit
                    // L6), recorded durably.
                    out.checkFailed = true;
                    out.failure = "the stored home seed could "
                            + "not be read";
                    DepinRuntime.recordError(context,
                            "home_seed_erasure", new IOException(
                                    out.failure));
                    return out;
                }
                // Self-check S-07: the seed is already gone
                // (no seed file), so the fresh due check
                // below — keyed on the home seed's own id —
                // can never re-derive the identity, and an
                // erasure interrupted at "seed_erased" would
                // never reach its marker. The recovery path
                // below is tried only under this conjunction:
                // no sent-out marker exists, AND an erasure
                // record file (current or legacy pending)
                // exists on disk. Reaching this point means
                // loadOrAdoptErasureRecord above returned
                // null, so a record file that exists here
                // was unreadable or unadoptable — a readable
                // one would already have resumed. (No marker
                // and no record file at all is the ordinary
                // not-due: nothing was ever held at home, or
                // a completed erasure already landed.)
                boolean erasureRecordPresent = new File(
                        storeRoot(context), ERASURE_RECORD).exists();
                boolean legacyPendingPresent = new File(
                        storeRoot(context), ERASURE_PENDING).exists();
                if (sentOutMarker(context) == null
                        && (erasureRecordPresent
                                || legacyPendingPresent)) {
                    TransferState st = TransferState.load(
                            storeRoot(context));
                    java.util.List<String> candidates =
                            app.quantusnode.node.depin.core
                                    .TransferRemediation
                                    .sentOutErasureCandidates(st);
                    if (candidates.size() == 1) {
                        String candidate = candidates.get(0);
                        long gen = maxS2Generation(st, candidate);
                        // The seed is verified gone by the
                        // branch itself, so the rebuilt
                        // record starts at "seed_erased";
                        // resumeErasure re-derives due-ness,
                        // re-verifies the seed's absence,
                        // writes the marker and removes the
                        // record through the existing machine.
                        try {
                            writeErasureRecord(context, candidate,
                                    gen, "seed_erased");
                        } catch (Exception e) {
                            out.failure = "the erasure record "
                                    + "could not be re-created "
                                    + "from the transfer state, "
                                    + "so the sent-out marker "
                                    + "was not written: "
                                    + e.getMessage();
                            DepinRuntime.recordError(context,
                                    "home_seed_erasure", e);
                            return out;
                        }
                        ErasureRecord rebuilt = new ErasureRecord();
                        rebuilt.deviceId = candidate;
                        rebuilt.generation = gen;
                        rebuilt.stage = "seed_erased";
                        resumeErasure(context, rebuilt, out);
                        return out;
                    }
                    // Zero candidates, or two or more: there
                    // is no single identity the marker could
                    // truthfully name. Touch nothing; leave
                    // a durable record carrying the count.
                    String why = "the erasure record could "
                            + "not be read or adopted, the home "
                            + "seed is gone and no sent-out "
                            + "marker exists, but the transfer "
                            + "state yields " + candidates.size()
                            + " sent-out erasure candidate(s); "
                            + "no record was re-created and no "
                            + "marker was written (self-check "
                            + "S-07)";
                    if (candidates.size() >= 2) {
                        out.failure = "the sent-out identity "
                                + "awaiting erasure is ambiguous: "
                                + candidates.size()
                                + " candidates in the transfer "
                                + "state";
                    }
                    DepinRuntime.recordError(context,
                            "home_seed_erasure",
                            new IOException(why));
                    return out;
                }
                return out;
            }
            TransferState st = TransferState.load(storeRoot(context));
            String due = app.quantusnode.node.depin.core
                    .TransferRemediation
                    .sentOutIdentityAwaitingErasure(st, homeId);
            if (due == null) {
                return out;
            }
            out.due = true;
            out.deviceId = due;
            long gen = maxS2Generation(st, due);
            // Audit L14: the stage record comes FIRST, and
            // it is not best-effort. If it cannot be made
            // durable, the erasure does not run at all —
            // while the seed exists, the due check above
            // re-derives everything on the next call and
            // tries again.
            try {
                writeErasureRecord(context, due, gen, "pending");
            } catch (Exception e) {
                out.failure = "the erasure could not be "
                        + "recorded durably, so the seed was "
                        + "not touched: " + e.getMessage();
                DepinRuntime.recordError(context,
                        "home_seed_erasure", e);
                return out;
            }
            ErasureRecord fresh = new ErasureRecord();
            fresh.deviceId = due;
            fresh.generation = gen;
            fresh.stage = "pending";
            resumeErasure(context, fresh, out);
            return out;
        } catch (Exception e) {
            out.checkFailed = true;
            out.failure = "the erasure check itself failed: "
                    + e.getMessage();
            DepinRuntime.recordError(context,
                    "home_seed_erasure", e);
            return out;
        }
    }

    /** The highest S2 generation in an identity's sender
     *  history, or -1 when it has none. */
    private static long maxS2Generation(TransferState st,
                                        String deviceId) {
        long gen = -1;
        java.util.Map<String, Object> idSection =
                st.identity(deviceId);
        Object hist = idSection == null
                ? null : idSection.get("sender_history");
        if (hist instanceof java.util.List) {
            for (Object o : (java.util.List<?>) hist) {
                if (o instanceof java.util.Map && "S2".equals(
                        ((java.util.Map<?, ?>) o).get("state"))) {
                    gen = Math.max(gen, TransferState.longOr(
                            (java.util.Map<String, Object>) o,
                            "generation", -1));
                }
            }
        }
        return gen;
    }

    /** Drop an erasure record without acting on it, and
     *  leave a durable trace of the drop (audit L12): the
     *  persistent error log entry names the record's
     *  identity and stage and says why it was discarded,
     *  and the outcome carries the discard, so a later
     *  reader can tell "dropped on purpose, this is why"
     *  from "vanished". A discard never touches any seed. */
    private static void discardErasureRecord(Context context,
                                             ErasureRecord rec,
                                             HomeSeedErasure out,
                                             String why) {
        out.pendingDiscarded = true;
        out.discardedPendingId = rec.deviceId;
        DepinRuntime.recordError(context,
                "home_seed_erasure", new IOException(
                        "erasure record discarded (identity "
                                + rec.deviceId + ", stage "
                                + rec.stage + "): " + why));
        clearErasureRecord(context);
    }

    /**
     * Advance an erasure record through its stages (audit
     * L6, L14). The record names the target; due-ness is
     * RE-DERIVED from the transfer state on every entry —
     * the core decision, evaluated for the record's
     * identity — never taken from the record alone. Two
     * exits discard the record instead of advancing it,
     * and both go through {@link #discardErasureRecord}
     * so the drop is on the record (audit L12):
     *
     *   - the handover the record names no longer stands
     *     in the state (it was undone, or an S1 superseded
     *     it): the record is stale;
     *   - the home seed now derives a DIFFERENT identity
     *     than the record names: that seed is not this
     *     record's to erase, and it is never touched.
     *
     * Stage transitions follow the convergence rule on
     * {@link ErasureRecord}: the seed goes only in the
     * pending stage, the marker only from seed_erased
     * (or re-derived from complete), and the record
     * removes itself only from complete with the marker
     * on disk. The order inside one call is: due-ness is
     * re-derived, the two discard exits above run, and
     * only then is the stage classified as a three-value
     * enum — the discards judge the record as a whole,
     * whatever its stage string says, while every
     * stage-driven action (the erasure itself, the
     * marker, the record's removal) waits for the
     * classification (audit L20: this ordering is the
     * behaviour). The seed's absence is re-verified on
     * every entry before the marker is written or the
     * record removed (audit L16): an unknown stage, or a
     * seed present at a post-erasure stage, is recorded
     * and stops the machine with nothing touched. A
     * failure at any stage leaves the record at that stage
     * and reports through the outcome; the next call
     * resumes from there.
     */
    private static void resumeErasure(Context context,
                                      ErasureRecord rec,
                                      HomeSeedErasure out) {
        String id = rec.deviceId;
        try {
            TransferState st = TransferState.load(storeRoot(context));
            if (app.quantusnode.node.depin.core.TransferRemediation
                    .sentOutIdentityAwaitingErasure(st, id) == null) {
                discardErasureRecord(context, rec, out,
                        "the handover it names no longer "
                                + "stands completed in the "
                                + "transfer state");
                return;
            }
            out.due = true;
            out.deviceId = id;
            AndroidSeedVault vault = new AndroidSeedVault(context);
            String homeId = homeSeedDeviceId(context);
            if (homeId != null && !homeId.equals(id)) {
                // A different identity's seed is home now:
                // the record is stale. Discard it (on the
                // record, audit L12); the seed that is
                // actually here is not this record's to
                // erase.
                discardErasureRecord(context, rec, out,
                        "the home seed now derives a "
                                + "different identity ("
                                + homeId + "); that seed was "
                                + "not touched");
                out.due = false;
                out.deviceId = null;
                return;
            }
            // Audit L16: the stage is a three-value enum,
            // classified by the core
            // (TransferRemediation.erasureStage) before any
            // STAGE-DRIVEN action is taken on it — the due
            // re-derivation and the two discard exits above
            // run first, by design (audit L20). An unknown
            // stage is recorded and stops the machine,
            // touching nothing.
            int stage = app.quantusnode.node.depin.core
                    .TransferRemediation.erasureStage(rec.stage);
            if (stage == app.quantusnode.node.depin.core
                    .TransferRemediation.ERASURE_STAGE_UNKNOWN) {
                out.checkFailed = true;
                out.held = true;
                out.failure = "the erasure record carries "
                        + "an unknown stage (" + rec.stage
                        + "); nothing was touched";
                DepinRuntime.recordError(context,
                        "home_seed_erasure",
                        new IOException(out.failure
                                + " (identity " + id + ")"));
                return;
            }
            if (app.quantusnode.node.depin.core.TransferRemediation
                    .erasureSeedMayBeErased(stage)) {
                // Stage "pending": the erasure itself is
                // what remains — or the seed is already gone
                // and only the stage advance remains.
                if (homeId != null) {
                    if (!vault.eraseSeed()) {
                        out.failure = "the stored home seed "
                                + "could not be deleted";
                        DepinRuntime.recordError(context,
                                "home_seed_erasure",
                                new IOException(out.failure
                                        + " (identity " + id
                                        + ")"));
                        return;
                    }
                    // Audit L17: the outcome reports the
                    // erasure from the moment it is true.
                    out.erased = true;
                } else if (vault.hasSeedFile()) {
                    // A seed file exists but cannot be
                    // read: do not advance toward a marker
                    // claiming it is gone.
                    out.checkFailed = true;
                    out.failure = "the stored home seed "
                            + "could not be read";
                    DepinRuntime.recordError(context,
                            "home_seed_erasure",
                            new IOException(out.failure));
                    return;
                } else {
                    out.erased = true;   // already gone
                }
                writeErasureRecord(context, id, rec.generation,
                        "seed_erased");
                rec.stage = "seed_erased";
                stage = app.quantusnode.node.depin.core
                        .TransferRemediation
                        .ERASURE_STAGE_SEED_ERASED;
            }
            // Stages "seed_erased" and "complete": before
            // anything derives from the stage, the seed's
            // absence is RE-VERIFIED on this entry (audit
            // L16). The marker asserts the seed is gone, so
            // the assertion is re-probed here — a seed that
            // is present, or present but unreadable, at
            // either stage stops the machine, recorded, with
            // nothing touched.
            String probeId = homeSeedDeviceId(context);
            boolean seedPresent = probeId != null;
            boolean seedUnreadable = !seedPresent
                    && vault.hasSeedFile();
            if (!app.quantusnode.node.depin.core.TransferRemediation
                    .erasureMarkerMayBeWritten(
                            stage, seedPresent, seedUnreadable)) {
                out.checkFailed = true;
                out.held = true;
                // Audit L22: on the pending-stage fall-through
                // this stop is reached AFTER eraseSeed()
                // succeeded and out.erased was set — but the
                // re-probe just failed to confirm the seed's
                // absence, so "erased" is not a claim this
                // outcome may carry. erased is reset, so the
                // flags are mutually exclusive and the held
                // wording is the one shown.
                out.erased = false;
                out.failure = seedUnreadable
                        ? "the stored home seed could not "
                                + "be read"
                        : "a home seed is present at erasure "
                                + "stage " + rec.stage
                                + "; the marker was not "
                                + "written";
                DepinRuntime.recordError(context,
                        "home_seed_erasure",
                        new IOException(out.failure
                                + " (identity " + id + ")"));
                return;
            }
            out.erased = true;
            if (stage == app.quantusnode.node.depin.core
                    .TransferRemediation.ERASURE_STAGE_COMPLETE) {
                // The marker was written before the crash;
                // only the record's own removal remains —
                // after confirming the marker is still on
                // disk (rewriting it if it is not: the seed
                // is verified gone, so the marker is the
                // truthful record of that).
                java.util.Map<String, Object> marker =
                        sentOutMarker(context);
                if (marker == null || !id.equals(
                        TransferState.stringOr(
                                marker, "device_id", null))) {
                    writeSentOutMarker(context, id,
                            rec.generation);
                }
                out.markerWritten = true;
                clearErasureRecord(context);
                return;
            }
            // Stage "seed_erased": the seed is verified
            // gone; the marker is the remaining write.
            writeSentOutMarker(context, id, rec.generation);
            out.markerWritten = true;
            writeErasureRecord(context, id, rec.generation,
                    "complete");
            clearErasureRecord(context);
        } catch (Exception e) {
            out.failure = "the interrupted erasure could not "
                    + "be finished: " + e.getMessage();
            DepinRuntime.recordError(context,
                    "home_seed_erasure", e);
        }
    }

    /** Whether the completed-handover display is TRUE for
     *  the sent-out marker's identity (audit L7): the bare
     *  condition — no binding names an identity, and a
     *  marker exists — also matches a DAMAGED store (the
     *  init record lost, traces and the marker left), where
     *  announcing "the handover is complete" hides the
     *  damage behind a success message. Completion is
     *  claimed only when the records agree: an init record
     *  exists, no binding is active, and the transfer state
     *  carries the S2 that sent the marker's identity out.
     *  Anything else falls back to the gate's own held
     *  reason. */
    public static boolean sentOutCompletionHolds(Context context) {
        try {
            java.util.Map<String, Object> marker =
                    sentOutMarker(context);
            if (marker == null) {
                return false;
            }
            String id = TransferState.stringOr(
                    marker, "device_id", null);
            if (id == null) {
                return false;
            }
            File root = storeRoot(context);
            if (TransferState.loadInit(root) == null) {
                return false;
            }
            TransferState st = TransferState.load(root);
            if (st.active() != null) {
                return false;
            }
            return maxS2Generation(st, id) >= 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------- durable inbox (section 4.4 (b)) ----------------

    public static File inboxDir(Context context) {
        File dir = new File(storeRoot(context), "transfer-io");
        dir.mkdirs();
        return dir;
    }

    public static void saveInbox(Context context, String name,
                                 byte[] bytes) throws IOException {
        TransferIo.writeFileSync(new File(inboxDir(context), name), bytes);
    }

    public static byte[] readInbox(Context context, String name)
            throws IOException {
        File f = new File(inboxDir(context), name);
        if (!f.exists()) return null;
        return TransferIo.readAll(f, TransferPackage.MAX_PACKAGE_BYTES);
    }

    public static byte[] messageBytes(Map<String, Object> message) {
        return TransferCodec.jcs(message);
    }

    public static Map<String, Object> parseMessage(byte[] bytes) {
        return TransferPackage.parseMessage(bytes);
    }
}
