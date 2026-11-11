package app.quantusnode.node.depin;

import android.os.Bundle;

/**
 * Names for everything that crosses a boundary between the
 * observer's components: intent actions for the observer's
 * service entry points, intent extras, messenger message codes,
 * and the keys of the status bundle / status file.
 *
 * One constants class, on purpose: these strings are a protocol.
 * A typo at one call site is not a compile error, it is a status
 * that silently never arrives or a command that silently does
 * nothing, so every producer and consumer spells them through
 * here and nowhere else.
 */
public final class DepinIpc {
    private DepinIpc() {}

    // ---------------- intent actions ----------------

    public static final String ACTION_START =
            "app.quantusnode.node.depin.action.START";
    public static final String ACTION_ENABLE =
            "app.quantusnode.node.depin.action.ENABLE";
    public static final String ACTION_DISABLE =
            "app.quantusnode.node.depin.action.DISABLE";
    public static final String ACTION_STOP =
            "app.quantusnode.node.depin.action.STOP";

    // ---------------- intent extras ----------------

    /** Identifies one launch among restarts, so a stale intent
     *  delivered late cannot be mistaken for the current launch. */
    public static final String EXTRA_LAUNCH_ID = "launch_id";
    /** The device id a start/enable command was issued for; the
     *  receiver compares it against the identity it actually
     *  loaded and discards the command on a mismatch. */
    public static final String EXTRA_EXPECTED_DEVICE_ID =
            "expected_device_id";
    /** The identity generation paired with
     *  EXTRA_EXPECTED_DEVICE_ID. */
    public static final String EXTRA_EXPECTED_GENERATION =
            "expected_generation";
    /** A NotifStrings bundle, carrying the caller's localised
     *  notification texts into the service process. */
    public static final String EXTRA_NOTIF = "notif";

    // ---------------- messenger codes ----------------

    /** UI -> service: reply with a MSG_STATUS bundle. */
    public static final int MSG_GET_STATUS = 1;
    /** Service -> UI: the reply payload is a DepinStatus bundle. */
    public static final int MSG_STATUS = 2;
    /** UI -> service: build the current proof bundle, stage it
     *  at DepinFiles.pendingBundleFile, and reply with a
     *  MSG_BUNDLE_READY bundle. The bundle itself never crosses
     *  the binder — only the staged file's suggested name and
     *  the outcome do. */
    public static final int MSG_EXPORT_BUNDLE = 3;
    /** Service -> UI: the reply payload of MSG_EXPORT_BUNDLE. */
    public static final int MSG_BUNDLE_READY = 4;

    // ---------------- bundle export reply keys ----------------

    /** Outcome of an export request: one of BUNDLE_RESULT_*. */
    public static final String KEY_BUNDLE_RESULT = "bundle_result";
    /** Suggested destination file name (BUNDLE_RESULT_OK). */
    public static final String KEY_BUNDLE_NAME = "bundle_name";
    /** Failure description (BUNDLE_RESULT_ERROR). */
    public static final String KEY_BUNDLE_ERROR = "bundle_error";

    /** The bundle was built and staged. */
    public static final int BUNDLE_RESULT_OK = 0;
    /** The journal has no checkpoints yet; nothing was staged. */
    public static final int BUNDLE_RESULT_NONE = 1;
    /** The export failed; KEY_BUNDLE_ERROR says why. */
    public static final int BUNDLE_RESULT_ERROR = 2;

    // ---------------- transfer operation codes (H-1 IPC) ----------------

    /** UI -> service: run one device-transfer operation (a
     *  T_OP_* code in KEY_T_OP) in the :depin process and reply
     *  with MSG_TRANSFER_RESULT. Mirrors the MSG_EXPORT_BUNDLE
     *  discipline: the request is asynchronous, files cross as
     *  staged files under files/depin (never as binder bytes),
     *  and no seed or seed-derived material ever crosses — the
     *  only values in either direction are operation codes,
     *  non-secret parameters (the transfer code the user typed,
     *  the staged generation's public identifiers) and outcome
     *  metadata. */
    public static final int MSG_TRANSFER_OP = 5;
    /** Service -> UI: the reply payload of MSG_TRANSFER_OP. */
    public static final int MSG_TRANSFER_RESULT = 6;

