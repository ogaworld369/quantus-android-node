package app.quantusnode.node;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Dashboard, versionCode 26 redesign: four screens in one activity —
 * home (the pulse, the state, the one primary action), node details
 * (exact heights, sync gap, diagnostics), the track
 * record, and settings — switched by swapping root views, with no
 * transition animation. No cards: background and spacing group the
 * content; surfaces appear only on buttons, fields, dialogs and the
 * failure supplement.
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
    /** Dialog surface (redesign spec, audit finding L-7). */
    private static final int DIALOG_BG = Color.rgb(0x18, 0x18, 0x18);
    private static final int RIPPLE_ACCENT =
            Color.argb(0x1F, 0xFF, 0x6B, 0x35); // flare at 12%
    private static final int REQ_NOTIF = 42;

    // ---- Screens ------------------------------------------------------
    private static final int SCREEN_HOME = 0;
    private static final int SCREEN_DETAILS = 1;
    private static final int SCREEN_TRACK = 2;
    private static final int SCREEN_SETTINGS = 3;

    private final ScrollView[] screens = new ScrollView[4];
    private int currentScreen = SCREEN_HOME;

    // Home
    private PulseView pulseView;
    private TextView statusView;
    private TextView supplementView;
    private TextView detailLinkView;
    private Button mainButton;
    private TextView pendingLinkView;
    private LinearLayout homeStatsBox;
    private TextView peersValue;
    private TextView sessionValue;

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
                final ScrollView sv = screens[i];
                final int y = ys[i];
                if (sv == null) {
                    continue;
                }
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
        // back goes home; home back is the platform default and never
        // sends a stop request (spec G).
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

    private void showScreen(int screen) {
        currentScreen = screen;
        for (int i = 0; i < screens.length; i++) {
            if (screens[i] != null) {
                screens[i].setVisibility(
                        i == screen ? View.VISIBLE : View.GONE);
            }
        }
        if (screen == SCREEN_DETAILS && scrollToDiagnosticsOnce) {
            scrollToDiagnosticsOnce = false;
            diagSection.post(() -> {
                ScrollView sv = screens[SCREEN_DETAILS];
                int y = offsetInContent(diagSection, sv.getChildAt(0));
                sv.scrollTo(0, Math.max(0, y - dp(8)));
            });
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

    /** Accent fill (start / retry). */
    private android.graphics.drawable.Drawable accentButtonBg() {
        if (mainBtnAccentBg == null) {
            float d = getResources().getDisplayMetrics().density;
            mainBtnAccentBg = ripple(rounded(ACCENT, 12, d, 0, 0));
        }
        return mainBtnAccentBg;
    }

    /** Bordered surface (stop / cancel startup). */
    private android.graphics.drawable.Drawable surfaceButtonBg() {
        if (mainBtnSurfaceBg == null) {
            float d = getResources().getDisplayMetrics().density;
            mainBtnSurfaceBg = ripple(rounded(SURFACE, 12, d, FIELD_BORDER, 1));
        }
        return mainBtnSurfaceBg;
    }

    /** Plain surface, no press ripple (stopping, disabled). */
    private android.graphics.drawable.Drawable stoppingButtonBg() {
        if (mainBtnStoppingBg == null) {
            float d = getResources().getDisplayMetrics().density;
            mainBtnStoppingBg = rounded(SURFACE, 12, d, 0, 0);
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
        back.setOnClickListener(v -> showScreen(SCREEN_HOME));
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

    private ScrollView buildHome(FrameLayout container, int dir) {
        LinearLayout root = screenContent(container, SCREEN_HOME, dir);

        // Header: app name + settings icon (48 dp target, all states).
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(56));
        TextView title = text("Quantus Node", 20, LABEL1, true);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        SettingsIconView settingsIcon = new SettingsIconView(this);
        settingsIcon.setContentDescription(
                I18n.get(this, StringsTable.SECTION_SETTINGS));
        settingsIcon.setBackground(ripple(null));
        settingsIcon.setOnClickListener(v -> showScreen(SCREEN_SETTINGS));
        header.addView(settingsIcon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(header, matchLp());

        // The pulse, centred, 160 dp.
        pulseView = new PulseView(this);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(160), dp(160));
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        plp.topMargin = dp(16);
        root.addView(pulseView, plp);

        statusView = text("", 28, LABEL1, true);
        statusView.setGravity(Gravity.CENTER);
        root.addView(statusView, matchLpTop(16));

        supplementView = text("", 14, LABEL3, false);
        supplementView.setGravity(Gravity.CENTER);
        root.addView(supplementView, matchLpTop(8));

        detailLinkView = text("", 14, ACCENT, true);
        detailLinkView.setGravity(Gravity.CENTER);
        detailLinkView.setBackground(ripple(null));
        detailLinkView.setClickable(true);
        detailLinkView.setFocusable(true);
        detailLinkView.setPadding(0, dp(8), 0, dp(8));
        detailLinkView.setOnClickListener(v -> openDiagnosticsFromHome());
        root.addView(detailLinkView, matchLpTop(8));

        // The one primary action.
        mainButton = new Button(this);
        mainButton.setTextSize(16);
        mainButton.setTypeface(
                Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mainButton.setAllCaps(false);
        mainButton.setStateListAnimator(null);
        mainButton.setMinHeight(dp(56));
        mainButton.setPadding(dp(16), dp(14), dp(16), dp(14));
        mainButton.setOnClickListener(v -> onMainButtonClicked());
        mainButton.setFocusableInTouchMode(false);
        LinearLayout.LayoutParams mlp = matchLpTop(24);
        mlp.height = LinearLayout.LayoutParams.WRAP_CONTENT;
        root.addView(mainButton, mlp);

        pendingLinkView = text("", 14, ACCENT, true);
        pendingLinkView.setGravity(Gravity.CENTER);
        pendingLinkView.setBackground(ripple(null));
        pendingLinkView.setClickable(true);
        pendingLinkView.setFocusable(true);
        pendingLinkView.setPadding(0, dp(8), 0, dp(8));
        pendingLinkView.setOnClickListener(v -> openPendingSettings());
        root.addView(pendingLinkView, matchLpTop(16));

        // Live stats for the running states only (spec H).
        homeStatsBox = new LinearLayout(this);
        homeStatsBox.setOrientation(LinearLayout.VERTICAL);
        peersValue = infoRow(homeStatsBox,
                I18n.get(this, StringsTable.LABEL_PEERS), false);
        sessionValue = infoRow(homeStatsBox,
                I18n.get(this, StringsTable.LABEL_UPTIME_SESSION), true);
        root.addView(homeStatsBox, matchLpTop(24));

        navRow(root, I18n.get(this, StringsTable.SCREEN_NODE_DETAILS), 24,
                v -> showScreen(SCREEN_DETAILS));
        navRow(root, I18n.get(this, StringsTable.SECTION_TRACK), 0,
                v -> showScreen(SCREEN_TRACK));
        return screens[SCREEN_HOME];
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
        return screens[SCREEN_DETAILS];
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
        return screens[SCREEN_TRACK];
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
        return screens[SCREEN_SETTINGS];
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
            pulseView.clearRipples();
            Intent stop = new Intent(this, NodeService.class)
                    .setAction(NodeService.ACTION_STOP);
            startService(stop);
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
        screens[SCREEN_SETTINGS].post(() -> screens[SCREEN_SETTINGS]
                .scrollTo(0, Math.max(0, offsetInContent(
                        target, screens[SCREEN_SETTINGS].getChildAt(0)) - dp(24))));
    }

    /**
     * Share the node's log file as plain text through the system
     * share sheet — unchanged from the pre-redesign UI: the read
     * runs off the UI thread through NodeService's coordinated
     * bounded reader, and only ever fires on the user's own tap.
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
            runOnUiThread(() -> shareLog(logText, wasTruncated));
        }, "share-log-read").start();
    }

    /** UI-thread half of share-log: the "no log" toast, or the sheet. */
    private void shareLog(String logText, boolean wasTruncated) {
        if (logText == null || logText.trim().isEmpty()) {
            Toast.makeText(this, I18n.get(this, StringsTable.LOG_MISSING_NOTE),
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (wasTruncated) {
            logText = "[... earlier log omitted for sharing; "
                    + "showing the last part ...]\n"
                    + logText;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT,
                I18n.get(this, StringsTable.SHARE_LOG_SUBJECT));
        send.putExtra(Intent.EXTRA_TEXT, logText);
        startActivity(Intent.createChooser(send,
                I18n.get(this, StringsTable.BUTTON_SHARE_LOG)));
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
                sScrollAfterRecreate[i] = screens[i] != null
                        ? screens[i].getScrollY() : 0;
            }
            sAnchorIdAfterRecreate = -1;
            sAnchorOffsetAfterRecreate = 0;
            ScrollView ssv = screens[SCREEN_SETTINGS];
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

        refreshHome(st, tag, hs, now);
        refreshDetails(st, tag, hs, now);
        trackUptimeValue.setText(UiLogic.durationText(
                tag, TrackStore.uptimeMs(this), true));
        trackBlocksValue.setText(
                UiLogic.grouped(TrackStore.blockUpdates(this)));
        trackPeersValue.setText(
                UiLogic.grouped(TrackStore.maxPeers(this)));
        refreshSettings(st);
        firstRefresh = false;
    }

    private void refreshHome(NodeState st, String tag,
                             UiLogic.HomeState hs, long now) {
        // The pulse core follows the presentation.
        switch (hs) {
            case STARTING:
                pulseView.setState(STATE_STARTING, false);
                break;
            case SYNCING:
                pulseView.setState(STATE_SYNCING, false);
                break;
            case SYNCED:
                pulseView.setState(STATE_SYNCED, false);
                break;
            case FAILED_START:
            case FAILED_UNEXPECTED:
            case FAILED_BINARY:
                pulseView.setState(STATE_FAILED, false);
                break;
            case STOPPED:
            case STOPPED_TIME_LIMIT:
            default:
                pulseView.setState(STATE_STOPPED, true);
                break;
        }
        // One ripple per newly recorded observation while SYNCED,
        // measured against the display-start baseline (finding
        // L-1): the baseline was adopted as a value when the UI
        // (re)started displaying, so the first increase after a
        // cold start fires, and a rebuild never manufactures one.
        if (st.lastObservedBestBlock >= 0) {
            if (UiLogic.shouldRipple(sRippleBaselineReady, firstRefresh,
                    hs == UiLogic.HomeState.SYNCED,
                    currentScreen == SCREEN_HOME,
                    st.lastObservedBestBlock, sLastRippleObservedBest)) {
                pulseView.pulse();
            }
            if (st.lastObservedBestBlock > sLastRippleObservedBest) {
                sLastRippleObservedBest = st.lastObservedBestBlock;
            }
        }

        // Status name.
        String statusKey;
        switch (hs) {
            case STARTING:
                statusKey = StringsTable.STATUS_STARTING;
                break;
            case SYNCING:
                statusKey = StringsTable.STATUS_SYNCING;
                break;
            case SYNCED:
                statusKey = StringsTable.STATUS_SYNCED;
                break;
            case FAILED_START:
            case FAILED_BINARY:
                statusKey = StringsTable.STATUS_START_FAILED;
                break;
            case FAILED_UNEXPECTED:
                statusKey = StringsTable.STATUS_UNEXPECTED_STOP;
                break;
            case STOPPED:
            case STOPPED_TIME_LIMIT:
            default:
                statusKey = StringsTable.STATUS_STOPPED;
                break;
        }
        setTextIfChanged(statusView, I18n.get(this, statusKey));

        // Supplement + cause-specific detail link.
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
                String gap = UiLogic.syncGapText(tag, st.bestBlock, st.highestBlock);
                supplement = gap != null ? gap
                        : I18n.get(this, StringsTable.MESSAGE_SYNC_TARGET_UNKNOWN);
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

        // The primary button: wording and dress per presentation,
        // with the stopping flag overriding while a stop settles.
        // The three backgrounds are built once and cached (finding
        // L-5); the text still goes through the state wording.
        if (stopping && st.serviceRunning) {
            mainButton.setText(I18n.get(this, StringsTable.BUTTON_STOPPING));
            mainButton.setEnabled(false);
            mainButton.setBackground(stoppingButtonBg());
            mainButton.setTextColor(LABEL3);
        } else {
            mainButton.setEnabled(true);
            switch (hs) {
                case STOPPED:
                case STOPPED_TIME_LIMIT:
                    mainButton.setText(I18n.get(this, StringsTable.BUTTON_START_NODE));
                    mainButton.setBackground(accentButtonBg());
                    mainButton.setTextColor(ON_ACCENT);
                    break;
                case FAILED_START:
                case FAILED_UNEXPECTED:
                case FAILED_BINARY:
                    mainButton.setText(I18n.get(this, StringsTable.BUTTON_RETRY));
                    mainButton.setBackground(accentButtonBg());
                    mainButton.setTextColor(ON_ACCENT);
                    break;
                case STARTING:
                    mainButton.setText(
                            I18n.get(this, StringsTable.BUTTON_CANCEL_STARTUP));
                    mainButton.setBackground(surfaceButtonBg());
                    mainButton.setTextColor(LABEL1);
                    break;
                case SYNCING:
                case SYNCED:
                default:
                    mainButton.setText(I18n.get(this, StringsTable.BUTTON_STOP_NODE));
                    mainButton.setBackground(surfaceButtonBg());
                    mainButton.setTextColor(LABEL1);
                    break;
            }
        }

        // Stats rows: running presentations only (spec H matrix).
        boolean stats = UiLogic.showsStats(hs);
        homeStatsBox.setVisibility(stats ? View.VISIBLE : View.GONE);
        if (stats) {
            peersValue.setText(st.peers >= 0
                    ? UiLogic.grouped(st.peers)
                    : I18n.get(this, StringsTable.VALUE_UNAVAILABLE));
            sessionValue.setText(st.sessionAcceptedElapsedMs >= 0
                    ? UiLogic.durationText(tag,
                            now - st.sessionAcceptedElapsedMs, false)
                    : I18n.get(this, StringsTable.VALUE_UNAVAILABLE));
        }

        // Pending-settings link (condition P).
        int pending = UiLogic.settingsPending(st.serviceRunning,
                st.launchedTelemetry, NodeSettings.telemetryEnabled(this),
                st.launchedNodeName, NodeSettings.effectiveNodeName(this));
        boolean showPending = pending != 0 && stats;
        pendingLinkView.setVisibility(showPending ? View.VISIBLE : View.GONE);
        if (showPending) {
            setTextIfChanged(pendingLinkView,
                    I18n.get(this, StringsTable.SETTINGS_PENDING_LINK));
        }
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
    }

    /** Skip a setText when the text is unchanged (avoids re-measure churn). */
    private static void setTextIfChanged(TextView v, String s) {
        CharSequence cur = v.getText();
        if (cur == null || !cur.toString().equals(s)) {
            v.setText(s);
        }
    }
}
