package app.quantusnode.node;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
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

import java.io.File;

/**
 * Dashboard. Dark, minimal, quiet — the node's presence ("気配"), not a
 * control panel shouting for attention.
 *
 * Shows: status, best/highest block, peers; a start/stop button; the
 * on-device "航跡" (track) record; the "量子ウォッチ" (quantum watch)
 * frame, whose external-threat half is an honest stub for now; and the
 * settings — the telemetry opt-in (default off, with what it reveals
 * stated next to the switch), the node's display name, and the UI
 * language (9 languages, "follow device" by default — see
 * {@link StringsTable} / {@link I18n}).
 *
 * All data comes from the node on this phone via local RPC (through
 * NodeService / NodeState). Nothing here talks to the outside.
 */
public class MainActivity extends Activity {

    private static final int BG = Color.rgb(11, 14, 19);
    private static final int TEXT = Color.rgb(205, 213, 222);
    private static final int DIM = Color.rgb(120, 130, 142);
    private static final int FAINT = Color.rgb(84, 94, 106);
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
    private TextView trackUptimeView;
    private TextView trackBlocksView;
    private TextView trackPeersView;
    private TextView watchStatusView;
    private TextView watchPeersView;
    private TextView watchBlocksView;
    private Switch telemetrySwitch;
    private EditText nodeNameInput;
    private Button languageButton;
    private TextView restartNoteView;

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
        int pad = (int) (24 * d);
        int dir = I18n.isRtl(this)
                ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(BG);
        root.setLayoutDirection(dir);

        TextView title = text("Quantus Node", 15, DIM);
        title.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        title.setLetterSpacing(0.18f);
        root.addView(title);

        TextView sub = text(I18n.get(this, StringsTable.APP_SUBTITLE), 11, FAINT);
        root.addView(sub);
        root.addView(spacer(d, 10));