    /** The operation code (a T_OP_* value). Echoed in the reply
     *  so the client can match it to the pending request. */
    public static final String KEY_T_OP = "t_op";
    /** Outcome of a transfer request: one of T_RESULT_*. */
    public static final String KEY_T_RESULT = "t_result";
    /** Simple class name of the failure (T_RESULT_ERROR /
     *  T_RESULT_PAUSED), so the UI can format the same
     *  "Class: message" line it showed when the operation ran
     *  in-process. */
    public static final String KEY_T_ERROR_CLASS = "t_error_class";
    /** Failure description (T_RESULT_ERROR / T_RESULT_PAUSED). */
    public static final String KEY_T_ERROR = "t_error";
    /** Flattened cause chain of the failure (T_RESULT_ERROR):
     *  "Class: message <- Class: message ..." — class names
     *  and messages only, bounded. Carried for the UI's
     *  error-file export. */
    public static final String KEY_T_ERROR_CHAIN = "t_error_chain";
    /** Failure reason code from OUR taxonomy (E10): the
     *  service classifies the exception by TYPE only and
     *  sends the code; the diagnostic file is built from it.
     *  The class/message/chain above remain for the on-screen
     *  failure line only — they never enter the file. */
    public static final String KEY_T_REASON = "t_reason";
    /** The one-shot confirmation number: sent by the UI to
     *  the close operation (E4), and returned by a successful
     *  one-shot confirm so the UI can display it. */
    public static final String KEY_T_COMMIT_CODE = "t_commit_code";
    /** The transfer code the user typed (T_OP_STAGE_PACKAGE
     *  request parameter). */
    public static final String KEY_T_CODE = "t_code";
    /** The sender export's one-time transfer code display text
     *  (T_OP_EXPORT_PACKAGE reply). Shown once by the UI,
     *  exactly as when the export ran in-process. */
    public static final String KEY_T_CODE_DISPLAY = "t_code_display";
    /** A staged generation's device id (request parameter of
     *  T_OP_COMPLETE_INTAKE; reply field of
     *  T_OP_STAGE_PACKAGE). */
    public static final String KEY_T_DEVICE_ID = "t_device_id";
    /** A staged generation's number (same roles as
     *  KEY_T_DEVICE_ID). */
    public static final String KEY_T_GENERATION = "t_generation";
    /** A staged package's SHA-256 (same roles as
     *  KEY_T_DEVICE_ID). */
    public static final String KEY_T_PACKAGE_SHA256 =
            "t_package_sha256";
    /** Checkpoint count of a staged package
     *  (T_OP_STAGE_PACKAGE reply, for the confirmation dialog). */
    public static final String KEY_T_CHECKPOINTS = "t_checkpoints";
    /** First checkpoint time (unix seconds) of a staged package
     *  (T_OP_STAGE_PACKAGE reply). */
    public static final String KEY_T_FIRST_TS = "t_first_ts";
    /** Last checkpoint time (unix seconds) of a staged package
     *  (T_OP_STAGE_PACKAGE reply). */
    public static final String KEY_T_TAIL_TS = "t_tail_ts";
    /** Suggested destination file name for the staged output
     *  (replies that stage a message file). */
    public static final String KEY_T_SAVE_NAME = "t_save_name";
    /** Absolute path of the exported package file
     *  (T_OP_EXPORT_PACKAGE reply). The file stays in the
     *  sender's outbox until the generation closes; the UI only
     *  streams a copy to the user's chosen destination. */
    public static final String KEY_T_PACKAGE_PATH = "t_package_path";

    /** The operation completed. */
    public static final int T_RESULT_OK = 0;
    /** The operation failed; KEY_T_ERROR_CLASS / KEY_T_ERROR
     *  say why. */
    public static final int T_RESULT_ERROR = 1;
    /** The section 3.5 verification budget paused the operation
     *  (a resumable stop, not a failure of the transfer):
     *  re-issuing the same operation continues from the durable
     *  cursor. Class/message keys carry the pause exception. */
    public static final int T_RESULT_PAUSED = 2;

