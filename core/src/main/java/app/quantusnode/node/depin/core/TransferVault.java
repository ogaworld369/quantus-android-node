package app.quantusnode.node.depin.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.crypto.SecretKey;

/**
 * The transfer vault: seed entries identified by (generation,
 * role) — plus initialisation-ID-bound entries — each wrapped
 * individually with the app's Keystore AES-GCM wrap key (via
 * {@link AesGcmSeedBox}), held in one atomically-updated file.
 *
 * This is deliberately a thin store: WHICH entries may be
 * erased, and when, is decided by the state machines under the
 * design's common erasure rule (section 4.2); this class only
 * guarantees that an entry's blob is separately wrapped (so one
 * entry's erasure never touches another entry's bytes) and that
 * updates are single-record atomic replacements with fsync.
 * The shared Keystore wrap KEY is never stored here and is
 * destroyed only under the common rule, which the state
 * machines evaluate — this class never destroys it.
 *
 * Entry keys are strings of the form:
 *   "sender:<generation>" / "receiver:<generation>" — per
 *       (generation, role) identity entries,
 *   "init:<initId>" — the initialisation-bound entry,
 *   "current" — never used for transfer entries; the app's
 *       pre-transfer identity lives in the app vault, not here.
 */
public final class TransferVault {
    public static final String FILE_NAME = "transfer-vault.json";
    private static final String FORMAT = "quantus-depin-transfer-vault";

    private final File file;
    private final SecretKey wrapKey;
    private final TreeMap<String, Map<String, Object>> entries =
            new TreeMap<>();

    public TransferVault(File storeRoot, SecretKey wrapKey)
            throws IOException {
        this.file = new File(storeRoot, FILE_NAME);
        this.wrapKey = wrapKey;
        load();
    }

    public File file() {
        return file;
    }

    private void load() throws IOException {
        entries.clear();
        if (!file.exists()) return;
        Map<String, Object> root = Json.parseObject(new String(
                TransferIo.readAll(file, 16L * 1024 * 1024),
                StandardCharsets.UTF_8));
        if (!FORMAT.equals(root.get("format"))) {
            throw new IOException("transfer vault format mismatch");
        }
        Object raw = root.get("entries");
        if (raw instanceof Map) {
            for (Map.Entry<String, Object> e :
                    Json.asObject(raw).entrySet()) {
                entries.put(e.getKey(), Json.asObject(e.getValue()));
            }
        }
    }

    /** Re-read from disk (after adoption syncs by the caller). */
    public void reload() throws IOException {
        load();
    }

    private void persist() throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", FORMAT);
        root.put("version", 1L);
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : entries.entrySet()) {
            m.put(e.getKey(), e.getValue());
        }
        root.put("entries", m);
        TransferIo.writeRecordAtomic(file,
                Json.canonicalBytes(root));
        sweepOrphanTemps();
    }

    /** Final check round 4 (I-08): remove temp files an
     *  interrupted vault write left behind. writeRecordAtomic
     *  names its temp uniquely per call (audit H-3), so a
     *  writer killed between the temp write and the rename
     *  leaves a full copy of the vault — every wrapped entry
     *  as it stood — that no later write ever overwrites. An
     *  entry erased afterwards was gone from the live file
     *  only; the orphan kept its wrap. Vault writers are
     *  serialised by the app layers (one transfer operation at
     *  a time), so once this write's own temp has been renamed
     *  into place every remaining sibling temp is an orphan.
     *  Best effort: a leftover that cannot be deleted now is
     *  retried by the next vault write. */
    private void sweepOrphanTemps() {
        try {
            File dir = file.getAbsoluteFile().getParentFile();
            File[] kids = dir == null ? null : dir.listFiles();
            if (kids == null) {
                return;
            }
            String prefix = FILE_NAME + ".tmp";
            for (File k : kids) {
                if (k.isFile() && k.getName().startsWith(prefix)) {
                    //noinspection ResultOfMethodCallIgnored
                    k.delete();
                }
            }
        } catch (RuntimeException ignored) {
            // Housekeeping; the next vault write sweeps again.
        }
    }

    public static String senderKey(long generation) {
        return "sender:" + generation;
    }

    public static String receiverKey(long generation) {
        return "receiver:" + generation;
    }

    public static String initKey(String initId) {
        return "init:" + initId;
    }

    public boolean has(String key) {
        return entries.containsKey(key);
    }

    public Set<String> keys() {
        return new java.util.TreeSet<>(entries.keySet());
    }

    /** Wrap and store a secret under {@code key}, replacing any
     *  previous entry (callers use this for the idempotent
     *  preparation steps the design defines; replacement of an
     *  entry with the SAME value is how preparation is redone). */
    public void put(String key, byte[] secret)
            throws IOException, GeneralSecurityException {
        AesGcmSeedBox.Wrapped w = AesGcmSeedBox.wrap(wrapKey, secret);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("iv", TransferCodec.b64Encode(w.iv));
        e.put("ct", TransferCodec.b64Encode(w.ciphertext));
        entries.put(key, e);
        persist();
    }

    /** Unwrap the secret stored under {@code key}, or null when
     *  absent. The caller owns the returned copy and zeroes it. */
    public byte[] get(String key)
            throws IOException, GeneralSecurityException {
        Map<String, Object> e = entries.get(key);
        if (e == null) return null;
        AesGcmSeedBox.Wrapped w = new AesGcmSeedBox.Wrapped(
                TransferCodec.b64DecodeStrict(
                        Json.asString(e.get("iv")), "vault.iv"),
                TransferCodec.b64DecodeStrict(
                        Json.asString(e.get("ct")), "vault.ct"));
        return AesGcmSeedBox.unwrap(wrapKey, w);
    }

    /** Erase one entry's blob. Never touches other entries and
     *  never the shared wrap key. */
    public void delete(String key) throws IOException {
        if (entries.remove(key) != null) {
            persist();
        }
    }
}
