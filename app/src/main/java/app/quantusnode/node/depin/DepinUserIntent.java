package app.quantusnode.node.depin;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/**
 * The user's recorded toggle intent — the record
 * DepinModule (main process) keeps of the last settings
 * toggle, in its own preferences file ("depin_facade").
 * This class is the :depin process's reader of that
 * record, and the single source of the record's names.
 *
 * Why the observer reads it, and why AS A FILE
 * (internal.26, audit findings F-03/F-04): the intent
 * record is the one durable fact about the user's last
 * explicit act that the main process can write safely —
 * it is the file's only writer. The observer's own
 * settings file ("depin_settings") is the opposite: its
 * only safe writer is the :depin process itself, because
 * SharedPreferences keeps a PER-PROCESS in-memory map and
 * MODE_PRIVATE never reloads another process's commits.
 * Internal.24 made the main process write the user's OFF
 * into depin_settings directly ("the OFF must land even
 * when no observer exists"), and that is unsafe in both
 * directions the audit identified: a commit whose value
 * already matches the writer's stale map writes NOTHING
 * (off → on → off with a dead observer leaves the disk
 * flag true), and a commit that does write overwrites the
 * file with the writer's whole stale map (rolling back
 * bindings and self-stop records the observer wrote
 * since). Internal.26 removes that write: the main
 * process records the intent here, and the observer —
 * at every start that could resume recording — reads
 * the record fresh from disk and lands an OFF itself,
 * in its own process, restoring the single-writer rule.
 *
 * Reading through SharedPreferences would defeat the
 * purpose: this process's copy would be loaded once and
 * never refreshed, serving the same staleness the fix
 * removes. So the preferences XML is read as a file on
 * every call. The file is tiny and the calls are rare
 * (start commands and claim refusals), so the cost is
 * beside the point; freshness is the point.
 *
 * A missing or unreadable record is NO intent — the
 * caller falls back to the pre-existing behaviour (the
 * observer's own settings), never to an invented OFF.
 * Only the facade writes this record, atomically per
 * toggle, so a record that claims to be set but lacks
 * its value is treated as absent rather than guessed at.
 */
public final class DepinUserIntent {

    private DepinUserIntent() {
    }

    /** The facade's preferences file (main-process owned). */
    public static final String PREFS_NAME = "depin_facade";
    public static final String KEY_SET = "user_intent_set";
    public static final String KEY_ENABLED = "user_intent_enabled";
    public static final String KEY_WALL_MS = "user_intent_wall_ms";

    /** One reading of the record. */
    public static final class Record {
        /** A complete record exists on disk. */
        public final boolean present;
        /** The intent's value (meaningful only when
         *  {@link #present}). */
        public final boolean enabled;
        /** Wall time the toggle was recorded (0 when the
         *  record carries none). */
        public final long wallMs;

        public Record(boolean present, boolean enabled,
                      long wallMs) {
            this.present = present;
            this.enabled = enabled;
            this.wallMs = wallMs;
        }
    }

    /** No record: missing, unreadable, or incomplete. */
    public static final Record ABSENT = new Record(false, false, 0L);

    /**
     * Parse the record out of a preferences XML text —
     * pure, so the harness can pin the wire format the
     * facade writes. The SharedPreferences serializer
     * emits one element per entry with the entry's name
     * and value as attributes; boolean and long values
     * carry no escaped characters, so a bounded attribute
     * scan is exact for the three keys read here.
     */
    public static Record parse(String xml) {
        if (xml == null) {
            return ABSENT;
        }
        String set = attributeValue(xml, KEY_SET);
        String enabled = attributeValue(xml, KEY_ENABLED);
        if (!"true".equals(set) || enabled == null) {
            // Not a record, or a record torn mid-write:
            // absent, not guessed.
            return ABSENT;
        }
        long wall = 0L;
        String wallText = attributeValue(xml, KEY_WALL_MS);
        if (wallText != null) {
            try {
                wall = Long.parseLong(wallText);
            } catch (NumberFormatException e) {
                wall = 0L;
            }
        }
        return new Record(true, "true".equals(enabled), wall);
    }

    /** The value attribute of the element named {@code
     *  key}, or null when there is no such element or it
     *  carries no value. The search is bounded by the end
     *  of the element's own tag. */
    private static String attributeValue(String xml, String key) {
        String needle = "name=\"" + key + "\"";
        int at = xml.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int tagEnd = xml.indexOf('>', at);
        if (tagEnd < 0) {
            return null;
        }
        int vAt = xml.indexOf("value=\"", at);
        if (vAt < 0 || vAt > tagEnd) {
            return null;
        }
        int vStart = vAt + "value=\"".length();
        int vEnd = xml.indexOf('"', vStart);
        if (vEnd < 0 || vEnd > tagEnd) {
            return null;
        }
        return xml.substring(vStart, vEnd);
    }

    /**
     * Read the record fresh from its file on disk, in
     * whatever process calls this. Any failure — no file
     * yet, an I/O error, a torn read — is {@link #ABSENT}:
     * the fallback is the caller's pre-existing behaviour,
     * never an invented intent.
     */
    public static Record read(Context context) {
        try {
            File dataDir = context.getApplicationContext()
                    .getFilesDir().getParentFile();
            if (dataDir == null) {
                return ABSENT;
            }
            File f = new File(new File(dataDir, "shared_prefs"),
                    PREFS_NAME + ".xml");
            if (!f.exists() || f.length() > 64L * 1024) {
                return ABSENT;
            }
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            try (FileInputStream in = new FileInputStream(f)) {
                int n;
                while (off < buf.length
                        && (n = in.read(buf, off,
                                buf.length - off)) > 0) {
                    off += n;
                }
            }
            return parse(new String(buf, 0, off,
                    StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return ABSENT;
        }
    }
}