        pulseView = new PulseView(this);
        root.addView(pulseView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (170 * d)));

        statusView = text(I18n.get(this, StringsTable.STATUS_STOPPED), 26, TEXT);
        statusView.setGravity(Gravity.CENTER);
        root.addView(statusView);
        noteView = text("", 11, FAINT);
        noteView.setGravity(Gravity.CENTER);
        root.addView(noteView);
        root.addView(spacer(d, 14));

        bestView = row(root, d, I18n.get(this, StringsTable.LABEL_BEST_BLOCK));
        highestView = row(root, d, I18n.get(this, StringsTable.LABEL_HIGHEST_BLOCK));
        peersView = row(root, d, I18n.get(this, StringsTable.LABEL_PEERS));
        root.addView(spacer(d, 18));

        toggleButton = new Button(this);
        toggleButton.setText(I18n.get(this, StringsTable.BUTTON_START_NODE));
        toggleButton.setOnClickListener(v -> onToggleClicked());
        root.addView(toggleButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(spacer(d, 26));

        // 航跡 — this phone's own record, kept only on this phone.
        root.addView(section(I18n.get(this, StringsTable.SECTION_TRACK), d));
        trackUptimeView = row(root, d, I18n.get(this, StringsTable.LABEL_UPTIME_TOTAL));
        trackBlocksView = row(root, d, I18n.get(this, StringsTable.LABEL_BLOCKS_OBSERVED));
        trackPeersView = row(root, d, I18n.get(this, StringsTable.LABEL_MAX_PEERS));
        root.addView(spacer(d, 26));

        // 量子ウォッチ — local/network health now; external threat intel
        // is deliberately not fetched yet (see the stub line in refresh()).
        root.addView(section(I18n.get(this, StringsTable.SECTION_QUANTUM_WATCH), d));
        watchStatusView = row(root, d, I18n.get(this, StringsTable.LABEL_WATCH_OWN_SYNC));
        watchPeersView = row(root, d, I18n.get(this, StringsTable.LABEL_WATCH_NETWORK));
        watchBlocksView = row(root, d, I18n.get(this, StringsTable.LABEL_WATCH_BLOCKS));
        TextView stub = text(I18n.get(this, StringsTable.WATCH_THREAT_STUB), 12, DIM);
        root.addView(stub);
        root.addView(spacer(d, 26));

        // 設定 — telemetry is an opt-in (default OFF) and the display
        // name defaults to the generic app name. Both apply at the next
        // node start only; if they diverge from what the running node
        // was launched with, refresh() says so instead of restarting
        // anything silently. The language choice below, in contrast,
        // applies to this UI immediately (via recreate()).
        root.addView(section(I18n.get(this, StringsTable.SECTION_SETTINGS), d));

        telemetrySwitch = new Switch(this);
        telemetrySwitch.setText(I18n.get(this, StringsTable.SETTINGS_TELEMETRY));
        telemetrySwitch.setTextSize(13);
        telemetrySwitch.setTextColor(TEXT);
        // Set the position before attaching the listener, so building
        // the UI does not itself "change" the setting.
        telemetrySwitch.setChecked(NodeSettings.telemetryEnabled(this));
        telemetrySwitch.setOnCheckedChangeListener((button, isChecked) -> {
            NodeSettings.setTelemetryEnabled(this, isChecked);
            refresh();
        });
        root.addView(telemetrySwitch);

        TextView privacyNote = text(
                I18n.get(this, StringsTable.SETTINGS_TELEMETRY_NOTE), 11, DIM);
        root.addView(privacyNote);
        root.addView(spacer(d, 10));

        TextView nameLabel = text(
                I18n.get(this, StringsTable.SETTINGS_DISPLAY_NAME), 12, DIM);
        root.addView(nameLabel);
        nodeNameInput = new EditText(this);
        nodeNameInput.setSingleLine(true);
        nodeNameInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        nodeNameInput.setHint(NodeSettings.DEFAULT_NODE_NAME);
        nodeNameInput.setTextSize(13);
        nodeNameInput.setTextColor(TEXT);
        nodeNameInput.setHintTextColor(FAINT);
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
        root.addView(nodeNameInput);

        restartNoteView = text("", 11, NOTE);
        root.addView(restartNoteView);
        root.addView(spacer(d, 14));

        // 言語 — stored on-device only; takes effect immediately via
        // recreate(), and the service notification follows the next
        // time it is rebuilt. Language names are shown in each
        // language's own script, as platforms conventionally do.
        TextView langLabel = text(
                I18n.get(this, StringsTable.SETTINGS_LANGUAGE), 12, DIM);
        root.addView(langLabel);
        languageButton = new Button(this);
        languageButton.setText(currentLanguageLabel());
        languageButton.setOnClickListener(v -> showLanguageDialog());
        root.addView(languageButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(spacer(d, 26));

        TextView foot = text(I18n.get(this, StringsTable.FOOTER_INFO), 10, FAINT);
        root.addView(foot);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setLayoutDirection(dir);
        scroll.addView(root);
        setContentView(scroll);
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

    private LinearLayout spacer(float d, int dpHeight) {
        LinearLayout s = new LinearLayout(this);
        s.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (dpHeight * d)));
        return s;
    }

    private TextView section(String label, float d) {
        TextView t = text(label, 12, DIM);
        t.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (6 * d);
        t.setLayoutParams(lp);
        return t;
    }

    /** A "label .... value" row; returns the value view to update later. */
    private TextView row(LinearLayout root, float d, String label) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        TextView l = text(label, 13, DIM);
        r.addView(l, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text("—", 13, TEXT);
        v.setGravity(Gravity.END);
        r.addView(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (4 * d);
        root.addView(r, lp);
        return v;
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

    // ------------------------------------------------------------------
    // Refresh (1 s tick while visible; reads NodeState + TrackStore only)
    // ------------------------------------------------------------------

    private void refresh() {
        NodeState st = NodeState.get();

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
        noteView.setText(note.toString());

        bestView.setText(st.bestBlock >= 0 ? "#" + st.bestBlock : "—");
        highestView.setText(st.highestBlock >= 0 ? "#" + st.highestBlock : "—");
        peersView.setText(st.peers >= 0 ? String.valueOf(st.peers) : "—");

        // The pulse: exactly one ripple per newly observed best block.
        if (!firstRefresh && st.bestBlock >= 0 && lastBestSeen >= 0
                && st.bestBlock != lastBestSeen) {
            pulseView.pulse();
        }
        if (st.bestBlock >= 0) {
            lastBestSeen = st.bestBlock;
        }
        firstRefresh = false;

        toggleButton.setText(st.serviceRunning
                ? I18n.get(this, StringsTable.BUTTON_STOP_NODE)
                : I18n.get(this, StringsTable.BUTTON_START_NODE));

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
        restartNoteView.setText(restartNote);
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
