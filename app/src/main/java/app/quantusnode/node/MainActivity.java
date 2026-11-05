package app.quantusnode.node;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/**
 * Dashboard. Dark, minimal, quiet — the node's presence ("気配"), not a
 * control panel shouting for attention. The layout is a stack of dark
 * rounded cards on pure black — iOS grouped-list discipline (hairline
 * rows, generous margins, few colours) over the same quiet concept.
 *
 * Shows: status, best/highest block, peers; a start/stop button; the
 * on-device "航跡" (track) record; the "量子ウォッチ" (quantum watch)
 * frame, whose external-threat half is an honest stub for now; and the
 * settings — the telemetry opt-in (default off, with what it reveals
 * stated next to the switch), the node's display name, the UI
 * language (9 languages, "follow device" by default — see
 * {@link StringsTable} / {@link I18n}), and a share-log button: the
 * log file lives in the app-private directory, so sharing it out is
 * a deliberate tap, never automatic. After a failed start, the note
 * in the details card at the bottom shows each launch attempt's
 * record (method, exit code, lived time, and what that attempt's
 * process printed).
 *
 * All data comes from the node on this phone via local RPC (through
 * NodeService / NodeState). Nothing here talks to the outside.
 */
public class MainActivity extends Activity {

    // ---- Visual style ------------------------------------------------
    // iOS dark-mode grouped-list discipline over the existing "気配"
    // (quiet presence) concept: pure-black background, one card tone,
    // hairline separators, generous margins, very few colours.
    private static final int BG = Color.rgb(0, 0, 0);
    private static final int CARD_BG = Color.rgb(0x1C, 0x1C, 0x1E);
    private static final int FIELD_BG = Color.rgb(0x2C, 0x2C, 0x2E);
    private static final int TEXT = Color.rgb(0xFF, 0xFF, 0xFF);
    private static final int SECONDARY = Color.rgb(0x98, 0x98, 0x9F);
    private static final int TERTIARY = Color.rgb(0x63, 0x63, 0x66);
    private static final int HAIRLINE = Color.rgb(0x38, 0x38, 0x3A);
    private static final int ACCENT = Color.rgb(0x30, 0xD1, 0x58);
    private static final int PILL_DARK = Color.rgb(0x2C, 0x2C, 0x2E);
    /** Muted amber, used only for the "restart to apply settings" note. */
    private static final int NOTE = Color.rgb(198, 160, 92);
    private static final int REQ_NOTIF = 42;

