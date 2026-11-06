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

    /**
     * Cap for the raw name as stored (versionCode 21, audit
     * finding 10): the effective name is capped at 64 code points by
     * {@link #sanitize} anyway, so nothing legitimate needs a longer
     * stored value; the cap only keeps a huge pasted text out of the
     * preferences file.
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
        prefs(ctx).edit().putBoolean(KEY_TELEMETRY, enabled).apply();
    }

    /** The display name exactly as the user typed it ("" when unset). */
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
            // Truncate on a code-point boundary, like sanitize(), so
            // the stored value never ends in a lone surrogate.
            v = v.substring(0, v.offsetByCodePoints(0, MAX_STORED_NAME_CHARS));
        }
        prefs(ctx).edit().putString(KEY_NODE_NAME, v).apply();
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
        prefs(ctx).edit().putString(KEY_LANGUAGE, tag == null ? "" : tag).apply();
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
     * The name to actually pass as --name: the user's display name,
     * sanitized (control and invisible format characters stripped,
     * trimmed, capped at {@link #MAX_NAME_CHARS} code points), or the
     * generic default when nothing usable was entered. It is handed
     * to the node as a single argv element — never via a shell.
     */
    public static String effectiveNodeName(Context ctx) {
        return sanitize(nodeNameRaw(ctx));
    }

    public static String sanitize(String raw) {
        return sanitizeInternal(raw, true);
    }

    /**
     * The sanitized result WITHOUT the {@link #MAX_NAME_CHARS} cap
     * (versionCode 26): every other rule of {@link #sanitize} applies
     * unchanged — control / invisible-format stripping, trim, the
     * leading-dash rules and the default-name fallbacks. The display
     * name editor validates against this: it counts the code points
     * of this result and refuses an over-limit save instead of
     * silently truncating what the user typed. When this result is
     * within the cap it is identical to sanitize(raw).
     */
    public static String sanitizeUntruncated(String raw) {
        return sanitizeInternal(raw, false);
    }

    /** The display-name cap, in Unicode code points after sanitizing. */
    public static int nameLimitCodePoints() {
        return MAX_NAME_CHARS;
    }

    private static String sanitizeInternal(String raw, boolean cap) {
        if (raw == null) {
            return DEFAULT_NODE_NAME;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!Character.isISOControl(c) && !isInvisibleFormatChar(c)) {
                sb.append(c);
            }
        }
        String s = sb.toString().trim();
        if (cap && s.codePointCount(0, s.length()) > MAX_NAME_CHARS) {
            // Cap by code points, not UTF-16 units (versionCode 21,
            // audit finding 3): a unit-wise cut could split a
            // surrogate pair and leave a lone surrogate in the name.
            s = s.substring(0, s.offsetByCodePoints(0, MAX_NAME_CHARS)).trim();
        }
        // F-6: strip leading '-' characters. The name is passed as a
        // single argv element (no shell), so there is no injection
        // risk — but a value starting with '-' can be misread by the
        // node's CLI parser as another option instead of the --name
        // value, failing the start. If nothing usable remains, the
        // check below falls back to the default name.
        //
        // External audit round 2 (F11): one strip-then-trim pass is
        // not enough — in "- --help" the space hides the second dash
        // run, so the single pass produced "--help", an option token
        // again. Strip-and-trim now repeats until the value is
        // stable. A value that needs more than one stripping pass
        // was a dash run smuggled past whitespace ("- --help"):
        // rather than keep whatever it reduces to, fall back to the
        // default name. A single contiguous run is an ordinary typo
        // and is simply removed ("--abc" -> "abc", "-" -> default).
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
            return DEFAULT_NODE_NAME;
        }
        return s.isEmpty() ? DEFAULT_NODE_NAME : s;
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
