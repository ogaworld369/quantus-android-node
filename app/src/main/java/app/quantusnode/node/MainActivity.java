package app.quantusnode.node;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;

import app.quantusnode.node.depin.DepinFiles;
import app.quantusnode.node.depin.DepinIpc;
import app.quantusnode.node.depin.DepinTransferClient;
import app.quantusnode.node.depin.TransferFlow;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import app.quantusnode.node.depin.DepinBundleExport;
import app.quantusnode.node.depin.DepinFiles;
import app.quantusnode.node.depin.DepinIpc;
import app.quantusnode.node.depin.DepinModule;
import app.quantusnode.node.depin.DepinStatus;

import java.io.File;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * Dashboard. Since the vision UI (spec: depin-vision-ui-spec.md)
 * the activity carries six screens in one activity — three tabs
 * under a floating bottom navigation (home: the status and price
 * cards and the one primary action; network: the peer field;
 * member: the DePIN member card) and the three sub-screens the
 * app already had (node details with exact heights, sync gap and
 * diagnostics; the track record; settings) — switched by swapping
 * root views. On the tab screens, content groups into floating
 * cards over the void background; the sub-screens keep the
 * earlier flat layout.
 *
 * Everything shown is a fact the service recorded ({@link NodeState}
 * / {@link TrackStore}); the display rules themselves — which of the
 * five home presentations applies, when a sync gap may be shown, how
 * durations round, when a setting counts as pending — live in
 * {@link UiLogic} and are unit-tested there. The "failed" home is
 * driven by the service's stop classification, never by a leftover
 * exit code.
 *
 * All data comes from the node on this phone via local RPC (through
 * NodeService / NodeState). Nothing here talks to the outside.
 */
public class MainActivity extends Activity {

    // ---- Palette (Quantus brand tokens from quantus.com, dark only) --
    // void #0E0E0E / surface #181818 / flare #FF6B35 / content #E8E6E0
    // / green #4ADE80. LABEL2, LABEL3, HAIRLINE and FIELD_BORDER are
    // content composited over the background at the site's own alpha
    // steps (60% / 40% / 12% / 30%), as solid colours.
    /** How long the attempt-log reset note stays on the
     *  DePIN status after the reset ran (H-06): seven days —
     *  long enough to be seen at the next several opens,
     *  short enough not to become permanent furniture that
     *  hides newer notes. */
    private static final long ATTEMPT_RESET_NOTE_WINDOW_SEC =
            7L * 24 * 3600;

    private static final int BG = Color.rgb(0x0E, 0x0E, 0x0E);
    private static final int SURFACE = Color.rgb(0x18, 0x18, 0x18);
    private static final int LABEL1 = Color.rgb(0xE8, 0xE6, 0xE0);
    private static final int LABEL2 = Color.rgb(0x91, 0x90, 0x8C);
    private static final int LABEL3 = Color.rgb(0x65, 0x64, 0x62);
    private static final int ACCENT = Color.rgb(0xFF, 0x6B, 0x35);
    private static final int ON_ACCENT = BG;
    private static final int HAIRLINE = Color.rgb(0x28, 0x28, 0x27);
    private static final int FIELD_BORDER = Color.rgb(0x4F, 0x4F, 0x4D);
    private static final int STATE_STOPPED = LABEL3;
    private static final int STATE_STARTING = Color.rgb(0x93, 0xB4, 0xEC);
    private static final int STATE_SYNCING = Color.rgb(0xF2, 0xA9, 0x3B);
    private static final int STATE_SYNCED = Color.rgb(0x4A, 0xDE, 0x80);
    private static final int STATE_FAILED = Color.rgb(0xF2, 0xA2, 0x98);
    /** Vision UI tokens (spec section 1): surface-2 for the
     *  stopped action and the member card's gradient start; the
     *  card line is content at 8%. */
    private static final int SURFACE2 = Color.rgb(0x20, 0x20, 0x20);
    private static final int CARD_LINE = Color.argb(20, 0xE8, 0xE6, 0xE0);
    /** Dialog surface (redesign spec, audit finding L-7). */
    private static final int DIALOG_BG = Color.rgb(0x18, 0x18, 0x18);
    private static final int RIPPLE_ACCENT =
            Color.argb(0x1F, 0xFF, 0x6B, 0x35); // flare at 12%
    private static final int REQ_NOTIF = 42;

    // ---- Screens ------------------------------------------------------
    // The vision UI (spec section 3 / design section 7.2): three
    // tabs — home, network, member — under a floating bottom
    // navigation; details / track / settings stay as sub-screens
    // reached from the tabs, exactly as before, and hide the bar.
    private static final int SCREEN_HOME = 0;
    private static final int SCREEN_DETAILS = 1;
    private static final int SCREEN_TRACK = 2;
    private static final int SCREEN_SETTINGS = 3;
    private static final int SCREEN_NETWORK = 4;
    private static final int SCREEN_MEMBER = 5;

    private final View[] screens = new View[6];
    private int currentScreen = SCREEN_HOME;
    /** The tab a sub-screen's back returns to. */
    private int tabScreen = SCREEN_HOME;

    // Bottom navigation
    private LinearLayout bottomNav;
    private final LinearLayout[] navButtons = new LinearLayout[3];
    private final NavIconView[] navIcons = new NavIconView[3];
    private final TextView[] navLabels = new TextView[3];

    // Home (vision): header, status card, price card, the action.
    private View stateDotView;
    private int stateDotColor = -1;
    private TextView statusView;
    private TextView vhBestValue;
    private TextView vhPeersValue;
    private TextView vhUptimeValue;
    private TextView supplementView;
    private TextView detailLinkView;
    private Button mainButton;
    private TextView pendingLinkView;
    private TextView priceDeltaView;
    private TextView priceValueView;
    private TextView priceSrcView;

    // Network tab
    private NetworkFieldView networkField;
    private TextView netNumView;
    private TextView netSubView;
    private TextView netTagView;
    private SparklineView sparkView;
    private TextView netSegNowBtn;
    private TextView netSeg24hBtn;
    private TextView netGhostNoteView;
    private LinearLayout tickerBox;
    private boolean netShow24h;
    // How-to-read sheet (stage 1 spec S1-02): an overlay panel
    // on the network root, built once, filled when opened.
    private FrameLayout readSheetOverlay;
    private TextView readSheetBestView;
    private TextView readSheetFactsView;
    // Ticker (stage 2 spec S2-03): in-memory only, newest
    // first, capped at 50 lines; never persisted. The
    // baselines are DISPLAY baselines in the L-1 sense: adopted
    // at display start so a rebuild cannot manufacture events.
    private final java.util.List<String> tickerLines =
            new java.util.ArrayList<>();
    private boolean tickerInit;
    private boolean tickerRunning;
    private long tickerBest = -1;
    private int tickerPeers = Integer.MIN_VALUE;
    private long tickerCpCount = -1;
    private boolean tickerHeld;
    /** Best-block advance tracking for the stalled state:
     *  the best this UI has seen and when (wall) it last grew. */
    private long netAdvanceBest = -1;
    private long netAdvanceWallMs = -1;

    // Home (vision): today's-record row (stage 1 spec S1-10).
    private LinearLayout homeTodayBox;
    private TextView homeTodayText;
    private FrameLayout homeTodayTrack;
    private View homeTodayFill;

    // Home (vision): age of the last block observation.
    private TextView vhBestAgoView;

    // Member tab
    private GlowCardView memberGlow;
    private FrameLayout memberCardBox;
    private LinearLayout memberContent;
    private View memberSpacer;
    private String memIdRaw;
    private boolean memIdGrouped = true;
    private TextView memberEmptyView;
    private TextView memNameView;
    private TextView memIdView;
    private TextView memSinceValue;
    private TextView memCpsValue;
    private TextView memDaysValue;
    private final java.util.List<TextView> statLabelViews =
            new java.util.ArrayList<>();
    private final java.util.List<TextView> statValueViews =
            new java.util.ArrayList<>();

    // Node details
    private LinearLayout detailsRunningBox;
    private LinearLayout detailsStoppedBox;
    private TextView detBestValue;
    private TextView detHighestValue;
    private TextView detGapValue;
    private TextView detPeersValue;
    private TextView detSessionValue;
    private TextView cumUptimeValue;
    private TextView cumBlocksValue;
    private TextView cumPeersValue;
    private LinearLayout diagSection;
    private LinearLayout diagContent;
    private TextView diagText;
    private TextView diagChevron;

    // Track
    private TextView trackUptimeValue;
    private TextView trackBlocksValue;
    private TextView trackPeersValue;