    // Sender role.
    /** Export a transfer package for this app's sender seed. */
    public static final int T_OP_EXPORT_PACKAGE = 1;
    /** Issue a permit over the inbox's staged notice. */
    public static final int T_OP_ISSUE_PERMIT = 2;
    /** Cancel as sender before a permit (issues a cancel token). */
    public static final int T_OP_CANCEL_SENDER = 3;
    /** Take in a receipt as sender; stages the ack message. */
    public static final int T_OP_INTAKE_RECEIPT = 4;
    /** Take in a cannot-confirm response as sender. */
    public static final int T_OP_INTAKE_ABANDON = 5;
    /** Retain a staging notice in the durable inbox (sender
     *  side bookkeeping; the permit is issued against it). */
    public static final int T_OP_INTAKE_NOTICE = 6;
    // Receiver role.
    /** Stage a picked package with the typed transfer code;
     *  the reply carries the confirmation dialog's values. */
    public static final int T_OP_STAGE_PACKAGE = 7;
    /** Adopt the staged generation (the confirmation dialog's
     *  proceed action). */
    public static final int T_OP_COMPLETE_INTAKE = 8;
    /** Retain and take in a permit. */
    public static final int T_OP_INTAKE_PERMIT = 9;
    /** Take in the sender's completion acknowledgement. */
    public static final int T_OP_INTAKE_ACK = 10;
    /** Take in a sender cancel token. */
    public static final int T_OP_INTAKE_CANCEL_TOKEN = 11;
    /** Build the staging notice; stages the message file. */
    public static final int T_OP_EXPORT_NOTICE = 12;
    /** Run the trial judgement (confirm). May reply
     *  T_RESULT_PAUSED under the section 3.5 budget. */
    public static final int T_OP_CONFIRM = 13;
    /** Build the receipt; stages the message file. */
    public static final int T_OP_EXPORT_RECEIPT = 14;
    /** Issue the cannot-confirm response for the newest staged
     *  generation; stages the message file. */
    public static final int T_OP_EXPORT_ABANDON = 15;
    /** Re-present the completion ack from the S2 sender history
     *  (KEY_T_GENERATION names the completed generation);
     *  stages the message file. Exists so a lost or unsaved ack
     *  file cannot strand the receiver at "confirmed" forever:
     *  the receipt intake itself is durable, and the ack's full
     *  value is retained in the history by design. */
    public static final int T_OP_REPRESENT_ACK = 16;
    // One-shot handover (simple mode; progress file section 10).
    /** Export a one-shot handover package (the same encrypted
     *  package as T_OP_EXPORT_PACKAGE; the sender record is
     *  marked mode "oneshot"). */
    public static final int T_OP_EXPORT_PACKAGE_ONESHOT = 17;
    /** Confirm a staged generation in one-shot mode (no
     *  notice/permit/receipt/ack relay). */
    public static final int T_OP_CONFIRM_ONESHOT = 18;
    /** Sender side: erase this device's key material for the
     *  one-shot handover and close it (the S2-equivalent
     *  erasure). Requires KEY_T_COMMIT_CODE: the confirmation
     *  number the receiver displayed after its confirm (E4);
     *  a mismatch erases nothing. */
    public static final int T_OP_CLOSE_ONESHOT_SENDER = 19;
    /** Sender side: cancel a one-shot handover whose file
     *  never reached the other device (return to S0). */
    public static final int T_OP_CANCEL_ONESHOT_SENDER = 20;
    /** Sender side: re-export a one-shot handover from the
     *  same frozen tip with a fresh transfer code (E7). The
     *  previous package remains valid. */
    public static final int T_OP_EXPORT_PACKAGE_ONESHOT_AGAIN = 21;

    // ---------------- status bundle / file keys ----------------

    public static final String KEY_STATE = "state";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_DEVICE_ID = "device_id";
    public static final String KEY_GENERATION = "generation";
    public static final String KEY_CHECKPOINTS = "checkpoints";
    public static final String KEY_LAST_CHECKPOINT_UNIX =
            "last_checkpoint_unix";
    /** Member card (vision UI): the journal's first record and the
     *  number of distinct local days it holds records for. Plain
     *  aggregates of the journal's own records, computed by the
     *  observer that owns the journal. */
    public static final String KEY_FIRST_CHECKPOINT_UNIX =
            "first_checkpoint_unix";
    public static final String KEY_RECORDED_DAYS = "recorded_days";
    /** Vision UI stage 1+2 aggregates (spec section 5): plain
     *  derivations of the journal's own records, computed by the
     *  observer in the same pass as the member-card aggregates —
     *  longest streak of consecutive recorded days, the peer
     *  counts of the last 48 checkpoint slots, the last-24h
     *  checkpoint series, and today's progress. Display data
     *  only: none of this enters proof bundles or telemetry. */
    public static final String KEY_LONGEST_STREAK_DAYS =
            "longest_streak_days";
    public static final String KEY_RECENT_SLOT_PEERS =
            "recent_slot_peers";
    public static final String KEY_RECENT_SLOT_END_UNIX =
            "recent_slot_end_unix";
    public static final String KEY_RECENT_DAY_TS = "recent_day_ts";
    public static final String KEY_RECENT_DAY_PEERS =
            "recent_day_peers";
    public static final String KEY_TODAY_CHECKPOINTS =
            "today_checkpoints";
    public static final String KEY_TODAY_SLOTS_ELAPSED =
            "today_slots_elapsed";
    public static final String KEY_LAST_BEST = "last_best";
    public static final String KEY_HOLD = "hold";
    public static final String KEY_SELF_STOP_REASON = "self_stop_reason";
    public static final String KEY_SELF_STOP_DETAIL = "self_stop_detail";
    public static final String KEY_LAST_ERROR = "last_error";
    public static final String KEY_DISCARDED = "discarded";
    public static final String KEY_ATTEMPT_CORRUPT = "attempt_corrupt";
    /** When the attempt log was last reset by the confirmed
     *  repair (design section 2.3; unix seconds, 0 = never). */
    public static final String KEY_ATTEMPT_RESET_UNIX =
            "attempt_reset_unix";
    public static final String KEY_SUPPRESSED = "suppressed";
    public static final String KEY_UPDATED_MS = "updated_ms";
    /** Internal (facade-merged) restart backoff fields. */
    public static final String KEY_RESTART_FAILURES = "restart_failures";
    public static final String KEY_RESTART_NEXT_MS = "restart_next_ms";

