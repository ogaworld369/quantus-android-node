package app.quantusnode.node.depin;

import android.content.Context;

import app.quantusnode.node.depin.core.JournalStore;

import java.io.File;

/**
 * The one place that knows where the DePIN layer's files live.
 *
 * Everything the observer owns sits under filesDir/depin, a
 * directory of its own, so the observer's state can be listed,
 * backed up, quarantined or wiped without ever touching the
 * node's own files (and the reverse). The supervisor has a
 * sibling directory, filesDir/depin-supervisor, because its
 * bookkeeping belongs to a different concern and must survive
 * independently of the observer's data directory.
 *
 * The application context is resolved on every call and never
 * retained: these helpers hold no state at all, and a static
 * holder that kept a caller's context would leak an Activity
 * for the lifetime of the process.
 */
public final class DepinFiles {
    private DepinFiles() {}

    /** The observer's private directory (created on demand). */
    public static File depinDir(Context context) {
        File dir = new File(
                context.getApplicationContext().getFilesDir(), "depin");
        // mkdirs is idempotent; its result is deliberately not
        // checked here because every consumer opens a file inside
        // the directory next and surfaces a real I/O failure at
        // that point, with the file name attached.
        dir.mkdirs();
        return dir;
    }

    /** The checkpoint journal (the core store owns its format). */
    public static File journalFile(Context context) {
        return new File(depinDir(context), JournalStore.FILE_NAME);
    }

    /** The Keystore-wrapped identity seed (see AndroidSeedVault). */
    public static File identityFile(Context context) {
        return new File(depinDir(context), "identity.enc");
    }

    /** The RPC attempt budget log (see RpcAttemptLog). */
    public static File attemptLogFile(Context context) {
        return new File(depinDir(context), "rpc-attempts.jsonl");
    }

    /** The user's confirmed attempt-log reset request (design
     *  section 2.3 repair; G-11): a bare flag file the UI side
     *  writes after the user's explicit confirmation and the
     *  :depin process consumes (deletes) at the observer's
     *  next initialisation, performing resetCorrupt() there.
     *  It carries no settings content and authorises exactly
     *  one repair. */
    public static File attemptResetRequestFile(Context context) {
        return new File(depinDir(context), "attempt-reset-request");
    }

    /** The observer's persistent crash / error log file. */
    public static File crashLogFile(Context context) {
        return new File(depinDir(context), "depin-crash.log");
    }

    /** The cross-process status snapshot (see DepinStatus). */
    public static File statusFile(Context context) {
        return new File(depinDir(context), "status.json");
    }

    /** The staged proof bundle (see DepinBundleExport): built
     *  and self-checked by the :depin process, then streamed by
     *  the main process to the destination the user picked.
     *  A bundle is public data by design; it carries no secret. */
    public static File pendingBundleFile(Context context) {
        return new File(depinDir(context), "pending-bundle.json");
    }

    /** The transfer layer's I/O directory inside the store
     *  root: the durable inbox lives here (see
     *  DepinTransferRuntime.inboxDir), and so do the two IPC
     *  staging files below. */
    public static File transferIoDir(Context context) {
        File dir = new File(depinDir(context), "transfer-io");
        dir.mkdirs();
        return dir;
    }

    /** The picked-file staging slot for transfer operations
     *  (H-1 IPC): the main process copies the file the user
     *  picked into this slot — the same directory the durable
     *  inbox uses, so the copy is a plain same-filesystem file
     *  — and the :depin process reads its input from here. The
     *  content is never secret (packages are encrypted under
     *  the transfer code; the message files are signed JSON),
     *  and the UI's serialised operation discipline means one
     *  operation's input is never overwritten mid-flight. */
    public static File transferInputFile(Context context) {
        return new File(transferIoDir(context), "ui-input.bin");
    }

    /** The staged output slot for transfer operations (H-1
     *  IPC): the :depin process writes the message a completed
     *  operation produced here, and the main process streams
     *  it to the destination the user picks — the pending-
     *  bundle pattern. Message files are public data by
     *  design; the sender package export does NOT use this
     *  slot (its file stays in the core's outbox and only its
     *  path crosses). */
    public static File transferOutputFile(Context context) {
        return new File(transferIoDir(context), "ui-output.bin");
    }

    /** The supervisor's private directory (created on demand). */
    public static File supervisorDir(Context context) {
        File dir = new File(
                context.getApplicationContext().getFilesDir(),
                "depin-supervisor");
        dir.mkdirs();
        return dir;
    }
}
