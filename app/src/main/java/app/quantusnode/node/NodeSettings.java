package app.quantusnode.node;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * User settings for the node, stored only on this phone:
 *
 *   - telemetry opt-in (default OFF): whether the node announces itself
 *     to the official Quantus telemetry list. Turning it on makes the
 *     node name and its live status public information — anyone can
 *     see them at telemetry.quantus.com. The default never announces;
 *     the choice, and what it reveals, belong to the user.
 *   - display name: the value passed to the node as --name. Empty
 *     means the generic default, which identifies the app — never the
 *     device or the person.
 *   - UI language: "" means "follow the device settings" (the
 *     default); otherwise one of the language tags in
 *     {@link StringsTable#TAGS}. A display preference only — it is
 *     never transmitted anywhere.
 *   - settings lock (default OFF): a mis-tap guard for the settings
 *     above, for a phone that sits somewhere running the node. While
 *     locked, the settings controls are disabled in the UI. It holds
 *     no credential and locks nothing else — node start/stop always
 *     stays available.
 *
 * Both live in the app's private SharedPreferences and are read by
 * {@link NodeService} at launch time only, so changing them never
 * affects a node that is already running (and never restarts one
 * silently). The app itself transmits these settings nowhere; the
 * display name leaves the phone only as part of the node's telemetry,
 * and only when the opt-in is on.
 */
public final class NodeSettings {

    /** Generic default node name: identifies the app, not the device. */
    public static final String DEFAULT_NODE_NAME = "quantus-android-node";

    private static final String PREFS = "settings";
    private static final String KEY_TELEMETRY = "telemetry_enabled";
    private static final String KEY_NODE_NAME = "node_name";
    private static final String KEY_LANGUAGE = "language";
    private static final String KEY_SETTINGS_LOCKED = "settings_locked";

    /** Display names are capped so they stay sane in lists and logs. */
    private static final int MAX_NAME_CHARS = 64;

    private NodeSettings() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean telemetryEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_TELEMETRY, false);
    }

    public static void setTelemetryEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_TELEMETRY, enabled).apply();
    }

    /** The display name exactly as the user typed it ("" when unset). */
    public static String nodeNameRaw(Context ctx) {
        return prefs(ctx).getString(KEY_NODE_NAME, "");
    }

    public static void setNodeNameRaw(Context ctx, String raw) {
        prefs(ctx).edit().putString(KEY_NODE_NAME, raw == null ? "" : raw).apply();
    }

    /**
     * The UI language exactly as stored: "" for "follow the device
     * settings" (the default), or a tag from {@link StringsTable#TAGS}.
     */
    public static String languageRaw(Context ctx) {
        return prefs(ctx).getString(KEY_LANGUAGE, "");
    }

    public static void setLanguageRaw(Context ctx, String tag) {
        prefs(ctx).edit().putString(KEY_LANGUAGE, tag == null ? "" : tag).apply();
    }

    /** Whether the settings UI is locked against accidental changes. */
    public static boolean settingsLocked(Context ctx) {
        return prefs(ctx).getBoolean(KEY_SETTINGS_LOCKED, false);
    }

    public static void setSettingsLocked(Context ctx, boolean locked) {
        prefs(ctx).edit().putBoolean(KEY_SETTINGS_LOCKED, locked).apply();
    }

    /**
     * The name to actually pass as --name: the user's display name,
     * sanitized (control characters stripped, trimmed, length-capped),
     * or the generic default when nothing usable was entered. It is
     * handed to the node as a single argv element — never via a shell.
     */
    public static String effectiveNodeName(Context ctx) {
        return sanitize(nodeNameRaw(ctx));
    }

    public static String sanitize(String raw) {
        if (raw == null) {
            return DEFAULT_NODE_NAME;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        String s = sb.toString().trim();
        if (s.length() > MAX_NAME_CHARS) {
            s = s.substring(0, MAX_NAME_CHARS).trim();
        }
        // F-6: strip leading '-' characters. The name is passed as a
        // single argv element (no shell), so there is no injection
        // risk — but a value starting with '-' can be misread by the
        // node's CLI parser as another option instead of the --name
        // value, failing the start. If nothing usable remains, the
        // check below falls back to the default name.
        int lead = 0;
        while (lead < s.length() && s.charAt(lead) == '-') {
            lead++;
        }
        if (lead > 0) {
            s = s.substring(lead).trim();
        }
        return s.isEmpty() ? DEFAULT_NODE_NAME : s;
    }
}
