package app.quantusnode.node.depin.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Durability primitives for the transfer machinery (integration
 * design sections 4.2 / 4.3): atomic single-record replacement
 * with file + parent-directory fsync, the record adoption
 * procedure, directory fsync discipline (including ancestors),
 * capped reads, and the framed append-only journal used for the
 * promotion journal.
 *
 * The sync operations go through a {@link Syncer} so the JVM
 * harness can inject sync failures at exact points (the section
 * 4.3 step-6 boundary rules distinguish "fsync failed" from
 * "process died": a failed fsync poisons the staged entity,
 * while re-running an fsync that never failed is idempotent).
 * Production code always uses {@link #REAL_SYNCER}.
 */
public final class TransferIo {
    private TransferIo() {}

    /** One sync operation against a file or a directory. */
    public interface Syncer {
        void sync(File f) throws IOException;
    }

    /** Production syncer: fsync via a file descriptor. Files
     *  go through a read/write RandomAccessFile (contents);
     *  directories through a read-only FileChannel —
     *  RandomAccessFile rejects directories outright on the
     *  JVM, and a read/write open would fail with EISDIR on
     *  Linux/Android, while a read channel's force() fsyncs
     *  the directory's entries. */
    public static final Syncer REAL_SYNCER = f -> {
        if (f.isDirectory()) {
            try (java.nio.channels.FileChannel ch =
                         java.nio.channels.FileChannel.open(
                                 f.toPath(),
                                 java.nio.file.StandardOpenOption.READ)) {
                ch.force(true);
            }
        } else {
            try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
                raf.getFD().sync();
            }
        }
    };

    private static volatile Syncer syncer = REAL_SYNCER;

    /** Test seam (harness only): substitute sync behaviour;
     *  null restores the production syncer. */
    public static void setSyncerForTest(Syncer s) {
        syncer = (s == null) ? REAL_SYNCER : s;
    }

    public static void fsync(File f) throws IOException {
        syncer.sync(f);
    }

    /** fsync a directory (its entries). */
    public static void fsyncDir(File dir) throws IOException {
        syncer.sync(dir);
    }

    /**
     * fsync {@code dir} and every ancestor up to and including
     * {@code stopAt} (the store root). Used by the pre-notice
     * sync boundary, which the design makes unconditional for
     * ancestors (their durability cannot be observed, so it is
     * never made a condition).
     */
    public static void fsyncAncestors(File stopAt, File dir)
            throws IOException {
        File cur = dir;
        while (cur != null) {
            fsyncDir(cur);
            if (cur.equals(stopAt)) return;
            cur = cur.getParentFile();
        }
    }

    /** Read a whole file, refusing anything over {@code cap}
     *  bytes. The length is checked BEFORE the content is read
     *  (design section 4.3 step 1: the 512 MB package cap is
     *  enforced before the whole input is in memory). */
    public static byte[] readAll(File f, long cap) throws IOException {
        long len = f.length();
        if (len > cap) {
            throw new IOException(
                    "input exceeds cap: " + len + " > " + cap + ": " + f);
        }
        return readAll(new FileInputStream(f), cap);
    }

    public static byte[] readAll(InputStream in, long cap)
            throws IOException {
        try (InputStream src = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = src.read(buf)) >= 0) {
                total += n;
                if (total > cap) {
                    throw new IOException("input exceeds cap: " + cap);
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /**
     * Atomic single-record update: write a temp file in the
     * same directory, fsync it, rename over the target, then
     * fsync the parent directory (the rename itself is not
     * durable until the directory is synced — design section
     * 4.3, live-manifest general rule, applied to every
     * atomically replaced state file).
     */
    public static void writeRecordAtomic(File target, byte[] content)
            throws IOException {
        File parent = target.getAbsoluteFile().getParentFile();
        if (parent != null) parent.mkdirs();
        // Audit H-3: the temp name is unique per call. A fixed
        // "<name>.tmp" let two concurrent writers (threads, or
        // the app's two processes) interleave writes into the
        // SAME temp file and rename a torn mixture into place.
        File tmp = new File(parent, target.getName() + ".tmp-"
                + Long.toHexString(System.nanoTime()) + "-"
                + Thread.currentThread().getId());
        boolean renamed = false;
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(content);
                out.getFD().sync();
            }
            if (!tmp.renameTo(target)) {
                // renameTo can fail across filesystems; same-dir
                // renames do not, so a failure here is a real error.
                throw new IOException(
                        "rename failed: " + tmp + " -> " + target);
            }
            renamed = true;
        } finally {
            if (!renamed && tmp.exists()) {
                // Best effort: a leftover unique temp is inert
                // (nothing reads "<name>.tmp-*" as the record).
                tmp.delete();
            }
        }
        if (parent != null) fsyncDir(parent);
    }

    /** Write a plain file and fsync it (for staged payload
     *  files and package copies, whose durability the boundary
     *  completes separately). */
    public static void writeFileSync(File target, byte[] content)
            throws IOException {
        File parent = target.getAbsoluteFile().getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(content);
            out.getFD().sync();
        }
    }

    /**
     * The adoption procedure (design section 4.2): before a
     * record's content may ground an operation, fsync the file
     * and its parent directory, then re-read it. Returns the
     * re-read bytes. Callers compare against what they observed
     * before; a mismatch (or a sync failure) means adoption did
     * NOT complete and the record must not be acted on.
     */
    public static byte[] adopt(File record) throws IOException {
        fsync(record);
        File parent = record.getAbsoluteFile().getParentFile();
        if (parent != null) fsyncDir(parent);
        return readAll(record, 64L * 1024 * 1024);
    }

    // ---------------- framed append-only journal ----------------

    /**
     * Frame format for append-only transfer journals (the
     * promotion journal): each line is the canonical JSON of
     * the entry object plus a "frame_checksum" field holding
     * the lowercase hex SHA-256 of the canonical bytes of the
     * entry WITHOUT that field. A line is complete only if it
     * ends with '\n', parses, and its checksum recomputes —
     * length-complete but checksum-mismatching tail lines are
     * frame-invalid (a torn write), exactly as the design's
     * frame/content distinction requires; frame-valid lines
     * whose CONTENT contradicts other records are never cut
     * here (that judgement belongs to the recovery classifier).
     */
    public static final String FRAME_CHECKSUM = "frame_checksum";

    public static String frameLine(Map<String, Object> entry) {
        Map<String, Object> body = new LinkedHashMap<>(entry);
        body.remove(FRAME_CHECKSUM);
        String sum = Hex.encode(DepinCore.sha256(Json.canonicalBytes(body)));
        Map<String, Object> framed = new LinkedHashMap<>(body);
        framed.put(FRAME_CHECKSUM, sum);
        return Json.canonical(framed);
    }

    /** Append one framed entry and fsync the file. */
    public static void appendFramed(File journal, Map<String, Object> entry)
            throws IOException {
        File parent = journal.getAbsoluteFile().getParentFile();
        if (parent != null) parent.mkdirs();
        byte[] line = (frameLine(entry) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(journal, true)) {
            out.write(line);
            out.getFD().sync();
        }
    }

    /** Result of reading a framed journal. */
    public static final class FramedRead {
        /** Frame-valid entries, in order (checksum field
         *  stripped). */
        public final List<Map<String, Object>> entries =
                new ArrayList<>();
        /** True when bytes after the last frame-valid line are
         *  a torn tail (incomplete line, unparseable line, or a
         *  complete-looking line whose checksum fails). */
        public boolean tornTail = false;
        /** Byte length of the frame-valid prefix (the torn tail
         *  starts here). */
        public long validPrefixBytes = 0;
        /** True when a NON-tail line is frame-invalid: evidence
         *  damage, not a torn write. */
        public boolean midDamage = false;
    }

    /** Maximum bytes of one framed-journal line (design section
     *  3.4: line-bounded inputs are capped at 1 MiB before
     *  allocation). */
    public static final int MAX_LINE_BYTES = 1024 * 1024;

    public static FramedRead readFramed(File journal) throws IOException {
        FramedRead result = new FramedRead();
        if (!journal.exists()) return result;
        byte[] all = readAll(journal, 512L * 1024 * 1024);
        int pos = 0;
        int lineIndex = 0;
        while (pos < all.length) {
            int nl = indexOf(all, (byte) '\n', pos);
            boolean terminated = nl >= 0;
            int lineEnd = terminated ? nl : all.length;
            int lineLen = lineEnd - pos;
            boolean isLast = !terminated || nl == all.length - 1;
            Map<String, Object> entry = null;
            boolean frameOk = false;
            if (lineLen > 0 && lineLen <= MAX_LINE_BYTES) {
                try {
                    String line = new String(all, pos, lineLen,
                            StandardCharsets.UTF_8);
                    Map<String, Object> parsed = Json.parseObject(line);
                    Object sum = parsed.remove(FRAME_CHECKSUM);
                    if (sum instanceof String) {
                        String expect = Hex.encode(DepinCore.sha256(
                                Json.canonicalBytes(parsed)));
                        frameOk = expect.equals(sum);
                        if (frameOk) entry = parsed;
                    }
                } catch (RuntimeException e) {
                    frameOk = false;
                }
            }
            if (!terminated || !frameOk) {
                // A line that does not end the file cleanly, or
                // whose frame does not verify: if anything
                // frame-valid follows it, this is mid-file
                // damage; otherwise it is the torn tail.
                if (!isLast && hasValidLineAfter(all, nl + 1)) {
                    result.midDamage = true;
                } else {
                    result.tornTail = true;
                }
                result.validPrefixBytes = pos;
                return result;
            }
            result.entries.add(entry);
            pos = nl + 1;
            result.validPrefixBytes = pos;
            lineIndex++;
        }
        return result;
    }

    private static boolean hasValidLineAfter(byte[] all, int from) {
        int pos = from;
        while (pos < all.length) {
            int nl = indexOf(all, (byte) '\n', pos);
            if (nl < 0) return false;
            int lineLen = nl - pos;
            if (lineLen > 0 && lineLen <= MAX_LINE_BYTES) {
                try {
                    String line = new String(all, pos, lineLen,
                            StandardCharsets.UTF_8);
                    Map<String, Object> parsed = Json.parseObject(line);
                    Object sum = parsed.remove(FRAME_CHECKSUM);
                    if (sum instanceof String) {
                        String expect = Hex.encode(DepinCore.sha256(
                                Json.canonicalBytes(parsed)));
                        if (expect.equals(sum)) return true;
                    }
                } catch (RuntimeException ignored) {
                    // keep scanning
                }
            }
            pos = nl + 1;
        }
        return false;
    }

    private static int indexOf(byte[] a, byte b, int from) {
        for (int i = Math.max(from, 0); i < a.length; i++) {
            if (a[i] == b) return i;
        }
        return -1;
    }

    /** Truncate a framed journal back to its frame-valid
     *  prefix (recovery repair for a torn tail; the truncation
     *  itself is the caller's to log, per the design). */
    public static void truncateTo(File f, long length) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.setLength(length);
            raf.getFD().sync();
        }
        File parent = f.getAbsoluteFile().getParentFile();
        if (parent != null) fsyncDir(parent);
    }

    /** Total size of a directory tree (for capacity checks). */
    public static long treeSize(File f) {
        if (!f.exists()) return 0;
        if (f.isFile()) return f.length();
        long total = 0;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) total += treeSize(k);
        }
        return total;
    }

    /** Recursively delete a file or directory tree. */
    public static void deleteTree(File f) throws IOException {
        if (!f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteTree(k);
            }
        }
        if (!f.delete()) {
            throw new IOException("delete failed: " + f);
        }
    }
}