    // Settings
    private LinearLayout telemetryRow;
    private ToggleView telemetrySwitch;
    private TextView telemetryPendingView;
    private TextView telemetryAppliedView;
    private LinearLayout depinRow;
    private ToggleView depinSwitch;
    private TextView depinDeviceIdValue;
    private TextView depinStateValue;
    private TextView depinCheckpointsValue;
    private TextView depinLastCheckpointValue;
    private TextView depinNoteView;
    // Settings-lock reason lines and the bundle export
    // (integration step 5): the lock disables controls silently,
    // so each affected section also says WHY while locked.
    private TextView telemetryLockedView;
    private TextView depinLockedView;
    private Button depinExportButton;
    private Button depinAttemptResetButton;
    private TextView depinExportNoteView;
    private static final int REQ_EXPORT_SAVE = 2103;
    // Transfer UI (integration step 4; consolidated into a
    // state-driven single main action per role): each role's
    // block has a state line ("what is done, what is next"),
    // ONE main button whose label and action follow the derived
    // state (TransferFlow), and a collapsed "other actions"
    // container for the off-path operations. Failures and
    // section 3.5 pauses land on the result line.
    private TextView depinTransferReceiverStateView;
    private TextView depinTransferSenderStateView;
    private TextView depinTransferResultView;
    private TextView depinTransferLockedView;
    private Button depinTransferReceiverMainButton;
    private Button depinTransferSenderMainButton;
    private Button depinTransferRepresentAckButton;
    private Button depinTransferSaveErrorButton;
    private Button depinTransferConfirmOneshotButton;
    private Button depinTransferCancelOneshotButton;
    private Button depinTransferReexportButton;
    /** The picked handover package's Uri (E1: after a
     *  successful intake the app tries to delete the source
     *  file; when it cannot, it prompts the user). */
    private android.net.Uri pickedPackageUri;
    private int receiverMainAction;
    private int senderMainAction;
    private boolean receiverOthersExpanded;
    private boolean senderOthersExpanded;
    /** The unnotified staging the last state derivation found
     *  (the resume target); refreshed with the state lines. */
    private volatile TransferFlow.StagingRef currentUnnotifiedStaging;
    /** The receiver record the last derivation resolved to. */
    private String currentReceiverDeviceId;
    private long currentReceiverGeneration = -1;
    /** The transfer state's active binding identity, from the
     *  latest derived snapshot (null when none). */
    private String currentTransferActiveDeviceId;
    private String currentReceiverPackageSha256;
    // Session hints for the receiver's two file-production
    // steps, which leave no durable trace of their own: once
    // the notice (receipt) has been saved, the main action
    // advances to the matching intake until the state moves
    // on. A hint is matched against the derived record
    // identity (device, generation, package hash), so it can
    // never fire on a different record; losing it (recreation)
    // simply returns to the export step, which is idempotent,
    // and both exports also live under "other actions".
    private String hintNoticeDeviceId;
    private long hintNoticeGeneration = -1;
    private String hintNoticePackageSha256;
    private String hintReceiptDeviceId;
    private long hintReceiptGeneration = -1;
    private String hintReceiptPackageSha256;
    /** Generation of the completed (S2) send whose ack the
     *  re-presentation action re-saves; -1 when none. */
    private long currentCompletedSenderGeneration = -1;
    private boolean representAckAvailable;
    /** The sender record the last derivation resolved to. */
    private String currentSenderDeviceId;
    private long currentSenderGeneration = -1;
    /** The open sender record is a one-shot handover. */
    private boolean oneshotSenderOpen;
    /** A staged, notified receiver record can be confirmed in
     *  one-shot mode (the confirm-oneshot other-action). */
    private boolean oneshotConfirmAvailable;
    /** Audit M1: the staged identity differs from the live
     *  one, so the one-shot confirm is not offered anywhere
     *  (E8 would refuse it at confirm time regardless). */
    private boolean currentOneshotBlockedByLive;
    // The most recent transfer failure, held for the error-file
    // export (metadata only — see TransferErrorReport; nothing
    // here can hold a secret: class names, messages, ids,
    // counts and durable state labels only).
    private String pendingErrorOp;
    private String pendingErrorMode;
    private String pendingErrorRole;
    private String pendingErrorReason;
    private long pendingErrorWhenMs;
    private String pendingErrorDeviceId;
    private String pendingErrorCounterpartId;
    private long pendingErrorGeneration = -1;
    private String pendingErrorPackageSha256;
    private long pendingErrorTotal = -1;
    private long pendingErrorVerified = -1;
    private final java.util.List<Button> depinTransferButtons =
            new java.util.ArrayList<>();
    // Audit H-3 / M-4: transfer operations and bundle exports
    // run strictly one at a time. A single-thread executor
    // serialises the background work (each tap formerly
    // spawned its own thread over the same transfer state,
    // staging files and seed material), and the busy flags
    // disable the buttons while an operation is in flight.
    private final java.util.concurrent.ExecutorService
            depinWorkExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "depin-ui-work");
                        t.setDaemon(true);
                        return t;
                    });
    private boolean transferBusy;
    private boolean exportBusy;
    private static final int REQ_TRANSFER_OPEN = 2101;
    private static final int REQ_TRANSFER_SAVE = 2102;
    private static final int T_OPEN_PACKAGE = 1;
    private static final int T_OPEN_PERMIT = 2;
    private static final int T_OPEN_ACK = 3;
    private static final int T_OPEN_NOTICE = 4;
    private static final int T_OPEN_RECEIPT = 5;
    private static final int T_OPEN_ABANDON = 6;
    private static final int T_OPEN_CANCEL_TOKEN = 7;
    private static final int ACT_IMPORT_PACKAGE = 11;
    private static final int ACT_EXPORT_NOTICE = 12;
    private static final int ACT_IMPORT_PERMIT = 13;
    private static final int ACT_CONFIRM = 14;
    private static final int ACT_EXPORT_RECEIPT = 15;
    private static final int ACT_IMPORT_ACK = 16;
    private static final int ACT_IMPORT_CANCEL_TOKEN = 17;
    private static final int ACT_EXPORT_ABANDON = 18;
    private static final int ACT_EXPORT_PACKAGE = 21;
    private static final int ACT_IMPORT_NOTICE = 22;
    private static final int ACT_ISSUE_PERMIT = 23;
    private static final int ACT_CANCEL_TRANSFER = 24;
    private static final int ACT_IMPORT_RECEIPT = 25;
    private static final int ACT_IMPORT_ABANDON = 26;
    private static final int ACT_REPRESENT_ACK = 27;
    private static final int ACT_RESUME_INTAKE = 31;
    private static final int ACT_EXPORT_PACKAGE_ONESHOT = 32;
    private static final int ACT_CONFIRM_ONESHOT = 33;
    private static final int ACT_CLOSE_ONESHOT = 34;
    private static final int ACT_CANCEL_ONESHOT = 35;
    private static final int ACT_REEXPORT_ONESHOT = 38;
    private static final int ACT_SAVE_ERROR = 36;
    private int pendingTransferOpen;
    // H-1 IPC: a produced transfer message is no longer held
    // as bytes in this process; the :depin process stages it
    // in a file and this field holds that file (or, for the
    // sender package, the core outbox file) until the save
    // picker returns a destination to stream it to.
    private java.io.File pendingTransferSaveFile;
    private volatile app.quantusnode.node.depin.core.TransferReceiver
            .IntakeResult pendingStaged;
    private LinearLayout nameRow;
    private TextView nameValue;
    private TextView nameChevron;
    private TextView namePendingView;
    private TextView nameAppliedView;
    private TextView nameLockedView;
    private LinearLayout languageRow;
    private TextView languageValue;
    private ToggleView lockSwitch;

    // ---- UI state ------------------------------------------------------
    /** Stop requested, service has not finished yet (spec H): an
     *  operation flag, not a sixth state. */
    private boolean stopping = false;
    private boolean diagExpanded = false;
    /** Set by the failure / stop detail links: this once, the saved
     *  scroll position yields to landing on the diagnostics section. */
    private boolean scrollToDiagnosticsOnce = false;
    /** Guards programmatic switch syncs from re-entering listeners. */
    private boolean syncingSettings = false;
    private String lastDiagText;
    private AlertDialog nameDialog;
    private AlertDialog languageDialog;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean firstRefresh = true;
    /**
     * The observation baseline the ripple keys off. Static — outside
     * the views and outside the activity instance — so a rebuild
     * (language change) alone can never fire a ripple (spec G).
     * The baseline is the snapshot of the service's observation the
     * UI adopts when it (re)starts displaying (onCreate / onResume):
     * adopting raises the baseline to the snapshot as a VALUE, never
     * as a firing, so the first increase detected after a cold
     * start ripples like any other, while blocks observed while the
     * UI was away are never replayed (audit finding L-1).
     */
    private static long sLastRippleObservedBest = -1;
    /** True once a display-start snapshot has been adopted. */
    private static boolean sRippleBaselineReady = false;

    /** The primary button's backgrounds, built once per instance
     *  and reused by every refresh (audit finding L-5). */
    private android.graphics.drawable.Drawable mainBtnAccentBg;
    private android.graphics.drawable.Drawable mainBtnSurfaceBg;
    private android.graphics.drawable.Drawable mainBtnStoppingBg;

    /** Settings rows that can anchor a language-change restore
     *  (spec G), in visual order; each carries a stable view id. */
    private final java.util.List<View> settingsAnchors =
            new java.util.ArrayList<>();

    // Carried across a language-change recreate() (spec G): current
    // screen, per-screen scroll offsets, section open states, and
    // whether focus returns to the language row.
    private static int sScreenAfterRecreate = -1;
    private static int[] sScrollAfterRecreate;
    private static boolean sDiagAfterRecreate;
    private static boolean sFocusLanguageAfterRecreate;
    /** Anchor restore for the settings screen (spec G): the stable
     *  id of the row at the top of the viewport when the language
     *  changed, and the scroll offset inside that row. -1 = none
     *  (fall back to the raw scroll offset). */
    private static int sAnchorIdAfterRecreate = -1;
    private static int sAnchorOffsetAfterRecreate = 0;

    // Stable ids for the settings anchor rows (spec G). Generated
    // once per process; the same rows get the same ids in every
    // activity instance, which is what makes an anchor recorded
    // before recreate() findable after it.
    private static final int ID_ANCHOR_TELEMETRY = View.generateViewId();
    private static final int ID_ANCHOR_DEPIN = View.generateViewId();
    private static final int ID_ANCHOR_NAME = View.generateViewId();
    private static final int ID_ANCHOR_LANGUAGE = View.generateViewId();
    private static final int ID_ANCHOR_LOCK = View.generateViewId();

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    /**
     * Adopt the service's current observation as the ripple
     * baseline (finding L-1): the display-start snapshot is taken
     * as the baseline VALUE, so nothing about this moment can fire
     * a ripple, and the first increase after it can.
     */
    private static void adoptRippleBaseline() {
        NodeState st = NodeState.get();
        if (st.lastObservedBestBlock > sLastRippleObservedBest) {
            sLastRippleObservedBest = st.lastObservedBestBlock;
        }
        sRippleBaselineReady = true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Transfer picker hand-offs survive recreation: the
        // document picker's result is delivered to the RECREATED
        // activity, and the pending kind / save source are plain
        // instance state. Without this restore, a recreation
        // while a picker was open silently dropped the picked
        // file's dispatch (kind 0 matches nothing) or the save
        // (null source) — one of the first production attempt's
        // failure paths.
        if (savedInstanceState != null) {
            pendingTransferOpen = savedInstanceState.getInt(
                    "transfer_pending_open", 0);
            String savePath = savedInstanceState.getString(
                    "transfer_pending_save");
            if (savePath != null) {
                pendingTransferSaveFile = new java.io.File(savePath);
            }
        }
        // Before the first refresh runs: adopt the display-start
        // snapshot as the ripple baseline (finding L-1).
        adoptRippleBaseline();
        int dir = I18n.isRtl(this)
                ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR;
        getWindow().getDecorView().setLayoutDirection(dir);
        if (sScreenAfterRecreate >= 0) {
            currentScreen = sScreenAfterRecreate;
            diagExpanded = sDiagAfterRecreate;
        }
        buildUi();
        showScreen(currentScreen);
        refresh();
        // The DePIN settings' identity binding is NOT synced
        // from here (G-01: the settings writers are :depin-only
        // — the audit M6 sync this replaces ran in the main
        // process, and a main-process SharedPreferences write
        // is exactly the F-03 hazard). The sync now happens
        // where the settings are owned: at the observer's
        // initialisation and when the transfer service is
        // bound (both :depin). This process only renders the
        // status snapshot the facade merges, whose binding the
        // :depin side keeps current.
        if (sScrollAfterRecreate != null) {
            final int[] ys = sScrollAfterRecreate;
            final boolean focusLang = sFocusLanguageAfterRecreate;
            final int anchorId = sAnchorIdAfterRecreate;
            final int anchorOffset = sAnchorOffsetAfterRecreate;
            sScrollAfterRecreate = null;
            sScreenAfterRecreate = -1;
            sFocusLanguageAfterRecreate = false;
            sAnchorIdAfterRecreate = -1;
            sAnchorOffsetAfterRecreate = 0;
            for (int i = 0; i < screens.length; i++) {
                if (!(screens[i] instanceof ScrollView)) {
                    continue;
                }
                final ScrollView sv = (ScrollView) screens[i];
                final int y = ys[i];
                if (i == SCREEN_SETTINGS && anchorId >= 0) {
                    // Anchor restore (spec G): translations change
                    // row heights, so the raw offset is meaningless
                    // for this screen — land the same row at the
                    // same in-row offset instead.
                    sv.post(() -> {
                        View anchor = sv.findViewById(anchorId);
                        if (anchor != null && sv.getChildAt(0) != null) {
                            sv.scrollTo(0, Math.max(0,
                                    offsetInContent(anchor, sv.getChildAt(0))
                                            + anchorOffset));
                        } else {
                            sv.scrollTo(0, Math.max(0, y));
                        }
                    });
                } else if (y > 0) {
                    sv.post(() -> sv.scrollTo(0, y));
                }
            }
            if (focusLang && languageRow != null) {
                // Focus returns to the language row (spec G); the
                // scroll position is the anchor's job, not focus's.
                languageRow.post(() -> languageRow.requestFocus());
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back to the screen must not replay ripples for
        // blocks observed while away (spec H): adopt the service's
        // current observation as the baseline before the tick runs.
        adoptRippleBaseline();
        handler.removeCallbacks(refresher);
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
    }

    @Override
    public void onBackPressed() {
        // Dialogs take the back press first (the platform routes it
        // to a showing dialog before this is ever called). Sub-screen
        // back goes to the tab it was opened from; a non-home tab
        // goes home; home back is the platform default and never
        // sends a stop request (spec G).
        if (!isTabScreen(currentScreen)) {
            showScreen(tabScreen);
            return;
        }
        if (currentScreen != SCREEN_HOME) {
            showScreen(SCREEN_HOME);
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------
    // UI construction
    // ------------------------------------------------------------------

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /** Side padding: 24 dp, growing so the column caps at 560 dp. */
    private int sidePadding() {
        int w = getResources().getDisplayMetrics().widthPixels;
        return Math.max(dp(24), (w - dp(560)) / 2);
    }

    /**
     * The floating bottom nav's zone, in dp from the screen's
     * bottom edge: its 22 dp bottom margin plus its own height
     * (6+6 padding around a button of 8+20 icon + 3 + ~15 label
     * + 8). Tab content that must never rest under the pill is
     * kept clear of this zone.
     */
    private int navZone() {
        return dp(92);
    }

    /** Bottom padding for a tab's scroll column: the nav zone
     *  plus a breathing gap, so the last row can scroll fully
     *  clear of the pill. */
    private int navClearance() {
        return navZone() + dp(32);
    }

    private void buildUi() {
        int dir = I18n.isRtl(this)
                ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR;
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(BG);
        container.setLayoutDirection(dir);
        screens[SCREEN_HOME] = buildHome(container, dir);
        screens[SCREEN_DETAILS] = buildDetails(container, dir);
        screens[SCREEN_TRACK] = buildTrack(container, dir);
        screens[SCREEN_SETTINGS] = buildSettings(container, dir);
        screens[SCREEN_NETWORK] = buildNetwork(container, dir);
        screens[SCREEN_MEMBER] = buildMember(container, dir);
        buildBottomNav(container, dir);
        container.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars =
                    insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setContentView(container);
    }

    /** A screen: ScrollView + vertical content column. */
    private LinearLayout screenContent(FrameLayout container, int screen,
                                       int dir) {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setLayoutDirection(dir);
        sv.setVisibility(View.GONE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int side = sidePadding();
        root.setPadding(side, dp(24), side, dp(24));
        root.setLayoutDirection(dir);
        sv.addView(root);
        container.addView(sv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        screens[screen] = sv;
        return root;
    }

    private static boolean isTabScreen(int screen) {
        return screen == SCREEN_HOME
                || screen == SCREEN_NETWORK
                || screen == SCREEN_MEMBER;
    }

    private void showScreen(int screen) {
        currentScreen = screen;
        if (isTabScreen(screen)) {
            tabScreen = screen;
        }
        for (int i = 0; i < screens.length; i++) {
            if (screens[i] != null) {
                screens[i].setVisibility(
                        i == screen ? View.VISIBLE : View.GONE);
            }
        }
        // The bar belongs to the tabs; sub-screens hide it.
        if (bottomNav != null) {
            bottomNav.setVisibility(
                    isTabScreen(screen) ? View.VISIBLE : View.GONE);
            applyNavSelection();
        }
        // The field and the card glow run their frame loops only
        // while their tab is the visible one.
        if (networkField != null) {
            networkField.setFieldActive(screen == SCREEN_NETWORK);
        }
        if (memberGlow != null) {
            memberGlow.setCardActive(screen == SCREEN_MEMBER);
        }
        if (screen == SCREEN_DETAILS && scrollToDiagnosticsOnce) {
            scrollToDiagnosticsOnce = false;
            diagSection.post(() -> {
                ScrollView sv = (ScrollView) screens[SCREEN_DETAILS];
                int y = offsetInContent(diagSection, sv.getChildAt(0));
                sv.scrollTo(0, Math.max(0, y - dp(8)));
            });
        }
    }

    /** Highlight the nav button of the current tab. */
    private void applyNavSelection() {
        int active = currentScreen == SCREEN_NETWORK ? 1
                : currentScreen == SCREEN_MEMBER ? 2 : 0;
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < navButtons.length; i++) {
            if (navButtons[i] == null) {
                continue;
            }
            boolean on = i == active;
            navButtons[i].setBackground(on
                    ? rounded(Color.argb(20, 0xE8, 0xE6, 0xE0),
                            999, density, 0, 0)
                    : null);
            navIcons[i].setIconColor(on ? LABEL1 : LABEL3);
            navLabels[i].setTextColor(on ? LABEL1 : LABEL3);
        }
    }

    /** Vertical offset of a view inside its scroll content root. */
    private static int offsetInContent(View v, View contentRoot) {
        int y = 0;
        View cur = v;
        while (cur != null && cur != contentRoot) {
            y += cur.getTop();
            Object parent = cur.getParent();
            cur = parent instanceof View ? (View) parent : null;
        }
        return y;
    }

    // ---- Shared pieces -------------------------------------------------

    private TextView text(String s, float sp, int color, boolean medium) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(medium
                ? Typeface.create("sans-serif-medium", Typeface.NORMAL)
                : Typeface.DEFAULT);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private LinearLayout.LayoutParams matchLp() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchLpTop(int topDp) {
        LinearLayout.LayoutParams lp = matchLp();
        lp.topMargin = dp(topDp);
        return lp;
    }

    private View hairline(int topDp) {
        View v = new View(this);
        v.setBackgroundColor(HAIRLINE);
        LinearLayout.LayoutParams lp = matchLpTop(topDp);
        lp.height = Math.max(1, dp(1));
        v.setLayoutParams(lp);
        return v;
    }

    private static GradientDrawable rounded(int color, float radiusDp,
                                            float density, int strokeColor,
                                            int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * density);
        if (strokeDp > 0) {
            g.setStroke((int) (strokeDp * density), strokeColor);
        }
        return g;
    }

    /** Press feedback: a 12% accent ripple over the given base. */
    private RippleDrawable ripple(android.graphics.drawable.Drawable base) {
        return new RippleDrawable(
                ColorStateList.valueOf(RIPPLE_ACCENT), base, null);
    }

    // The primary button's three dresses, cached per instance:
    // refresh() runs every second and must not rebuild drawables
    // it only re-applies (finding L-5).

    /** Accent fill, pill (start / retry; vision spec 4.1). */
    private android.graphics.drawable.Drawable accentButtonBg() {
        if (mainBtnAccentBg == null) {
            float d = getResources().getDisplayMetrics().density;
            mainBtnAccentBg = ripple(rounded(ACCENT, 999, d, 0, 0));
        }
        return mainBtnAccentBg;
    }

    /** Bordered surface-2, pill (stop / cancel startup). */
    private android.graphics.drawable.Drawable surfaceButtonBg() {
        if (mainBtnSurfaceBg == null) {
            float d = getResources().getDisplayMetrics().density;
            mainBtnSurfaceBg = ripple(rounded(SURFACE2, 999, d, CARD_LINE, 1));
        }
        return mainBtnSurfaceBg;
    }

    /** Plain surface, no press ripple (stopping, disabled). */
    private android.graphics.drawable.Drawable stoppingButtonBg() {
        if (mainBtnStoppingBg == null) {
            float d = getResources().getDisplayMetrics().density;
            mainBtnStoppingBg = rounded(SURFACE2, 999, d, 0, 0);
        }
        return mainBtnStoppingBg;
    }

    /**
     * A label/value information row: label at start, value at end,
     * at least 24 dp between them, 56 dp tall. When the two sides
     * cannot share one line they stack, label above value (§6);
     * nothing is shrunk or ellipsized away. The measuring and the
     * fallback live in {@link InfoRowView}.
     */
    private TextView infoRow(LinearLayout parent, String label,
                             boolean hairlineAbove) {
        if (hairlineAbove) {
            parent.addView(hairline(0));
        }
        InfoRowView r = new InfoRowView(this);
        TextView l = text(label, 16, LABEL2, false);
        r.addView(l);
        TextView v = text("", 16, LABEL1, false);
        v.setGravity(Gravity.END);
        r.addView(v);
        parent.addView(r, matchLp());
        return v;
    }

    /** A full-width tappable row with a trailing chevron. */
    private LinearLayout navRow(LinearLayout parent, String label,
                                int topDp, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(56));
        r.setPadding(0, dp(12), 0, dp(12));
        r.setBackground(ripple(null));
        r.setClickable(true);
        r.setFocusable(true);
        r.setOnClickListener(click);
        TextView l = text(label, 16, LABEL1, false);
        r.addView(l, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView chev = text(I18n.isRtl(this) ? "‹" : "›", 22, LABEL3, false);
        r.addView(chev);
        parent.addView(r, matchLpTop(topDp));
        return r;
    }

    /** Sub-screen header: back target + 20 sp title. */
    private void subHeader(LinearLayout root, String title) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setMinimumHeight(dp(56));
        TextView back = text(I18n.isRtl(this) ? "›" : "‹", 30, LABEL2, false);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription(I18n.get(this, StringsTable.A11Y_BACK));
        back.setBackground(ripple(null));
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(v -> showScreen(tabScreen));
        h.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView t = text(title, 20, LABEL1, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginStart(dp(4));
        h.addView(t, tlp);
        root.addView(h, matchLp());
    }

    /** A 14 sp medium section heading for expandable sections. */
    private TextView sectionLabel(String s) {
        return text(s, 14, LABEL2, true);
    }

    /**
     * Give a dialog the redesign's surface instead of the platform
     * theme's (finding L-7): #181818 with 16 dp corners, applied to
     * the dialog window once it exists. The platform layout keeps
     * its own inner padding; only the surface behind it changes.
     */
    private void styleDialog(AlertDialog dialog) {
        if (dialog == null || dialog.getWindow() == null) {
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        dialog.getWindow().setBackgroundDrawable(
                rounded(DIALOG_BG, 16, d, 0, 0));
    }

    // ------------------------------------------------------------------
    // Home
    // ------------------------------------------------------------------

    /** A vision card surface: surface fill, the 8% line, 24 dp
     *  corners (spec sections 1 and 3). */
    private android.graphics.drawable.Drawable cardBg() {
        float d = getResources().getDisplayMetrics().density;
        return rounded(SURFACE, 24, d, CARD_LINE, 1);
    }

    /** The official logo in its rounded tile (spec section 7):
     *  the artwork itself is the vector drawable, unmodified;
     *  the rounding belongs to the tile around it. */
    private ImageView logoView(int sizeDp, int radiusDp) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(R.drawable.quantus_logo);
        iv.setScaleType(ImageView.ScaleType.FIT_XY);
        final float radius = dp(radiusDp);
        iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(),
                        view.getHeight(), radius);
            }
        });
        iv.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(sizeDp), dp(sizeDp));
        iv.setLayoutParams(lp);
        return iv;
    }

    /** A small label in the vision cards (13 sp, muted). */
    private TextView cardLabel(String s) {
        return text(s, 13, LABEL2, false);
    }

    private ScrollView buildHome(FrameLayout container, int dir) {
        LinearLayout root = screenContent(container, SCREEN_HOME, dir);
        // The floating nav overlays the bottom; give the column
        // room to scroll clear of it (nav zone + gap, see
        // navClearance()).
        root.setPadding(root.getPaddingLeft(), dp(24),
                root.getPaddingRight(), navClearance());

        // Header: logo tile + name + settings icon.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(56));
        header.addView(logoView(32, 9));
        TextView title = text("Quantus Node", 20, LABEL1, true);
        title.setTypeface(VisionFonts.displayBold(this));
        LinearLayout.LayoutParams ttlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        ttlp.setMarginStart(dp(12));
        header.addView(title, ttlp);
        SettingsIconView settingsIcon = new SettingsIconView(this);
        settingsIcon.setContentDescription(
                I18n.get(this, StringsTable.VISION_SETTINGS));
        settingsIcon.setBackground(ripple(null));
        settingsIcon.setOnClickListener(v -> showScreen(SCREEN_SETTINGS));
        header.addView(settingsIcon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(header, matchLp());

        // Status card: state, the big best number, peers/uptime.
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBg());
        card.setPadding(dp(26), dp(26), dp(26), dp(26));
        LinearLayout stateRow = new LinearLayout(this);
        stateRow.setOrientation(LinearLayout.HORIZONTAL);
        stateRow.setGravity(Gravity.CENTER_VERTICAL);
        stateDotView = new View(this);
        stateRow.addView(stateDotView,
                new LinearLayout.LayoutParams(dp(7), dp(7)));
        statusView = text("", 22, LABEL1, true);
        statusView.setTypeface(VisionFonts.displayMedium(this));
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        stlp.setMarginStart(dp(9));
        stateRow.addView(statusView, stlp);
        card.addView(stateRow, matchLp());
        TextView bestLabel = cardLabel(
                I18n.get(this, StringsTable.VISION_BEST));
        card.addView(bestLabel, matchLpTop(32));
        vhBestValue = text("", 56, LABEL1, true);
        vhBestValue.setTypeface(VisionFonts.displayBold(this));
        card.addView(vhBestValue, matchLpTop(10));
        // Age of the last block observation, under the number
        // (stage 1 spec S1-10): a relative age while an
        // observation exists, a dash while none does — the
        // same 0-vs-dash discipline as everywhere else.
        vhBestAgoView = text("", 11, LABEL3, false);
        vhBestAgoView.setTypeface(VisionFonts.mono(this));
        card.addView(vhBestAgoView, matchLpTop(6));
        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout peersCol = new LinearLayout(this);
        peersCol.setOrientation(LinearLayout.VERTICAL);
        peersCol.addView(cardLabel(
                I18n.get(this, StringsTable.VISION_PEERS)), matchLp());
        vhPeersValue = text("", 28, LABEL1, true);
        vhPeersValue.setTypeface(VisionFonts.displayMedium(this));
        peersCol.addView(vhPeersValue, matchLpTop(8));
        stats.addView(peersCol, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout upCol = new LinearLayout(this);
        upCol.setOrientation(LinearLayout.VERTICAL);
        upCol.addView(cardLabel(
                I18n.get(this, StringsTable.VISION_UPTIME)), matchLp());
        vhUptimeValue = text("", 24, LABEL1, false);
        vhUptimeValue.setTypeface(VisionFonts.monoMedium(this));
        upCol.addView(vhUptimeValue, matchLpTop(8));
        stats.addView(upCol, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(stats, matchLpTop(36));
        supplementView = text("", 13, LABEL3, false);
        supplementView.setVisibility(View.GONE);
        card.addView(supplementView, matchLpTop(20));
        root.addView(card, matchLpTop(8));

        // Price card: QTC price, 24 h change, source + time.
        LinearLayout priceCard = new LinearLayout(this);
        priceCard.setOrientation(LinearLayout.VERTICAL);
        priceCard.setBackground(cardBg());
        priceCard.setPadding(dp(26), dp(26), dp(26), dp(26));
        LinearLayout priceHead = new LinearLayout(this);
        priceHead.setOrientation(LinearLayout.HORIZONTAL);
        priceHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView priceLabel = cardLabel(
                I18n.get(this, StringsTable.VISION_PRICE));
        priceHead.addView(priceLabel, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        priceDeltaView = text("", 13, LABEL2, false);
        priceDeltaView.setTypeface(VisionFonts.mono(this));
        priceDeltaView.setGravity(Gravity.END);
        priceHead.addView(priceDeltaView);
        priceCard.addView(priceHead, matchLp());
        priceValueView = text("", 44, LABEL1, true);
        priceValueView.setTypeface(VisionFonts.displayBold(this));
        priceCard.addView(priceValueView, matchLpTop(14));
        priceSrcView = text("", 11, LABEL3, false);
        priceSrcView.setTypeface(VisionFonts.mono(this));
        priceCard.addView(priceSrcView, matchLpTop(14));
        root.addView(priceCard, matchLpTop(16));
        // Tapping the "24h —" placeholder explains itself
        // (stage 1 spec S1-07): the fetch simply carried no
        // change rate. The tap is live only in that state.
        priceDeltaView.setClickable(true);
        priceDeltaView.setOnClickListener(v -> {
            PriceStore.Snapshot s = PriceStore.snapshot(this);
            if (s.hasValue && !s.hasChange) {
                android.widget.Toast.makeText(this,
                        I18n.get(this, StringsTable.PRICE_NO_CHANGE),
                        android.widget.Toast.LENGTH_LONG).show();
            }
        });

        // Today's record (stage 1 spec S1-10): a thin card
        // under the price card — the share of today's elapsed
        // checkpoint slots that actually hold a record. Shown
        // only while DePIN is on: a device that is not
        // recording must not be shown a 0% progress bar for a
        // record it never claimed to keep.
        homeTodayBox = new LinearLayout(this);
        homeTodayBox.setOrientation(LinearLayout.VERTICAL);
        homeTodayBox.setBackground(cardBg());
        homeTodayBox.setPadding(dp(26), dp(18), dp(26), dp(18));
        homeTodayBox.addView(cardLabel(
                I18n.get(this, StringsTable.HOME_TODAY)), matchLp());
        homeTodayText = text("", 13, LABEL2, false);
        homeTodayText.setTypeface(VisionFonts.mono(this));
        homeTodayBox.addView(homeTodayText, matchLpTop(8));
        homeTodayTrack = new FrameLayout(this);
        homeTodayTrack.setBackground(rounded(
                Color.rgb(0x22, 0x22, 0x2A), 2,
                getResources().getDisplayMetrics().density, 0, 0));
        homeTodayFill = new View(this);
        homeTodayFill.setBackground(rounded(ACCENT, 2,
                getResources().getDisplayMetrics().density, 0, 0));
        homeTodayTrack.addView(homeTodayFill,
                new FrameLayout.LayoutParams(0, dp(4)));
        LinearLayout.LayoutParams tlp2 = matchLpTop(10);
        tlp2.height = dp(4);
        homeTodayBox.addView(homeTodayTrack, tlp2);
        homeTodayBox.setVisibility(View.GONE);
        root.addView(homeTodayBox, matchLpTop(16));

        // The one primary action (pill dress, vision spec 4.1).
        mainButton = new Button(this);
        mainButton.setTextSize(15);
        mainButton.setAllCaps(false);
        mainButton.setStateListAnimator(null);
        mainButton.setMinHeight(dp(58));
        mainButton.setPadding(dp(24), dp(12), dp(24), dp(12));
        mainButton.setOnClickListener(v -> onMainButtonClicked());
        mainButton.setFocusableInTouchMode(false);
        LinearLayout.LayoutParams mlp = matchLpTop(16);
        mlp.height = LinearLayout.LayoutParams.WRAP_CONTENT;
        root.addView(mainButton, mlp);

        detailLinkView = text("", 14, ACCENT, true);
        detailLinkView.setGravity(Gravity.CENTER);
        detailLinkView.setBackground(ripple(null));
        detailLinkView.setClickable(true);
        detailLinkView.setFocusable(true);
        detailLinkView.setPadding(0, dp(8), 0, dp(8));
        detailLinkView.setOnClickListener(v -> openDiagnosticsFromHome());
        root.addView(detailLinkView, matchLpTop(8));

        pendingLinkView = text("", 14, ACCENT, true);
        pendingLinkView.setGravity(Gravity.CENTER);
        pendingLinkView.setBackground(ripple(null));
        pendingLinkView.setClickable(true);
        pendingLinkView.setFocusable(true);
        pendingLinkView.setPadding(0, dp(8), 0, dp(8));
        pendingLinkView.setOnClickListener(v -> openPendingSettings());
        root.addView(pendingLinkView, matchLpTop(8));

        navRow(root, I18n.get(this, StringsTable.SCREEN_NODE_DETAILS), 16,
                v -> showScreen(SCREEN_DETAILS));
        navRow(root, I18n.get(this, StringsTable.SECTION_TRACK), 0,
                v -> showScreen(SCREEN_TRACK));
        return (ScrollView) screens[SCREEN_HOME];
    }

    // ------------------------------------------------------------------
    // Network tab (vision): hero, field card, ticker, read sheet
    // ------------------------------------------------------------------

    private View buildNetwork(FrameLayout container, int dir) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.setLayoutDirection(dir);
        root.setVisibility(View.GONE);
        float density = getResources().getDisplayMetrics().density;

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        root.addView(sv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int side = sidePadding();
        col.setPadding(side, dp(24), side, navClearance());
        sv.addView(col);

        // Header: title left; the segment and the how-to-read
        // button right (stage 1 spec S1-02 / S1-03).
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(I18n.get(this, StringsTable.VISION_NET_TITLE),
                20, LABEL1, true);
        t.setTypeface(VisionFonts.displayBold(this));
        header.addView(t, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(rounded(Color.rgb(0x1A, 0x1A, 0x20),
                999, density, 0, 0));
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        netSegNowBtn = segmentButton(
                I18n.get(this, StringsTable.NET_SEG_NOW), false);
        netSeg24hBtn = segmentButton(
                I18n.get(this, StringsTable.NET_SEG_24H), true);
        seg.addView(netSegNowBtn);
        seg.addView(netSeg24hBtn);
        header.addView(seg);
        TextView info = text("i", 13, LABEL1, true);
        info.setGravity(Gravity.CENTER);
        info.setBackground(rounded(0, 999, density, LABEL3, 1));
        info.setClickable(true);
        info.setOnClickListener(v -> openReadSheet());
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                dp(30), dp(30));
        ilp.setMarginStart(dp(10));
        header.addView(info, ilp);
        col.addView(header, matchLp());
        updateSegmentButtons();

        // Hero row: the count, its label (or the state line),
        // and the source tag on the left; the slot sparkline
        // on the right (stage 1 spec S1-03, stage 2 S2-02).
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout heroLeft = new LinearLayout(this);
        heroLeft.setOrientation(LinearLayout.VERTICAL);
        netNumView = text("", 90, ACCENT, true);
        netNumView.setTypeface(VisionFonts.displayBold(this));
        heroLeft.addView(netNumView, matchLp());
        netSubView = text("", 15, LABEL2, false);
        heroLeft.addView(netSubView, matchLpTop(10));
        netTagView = text("", 11, LABEL3, false);
        netTagView.setTypeface(VisionFonts.mono(this));
        heroLeft.addView(netTagView, matchLpTop(6));
        hero.addView(heroLeft, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        sparkView = new SparklineView(this);
        LinearLayout.LayoutParams splp = new LinearLayout.LayoutParams(
                dp(96), dp(32));
        splp.gravity = Gravity.CENTER_VERTICAL;
        splp.setMarginStart(dp(12));
        hero.addView(sparkView, splp);
        col.addView(hero, matchLpTop(18));

        // The field, in its card.
        FrameLayout fieldCard = new FrameLayout(this);
        fieldCard.setBackground(cardBg());
        networkField = new NetworkFieldView(this);
        networkField.setOnClickListener(v -> openReadSheet());
        fieldCard.addView(networkField, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams flp = matchLpTop(20);
        flp.height = dp(340);
        col.addView(fieldCard, flp);

        netGhostNoteView = text(
                I18n.get(this, StringsTable.SEG_24H_NOTE),
                12, LABEL3, false);
        netGhostNoteView.setVisibility(View.GONE);
        col.addView(netGhostNoteView, matchLpTop(10));

        // The ticker zone: the live log's latest lines, newest
        // first, resting above the nav zone (S2-03).
        tickerBox = new LinearLayout(this);
        tickerBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(tickerBox, matchLpTop(18));

        buildReadSheet(root);

        container.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return root;
    }

    /** One segment pill of the network header. */
    private TextView segmentButton(String label, boolean is24h) {
        TextView b = text(label, 12, LABEL3, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(6), dp(12), dp(6));
        b.setClickable(true);
        b.setOnClickListener(v -> {
            netShow24h = is24h;
            updateSegmentButtons();
        });
        return b;
    }

    private void updateSegmentButtons() {
        if (netSegNowBtn == null || netSeg24hBtn == null) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        android.graphics.drawable.Drawable sel = rounded(
                Color.argb(38, 0xE8, 0xE6, 0xE0), 999, density, 0, 0);
        netSegNowBtn.setBackground(netShow24h ? null : sel);
        netSegNowBtn.setTextColor(netShow24h ? LABEL3 : LABEL1);
        netSeg24hBtn.setBackground(netShow24h ? sel : null);
        netSeg24hBtn.setTextColor(netShow24h ? LABEL1 : LABEL3);
        if (netGhostNoteView != null) {
            netGhostNoteView.setVisibility(
                    netShow24h ? View.VISIBLE : View.GONE);
        }
    }

    /** The how-to-read sheet (S1-02): an overlay panel on the
     *  network root; a tap on the scrim closes it. */
    private void buildReadSheet(FrameLayout root) {
        readSheetOverlay = new FrameLayout(this);
        readSheetOverlay.setBackgroundColor(Color.argb(160, 0, 0, 0));
        readSheetOverlay.setVisibility(View.GONE);
        readSheetOverlay.setOnClickListener(
                v -> readSheetOverlay.setVisibility(View.GONE));
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(cardBg());
        panel.setPadding(dp(26), dp(22), dp(26), dp(22));
        panel.setOnClickListener(v -> {
            // Swallow taps so the panel itself does not close.
        });
        TextView title = text(
                I18n.get(this, StringsTable.FIELD_HOWTO),
                17, LABEL1, true);
        panel.addView(title, matchLp());
        readSheetBestView = text("", 15, LABEL1, false);
        readSheetBestView.setTypeface(VisionFonts.mono(this));
        panel.addView(readSheetBestView, matchLpTop(16));
        readSheetFactsView = text("", 13, LABEL2, false);
        readSheetFactsView.setTypeface(VisionFonts.mono(this));
        panel.addView(readSheetFactsView, matchLpTop(8));
        TextView rings = text(
                I18n.get(this, StringsTable.FIELD_READ_RINGS),
                13, LABEL2, false);
        rings.setLineSpacing(0f, 1.3f);
        panel.addView(rings, matchLpTop(16));
        TextView ghost = text(
                I18n.get(this, StringsTable.FIELD_READ_GHOST),
                13, LABEL2, false);
        ghost.setLineSpacing(0f, 1.3f);
        panel.addView(ghost, matchLpTop(12));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        plp.setMargins(dp(16), 0, dp(16), dp(16));
        readSheetOverlay.addView(panel, plp);
        root.addView(readSheetOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    /** Fill and show the how-to-read sheet with the aggregate
     *  facts of this moment (S1-02): the node's own best, the
     *  connection count, the last observation, the session
     *  uptime. Nothing per-peer exists to show. */
    private void openReadSheet() {
        if (readSheetOverlay == null) {
            return;
        }
        NodeState st = NodeState.get();
        String tag = I18n.effectiveTag(this);
        long now = SystemClock.elapsedRealtime();
        readSheetBestView.setText(
                I18n.get(this, StringsTable.FIELD_BEST) + "  "
                        + (st.bestBlock >= 0
                                ? "#" + UiLogic.grouped(st.bestBlock)
                                : "\u2014"));
        String peersText = st.peers >= 0
                ? UiLogic.grouped(st.peers) : "\u2014";
        String obsText = st.lastRpcOkWallMs > 0
                ? timeHhMmSs(st.lastRpcOkWallMs) : "\u2014";
        String upText = st.sessionAcceptedElapsedMs >= 0
                ? UiLogic.durationText(tag,
                        now - st.sessionAcceptedElapsedMs, false)
                : "\u2014";
        readSheetFactsView.setText(I18n.format(this,
                StringsTable.FIELD_FACTS_FMT,
                peersText, obsText, upText));
        readSheetOverlay.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------
    // Member tab (vision): the card, or the guidance alone
    // ------------------------------------------------------------------

    private View buildMember(FrameLayout container, int dir) {
        // A vertical column, not an overlay frame: the title owns
        // the top row and the card centres in the area BELOW it.
        // The overlay layout let the card slide up over the
        // title, leaving only its first character visible
        // (device finding, 2026-11-09).
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setLayoutDirection(dir);
        root.setVisibility(View.GONE);
        int side = sidePadding();

        TextView title = text(
                I18n.get(this, StringsTable.VISION_MEM_TITLE),
                20, LABEL1, true);
        title.setTypeface(VisionFonts.displayBold(this));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.setMarginStart(side);
        tlp.setMarginEnd(side);
        tlp.topMargin = dp(24);
        tlp.bottomMargin = dp(20);
        root.addView(title, tlp);

        // The area the card (or the guidance) centres in. Its
        // bottom padding keeps the centred card out of the
        // floating nav's zone.
        FrameLayout area = new FrameLayout(this);
        area.setPadding(0, 0, 0, navZone());
        root.addView(area, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Guidance while DePIN holds no records yet: no card is
        // dressed up for a record that does not exist.
        memberEmptyView = text(
                I18n.get(this, StringsTable.VISION_MEMBER_EMPTY),
                15, LABEL2, false);
        memberEmptyView.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        elp.setMarginStart(side);
        elp.setMarginEnd(side);
        area.addView(memberEmptyView, elp);

        // The card: glow layer behind, content on top.
        memberCardBox = new FrameLayout(this);
        memberGlow = new GlowCardView(this);
        memberCardBox.addView(memberGlow, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout content = new LinearLayout(this);
        memberContent = content;
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(22), dp(22), dp(22));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(logoView(48, 13));
        TextView memberWord = text("MEMBER", 11, ACCENT, true);
        memberWord.setTypeface(VisionFonts.displayBold(this));
        memberWord.setLetterSpacing(0.32f);
        LinearLayout.LayoutParams mwlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        mwlp.setMarginStart(dp(12));
        top.addView(memberWord, mwlp);
        content.addView(top, matchLp());
        memNameView = text("", 56, LABEL1, true);
        memNameView.setTypeface(VisionFonts.displayBold(this));
        memNameView.setSingleLine(true);
        content.addView(memNameView, matchLpTop(28));
        TextView idLabel = cardLabel(
                I18n.get(this, StringsTable.VISION_MEM_ID));
        content.addView(idLabel, matchLpTop(18));
        memIdView = text("", 14, LABEL1, false);
        memIdView.setTypeface(VisionFonts.mono(this));
        memIdView.setSingleLine(true);
        // The number rests grouped in fours for reading; a tap
        // shows the raw id for copying, and back (S1-11).
        memIdView.setClickable(true);
        memIdView.setOnClickListener(v -> {
            memIdGrouped = !memIdGrouped;
            if (memIdRaw != null) {
                memIdView.setText(memIdGrouped
                        ? groupMemberId(memIdRaw) : memIdRaw);
                fitSingleLinePx(memIdView, 14f, 10f, 7f);
            }
        });
        content.addView(memIdView, matchLpTop(8));
        LinearLayout mstats = new LinearLayout(this);
        mstats.setOrientation(LinearLayout.HORIZONTAL);
        memSinceValue = memberStat(mstats,
                I18n.get(this, StringsTable.VISION_SINCE), 1.35f, true);
        memSinceValue.setTypeface(VisionFonts.mono(this));
        memCpsValue = memberStat(mstats,
                I18n.get(this, StringsTable.VISION_CPS), 1f, false);
        memDaysValue = memberStat(mstats,
                I18n.get(this, StringsTable.MEM_STREAK), 1f, false);
        content.addView(mstats, matchLpTop(20));
        // The card keeps the credit-card ratio (1.586:1) when
        // its content is shorter than the ratio demands; the
        // spacer absorbs the difference and the refresh loop
        // sizes it. Content taller than the ratio always wins:
        // nothing is ever clipped for the ratio's sake (S1-11).
        memberSpacer = new View(this);
        content.addView(memberSpacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0));
        memberCardBox.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                Math.min(dp(330), getResources().getDisplayMetrics()
                        .widthPixels - side * 2),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        area.addView(memberCardBox, clp);

        container.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return root;
    }

    /**
     * One member-card stat column; returns its value view. The
     * label is single-line and both label and value are fitted
     * to the column's width at refresh time (see
     * fitSingleLinePx): the equal split used to starve the date
     * column until the value was clipped and the longest label
     * wrapped onto the value's line (device finding,
     * 2026-11-09). Columns are disjoint layout cells, so with
     * every text fitted to its own cell nothing can overlap or
     * be cut.
     */
    private TextView memberStat(LinearLayout parent, String label,
                                float weight, boolean first) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView lab = text(label, 12, LABEL2, false);
        lab.setSingleLine(true);
        col.addView(lab, matchLp());
        TextView v = text("", 20, LABEL1, true);
        v.setTypeface(VisionFonts.displayMedium(this));
        v.setSingleLine(true);
        col.addView(v, matchLpTop(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        if (!first) {
            lp.setMarginStart(dp(12));
        }
        parent.addView(col, lp);
        statLabelViews.add(lab);
        statValueViews.add(v);
        return v;
    }

    // ------------------------------------------------------------------
    // Bottom navigation (vision): a floating pill, three tabs
    // ------------------------------------------------------------------

    private void buildBottomNav(FrameLayout container, int dir) {
        bottomNav = new LinearLayout(this);
        bottomNav.setOrientation(LinearLayout.HORIZONTAL);
        bottomNav.setLayoutDirection(dir);
        float density = getResources().getDisplayMetrics().density;
        bottomNav.setBackground(rounded(
                Color.argb(209, 0x18, 0x18, 0x18), 999, density,
                CARD_LINE, 1));
        bottomNav.setPadding(dp(6), dp(6), dp(6), dp(6));
        bottomNav.setElevation(dp(12));
        String[] labels = {
                I18n.get(this, StringsTable.VISION_NAV_HOME),
                I18n.get(this, StringsTable.VISION_NAV_NETWORK),
                I18n.get(this, StringsTable.VISION_NAV_MEMBER)};
        int[] icons = {NavIconView.ICON_HOME,
                NavIconView.ICON_NETWORK, NavIconView.ICON_MEMBER};
        int[] targets = {SCREEN_HOME, SCREEN_NETWORK, SCREEN_MEMBER};
        for (int i = 0; i < 3; i++) {
            final int target = targets[i];
            LinearLayout btn = new LinearLayout(this);
            btn.setOrientation(LinearLayout.VERTICAL);
            btn.setGravity(Gravity.CENTER);
            btn.setMinimumWidth(dp(78));
            btn.setPadding(dp(14), dp(8), dp(14), dp(8));
            btn.setClickable(true);
            btn.setFocusable(true);
            btn.setContentDescription(labels[i]);
            navIcons[i] = new NavIconView(this, icons[i], LABEL3);
            btn.addView(navIcons[i], new LinearLayout.LayoutParams(
                    dp(20), dp(20)));
            navLabels[i] = text(labels[i], 11, LABEL3, true);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = dp(3);
            btn.addView(navLabels[i], nlp);
            btn.setOnClickListener(v -> showScreen(target));
            navButtons[i] = btn;
            bottomNav.addView(btn);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(22);
        container.addView(bottomNav, lp);
    }

    /** Failure / stop details: open details with diagnostics expanded. */
    private void openDiagnosticsFromHome() {
        diagExpanded = true;
        applySectionStates();
        scrollToDiagnosticsOnce = true;
        showScreen(SCREEN_DETAILS);
    }

    // ------------------------------------------------------------------
    // Node details
    // ------------------------------------------------------------------

    private ScrollView buildDetails(FrameLayout container, int dir) {
        LinearLayout root = screenContent(container, SCREEN_DETAILS, dir);
        subHeader(root, I18n.get(this, StringsTable.SCREEN_NODE_DETAILS));

        TextView identity = text(
                I18n.get(this, StringsTable.APP_SUBTITLE), 14, LABEL3, false);
        root.addView(identity, matchLpTop(8));

        // Running presentation: the exact current values.
        detailsRunningBox = new LinearLayout(this);
        detailsRunningBox.setOrientation(LinearLayout.VERTICAL);
        detBestValue = infoRow(detailsRunningBox,
                I18n.get(this, StringsTable.LABEL_BEST_BLOCK), false);
        detHighestValue = infoRow(detailsRunningBox,
                I18n.get(this, StringsTable.LABEL_HIGHEST_BLOCK), true);
        detGapValue = infoRow(detailsRunningBox,
                I18n.get(this, StringsTable.LABEL_SYNC_GAP), true);
        detPeersValue = infoRow(detailsRunningBox,
                I18n.get(this, StringsTable.LABEL_PEERS), true);
        detSessionValue = infoRow(detailsRunningBox,
                I18n.get(this, StringsTable.LABEL_UPTIME_SESSION), true);
        TextView gapNote = text(
                I18n.get(this, StringsTable.SYNC_GAP_NOTE), 14, LABEL3, false);
        detailsRunningBox.addView(gapNote, matchLpTop(8));
        root.addView(detailsRunningBox, matchLpTop(16));

        // Stopped presentation (spec D): the cumulative record.
        detailsStoppedBox = new LinearLayout(this);
        detailsStoppedBox.setOrientation(LinearLayout.VERTICAL);
        TextView cumNote = text(I18n.get(
                this, StringsTable.STOPPED_CUMULATIVE_NOTE), 14, LABEL3, false);
        detailsStoppedBox.addView(cumNote, matchLp());
        cumUptimeValue = infoRow(detailsStoppedBox,
                I18n.get(this, StringsTable.LABEL_UPTIME_TOTAL), false);
        cumBlocksValue = infoRow(detailsStoppedBox,
                I18n.get(this, StringsTable.LABEL_BLOCKS_OBSERVED), true);
        cumPeersValue = infoRow(detailsStoppedBox,
                I18n.get(this, StringsTable.LABEL_MAX_PEERS), true);
        root.addView(detailsStoppedBox, matchLpTop(16));

        // Network / RPC line (raw endpoint — not translated, by design).
        TextView rpc = text(I18n.get(this, StringsTable.RPC_LOCAL_NOTE)
                + " · 127.0.0.1:9944", 14, LABEL3, false);
        root.addView(rpc, matchLpTop(24));

        // Diagnostics (expandable).
        diagSection = new LinearLayout(this);
        diagSection.setOrientation(LinearLayout.VERTICAL);
        LinearLayout diagHeader = expandableHeader(
                I18n.get(this, StringsTable.SECTION_DIAGNOSTICS), null,
                v -> {
                    diagExpanded = !diagExpanded;
                    applySectionStates();
                });
        diagChevron = (TextView) diagHeader.getChildAt(1);
        diagSection.addView(diagHeader, matchLp());
        diagContent = new LinearLayout(this);
        diagContent.setOrientation(LinearLayout.VERTICAL);
        diagText = text("", 14, LABEL2, false);
        diagText.setTextIsSelectable(true);
        diagContent.addView(diagText, matchLp());
        TextView logLocLabel = text(
                I18n.get(this, StringsTable.LABEL_LOG_LOCATION), 14, LABEL3, false);
        diagContent.addView(logLocLabel, matchLpTop(16));
        TextView logLoc = text("files/logs/node.log", 14, LABEL2, false);
        diagContent.addView(logLoc, matchLpTop(4));
        Button share = new Button(this);
        share.setText(I18n.get(this, StringsTable.BUTTON_SHARE_LOG));
        share.setTextSize(16);
        share.setTextColor(ACCENT);
        share.setAllCaps(false);
        share.setBackground(ripple(null));
        share.setStateListAnimator(null);
        share.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        share.setMinHeight(dp(48));
        share.setOnClickListener(v -> onShareLogClicked());
        diagContent.addView(share, matchLpTop(8));
        diagSection.addView(diagContent, matchLp());
        root.addView(diagSection, matchLpTop(32));

        applySectionStates();
        return (ScrollView) screens[SCREEN_DETAILS];
    }

    /**
     * A tappable section header: section label, an optional trailing
     * status word, and a chevron that reflects the open state.
     */
    private LinearLayout expandableHeader(String label, String trailing,
                                          View.OnClickListener click) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setMinimumHeight(dp(56));
        h.setPadding(0, dp(12), 0, dp(12));
        h.setBackground(ripple(null));
        h.setClickable(true);
        h.setFocusable(true);
        h.setOnClickListener(click);
        TextView l = sectionLabel(label);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        h.addView(l, llp);
        if (trailing != null) {
            TextView t = text(trailing, 14, LABEL3, false);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            tlp.setMarginEnd(dp(12));
            h.addView(t, tlp);
        }
        TextView chev = text("›", 22, LABEL3, false);
        h.addView(chev);
        h.setTag(label);
        return h;
    }

    /** Apply the diagnostics open state + a11y wording. */
    private void applySectionStates() {
        if (diagContent != null) {
            diagContent.setVisibility(diagExpanded ? View.VISIBLE : View.GONE);
            diagChevron.setText(diagExpanded ? "⌄" : (I18n.isRtl(this) ? "‹" : "›"));
            View header = (View) diagChevron.getParent();
            header.setContentDescription(
                    I18n.get(this, StringsTable.SECTION_DIAGNOSTICS) + ", "
                            + I18n.get(this, diagExpanded
                                    ? StringsTable.A11Y_EXPANDED
                                    : StringsTable.A11Y_COLLAPSED));
        }
    }

    // ------------------------------------------------------------------
    // Track
    // ------------------------------------------------------------------

    private ScrollView buildTrack(FrameLayout container, int dir) {
        LinearLayout root = screenContent(container, SCREEN_TRACK, dir);
        subHeader(root, I18n.get(this, StringsTable.SECTION_TRACK));
        trackUptimeValue = bigMetric(root,
                I18n.get(this, StringsTable.LABEL_UPTIME_TOTAL), 24);
        trackBlocksValue = bigMetric(root,
                I18n.get(this, StringsTable.LABEL_BLOCKS_OBSERVED), 32);
        trackPeersValue = bigMetric(root,
                I18n.get(this, StringsTable.LABEL_MAX_PEERS), 32);
        TextView note = text(
                I18n.get(this, StringsTable.TRACK_LOCAL_NOTE), 14, LABEL3, false);
        root.addView(note, matchLpTop(32));
        return (ScrollView) screens[SCREEN_TRACK];
    }

    /** One track metric: small label, 24 sp medium value. */
    private TextView bigMetric(LinearLayout parent, String label, int topDp) {
        TextView l = text(label, 14, LABEL2, false);
        parent.addView(l, matchLpTop(topDp));
        TextView v = text("", 24, LABEL1, true);
        parent.addView(v, matchLpTop(4));
        return v;
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    private ScrollView buildSettings(FrameLayout container, int dir) {
        LinearLayout root = screenContent(container, SCREEN_SETTINGS, dir);
        subHeader(root, I18n.get(this, StringsTable.SECTION_SETTINGS));

        TextView restartNote = text(I18n.get(
                this, StringsTable.SETTINGS_NODE_RESTART_NOTE), 14, LABEL3, false);
        root.addView(restartNote, matchLpTop(8));

        // Telemetry: switch + always-visible public-scope note.
        telemetrySwitch = settingsSwitch(root, 16, ID_ANCHOR_TELEMETRY,
                I18n.get(this, StringsTable.SETTINGS_TELEMETRY),
                NodeSettings.telemetryEnabled(this),
                (view, isChecked) -> {
                    if (syncingSettings) {
                        return;
                    }
                    NodeSettings.setTelemetryEnabled(this, isChecked);
                    refresh();
                });
        telemetryRow = (LinearLayout) telemetrySwitch.getParent();
        telemetryPendingView = text("", 14, ACCENT, false);
        root.addView(telemetryPendingView, matchLpTop(4));
        telemetryAppliedView = text("", 14, LABEL3, false);
        root.addView(telemetryAppliedView, matchLpTop(4));
        TextView telemetryNote = text(I18n.get(
                this, StringsTable.SETTINGS_TELEMETRY_NOTE), 14, LABEL3, false);
        root.addView(telemetryNote, matchLpTop(4));
        telemetryLockedView = text(I18n.get(
                this, StringsTable.SETTINGS_LOCKED_NOTICE), 14, LABEL3, false);
        telemetryLockedView.setVisibility(View.GONE);
        root.addView(telemetryLockedView, matchLpTop(4));

        root.addView(hairline(24));

        // DePIN: opt-in observation of this node's uptime, kept
        // entirely separate from node operation (integration step 2).
        // The toggle talks only to the DepinModule facade; the rows
        // below it are a read-only view of its latest status.
        TextView depinHeading = text(I18n.get(
                this, StringsTable.SETTINGS_DEPIN_SECTION), 16, LABEL2, true);
        root.addView(depinHeading, matchLpTop(8));
        depinSwitch = settingsSwitch(root, 8, ID_ANCHOR_DEPIN,
                I18n.get(this, StringsTable.SETTINGS_DEPIN_TOGGLE),
                false,
                (view, isChecked) -> {
                    if (syncingSettings) {
                        return;
                    }
                    DepinStatus cur = DepinModule.get(this).latestStatus();
                    String deviceId = cur != null ? cur.deviceId : null;
                    long generation = cur != null
                            ? cur.identityGeneration : 0;
                    if (deviceId != null
                            && currentTransferActiveDeviceId != null
                            && !deviceId.equals(
                                    currentTransferActiveDeviceId)) {
                        // The status snapshot still describes the
                        // identity a completed transfer displaced:
                        // its binding is not an expectation the
                        // service may honour for the live identity.
                        // Send no expectation — the command then
                        // means "the live identity", exactly as
                        // for a fresh identity's first enable
                        // (progress file section 9).
                        deviceId = null;
                        generation = 0;
                    }
                    if (isChecked) {
                        DepinModule.get(this).enable(
                                deviceId, generation, notifStrings());
                    } else {
                        DepinModule.get(this).disable(deviceId, generation);
                    }
                    refresh();
                });
        depinRow = (LinearLayout) depinSwitch.getParent();
        TextView depinNote = text(I18n.get(
                this, StringsTable.SETTINGS_DEPIN_NOTE), 14, LABEL3, false);
        root.addView(depinNote, matchLpTop(4));
        depinLockedView = text(I18n.get(
                this, StringsTable.SETTINGS_LOCKED_NOTICE), 14, LABEL3, false);
        depinLockedView.setVisibility(View.GONE);
        root.addView(depinLockedView, matchLpTop(4));
        depinDeviceIdValue = infoRow(root,
                I18n.get(this, StringsTable.DEPIN_LABEL_DEVICE_ID), false);
        depinStateValue = infoRow(root,
                I18n.get(this, StringsTable.DEPIN_LABEL_STATE), true);
        depinCheckpointsValue = infoRow(root,
                I18n.get(this, StringsTable.DEPIN_LABEL_CHECKPOINTS), true);
        depinLastCheckpointValue = infoRow(root,
                I18n.get(this, StringsTable.DEPIN_LABEL_LAST_CHECKPOINT), true);
        depinNoteView = text("", 14, LABEL3, false);
        depinNoteView.setVisibility(View.GONE);
        root.addView(depinNoteView, matchLpTop(4));

        // Attempt-log repair (design section 2.3; G-11): shown
        // only while the status reports the attempt-log
        // self-stop with the corruption still present (the
        // visibility is driven in the settings refresh). The
        // tap asks for an explicit confirmation first — the
        // reset is a maintenance operation, never a silent one.
        depinAttemptResetButton = new Button(this);
        depinAttemptResetButton.setText(I18n.get(
                this, StringsTable.DEPIN_ATTEMPT_RESET_BUTTON));
        depinAttemptResetButton.setTextSize(15);
        depinAttemptResetButton.setTextColor(ACCENT);
        depinAttemptResetButton.setAllCaps(false);
        depinAttemptResetButton.setBackground(ripple(null));
        depinAttemptResetButton.setStateListAnimator(null);
        depinAttemptResetButton.setGravity(
                Gravity.START | Gravity.CENTER_VERTICAL);
        depinAttemptResetButton.setMinHeight(dp(44));
        depinAttemptResetButton.setVisibility(View.GONE);
        depinAttemptResetButton.setOnClickListener(
                v -> confirmAttemptLogReset());
        root.addView(depinAttemptResetButton, matchLpTop(4));

        // Proof-bundle file export (integration step 5): the
        // companion has always had this; the integrated build
        // gains it here. The bundle is built and staged by the
        // :depin process (see DepinBundleExport); this button
        // only asks the facade and then offers the system
        // document picker for the destination.
        depinExportButton = new Button(this);
        depinExportButton.setText(I18n.get(
                this, StringsTable.DEPIN_EXPORT_BUNDLE));
        depinExportButton.setTextSize(15);
        depinExportButton.setTextColor(ACCENT);
        depinExportButton.setAllCaps(false);
        depinExportButton.setBackground(ripple(null));
        depinExportButton.setStateListAnimator(null);
        depinExportButton.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        depinExportButton.setMinHeight(dp(44));
        depinExportButton.setOnClickListener(v -> onExportBundleClicked());
        root.addView(depinExportButton, matchLpTop(2));
        depinExportNoteView = text("", 14, LABEL3, false);
        depinExportNoteView.setVisibility(View.GONE);
        root.addView(depinExportNoteView, matchLpTop(4));

        // Device transfer (integration step 4, design section
        // 5.3; UI consolidated into a state-driven main action):
        // receive a recorded identity from the companion
        // through a straight line of file hand-overs, or act as
        // the sender for a reverse migration. Each role's block
        // shows its derived state and ONE main button — the next
        // step — while the off-path operations (cancel token,
        // cannot-confirm response, transfer cancellation, ack
        // re-presentation) live collapsed under "other actions".
        // Every value shown at the confirmation step is a full
        // value.
        TextView transferHeading = text(I18n.get(
                this, StringsTable.DEPIN_TRANSFER_SECTION), 16, LABEL2, true);
        root.addView(transferHeading, matchLpTop(16));
        TextView transferNote = text(I18n.get(
                this, StringsTable.DEPIN_TRANSFER_NOTE), 14, LABEL3, false);
        root.addView(transferNote, matchLpTop(4));
        depinTransferLockedView = text(I18n.get(
                this, StringsTable.DEPIN_TRANSFER_LOCKED), 14, LABEL3, false);
        depinTransferLockedView.setVisibility(View.GONE);
        root.addView(depinTransferLockedView, matchLpTop(4));
        depinTransferResultView = text("", 14, LABEL3, false);
        depinTransferResultView.setVisibility(View.GONE);
        root.addView(depinTransferResultView, matchLpTop(4));
        // The error-file export sits directly under the failure
        // line it belongs to (visible only while a failure is
        // held for export). It is not a transfer operation, so
        // it stays out of depinTransferButtons and the busy
        // discipline is applied to it directly.
        depinTransferSaveErrorButton = new Button(this);
        depinTransferSaveErrorButton.setText(I18n.get(this,
                StringsTable.DEPIN_TRANSFER_SAVE_ERROR));
        depinTransferSaveErrorButton.setTextSize(15);
        depinTransferSaveErrorButton.setTextColor(ACCENT);
        depinTransferSaveErrorButton.setAllCaps(false);
        depinTransferSaveErrorButton.setBackground(ripple(null));
        depinTransferSaveErrorButton.setStateListAnimator(null);
        depinTransferSaveErrorButton.setGravity(
                Gravity.START | Gravity.CENTER_VERTICAL);
        depinTransferSaveErrorButton.setMinHeight(dp(44));
        depinTransferSaveErrorButton.setVisibility(View.GONE);
        depinTransferSaveErrorButton.setOnClickListener(
                v -> runTransferAction(ACT_SAVE_ERROR));
        root.addView(depinTransferSaveErrorButton, matchLpTop(2));

        // Receiver block.
        TextView receiverHeading = text(I18n.get(
                this, StringsTable.DEPIN_TRANSFER_RECEIVER_HEADING),
                15, LABEL2, true);
        root.addView(receiverHeading, matchLpTop(12));
        depinTransferReceiverStateView = text("", 14, LABEL3, false);
        root.addView(depinTransferReceiverStateView, matchLpTop(4));
        depinTransferReceiverMainButton = mainTransferButton(
                v -> runTransferAction(receiverMainAction));
        root.addView(depinTransferReceiverMainButton, matchLpTop(2));
        final LinearLayout receiverOthers = new LinearLayout(this);
        receiverOthers.setOrientation(LinearLayout.VERTICAL);
        receiverOthers.setVisibility(View.GONE);
        root.addView(othersToggle(receiverOthers, true), matchLpTop(2));
        root.addView(receiverOthers, matchLpTop(0));
        transferButton(receiverOthers,
                StringsTable.DEPIN_TRANSFER_IMPORT_CANCEL_TOKEN,
                ACT_IMPORT_CANCEL_TOKEN);
        transferButton(receiverOthers,
                StringsTable.DEPIN_TRANSFER_EXPORT_ABANDON,
                ACT_EXPORT_ABANDON);
        transferButton(receiverOthers,
                StringsTable.DEPIN_TRANSFER_EXPORT_NOTICE,
                ACT_EXPORT_NOTICE);
        transferButton(receiverOthers,
                StringsTable.DEPIN_TRANSFER_EXPORT_RECEIPT,
                ACT_EXPORT_RECEIPT);
        depinTransferConfirmOneshotButton = transferButton(receiverOthers,
                StringsTable.DEPIN_TRANSFER_CONFIRM_ONESHOT,
                ACT_CONFIRM_ONESHOT);

        // Sender block (reverse migration).
        TextView senderHeading = text(I18n.get(
                this, StringsTable.DEPIN_TRANSFER_SENDER_HEADING),
                15, LABEL2, true);
        root.addView(senderHeading, matchLpTop(12));
        depinTransferSenderStateView = text("", 14, LABEL3, false);
        root.addView(depinTransferSenderStateView, matchLpTop(4));
        depinTransferSenderMainButton = mainTransferButton(
                v -> runTransferAction(senderMainAction));
        root.addView(depinTransferSenderMainButton, matchLpTop(2));
        final LinearLayout senderOthers = new LinearLayout(this);
        senderOthers.setOrientation(LinearLayout.VERTICAL);
        senderOthers.setVisibility(View.GONE);
        root.addView(othersToggle(senderOthers, false), matchLpTop(2));
        root.addView(senderOthers, matchLpTop(0));
        transferButton(senderOthers,
                StringsTable.DEPIN_TRANSFER_CANCEL_TRANSFER,
                ACT_CANCEL_TRANSFER);
        transferButton(senderOthers,
                StringsTable.DEPIN_TRANSFER_IMPORT_ABANDON,
                ACT_IMPORT_ABANDON);
        depinTransferRepresentAckButton = transferButton(senderOthers,
                StringsTable.DEPIN_TRANSFER_REPRESENT_ACK,
                ACT_REPRESENT_ACK);
        transferButton(senderOthers,
                StringsTable.DEPIN_TRANSFER_EXPORT_PACKAGE,
                ACT_EXPORT_PACKAGE);
        depinTransferCancelOneshotButton = transferButton(senderOthers,
                StringsTable.DEPIN_TRANSFER_CANCEL_ONESHOT,
                ACT_CANCEL_ONESHOT);
        depinTransferReexportButton = transferButton(senderOthers,
                StringsTable.DEPIN_TRANSFER_REEXPORT_ONESHOT,
                ACT_REEXPORT_ONESHOT);

        root.addView(hairline(24));

        // Display name: shows the stored value; opens the editor.
        nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        nameRow.setMinimumHeight(dp(56));
        nameRow.setPadding(0, dp(12), 0, dp(12));
        nameRow.setBackground(ripple(null));
        nameRow.setClickable(true);
        nameRow.setFocusable(true);
        nameRow.setOnClickListener(v -> {
            if (!NodeSettings.settingsLocked(this)) {
                showNameDialog();
            }
        });
        nameRow.setId(ID_ANCHOR_NAME);
        settingsAnchors.add(nameRow);
        TextView nameLabel = text(
                I18n.get(this, StringsTable.SETTINGS_DISPLAY_NAME), 16, LABEL2, false);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nlp.setMarginEnd(dp(24));
        nameRow.addView(nameLabel, nlp);
        nameValue = text("", 16, LABEL1, false);
        nameValue.setGravity(Gravity.END);
        nameRow.addView(nameValue);
        nameChevron = text(I18n.isRtl(this) ? "‹" : "›", 22, LABEL3, false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.setMarginStart(dp(12));
        nameRow.addView(nameChevron, clp);
        root.addView(nameRow, matchLpTop(8));
        namePendingView = text("", 14, ACCENT, false);
        root.addView(namePendingView, matchLpTop(4));
        nameAppliedView = text("", 14, LABEL3, false);
        root.addView(nameAppliedView, matchLpTop(4));
        nameLockedView = text(I18n.get(
                this, StringsTable.SETTINGS_NAME_LOCKED), 14, LABEL3, false);
        root.addView(nameLockedView, matchLpTop(4));

        root.addView(hairline(24));

        // Language: shows the current choice; opens the picker.
        languageRow = new LinearLayout(this);
        languageRow.setOrientation(LinearLayout.HORIZONTAL);
        languageRow.setGravity(Gravity.CENTER_VERTICAL);
        languageRow.setMinimumHeight(dp(56));
        languageRow.setPadding(0, dp(12), 0, dp(12));
        languageRow.setBackground(ripple(null));
        languageRow.setClickable(true);
        languageRow.setFocusable(true);
        languageRow.setOnClickListener(v -> showLanguageDialog());
        languageRow.setId(ID_ANCHOR_LANGUAGE);
        settingsAnchors.add(languageRow);
        TextView langLabel = text(
                I18n.get(this, StringsTable.SETTINGS_LANGUAGE), 16, LABEL2, false);
        LinearLayout.LayoutParams llp2 = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        llp2.setMarginEnd(dp(24));
        languageRow.addView(langLabel, llp2);
        languageValue = text("", 16, LABEL1, false);
        languageValue.setGravity(Gravity.END);
        languageRow.addView(languageValue);
        TextView langChevron = text(I18n.isRtl(this) ? "‹" : "›", 22, LABEL3, false);
        LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        clp2.setMarginStart(dp(12));
        languageRow.addView(langChevron, clp2);
        root.addView(languageRow, matchLpTop(8));

        root.addView(hairline(24));

        // Settings lock: always reachable, including to unlock.
        lockSwitch = settingsSwitch(root, 8, ID_ANCHOR_LOCK,
                I18n.get(this, StringsTable.SETTINGS_LOCK),
                NodeSettings.settingsLocked(this),
                (view, isChecked) -> {
                    if (syncingSettings) {
                        return;
                    }
                    if (isChecked != NodeSettings.settingsLocked(this)) {
                        NodeSettings.setSettingsLocked(this, isChecked);
                        applySettingsLock();
                        refresh();
                    }
                });
        TextView lockNote = text(I18n.get(
                this, StringsTable.SETTINGS_LOCK_NOTE), 14, LABEL3, false);
        root.addView(lockNote, matchLpTop(4));

        // Version, at the very bottom.
        TextView version = text(versionText(), 14, LABEL3, false);
        version.setGravity(Gravity.CENTER);
        root.addView(version, matchLpTop(32));
        return (ScrollView) screens[SCREEN_SETTINGS];
    }

    /**
     * The DePIN service's notification texts, resolved here (the UI
     * owns localisation) and handed to the facade for the service
     * process, mirroring how the node service receives its strings.
     */
    private DepinIpc.NotifStrings notifStrings() {
        DepinIpc.NotifStrings s = new DepinIpc.NotifStrings();
        s.title = I18n.get(this, StringsTable.DEPIN_NOTIF_TITLE);
        s.channelName = I18n.get(this, StringsTable.DEPIN_NOTIF_CHANNEL_NAME);
        s.channelDesc = I18n.get(this, StringsTable.DEPIN_NOTIF_CHANNEL_DESC);
        s.starting = I18n.get(this, StringsTable.DEPIN_NOTIF_STARTING);
        s.observing = I18n.get(this, StringsTable.DEPIN_NOTIF_OBSERVING);
        s.checkpointFormat = I18n.get(this, StringsTable.DEPIN_NOTIF_CHECKPOINT);
        s.cannotStart = I18n.get(this, StringsTable.DEPIN_NOTIF_CANNOT_START);
        return s;
    }

    private String versionText() {
        String v = "";
        try {
            v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            // The version line is informational; never crash over it.
        }
        return I18n.format(this, StringsTable.VERSION_FORMAT, v == null ? "" : v);
    }

    /**
     * A settings switch row: label at the start, a {@link
     * ToggleView} at the end — the layout the platform Switch
     * produced when its text carried the label. The whole row is
     * the touch target (a tap anywhere toggles, as it did when the
     * row WAS the switch) and ripples on press; the toggle itself
     * stays the focusable, checkable accessibility target, with
     * the label as its content description. The row carries the
     * anchor id: it is the row's position a language-change
     * restore scrolls back to.
     *
     * The platform Switch dressed with code-generated drawables
     * (proposal §6, audit finding L-7) rendered broken on
     * GrapheneOS — a giant clipped half-disc for a track — so the
     * glyph is drawn on Canvas instead; see ToggleView.
     */
    private ToggleView settingsSwitch(LinearLayout root, int topDp,
                                      int anchorId, String label,
                                      boolean checked,
                                      ToggleView.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(0, dp(12), 0, dp(12));
        row.setBackground(ripple(null));
        row.setClickable(true);
        row.setId(anchorId);
        settingsAnchors.add(row);
        TextView labelView = text(label, 16, LABEL1, false);
        row.addView(labelView, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        ToggleView toggle = new ToggleView(this);
        toggle.setContentDescription(label);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener(listener);
        row.addView(toggle, new LinearLayout.LayoutParams(dp(52), dp(48)));
        row.setOnClickListener(v -> {
            if (toggle.isEnabled()) {
                toggle.toggle();
            }
        });
        root.addView(row, matchLpTop(topDp));
        return toggle;
    }

    /** Enable or disable the settings controls to match the lock. */
    private void applySettingsLock() {
        boolean locked = NodeSettings.settingsLocked(this);
        if (telemetrySwitch != null) {
            telemetrySwitch.setEnabled(!locked);
        }
        if (telemetryRow != null) {
            // The row is the tap target around the toggle; locking
            // must close both, or the row would keep toggling a
            // disabled switch.
            telemetryRow.setEnabled(!locked);
        }
        if (depinSwitch != null) {
            depinSwitch.setEnabled(!locked);
        }
        if (depinRow != null) {
            // Same as the telemetry row: the row is the tap target
            // around the toggle, so locking must close both.
            depinRow.setEnabled(!locked);
        }
        // Say WHY the controls are inert while locked (step-5
        // fix for the step-3 soak feedback: a toggle that does
        // not react and gives no reason reads as a bug).
        if (telemetryLockedView != null) {
            telemetryLockedView.setVisibility(
                    locked ? View.VISIBLE : View.GONE);
        }
        if (depinLockedView != null) {
            depinLockedView.setVisibility(
                    locked ? View.VISIBLE : View.GONE);
        }
        if (depinExportButton != null) {
            depinExportButton.setEnabled(!locked);
        }
        if (depinAttemptResetButton != null) {
            depinAttemptResetButton.setEnabled(!locked);
        }
        if (languageRow != null) {
            languageRow.setEnabled(!locked);
            languageRow.setAlpha(locked ? 0.55f : 1f);
        }
        if (nameRow != null) {
            nameRow.setEnabled(!locked);
            nameRow.setClickable(!locked);
            nameChevron.setVisibility(locked ? View.GONE : View.VISIBLE);
            nameLockedView.setVisibility(locked ? View.VISIBLE : View.GONE);
            String shown = shownName();
            nameRow.setContentDescription(
                    I18n.get(this, StringsTable.SETTINGS_DISPLAY_NAME) + ": " + shown
                            + (locked
                                    ? ". " + I18n.get(this, StringsTable.SETTINGS_NAME_LOCKED)
                                    : ""));
        }
        syncSwitch(lockSwitch, locked);
    }

    private void syncSwitch(ToggleView sw, boolean value) {
        if (sw != null && sw.isChecked() != value) {
            syncingSettings = true;
            sw.setChecked(value);
            syncingSettings = false;
        }
    }

    /** The name as the settings row shows it: stored value, or "default". */
    private String shownName() {
        String raw = NodeSettings.nodeNameRaw(this);
        return (raw == null || raw.isEmpty())
                ? I18n.get(this, StringsTable.SETTINGS_NAME_DEFAULT)
                : raw;
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void onMainButtonClicked() {
        if (stopping) {
            return;
        }
        NodeState st = NodeState.get();
        UiLogic.HomeState hs = UiLogic.homeState(st.status, st.lastStopKind);
        if (hs == UiLogic.HomeState.STARTING
                || hs == UiLogic.HomeState.SYNCING
                || hs == UiLogic.HomeState.SYNCED) {
            // Stop (or cancel the startup): the button flips to its
            // processing form at once; the service's own completion
            // is what brings the normal stopped screen back (spec H).
            stopping = true;
            Intent stop = new Intent(this, NodeService.class)
                    .setAction(NodeService.ACTION_STOP);
            startService(stop);
            Toast.makeText(this,
                    I18n.get(this, StringsTable.VISION_TOAST_STOP),
                    Toast.LENGTH_SHORT).show();
        } else {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIF);
            }
            Intent start = new Intent(this, NodeService.class)
                    .setAction(NodeService.ACTION_START);
            startForegroundService(start);
            Toast.makeText(this,
                    I18n.get(this, StringsTable.VISION_TOAST_START),
                    Toast.LENGTH_SHORT).show();
        }
        refresh();
    }

    /** Pending-settings link: settings screen, first pending item. */
    private void openPendingSettings() {
        showScreen(SCREEN_SETTINGS);
        NodeState st = NodeState.get();
        int pending = UiLogic.settingsPending(st.serviceRunning,
                st.launchedTelemetry, NodeSettings.telemetryEnabled(this),
                st.launchedNodeName, NodeSettings.effectiveNodeName(this));
        final View target = (pending & UiLogic.PENDING_TELEMETRY) != 0
                ? telemetryRow : nameRow;
        ScrollView ssv = (ScrollView) screens[SCREEN_SETTINGS];
        ssv.post(() -> ssv.scrollTo(0, Math.max(0, offsetInContent(
                target, ssv.getChildAt(0)) - dp(24))));
    }

    /**
     * Share the node's log file as plain text through the system
     * share sheet: the read runs off the UI thread through
     * NodeService's coordinated bounded reader, and only ever fires
     * on the user's own tap. versionCode 28 adds, on the same
     * background thread, the diagnostic summary's facts and the
     * masking inputs (see {@link DiagShare}); the UI half shows the
     * mask dialog in front of the sheet.
     */
    private void onShareLogClicked() {
        final File logDir = new File(getFilesDir(), "logs");
        final int shareMaxChars = 100_000;
        new Thread(() -> {
            boolean[] truncated = new boolean[1];
            String read = NodeService.readLogTailForShare(
                    logDir, shareMaxChars, truncated);
            final String logText = read;
            final boolean wasTruncated = truncated[0];
            final DiagShare.Facts facts =
                    logText == null ? null : gatherDiagFacts();
            final boolean[] ipEnumFailed = new boolean[1];
            final List<String> ownIps =
                    logText == null ? null : ownIpAddresses(ipEnumFailed);
            final List<String> pathPrefixes =
                    logText == null ? null : appPathPrefixes();
            runOnUiThread(() -> shareLog(logText, wasTruncated,
                    facts, ownIps, ipEnumFailed[0], pathPrefixes));
        }, "share-log-read").start();
    }

    /**
     * UI-thread half of share-log: the "no log" toast, or the mask
     * dialog (versionCode 28). Masking is ON by default: the shared
     * text then hides the display name, this device's IP addresses
     * and the app-private paths, so a log pasted into a public
     * issue keeps its diagnostic structure without the identifying
     * values. The user can turn it off for the shares where a
     * complete log is what the diagnosis needs — except when the
     * own-address enumeration failed, in which case the app's
     * picture of what masking must hide is known to be incomplete
     * and the toggle is locked on for that share.
     */
    private void shareLog(String logText, boolean wasTruncated,
                          DiagShare.Facts facts, List<String> ownIps,
                          boolean ipEnumFailed,
                          List<String> pathPrefixes) {
        if (isFinishing() || isDestroyed()) {
            // The background read finished into an activity that
            // is already going away: showing the dialog (or the
            // toast) on it can throw, and there is nobody left to
            // share for. The tap simply ends here.
            return;
        }
        if (logText == null || logText.trim().isEmpty()) {
            Toast.makeText(this, I18n.get(this, StringsTable.LOG_MISSING_NOTE),
                    Toast.LENGTH_LONG).show();
            return;
        }
        final String excerpt = logText;
        final boolean truncated = wasTruncated;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), dp(8));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(0, dp(12), 0, dp(12));
        row.setBackground(ripple(null));
        row.setClickable(true);
        row.setFocusable(true);
        TextView maskLabel = text(I18n.get(this, StringsTable.SHARE_MASK_LABEL),
                16, LABEL1, false);
        row.addView(maskLabel, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final ToggleView maskToggle = new ToggleView(this);
        maskToggle.setContentDescription(
                I18n.get(this, StringsTable.SHARE_MASK_LABEL));
        maskToggle.setChecked(true);
        if (ipEnumFailed) {
            // Locked on (see the method doc): the row's tap checks
            // the toggle's enabled state, so disabling it is
            // enough to hold the choice.
            maskToggle.setEnabled(false);
        }
        row.addView(maskToggle, new LinearLayout.LayoutParams(dp(52), dp(48)));
        row.setOnClickListener(v -> {
            if (maskToggle.isEnabled()) {
                maskToggle.toggle();
            }
        });
        box.addView(row, matchLp());

        TextView maskNote = text(I18n.get(this, StringsTable.SHARE_MASK_NOTE),
                14, LABEL3, false);
        box.addView(maskNote, matchLpTop(4));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(I18n.get(this, StringsTable.BUTTON_SHARE_LOG))
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                        I18n.get(this, StringsTable.BUTTON_SHARE_LOG),
                        (d, which) -> doShareLog(excerpt, truncated, facts,
                                maskToggle.isChecked(), ownIps, pathPrefixes))
                .create();
        dialog.show();
        styleDialog(dialog);
    }

    /**
     * Assemble the share text and open the sheet: the summary
     * block first (outside the excerpt's character cap), then the
     * truncation note and the excerpt exactly as before. Masking,
     * when on, is applied to the body only: running the
     * replacement over the summary could damage its fixed labels
     * and values (a display name of "off" against "Telemetry:
     * off"). The summary's one free-text field — the price-fetch
     * error — is instead sanitised structurally inside
     * DiagShare (internal.26, F-09): only the error's kind is
     * ever rendered, so no address-bearing message can reach
     * the summary whether masking is on or off.
     */
    private void doShareLog(String excerpt, boolean truncated,
                            DiagShare.Facts facts, boolean maskOn,
                            List<String> ownIps, List<String> pathPrefixes) {
        String body = excerpt;
        if (truncated) {
            body = "[... earlier log omitted for sharing; "
                    + "showing the last part ...]\n"
                    + body;
        }
        if (maskOn) {
            body = DiagShare.maskSensitive(body, shareMaskNames(),
                    ownIps, pathPrefixes);
        }
        String full = DiagShare.buildSummary(facts, excerpt, truncated)
                + "\n" + body;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT,
                I18n.get(this, StringsTable.SHARE_LOG_SUBJECT));
        send.putExtra(Intent.EXTRA_TEXT, full);
        startActivity(Intent.createChooser(send,
                I18n.get(this, StringsTable.BUTTON_SHARE_LOG)));
    }

    /**
     * Every display name the shared log can carry: the running
     * session's launched name — the one this session's lines
     * actually bear, which can differ from the setting after an
     * edit — and the current setting's name, which is what a
     * stopped log's lines bear and what the next start will use.
     * The built-in default is omitted: it identifies the app, not
     * the person, and appears throughout the log as ordinary text.
     */
    private List<String> shareMaskNames() {
        List<String> names = new ArrayList<>();
        String launched = NodeState.get().launchedNodeName;
        if (launched != null && !launched.isEmpty()
                && !NodeSettings.DEFAULT_NODE_NAME.equals(launched)) {
            names.add(launched);
        }
        String current = NodeSettings.effectiveNodeName(this);
        if (current != null && !current.isEmpty()
                && !NodeSettings.DEFAULT_NODE_NAME.equals(current)
                && !names.contains(current)) {
            names.add(current);
        }
        return names;
    }

    /**
     * The diagnostic summary's inputs (versionCode 28): package
     * facts, the service's state snapshot, device resources and
     * the durable records. A source that fails leaves its sentinel
     * in the Facts and the summary prints "n/a" for it, so nothing
     * here throws.
     */
    private DiagShare.Facts gatherDiagFacts() {
        DiagShare.Facts f = new DiagShare.Facts();
        try {
            PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            f.appVersionName = pi.versionName;
            f.appVersionCode = (int) pi.getLongVersionCode();
            f.installMs = pi.firstInstallTime;
            f.updateMs = pi.lastUpdateTime;
        } catch (Exception ignored) {
            // The sentinels stand.
        }
        f.androidRelease = Build.VERSION.RELEASE;
        f.androidApi = Build.VERSION.SDK_INT;
        f.deviceModel = Build.MODEL;
        f.deviceMaker = Build.MANUFACTURER;
        f.chainName = NodeService.CHAIN_NAME;
        // nodeBinaryVersion and genesisShort stay unset on purpose:
        // the app cannot know either (see DiagShare), and the
        // summary says "n/a" instead of guessing.
        NodeState st = NodeState.get();
        f.status = st.status;
        if (st.serviceRunning && st.sessionAcceptedElapsedMs >= 0) {
            f.sessionUptimeMs = SystemClock.elapsedRealtime()
                    - st.sessionAcceptedElapsedMs;
        }
        f.bestBlock = st.bestBlock;
        f.highestBlock = st.highestBlock;
        f.peers = st.peers;
        f.lastRpcOkWallMs = st.lastRpcOkWallMs;
        DiagShare.fillPreviousEnd(f, st.lastStopKind, st.lastExitCode,
                st.prevStopKind, st.prevExitCode);
        // The telemetry line reports what the node is actually
        // running with when there is a launched value; the saved
        // setting stands in only when there is none, and the
        // summary marks it as a setting so the two are never
        // confused (a settings edit mid-session must not rewrite
        // what the running node was launched with).
        if (st.serviceRunning && st.launchedTelemetry != null) {
            f.telemetryOn = st.launchedTelemetry;
            f.telemetryIsSetting = false;
        } else {
            f.telemetryOn = NodeSettings.telemetryEnabled(this);
            f.telemetryIsSetting = true;
        }
        // The QTC price's fetch record (internal.23): when the
        // last fetch succeeded, and — while failures persist —
        // what the last one failed with. Both live in
        // PriceStore's persisted state, not in this process's
        // memory, so they survive the restarts that used to
        // erase every trace of a stopped price.
        PriceStore.Snapshot priceSnap = PriceStore.snapshot(this);
        f.priceFetchedMs = priceSnap.fetchedWallMs;
        f.priceError = PriceStore.lastErrorText(this);
        f.priceErrorMs = PriceStore.lastErrorWallMs(this);
        f.nodeDataBytes = dirSizeBytes(new File(getFilesDir(), "node"));
        try {
            f.freeStorageBytes = getFilesDir().getUsableSpace();
        } catch (Throwable ignored) {
            // Sentinel stands.
        }
        Intent battery = registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) {
                f.batteryPercent = (int) (100L * level / scale);
            }
            if (battery.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) {
                f.batteryTempTenths = battery.getIntExtra(
                        BatteryManager.EXTRA_TEMPERATURE, 0);
            }
        }
        try {
            ActivityManager am = (ActivityManager)
                    getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi =
                        new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                f.freeMemBytes = mi.availMem;
            }
        } catch (Throwable ignored) {
            // Sentinel stands.
        }
        try {
            PowerManager pm = (PowerManager)
                    getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                f.batteryOptExempt =
                        pm.isIgnoringBatteryOptimizations(getPackageName());
            }
        } catch (Throwable ignored) {
            // Sentinel stands.
        }
        NotificationManager nm = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            f.notificationsGranted = nm.areNotificationsEnabled();
        }
        long now = System.currentTimeMillis();
        f.startsLast24h = TrackStore.startsSince(
                this, now - 24L * 3600 * 1000);
        f.startsCapped = TrackStore.startRingAtCap(this);
        f.trackUptimeMs = TrackStore.uptimeMs(this);
        f.trackBlocks = TrackStore.blockUpdates(this);
        f.trackMaxPeers = TrackStore.maxPeers(this);
        f.telemetryChangedMs = NodeSettings.telemetryChangedMs(this);
        f.nameChangedMs = NodeSettings.nodeNameChangedMs(this);
        f.languageChangedMs = NodeSettings.languageChangedMs(this);
        return f;
    }

    /**
     * Recursive size of a directory tree, -1 when it cannot be
     * read. The walk is capped: the node database is a few
     * thousand files, and past the cap a partial total would be a
     * wrong answer rather than a smaller one — the sentinel is
     * the honest result there. The same goes for a directory
     * whose listing fails mid-walk: its subtree's size is
     * unknowable, so the whole measurement is "not known" (the
     * summary prints n/a), never a partial total dressed as a
     * measurement.
     */
    private static long dirSizeBytes(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return -1;
        }
        long total = 0;
        ArrayDeque<File> stack = new ArrayDeque<>();
        stack.push(dir);
        int seen = 0;
        while (!stack.isEmpty()) {
            File cur = stack.pop();
            File[] kids = cur.listFiles();
            if (kids == null) {
                return -1;
            }
            for (File k : kids) {
                if (++seen > 200_000) {
                    return -1;
                }
                if (k.isDirectory()) {
                    stack.push(k);
                } else {
                    total += k.length();
                }
            }
        }
        return total;
    }

    /**
     * This device's own non-loopback IP addresses (masking
     * input). When the enumeration itself fails — outright or
     * partway — {@code failed[0]} is set and the caller locks
     * masking on for that share: the list is then known to be
     * incomplete, and proceeding as if it were whole would
     * silently share addresses the app meant to hide. What was
     * collected before the failure is still returned and still
     * masked.
     */
    private static List<String> ownIpAddresses(boolean[] failed) {
        List<String> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifs =
                    NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                Enumeration<InetAddress> addrs =
                        ifs.nextElement().getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress()) {
                        String host = a.getHostAddress();
                        if (host != null && !host.isEmpty()) {
                            // Strip an IPv6 zone id ("%wlan0"): the
                            // log lines carry the bare address.
                            int zone = host.indexOf('%');
                            out.add(zone >= 0
                                    ? host.substring(0, zone) : host);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            failed[0] = true;
        }
        return out;
    }

    /** The app-private path prefixes the log can carry (masking input). */
    private List<String> appPathPrefixes() {
        List<String> out = new ArrayList<>();
        try {
            out.add(getFilesDir().getAbsolutePath());
            out.add(getCacheDir().getAbsolutePath());
            out.add(getApplicationInfo().dataDir);
            out.add(getApplicationInfo().nativeLibraryDir);
            out.add("/data/data/" + getPackageName());
        } catch (Throwable ignored) {
            // Fewer prefixes: masking still covers what it has.
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Display-name dialog (spec C)
    // ------------------------------------------------------------------

    private void showNameDialog() {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), dp(8));

        TextView hint = text(I18n.get(
                this, StringsTable.SETTINGS_NAME_EMPTY_HINT), 14, LABEL3, false);
        box.addView(hint, matchLp());

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHorizontallyScrolling(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint(NodeSettings.DEFAULT_NODE_NAME);
        input.setTextSize(16);
        input.setTextColor(LABEL1);
        input.setHintTextColor(LABEL3);
        input.setBackground(rounded(SURFACE, 12, density, FIELD_BORDER, 1));
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        input.setMinHeight(dp(56));
        String raw = NodeSettings.nodeNameRaw(this);
        input.setText(raw == null ? "" : raw);
        input.setSelection(input.getText().length());
        box.addView(input, matchLpTop(12));

        final TextView counter = text("", 14, LABEL3, false);
        box.addView(counter, matchLpTop(8));
        // display_name_limit is a quantity string: resolve its
        // wording for the limit value itself.
        final TextView error = text(UiLogic.quantityText(I18n.effectiveTag(this),
                StringsTable.DISPLAY_NAME_LIMIT,
                NodeSettings.nameLimitCodePoints()),
                14, STATE_FAILED, false);
        error.setVisibility(View.GONE);
        box.addView(error, matchLpTop(4));
        final TextView preview = text("", 14, LABEL3, false);
        preview.setVisibility(View.GONE);
        box.addView(preview, matchLpTop(4));

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(I18n.get(this, StringsTable.SETTINGS_DISPLAY_NAME))
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                        I18n.get(this, StringsTable.BUTTON_SAVE), null);
        nameDialog = b.create();
        nameDialog.setOnShowListener(dlg -> {
            Button save = nameDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Runnable update = () -> updateNameDialogState(
                    input, counter, error, preview, save);
            input.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(
                        CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(
                        CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    update.run();
                }
            });
            update.run();
            save.setOnClickListener(v -> {
                // Re-validate at the last moment (spec C): the lock
                // may have become effective, and the final text is
                // what gets validated — never a truncated form.
                if (NodeSettings.settingsLocked(this)) {
                    input.setEnabled(false);
                    save.setEnabled(false);
                    return;
                }
                String value = input.getText().toString();
                if (!UiLogic.nameSaveable(value)) {
                    update.run();
                    return; // stay open with the error showing
                }
                // versionCode 27 (external audit of v0.2.0): store
                // the NORMALIZED name, not the typed text — the one
                // normalization the preview showed and the launch
                // will use, so what was previewed is byte-for-byte
                // what gets saved and passed to the node. (Before,
                // the raw text was stored and the launch re-derived
                // the name with a differently ordered sanitizer,
                // which could hand the node a shorter name than the
                // one the user had confirmed.)
                String normalized = NodeSettings.normalizeNodeName(value);
                String current = NodeSettings.nodeNameRaw(this);
                if (!normalized.equals(current == null ? "" : current)) {
                    NodeSettings.setNodeNameRaw(this, normalized);
                }
                nameDialog.dismiss();
                refresh();
            });
        });
        nameDialog.show();
        styleDialog(nameDialog);
    }

    /** Counter / error / preview under the name field, from the text. */
    private void updateNameDialogState(EditText input, TextView counter,
                                       TextView error, TextView preview,
                                       Button save) {
        String value = input.getText().toString();
        String tag = I18n.effectiveTag(this);
        int count = UiLogic.nameCodePoints(value);
        int limit = NodeSettings.nameLimitCodePoints();
        counter.setText(I18n.format(this, StringsTable.CHARACTER_COUNTER_FORMAT,
                UiLogic.grouped(count), UiLogic.grouped(limit)));
        counter.setContentDescription(String.format(Locale.US,
                StringsTable.quantity(tag, StringsTable.DISPLAY_NAME_COUNT_A11Y, count),
                UiLogic.grouped(count), UiLogic.grouped(limit)));
        boolean saveable = count <= limit;
        error.setVisibility(saveable ? View.GONE : View.VISIBLE);
        save.setEnabled(saveable);
        String saved = UiLogic.nameSavedPreview(value);
        if (!saved.equals(value) && saveable) {
            preview.setText(I18n.format(
                    this, StringsTable.SETTINGS_NAME_SAVED_PREVIEW, saved));
            preview.setVisibility(View.VISIBLE);
        } else {
            preview.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------------
    // Language dialog (spec B: immediate apply, no save button)
    // ------------------------------------------------------------------

    /** Picker label for the currently stored language choice. */
    private String currentLanguageLabel() {
        String raw = NodeSettings.languageRaw(this);
        if (raw == null || raw.isEmpty() || !I18n.isSupported(raw)) {
            return I18n.get(this, StringsTable.LANGUAGE_FOLLOW_SYSTEM);
        }
        String name = StringsTable.LANGUAGE_NAMES.get(raw);
        return name != null ? name : raw;
    }

    private void showLanguageDialog() {
        String[] tags = StringsTable.TAGS;
        CharSequence[] items = new CharSequence[tags.length + 1];
        items[0] = I18n.get(this, StringsTable.LANGUAGE_FOLLOW_SYSTEM);
        for (int i = 0; i < tags.length; i++) {
            items[i + 1] = StringsTable.LANGUAGE_NAMES.get(tags[i]);
        }
        String raw = NodeSettings.languageRaw(this);
        int checkedIndex = 0;
        for (int i = 0; i < tags.length; i++) {
            if (tags[i].equals(raw)) {
                checkedIndex = i + 1;
                break;
            }
        }
        final int checked = checkedIndex;
        // A custom view carries the picker (device finding,
        // 2026-11-06): setMessage + setSingleChoiceItems together
        // lose the list entirely on GrapheneOS — the dialog showed
        // the title, the message and Cancel, and no languages. So
        // the note and the single-choice list both live in the
        // dialog's own view here: the immediate-apply note on top,
        // the list below it, capped in height so the dialog fits
        // short screens (the list scrolls past the cap). The item
        // semantics are the ones the builder's listener had: a tap
        // saves at once, closes and rebuilds.
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), dp(8));
        TextView note = text(I18n.get(
                this, StringsTable.SETTINGS_LANGUAGE_IMMEDIATE),
                14, LABEL3, false);
        box.addView(note, matchLp());
        ListView list = new ListView(this);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_single_choice, items));
        list.setItemChecked(checked, true);
        list.setDivider(new ColorDrawable(HAIRLINE));
        list.setDividerHeight(Math.max(1, dp(1)));
        LinearLayout.LayoutParams listLp = matchLpTop(8);
        listLp.height = Math.min(dp(56) * items.length, dp(400));
        box.addView(list, listLp);
        list.setOnItemClickListener((parent, view, which, id) -> {
            if (which == checked) {
                languageDialog.dismiss();
                return; // current language: nothing to do
            }
            // Spec G order: keep the UI state, save, close,
            // rebuild, restore on the settings screen with
            // focus back on the language row. For the
            // settings screen the restore anchor is the
            // first visible row's stable id plus the offset
            // inside it (translations change row heights,
            // so the raw scroll offset alone would land
            // elsewhere); other screens keep raw offsets.
            sScreenAfterRecreate = currentScreen;
            sScrollAfterRecreate = new int[screens.length];
            for (int i = 0; i < screens.length; i++) {
                sScrollAfterRecreate[i] =
                        screens[i] instanceof ScrollView
                                ? screens[i].getScrollY() : 0;
            }
            sAnchorIdAfterRecreate = -1;
            sAnchorOffsetAfterRecreate = 0;
            ScrollView ssv = (ScrollView) screens[SCREEN_SETTINGS];
            if (ssv != null && ssv.getChildAt(0) != null) {
                int sy = ssv.getScrollY();
                View content = ssv.getChildAt(0);
                for (View anchor : settingsAnchors) {
                    int top = offsetInContent(anchor, content);
                    if (top <= sy) {
                        sAnchorIdAfterRecreate = anchor.getId();
                        sAnchorOffsetAfterRecreate = sy - top;
                    } else {
                        break;
                    }
                }
            }
            sDiagAfterRecreate = diagExpanded;
            sFocusLanguageAfterRecreate = true;
            NodeSettings.setLanguageRaw(
                    this, which == 0 ? "" : tags[which - 1]);
            languageDialog.dismiss();
            recreate();
        });
        languageDialog = new AlertDialog.Builder(this)
                .setTitle(I18n.get(this, StringsTable.SETTINGS_LANGUAGE))
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        styleDialog(languageDialog);
    }

    // ------------------------------------------------------------------
    // Refresh (1 s tick while visible; NodeState + TrackStore only)
    // ------------------------------------------------------------------

    private void refresh() {
        NodeState st = NodeState.get();
        String tag = I18n.effectiveTag(this);
        UiLogic.HomeState hs = UiLogic.homeState(st.status, st.lastStopKind);
        if (!st.serviceRunning) {
            stopping = false;
        }
        long now = SystemClock.elapsedRealtime();

        // One field event per newly recorded observation while
        // SYNCED, measured against the display-start baseline
        // (finding L-1): the baseline is adopted as a VALUE when
        // the UI (re)starts displaying, so the first increase
        // after a cold start fires, a rebuild never manufactures
        // one, and the event reaches the network field only
        // while its tab is the visible one.
        if (st.lastObservedBestBlock >= 0) {
            if (UiLogic.shouldRipple(sRippleBaselineReady, firstRefresh,
                    hs == UiLogic.HomeState.SYNCED,
                    currentScreen == SCREEN_NETWORK,
                    st.lastObservedBestBlock, sLastRippleObservedBest)) {
                networkField.onBlockArrived();
            }
            if (st.lastObservedBestBlock > sLastRippleObservedBest) {
                sLastRippleObservedBest = st.lastObservedBestBlock;
            }
        }
        PriceStore.maybeFetch(this);
        refreshVisionHome(st, tag, hs, now);
        refreshNetwork(st, hs);
        refreshMember();
        refreshDetails(st, tag, hs, now);
        trackUptimeValue.setText(UiLogic.durationText(
                tag, TrackStore.uptimeMs(this), true));
        trackBlocksValue.setText(
                UiLogic.grouped(TrackStore.blockUpdates(this)));
        trackPeersValue.setText(
                UiLogic.grouped(TrackStore.maxPeers(this)));
        refreshSettings(st);
        refreshTransferState();
        firstRefresh = false;
    }

    private void refreshVisionHome(NodeState st, String tag,
                                   UiLogic.HomeState hs, long now) {
        // State dot + word. The word uses the vision strings for
        // the two calm states (synced / stopped) and the existing
        // status strings for the transitional and failed ones.
        int stateColor;
        String statusKey;
        switch (hs) {
            case STARTING:
                stateColor = STATE_STARTING;
                statusKey = StringsTable.STATUS_STARTING;
                break;
            case SYNCING:
                stateColor = STATE_SYNCING;
                statusKey = StringsTable.STATUS_SYNCING;
                break;
            case SYNCED:
                stateColor = STATE_SYNCED;
                statusKey = StringsTable.VISION_SYNCED;
                break;
            case FAILED_START:
            case FAILED_BINARY:
                stateColor = STATE_FAILED;
                statusKey = StringsTable.STATUS_START_FAILED;
                break;
            case FAILED_UNEXPECTED:
                stateColor = STATE_FAILED;
                statusKey = StringsTable.STATUS_UNEXPECTED_STOP;
                break;
            case STOPPED:
            case STOPPED_TIME_LIMIT:
            default:
                stateColor = STATE_STOPPED;
                statusKey = StringsTable.VISION_STOPPED;
                break;
        }
        if (stateColor != stateDotColor) {
            stateDotColor = stateColor;
            float density = getResources().getDisplayMetrics().density;
            stateDotView.setBackground(
                    rounded(stateColor, 999, density, 0, 0));
        }
        statusView.setTextColor(stateColor == STATE_SYNCED
                ? STATE_SYNCED : stateColor == STATE_STOPPED
                        ? LABEL2 : LABEL1);
        // Syncing shows its numbers in the hero itself (stage
        // 1 spec S1-06 / S1-10): the percent and the blocks to
        // go, computed here from the two heights. While either
        // height is unknown the plain word stands, and the
        // supplement below keeps its gap / unknown-target line
        // (heroSync suppresses the duplicate).
        String statusText = I18n.get(this, statusKey);
        boolean heroSync = false;
        if (hs == UiLogic.HomeState.SYNCING) {
            long gap = UiLogic.syncGap(st.bestBlock, st.highestBlock);
            if (gap >= 0 && st.highestBlock > 0) {
                long pct = st.bestBlock * 100L / st.highestBlock;
                statusText = I18n.format(this,
                        StringsTable.STATE_SYNCING_FMT,
                        UiLogic.grouped(pct), UiLogic.grouped(gap));
                heroSync = true;
            }
        }
        setTextIfChanged(statusView, statusText);

        // The big best number: "#" in faint ahead of the grouped
        // height (mock v5). Unknown height reads as a dash.
        String bestText = st.bestBlock >= 0
                ? "#" + UiLogic.grouped(st.bestBlock) : "\u2014";
        if (!bestText.equals(vhBestValue.getText().toString())) {
            android.text.SpannableString span =
                    new android.text.SpannableString(bestText);
            if (bestText.startsWith("#")) {
                span.setSpan(new android.text.style.ForegroundColorSpan(
                                LABEL3), 0, 1,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            vhBestValue.setText(span);
        }
        // Cap the display size at 16% of the screen width (spec
        // section 2), 64 sp at most.
        float widthDp = getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density;
        vhBestValue.setTextSize(Math.min(64f, widthDp * 0.16f));

        vhPeersValue.setText(st.peers >= 0
                ? UiLogic.grouped(st.peers)
                : I18n.get(this, StringsTable.VALUE_UNAVAILABLE));
        vhUptimeValue.setText(st.sessionAcceptedElapsedMs >= 0
                ? sessionClockText(now - st.sessionAcceptedElapsedMs)
                : I18n.get(this, StringsTable.VALUE_UNAVAILABLE));
        setTextIfChanged(vhBestAgoView, st.lastObservedElapsedMs >= 0
                ? agoText(now - st.lastObservedElapsedMs)
                : "\u2014");

        // Supplement + cause-specific detail link (unchanged
        // rules from the previous home: facts only, from the
        // service's own records).
        String supplement = null;
        String linkKey = null;
        switch (hs) {
            case STOPPED_TIME_LIMIT:
                supplement = I18n.get(this, StringsTable.MESSAGE_TIME_LIMIT_STOP);
                linkKey = StringsTable.ACTION_STOP_DETAILS;
                break;
            case FAILED_START:
                supplement = I18n.get(this, StringsTable.MESSAGE_START_FAILED);
                linkKey = StringsTable.ACTION_FAILURE_DETAILS;
                break;
            case FAILED_UNEXPECTED:
                supplement = I18n.get(this, StringsTable.MESSAGE_UNEXPECTED_STOP);
                linkKey = StringsTable.ACTION_FAILURE_DETAILS;
                break;
            case FAILED_BINARY:
                supplement = I18n.get(this, StringsTable.MESSAGE_BINARY_MISSING);
                linkKey = StringsTable.ACTION_FAILURE_DETAILS;
                break;
            case SYNCING: {
                if (!heroSync) {
                    String gap = UiLogic.syncGapText(tag,
                            st.bestBlock, st.highestBlock);
                    supplement = gap != null ? gap
                            : I18n.get(this,
                                    StringsTable.MESSAGE_SYNC_TARGET_UNKNOWN);
                }
                break;
            }
            case SYNCED:
                if (st.peers == 0) {
                    supplement = I18n.get(this, StringsTable.MESSAGE_NO_PEERS);
                } else if (st.lastObservedElapsedMs >= 0) {
                    supplement = UiLogic.lastSeenText(
                            tag, now - st.lastObservedElapsedMs);
                } else {
                    supplement = I18n.get(this, StringsTable.MESSAGE_WAITING_BLOCK);
                }
                break;
            default:
                break;
        }
        supplementView.setVisibility(supplement != null ? View.VISIBLE : View.GONE);
        if (supplement != null) {
            setTextIfChanged(supplementView, supplement);
        }
        detailLinkView.setVisibility(linkKey != null ? View.VISIBLE : View.GONE);
        if (linkKey != null) {
            setTextIfChanged(detailLinkView, I18n.get(this, linkKey));
        }

        // The primary button: the vision wording (start / stop
        // from the spec's dictionary) over the same state machine
        // as before, stopping flag included. The three dresses
        // are built once and cached (finding L-5).
        if (stopping && st.serviceRunning) {
            mainButton.setText(I18n.get(this, StringsTable.BUTTON_STOPPING));
            mainButton.setEnabled(false);
            mainButton.setBackground(stoppingButtonBg());
            mainButton.setTextColor(LABEL3);
            mainButton.setTypeface(VisionFonts.displayMedium(this));
        } else {
            mainButton.setEnabled(true);
            switch (hs) {
                case STOPPED:
                case STOPPED_TIME_LIMIT:
                    mainButton.setText(
                            I18n.get(this, StringsTable.VISION_START));
                    mainButton.setBackground(accentButtonBg());
                    mainButton.setTextColor(ON_ACCENT);
                    mainButton.setTypeface(VisionFonts.displayBold(this));
                    break;
                case FAILED_START:
                case FAILED_UNEXPECTED:
                case FAILED_BINARY:
                    mainButton.setText(I18n.get(this, StringsTable.BUTTON_RETRY));
                    mainButton.setBackground(accentButtonBg());
                    mainButton.setTextColor(ON_ACCENT);
                    mainButton.setTypeface(VisionFonts.displayBold(this));
                    break;
                case STARTING:
                    mainButton.setText(
                            I18n.get(this, StringsTable.BUTTON_CANCEL_STARTUP));
                    mainButton.setBackground(surfaceButtonBg());
                    mainButton.setTextColor(LABEL1);
                    mainButton.setTypeface(VisionFonts.displayMedium(this));
                    break;
                case SYNCING:
                case SYNCED:
                default:
                    mainButton.setText(
                            I18n.get(this, StringsTable.VISION_STOP));
                    mainButton.setBackground(surfaceButtonBg());
                    mainButton.setTextColor(LABEL1);
                    mainButton.setTypeface(VisionFonts.displayMedium(this));
                    break;
            }
        }

        // Pending-settings link (condition P), as before: shown
        // with the running presentations only.
        int pending = UiLogic.settingsPending(st.serviceRunning,
                st.launchedTelemetry, NodeSettings.telemetryEnabled(this),
                st.launchedNodeName, NodeSettings.effectiveNodeName(this));
        boolean showPending = pending != 0 && UiLogic.showsStats(hs);
        pendingLinkView.setVisibility(showPending ? View.VISIBLE : View.GONE);
        if (showPending) {
            setTextIfChanged(pendingLinkView,
                    I18n.get(this, StringsTable.SETTINGS_PENDING_LINK));
        }

        refreshPriceCard();
        refreshTodayRow();
    }

    /**
     * A relative age in the ago_* wording (stage 1 spec
     * S1-10): whole seconds, then whole minutes, then whole
     * hours. The number is handed over pre-formatted, so the
     * strings need no plural machinery.
     */
    private String agoText(long ageMs) {
        long sec = Math.max(0, ageMs / 1000);
        if (sec < 60) {
            return I18n.format(this, StringsTable.AGO_SEC,
                    UiLogic.grouped(sec));
        }
        if (sec < 3600) {
            return I18n.format(this, StringsTable.AGO_MIN,
                    UiLogic.grouped(sec / 60));
        }
        return I18n.format(this, StringsTable.AGO_HOUR,
                UiLogic.grouped(sec / 3600));
    }

    /**
     * The today's-record row (stage 1 spec S1-10): visible
     * only while DePIN is enabled and its aggregates are
     * known — a device that is not recording is never shown
     * a 0% bar for a record it does not keep. The bar is the
     * share of today's elapsed checkpoint slots that hold a
     * record. While recording is HELD the bar freezes where
     * it was and the line says so.
     */
    private void refreshTodayRow() {
        DepinStatus ds = DepinModule.get(this).latestStatus();
        if (ds == null || !ds.enabled || ds.todayCheckpoints < 0
                || ds.todaySlotsElapsed < 0) {
            homeTodayBox.setVisibility(View.GONE);
            return;
        }
        homeTodayBox.setVisibility(View.VISIBLE);
        if (ds.state == DepinStatus.STATE_HELD || ds.holdActive) {
            setTextIfChanged(homeTodayText,
                    I18n.get(this, StringsTable.HOME_HELD_TODAY));
            return;
        }
        setTextIfChanged(homeTodayText, I18n.format(this,
                StringsTable.HOME_RECORDED_FMT,
                UiLogic.grouped(ds.todayCheckpoints),
                UiLogic.grouped(ds.todaySlotsElapsed)));
        final float frac = ds.todaySlotsElapsed > 0
                ? Math.min(1f, (float) ds.todayCheckpoints
                        / (float) ds.todaySlotsElapsed)
                : 0f;
        homeTodayTrack.post(() -> {
            int w = homeTodayTrack.getWidth();
            if (w > 0) {
                FrameLayout.LayoutParams lp =
                        (FrameLayout.LayoutParams)
                                homeTodayFill.getLayoutParams();
                int want = Math.round(w * frac);
                if (lp.width != want) {
                    lp.width = want;
                    homeTodayFill.setLayoutParams(lp);
                }
            }
        });
    }

    /** Session uptime as a mono clock (HH:MM:SS, hours unbounded). */
    private static String sessionClockText(long elapsedMs) {
        long s = Math.max(0, elapsedMs / 1000);
        return String.format(Locale.US, "%02d:%02d:%02d",
                s / 3600, (s % 3600) / 60, s % 60);
    }

    /**
     * The price card from {@link PriceStore}'s snapshot: no value
     * yet is a dash and nothing else (design 2.5); a stale value
     * dims and its source line says so. Stage 1 spec S1-07 /
     * S1-08: a fetch that carried no 24 h change shows "24h —"
     * in the same slot at the same width (never a computed-
     * looking 0.0%), tapping it explains why, and the source
     * tag's time is CoinGecko's own update time, formatted
     * HH:mm for today and M/d HH:mm for older values. The
     * change's ▲▼ stays, in the neutral label colour — never
     * green or red.
     */
    private void refreshPriceCard() {
        PriceStore.Snapshot snap = PriceStore.snapshot(this);
        if (!snap.hasValue) {
            setTextIfChanged(priceValueView, "\u2014");
            priceValueView.setTextColor(LABEL1);
            setTextIfChanged(priceDeltaView, "");
            setTextIfChanged(priceSrcView, "");
            return;
        }
        setTextIfChanged(priceValueView, priceText(snap.usd));
        priceValueView.setTextColor(snap.stale ? LABEL2 : LABEL1);
        priceDeltaView.setTextColor(LABEL2);
        if (snap.hasChange) {
            double ch = snap.change24h;
            String delta = String.format(Locale.US, "%s %.1f%% 24H",
                    ch >= 0 ? "\u25B2" : "\u25BC", Math.abs(ch));
            setTextIfChanged(priceDeltaView, delta);
        } else {
            setTextIfChanged(priceDeltaView, "24h \u2014");
        }
        long srcMs = snap.updatedUnix > 0
                ? snap.updatedUnix * 1000L : snap.fetchedWallMs;
        String time = priceTimeText(srcMs);
        setTextIfChanged(priceSrcView, I18n.format(this,
                snap.stale ? StringsTable.VISION_PRICE_STALE
                        : StringsTable.VISION_SRC, time));
    }

    /** The source tag's time (spec S1-08): HH:mm when the
     *  value is from today (local), M/d HH:mm otherwise. */
    private static String priceTimeText(long wallMs) {
        java.util.Calendar when = java.util.Calendar.getInstance();
        when.setTimeInMillis(wallMs);
        java.util.Calendar now = java.util.Calendar.getInstance();
        boolean today = when.get(java.util.Calendar.YEAR)
                        == now.get(java.util.Calendar.YEAR)
                && when.get(java.util.Calendar.DAY_OF_YEAR)
                        == now.get(java.util.Calendar.DAY_OF_YEAR);
        return new SimpleDateFormat(
                today ? "HH:mm" : "M/d HH:mm", Locale.US)
                .format(new Date(wallMs));
    }

    /** USD price text: 2 decimals at $1 and above, 4 below (the
     *  mock's precision for QTC's current price band; spec 2.6). */
    private static String priceText(double usd) {
        return usd >= 1.0
                ? String.format(Locale.US, "$%.2f", usd)
                : String.format(Locale.US, "$%.4f", usd);
    }

    /** HH:mm of a wall-clock time, for ticker captions. */
    private static String timeHhMm(long wallMs) {
        return new SimpleDateFormat("HH:mm", Locale.US)
                .format(new Date(wallMs));
    }

    /** HH:mm:ss of a wall-clock time, for source tags. */
    private static String timeHhMmSs(long wallMs) {
        return new SimpleDateFormat("HH:mm:ss", Locale.US)
                .format(new Date(wallMs));
    }

    /** Whether the device reports an internet-capable network
     *  right now (the offline state of spec S1-06). */
    private boolean deviceOnline() {
        ConnectivityManager cm = (ConnectivityManager)
                getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return true;
        }
        Network n = cm.getActiveNetwork();
        if (n == null) {
            return false;
        }
        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
        return caps != null && caps.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void refreshNetwork(NodeState st, UiLogic.HomeState hs) {
        boolean running = UiLogic.showsStats(hs);
        boolean failed = UiLogic.isFailure(hs);
        boolean synced = hs == UiLogic.HomeState.SYNCED;
        String tag = I18n.effectiveTag(this);
        long nowWall = System.currentTimeMillis();
        long nowEl = SystemClock.elapsedRealtime();

        // Advance tracking for the stalled state: the wall
        // time this UI last saw the best block grow. The first
        // sighting only sets the baseline (a fresh start is
        // not a stall), and a stop clears it, so a restarted
        // node is never judged against the old session's best.
        if (!running) {
            netAdvanceBest = -1;
            netAdvanceWallMs = -1;
        }
        if (st.bestBlock >= 0) {
            if (netAdvanceBest < 0 || st.bestBlock > netAdvanceBest) {
                netAdvanceBest = st.bestBlock;
                netAdvanceWallMs = nowWall;
            }
        }
        long rpcAgeSec = st.lastRpcOkWallMs > 0
                ? (nowWall - st.lastRpcOkWallMs) / 1000 : -1;
        long advanceAgeSec = netAdvanceWallMs > 0
                ? (nowWall - netAdvanceWallMs) / 1000 : -1;
        UiLogic.NetState ns = UiLogic.classifyNetState(
                deviceOnline(), running, failed,
                hs == UiLogic.HomeState.SYNCING, st.peers,
                rpcAgeSec, advanceAgeSec);

        float widthDp = getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density;
        netNumView.setTextSize(Math.min(96f, widthDp * 0.26f));
        networkField.setNodeState(running, synced);
        networkField.setPeers(running ? st.peers : -1);
        networkField.setBestBlock(running ? st.bestBlock : -1);

        String peersText = st.peers >= 0
                ? UiLogic.grouped(st.peers) : "\u2014";
        String sub;
        boolean tagVisible = running && st.lastRpcOkWallMs > 0;
        switch (ns) {
            case OFFLINE:
                setTextIfChanged(netNumView, "\u2014");
                netNumView.setTextColor(LABEL3);
                sub = I18n.get(this, StringsTable.STATE_OFFLINE);
                tagVisible = false;
                break;
            case STOPPED:
                setTextIfChanged(netNumView, "\u2014");
                netNumView.setTextColor(LABEL3);
                sub = I18n.get(this, StringsTable.VISION_NET_OFF);
                tagVisible = false;
                break;
            case RPC_FAIL:
                // The last measured value, dimmed (spec S1-06):
                // it is shown AS a stale value, never as a live
                // one. The state line carries the last success.
                setTextIfChanged(netNumView, peersText);
                netNumView.setTextColor(LABEL3);
                sub = I18n.format(this, StringsTable.STATE_RPC_FAIL,
                        timeHhMmSs(st.lastRpcOkWallMs));
                tagVisible = false;
                break;
            case SYNCING: {
                setTextIfChanged(netNumView, peersText);
                netNumView.setTextColor(ACCENT);
                long gap = UiLogic.syncGap(st.bestBlock,
                        st.highestBlock);
                if (gap >= 0 && st.highestBlock > 0) {
                    long pct = st.bestBlock * 100L / st.highestBlock;
                    sub = I18n.format(this,
                            StringsTable.STATE_SYNCING_FMT,
                            UiLogic.grouped(pct),
                            UiLogic.grouped(gap));
                } else {
                    sub = NodeState.label(this, st.status);
                }
                break;
            }
            case SEARCHING: {
                // A measured zero reads "0"; not-yet-measured
                // reads "—". The line reports how long the node
                // has been observing zero (or nothing yet).
                setTextIfChanged(netNumView,
                        st.peers == 0 ? "0" : "\u2014");
                netNumView.setTextColor(ACCENT);
                long elapsedMs = st.sessionAcceptedElapsedMs >= 0
                        ? nowEl - st.sessionAcceptedElapsedMs : 0;
                sub = I18n.format(this, StringsTable.STATE_SEARCHING,
                        UiLogic.durationText(tag, elapsedMs, false));
                break;
            }
            case STALLED:
                setTextIfChanged(netNumView, peersText);
                netNumView.setTextColor(ACCENT);
                sub = I18n.format(this, StringsTable.STATE_STALLED,
                        UiLogic.durationText(tag,
                                Math.max(0, advanceAgeSec) * 1000L,
                                false));
                break;
            case NORMAL:
            default:
                setTextIfChanged(netNumView, peersText);
                netNumView.setTextColor(ACCENT);
                sub = I18n.get(this, StringsTable.NET_LABEL_PEERS);
                break;
        }
        setTextIfChanged(netSubView, sub);
        netTagView.setVisibility(tagVisible ? View.VISIBLE : View.GONE);
        if (tagVisible) {
            setTextIfChanged(netTagView, I18n.format(this,
                    StringsTable.TAG_NODE,
                    timeHhMmSs(st.lastRpcOkWallMs)));
        }

        // Journal-derived field data (stage 2): the record
        // ring / sparkline slot window and the ghost layer.
        // Display data of this device's own journal only.
        DepinStatus ds = DepinModule.get(this).latestStatus();
        if (ds != null) {
            networkField.setRecordRing(ds.recentSlotPeers);
            networkField.setGhostData(ds.recentDayTs,
                    ds.recentDayPeers, netShow24h);
            sparkView.setSlots(ds.recentSlotPeers);
        }

        // The aggregate TalkBack readout (S2-04): one text for
        // the whole field, in the same facts as the hero.
        if (running) {
            networkField.setA11yText(I18n.format(this,
                    StringsTable.FIELD_A11Y, peersText,
                    st.bestBlock >= 0
                            ? UiLogic.grouped(st.bestBlock) : "\u2014",
                    st.lastRpcOkWallMs > 0
                            ? timeHhMmSs(st.lastRpcOkWallMs)
                            : "\u2014"));
        } else {
            networkField.setA11yText(
                    I18n.get(this, StringsTable.VISION_NET_OFF));
        }

        deriveTicker(st, ds, running, nowWall, nowEl);
    }

    /**
     * Ticker derivation (stage 2 spec S2-03): events are the
     * DIFFERENCES between successive snapshots this UI has
     * seen, measured against display baselines adopted at
     * display start (the L-1 discipline), so a rebuild or a
     * return to the tab can never replay history. Captions
     * use observation language only — a block was observed, a
     * peer count was seen, a checkpoint was recorded. Nothing
     * here can say where a block came from or went: the node
     * does not know, so the log does not claim it. Times are
     * the observations' own times where the service recorded
     * them (the block's observation instant, the checkpoint's
     * own timestamp), else the snapshot time — the log's
     * granularity is the refresh cadence and it says nothing
     * finer.
     */
    private void deriveTicker(NodeState st, DepinStatus ds,
                              boolean running, long nowWall,
                              long nowEl) {
        if (!tickerInit) {
            tickerInit = true;
            tickerRunning = running;
            tickerBest = st.lastObservedBestBlock;
            tickerPeers = running && st.peers >= 0
                    ? st.peers : Integer.MIN_VALUE;
            tickerCpCount = ds != null ? ds.checkpointCount : -1;
            tickerHeld = ds != null && ds.holdActive;
            return;
        }
        if (running != tickerRunning) {
            addTickerLine(I18n.get(this, running
                    ? StringsTable.TICK_STARTED
                    : StringsTable.TICK_STOPPED)
                    + "  " + timeHhMm(nowWall));
            tickerRunning = running;
            if (!running) {
                tickerPeers = Integer.MIN_VALUE;
            }
        }
        if (running && tickerBest >= 0
                && st.lastObservedBestBlock > tickerBest) {
            long obsWall = st.lastObservedElapsedMs >= 0
                    ? nowWall - (nowEl - st.lastObservedElapsedMs)
                    : nowWall;
            addTickerLine(I18n.format(this, StringsTable.TICK_BLOCK,
                    timeHhMm(obsWall),
                    UiLogic.grouped(st.lastObservedBestBlock)));
        }
        if (st.lastObservedBestBlock > tickerBest) {
            tickerBest = st.lastObservedBestBlock;
        }
        if (running && st.peers >= 0) {
            if (tickerPeers != Integer.MIN_VALUE
                    && st.peers != tickerPeers) {
                addTickerLine(I18n.format(this,
                        StringsTable.TICK_PEERS, timeHhMm(nowWall),
                        UiLogic.grouped(st.peers)));
            }
            tickerPeers = st.peers;
        }
        if (ds != null && ds.checkpointCount >= 0) {
            if (tickerCpCount >= 0
                    && ds.checkpointCount > tickerCpCount) {
                long cpWall = ds.lastCheckpointUnix > 0
                        ? ds.lastCheckpointUnix * 1000L : nowWall;
                addTickerLine(I18n.format(this,
                        StringsTable.TICK_CHECKPOINT,
                        timeHhMm(cpWall),
                        UiLogic.grouped(ds.checkpointCount)));
                networkField.onCheckpointRecorded();
            }
            tickerCpCount = ds.checkpointCount;
            if (ds.holdActive && !tickerHeld) {
                addTickerLine(I18n.format(this,
                        StringsTable.TICK_HELD, timeHhMm(nowWall)));
            }
            tickerHeld = ds.holdActive;
        }
    }

    /** Prepend one ticker line (newest first, cap 50) and
     *  re-render the visible tail. Lines live in memory only. */
    private void addTickerLine(String line) {
        tickerLines.add(0, line);
        while (tickerLines.size() > 50) {
            tickerLines.remove(tickerLines.size() - 1);
        }
        if (tickerBox == null) {
            return;
        }
        tickerBox.removeAllViews();
        int show = Math.min(12, tickerLines.size());
        for (int i = 0; i < show; i++) {
            TextView row = text(tickerLines.get(i), 13,
                    i == 0 ? LABEL1 : LABEL2, false);
            row.setTypeface(VisionFonts.mono(this));
            tickerBox.addView(row, i == 0
                    ? matchLp() : matchLpTop(6));
        }
    }

    /**
     * The member card from the DePIN facade's status: the card
     * exists only once the journal holds real records (design
     * 7.2); before that the tab shows the guidance alone. Every
     * number is an aggregate of this device's own journal.
     */
    private void refreshMember() {
        DepinStatus ds = DepinModule.get(this).latestStatus();
        boolean hasCard = ds != null && ds.deviceId != null
                && ds.checkpointCount > 0;
        memberCardBox.setVisibility(hasCard ? View.VISIBLE : View.GONE);
        memberEmptyView.setVisibility(hasCard ? View.GONE : View.VISIBLE);
        if (!hasCard) {
            return;
        }
        String name = NodeSettings.nodeNameRaw(this);
        memNameView.setVisibility(name != null && !name.isEmpty()
                ? View.VISIBLE : View.GONE);
        if (name != null && !name.isEmpty()) {
            setTextIfChanged(memNameView, name);
        }
        memIdRaw = ds.deviceId;
        setTextIfChanged(memIdView, memIdGrouped
                ? groupMemberId(ds.deviceId) : ds.deviceId);
        fitSingleLinePx(memIdView, 14f, 10f, 7f);
        fitSingleLinePx(memNameView, 40f, 20f, 12f);
        memSinceValue.setText(ds.firstCheckpointUnix > 0
                ? new SimpleDateFormat("yyyy.MM.dd", Locale.US)
                        .format(new Date(ds.firstCheckpointUnix * 1000L))
                : "\u2014");
        memCpsValue.setText(UiLogic.grouped(ds.checkpointCount));
        memDaysValue.setText(ds.longestStreakDays >= 0
                ? I18n.format(this, StringsTable.VISION_DAYS_VAL,
                        UiLogic.grouped(ds.longestStreakDays))
                : "\u2014");
        for (TextView lab : statLabelViews) {
            fitSingleLinePx(lab, 12f, 10f, 8f);
        }
        for (TextView val : statValueViews) {
            fitSingleLinePx(val, 20f, 14f, 9f);
        }
        // Keep the 1.586:1 ratio via the spacer (see
        // buildMember): the target height comes from the
        // card's own width; content taller than the target
        // keeps its full height (the spacer floors at 0).
        memberCardBox.post(() -> {
            int w = memberCardBox.getWidth();
            if (w <= 0 || memberContent == null
                    || memberSpacer == null) {
                return;
            }
            int target = Math.round(w / 1.586f);
            int contentH = memberContent.getHeight();
            int spacerH = memberSpacer.getHeight();
            int want = Math.max(0, target - (contentH - spacerH));
            if (want != spacerH) {
                LinearLayout.LayoutParams lp =
                        (LinearLayout.LayoutParams)
                                memberSpacer.getLayoutParams();
                lp.height = want;
                memberSpacer.setLayoutParams(lp);
            }
        });
    }

    /** The member number for display: hyphens removed, then a
     *  space every four characters (stage 1 spec S1-11). The
     *  raw id stays one tap away on the card itself. */
    private static String groupMemberId(String deviceId) {
        if (deviceId == null) {
            return "";
        }
        String flat = deviceId.replace("-", "");
        StringBuilder sb = new StringBuilder(flat.length() + 8);
        for (int i = 0; i < flat.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                sb.append(' ');
            }
            sb.append(flat.charAt(i));
        }
        return sb.toString();
    }

    /** What a view was last fitted for. Fitting is idempotent
     *  per (text, width), so the per-second refresh can offer
     *  every text every time without the old reset-and-shrink
     *  churn (audit F3-L8). */
    private static final class FitState {
        String text;
        int width;
    }

    private final java.util.Map<TextView, FitState> fitStates =
            new java.util.IdentityHashMap<>();

    /**
     * Fit a single-line view's text into its width — in PIXELS,
     * never in sp, so the device font-size setting cannot scale
     * the fitted size back up and break the one-line promise
     * (audit F3-L8: at font scale 2.0 the old sp-based floor of
     * 10 sp still overflowed the card and the member number's
     * tail was silently clipped). The size shrinks from startDp
     * toward softFloorDp; if the text is still wide, letter
     * spacing tightens to -0.06 em; if still wide, the size
     * keeps shrinking toward hardFloorDp and, in the limit, as
     * far as needed. The text is never wrapped or ellipsised.
     * Sizes are dp-denominated pixel counts, so the result is
     * identical at any font scale.
     */
    private void fitSingleLinePx(final TextView v, float startDp,
                                 float softFloorDp, float hardFloorDp) {
        v.post(() -> {
            int avail = v.getWidth() - v.getPaddingLeft()
                    - v.getPaddingRight();
            if (avail <= 0) {
                return;
            }
            String s = v.getText().toString();
            FitState st = fitStates.get(v);
            if (st != null && st.width == avail
                    && s.equals(st.text)) {
                return;
            }
            float density =
                    getResources().getDisplayMetrics().density;
            float step = Math.max(1f, density * 0.5f);
            float size = startDp * density;
            float soft = softFloorDp * density;
            float hard = hardFloorDp * density;
            android.graphics.Paint p = v.getPaint();
            float ls = 0f;
            v.setLetterSpacing(0f);
            v.setTextSize(
                    android.util.TypedValue.COMPLEX_UNIT_PX, size);
            while (lineWidthPx(p, s, size, ls) > avail
                    && size > soft) {
                size = Math.max(soft, size - step);
                v.setTextSize(
                        android.util.TypedValue.COMPLEX_UNIT_PX, size);
            }
            while (lineWidthPx(p, s, size, ls) > avail
                    && ls > -0.06f) {
                ls = Math.max(-0.06f, ls - 0.02f);
                v.setLetterSpacing(ls);
            }
            while (lineWidthPx(p, s, size, ls) > avail
                    && size > hard) {
                size = Math.max(hard, size - step);
                v.setTextSize(
                        android.util.TypedValue.COMPLEX_UNIT_PX, size);
            }
            while (lineWidthPx(p, s, size, ls) > avail
                    && size > 4f) {
                size -= step;
                v.setTextSize(
                        android.util.TypedValue.COMPLEX_UNIT_PX, size);
            }
            if (st == null) {
                st = new FitState();
                fitStates.put(v, st);
            }
            st.text = s;
            st.width = avail;
        });
    }

    /** Width of s at the paint's current size, plus the letter
     *  spacing extras that Paint.measureText does not count. */
    private static float lineWidthPx(android.graphics.Paint p,
                                     String s, float sizePx,
                                     float lsEm) {
        float w = p.measureText(s);
        if (lsEm != 0f && s.length() > 1) {
            w += lsEm * sizePx * (s.length() - 1);
        }
        return w;
    }

    private void refreshDetails(NodeState st, String tag,
                                UiLogic.HomeState hs, long now) {
        boolean stopped = st.status == NodeState.Status.STOPPED;
        detailsRunningBox.setVisibility(stopped ? View.GONE : View.VISIBLE);
        detailsStoppedBox.setVisibility(stopped ? View.VISIBLE : View.GONE);
        if (!stopped) {
            detBestValue.setText(st.bestBlock >= 0
                    ? UiLogic.grouped(st.bestBlock)
                    : I18n.get(this, StringsTable.VALUE_UNAVAILABLE));
            detHighestValue.setText(st.highestBlock >= 0
                    ? UiLogic.grouped(st.highestBlock)
                    : I18n.get(this, StringsTable.VALUE_UNAVAILABLE));
            long gap = UiLogic.syncGap(st.bestBlock, st.highestBlock);
            detGapValue.setText(gap >= 0
                    ? UiLogic.grouped(gap)
                    : I18n.get(this, StringsTable.VALUE_UNAVAILABLE));
            detPeersValue.setText(st.peers >= 0
                    ? UiLogic.grouped(st.peers)
                    : I18n.get(this, StringsTable.VALUE_UNAVAILABLE));
            detSessionValue.setText(st.sessionAcceptedElapsedMs >= 0
                    ? UiLogic.durationText(tag,
                            now - st.sessionAcceptedElapsedMs, false)
                    : I18n.get(this, StringsTable.VALUE_UNAVAILABLE));
        } else {
            cumUptimeValue.setText(UiLogic.durationText(
                    tag, TrackStore.uptimeMs(this), true));
            cumBlocksValue.setText(
                    UiLogic.grouped(TrackStore.blockUpdates(this)));
            cumPeersValue.setText(
                    UiLogic.grouped(TrackStore.maxPeers(this)));
        }

        // Diagnostics text: the service's own records, verbatim.
        StringBuilder sb = new StringBuilder();
        if (st.lastStopKind == NodeState.StopKind.USER) {
            sb.append(I18n.get(this, StringsTable.MESSAGE_USER_STOP));
        }
        if (st.lastExitCode != null) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(I18n.format(
                    this, StringsTable.NOTE_LAST_EXIT_CODE, st.lastExitCode));
        }
        if (st.lastNote != null) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(st.lastNote);
        }
        List<NodeState.Attempt> attempts = st.lastAttempts;
        if (attempts != null && !attempts.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(I18n.get(this, StringsTable.ATTEMPTS_TITLE));
            int n = 1;
            for (NodeState.Attempt a : attempts) {
                sb.append("\n");
                String method = I18n.get(this, a.viaLinker
                        ? StringsTable.ATTEMPT_METHOD_LINKER
                        : StringsTable.ATTEMPT_METHOD_DIRECT);
                if (a.exitCode != null) {
                    sb.append(I18n.format(this, StringsTable.ATTEMPT_SUMMARY,
                            n, method, a.exitCode, a.livedMs));
                } else {
                    sb.append(I18n.format(this,
                            StringsTable.ATTEMPT_SUMMARY_NO_EXIT,
                            n, method, a.livedMs));
                }
                if (a.lines.isEmpty()) {
                    sb.append("\n").append(
                            I18n.get(this, StringsTable.ATTEMPT_NO_OUTPUT));
                } else {
                    for (String line : a.lines) {
                        sb.append("\n").append(line);
                    }
                }
                n++;
            }
        }
        String diag = sb.length() == 0
                ? I18n.get(this, StringsTable.DIAGNOSTICS_EMPTY)
                : sb.toString();
        if (!diag.equals(lastDiagText)) {
            lastDiagText = diag;
            diagText.setText(diag);
        }
    }

    private void refreshSettings(NodeState st) {
        syncSwitch(telemetrySwitch, NodeSettings.telemetryEnabled(this));
        syncSwitch(lockSwitch, NodeSettings.settingsLocked(this));
        applySettingsLock();

        int pending = UiLogic.settingsPending(st.serviceRunning,
                st.launchedTelemetry, NodeSettings.telemetryEnabled(this),
                st.launchedNodeName, NodeSettings.effectiveNodeName(this));
        boolean telPending = (pending & UiLogic.PENDING_TELEMETRY) != 0;
        boolean namePending = (pending & UiLogic.PENDING_NAME) != 0;
        telemetryPendingView.setVisibility(telPending ? View.VISIBLE : View.GONE);
        telemetryAppliedView.setVisibility(telPending ? View.VISIBLE : View.GONE);
        if (telPending) {
            setTextIfChanged(telemetryPendingView,
                    I18n.get(this, StringsTable.SETTINGS_PENDING_STATUS));
            // Symmetric with the display name (finding L-6): the
            // value the running node is actually using, as a word.
            // telPending implies a launch snapshot exists.
            boolean applied = st.launchedTelemetry != null
                    && st.launchedTelemetry;
            setTextIfChanged(telemetryAppliedView, I18n.format(this,
                    StringsTable.SETTINGS_APPLIED_VALUE,
                    I18n.get(this, applied
                            ? StringsTable.VALUE_ON
                            : StringsTable.VALUE_OFF)));
        }
        namePendingView.setVisibility(namePending ? View.VISIBLE : View.GONE);
        nameAppliedView.setVisibility(namePending ? View.VISIBLE : View.GONE);
        if (namePending) {
            setTextIfChanged(namePendingView,
                    I18n.get(this, StringsTable.SETTINGS_PENDING_STATUS));
            String applied = st.launchedNodeName;
            setTextIfChanged(nameAppliedView, I18n.format(this,
                    StringsTable.SETTINGS_APPLIED_VALUE,
                    applied == null ? "" : applied));
        }
        nameValue.setText(shownName());
        languageValue.setText(currentLanguageLabel());

        // DePIN section: pull the facade's latest status and mirror
        // it. syncSwitch guards the programmatic toggle sync with
        // syncingSettings, exactly as for telemetry above.
        DepinModule facade = DepinModule.get(this);
        facade.refreshStatus();
        DepinStatus ds = facade.latestStatus();
        syncSwitch(depinSwitch, ds != null && ds.enabled);
        if (ds == null) {
            depinDeviceIdValue.setText(
                    I18n.get(this, StringsTable.DEPIN_VALUE_NONE));
            depinStateValue.setText(
                    I18n.get(this, StringsTable.DEPIN_STATE_UNAVAILABLE));
            depinCheckpointsValue.setText("\u2014");
            depinLastCheckpointValue.setText(
                    I18n.get(this, StringsTable.DEPIN_VALUE_NONE));
            depinNoteView.setVisibility(View.GONE);
            if (depinAttemptResetButton != null) {
                depinAttemptResetButton.setVisibility(View.GONE);
            }
            return;
        }
        depinDeviceIdValue.setText(ds.deviceId != null
                ? ds.deviceId
                : I18n.get(this, StringsTable.DEPIN_VALUE_NONE));
        String depinStateKey;
        switch (ds.state) {
            case DepinStatus.STATE_PREPARING:
                depinStateKey = StringsTable.DEPIN_STATE_PREPARING;
                break;
            case DepinStatus.STATE_RECORDING:
                depinStateKey = StringsTable.DEPIN_STATE_RECORDING;
                break;
            case DepinStatus.STATE_HELD:
                depinStateKey = StringsTable.DEPIN_STATE_HELD;
                break;
            case DepinStatus.STATE_SELF_STOPPED:
                depinStateKey = StringsTable.DEPIN_STATE_SELF_STOPPED;
                break;
            case DepinStatus.STATE_UNAVAILABLE:
                depinStateKey = StringsTable.DEPIN_STATE_UNAVAILABLE;
                break;
            case DepinStatus.STATE_OFF:
            default:
                depinStateKey = StringsTable.DEPIN_STATE_OFF;
                break;
        }
        depinStateValue.setText(I18n.get(this, depinStateKey));
        depinCheckpointsValue.setText(ds.checkpointCount >= 0
                ? UiLogic.grouped(ds.checkpointCount)
                : "\u2014");
        depinLastCheckpointValue.setText(ds.lastCheckpointUnix > 0
                ? new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                        .format(new Date(ds.lastCheckpointUnix * 1000L))
                : I18n.get(this, StringsTable.DEPIN_VALUE_NONE));
        // G-11 (design section 2.3): the confirmed repair is
        // offered exactly in the state that needs it — the
        // observer self-stopped on the attempt log and the
        // corruption is still reported. Everywhere else the
        // button stays hidden.
        if (depinAttemptResetButton != null) {
            boolean offerReset =
                    ds.state == DepinStatus.STATE_SELF_STOPPED
                            && ds.attemptLogCorrupt
                            && DepinIpc.REASON_ATTEMPT_LOG.equals(
                                    ds.selfStopReason);
            depinAttemptResetButton.setVisibility(
                    offerReset ? View.VISIBLE : View.GONE);
        }
        if (ds.state == DepinStatus.STATE_SELF_STOPPED) {
            String reason = ds.selfStopDetail != null
                    && !ds.selfStopDetail.isEmpty()
                    ? ds.selfStopDetail
                    : ds.selfStopReason != null
                            && !ds.selfStopReason.isEmpty()
                            ? ds.selfStopReason
                            : I18n.get(this, StringsTable.DEPIN_VALUE_NONE);
            depinNoteView.setText(I18n.format(
                    this, StringsTable.DEPIN_NOTE_SELF_STOPPED, reason));
            depinNoteView.setVisibility(View.VISIBLE);
        } else if (ds.commandDiscarded) {
            depinNoteView.setText(
                    I18n.get(this, StringsTable.DEPIN_NOTE_DISCARDED));
            depinNoteView.setVisibility(View.VISIBLE);
        } else if (ds.attemptLogResetUnix > 0
                && System.currentTimeMillis() / 1000L
                        - ds.attemptLogResetUnix
                        < ATTEMPT_RESET_NOTE_WINDOW_SEC) {
            // H-06 (final check round 3): the reset is never
            // silent — but the note is TIME-BOXED (seven days
            // from the execution, in any enable state) and it
            // yields to the command-discarded note above. The
            // pre-H-06 shape showed it for as long as the
            // observer sat OFF — which can be forever — ahead
            // of the discarded note, so a command discarded
            // after a reset was never surfaced at all.
            depinNoteView.setText(I18n.format(this,
                    StringsTable.DEPIN_NOTE_ATTEMPT_RESET_DONE,
                    new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                            .format(new Date(
                                    ds.attemptLogResetUnix * 1000L))));
            depinNoteView.setVisibility(View.VISIBLE);
        } else if ((ds.state == DepinStatus.STATE_RECORDING
                        || ds.state == DepinStatus.STATE_HELD)
                && ds.lastError != null && !ds.lastError.isEmpty()) {
            // H-09 (final check round 3): the observer's
            // last-error text finally has a reader. While
            // recording (or held), the reasons the loop gives
            // for skipping or holding a checkpoint — an
            // implausible reading, a held append, the
            // free-space floor — were written into the status
            // and displayed NOWHERE, so a silent stall looked
            // exactly like healthy recording. The state label
            // still leads; this note carries the detail.
            depinNoteView.setText(I18n.format(this,
                    StringsTable.DEPIN_NOTE_LAST_ERROR,
                    ds.lastError));
            depinNoteView.setVisibility(View.VISIBLE);
        } else if (ds.enabled && ds.restartFailures > 0
                && ds.state == DepinStatus.STATE_UNAVAILABLE) {
            // Internal.24: the restart backoff, stated
            // truthfully — restarts have been failing, they
            // continue automatically with growing spacing, and
            // an off→on retries immediately. (The internal.23
            // copy claimed restarts had STOPPED behind a latch
            // whose only exit was this same toggle; that state
            // no longer exists.)
            String when = ds.restartNextWallMs
                    > System.currentTimeMillis()
                    ? priceTimeText(ds.restartNextWallMs)
                    : I18n.get(this,
                            StringsTable.DEPIN_NEXT_RETRY_SOON);
            depinNoteView.setText(I18n.format(
                    this, StringsTable.DEPIN_NOTE_RESTART_BACKOFF,
                    when));
            depinNoteView.setVisibility(View.VISIBLE);
        } else {
            depinNoteView.setVisibility(View.GONE);
        }
    }

    /**
     * The user's explicit confirmation of the attempt-log
     * repair (design section 2.3; G-11). On confirm, the facade
     * records the one-shot reset request and the normal enable
     * path runs: the :depin process performs the reset during
     * its initialisation — corrupt bytes set aside under the
     * fixed name rpc-attempts.jsonl.corrupt (with -1, -2…
     * suffixes on collision), the fact recorded in the
     * settings and the dedicated log — and recording resumes.
     * This process writes no DePIN
     * settings itself (G-01: the settings writers are
     * :depin-only; the request is a bare flag file, in the
     * user-intent shape).
     */
    private void confirmAttemptLogReset() {
        new AlertDialog.Builder(this)
                .setTitle(I18n.get(this,
                        StringsTable.DEPIN_ATTEMPT_RESET_CONFIRM_TITLE))
                .setMessage(I18n.get(this,
                        StringsTable.DEPIN_ATTEMPT_RESET_CONFIRM_BODY))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(I18n.get(this,
                        StringsTable.DEPIN_ATTEMPT_RESET_BUTTON),
                        (dialog, which) -> {
                            DepinModule.get(this)
                                    .requestAttemptLogReset();
                            DepinStatus cur = DepinModule.get(this)
                                    .latestStatus();
                            String deviceId = cur != null
                                    ? cur.deviceId : null;
                            long generation = cur != null
                                    ? cur.identityGeneration : 0;
                            if (deviceId != null
                                    && currentTransferActiveDeviceId
                                            != null
                                    && !deviceId.equals(
                                            currentTransferActiveDeviceId)) {
                                // Same displaced-binding guard as
                                // the toggle: no expectation for a
                                // displaced identity.
                                deviceId = null;
                                generation = 0;
                            }
                            DepinModule.get(this).enable(
                                    deviceId, generation, notifStrings());
                            refresh();
                        })
                .show();
    }

    /** Skip a setText when the text is unchanged (avoids re-measure churn). */
    private static void setTextIfChanged(TextView v, String s) {
        CharSequence cur = v.getText();
        if (cur == null || !cur.toString().equals(s)) {
            v.setText(s);
        }
    }

    // ------------------------------------------------------------------
    // Proof-bundle export (integration step 5)
    // ------------------------------------------------------------------

    private void showExportNote(String text) {
        if (depinExportNoteView != null) {
            depinExportNoteView.setText(text);
            depinExportNoteView.setVisibility(View.VISIBLE);
        }
    }

    /** M-4: the export button is disabled from the tap until
     *  the export (and any save of its staged file) completes,
     *  so exports cannot overlap. */
    private void setExportBusy(boolean busy) {
        exportBusy = busy;
        if (depinExportButton != null) {
            depinExportButton.setEnabled(
                    !busy && !NodeSettings.settingsLocked(this));
        }
    }

    private void onExportBundleClicked() {
        if (NodeSettings.settingsLocked(this)) {
            applySettingsLock();
            return;
        }
        if (exportBusy) {
            return;
        }
        if (depinExportNoteView != null) {
            depinExportNoteView.setVisibility(View.GONE);
        }
        setExportBusy(true);
        DepinModule.get(this).exportBundle((result, name, error) ->
                runOnUiThread(() -> {
                    switch (result) {
                        case DepinIpc.BUNDLE_RESULT_OK: {
                            Intent intent = new Intent(
                                    Intent.ACTION_CREATE_DOCUMENT);
                            intent.addCategory(Intent.CATEGORY_OPENABLE);
                            intent.setType("application/json");
                            intent.putExtra(Intent.EXTRA_TITLE, name);
                            try {
                                startActivityForResult(
                                        intent, REQ_EXPORT_SAVE);
                                // Busy stays set: the staged file
                                // is in use until the save ends
                                // (finishBundleSave / picker cancel
                                // clears it).
                            } catch (Throwable t) {
                                DepinBundleExport.discardStaged(this);
                                setExportBusy(false);
                                showExportNote(I18n.format(this,
                                        StringsTable.DEPIN_EXPORT_FAILED,
                                        t.getClass().getSimpleName()));
                            }
                            break;
                        }
                        case DepinIpc.BUNDLE_RESULT_NONE:
                            setExportBusy(false);
                            showExportNote(I18n.get(
                                    this, StringsTable.DEPIN_EXPORT_NONE));
                            break;
                        case DepinModule.BUNDLE_NOT_RUNNING:
                            setExportBusy(false);
                            showExportNote(I18n.get(this,
                                    StringsTable.DEPIN_EXPORT_NOT_RUNNING));
                            break;
                        default:
                            setExportBusy(false);
                            showExportNote(I18n.format(this,
                                    StringsTable.DEPIN_EXPORT_FAILED,
                                    error != null ? error : "?"));
                            break;
                    }
                }));
    }

    /** Stream the staged bundle to the picked destination,
     *  then drop the staged copy either way. Runs on the
     *  shared serial executor (M-4) and ends the export's
     *  busy period. */
    private void finishBundleSave(Uri uri) {
        depinWorkExecutor.execute(() -> {
            boolean ok = false;
            try (java.io.InputStream in = new java.io.FileInputStream(
                         DepinFiles.pendingBundleFile(this));
                 java.io.OutputStream out =
                         getContentResolver().openOutputStream(uri)) {
                if (out == null) {
                    throw new IllegalStateException("no output stream");
                }
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
                ok = true;
            } catch (Throwable t) {
                final String msg = t.getClass().getSimpleName()
                        + ": " + t.getMessage();
                runOnUiThread(() -> showExportNote(I18n.format(
                        this, StringsTable.DEPIN_EXPORT_FAILED, msg)));
            } finally {
                DepinBundleExport.discardStaged(this);
            }
            runOnUiThread(() -> setExportBusy(false));
            if (ok) {
                runOnUiThread(() -> showExportNote(I18n.get(
                        this, StringsTable.DEPIN_EXPORT_SAVED)));
            }
        });
    }

    // ------------------------------------------------------------------
    // Device transfer (integration step 4; design sections 4, 5.3)
    // ------------------------------------------------------------------

    /** Follow-up for a completed transfer operation, run on
     *  the UI thread with the reply payload (dialogs, save
     *  pickers). */
    private interface TransferOk {
        void run(Bundle data) throws Exception;
    }

    private Button transferButton(LinearLayout root, String labelKey,
                                  final int action) {
        Button b = new Button(this);
        b.setText(I18n.get(this, labelKey));
        b.setTextSize(15);
        b.setTextColor(ACCENT);
        b.setAllCaps(false);
        b.setBackground(ripple(null));
        b.setStateListAnimator(null);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setMinHeight(dp(44));
        b.setOnClickListener(v -> runTransferAction(action));
        depinTransferButtons.add(b);
        root.addView(b, matchLpTop(2));
        return b;
    }

    /** One role's main action button: its label and the action
     *  it runs are (re)assigned by every state derivation, so
     *  the button always offers the flow's next step. */
    private Button mainTransferButton(View.OnClickListener listener) {
        Button b = new Button(this);
        b.setTextSize(15);
        b.setTextColor(ACCENT);
        b.setAllCaps(false);
        b.setBackground(ripple(null));
        b.setStateListAnimator(null);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setMinHeight(dp(44));
        b.setOnClickListener(listener);
        depinTransferButtons.add(b);
        return b;
    }

    /** The "other actions" expander over a role's secondary
     *  operations container. */
    private TextView othersToggle(final LinearLayout container,
                                  final boolean receiver) {
        final TextView t = text(I18n.get(
                this, StringsTable.DEPIN_TRANSFER_OTHER_OPS) + " ▸",
                14, LABEL3, false);
        t.setClickable(true);
        t.setFocusable(true);
        t.setMinHeight(dp(40));
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setOnClickListener(v -> {
            boolean expanded = receiver
                    ? (receiverOthersExpanded = !receiverOthersExpanded)
                    : (senderOthersExpanded = !senderOthersExpanded);
            container.setVisibility(
                    expanded ? View.VISIBLE : View.GONE);
            t.setText(I18n.get(this,
                    StringsTable.DEPIN_TRANSFER_OTHER_OPS)
                    + (expanded ? " ▾" : " ▸"));
        });
        return t;
    }

    /**
     * Run one device-transfer operation (audit H-1: in the
     * :depin process, via DepinTransferClient — this process
     * never loads a seed or a transfer machine). The transfer
     * layer is introduced by the service on first use
     * (legacy-upgrade mode; the positive legacy-journal
     * confirmation runs inside its initialise()). Until then
     * the app behaves exactly as in build step 2.
     *
     * Audit H-3 discipline is unchanged: the busy flag disables
     * the transfer buttons from the tap until the reply lands,
     * so a second tap cannot start a parallel operation over
     * the same transfer state and staging files. The reply is
     * asynchronous — a confirm under the section 3.5 budget may
     * take a long time and may come back PAUSED (a resumable
     * stop; re-issuing the same operation continues it) — and
     * success / failure land exactly where the in-process flow
     * put them: success refreshes the state line, failure (and
     * pause) shows the "Class: message" line the in-process
     * catch block produced.
     */
    private void transferOp(final int op, Bundle params,
                            final TransferOk ok) {
        // A new operation supersedes the held failure: the
        // error export always describes the LAST failure.
        pendingErrorOp = null;
        transferBusy = true;
        applyTransferButtonStates();
        DepinTransferClient.get(this).request(op, params,
                (rop, result, data) -> {
                    if (result == DepinIpc.T_RESULT_OK) {
                        runOnUiThread(() -> {
                            transferBusy = false;
                            hideTransferResult();
                            try {
                                if (ok != null) {
                                    ok.run(data);
                                }
                            } catch (Throwable t) {
                                captureTransferError(
                                        transferOpPhase(op),
                                        transferOpMode(op),
                                        transferOpRole(op),
                                        app.quantusnode.node.depin
                                                .TransferErrorReport
                                                .classify(t));
                                showTransferFailure(
                                        t.getClass().getSimpleName()
                                                + ": " + t.getMessage());
                                return;
                            }
                            refreshTransferState();
                        });
                    } else if (result == DepinIpc.T_RESULT_PAUSED) {
                        // Section 3.5 budget pause: a resumable
                        // stop, NOT a failure (the pre-consolidation
                        // UI lumped it into the failure branch and
                        // showed it as "failed", with no way to
                        // resume). The durable cursor is saved;
                        // the derived state now offers the resume.
                        final String msg = data.getString(
                                DepinIpc.KEY_T_ERROR_CLASS,
                                "VerificationPausedException")
                                + ": " + data.getString(
                                        DepinIpc.KEY_T_ERROR);
                        runOnUiThread(() -> {
                            transferBusy = false;
                            showTransferPaused(msg);
                        });
                    } else if (result
                            == DepinTransferClient.RESULT_UNAVAILABLE) {
                        runOnUiThread(() -> {
                            transferBusy = false;
                            captureTransferError(
                                    transferOpPhase(op),
                                    transferOpMode(op),
                                    transferOpRole(op),
                                    app.quantusnode.node.depin
                                            .TransferErrorReport
                                            .R_SERVICE);
                            showTransferFailure(
                                    "IllegalStateException: the "
                                            + "transfer service is "
                                            + "unavailable");
                        });
                    } else {
                        final String errorClass = data.getString(
                                DepinIpc.KEY_T_ERROR_CLASS,
                                "TransferException");
                        final String errorMessage = data.getString(
                                DepinIpc.KEY_T_ERROR);
                        final String errorChain = data.getString(
                                DepinIpc.KEY_T_ERROR_CHAIN);
                        final String msg = errorClass + ": "
                                + errorMessage;
                        runOnUiThread(() -> {
                            transferBusy = false;
                            captureTransferError(
                                    transferOpPhase(op),
                                    transferOpMode(op),
                                    transferOpRole(op),
                                    data.getString(
                                            DepinIpc.KEY_T_REASON,
                                            app.quantusnode.node.depin
                                                    .TransferErrorReport
                                                    .R_UNKNOWN));
                            showTransferFailure(msg);
                        });
                    }
                });
    }

    private void showTransferFailure(String msg) {
        applyTransferButtonStates();
        if (depinTransferResultView != null) {
            depinTransferResultView.setText(I18n.format(
                    this, StringsTable.DEPIN_TRANSFER_FAILED, msg));
            depinTransferResultView.setVisibility(View.VISIBLE);
        }
    }

    /** A section 3.5 pause gets its own wording: the state is
     *  saved and the same action resumes it. The state lines are
     *  refreshed too — after a paused stage they offer the
     *  resume as the main action. */
    private void showTransferPaused(String msg) {
        applyTransferButtonStates();
        if (depinTransferResultView != null) {
            depinTransferResultView.setText(I18n.format(
                    this, StringsTable.DEPIN_TRANSFER_PAUSED, msg));
            depinTransferResultView.setVisibility(View.VISIBLE);
        }
        refreshTransferState();
    }

    private void hideTransferResult() {
        if (depinTransferResultView != null) {
            depinTransferResultView.setVisibility(View.GONE);
        }
    }

    private static String transferOpLabel(int op) {
        switch (op) {
            case DepinIpc.T_OP_EXPORT_PACKAGE:
                return "export_package";
            case DepinIpc.T_OP_EXPORT_PACKAGE_ONESHOT:
                return "export_package_oneshot";
            case DepinIpc.T_OP_EXPORT_PACKAGE_ONESHOT_AGAIN:
                return "export_package_oneshot_again";
            case DepinIpc.T_OP_ISSUE_PERMIT:
                return "issue_permit";
            case DepinIpc.T_OP_CANCEL_SENDER:
                return "cancel_sender";
            case DepinIpc.T_OP_INTAKE_RECEIPT:
                return "intake_receipt";
            case DepinIpc.T_OP_INTAKE_ABANDON:
                return "intake_abandon";
            case DepinIpc.T_OP_REPRESENT_ACK:
                return "represent_ack";
            case DepinIpc.T_OP_INTAKE_NOTICE:
                return "intake_notice";
            case DepinIpc.T_OP_STAGE_PACKAGE:
                return "stage_package";
            case DepinIpc.T_OP_COMPLETE_INTAKE:
                return "complete_intake";
            case DepinIpc.T_OP_INTAKE_PERMIT:
                return "intake_permit";
            case DepinIpc.T_OP_INTAKE_ACK:
                return "intake_ack";
            case DepinIpc.T_OP_INTAKE_CANCEL_TOKEN:
                return "intake_cancel_token";
            case DepinIpc.T_OP_EXPORT_NOTICE:
                return "export_notice";
            case DepinIpc.T_OP_CONFIRM:
                return "confirm";
            case DepinIpc.T_OP_CONFIRM_ONESHOT:
                return "confirm_oneshot";
            case DepinIpc.T_OP_CLOSE_ONESHOT_SENDER:
                return "close_oneshot_sender";
            case DepinIpc.T_OP_CANCEL_ONESHOT_SENDER:
                return "cancel_oneshot_sender";
            case DepinIpc.T_OP_EXPORT_RECEIPT:
                return "export_receipt";
            case DepinIpc.T_OP_EXPORT_ABANDON:
                return "export_abandon";
            default:
                return "op_" + op;
        }
    }

    private static String transferOpMode(int op) {
        switch (op) {
            case DepinIpc.T_OP_EXPORT_PACKAGE_ONESHOT:
            case DepinIpc.T_OP_EXPORT_PACKAGE_ONESHOT_AGAIN:
            case DepinIpc.T_OP_CONFIRM_ONESHOT:
            case DepinIpc.T_OP_CLOSE_ONESHOT_SENDER:
            case DepinIpc.T_OP_CANCEL_ONESHOT_SENDER:
                return "oneshot";
            default:
                return "strict";
        }
    }

    private static String transferOpRole(int op) {
        switch (op) {
            case DepinIpc.T_OP_EXPORT_PACKAGE:
            case DepinIpc.T_OP_EXPORT_PACKAGE_ONESHOT:
            case DepinIpc.T_OP_ISSUE_PERMIT:
            case DepinIpc.T_OP_CANCEL_SENDER:
            case DepinIpc.T_OP_INTAKE_RECEIPT:
            case DepinIpc.T_OP_INTAKE_ABANDON:
            case DepinIpc.T_OP_REPRESENT_ACK:
            case DepinIpc.T_OP_INTAKE_NOTICE:
            case DepinIpc.T_OP_EXPORT_PACKAGE_ONESHOT_AGAIN:
            case DepinIpc.T_OP_CLOSE_ONESHOT_SENDER:
            case DepinIpc.T_OP_CANCEL_ONESHOT_SENDER:
                return "sender";
            default:
                return "receiver";
        }
    }

    /** The diagnostic taxonomy phase for an operation (E10). */
    private static String transferOpPhase(int op) {
        switch (op) {
            case DepinIpc.T_OP_EXPORT_PACKAGE:
            case DepinIpc.T_OP_EXPORT_PACKAGE_ONESHOT:
            case DepinIpc.T_OP_EXPORT_PACKAGE_ONESHOT_AGAIN:
                return app.quantusnode.node.depin
                        .TransferErrorReport.PHASE_EXPORT;
            case DepinIpc.T_OP_STAGE_PACKAGE:
            case DepinIpc.T_OP_COMPLETE_INTAKE:
                return app.quantusnode.node.depin
                        .TransferErrorReport.PHASE_STAGE;
            case DepinIpc.T_OP_CONFIRM:
            case DepinIpc.T_OP_CONFIRM_ONESHOT:
                return app.quantusnode.node.depin
                        .TransferErrorReport.PHASE_CONFIRM;
            case DepinIpc.T_OP_CLOSE_ONESHOT_SENDER:
                return app.quantusnode.node.depin
                        .TransferErrorReport.PHASE_CLOSE;
            case DepinIpc.T_OP_CANCEL_SENDER:
            case DepinIpc.T_OP_CANCEL_ONESHOT_SENDER:
            case DepinIpc.T_OP_INTAKE_ABANDON:
            case DepinIpc.T_OP_EXPORT_ABANDON:
                return app.quantusnode.node.depin
                        .TransferErrorReport.PHASE_CANCEL;
            case DepinIpc.T_OP_EXPORT_NOTICE:
            case DepinIpc.T_OP_INTAKE_NOTICE:
                return app.quantusnode.node.depin
                        .TransferErrorReport.PHASE_NOTICE;
            case DepinIpc.T_OP_ISSUE_PERMIT:
            case DepinIpc.T_OP_INTAKE_PERMIT:
                return app.quantusnode.node.depin
                        .TransferErrorReport.PHASE_PERMIT;
            case DepinIpc.T_OP_EXPORT_RECEIPT:
            case DepinIpc.T_OP_INTAKE_RECEIPT:
                return app.quantusnode.node.depin
                        .TransferErrorReport.PHASE_RECEIPT;
            case DepinIpc.T_OP_INTAKE_ACK:
            case DepinIpc.T_OP_REPRESENT_ACK:
                return app.quantusnode.node.depin
                        .TransferErrorReport.PHASE_ACK;
            case DepinIpc.T_OP_INTAKE_CANCEL_TOKEN:
                return app.quantusnode.node.depin
                        .TransferErrorReport.PHASE_RECOVERY;
            default:
                return app.quantusnode.node.depin
                        .TransferErrorReport.PHASE_SERVICE;
        }
    }

    /** Hold a failure for the error-file export (E10: the
     *  file is built from a strict whitelist — own state plus
     *  manifest-derived facts — and the failure is described
     *  by OUR phase + reason taxonomy, never exception text).
     *  Kept here: the phase, mode and role, the reason code,
     *  and the durable context the last state derivation read
     *  — device ids, generation, package hash and the
     *  staging's verified/total counts. */
    private void captureTransferError(String phase, String mode,
                                      String role, String reason) {
        pendingErrorOp = phase;
        pendingErrorMode = mode;
        pendingErrorRole = role;
        pendingErrorReason = reason;
        pendingErrorWhenMs = System.currentTimeMillis();
        // Audit Low (error-file labels): the report's
        // peer_from_manifest block is only truthful for a
        // RECEIVER-side failure — the manifest's identity is
        // the peer there. For a sender-side failure this code
        // used to fill it with whichever track existed (with
        // both tracks present, the RECEIVER's id — the exact
        // opposite of the failing side). Sender-side failures
        // omit the peer block; the sender identity still
        // appears in local_state from the snapshot.
        if ("sender".equals(role)) {
            pendingErrorDeviceId = null;
            pendingErrorGeneration = -1;
            pendingErrorPackageSha256 = null;
        } else {
            pendingErrorDeviceId = currentReceiverDeviceId != null
                    ? currentReceiverDeviceId : currentSenderDeviceId;
            pendingErrorGeneration = currentReceiverDeviceId != null
                    ? currentReceiverGeneration
                    : currentSenderGeneration;
            pendingErrorPackageSha256 = currentReceiverPackageSha256;
        }
        pendingErrorCounterpartId = currentReceiverDeviceId != null
                && currentSenderDeviceId != null
                && !currentSenderDeviceId.equals(currentReceiverDeviceId)
                ? currentSenderDeviceId : null;
        TransferFlow.StagingRef ref = currentUnnotifiedStaging;
        pendingErrorTotal = ref != null ? ref.checkpointCount : -1;
        pendingErrorVerified = ref != null ? ref.verifiedCount : -1;
        applyTransferButtonStates();
    }

    /** Write the held failure's diagnostic JSON and offer it
     *  through the usual save picker. The state classification
     *  is re-derived at save time ("where readable"), and the
     *  build identity comes from the package manager. */
    private void saveTransferErrorFile() {
        if (pendingErrorOp == null) return;
        final String phase = pendingErrorOp;
        final String mode = pendingErrorMode;
        final String role = pendingErrorRole;
        final String reason = pendingErrorReason;
        final long whenMs = pendingErrorWhenMs;
        final String deviceId = pendingErrorDeviceId;
        final long generation = pendingErrorGeneration;
        final String sha = pendingErrorPackageSha256;
        final long total = pendingErrorTotal;
        final long verified = pendingErrorVerified;
        transferBusy = true;
        applyTransferButtonStates();
        depinWorkExecutor.execute(() -> {
            TransferFlow.Snapshot snap = null;
            try {
                snap = TransferFlow.derive(DepinFiles.depinDir(this));
            } catch (Throwable ignored) {
                // The classification is best-effort; the rest of
                // the report does not depend on it.
            }
            String appName = "Quantus Node";
            String versionName = "";
            long versionCode = -1;
            try {
                android.content.pm.PackageInfo pi =
                        getPackageManager().getPackageInfo(
                                getPackageName(), 0);
                versionName = pi.versionName;
                versionCode = pi.getLongVersionCode();
                CharSequence label = getApplicationInfo().loadLabel(
                        getPackageManager());
                if (label != null) appName = label.toString();
            } catch (Throwable ignored) {
            }
            final String name = app.quantusnode.node.depin
                    .TransferErrorReport.suggestedFileName();
            final java.io.File out = new java.io.File(
                    getCacheDir(), name);
            try {
                String json = app.quantusnode.node.depin
                        .TransferErrorReport.buildJson(
                                appName, versionName, versionCode,
                                whenMs, phase, mode, role, reason,
                                snap, deviceId, generation, sha,
                                total, verified);
                app.quantusnode.node.depin.core.TransferIo.writeFileSync(
                        out, json.getBytes(
                                java.nio.charset.StandardCharsets.UTF_8));
            } catch (Throwable t) {
                final String msg = t.getClass().getSimpleName()
                        + ": " + t.getMessage();
                runOnUiThread(() -> {
                    transferBusy = false;
                    showTransferFailure(msg);
                });
                return;
            }
            runOnUiThread(() -> {
                transferBusy = false;
                applyTransferButtonStates();
                saveTransferDocument(name, out);
            });
        });
    }

    /** A finished export's reply (strict and one-shot share
     *  the shape): the transfer code is shown once, on this
     *  screen only, as when the export ran in-process; then
     *  the save picker takes the package file the service
     *  named. */
    /** A one-shot confirm finished (E4): show the
     *  confirmation number the user must carry to the sender
     *  side — it is also kept in the DONE state text — and
     *  try to delete the picked source file (E1). */
    private void onConfirmOneshotReply(Bundle data) {
        String code = data.getString(DepinIpc.KEY_T_COMMIT_CODE);
        if (code != null) {
            new AlertDialog.Builder(this)
                    .setTitle(I18n.get(this,
                            StringsTable
                                    .DEPIN_TRANSFER_COMMIT_CODE_TITLE))
                    .setMessage(I18n.format(this,
                            StringsTable
                                    .DEPIN_TRANSFER_COMMIT_CODE_BODY,
                            code))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
        attemptDeletePickedPackage();
    }

    /** E7 re-export reply: the new code, with the note that
     *  the previous file remains valid. */
    private void onExportReplyAgain(Bundle data) {
        final String code = data.getString(
                DepinIpc.KEY_T_CODE_DISPLAY);
        new AlertDialog.Builder(this)
                .setMessage(code + "\n\n" + I18n.get(this,
                        StringsTable
                                .DEPIN_TRANSFER_EXPORTED_AGAIN_NOTE))
                .setPositiveButton(android.R.string.ok, null)
                .show();
        saveStagedOutput(data);
    }

    /** E1: after a successful intake, try to delete the
     *  picked source file through the provider; when that is
     *  not possible, ask the user to delete it. Runs once per
     *  pick. */
    private void attemptDeletePickedPackage() {
        final android.net.Uri uri = pickedPackageUri;
        pickedPackageUri = null;
        if (uri == null) return;
        new Thread(() -> {
            boolean deleted = false;
            try {
                deleted = android.provider.DocumentsContract
                        .deleteDocument(getContentResolver(), uri);
            } catch (Throwable ignored) {
                deleted = false;
            }
            if (!deleted) {
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setMessage(I18n.get(this,
                                StringsTable
                                        .DEPIN_TRANSFER_DELETE_SOURCE_PROMPT))
                        .setPositiveButton(android.R.string.ok, null)
                        .show());
            }
        }).start();
    }

    private void onExportReply(Bundle data) {
        final String code = data.getString(
                DepinIpc.KEY_T_CODE_DISPLAY);
        new AlertDialog.Builder(this)
                .setMessage(code)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        saveStagedOutput(data);
    }

    /** Save-picker follow-up for the message outputs an
     *  operation staged: the reply's suggested name, sourced
     *  from the :depin staging slot (or, for the sender
     *  package, the core outbox file named in the reply). */
    private void saveStagedOutput(Bundle data) {
        String name = data.getString(DepinIpc.KEY_T_SAVE_NAME);
        String path = data.getString(DepinIpc.KEY_T_PACKAGE_PATH);
        java.io.File source = path != null
                ? new java.io.File(path)
                : DepinFiles.transferOutputFile(this);
        saveTransferDocument(name, source);
    }

    private void applyTransferButtonStates() {
        final boolean locked = NodeSettings.settingsLocked(this);
        for (Button b : depinTransferButtons) {
            b.setEnabled(!locked && !transferBusy);
        }
        // The ack re-presentation additionally needs a completed
        // send in the history to re-present from.
        if (depinTransferRepresentAckButton != null) {
            depinTransferRepresentAckButton.setEnabled(
                    !locked && !transferBusy && representAckAvailable);
        }
        // The one-shot other-actions exist only in their states.
        if (depinTransferConfirmOneshotButton != null) {
            depinTransferConfirmOneshotButton.setEnabled(
                    !locked && !transferBusy && oneshotConfirmAvailable);
        }
        if (depinTransferCancelOneshotButton != null) {
            depinTransferCancelOneshotButton.setEnabled(
                    !locked && !transferBusy && oneshotSenderOpen);
        }
        if (depinTransferReexportButton != null) {
            depinTransferReexportButton.setEnabled(
                    !locked && !transferBusy && oneshotSenderOpen);
        }
        // The error export mirrors the failure line's visibility
        // (it is not gated on the settings lock: saving a
        // diagnostic file changes no setting or state).
        if (depinTransferSaveErrorButton != null) {
            depinTransferSaveErrorButton.setVisibility(
                    pendingErrorOp != null
                            ? View.VISIBLE : View.GONE);
            depinTransferSaveErrorButton.setEnabled(!transferBusy);
        }
    }

    private void refreshTransferState() {
        if (depinTransferReceiverStateView == null) return;
        applyTransferButtonStates();
        final boolean locked = NodeSettings.settingsLocked(this);
        new Thread(() -> {
            TransferFlow.Snapshot snap;
            try {
                snap = TransferFlow.derive(DepinFiles.depinDir(this));
            } catch (Throwable t) {
                snap = null;
            }
            final TransferFlow.Snapshot fs = snap;
            runOnUiThread(() -> applyTransferSnapshot(fs, locked));
        }, "depin-transfer-state").start();
    }

    /** Apply one derived snapshot to the two role blocks: the
     *  state lines ("what is done, what is next"), the main
     *  buttons' labels and actions, and the lock note's
     *  visibility. All state comes from TransferFlow's reading
     *  of the durable store — including the initialisation
     *  record, whose absence the pre-consolidation status line
     *  could not distinguish from "nothing imported yet" (it
     *  showed "not introduced" even after the introduction had
     *  completed). */
    private void applyTransferSnapshot(TransferFlow.Snapshot snap,
                                       boolean locked) {
        if (depinTransferReceiverStateView == null) return;
        if (depinTransferLockedView != null) {
            depinTransferLockedView.setVisibility(
                    locked ? View.VISIBLE : View.GONE);
        }
        if (snap == null || snap.loadFailed) {
            depinTransferReceiverStateView.setText(I18n.format(
                    this, StringsTable.DEPIN_TRANSFER_FAILED,
                    "transfer state unreadable"));
            depinTransferSenderStateView.setText("");
            currentUnnotifiedStaging = null;
            representAckAvailable = false;
            oneshotSenderOpen = false;
            oneshotConfirmAvailable = false;
            currentOneshotBlockedByLive = false;
            applyTransferButtonStates();
            return;
        }
        currentUnnotifiedStaging = snap.unnotifiedStaging;
        currentTransferActiveDeviceId = snap.activeDeviceId;
        currentReceiverDeviceId = snap.receiverDeviceId;
        currentReceiverGeneration = snap.receiverGeneration;
        currentReceiverPackageSha256 = snap.receiverPackageSha256;
        currentSenderDeviceId = snap.senderDeviceId;
        currentSenderGeneration = snap.senderGeneration;
        currentCompletedSenderGeneration =
                snap.completedSenderGeneration;
        representAckAvailable = snap.sender == TransferFlow.SenderState.DONE
                && snap.completedSenderGeneration >= 0;
        oneshotSenderOpen = snap.senderOneshot;
        // Audit M1: E8's refusal is predictable at stage time —
        // when a DIFFERENT identity is live, the one-shot
        // confirm can only fail, so it is not offered (the
        // button below, and the confirmation dialog's simple
        // choice, which shows the reason instead).
        currentOneshotBlockedByLive = snap.oneshotBlockedByLiveIdentity;
        oneshotConfirmAvailable = !snap.oneshotBlockedByLiveIdentity
                && (snap.receiver
                        == TransferFlow.ReceiverState.STAGED_NOTIFIED
                        || snap.receiver
                        == TransferFlow.ReceiverState.PERMIT_IN);

        // Receiver: state line + the main action it implies.
        String rKey;
        boolean rGenArg = true;
        switch (snap.receiver) {
            case INTRODUCING:
                rKey = StringsTable.DEPIN_TRANSFER_INTRODUCING;
                rGenArg = false;
                receiverMainAction = ACT_IMPORT_PACKAGE;
                break;
            case READY:
                rKey = StringsTable.DEPIN_TRANSFER_RS_READY;
                rGenArg = false;
                receiverMainAction = ACT_IMPORT_PACKAGE;
                break;
            case STAGED_UNNOTIFIED:
                rKey = StringsTable.DEPIN_TRANSFER_RS_STAGED_UNNOTIFIED;
                rGenArg = false;
                receiverMainAction = ACT_RESUME_INTAKE;
                break;
            case STAGED_NOTIFIED:
                rKey = StringsTable.DEPIN_TRANSFER_RS_NOTIFIED;
                receiverMainAction = ACT_EXPORT_NOTICE;
                break;
            case PERMIT_IN:
                rKey = StringsTable.DEPIN_TRANSFER_RS_PERMIT_IN;
                receiverMainAction = ACT_CONFIRM;
                break;
            case PERMIT_EXPIRED:
                rKey = StringsTable.DEPIN_TRANSFER_RS_PERMIT_EXPIRED;
                receiverMainAction = ACT_IMPORT_PERMIT;
                break;
            case TRIAL_CLOSED:
                rKey = StringsTable.DEPIN_TRANSFER_RS_TRIAL_CLOSED;
                receiverMainAction = ACT_EXPORT_ABANDON;
                break;
            case CONFIRMED:
                rKey = StringsTable.DEPIN_TRANSFER_RS_CONFIRMED;
                receiverMainAction = ACT_EXPORT_RECEIPT;
                break;
            case DONE:
                rKey = StringsTable.DEPIN_TRANSFER_RS_DONE;
                receiverMainAction = ACT_IMPORT_PACKAGE;
                break;
            case CLOSED_REFUSAL:
                rKey = StringsTable.DEPIN_TRANSFER_RS_CLOSED_REFUSAL;
                receiverMainAction = ACT_IMPORT_PACKAGE;
                break;
            case CLOSED_CANCEL:
                rKey = StringsTable.DEPIN_TRANSFER_RS_CLOSED_CANCEL;
                receiverMainAction = ACT_IMPORT_PACKAGE;
                break;
            case CLOSED_OTHER:
                rKey = StringsTable.DEPIN_TRANSFER_RS_CLOSED_OTHER;
                receiverMainAction = ACT_IMPORT_PACKAGE;
                break;
            case NOT_INTRODUCED:
            default:
                rKey = StringsTable.DEPIN_TRANSFER_RS_NONE;
                rGenArg = false;
                receiverMainAction = ACT_IMPORT_PACKAGE;
                break;
        }
        // File-production hints: the notice / receipt exports
        // change no durable state, so the derived state alone
        // would offer the export forever and never the intake
        // that follows it. A matching session hint advances the
        // main action to that intake (and the state line says
        // so); the exports stay reachable under "other actions".
        if (snap.receiver == TransferFlow.ReceiverState.STAGED_NOTIFIED
                && hintMatches(hintNoticeDeviceId, hintNoticeGeneration,
                        hintNoticePackageSha256, snap)) {
            rKey = StringsTable.DEPIN_TRANSFER_RS_NOTICE_OUT;
            receiverMainAction = ACT_IMPORT_PERMIT;
        } else if (snap.receiver
                == TransferFlow.ReceiverState.CONFIRMED
                && hintMatches(hintReceiptDeviceId, hintReceiptGeneration,
                        hintReceiptPackageSha256, snap)) {
            rKey = StringsTable.DEPIN_TRANSFER_RS_RECEIPT_OUT;
            receiverMainAction = ACT_IMPORT_ACK;
        }
        if (snap.receiver == TransferFlow.ReceiverState.STAGED_UNNOTIFIED
                && snap.unnotifiedStaging != null
                && snap.unnotifiedStaging.verifiedCount >= 0
                && snap.unnotifiedStaging.checkpointCount > 0) {
            // Section 3.5 progress, derived from the durable
            // resume cursor: how much of the staged journal is
            // already re-verified. This line is what the user
            // watches move while a stage runs (the periodic
            // refresh re-derives it), and what a budget-stopped
            // intake reports alongside the wait guidance.
            String progress = I18n.format(this,
                    StringsTable.DEPIN_TRANSFER_RS_STAGED_PROGRESS,
                    snap.unnotifiedStaging.verifiedCount,
                    snap.unnotifiedStaging.checkpointCount);
            if (snap.unnotifiedStaging.archiveCheckpointCount > 0) {
                // E11: the archive counts are shown separately
                // from the current-format progress.
                progress += "\n" + I18n.format(this,
                        StringsTable.DEPIN_TRANSFER_RS_ARCHIVE_LINE,
                        snap.unnotifiedStaging.archiveCheckpointCount);
            }
            depinTransferReceiverStateView.setText(progress);
        } else if (snap.receiver == TransferFlow.ReceiverState.DONE
                && snap.receiverCommitCode != null) {
            // E4: the confirmation number stays visible in the
            // completed state until the sender side has used it.
            depinTransferReceiverStateView.setText(
                    I18n.format(this, rKey, snap.receiverGeneration)
                            + "\n" + I18n.format(this,
                                    StringsTable
                                            .DEPIN_TRANSFER_RS_COMMIT_CODE,
                                    snap.receiverCommitCode));
        } else {
            depinTransferReceiverStateView.setText(rGenArg
                    ? I18n.format(this, rKey, snap.receiverGeneration)
                    : I18n.get(this, rKey));
        }
        depinTransferReceiverMainButton.setText(I18n.get(this,
                receiverMainLabel(receiverMainAction)));

        // Sender: same treatment.
        String sKey;
        boolean sGenArg = true;
        switch (snap.sender) {
            case INTRODUCING:
                sKey = StringsTable.DEPIN_TRANSFER_INTRODUCING;
                sGenArg = false;
                senderMainAction = ACT_EXPORT_PACKAGE_ONESHOT;
                break;
            case READY:
                sKey = StringsTable.DEPIN_TRANSFER_SS_READY;
                sGenArg = false;
                senderMainAction = ACT_EXPORT_PACKAGE_ONESHOT;
                break;
            case FROZEN_WAIT_NOTICE:
                sKey = StringsTable.DEPIN_TRANSFER_SS_WAIT_NOTICE;
                senderMainAction = ACT_IMPORT_NOTICE;
                break;
            case FROZEN_NOTICE_IN:
                sKey = StringsTable.DEPIN_TRANSFER_SS_NOTICE_IN;
                senderMainAction = ACT_ISSUE_PERMIT;
                break;
            case PERMIT_ISSUED:
                sKey = StringsTable.DEPIN_TRANSFER_SS_PERMIT_ISSUED;
                senderMainAction = ACT_IMPORT_RECEIPT;
                break;
            case FROZEN_ONESHOT:
                sKey = snap.senderCheckpointCount >= 0
                        ? StringsTable.DEPIN_TRANSFER_SS_FROZEN_ONESHOT
                        : StringsTable.DEPIN_TRANSFER_SS_FROZEN_ONESHOT_NC;
                sGenArg = false;
                senderMainAction = ACT_CLOSE_ONESHOT;
                break;
            case DONE:
                sKey = StringsTable.DEPIN_TRANSFER_SS_DONE;
                sGenArg = false;
                senderMainAction = ACT_EXPORT_PACKAGE_ONESHOT;
                break;
            case CLOSED:
                sKey = StringsTable.DEPIN_TRANSFER_SS_CLOSED;
                sGenArg = false;
                senderMainAction = ACT_EXPORT_PACKAGE_ONESHOT;
                break;
            case NOT_INTRODUCED:
            default:
                sKey = StringsTable.DEPIN_TRANSFER_SS_NONE;
                sGenArg = false;
                senderMainAction = ACT_EXPORT_PACKAGE_ONESHOT;
                break;
        }
        if (snap.sender == TransferFlow.SenderState.FROZEN_ONESHOT) {
            // The frozen one-shot line carries the generation
            // and, when the manifest recorded it, the checkpoint
            // count — numbers, not a bare "in progress".
            depinTransferSenderStateView.setText(
                    snap.senderCheckpointCount >= 0
                            ? I18n.format(this, sKey,
                                    snap.senderGeneration,
                                    snap.senderCheckpointCount)
                            : I18n.format(this, sKey,
                                    snap.senderGeneration));
        } else {
            depinTransferSenderStateView.setText(sGenArg
                    ? I18n.format(this, sKey, snap.senderGeneration)
                    : I18n.get(this, sKey));
        }
        depinTransferSenderMainButton.setText(I18n.get(this,
                senderMainLabel(senderMainAction)));
        applyTransferButtonStates();
    }

    private static boolean hintMatches(String deviceId, long generation,
                                       String packageSha256,
                                       TransferFlow.Snapshot snap) {
        return deviceId != null
                && deviceId.equals(snap.receiverDeviceId)
                && generation == snap.receiverGeneration
                && packageSha256 != null
                && packageSha256.equals(snap.receiverPackageSha256);
    }

    /** The label a main action carries: the operation's own
     *  string, so the button reads exactly like the step it
     *  runs. */
    private String receiverMainLabel(int action) {
        switch (action) {
            case ACT_RESUME_INTAKE:
                return StringsTable.DEPIN_TRANSFER_RESUME_INTAKE;
            case ACT_EXPORT_NOTICE:
                return StringsTable.DEPIN_TRANSFER_EXPORT_NOTICE;
            case ACT_IMPORT_PERMIT:
                return StringsTable.DEPIN_TRANSFER_IMPORT_PERMIT;
            case ACT_CONFIRM:
                return StringsTable.DEPIN_TRANSFER_CONFIRM_BUTTON;
            case ACT_EXPORT_ABANDON:
                return StringsTable.DEPIN_TRANSFER_EXPORT_ABANDON;
            case ACT_EXPORT_RECEIPT:
                return StringsTable.DEPIN_TRANSFER_EXPORT_RECEIPT;
            case ACT_IMPORT_ACK:
                return StringsTable.DEPIN_TRANSFER_IMPORT_ACK;
            case ACT_IMPORT_PACKAGE:
            default:
                return StringsTable.DEPIN_TRANSFER_IMPORT_PACKAGE;
        }
    }

    private String senderMainLabel(int action) {
        switch (action) {
            case ACT_IMPORT_NOTICE:
                return StringsTable.DEPIN_TRANSFER_IMPORT_NOTICE;
            case ACT_ISSUE_PERMIT:
                return StringsTable.DEPIN_TRANSFER_ISSUE_PERMIT;
            case ACT_IMPORT_RECEIPT:
                return StringsTable.DEPIN_TRANSFER_IMPORT_RECEIPT;
            case ACT_CLOSE_ONESHOT:
                return StringsTable.DEPIN_TRANSFER_CLOSE_ONESHOT;
            case ACT_EXPORT_PACKAGE:
                return StringsTable.DEPIN_TRANSFER_EXPORT_PACKAGE;
            case ACT_EXPORT_PACKAGE_ONESHOT:
            default:
                return StringsTable.DEPIN_TRANSFER_EXPORT_PACKAGE_ONESHOT;
        }
    }

    private void runTransferAction(int action) {
        if (action == ACT_SAVE_ERROR) {
            // Saving the diagnostic file changes nothing and is
            // offered precisely when something else just failed;
            // it is not gated on the settings lock.
            saveTransferErrorFile();
            return;
        }
        if (NodeSettings.settingsLocked(this)) {
            refreshTransferState();
            return;
        }
        switch (action) {
            case ACT_IMPORT_PACKAGE:
                openTransferDocument(T_OPEN_PACKAGE);
                break;
            case ACT_IMPORT_PERMIT:
                openTransferDocument(T_OPEN_PERMIT);
                break;
            case ACT_IMPORT_ACK:
                openTransferDocument(T_OPEN_ACK);
                break;
            case ACT_IMPORT_NOTICE:
                openTransferDocument(T_OPEN_NOTICE);
                break;
            case ACT_IMPORT_RECEIPT:
                openTransferDocument(T_OPEN_RECEIPT);
                break;
            case ACT_IMPORT_ABANDON:
                openTransferDocument(T_OPEN_ABANDON);
                break;
            case ACT_IMPORT_CANCEL_TOKEN:
                openTransferDocument(T_OPEN_CANCEL_TOKEN);
                break;
            case ACT_EXPORT_ABANDON:
                transferOp(DepinIpc.T_OP_EXPORT_ABANDON, null,
                        this::saveStagedOutput);
                break;
            case ACT_EXPORT_NOTICE:
                transferOp(DepinIpc.T_OP_EXPORT_NOTICE, null, data -> {
                    hintNoticeDeviceId = currentReceiverDeviceId;
                    hintNoticeGeneration = currentReceiverGeneration;
                    hintNoticePackageSha256 =
                            currentReceiverPackageSha256;
                    saveStagedOutput(data);
                });
                break;
            case ACT_CONFIRM:
                transferOp(DepinIpc.T_OP_CONFIRM, null,
                        data -> attemptDeletePickedPackage());
                break;
            case ACT_EXPORT_RECEIPT:
                transferOp(DepinIpc.T_OP_EXPORT_RECEIPT, null, data -> {
                    hintReceiptDeviceId = currentReceiverDeviceId;
                    hintReceiptGeneration = currentReceiverGeneration;
                    hintReceiptPackageSha256 =
                            currentReceiverPackageSha256;
                    saveStagedOutput(data);
                });
                break;
            case ACT_RESUME_INTAKE:
                resumeStagedIntake();
                break;
            case ACT_REPRESENT_ACK: {
                Bundle params = new Bundle();
                params.putLong(DepinIpc.KEY_T_GENERATION,
                        currentCompletedSenderGeneration);
                transferOp(DepinIpc.T_OP_REPRESENT_ACK, params,
                        this::saveStagedOutput);
                break;
            }
            case ACT_EXPORT_PACKAGE:
                transferOp(DepinIpc.T_OP_EXPORT_PACKAGE, null,
                        this::onExportReply);
                break;
            case ACT_EXPORT_PACKAGE_ONESHOT:
                transferOp(DepinIpc.T_OP_EXPORT_PACKAGE_ONESHOT, null,
                        this::onExportReply);
                break;
            case ACT_CONFIRM_ONESHOT:
                // From "other actions" on a notified record: the
                // all-values confirmation was shown at stage
                // time; ask once more, plainly, then confirm in
                // one-shot mode (no notice / permit / receipt).
                new AlertDialog.Builder(this)
                        .setTitle(I18n.get(this,
                                StringsTable
                                        .DEPIN_TRANSFER_CONFIRM_ONESHOT_TITLE))
                        .setMessage(I18n.format(this,
                                StringsTable
                                        .DEPIN_TRANSFER_CONFIRM_ONESHOT_BODY,
                                currentReceiverDeviceId,
                                currentReceiverGeneration))
                        .setPositiveButton(I18n.get(this,
                                StringsTable.DEPIN_TRANSFER_CONFIRM_ONESHOT),
                                (d, w) -> transferOp(
                                        DepinIpc.T_OP_CONFIRM_ONESHOT,
                                        null, this::onConfirmOneshotReply))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                break;
            case ACT_CLOSE_ONESHOT: {
                // E4: the erasure requires the confirmation
                // number the RECEIVER displayed after its
                // confirm completed. A bare tap erases
                // nothing; a wrong number erases nothing (the
                // core checks it against the stored key).
                final android.widget.EditText codeInput =
                        new android.widget.EditText(this);
                codeInput.setInputType(
                        android.text.InputType.TYPE_CLASS_NUMBER);
                codeInput.setHint("12345678");
                new AlertDialog.Builder(this)
                        .setTitle(I18n.get(this,
                                StringsTable
                                        .DEPIN_TRANSFER_CLOSE_ONESHOT_TITLE))
                        .setMessage(I18n.format(this,
                                StringsTable
                                        .DEPIN_TRANSFER_CLOSE_ONESHOT_BODY,
                                currentSenderDeviceId))
                        .setView(codeInput)
                        .setPositiveButton(I18n.get(this,
                                StringsTable.DEPIN_TRANSFER_CLOSE_ONESHOT),
                                (d, w) -> {
                                    Bundle params = new Bundle();
                                    params.putString(
                                            DepinIpc.KEY_T_COMMIT_CODE,
                                            codeInput.getText().toString()
                                                    .replace(" ", ""));
                                    transferOp(
                                            DepinIpc.T_OP_CLOSE_ONESHOT_SENDER,
                                            params, dd -> {
                                                new AlertDialog.Builder(this)
                                                        .setMessage(I18n.get(this,
                                                                StringsTable
                                                                        .DEPIN_TRANSFER_ONESHOT_CLOSED_MSG))
                                                        .setPositiveButton(
                                                                android.R.string.ok,
                                                                null)
                                                        .show();
                                            });
                                })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                break;
            }
            case ACT_REEXPORT_ONESHOT:
                // E7: a fresh file + code from the same frozen
                // tip; the previous file remains valid.
                transferOp(DepinIpc.T_OP_EXPORT_PACKAGE_ONESHOT_AGAIN,
                        null, this::onExportReplyAgain);
                break;
            case ACT_CANCEL_ONESHOT:
                new AlertDialog.Builder(this)
                        .setTitle(I18n.get(this,
                                StringsTable
                                        .DEPIN_TRANSFER_CANCEL_ONESHOT_TITLE))
                        .setMessage(I18n.get(this,
                                StringsTable
                                        .DEPIN_TRANSFER_CANCEL_ONESHOT_BODY))
                        .setPositiveButton(I18n.get(this,
                                StringsTable.DEPIN_TRANSFER_CANCEL_ONESHOT),
                                (d, w) -> transferOp(
                                        DepinIpc.T_OP_CANCEL_ONESHOT_SENDER,
                                        null, null))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                break;
            case ACT_ISSUE_PERMIT:
                transferOp(DepinIpc.T_OP_ISSUE_PERMIT, null,
                        this::saveStagedOutput);
                break;
            case ACT_CANCEL_TRANSFER:
                transferOp(DepinIpc.T_OP_CANCEL_SENDER, null,
                        this::saveStagedOutput);
                break;
            default:
                break;
        }
    }

    private void openTransferDocument(int kind) {
        pendingTransferOpen = kind;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        startActivityForResult(intent, REQ_TRANSFER_OPEN);
    }

    private void saveTransferDocument(String name,
                                      java.io.File source) {
        pendingTransferSaveFile = source;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(intent, REQ_TRANSFER_SAVE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EXPORT_SAVE) {
            // Bundle export (step 5): a cancelled picker drops
            // the staged bundle, like the companion clears its
            // pending export; a picked destination receives it.
            if (resultCode != Activity.RESULT_OK || data == null
                    || data.getData() == null) {
                DepinBundleExport.discardStaged(this);
                setExportBusy(false);
                return;
            }
            finishBundleSave(data.getData());
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQ_TRANSFER_SAVE) {
            final java.io.File source = pendingTransferSaveFile;
            pendingTransferSaveFile = null;
            if (source == null) return;
            // Stream the staged file to the picked destination
            // (the pending-bundle save pattern): plain file I/O
            // on the UI work executor, no transfer operation.
            transferBusy = true;
            applyTransferButtonStates();
            depinWorkExecutor.execute(() -> {
                try (java.io.InputStream in =
                             new java.io.FileInputStream(source);
                     java.io.OutputStream out =
                             getContentResolver().openOutputStream(uri)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    out.flush();
                } catch (Throwable t) {
                    final String msg = t.getClass().getSimpleName()
                            + ": " + t.getMessage();
                    runOnUiThread(() -> {
                        transferBusy = false;
                        captureTransferError(
                                app.quantusnode.node.depin
                                        .TransferErrorReport.PHASE_FILE,
                                null, null,
                                app.quantusnode.node.depin
                                        .TransferErrorReport.classify(t));
                        showTransferFailure(msg);
                    });
                    return;
                }
                runOnUiThread(() -> {
                    transferBusy = false;
                    refreshTransferState();
                });
            });
            return;
        }
        if (requestCode != REQ_TRANSFER_OPEN) return;
        final int kind = pendingTransferOpen;
        if (kind == T_OPEN_PACKAGE && data != null
                && data.getData() != null) {
            pickedPackageUri = data.getData();
        }
        // Read the picked file (with the same size cap as the
        // in-process flow) on the UI work executor, stage it as
        // the :depin process's input, then dispatch the picked
        // kind's operation. Failures of the read itself land on
        // the state line exactly as the in-process catch did.
        transferBusy = true;
        applyTransferButtonStates();
        depinWorkExecutor.execute(() -> {
            try {
                byte[] raw;
                try (java.io.InputStream in =
                             getContentResolver().openInputStream(uri)) {
                    java.io.ByteArrayOutputStream bos =
                            new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    long total = 0;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > app.quantusnode.node.depin.core
                                .TransferPackage.MAX_PACKAGE_BYTES) {
                            throw new IllegalStateException(
                                    "file exceeds the transfer size limit");
                        }
                        bos.write(buf, 0, n);
                    }
                    raw = bos.toByteArray();
                }
                app.quantusnode.node.depin.core.TransferIo
                        .writeFileSync(
                                DepinFiles.transferInputFile(this), raw);
            } catch (Throwable t) {
                final String msg = t.getClass().getSimpleName()
                        + ": " + t.getMessage();
                runOnUiThread(() -> {
                    transferBusy = false;
                    captureTransferError(
                            app.quantusnode.node.depin
                                    .TransferErrorReport.PHASE_FILE,
                            null, null,
                            app.quantusnode.node.depin
                                    .TransferErrorReport.classify(t));
                    showTransferFailure(msg);
                });
                return;
            }
            runOnUiThread(() -> {
                transferBusy = false;
                dispatchPicked(kind);
            });
        });
    }

    /** Dispatch the operation for a picked file whose bytes
     *  are already staged as the transfer input. */
    private void dispatchPicked(int kind) {
        switch (kind) {
            case T_OPEN_PACKAGE:
                handlePickedPackage();
                break;
            case T_OPEN_PERMIT:
                transferOp(DepinIpc.T_OP_INTAKE_PERMIT, null, null);
                break;
            case T_OPEN_ACK:
                transferOp(DepinIpc.T_OP_INTAKE_ACK, null, null);
                break;
            case T_OPEN_NOTICE:
                transferOp(DepinIpc.T_OP_INTAKE_NOTICE, null, null);
                break;
            case T_OPEN_RECEIPT:
                transferOp(DepinIpc.T_OP_INTAKE_RECEIPT, null,
                        this::saveStagedOutput);
                break;
            case T_OPEN_ABANDON:
                transferOp(DepinIpc.T_OP_INTAKE_ABANDON, null, null);
                break;
            case T_OPEN_CANCEL_TOKEN:
                transferOp(DepinIpc.T_OP_INTAKE_CANCEL_TOKEN,
                        null, null);
                break;
            default:
                break;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("transfer_pending_open", pendingTransferOpen);
        if (pendingTransferSaveFile != null) {
            outState.putString("transfer_pending_save",
                    pendingTransferSaveFile.getAbsolutePath());
        }
    }

    /** Package picked (its bytes are already staged as the
     *  transfer input): ask for the code, stage it in the
     *  :depin process, show the full-value confirmation; only
     *  the proceed button creates the notified R1 (design
     *  sections 4.3, 5.3). */
    private void handlePickedPackage() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle(I18n.get(this,
                        StringsTable.DEPIN_TRANSFER_CODE_PROMPT))
                .setView(input)
                .setPositiveButton(android.R.string.ok,
                        (d, w) -> {
                            Bundle params = new Bundle();
                            params.putString(DepinIpc.KEY_T_CODE,
                                    input.getText().toString());
                            transferOp(DepinIpc.T_OP_STAGE_PACKAGE,
                                    params, this::onStageReply);
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** A finished stage's reply, shared by the fresh intake and
     *  the resume: hold the result for the confirmation dialog
     *  and show the full-value confirmation. */
    private void onStageReply(Bundle data) {
        app.quantusnode.node.depin.core.TransferReceiver.IntakeResult info =
                new app.quantusnode.node.depin.core.TransferReceiver
                        .IntakeResult();
        info.deviceId = data.getString(DepinIpc.KEY_T_DEVICE_ID);
        info.generation = data.getLong(DepinIpc.KEY_T_GENERATION);
        info.packageSha256 = data.getString(
                DepinIpc.KEY_T_PACKAGE_SHA256);
        info.checkpointCount = data.getLong(DepinIpc.KEY_T_CHECKPOINTS);
        info.firstTimestampUnix = data.getLong(DepinIpc.KEY_T_FIRST_TS);
        info.tailTimestampUnix = data.getLong(DepinIpc.KEY_T_TAIL_TS);
        pendingStaged = info;
        showStagedConfirmation(info);
    }

    /** Resume an interrupted intake (the STAGED_UNNOTIFIED main
     *  action): copy the staging's retained package copy into
     *  the IPC input slot and re-issue the stage. The core
     *  takes its resume branch — no re-expansion, the transfer
     *  code is not consulted, verification continues from the
     *  durable section 3.5 cursor — and the full-value
     *  confirmation dialog follows as usual. This is the route
     *  that did not exist when a paused or interrupted stage
     *  stranded the first production attempt: the staging was
     *  durable on disk, but only volatile Activity state led
     *  back to it. */
    private void resumeStagedIntake() {
        final TransferFlow.StagingRef ref = currentUnnotifiedStaging;
        if (ref == null || ref.packageFile == null) {
            refreshTransferState();
            return;
        }
        transferBusy = true;
        applyTransferButtonStates();
        depinWorkExecutor.execute(() -> {
            try {
                byte[] raw = app.quantusnode.node.depin.core.TransferIo
                        .readAll(ref.packageFile,
                                app.quantusnode.node.depin.core
                                        .TransferPackage.MAX_PACKAGE_BYTES);
                app.quantusnode.node.depin.core.TransferIo.writeFileSync(
                        DepinFiles.transferInputFile(this), raw);
            } catch (Throwable t) {
                final String msg = t.getClass().getSimpleName()
                        + ": " + t.getMessage();
                runOnUiThread(() -> {
                    transferBusy = false;
                    captureTransferError(
                            app.quantusnode.node.depin
                                    .TransferErrorReport.PHASE_FILE,
                            null, "receiver",
                            app.quantusnode.node.depin
                                    .TransferErrorReport.classify(t));
                    showTransferFailure(msg);
                });
                return;
            }
            runOnUiThread(() -> {
                transferBusy = false;
                Bundle params = new Bundle();
                params.putString(DepinIpc.KEY_T_CODE, "");
                transferOp(DepinIpc.T_OP_STAGE_PACKAGE, params,
                        this::onStageReply);
            });
        });
    }

    private void showStagedConfirmation(
            app.quantusnode.node.depin.core.TransferReceiver.IntakeResult info) {
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm", java.util.Locale.US);
        String body = I18n.format(this,
                StringsTable.DEPIN_TRANSFER_CONFIRM_BODY,
                info.deviceId,
                info.checkpointCount,
                fmt.format(new java.util.Date(info.firstTimestampUnix * 1000L)),
                fmt.format(new java.util.Date(info.tailTimestampUnix * 1000L)),
                info.packageSha256);
        // Audit M1: with a DIFFERENT identity live on this
        // device, the simple (one-shot) choice can only be
        // refused at confirm time (E8) — so the dialog does
        // not offer it and says why, pointing at the strict
        // proceed button, which quarantines the live identity
        // safely instead of displacing it.
        final boolean oneshotBlocked = currentOneshotBlockedByLive
                || (currentTransferActiveDeviceId != null
                        && !currentTransferActiveDeviceId
                                .equals(info.deviceId));
        if (oneshotBlocked) {
            body = body + "\n\n" + I18n.get(this,
                    StringsTable.DEPIN_TRANSFER_ONESHOT_BLOCKED_LIVE);
        }
        final String dialogBody = body;
        runOnUiThread(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(I18n.get(this,
                        StringsTable.DEPIN_TRANSFER_CONFIRM_TITLE))
                .setMessage(dialogBody)
                .setPositiveButton(I18n.get(this,
                        StringsTable.DEPIN_TRANSFER_PROCEED),
                        (d, w) -> {
                            app.quantusnode.node.depin.core.TransferReceiver
                                    .IntakeResult cur = pendingStaged;
                            if (cur == null) {
                                // The Activity was recreated while
                                // the dialog was up and the held
                                // result went with it. The staged
                                // intake itself is durable: resume
                                // it from the retained copy (which
                                // re-shows this confirmation) instead
                                // of silently dropping the tap — the
                                // exact dead end the first production
                                // attempt hit.
                                resumeStagedIntake();
                                return;
                            }
                            Bundle params = new Bundle();
                            params.putString(DepinIpc.KEY_T_DEVICE_ID,
                                    cur.deviceId);
                            params.putLong(DepinIpc.KEY_T_GENERATION,
                                    cur.generation);
                            params.putString(
                                    DepinIpc.KEY_T_PACKAGE_SHA256,
                                    cur.packageSha256);
                            pendingStaged = null;
                            transferOp(DepinIpc.T_OP_COMPLETE_INTAKE,
                                    params, null);
                        });
            if (!oneshotBlocked) {
                builder.setNeutralButton(I18n.get(this,
                        StringsTable.DEPIN_TRANSFER_CONFIRM_ONESHOT),
                        (d, w) -> {
                            // The simple mode's confirm, taken on
                            // this same all-values dialog: the
                            // service completes the intake and
                            // confirms in one operation — no
                            // notice, permit, receipt or ack.
                            app.quantusnode.node.depin.core.TransferReceiver
                                    .IntakeResult cur = pendingStaged;
                            if (cur == null) {
                                resumeStagedIntake();
                                return;
                            }
                            Bundle params = new Bundle();
                            params.putString(DepinIpc.KEY_T_DEVICE_ID,
                                    cur.deviceId);
                            params.putLong(DepinIpc.KEY_T_GENERATION,
                                    cur.generation);
                            params.putString(
                                    DepinIpc.KEY_T_PACKAGE_SHA256,
                                    cur.packageSha256);
                            pendingStaged = null;
                            transferOp(DepinIpc.T_OP_CONFIRM_ONESHOT,
                                    params, this::onConfirmOneshotReply);
                        });
            }
            builder.setNegativeButton(I18n.get(this,
                    StringsTable.DEPIN_TRANSFER_ABORT), null)
                    .show();
        });
    }
}
