package app.quantusnode.node.depin.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Persistent, app-private error log: the reason a failure happened
 * must survive the process that died from it. CompanionStatus is
 * in-memory only, so before v0.1.1 an init failure vanished with
 * the process and the relaunched UI had nothing to show.
 *
 * Entries record ONLY: timestamp, a short kind tag, the exception
 * class, its message and the top stack frames (plus the cause
 * chain). No key material, no seed bytes, no journal contents —
 * exception messages in this app never carry secrets, and stack
 * frames are class/method names only.
 *
 * The file is capped: once it grows past MAX_BYTES the oldest
 * entries are dropped at an entry boundary.
 *
 * Pure java.io on purpose (no Android types), so the JVM harness
 * exercises this exact code.
 */
public final class CrashLog {
    public static final String FILE_NAME = "error-log.txt";

    private static final int MAX_BYTES = 64 * 1024;
    private static final int MAX_FRAMES = 16;
    private static final int MAX_MESSAGE_CHARS = 500;
    private static final String ENTRY_MARKER = "=== ";

    private CrashLog() {}

    public static synchronized void record(File dir, String kind, Throwable t) {
        recordAt(new File(dir, FILE_NAME), kind, t);
    }

    /** Record directly to a log file, rather than to a directory. */
    public static synchronized void recordAt(File file, String kind, Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append(ENTRY_MARKER).append(timestamp()).append(" [").append(kind).append("]\n");
        appendThrowable(sb, t, false);
        appendEntryTo(file, sb.toString());
    }

    public static synchronized void recordMessage(File dir, String kind, String message) {
        recordMessageAt(new File(dir, FILE_NAME), kind, message);
    }

    /** Record a message directly to a log file, rather than to a directory. */
    public static synchronized void recordMessageAt(File file, String kind, String message) {
        String entry = ENTRY_MARKER + timestamp() + " [" + kind + "]\n"
                + truncate(message) + "\n";
        appendEntryTo(file, entry);
    }

    /** Full log text, or null when nothing has been recorded. */
    public static synchronized String readAll(File dir) {
        return readAllAt(new File(dir, FILE_NAME));
    }

    /** Full text of a log file, or null when nothing has been recorded. */
    public static synchronized String readAllAt(File file) {
        return readFrom(file);
    }

