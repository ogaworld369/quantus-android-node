package app.quantusnode.node;

import android.content.Context;
import android.content.res.Resources;
import android.os.LocaleList;

import java.util.Locale;

/**
 * Android-side language resolution on top of {@link StringsTable}.
 *
 * Which language is in effect:
 *   1. the user's explicit pick in the settings (stored on-device by
 *      {@link NodeSettings}), if any;
 *   2. otherwise the device language ("follow system", the default):
 *      its primary language is mapped onto a supported table — all
 *      Chinese variants use the Simplified Chinese table, which is
 *      the only Chinese table that exists;
 *   3. if the device language is not supported either, English.
 *
 * Formatting: {@link #format} always uses Locale.US so numbers render
 * with ASCII digits 0-9 and no grouping, in every language (decided
 * policy, see {@link StringsTable}). Plain {@link #get} strings
 * contain no numbers that need localising.
 *
 * The chosen language is read from preferences on every call, so the
 * foreground service's notification follows a language change the
 * next time it is rebuilt; notes the service recorded earlier keep
 * the language they were written in.
 */
public final class I18n {

    private I18n() {
    }

    /** The language tag actually in effect for this context. */
    public static String effectiveTag(Context ctx) {
        String pick = NodeSettings.languageRaw(ctx);
        if (pick != null && !pick.isEmpty() && isSupported(pick)) {
            return pick;
        }
        Locale sys = systemLocale();
        if (sys != null) {
            String tag = mapLanguage(sys.getLanguage());
            if (tag != null) {
                return tag;
            }
        }
        return "en";
    }

    public static boolean isSupported(String tag) {
        if (tag == null) {
            return false;
        }
        for (String t : StringsTable.TAGS) {
            if (t.equals(tag)) {
                return true;
            }
        }
        return false;
    }

    /** Map an ISO language code onto a table tag, or null if unsupported. */
    private static String mapLanguage(String lang) {
        if (lang == null) {
            return null;
        }
        switch (lang) {
            case "ja":
                return "ja";
            case "en":
                return "en";
            case "zh":
                return "zh"; // Simplified table; the only Chinese table
            case "es":
                return "es";
            case "hi":
                return "hi";
            case "ar":
                return "ar";
            case "ko":
                return "ko";
            case "ru":
                return "ru";
            case "fr":
                return "fr";
            default:
                return null;
        }
    }

    private static Locale systemLocale() {
        LocaleList list = Resources.getSystem().getConfiguration().getLocales();
        return list.isEmpty() ? null : list.get(0);
    }

    /** Resolve one key in the effective language. */
    public static String get(Context ctx, String key) {
        return StringsTable.get(effectiveTag(ctx), key);
    }

    /**
     * Resolve and format. Locale.US on purpose: ASCII digits 0-9 and
     * no digit grouping in every language — see {@link StringsTable}.
     */
    public static String format(Context ctx, String key, Object... args) {
        return String.format(Locale.US, get(ctx, key), args);
    }

    /** True when the effective language is written right-to-left (Arabic). */
    public static boolean isRtl(Context ctx) {
        return "ar".equals(effectiveTag(ctx));
    }
}