    private PulseView pulseView;
    private TextView statusView;
    private TextView bestView;
    private TextView highestView;
    private TextView peersView;
    private TextView noteView;
    private Button toggleButton;
    private GradientDrawable startPill;
    private GradientDrawable stopPill;
    private TextView trackUptimeView;
    private TextView trackBlocksView;
    private TextView trackPeersView;
    private TextView watchStatusView;
    private TextView watchPeersView;
    private TextView watchBlocksView;
    private Switch telemetrySwitch;
    private EditText nodeNameInput;
    private Button languageButton;
    private Switch settingsLockSwitch;
    private TextView restartNoteView;
    private ScrollView scrollView;
    /** Last text actually set on noteView / restartNoteView. */
    private String lastNoteText;
    private String lastRestartNoteText;
    /** Scroll offset carried across a language-change recreate(). */
    private static int scrollYAfterRecreate = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastBestSeen = -1;
    private boolean firstRefresh = true;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Layout direction follows the UI language (Arabic is RTL).
        // Set it on the window before building, and again on the view
        // tree roots in buildUi, so START/END gravities and horizontal
        // rows mirror instead of breaking.
        int dir = I18n.isRtl(this)
                ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR;
        getWindow().getDecorView().setLayoutDirection(dir);
        buildUi();
        refresh();
        // A language change recreates the activity; if the user
        // was scrolled down when they picked the language, land
        // back at the same offset instead of the top.
        if (scrollYAfterRecreate > 0) {
            final int y = scrollYAfterRecreate;
            scrollYAfterRecreate = 0;
            restoreScrollLater(y);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refresher);
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
    }

    // ------------------------------------------------------------------
    // UI construction (programmatic — no resource files, by design)
    // ------------------------------------------------------------------

    private void buildUi() {
        float d = getResources().getDisplayMetrics().density;
        int dir = I18n.isRtl(this)
                ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR;

        // Screen: pure black, 16 dp side margins; the content is a
        // stack of rounded cards 12 dp apart (iOS grouped-list shape).
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (16 * d), (int) (20 * d),
                (int) (16 * d), (int) (24 * d));
        root.setBackgroundColor(BG);
        root.setLayoutDirection(dir);

        // Header — the app name and one quiet line, above the cards.
        TextView title = text("Quantus Node", 22, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        TextView sub = text(I18n.get(this, StringsTable.APP_SUBTITLE), 12, TERTIARY);
        root.addView(sub, matchLp(d, 2));

        // Status card — the pulse and the state itself, nothing else.
        // (The per-attempt start records that used to sit here live in
        // the details card at the bottom; see below.)
        LinearLayout statusCard = card(d);
        addBlock(root, statusCard, d, 16);
        pulseView = new PulseView(this);
        statusCard.addView(pulseView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (110 * d)));
        statusView = text(I18n.get(this, StringsTable.STATUS_STOPPED), 26, TEXT);
        statusView.setGravity(Gravity.CENTER);
        statusCard.addView(statusView, matchLp(d, 4));

        // Stats card — the live numbers as label/value rows.
        LinearLayout statsCard = card(d);
        addBlock(root, statsCard, d, 12);
        bestView = statRow(statsCard, d,
                I18n.get(this, StringsTable.LABEL_BEST_BLOCK), true);
        highestView = statRow(statsCard, d,
                I18n.get(this, StringsTable.LABEL_HIGHEST_BLOCK), false);
        peersView = statRow(statsCard, d,
                I18n.get(this, StringsTable.LABEL_PEERS), false);

        // The one primary action: a full-width pill between the
        // cards. Green with black text to start, dark grey with
        // white text to stop; refresh() swaps the two prebuilt
        // backgrounds (and the label) with the service state.
        startPill = pill(ACCENT, d);
        stopPill = pill(PILL_DARK, d);
        toggleButton = new Button(this);
        toggleButton.setText(I18n.get(this, StringsTable.BUTTON_START_NODE));
        toggleButton.setTextSize(17);
        toggleButton.setTypeface(Typeface.DEFAULT_BOLD);
        toggleButton.setAllCaps(false);
        toggleButton.setPadding(0, 0, 0, 0);
        toggleButton.setStateListAnimator(null);
        toggleButton.setBackground(startPill);
        toggleButton.setTextColor(Color.BLACK);
        toggleButton.setOnClickListener(v -> onToggleClicked());
        // Not focusable in touch mode: a tapped control must not
        // become the anchor the ScrollView re-centres on when the
        // 1 s refresh re-lays out the page (see refresh()).
        toggleButton.setFocusableInTouchMode(false);
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (50 * d));
        toggleLp.topMargin = (int) (12 * d);
        root.addView(toggleButton, toggleLp);

        // 航跡 — this phone's own record, kept only on this phone.
        root.addView(sectionHeader(
                I18n.get(this, StringsTable.SECTION_TRACK), d, 20));
        LinearLayout trackCard = card(d);
        addBlock(root, trackCard, d, 6);
        trackUptimeView = statRow(trackCard, d,
                I18n.get(this, StringsTable.LABEL_UPTIME_TOTAL), true);
        trackBlocksView = statRow(trackCard, d,
                I18n.get(this, StringsTable.LABEL_BLOCKS_OBSERVED), false);
        trackPeersView = statRow(trackCard, d,
                I18n.get(this, StringsTable.LABEL_MAX_PEERS), false);

        // 量子ウォッチ — local/network health now; external threat intel
        // is deliberately not fetched yet (see the stub line in refresh()).
        root.addView(sectionHeader(
                I18n.get(this, StringsTable.SECTION_QUANTUM_WATCH), d, 20));
        LinearLayout watchCard = card(d);
        addBlock(root, watchCard, d, 6);
        watchStatusView = statRow(watchCard, d,
                I18n.get(this, StringsTable.LABEL_WATCH_OWN_SYNC), true);
        watchPeersView = statRow(watchCard, d,
                I18n.get(this, StringsTable.LABEL_WATCH_NETWORK), false);
        watchBlocksView = statRow(watchCard, d,
                I18n.get(this, StringsTable.LABEL_WATCH_BLOCKS), false);
        watchCard.addView(divider(d, 10));
        TextView stub = text(I18n.get(this, StringsTable.WATCH_THREAT_STUB), 12, TERTIARY);
        watchCard.addView(stub, matchLp(d, 10));

        // 設定 — telemetry is an opt-in (default OFF) and the display
        // name defaults to the generic app name. Both apply at the next
        // node start only; if they diverge from what the running node
        // was launched with, refresh() says so instead of restarting
        // anything silently. The language choice below, in contrast,
        // applies to this UI immediately (via recreate()).
        root.addView(sectionHeader(
                I18n.get(this, StringsTable.SECTION_SETTINGS), d, 20));
        LinearLayout settingsCard = card(d);
        addBlock(root, settingsCard, d, 6);

        telemetrySwitch = new Switch(this);
        telemetrySwitch.setText(I18n.get(this, StringsTable.SETTINGS_TELEMETRY));
        telemetrySwitch.setTextSize(15);
        telemetrySwitch.setTextColor(TEXT);
        telemetrySwitch.setTrackTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{ACCENT, Color.rgb(0x39, 0x39, 0x3D)}));
        telemetrySwitch.setThumbTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{Color.WHITE, Color.WHITE}));
        // Set the position before attaching the listener, so building
        // the UI does not itself "change" the setting.
        telemetrySwitch.setChecked(NodeSettings.telemetryEnabled(this));
        telemetrySwitch.setOnCheckedChangeListener((button, isChecked) -> {
            // The change rewrites the restart note via refresh(),
            // which can grow or shrink the settings card mid-page;
            // hold the scroll position across the whole handler,
            // not just inside refresh().
            runPreservingScroll(() -> {
                NodeSettings.setTelemetryEnabled(this, isChecked);
                refresh();
            });
        });
        telemetrySwitch.setFocusableInTouchMode(false);
        settingsCard.addView(telemetrySwitch, matchLp());

        TextView privacyNote = text(
                I18n.get(this, StringsTable.SETTINGS_TELEMETRY_NOTE), 12, TERTIARY);
        settingsCard.addView(privacyNote, matchLp(d, 4));

        settingsCard.addView(divider(d, 12));
        TextView nameLabel = text(
                I18n.get(this, StringsTable.SETTINGS_DISPLAY_NAME), 13, SECONDARY);
        settingsCard.addView(nameLabel, matchLp(d, 10));
        nodeNameInput = new EditText(this);
        nodeNameInput.setSingleLine(true);
        nodeNameInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        nodeNameInput.setHint(NodeSettings.DEFAULT_NODE_NAME);
        nodeNameInput.setTextSize(17);
        nodeNameInput.setTextColor(TEXT);
        nodeNameInput.setHintTextColor(TERTIARY);
        GradientDrawable fieldBg = new GradientDrawable();
        fieldBg.setColor(FIELD_BG);
        fieldBg.setCornerRadius(10 * d);
        nodeNameInput.setBackground(fieldBg);
        nodeNameInput.setPadding((int) (12 * d), (int) (10 * d),
                (int) (12 * d), (int) (10 * d));
        nodeNameInput.setText(NodeSettings.nodeNameRaw(this));
        nodeNameInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                NodeSettings.setNodeNameRaw(MainActivity.this, s.toString());
                refresh();
            }
        });
        settingsCard.addView(nodeNameInput, matchLp(d, 6));

        restartNoteView = text("", 12, NOTE);
        settingsCard.addView(restartNoteView, matchLp(d, 6));

        settingsCard.addView(divider(d, 12));
        // 言語 — stored on-device only; takes effect immediately via
        // recreate(), and the service notification follows the next
        // time it is rebuilt. Language names are shown in each
        // language's own script, as platforms conventionally do.
        LinearLayout langRow = new LinearLayout(this);
        langRow.setOrientation(LinearLayout.HORIZONTAL);
        langRow.setGravity(Gravity.CENTER_VERTICAL);
        langRow.setMinimumHeight((int) (44 * d));
        TextView langLabel = text(
                I18n.get(this, StringsTable.SETTINGS_LANGUAGE), 13, SECONDARY);
        langRow.addView(langLabel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        languageButton = textButton(currentLanguageLabel(), d);
        languageButton.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        languageButton.setOnClickListener(v -> showLanguageDialog());
        languageButton.setFocusableInTouchMode(false);
        langRow.addView(languageButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        settingsCard.addView(langRow, matchLp(d, 4));

        settingsCard.addView(divider(d, 4));
        // 設定ロック — a mis-tap guard for the settings above, for a
        // phone that sits somewhere running the node: while locked,
        // the telemetry switch, the name field and the language picker
        // are disabled. It is deliberately not an authentication
        // feature (no PIN), and it never locks node start/stop. Same
        // switch row as telemetry — the position shows the state at a
        // glance — with the purpose stated under it at all times, so
        // it needs no separate explanation elsewhere.
        settingsLockSwitch = new Switch(this);
        settingsLockSwitch.setText(I18n.get(this, StringsTable.SETTINGS_LOCK));
        settingsLockSwitch.setTextSize(15);
        settingsLockSwitch.setTextColor(TEXT);
        settingsLockSwitch.setTrackTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{ACCENT, Color.rgb(0x39, 0x39, 0x3D)}));
        settingsLockSwitch.setThumbTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{Color.WHITE, Color.WHITE}));
        // Set the position before attaching the listener, as with the
        // telemetry switch. The listener ignores positions that already
        // match the stored state, so applySettingsLock() syncing the
        // switch cannot re-enter it.
        settingsLockSwitch.setChecked(NodeSettings.settingsLocked(this));
        settingsLockSwitch.setOnCheckedChangeListener((button, isChecked) -> {
            if (isChecked != NodeSettings.settingsLocked(this)) {
                // applySettingsLock() flips the enabled state of
                // the whole settings block (and may re-sync this
                // switch) — state changes that re-lay out the page
                // around the user's scroll position. Capture the
                // position here, at the handler's entry, before any
                // of that runs, and restore it once it has all
                // settled; refresh()'s own save/restore never sees
                // this path, because the handler does not call it.
                runPreservingScroll(() -> {
                    NodeSettings.setSettingsLocked(this, isChecked);
                    applySettingsLock();
                });
            }
        });
        settingsLockSwitch.setFocusableInTouchMode(false);
        settingsCard.addView(settingsLockSwitch, matchLp());
        TextView lockNote = text(
                I18n.get(this, StringsTable.SETTINGS_LOCK_NOTE), 12, TERTIARY);
        settingsCard.addView(lockNote, matchLp(d, 4));
        applySettingsLock();

        // 詳細・ログ — the fine print lives at the bottom, out of the
        // way: the per-attempt start records (evidence after a failed
        // start — raw node output included, selectable so the user
        // can copy the exact text out) and the share-log action. The
        // node's whole log lives in the app-private directory and
        // cannot be reached from outside; the button is the one way
        // out, and it only ever fires on the user's own tap (nothing
        // is sent automatically, ever).
        LinearLayout detailsCard = card(d);
        addBlock(root, detailsCard, d, 20);
        noteView = text("", 12, SECONDARY);
        noteView.setGravity(Gravity.START);
        noteView.setLineSpacing(0, 1.35f);
        noteView.setTextIsSelectable(true);
        detailsCard.addView(noteView, matchLp());
        detailsCard.addView(divider(d, 10));
        Button shareLogButton = textButton(
                I18n.get(this, StringsTable.BUTTON_SHARE_LOG), d);
        shareLogButton.setOnClickListener(v -> onShareLogClicked());
        detailsCard.addView(shareLogButton, matchLp(d, 4));

        TextView foot = text(I18n.get(this, StringsTable.FOOTER_INFO), 12, TERTIARY);
        root.addView(foot, matchLp(d, 16));

        scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(BG);
        scrollView.setLayoutDirection(dir);
        scrollView.addView(root);
        setContentView(scrollView);
    }

    /** Enable or disable the settings controls to match the lock. */
    private void applySettingsLock() {
        boolean locked = NodeSettings.settingsLocked(this);
        if (telemetrySwitch != null) {
            telemetrySwitch.setEnabled(!locked);
        }
        if (nodeNameInput != null) {
            nodeNameInput.setEnabled(!locked);
        }
        if (languageButton != null) {
            languageButton.setEnabled(!locked);
        }
        if (settingsLockSwitch != null
                && settingsLockSwitch.isChecked() != locked) {
            // Sync the switch to the stored state. The listener sees
            // the stored state already matching and does nothing, so
            // this cannot loop.
            settingsLockSwitch.setChecked(locked);
        }
    }

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
        int checked = 0;
        for (int i = 0; i < tags.length; i++) {
            if (tags[i].equals(raw)) {
                checked = i + 1;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(I18n.get(this, StringsTable.SETTINGS_LANGUAGE))
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    // recreate() rebuilds the whole screen; carry
                    // the scroll position across it (onCreate
                    // restores it) so picking a language does not
                    // throw the user back to the top of the page.
                    scrollYAfterRecreate =
                            scrollView != null ? scrollView.getScrollY() : 0;
                    NodeSettings.setLanguageRaw(
                            this, which == 0 ? "" : tags[which - 1]);
                    dialog.dismiss();
                    recreate();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    /** Full-width layout params, optionally with a top margin. */
    private LinearLayout.LayoutParams matchLp() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchLp(float d, int topDp) {
        LinearLayout.LayoutParams lp = matchLp();
        lp.topMargin = (int) (topDp * d);
        return lp;
    }

    /** Add a full-width block (a card) to the root with a top margin. */
    private void addBlock(LinearLayout root, View v, float d, int topDp) {
        root.addView(v, matchLp(d, topDp));
    }

    /** A rounded dark card: the one surface everything lives on. */
    private LinearLayout card(float d) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * d);
        c.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD_BG);
        bg.setCornerRadius(16 * d);
        c.setBackground(bg);
        return c;
    }

    /** A pill background for the primary start/stop button. */
    private GradientDrawable pill(int color, float d) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(25 * d);
        return g;
    }

    /** A 1 px hairline separator, inset by the card's own padding. */
    private View divider(float d, int topDp) {
        View v = new View(this);
        v.setBackgroundColor(HAIRLINE);
        LinearLayout.LayoutParams lp = matchLp(d, topDp);
        lp.height = 1;
        v.setLayoutParams(lp);
        return v;
    }

    /** A section heading above its card, aligned with the card's text. */
    private TextView sectionHeader(String label, float d, int topDp) {
        TextView t = text(label, 13, SECONDARY);
        t.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (topDp * d);
        lp.setMarginStart((int) (16 * d));
        t.setLayoutParams(lp);
        return t;
    }

    /**
     * An iOS-style "label .... value" row inside a card, with a
     * hairline above it unless it is the card's first row; returns
     * the value view to update later.
     */
    private TextView statRow(LinearLayout card, float d, String label, boolean first) {
        if (!first) {
            card.addView(divider(d, 0));
        }
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight((int) (44 * d));
        TextView l = text(label, 13, SECONDARY);
        r.addView(l, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text("—", 17, TEXT);
        v.setGravity(Gravity.END);
        r.addView(v, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        card.addView(r, matchLp());
        return v;
    }

    /**
     * A quiet text button for secondary actions: no background, no
     * borders — just a label with a full-height touch target.
     */
    private Button textButton(String label, float d) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(15);
        b.setTextColor(SECONDARY);
        b.setAllCaps(false);
        b.setBackground(null);
        b.setPadding(0, 0, 0, 0);
        b.setMinimumWidth(0);
        b.setMinimumHeight((int) (44 * d));
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setStateListAnimator(null);
        return b;
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void onToggleClicked() {
        if (NodeState.get().serviceRunning) {
            Intent stop = new Intent(this, NodeService.class)
                    .setAction(NodeService.ACTION_STOP);
            startService(stop);
        } else {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            }
            Intent start = new Intent(this, NodeService.class)
                    .setAction(NodeService.ACTION_START);
            startForegroundService(start);
        }
        refresh();
    }

    /**
     * Share the node's log file (filesDir/logs/node.log, the same
     * size-capped file NodeService writes) as plain text through the
     * system share sheet. The text travels as EXTRA_TEXT — no
     * FileProvider, no new dependency, no permission. If there is no
     * log yet (or it is empty), say so instead of opening the sheet.
     */
    private void onShareLogClicked() {
        final File logDir = new File(getFilesDir(), "logs");
        // F-10: the log can be up to 512 KB. Reading it here, on the
        // UI thread, made the tap visibly stutter, so the read moves
        // to a throwaway background thread; the result is handed back
        // to the UI thread, which alone shows the toast / share sheet.
        //
        // versionCode 23 (audit finding B-3): the read itself goes
        // through NodeService's coordinated reader — bounded, taken
        // under the log writers' own lock so a rotation cannot tear
        // it, and already cut to the share tail. This method used to
        // read the whole file uncoordinated and cut it here, which
        // could hand over a partial log that looked complete (and
        // could split a surrogate pair at the cut).
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
        // The log file is capped at 512 KB, but an intent extra has a
        // much smaller safe ceiling (the Binder transaction limit is
        // ~1 MB for the whole parcel, and text travels as UTF-16):
        // handing a full-size log to the share sheet could fail or
        // crash the chooser. Share the tail — where a failed start's
        // evidence lives — when the log is large. The marker is plain
        // text like the log itself, which is unlocalized by design.
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
    // Scroll position keeping (the page must never move on its own)
    // ------------------------------------------------------------------

    /**
     * Posts a restore of the given scroll offset, to land after
     * the layout pass the pending changes are about to trigger —
     * posted, not a direct scrollTo, so it applies against the
     * new layout. A no-op at the top of the page (offset 0): a
     * user who is not scrolled down has nothing to lose.
     */
    private void restoreScrollLater(final int y) {
        if (y > 0 && scrollView != null) {
            scrollView.post(() -> scrollView.scrollTo(0, y));
        }
    }

    /**
     * Runs a settings change with the scroll position held: the
     * offset is captured at the handler's entry — before the
     * change's own view-state flips (enabling/disabling the
     * settings block, a note growing or shrinking via refresh())
     * can re-lay out the page and shift the offset — and restored
     * once the change has fully run.
     */
    private void runPreservingScroll(Runnable change) {
        final int y = scrollView != null ? scrollView.getScrollY() : 0;
        change.run();
        restoreScrollLater(y);
    }

    // ------------------------------------------------------------------
    // Refresh (1 s tick while visible; reads NodeState + TrackStore only)
    // ------------------------------------------------------------------

    private void refresh() {
        NodeState st = NodeState.get();

        // Remember where the user has scrolled to. The setText
        // calls below trigger a layout pass, and during that pass
        // the ScrollView can drift toward a focused child — on the
        // device this read as "swipe up to see the lower cards and
        // the page slides back down on its own". The position is
        // restored at the end of refresh(); a user sitting at the
        // very top (scrollY == 0) is left alone.
        final int savedScrollY =
                scrollView != null ? scrollView.getScrollY() : 0;

        statusView.setText(NodeState.label(this, st.status));
        StringBuilder note = new StringBuilder();
        if (st.lastExitCode != null && st.status == NodeState.Status.STOPPED) {
            note.append(I18n.format(this, StringsTable.NOTE_LAST_EXIT_CODE, st.lastExitCode));
        }
        if (st.lastNote != null) {
            if (note.length() > 0) {
                note.append(" · ");
            }
            note.append(st.lastNote);
        }
        // Failed start: show each launch attempt's own record —
        // method, exit code, how long it lived, and the lines that
        // attempt's process printed (or that it printed nothing) —
        // so the reason is visible, not just the exit code (P-6,
        // per-attempt form). The output lines are raw node text (the
        // node's own language); the labels are localized.
        List<NodeState.Attempt> attempts = st.lastAttempts;
        if (attempts != null && !attempts.isEmpty()
                && st.status == NodeState.Status.STOPPED) {
            if (note.length() > 0) {
                note.append("\n\n");
            }
            note.append(I18n.get(this, StringsTable.ATTEMPTS_TITLE));
            int n = 1;
            for (NodeState.Attempt a : attempts) {
                note.append("\n");
                String method = I18n.get(this, a.viaLinker
                        ? StringsTable.ATTEMPT_METHOD_LINKER
                        : StringsTable.ATTEMPT_METHOD_DIRECT);
                if (a.exitCode != null) {
                    note.append(I18n.format(this, StringsTable.ATTEMPT_SUMMARY,
                            n, method, a.exitCode, a.livedMs));
                } else {
                    note.append(I18n.format(this,
                            StringsTable.ATTEMPT_SUMMARY_NO_EXIT,
                            n, method, a.livedMs));
                }
                if (a.lines.isEmpty()) {
                    note.append("\n").append(
                            I18n.get(this, StringsTable.ATTEMPT_NO_OUTPUT));
                } else {
                    for (String line : a.lines) {
                        note.append("\n").append(line);
                    }
                }
                n++;
            }
        }
        String noteText = note.toString();
        if (!noteText.equals(lastNoteText)) {
            // Skip the setText when the (potentially long,
            // multi-line) text is unchanged, so an idle tick does
            // not re-measure it and disturb the scroll position.
            lastNoteText = noteText;
            noteView.setText(noteText);
        }

        bestView.setText(st.bestBlock >= 0 ? "#" + st.bestBlock : "—");
        highestView.setText(st.highestBlock >= 0 ? "#" + st.highestBlock : "—");
        peersView.setText(st.peers >= 0 ? String.valueOf(st.peers) : "—");

        // The pulse: exactly one ripple per newly observed best block —
        // and only when the node is no longer in its initial sync
        // (F-12). "Changed" used to be enough, so a decrease (reorg,
        // re-observation) emitted a ripple too, and during the initial
        // sync the best block moved on almost every 1 s refresh, so
        // ripples ran nearly continuously. The design is one quiet
        // ripple when a new block arrives at the head: require an
        // increase, and require the sync state the dashboard already
        // displays to not be SYNCING (NodeService derives it from
        // system_health.isSyncing and the best-vs-highest gap in
        // system_syncState — no new state is fetched for this).
        if (!firstRefresh && st.bestBlock >= 0 && lastBestSeen >= 0
                && st.bestBlock > lastBestSeen
                && st.status != NodeState.Status.SYNCING) {
            pulseView.pulse();
        }
        if (st.bestBlock >= 0) {
            lastBestSeen = st.bestBlock;
        }
        firstRefresh = false;

        boolean running = st.serviceRunning;
        toggleButton.setText(running
                ? I18n.get(this, StringsTable.BUTTON_STOP_NODE)
                : I18n.get(this, StringsTable.BUTTON_START_NODE));
        toggleButton.setBackground(running ? stopPill : startPill);
        toggleButton.setTextColor(running ? TEXT : Color.BLACK);

        trackUptimeView.setText(formatDuration(TrackStore.uptimeMs(this)));
        trackBlocksView.setText(String.valueOf(TrackStore.blockUpdates(this)));
        trackPeersView.setText(String.valueOf(TrackStore.maxPeers(this)));

        watchStatusView.setText(NodeState.label(this, st.status));
        watchPeersView.setText(st.peers >= 0
                ? I18n.format(this, StringsTable.VALUE_PEERS_CONNECTED, st.peers)
                : "—");
        watchBlocksView.setText(
                (st.bestBlock >= 0 ? "#" + st.bestBlock : "—")
                        + " / "
                        + (st.highestBlock >= 0 ? "#" + st.highestBlock : "—"));

        // Settings vs. the running node: if the user changed the
        // telemetry opt-in or the display name after this node process
        // was launched, the change is not live yet. Say exactly that —
        // applying it is the user's own 停止 → 開始, never an automatic
        // restart behind their back.
        String restartNote = "";
        if (st.serviceRunning && st.launchedTelemetry != null
                && st.launchedNodeName != null) {
            boolean wantTelemetry = NodeSettings.telemetryEnabled(this);
            String wantName = NodeSettings.effectiveNodeName(this);
            if (wantTelemetry != st.launchedTelemetry
                    || !wantName.equals(st.launchedNodeName)) {
                restartNote = I18n.get(this, StringsTable.SETTINGS_RESTART_NOTE);
            }
        }
        if (!restartNote.equals(lastRestartNoteText)) {
            lastRestartNoteText = restartNote;
            restartNoteView.setText(restartNote);
        }

        // Restore the scroll position captured at the top, once
        // the layout pass this refresh triggered has settled.
        restoreScrollLater(savedScrollY);
    }

    private String formatDuration(long ms) {
        long totalMin = ms / 60000L;
        long h = totalMin / 60;
        long m = totalMin % 60;
        if (h > 0) {
            return I18n.format(this, StringsTable.DURATION_HOURS_MINUTES, h, m);
        }
        return I18n.format(this, StringsTable.DURATION_MINUTES, m);
    }
}