    private static String readFrom(File file) {
        if (!file.exists()) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) >= 0) {
                out.write(chunk, 0, n);
            }
            String text = out.toString(StandardCharsets.UTF_8.name());
            return text.isEmpty() ? null : text;
        } catch (IOException e) {
            return null;
        }
    }

    static synchronized void clear(File dir) {
        //noinspection ResultOfMethodCallIgnored
        new File(dir, FILE_NAME).delete();
    }

    /** The most recent entry in a log text (or the whole text). */
    public static String lastEntry(String logText) {
        if (logText == null) {
            return null;
        }
        int idx = logText.lastIndexOf("\n" + ENTRY_MARKER);
        if (idx < 0) {
            return logText.strip();
        }
        return logText.substring(idx + 1).strip();
    }

    /** The body of the most recent entry recorded under
     *  {@code kind}, or null when there is none. Audit L18:
     *  the superseded-copy sweep's steady-state notes are
     *  deduplicated against the last one actually recorded
     *  under their own kind, which needs this read-back. */
    public static String lastMessageOfKind(String logText,
                                           String kind) {
        if (logText == null) {
            return null;
        }
        String tag = "[" + kind + "]\n";
        int idx = logText.lastIndexOf(tag);
        if (idx < 0) {
            return null;
        }
        int start = idx + tag.length();
        int end = logText.indexOf("\n" + ENTRY_MARKER, start);
        String body = end < 0
                ? logText.substring(start)
                : logText.substring(start, end);
        return body.strip();
    }

    /** The bodies of ALL entries recorded under {@code kind},
     *  oldest first. Audit L23: deduplicating the sweep's
     *  steady-state notes against only the LAST recorded one
     *  re-recorded the whole set on every sweep whenever a
     *  sweep produced two or more (each note differed from
     *  the single comparison point in turn). The app layers
     *  now deduplicate against the recorded SET: a note is
     *  recorded only when its body is not already on the
     *  record under this kind. (Entries the log's size cap
     *  has rotated out are absent from the set, so a note
     *  whose record was dropped is recorded once more —
     *  bounded, and true: it was no longer on the record.) */
    public static java.util.List<String> messagesOfKind(
            String logText, String kind) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (logText == null) {
            return out;
        }
        String tag = "[" + kind + "]\n";
        int from = 0;
        while (true) {
            int idx = logText.indexOf(tag, from);
            if (idx < 0) {
                return out;
            }
            int start = idx + tag.length();
            int end = logText.indexOf("\n" + ENTRY_MARKER, start);
            String body = end < 0
                    ? logText.substring(start)
                    : logText.substring(start, end);
            out.add(body.strip());
            from = start;
        }
    }

    /** The most recent entry whose kind is none of
     *  {@code excludedKinds}, or null when there is none.
     *  Audit L23: a kind-blind "last entry" summary lets
     *  the superseded-copy sweep's notes (its action
     *  records and its steady-state notes alike; neither
     *  is an error) pose as the most recent error whenever
     *  a sweep ran after the most recent real failure. The
     *  companion's lastErrorEntry — the one with a display
     *  — excludes both sweep kinds through this helper;
     *  the entries themselves stay in the log in full. It
     *  lives here too so the shared harness exercises the
     *  same code in both apps; this app's own
     *  lastErrorEntry has no display caller at present. */
    public static String lastEntryExcludingKinds(
            String logText, String... excludedKinds) {
        if (logText == null) {
            return null;
        }
        int end = logText.length();
        while (end > 0) {
            int idx = logText.lastIndexOf(
                    "\n" + ENTRY_MARKER, end - 1);
            int start = idx < 0 ? 0 : idx + 1;
            String entry = logText.substring(start, end).strip();
            if (!entry.isEmpty()) {
                String kind = entryKind(entry);
                boolean excluded = false;
                if (kind != null) {
                    for (String k : excludedKinds) {
                        if (k.equals(kind)) {
                            excluded = true;
                            break;
                        }
                    }
                }
                if (!excluded) {
                    return entry;
                }
            }
            if (idx < 0) {
                return null;
            }
            end = idx;
        }
        return null;
    }

    /** The kind tag of an entry (the text in brackets on
     *  its header line), or null when it has none. */
    private static String entryKind(String entry) {
        int nl = entry.indexOf('\n');
        String head = nl < 0 ? entry : entry.substring(0, nl);
        int lb = head.indexOf('[');
        int rb = head.indexOf(']', lb + 1);
        if (lb < 0 || rb < 0) {
            return null;
        }
        return head.substring(lb + 1, rb);
    }

    // ---------------- internals ----------------

    private static void appendEntryTo(File file, String entry) {
        try {
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            try (java.io.RandomAccessFile raf =
                         new java.io.RandomAccessFile(file, "rw");
                 java.nio.channels.FileChannel channel =
                         raf.getChannel()) {
                // Residual F-12: the class monitor serializes
                // only within one process, but the main and
                // :depin processes append to this same file —
                // the bare read-modify-write let each process
                // overwrite the other's appends wholesale (a
                // two-writer harness run kept 5 of 400 entries)
                // and never fsynced, so a crash could lose or
                // tear what was written. The whole
                // read-combine-write now runs under a
                // cross-process file lock and is forced to
                // storage before the lock is released.
                java.nio.channels.FileLock lock = null;
                try {
                    lock = channel.lock();
                } catch (java.nio.channels
                        .OverlappingFileLockException e) {
                    // This JVM already holds the lock (a
                    // re-entrant record on the same stack):
                    // the class monitor is the serializer
                    // there, so proceeding unlocked is sound.
                }
                try {
                    String existing = readFromChannel(channel);
                    String combined =
                            (existing == null ? "" : existing)
                                    + entry;
                    if (combined.length() > MAX_BYTES) {
                        int cut = combined.length() - MAX_BYTES;
                        int boundary =
                                combined.indexOf(ENTRY_MARKER, cut);
                        combined = boundary >= 0
                                ? combined.substring(boundary)
                                : combined.substring(cut);
                    }
                    byte[] bytes =
                            combined.getBytes(StandardCharsets.UTF_8);
                    java.nio.ByteBuffer out =
                            java.nio.ByteBuffer.wrap(bytes);
                    channel.position(0);
                    while (out.hasRemaining()) {
                        channel.write(out);
                    }
                    channel.truncate(bytes.length);
                    channel.force(false);
                } finally {
                    if (lock != null) {
                        lock.release();
                    }
                }
            }
        } catch (IOException ignored) {
            // Diagnostics must never take the app down with them.
        }
    }

    /** Read the channel's full current content as text, or
     *  null when it is empty. The caller holds the file lock. */
    private static String readFromChannel(
            java.nio.channels.FileChannel channel)
            throws IOException {
        long size = channel.size();
        if (size <= 0) {
            return null;
        }
        java.io.ByteArrayOutputStream buf =
                new java.io.ByteArrayOutputStream();
        java.nio.ByteBuffer chunk =
                java.nio.ByteBuffer.allocate(8192);
        long pos = 0;
        while (pos < size) {
            chunk.clear();
            int n = channel.read(chunk, pos);
            if (n < 0) {
                break;
            }
            buf.write(chunk.array(), 0, n);
            pos += n;
        }
        String text = buf.toString(StandardCharsets.UTF_8.name());
        return text.isEmpty() ? null : text;
    }

    private static void appendThrowable(StringBuilder sb, Throwable t, boolean causedBy) {
        if (t == null) {
            sb.append("(no throwable)\n");
            return;
        }
        if (causedBy) {
            sb.append("Caused by: ");
        }
        sb.append(t.getClass().getName()).append(": ")
                .append(truncate(String.valueOf(t.getMessage()))).append('\n');
        StackTraceElement[] frames = t.getStackTrace();
        int n = Math.min(frames.length, MAX_FRAMES);
        for (int i = 0; i < n; i++) {
            sb.append("    at ").append(frames[i]).append('\n');
        }
        if (frames.length > n) {
            sb.append("    ... ").append(frames.length - n).append(" more\n");
        }
        if (t.getCause() != null && t.getCause() != t) {
            appendThrowable(sb, t.getCause(), true);
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_MESSAGE_CHARS
                ? s : s.substring(0, MAX_MESSAGE_CHARS) + "…";
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date());
    }
}
