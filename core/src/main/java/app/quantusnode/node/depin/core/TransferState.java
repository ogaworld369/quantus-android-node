package app.quantusnode.node.depin.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The transfer record (design sections 4.2 / 4.3 / 4.4): the
 * single durable record that carries, per identity, the sender
 * state (S0/S1/S2 with the issued-permit substate), the
 * receiver state per generation (notified R1, permit intake
 * history, the trial record with its judgement field, refusal,
 * closure, restoration state, the R2 confirmation), the expiry
 * latches, and — at the top level — the active binding and this
 * installation's own install ID.
 *
 * Storage: one JSON document, transfer-state.json in the store
 * root, updated ONLY by single-record atomic replacement +
 * fsync (TransferIo.writeRecordAtomic). Every mutating
 * operation in the state machines is expressed as one document
 * update followed by save(); compound design steps that the
 * design binds into "the same update" (e.g. refusal + abandon
 * value + trial stage + restoration state; S2 transition + ack
 * value; R2 confirmation + active switch) are literally one
 * save() here.
 *
 * Adoption (section 4.2): {@link #adopt()} fsyncs the file and
 * its parent, re-reads, and reports whether the re-read content
 * equals what this instance holds. Operations the design lists
 * as adoption-gated (opening a writable journal, erasing vault
 * entries, re-presenting messages, resuming recording after a
 * cancel) must call adopt() first and proceed only on a match.
 *
 * The initialisation record (section 4.2) is a SEPARATE small
 * file, transfer-init.json: the evidence of completed
 * introduction must live outside the transfer record so that
 * "initialised but transfer record lost" is distinguishable
 * from "still preparing".
 */
public final class TransferState {
    public static final String FILE_NAME = "transfer-state.json";
    public static final String INIT_FILE_NAME = "transfer-init.json";
    private static final String FORMAT = "quantus-depin-transfer-state";
    private static final String INIT_FORMAT =
            "quantus-depin-transfer-init";

    private final File storeRoot;
    private Map<String, Object> doc;

    private TransferState(File storeRoot, Map<String, Object> doc) {
        this.storeRoot = storeRoot;
        this.doc = doc;
    }

    public File file() {
        return new File(storeRoot, FILE_NAME);
    }

    public File storeRoot() {
        return storeRoot;
    }

    /** Load the record if present; otherwise a fresh document
     *  (not yet persisted). Corrupt content raises IOException
     *  — a record that cannot be read is NEVER silently reset
     *  (design section 4.2: do not fall back to S0 by guess). */
    public static TransferState load(File storeRoot) throws IOException {
        File f = new File(storeRoot, FILE_NAME);
        if (!f.exists()) {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("format", FORMAT);
            doc.put("version", 1L);
            doc.put("identities", new LinkedHashMap<String, Object>());
            return new TransferState(storeRoot, doc);
        }
        Map<String, Object> doc = parse(f);
        return new TransferState(storeRoot, doc);
    }

    private static Map<String, Object> parse(File f) throws IOException {
        Map<String, Object> doc = Json.parseObject(new String(
                TransferIo.readAll(f, 64L * 1024 * 1024),
                StandardCharsets.UTF_8));
        if (!FORMAT.equals(doc.get("format"))) {
            throw new IOException("transfer state format mismatch");
        }
        return doc;
    }

    /** True when a transfer-state file exists on disk. */
    public boolean existsOnDisk() {
        return file().exists();
    }

    /** The adoption procedure: fsync file + parent, re-read,
     *  and adopt the re-read content when it differs. Returns
     *  true when the re-read content is byte-identical to what
     *  this instance last persisted or loaded (i.e. the state
     *  observed is durable). On false, this instance has been
     *  refreshed to the durable content and the caller must
     *  re-evaluate from it — the previously observed state may
     *  have been rolled back by a power loss. */
    public synchronized boolean adopt() throws IOException {
        File f = file();
        if (!f.exists()) {
            return !existsInMemory();
        }
        byte[] reread = TransferIo.adopt(f);
        Map<String, Object> durable = Json.parseObject(
                new String(reread, StandardCharsets.UTF_8));
        boolean identical = TransferCodec.jcs(durable)
                .length == TransferCodec.jcs(doc).length
                && java.util.Arrays.equals(
                        TransferCodec.jcs(durable), TransferCodec.jcs(doc));
        doc = durable;
        return identical;
    }

    private boolean existsInMemory() {
        return doc.containsKey("install_id")
                || doc.containsKey("active")
                || !identities().isEmpty();
    }

    /** Persist the current document as one atomic update. */
    public synchronized void save() throws IOException {
        storeRoot.mkdirs();
        TransferIo.writeRecordAtomic(file(), TransferCodec.jcs(doc));
    }

    // ---------------- navigation helpers ----------------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> child(Map<String, Object> m,
                                            String key) {
        Object v = m.get(key);
        return (v instanceof Map) ? (Map<String, Object>) v : null;
    }

    public static Map<String, Object> childOrCreate(
            Map<String, Object> m, String key) {
        Map<String, Object> c = child(m, key);
        if (c == null) {
            c = new LinkedHashMap<>();
            m.put(key, c);
        }
        return c;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> listOrCreate(Map<String, Object> m,
                                            String key) {
        Object v = m.get(key);
        if (v instanceof List) return (List<Object>) v;
        List<Object> l = new ArrayList<>();
        m.put(key, l);
        return l;
    }

    public static long longOr(Map<String, Object> m, String key, long def) {
        Object v = m.get(key);
        return (v instanceof Number) ? ((Number) v).longValue() : def;
    }

    public static String stringOr(Map<String, Object> m, String key,
                                  String def) {
        Object v = m.get(key);
        return (v instanceof String) ? (String) v : def;
    }

    public static boolean boolOr(Map<String, Object> m, String key,
                                 boolean def) {
        Object v = m.get(key);
        return (v instanceof Boolean) ? (Boolean) v : def;
    }

    // ---------------- top level ----------------

    public synchronized Map<String, Object> doc() {
        return doc;
    }

    public synchronized String installId() {
        return stringOr(doc, "install_id", null);
    }

    public synchronized void setInstallId(String installId)
            throws IOException {
        doc.put("install_id", installId);
        save();
    }

    /** The active binding: {device_id, generation, role,
     *  holding} or null when no identity is bound yet. */
    public synchronized Map<String, Object> active() {
        return child(doc, "active");
    }

    public synchronized void setActive(Map<String, Object> active)
            throws IOException {
        if (active == null) {
            doc.remove("active");
        } else {
            doc.put("active", active);
        }
        save();
    }

    public static Map<String, Object> binding(String deviceId,
                                              long generation,
                                              String role,
                                              String holding) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("device_id", deviceId);
        b.put("generation", generation);
        b.put("role", role);
        b.put("holding", holding);
        return b;
    }

    // ---------------- per-identity sections ----------------

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> identities() {
        Object v = doc.get("identities");
        if (v instanceof Map) return (Map<String, Object>) v;
        Map<String, Object> m = new LinkedHashMap<>();
        doc.put("identities", m);
        return m;
    }

    public synchronized Map<String, Object> identity(String deviceId) {
        return child(identities(), deviceId);
    }

    public synchronized Map<String, Object> identityOrCreate(
            String deviceId) {
        return childOrCreate(identities(), deviceId);
    }

    public synchronized long maxCommittedGeneration(String deviceId) {
        Map<String, Object> id = identity(deviceId);
        return (id == null)
                ? 0 : longOr(id, "max_committed_generation", 0);
    }

    /** The current sender record for an identity, or null. */
    public synchronized Map<String, Object> sender(String deviceId) {
        Map<String, Object> id = identity(deviceId);
        return (id == null) ? null : child(id, "sender");
    }

    /** The receiver record for (identity, generation), or
     *  null. */
    public synchronized Map<String, Object> receiver(String deviceId,
                                                     long generation) {
        Map<String, Object> id = identity(deviceId);
        if (id == null) return null;
        Map<String, Object> receivers = child(id, "receivers");
        return (receivers == null)
                ? null : child(receivers, Long.toString(generation));
    }

    public synchronized Map<String, Object> receiverOrCreate(
            String deviceId, long generation) {
        Map<String, Object> id = identityOrCreate(deviceId);
        Map<String, Object> receivers = childOrCreate(id, "receivers");
        return childOrCreate(receivers, Long.toString(generation));
    }

    /** All receiver generations recorded for an identity. */
    public synchronized List<Long> receiverGenerations(String deviceId) {
        List<Long> out = new ArrayList<>();
        Map<String, Object> id = identity(deviceId);
        if (id == null) return out;
        Map<String, Object> receivers = child(id, "receivers");
        if (receivers == null) return out;
        for (String k : receivers.keySet()) {
            try {
                out.add(Long.parseLong(k));
            } catch (NumberFormatException ignored) {
                // A non-numeric key is not a generation; the
                // record's own validation surfaces corruption
                // elsewhere (fail-closed paths check shapes).
            }
        }
        out.sort(Long::compareTo);
        return out;
    }

    /** Expiry latches for an identity: permit full-value hash
     *  → {clock, value}. Lives in the transfer record, never
     *  in staging (design section 4.4: latches and refusals
     *  survive staging cleanup). */
    public synchronized Map<String, Object> latches(String deviceId) {
        Map<String, Object> id = identity(deviceId);
        return (id == null) ? null : child(id, "latches");
    }

    public synchronized Map<String, Object> latchesOrCreate(
            String deviceId) {
        return childOrCreate(identityOrCreate(deviceId), "latches");
    }

    public synchronized boolean hasLatch(String deviceId,
                                         String permitHash) {
        Map<String, Object> l = latches(deviceId);
        return l != null && l.containsKey(permitHash);
    }

    /** Record an expiry observation under the permit's
     *  full-value hash (one atomic update). Observations for a
     *  hash that already has one are kept (first observation
     *  stands; the latch is one-directional). */
    public synchronized void recordLatch(String deviceId,
                                         String permitHash, String clock,
                                         long value) throws IOException {
        Map<String, Object> l = latchesOrCreate(deviceId);
        if (!l.containsKey(permitHash)) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("clock", clock);
            e.put("value", value);
            l.put(permitHash, e);
            save();
        }
    }

    // ---------------- initialisation record ----------------

    /**
     * The initialisation record (design section 4.2): separate
     * file, states "preparing" / "complete", modes "new" /
     * "legacy_upgrade" / "first_receive". The pubkey hash may
     * be absent while preparing (it is fixed at the moment the
     * corresponding key material becomes durable, per mode).
     */
    public static final class InitRecord {
        public String initId;
        public String mode;
        public String vaultRef;
        public String pubkeyHash;   // nullable while preparing
        public String state;        // preparing | complete

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("format", INIT_FORMAT);
            m.put("version", 1L);
            m.put("init_id", initId);
            m.put("mode", mode);
            m.put("vault_ref", vaultRef);
            if (pubkeyHash != null) m.put("pubkey_hash", pubkeyHash);
            m.put("state", state);
            return m;
        }

        public static InitRecord fromMap(Map<String, Object> m) {
            if (!INIT_FORMAT.equals(m.get("format"))) {
                throw new IllegalArgumentException(
                        "init record format mismatch");
            }
            InitRecord r = new InitRecord();
            r.initId = stringOr(m, "init_id", null);
            r.mode = stringOr(m, "mode", null);
            r.vaultRef = stringOr(m, "vault_ref", null);
            r.pubkeyHash = stringOr(m, "pubkey_hash", null);
            r.state = stringOr(m, "state", null);
            if (r.initId == null || r.mode == null || r.state == null) {
                throw new IllegalArgumentException(
                        "init record is incomplete");
            }
            return r;
        }
    }

    public static InitRecord loadInit(File storeRoot) throws IOException {
        File f = new File(storeRoot, INIT_FILE_NAME);
        if (!f.exists()) return null;
        return InitRecord.fromMap(Json.parseObject(new String(
                TransferIo.readAll(f, 1024 * 1024),
                StandardCharsets.UTF_8)));
    }

    public static void saveInit(File storeRoot, InitRecord r)
            throws IOException {
        storeRoot.mkdirs();
        TransferIo.writeRecordAtomic(
                new File(storeRoot, INIT_FILE_NAME),
                TransferCodec.jcs(r.toMap()));
    }

    /** Adoption for the init record (same procedure). */
    public static InitRecord adoptInit(File storeRoot)
            throws IOException {
        File f = new File(storeRoot, INIT_FILE_NAME);
        if (!f.exists()) return null;
        byte[] reread = TransferIo.adopt(f);
        return InitRecord.fromMap(Json.parseObject(
                new String(reread, StandardCharsets.UTF_8)));
    }
}