    // ---------------- self-stop reason codes ----------------

    /** Repeated observation-round failures crossed the limit. */
    public static final String REASON_TICK_FAILURES = "tick_failures";
    /** The checkpoint journal could not be opened. */
    public static final String REASON_JOURNAL_OPEN = "journal_open_failed";
    /** The identity could not be loaded or created. */
    public static final String REASON_IDENTITY = "identity_failed";
    /** The RPC attempt budget log is corrupt (fails closed). */
    public static final String REASON_ATTEMPT_LOG =
            "rpc_attempt_log_corrupt";
    /** An uncaught exception reached the process handler. */
    public static final String REASON_UNCAUGHT = "uncaught_exception";

    /**
     * The notification texts, supplied by the UI side (which owns
     * localisation) and carried into the service process inside
     * the start intent, because the service must not reach back
     * into UI resources across the process boundary. Plain mutable
     * strings in a plain holder — this is a parcel-shaped DTO,
     * not a model object. Strings are capped at 256 chars in both
     * directions: a notification text is a short label by design,
     * and the cap keeps a damaged or hostile bundle from handing
     * the notification builder an unbounded string.
     */
    public static final class NotifStrings {
        public String title;
        public String channelName;
        public String channelDesc;
        public String starting;
        public String observing;
        /** Format string for the checkpoint notification text;
         *  %1$s is the local time of the last checkpoint and
         *  %2$d is the number of checkpoints recorded (the
         *  localised strings in the UI table are written to
         *  exactly this shape). */
        public String checkpointFormat;
        public String cannotStart;

        private static final int MAX_CHARS = 256;

        private static final String K_TITLE = "nt_title";
        private static final String K_CHANNEL_NAME = "nt_channel_name";
        private static final String K_CHANNEL_DESC = "nt_channel_desc";
        private static final String K_STARTING = "nt_starting";
        private static final String K_OBSERVING = "nt_observing";
        private static final String K_CHECKPOINT_FORMAT =
                "nt_checkpoint_format";
        private static final String K_CANNOT_START = "nt_cannot_start";

        private static String truncate(String s) {
            if (s == null || s.length() <= MAX_CHARS) {
                return s;
            }
            return s.substring(0, MAX_CHARS);
        }

        public Bundle toBundle() {
            Bundle b = new Bundle();
            b.putString(K_TITLE, truncate(title));
            b.putString(K_CHANNEL_NAME, truncate(channelName));
            b.putString(K_CHANNEL_DESC, truncate(channelDesc));
            b.putString(K_STARTING, truncate(starting));
            b.putString(K_OBSERVING, truncate(observing));
            b.putString(K_CHECKPOINT_FORMAT, truncate(checkpointFormat));
            b.putString(K_CANNOT_START, truncate(cannotStart));
            return b;
        }

        public static NotifStrings fromBundle(Bundle b) {
            NotifStrings n = new NotifStrings();
            if (b == null) {
                return n;
            }
            n.title = truncate(b.getString(K_TITLE));
            n.channelName = truncate(b.getString(K_CHANNEL_NAME));
            n.channelDesc = truncate(b.getString(K_CHANNEL_DESC));
            n.starting = truncate(b.getString(K_STARTING));
            n.observing = truncate(b.getString(K_OBSERVING));
            n.checkpointFormat =
                    truncate(b.getString(K_CHECKPOINT_FORMAT));
            n.cannotStart = truncate(b.getString(K_CANNOT_START));
            return n;
        }
    }
}
