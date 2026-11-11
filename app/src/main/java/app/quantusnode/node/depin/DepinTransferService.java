package app.quantusnode.node.depin;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import app.quantusnode.node.depin.core.TransferPackage;
import app.quantusnode.node.depin.core.TransferReceiver;
import app.quantusnode.node.depin.core.TransferSender;
import app.quantusnode.node.depin.core.TransferState;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The :depin process's host for device-transfer operations
 * (integration design section 4; audit H-1).
 *
 * Why a separate bound service rather than a handler inside
 * DepinObserverService: the observer's lifecycle is the
 * section 3.1 claim protocol — it starts on a launch claim
 * and stops itself on disable. The transfer flow itself
 * disables the observer (the sender freeze is part of
 * exporting a package), so transfer operations must keep a
 * host AFTER the observer has stopped: permit issuance,
 * receipt intake and cancellation all happen while this
 * app's own observer is frozen in S1. A bound service in the
 * same :depin process gives those operations a host whose
 * lifetime is the settings screen's binding, without touching
 * the observer's audited lifecycle — and the seed boundary
 * the design draws is a PROCESS boundary ("seed-handling code
 * closed inside the :depin process"), which this placement
 * satisfies exactly.
 *
 * Protocol (DepinIpc, mirroring MSG_EXPORT_BUNDLE): the main
 * process sends MSG_TRANSFER_OP with a T_OP_* code; the
 * operation runs here, serialised on a single-thread executor;
 * the reply is MSG_TRANSFER_RESULT carrying the outcome and
 * non-secret metadata only. Files cross as staged files under
 * files/depin (the picked input at DepinFiles.transferInputFile,
 * produced messages at DepinFiles.transferOutputFile, the
 * exported package in the core's own outbox). No seed, wrapped
 * or plain, ever crosses the binder.
 *
 * The transfer layer is introduced on first use, exactly as
 * when the operations ran in-process: an operation that finds
 * no initialisation record runs the legacy-upgrade
 * introduction first (its positive legacy-journal confirmation
 * inside DepinTransferRuntime.initialise).
 */
public final class DepinTransferService extends Service {

    private HandlerThread ipcThread;
    private Messenger messenger;

    /** Operations run on this single thread: two transfer
     *  operations never overlap (the service-side half of the
     *  audit H-3 serialisation; the UI serialises its requests
     *  as well). */
    private final ExecutorService transferExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "depin-transfer");
                t.setDaemon(true);
                return t;
            });

    @Override
    public void onCreate() {
        super.onCreate();
        ipcThread = new HandlerThread("depin-transfer-ipc");
        ipcThread.start();
        Handler ipcHandler = new Handler(ipcThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                if (msg.what == DepinIpc.MSG_TRANSFER_OP
                        && msg.replyTo != null) {
                    final Messenger replyTo = msg.replyTo;
                    final Bundle request = msg.getData() != null
                            ? new Bundle(msg.getData())
                            : new Bundle();
                    final int op = request.getInt(
                            DepinIpc.KEY_T_OP, -1);
                    transferExecutor.execute(
                            () -> deliver(replyTo, op, request));
                } else {
                    super.handleMessage(msg);
                }
            }
        };
        messenger = new Messenger(ipcHandler);
    }

    @Override
    public IBinder onBind(Intent intent) {
        // G-01: the settings' display binding is synced to the
        // transfer-active identity HERE, in the :depin process
        // that owns the settings — it used to run in
        // MainActivity.onCreate (main process), which broke
        // the ":depin is the only DepinSettings writer"
        // invariant the F-03 fix claimed. A bind happens on
        // the first transfer request of a UI session, i.e.
        // before any transfer state is displayed or acted on,
        // and the observer's initialisation performs the same
        // sync, so a completed transfer's binding is current
        // on every path that can show it. The sync never
        // touches the enable authorisation (see
        // DepinTransferRuntime.syncSettingsBindingToTransferActive).
        transferExecutor.execute(() -> {
            try {
                DepinTransferRuntime
                        .syncSettingsBindingToTransferActive(this);
            } catch (Throwable ignored) {
                // A failed sync leaves the previous binding in
                // place; the observer's next initialisation
                // retries it. Never fail a bind over it.
            }
        });
        return messenger != null ? messenger.getBinder() : null;
    }

    @Override
    public void onDestroy() {
        transferExecutor.shutdown();
        if (ipcThread != null) {
            ipcThread.quitSafely();
        }
        super.onDestroy();
    }

    // ---------------- operation execution ----------------

    private void deliver(Messenger replyTo, int op, Bundle request) {
        Bundle data = new Bundle();
        data.putInt(DepinIpc.KEY_T_OP, op);
        try {
            ensureInitialised();
            // Audit M10: retry any superseded-copy sweep a
            // crashed closure left behind, ahead of the
            // operation. Idempotent and self-recording; it
            // never fails the operation.
            DepinTransferRuntime.sweepSupersededSeedCopies(this);
            // Final check round 1, F-05: run the receiver's
            // crash recovery ahead of the operation. A trial
            // interrupted between judgement and
            // materialisation can be completed by recover()
            // and by nothing else — the confirms refuse it —
            // and until this wiring recover() was invoked
            // from nowhere in either app. Same contract as
            // the sweep: idempotent, self-recording, never
            // fails the operation.
            DepinTransferRuntime.runRecovery(this);
            // Final check round 2, G-07: if the home
            // identity has been sent onward for good, erase
            // its home seed copy (the staged machine in
            // DepinTransferRuntime resumes an interrupted
            // erasure from its durable record). Runs ahead
            // of every operation — and again after a closure
            // below — so the erasure lands as soon as it is
            // due and is retried until it lands. Never fails
            // the operation; failures are on the durable
            // record via the outcome's own logging.
            DepinTransferRuntime.eraseHomeSeedIfSentOut(this);
            runOp(op, request, data);
            if (op == DepinIpc.T_OP_CLOSE_ONESHOT_SENDER
                    || op == DepinIpc.T_OP_INTAKE_RECEIPT) {
                // The closure this operation just completed
                // is what makes the erasure due: run it now
                // rather than at the next operation, so the
                // completion the UI is about to show is true
                // when it is shown.
                DepinTransferRuntime.eraseHomeSeedIfSentOut(this);
            }
            if (op == DepinIpc.T_OP_CLOSE_ONESHOT_SENDER
                    || op == DepinIpc.T_OP_INTAKE_RECEIPT) {
                disableRecordingIfEnabledIdentityLeft();
            }
            if (!data.containsKey(DepinIpc.KEY_T_RESULT)) {
                data.putInt(DepinIpc.KEY_T_RESULT,
                        DepinIpc.T_RESULT_OK);
            }
        } catch (TransferReceiver.VerificationPausedException p) {
            // Section 3.5 pause: a resumable stop. Wait pauses
            // during a stage are already absorbed inside the
            // operation (DepinTransferRuntime's loop), so a
            // pause that reaches this handler is the budget
            // kind (or the loop's own wall cap): the durable
            // cursor is saved by the core, and the UI re-issues
            // the same operation to continue once time has
            // passed.
            data.putInt(DepinIpc.KEY_T_RESULT,
                    DepinIpc.T_RESULT_PAUSED);
            data.putString(DepinIpc.KEY_T_ERROR_CLASS,
                    p.getClass().getSimpleName());
            data.putString(DepinIpc.KEY_T_ERROR, p.getMessage());
        } catch (Throwable t) {
            DepinRuntime.recordError(this, "transfer_op_" + op, t);
            data.putInt(DepinIpc.KEY_T_RESULT,
                    DepinIpc.T_RESULT_ERROR);
            data.putString(DepinIpc.KEY_T_ERROR_CLASS,
                    t.getClass().getSimpleName());
            data.putString(DepinIpc.KEY_T_ERROR, t.getMessage());
            data.putString(DepinIpc.KEY_T_ERROR_CHAIN,
                    errorChain(t));
            data.putString(DepinIpc.KEY_T_REASON,
                    TransferErrorReport.classify(t));
        } finally {
            // Every transfer operation can move the writer
            // resolution (a completed transfer changes which
            // identity is live; an export closes the gate for
            // its identity): the observer runtime's cached
            // journal construction — success or failure — must
            // not outlive the state it was resolved against
            // (progress file section 9).
            DepinRuntime.invalidateJournal();
        }
        Message reply = Message.obtain(
                null, DepinIpc.MSG_TRANSFER_RESULT);
        reply.setData(data);
        try {
            replyTo.send(reply);
        } catch (RemoteException ignored) {
            // The caller went away. The operation's effects are
            // durable transfer state; nothing is rolled back,
            // exactly as when the caller was this process.
        }
    }

    /**
     * Audit Low: when the identity recording was enabled for
     * leaves this device for good (a sender closure of either
     * kind just succeeded), clear its enable flag. Otherwise
     * the flag stays bound to a departed identity, and the
     * observer's next start walks the held-gate path into a
     * REASON_JOURNAL_OPEN self-stop ("transfer gate:
     * no_active_binding") instead of the clean OFF that
     * matches reality — the identity is gone, not broken.
     * Fires only when the enabled identity has an S2 in its
     * sender history and no longer holds the active binding.
     */
    private void disableRecordingIfEnabledIdentityLeft() {
        try {
            String enabledId = DepinSettings.enabledDeviceId(this);
            if (enabledId == null || !DepinSettings.isEnabled(this)) {
                return;
            }
            TransferState st = TransferState.load(
                    DepinTransferRuntime.storeRoot(this));
            Map<String, Object> active = st.active();
            if (active != null && enabledId.equals(
                    TransferState.stringOr(active, "device_id", null))) {
                return; // still the live identity on this device
            }
            Map<String, Object> idSection = st.identity(enabledId);
            Object hist = idSection == null
                    ? null : idSection.get("sender_history");
            boolean sent = false;
            if (hist instanceof java.util.List) {
                for (Object o : (java.util.List<?>) hist) {
                    if (o instanceof Map && "S2".equals(
                            ((Map<?, ?>) o).get("state"))) {
                        sent = true;
                    }
                }
            }
            if (sent) {
                DepinSettings.setDisabled(this);
            }
        } catch (Exception ignored) {
            // Best effort: every strict path re-derives the
            // state itself.
        }
    }

    /** Flatten an exception's cause chain for the error
     *  reply: "Class: message <- Class: message <- ..." —
     *  class names and messages only (never stack frames,
     *  never any key or payload material), bounded in depth
     *  and length. The UI's error-file export carries this so
     *  a failure can be diagnosed from the file alone. */
    private static String errorChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        for (int i = 0; i < 8 && cur != null; i++) {
            if (i > 0) {
                sb.append(" <- ");
            }
            sb.append(cur.getClass().getSimpleName())
                    .append(": ")
                    .append(String.valueOf(cur.getMessage()));
            if (sb.length() > 1024) {
                sb.setLength(1024);
                break;
            }
            cur = cur.getCause();
        }
        return sb.toString();
    }

    /** Introduce the transfer layer on first use (legacy-
     *  upgrade mode), as the in-process flow did before every
     *  operation. The guard keys on COMPLETENESS, not existence:
     *  the init record is saved in "preparing" state before the
     *  introduction's work runs, so a process death during the
     *  first introduction (the legacy journal's full verification
     *  takes seconds) used to leave a record that no later
     *  operation would ever resume — every transfer operation
     *  then ran against a half-introduced store. The core's
     *  initialise() resumes a preparing record idempotently, so
     *  anything short of "complete" (re)runs it. */
    private void ensureInitialised() throws Exception {
        TransferState.InitRecord init = TransferState.loadInit(
                DepinTransferRuntime.storeRoot(this));
        if (init == null || !"complete".equals(init.state)) {
            DepinTransferRuntime.initialise(this);
        }
    }

    private void runOp(int op, Bundle request, Bundle data)
            throws Exception {
        switch (op) {
            // ---------------- sender role ----------------
            case DepinIpc.T_OP_EXPORT_PACKAGE: {
                byte[] seed = DepinTransferRuntime.loadSenderSeed(this);
                try {
                    TransferSender.ExportResult r =
                            DepinTransferRuntime.sender(this)
                                    .exportPackage(seed);
                    DepinTransferRuntime.lastExport = r;
                    data.putString(DepinIpc.KEY_T_CODE_DISPLAY,
                            r.transferCodeDisplay);
                    data.putString(DepinIpc.KEY_T_PACKAGE_PATH,
                            r.packageFile.getAbsolutePath());
                    data.putString(DepinIpc.KEY_T_SAVE_NAME,
                            "transfer-package.json");
                    data.putLong(DepinIpc.KEY_T_GENERATION,
                            r.generation);
                } finally {
                    java.util.Arrays.fill(seed, (byte) 0);
                }
                break;
            }
            case DepinIpc.T_OP_EXPORT_PACKAGE_ONESHOT: {
                // One-shot handover (simple mode): the same
                // export, the record marked oneshot. The reply
                // shape is the strict export's.
                byte[] seed = DepinTransferRuntime.loadSenderSeed(this);
                try {
                    TransferSender.ExportResult r =
                            DepinTransferRuntime.sender(this)
                                    .exportPackageOneshot(seed);
                    DepinTransferRuntime.lastExport = r;
                    data.putString(DepinIpc.KEY_T_CODE_DISPLAY,
                            r.transferCodeDisplay);
                    data.putString(DepinIpc.KEY_T_PACKAGE_PATH,
                            r.packageFile.getAbsolutePath());
                    data.putString(DepinIpc.KEY_T_SAVE_NAME,
                            "transfer-package.json");
                    data.putLong(DepinIpc.KEY_T_GENERATION,
                            r.generation);
                } finally {
                    java.util.Arrays.fill(seed, (byte) 0);
                }
                break;
            }
            case DepinIpc.T_OP_EXPORT_PACKAGE_ONESHOT_AGAIN: {
                // E7 re-export from the frozen tip: a fresh
                // code for the same generation; the previous
                // package remains valid.
                String deviceId = openOneshotSenderDeviceId();
                if (deviceId == null) {
                    throw new IllegalStateException(
                            "no one-shot handover is open");
                }
                byte[] seed = DepinTransferRuntime.loadSenderSeed(this);
                try {
                    TransferSender.ExportResult r =
                            DepinTransferRuntime.sender(this)
                                    .exportPackageOneshotAgain(seed);
                    DepinTransferRuntime.lastExport = r;
                    data.putString(DepinIpc.KEY_T_CODE_DISPLAY,
                            r.transferCodeDisplay);
                    data.putString(DepinIpc.KEY_T_PACKAGE_PATH,
                            r.packageFile.getAbsolutePath());
                    data.putString(DepinIpc.KEY_T_SAVE_NAME,
                            "transfer-package.json");
                    data.putLong(DepinIpc.KEY_T_GENERATION,
                            r.generation);
                } finally {
                    java.util.Arrays.fill(seed, (byte) 0);
                }
                break;
            }
            case DepinIpc.T_OP_CLOSE_ONESHOT_SENDER: {
                String deviceId = openOneshotSenderDeviceId();
                if (deviceId == null) {
                    throw new IllegalStateException(
                            "no one-shot handover is open");
                }
                // E4: the erasure requires the confirmation
                // number the receiver displayed.
                String code = request.getString(
                        DepinIpc.KEY_T_COMMIT_CODE);
                DepinTransferRuntime.sender(this)
                        .closeOneshotHandover(deviceId, code);
                break;
            }
            case DepinIpc.T_OP_CANCEL_ONESHOT_SENDER: {
                String deviceId = openOneshotSenderDeviceId();
                if (deviceId == null) {
                    throw new IllegalStateException(
                            "no one-shot handover is open");
                }
                DepinTransferRuntime.sender(this)
                        .cancelOneshotHandover(deviceId);
                break;
            }
            case DepinIpc.T_OP_ISSUE_PERMIT: {
                byte[] raw = DepinTransferRuntime.readInbox(
                        this, "notice.json");
                if (raw == null) {
                    throw new IllegalStateException(
                            "no notice has been imported");
                }
                Map<String, Object> notice =
                        DepinTransferRuntime.parseMessage(raw);
                byte[] seed = DepinTransferRuntime.loadSenderSeed(this);
                try {
                    Map<String, Object> permit =
                            DepinTransferRuntime.sender(this)
                                    .issuePermit(seed, notice);
                    stageOutput(DepinTransferRuntime.messageBytes(
                            permit));
                    data.putString(DepinIpc.KEY_T_SAVE_NAME,
                            "transfer-permit.json");
                } finally {
                    java.util.Arrays.fill(seed, (byte) 0);
                }
                break;
            }
            case DepinIpc.T_OP_CANCEL_SENDER: {
                byte[] seed = DepinTransferRuntime.loadSenderSeed(this);
                try {
                    Map<String, Object> token =
                            DepinTransferRuntime.sender(this)
                                    .cancelBeforePermit(seed);
                    stageOutput(DepinTransferRuntime.messageBytes(
                            token));
                    data.putString(DepinIpc.KEY_T_SAVE_NAME,
                            "transfer-cancel.json");
                } finally {
                    java.util.Arrays.fill(seed, (byte) 0);
                }
                break;
            }
            case DepinIpc.T_OP_INTAKE_RECEIPT: {
                Map<String, Object> receipt =
                        DepinTransferRuntime.parseMessage(readInput());
                byte[] seed = DepinTransferRuntime.loadSenderSeed(this);
                try {
                    Map<String, Object> ack =
                            DepinTransferRuntime.sender(this)
                                    .onReceipt(seed, receipt);
                    stageOutput(DepinTransferRuntime.messageBytes(
                            ack));
                    data.putString(DepinIpc.KEY_T_SAVE_NAME,
                            "transfer-ack.json");
                } finally {
                    java.util.Arrays.fill(seed, (byte) 0);
                }
                break;
            }
            case DepinIpc.T_OP_INTAKE_ABANDON: {
                DepinTransferRuntime.sender(this).onAbandon(
                        DepinTransferRuntime.parseMessage(readInput()));
                break;
            }
            case DepinIpc.T_OP_REPRESENT_ACK: {
                long gen = request.getLong(
                        DepinIpc.KEY_T_GENERATION, -1);
                Map<String, Object> ack =
                        DepinTransferRuntime.sender(this)
                                .rePresentAck(gen);
                if (ack == null) {
                    throw new IllegalStateException(
                            "no completed send to re-present an ack for");
                }
                stageOutput(DepinTransferRuntime.messageBytes(ack));
                data.putString(DepinIpc.KEY_T_SAVE_NAME,
                        "transfer-ack.json");
                break;
            }
            case DepinIpc.T_OP_INTAKE_NOTICE: {
                // Durable inbox retention (section 4.4 (b)): the
                // notice is kept, unprocessed, until the permit
                // is issued against it.
                DepinTransferRuntime.saveInbox(
                        this, "notice.json", readInput());
                break;
            }
            // ---------------- receiver role ----------------
            case DepinIpc.T_OP_STAGE_PACKAGE: {
                String code = request.getString(DepinIpc.KEY_T_CODE);
                // Wait-absorbing stage (internal.10): one
                // request now runs the section 3.5 sliced
                // re-verification to completion, idling through
                // each slice's wait obligation, instead of
                // returning a pause per slice. A budget pause
                // still returns T_RESULT_PAUSED.
                TransferReceiver.IntakeResult info =
                        DepinTransferRuntime.stagePackageResuming(
                                this, DepinFiles.transferInputFile(this),
                                code);
                data.putString(DepinIpc.KEY_T_DEVICE_ID, info.deviceId);
                data.putLong(DepinIpc.KEY_T_GENERATION, info.generation);
                data.putString(DepinIpc.KEY_T_PACKAGE_SHA256,
                        info.packageSha256);
                data.putLong(DepinIpc.KEY_T_CHECKPOINTS,
                        info.checkpointCount);
                data.putLong(DepinIpc.KEY_T_FIRST_TS,
                        info.firstTimestampUnix);
                data.putLong(DepinIpc.KEY_T_TAIL_TS,
                        info.tailTimestampUnix);
                break;
            }
            case DepinIpc.T_OP_COMPLETE_INTAKE: {
                DepinTransferRuntime.receiver(this).completeIntake(
                        request.getString(DepinIpc.KEY_T_DEVICE_ID),
                        request.getLong(DepinIpc.KEY_T_GENERATION, -1),
                        request.getString(
                                DepinIpc.KEY_T_PACKAGE_SHA256));
                break;
            }
            case DepinIpc.T_OP_INTAKE_PERMIT: {
                byte[] raw = readInput();
                DepinTransferRuntime.saveInbox(
                        this, "permit.json", raw);
                DepinTransferRuntime.receiver(this).intakePermit(
                        DepinTransferRuntime.parseMessage(raw));
                break;
            }
            case DepinIpc.T_OP_INTAKE_ACK: {
                DepinTransferRuntime.receiver(this).intakeAck(
                        DepinTransferRuntime.parseMessage(readInput()));
                break;
            }
            case DepinIpc.T_OP_INTAKE_CANCEL_TOKEN: {
                DepinTransferRuntime.receiver(this).intakeCancelToken(
                        DepinTransferRuntime.parseMessage(readInput()));
                break;
            }
            case DepinIpc.T_OP_EXPORT_NOTICE: {
                Object[] ref = receiverRef(REF_OPEN);
                if (ref == null) {
                    throw new IllegalStateException(
                            "no staged transfer");
                }
                Map<String, Object> notice =
                        DepinTransferRuntime.receiver(this).buildNotice(
                                (String) ref[0], (Long) ref[1]);
                stageOutput(DepinTransferRuntime.messageBytes(notice));
                data.putString(DepinIpc.KEY_T_SAVE_NAME,
                        "transfer-notice.json");
                break;
            }
            case DepinIpc.T_OP_CONFIRM: {
                Object[] ref = receiverRef(REF_OPEN);
                if (ref == null) {
                    throw new IllegalStateException(
                            "no staged transfer");
                }
                DepinTransferRuntime.receiver(this).confirmTrial(
                        (String) ref[0], (Long) ref[1]);
                break;
            }
            case DepinIpc.T_OP_CONFIRM_ONESHOT: {
                // One-shot confirm (simple mode): with an
                // explicit staged reference (the stage dialog's
                // confirm), the intake completion is part of
                // the one flow — completeIntake is idempotent,
                // so an already-notified record passes through.
                // Without one, the open record is resolved as
                // the strict confirm resolves it (it must
                // already be notified; the core refuses
                // otherwise).
                String deviceId = request.getString(
                        DepinIpc.KEY_T_DEVICE_ID);
                long gen = request.getLong(
                        DepinIpc.KEY_T_GENERATION, -1);
                if (deviceId != null) {
                    DepinTransferRuntime.receiver(this).completeIntake(
                            deviceId, gen,
                            request.getString(
                                    DepinIpc.KEY_T_PACKAGE_SHA256));
                } else {
                    Object[] ref = receiverRef(REF_OPEN);
                    if (ref == null) {
                        throw new IllegalStateException(
                                "no staged transfer");
                    }
                    deviceId = (String) ref[0];
                    gen = (Long) ref[1];
                }
                DepinTransferRuntime.receiver(this)
                        .confirmOneshot(deviceId, gen);
                // E4: return the confirmation number the UI
                // must show (it is committed in the record; the
                // snapshot carries it too for later display).
                try {
                    app.quantusnode.node.depin.core.TransferState st =
                            app.quantusnode.node.depin.core.TransferState
                                    .load(DepinFiles.depinDir(this));
                    java.util.Map<String, Object> rec =
                            st.receiver(deviceId, gen);
                    String cc = rec == null ? null
                            : app.quantusnode.node.depin.core.TransferState
                                    .stringOr(rec, "commit_code", null);
                    if (cc != null) {
                        data.putString(DepinIpc.KEY_T_COMMIT_CODE, cc);
                    }
                } catch (Throwable ignored) {
                    // The confirm itself succeeded; the number
                    // remains readable from the derived state.
                }
                break;
            }
            case DepinIpc.T_OP_EXPORT_RECEIPT: {
                Object[] ref = receiverRef(REF_R2);
                if (ref == null) {
                    throw new IllegalStateException(
                            "no confirmed transfer");
                }
                Map<String, Object> receipt =
                        DepinTransferRuntime.receiver(this).buildReceipt(
                                (String) ref[0], (Long) ref[1]);
                stageOutput(DepinTransferRuntime.messageBytes(receipt));
                data.putString(DepinIpc.KEY_T_SAVE_NAME,
                        "transfer-receipt.json");
                break;
            }
            case DepinIpc.T_OP_EXPORT_ABANDON: {
                Object[] ref = receiverRef(REF_ABANDON);
                if (ref == null) {
                    throw new IllegalStateException(
                            "no staged transfer");
                }
                Map<String, Object> abandon =
                        DepinTransferRuntime.issueReceiverAbandon(
                                this, (String) ref[0], (Long) ref[1]);
                stageOutput(DepinTransferRuntime.messageBytes(abandon));
                data.putString(DepinIpc.KEY_T_SAVE_NAME,
                        "transfer-abandon.json");
                break;
            }
            default:
                throw new IllegalArgumentException(
                        "unknown transfer operation " + op);
        }
    }

    // Receiver-record resolution purposes (audit F3-M2).
    private static final int REF_OPEN = 0;
    private static final int REF_R2 = 1;
    private static final int REF_ABANDON = 2;

    /**
     * Resolve the receiver record an operation acts on, as
     * {deviceId, generation}, or null (audit F3-M2).
     *
     * Generation numbers are PER-IDENTITY sequences, so the
     * previous rule — the largest generation across ALL
     * identities — targeted the wrong identity whenever two
     * identities had receiver history (a completed gen 3 of
     * identity A shadowed an open gen 1 of identity B, and
     * notice / confirm / receipt / abandon all acted on A).
     * The resolution is by recorded STATE instead:
     * REF_OPEN picks a record with no closure (the receiving
     * flow still in progress); REF_R2 picks the record
     * holding its R2 confirmation (the receipt's target);
     * REF_ABANDON picks an open record, else a refusal-closed
     * one (the re-presentation target of core
     * TransferAbandon). Remaining ties are broken
     * deterministically — device ids in sorted order, then
     * that identity's highest generation — never by comparing
     * generations across identities. The companion's receiver
     * UI and this app's transfer status line resolve by the
     * same rule.
     */
    private Object[] receiverRef(int purpose) throws Exception {
        TransferState st = TransferState.load(
                DepinTransferRuntime.storeRoot(this));
        final int open = 0, r2 = 1, refusal = 2;
        int[] kinds = purpose == REF_R2
                ? new int[]{r2}
                : purpose == REF_ABANDON
                        ? new int[]{open, refusal}
                        : new int[]{open};
        java.util.List<String> ids =
                new java.util.ArrayList<>(st.identities().keySet());
        java.util.Collections.sort(ids);
        for (int kind : kinds) {
            for (String deviceId : ids) {
                java.util.List<Long> gens =
                        st.receiverGenerations(deviceId);
                for (int i = gens.size() - 1; i >= 0; i--) {
                    long g = gens.get(i);
                    Map<String, Object> rec = st.receiver(deviceId, g);
                    if (rec == null) {
                        continue;
                    }
                    Map<String, Object> closure =
                            TransferState.child(rec, "closure");
                    boolean match;
                    if (kind == r2) {
                        match = rec.get("r2") != null;
                    } else if (kind == refusal) {
                        match = closure != null && "refusal".equals(
                                TransferState.stringOr(
                                        closure, "kind", null));
                    } else {
                        match = closure == null;
                    }
                    if (match) {
                        return new Object[]{deviceId, g};
                    }
                }
            }
        }
        return null;
    }

    /** The identity whose open sender record is a one-shot
     *  handover (device ids in sorted order, first match), or
     *  null. A resolution convenience only: the core close /
     *  cancel re-validate the record's mode before acting. */
    private String openOneshotSenderDeviceId() throws Exception {
        TransferState st = TransferState.load(
                DepinTransferRuntime.storeRoot(this));
        java.util.List<String> ids =
                new java.util.ArrayList<>(st.identities().keySet());
        java.util.Collections.sort(ids);
        for (String deviceId : ids) {
            Map<String, Object> sender = st.sender(deviceId);
            if (sender != null && "oneshot".equals(
                    TransferState.stringOr(sender, "mode", null))) {
                return deviceId;
            }
        }
        return null;
    }

    /** The picked input the UI staged for this operation. */
    private byte[] readInput() throws Exception {
        File f = DepinFiles.transferInputFile(this);
        return app.quantusnode.node.depin.core.TransferIo.readAll(
                f, TransferPackage.MAX_PACKAGE_BYTES);
    }

    /** Stage a produced message for the UI's save picker. */
    private void stageOutput(byte[] bytes) throws Exception {
        app.quantusnode.node.depin.core.TransferIo.writeFileSync(
                DepinFiles.transferOutputFile(this), bytes);
    }
}
