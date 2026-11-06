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
    /**
     * Last-change stamps (versionCode 28): when the telemetry
     * opt-in, the display name and the language pick were last
     * changed through their setters — epoch millis, 0 while never
     * changed (including everything set before this version, whose
     * change dates simply were not kept). Only the diagnostic
     * summary reads them: a setting changed just before a problem
     * appeared is a first-class diagnostic fact, and keeping just
     * the latest change per setting is all that takes.
     */
    private static final String KEY_TELEMETRY_CHANGED_MS = "telemetry_changed_ms";
    private static final String KEY_NODE_NAME_CHANGED_MS = "node_name_changed_ms";
    private static final String KEY_LANGUAGE_CHANGED_MS = "language_changed_ms";

    /**
     * Display-name limit so names stay sane in lists and logs: the
     * editor refuses a save whose normalized name exceeds it (it
     * never truncates). It is a validation limit, not a step of the
     * normalization itself (versionCode 27, external audit of
     * v0.2.0).
     */
    private static final int MAX_NAME_CHARS = 64;

    /**
     * Cap for the name as stored (versionCode 21, audit
     * finding 10): the editor stores the normalized name, which the
     * 64-code-point limit already bounds, so nothing legitimate
     * needs a longer stored value; the cap only keeps a huge pasted
     * text out of the preferences file.
     */
    private static final int MAX_STORED_NAME_CHARS = 256;

    private NodeSettings() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // versionCode 23 (audit finding B-6): every read below tolerates
    // a stored value of the wrong type. SharedPreferences getters
    // throw ClassCastException in that case; the values here are
    // only ever written by this class, so a mismatch means the
    // preferences file was damaged outside the app (the audit's
    // stated precondition) — the right answer is the default, not
    // a crash in the UI or in the service's launch path.

    public static boolean telemetryEnabled(Context ctx) {
        try {
            return prefs(ctx).getBoolean(KEY_TELEMETRY, false);
        } catch (ClassCastException e) {
            return false;
        }
    }

    public static void setTelemetryEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_TELEMETRY, enabled)
                .putLong(KEY_TELEMETRY_CHANGED_MS,
                        System.currentTimeMillis())
                .apply();
    }

    /**
     * The stored display name ("" when unset). Since versionCode 27
     * the editor stores the value already normalized by
     * {@link #normalizeNodeName}; a value stored by an older version
     * can still be the raw typed text, and every reader normalizes
     * through that same function, so the two behave identically.
     */
    public static String nodeNameRaw(Context ctx) {
        try {
            return prefs(ctx).getString(KEY_NODE_NAME, "");
        } catch (ClassCastException e) {
            return "";
        }
    }

    public static void setNodeNameRaw(Context ctx, String raw) {
        String v = raw == null ? "" : raw;
        if (v.codePointCount(0, v.length()) > MAX_STORED_NAME_CHARS) {
            // Truncate on a code-point boundary, so the stored
            // value never ends in a lone surrogate.
            v = v.substring(0, v.offsetByCodePoints(0, MAX_STORED_NAME_CHARS));
        }
        prefs(ctx).edit().putString(KEY_NODE_NAME, v)
                .putLong(KEY_NODE_NAME_CHANGED_MS,
                        System.currentTimeMillis())
                .apply();
    }

    /**
     * The UI language exactly as stored: "" for "follow the device
     * settings" (the default), or a tag from {@link StringsTable#TAGS}.
     */
    public static String languageRaw(Context ctx) {
        try {
            return prefs(ctx).getString(KEY_LANGUAGE, "");
        } catch (ClassCastException e) {
            return "";
        }
    }

    public static void setLanguageRaw(Context ctx, String tag) {
        prefs(ctx).edit().putString(KEY_LANGUAGE, tag == null ? "" : tag)
                .putLong(KEY_LANGUAGE_CHANGED_MS,
                        System.currentTimeMillis())
                .apply();
    }

    /** When the telemetry opt-in last changed (0 = never / not kept). */
    public static long telemetryChangedMs(Context ctx) {
        try {
            return prefs(ctx).getLong(KEY_TELEMETRY_CHANGED_MS, 0L);
        } catch (ClassCastException e) {
            return 0L;
        }
    }

    /** When the display name last changed (0 = never / not kept). */
    public static long nodeNameChangedMs(Context ctx) {
        try {
            return prefs(ctx).getLong(KEY_NODE_NAME_CHANGED_MS, 0L);
        } catch (ClassCastException e) {
            return 0L;
        }
    }

    /** When the language pick last changed (0 = never / not kept). */
    public static long languageChangedMs(Context ctx) {
        try {
            return prefs(ctx).getLong(KEY_LANGUAGE_CHANGED_MS, 0L);
        } catch (ClassCastException e) {
            return 0L;
        }
    }

    /** Whether the settings UI is locked against accidental changes. */
    public static boolean settingsLocked(Context ctx) {
        try {
            return prefs(ctx).getBoolean(KEY_SETTINGS_LOCKED, false);
        } catch (ClassCastException e) {
            return false;
        }
    }

    public static void setSettingsLocked(Context ctx, boolean locked) {
        prefs(ctx).edit().putBoolean(KEY_SETTINGS_LOCKED, locked).apply();
    }

    /**
     * The name to actually pass as --name: the stored display name,
     * normalized by {@link #normalizeNodeName} (the editor stores
     * the normalized form already, so for anything it saved this
     * changes nothing; a value stored by an older version is
     * normalized by the same single function, so it behaves
     * identically), or the generic default when nothing usable is
     * stored. It is handed to the node as a single argv element —
     * never via a shell.
     */
    public static String effectiveNodeName(Context ctx) {
        return effectiveNodeNameForRaw(nodeNameRaw(ctx));
    }

    /**
     * The effective name for a raw input value: its normalization,
     * or the generic default when the normalization is empty.
     * This — via {@link #normalizeNodeName} — is the ONE result the
     * editor's validation and preview, the stored value and the
     * launch all share (versionCode 27, external audit of v0.2.0;
     * see normalizeNodeName for why they used to diverge).
     */
    public static String effectiveNodeNameForRaw(String raw) {
        String n = normalizeNodeName(raw);
        return n.isEmpty() ? DEFAULT_NODE_NAME : n;
    }

    /**
     * THE display-name normalization (versionCode 27, external
     * audit of v0.2.0): control and invisible-format characters
     * stripped, trimmed, leading '-' runs stripped (see the F-6 /
     * F11 rules below). Returns "" when nothing usable remains —
     * the unset case, and the smuggled-dash case — and the caller
     * (the editor's preview, {@link #effectiveNodeNameForRaw})
     * presents that as the generic default name.
     *
     * Until versionCode 26 there were two sanitizers whose step
     * order differed: the editor validated against an uncapped
     * result (strip, trim, dash-strip, then count), while the
     * launch path capped the raw value at {@link #MAX_NAME_CHARS}
     * code points BEFORE the dash strip. For "--" followed by 64
     * letters the editor therefore accepted — and previewed — a
     * 64-letter name that the node then received as 62 letters;
     * for a long run of spaces in front of a short name, the raw
     * value's stored-length cut could remove the name before the
     * trim ever saw it. The normalization is now defined once,
     * here, in the editor's order; the length limit is a check ON
     * this result (the editor refuses an over-limit save, it never
     * truncates), the editor stores THIS result, and the launch
     * applies no second transformation of its own — this function
     * is idempotent, so applying it to a stored value returns that
     * value unchanged.
     */
    public static String normalizeNodeName(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!Character.isISOControl(c) && !isInvisibleFormatChar(c)) {
                sb.append(c);
            }
        }
        String s = sb.toString().trim();
        // F-6: strip leading '-' characters. The name is passed as a
        // single argv element (no shell), so there is no injection
        // risk — but a value starting with '-' can be misread by the
        // node's CLI parser as another option instead of the --name
        // value, failing the start. If nothing usable remains, the
        // result is empty and the effective name falls back to the
        // default.
        //
        // External audit round 2 (F11): one strip-then-trim pass is
        // not enough — in "- --help" the space hides the second dash
        // run, so the single pass produced "--help", an option token
        // again. Strip-and-trim now repeats until the value is
        // stable. A value that needs more than one stripping pass
        // was a dash run smuggled past whitespace ("- --help"):
        // rather than keep whatever it reduces to, the result is
        // empty (the default name). A single contiguous run is an
        // ordinary typo and is simply removed ("--abc" -> "abc",
        // "-" -> empty).
        int stripPasses = 0;
        while (true) {
            int lead = 0;
            while (lead < s.length() && s.charAt(lead) == '-') {
                lead++;
            }
            if (lead == 0) {
                break;
            }
            stripPasses++;
            s = s.substring(lead).trim();
            if (s.isEmpty()) {
                break;
            }
        }
        if (stripPasses > 1) {
            return "";
        }
        return s;
    }

    /** The display-name cap, in Unicode code points after normalizing. */
    public static int nameLimitCodePoints() {
        return MAX_NAME_CHARS;
    }

    /**
     * Invisible characters that can make a name LOOK like a different
     * name in a list (versionCode 21, audit finding 3): the
     * bidirectional embedding/override and isolate controls
     * (U+202A–U+202E, U+2066–U+2069), the byte-order mark / zero-width
     * no-break space (U+FEFF) and the zero-width space (U+200B). The
     * display name becomes public whenever telemetry is on, so these
     * are stripped.
     *
     * versionCode 23 (audit finding B-5): the bidi MARKS join the
     * list — U+200E (LRM), U+200F (RLM) and U+061C (ALM). Weaker than
     * the overrides (they attach direction instead of reordering),
     * but the same spoofing family. With them, the invisible format
     * characters that carry no legitimate name content: the word
     * joiner (U+2060), the deprecated invisible operators
     * (U+2061–U+2064), and the soft hyphen (U+00AD — invisible
     * unless it lands at a line break).
     *
     * Deliberately NOT stripped: the zero-width non-joiner (U+200C)
     * and zero-width joiner (U+200D). They carry meaning in
     * Arabic-script and Indic names (and in emoji sequences), and
     * they cannot reorder surrounding text the way the bidi controls
     * can.
     */
    private static boolean isInvisibleFormatChar(char c) {
        return (c >= 0x202A && c <= 0x202E)
                || (c >= 0x2066 && c <= 0x2069)
                || (c >= 0x2060 && c <= 0x2064)
                || c == 0xFEFF
                || c == 0x200B
                || c == 0x200E
                || c == 0x200F
                || c == 0x061C
                || c == 0x00AD;
    }
}
