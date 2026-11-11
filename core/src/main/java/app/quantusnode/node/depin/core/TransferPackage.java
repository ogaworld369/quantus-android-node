package app.quantusnode.node.depin.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.SecretKey;

/**
 * Transfer package format v1 and the six follow-up messages
 * (integration design section 4.1): strict schemas, exact key
 * sets at every level (unknown and duplicate keys are rejected
 * — duplicates by the JSON parser itself), the envelope, the
 * manifest, payload framing, and the message builders /
 * validators.
 *
 * Encoding discipline, fixed by the design and enforced here:
 *  - signature / salt / iv / payload fields are strict-profile
 *    Base64 strings;
 *  - hashes, public keys, MACs and install IDs are lowercase
 *    hex of fixed length and are NEVER Base64;
 *  - package_sha256 is computed from the RE-CONSTRUCTED JCS
 *    bytes of the parsed envelope (all 7 fields), never from
 *    the raw transport bytes;
 *  - writers emit the envelope's JCS bytes themselves as the
 *    file content (no trailing newline);
 *  - a signed message's signature input is the JCS bytes of
 *    the object without its signature field; a message's
 *    full-value hash covers the complete object.
 */
public final class TransferPackage {
    private TransferPackage() {}

    public static final String ENVELOPE_FORMAT = "quantus-depin-transfer";
    public static final String NOTICE_FORMAT =
            "quantus-depin-transfer-notice";
    public static final String PERMIT_FORMAT =
            "quantus-depin-transfer-permit";
    public static final String CANCEL_FORMAT =
            "quantus-depin-transfer-cancel";
    public static final String RECEIPT_FORMAT =
            "quantus-depin-transfer-receipt";
    public static final String ACK_FORMAT = "quantus-depin-transfer-ack";
    public static final String ABANDON_FORMAT =
            "quantus-depin-transfer-abandon";

    /** Package input cap (design section 3.5): 512 MB, checked
     *  before the input is read whole. */
    public static final long MAX_PACKAGE_BYTES = 512L * 1024 * 1024;
    /** Manifest cap (design section 3.4): 4 MiB. */
    public static final long MAX_MANIFEST_BYTES = 4L * 1024 * 1024;
    /** Design sections 3.4 / 4.3: migration staging cap (1 GB). */
    public static final long MAX_STAGING_BYTES = 1024L * 1024 * 1024;

    private static final Set<String> ENVELOPE_KEYS = keySet(
            "format", "version", "manifest", "signature", "salt",
            "iv", "payload");
    private static final Set<String> MANIFEST_KEYS = keySet(
            "device_id", "pubkey", "transfer_generation", "tail_seq",
            "tail_checkpoint_hash", "tail_timestamp_unix",
            "checkpoint_count", "archives", "anchor_bundle_hash",
            "anchor_created_at_unix", "anchor_params",
            "created_at_unix", "sender_app");
    private static final Set<String> ARCHIVE_KEYS = keySet(
            "id", "version", "count", "tail_checkpoint_hash", "sha256");
    private static final Set<String> ANCHOR_PARAM_KEYS = keySet(
            "hash", "signature", "iterations_per_checkpoint",
            "checkpoint_interval_sec");

    // ---------------- strict schema helpers ----------------

    static Set<String> keySet(String... keys) {
        return new HashSet<>(Arrays.asList(keys));
    }

    static void requireExactKeys(Map<String, Object> m,
                                 Set<String> keys, String what) {
        for (String k : m.keySet()) {
            if (!keys.contains(k)) {
                throw new IllegalArgumentException(
                        what + ": unknown key: " + k);
            }
        }
        for (String k : keys) {
            if (!m.containsKey(k)) {
                throw new IllegalArgumentException(
                        what + ": missing key: " + k);
            }
        }
    }

    static long requireInt(Map<String, Object> m, String key,
                           long min, String what) {
        Object v = m.get(key);
        if (!(v instanceof Long)) {
            throw new IllegalArgumentException(
                    what + ": " + key + " must be an integer");
        }
        long n = (Long) v;
        if (n < min) {
            throw new IllegalArgumentException(
                    what + ": " + key + " below minimum " + min);
        }
        return n;
    }

    static String requireString(Map<String, Object> m, String key,
                                String what) {
        Object v = m.get(key);
        if (!(v instanceof String)) {
            throw new IllegalArgumentException(
                    what + ": " + key + " must be a string");
        }
        return (String) v;
    }

    // ---------------- manifest ----------------

    public static final class ArchiveEntry {
        public String id;
        public String version;
        public long count;
        public String tailCheckpointHash;
        public String sha256;
    }

    public static final class Manifest {
        public String deviceId;
        public byte[] pubkey;
        public long generation;
        public long tailSeq;
        public String tailCheckpointHash;
        public long tailTimestampUnix;
        public long checkpointCount;
        public final List<ArchiveEntry> archives = new ArrayList<>();
        public String anchorBundleHash;
        public long anchorCreatedAtUnix;
        public Map<String, Object> anchorParams;
        public long createdAtUnix;
        public String senderApp;
        /** The parsed manifest object, retained so its JCS
         *  bytes (signature input / AAD) are recomputed from
         *  the values, never from transport bytes. */
        public Map<String, Object> raw;

        public byte[] jcsBytes() {
            return TransferCodec.jcs(raw);
        }
    }

    /** Archive ID rule (design section 4.1): the archive file
     *  name itself; [A-Za-z0-9._-]{1,64}; "." and ".." excluded. */
    public static boolean isArchiveId(String id) {
        if (id == null || id.isEmpty() || id.length() > 64) return false;
        if (id.equals(".") || id.equals("..")) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    public static Manifest validateManifest(Map<String, Object> m) {
        requireExactKeys(m, MANIFEST_KEYS, "manifest");
        Manifest out = new Manifest();
        out.raw = m;
        out.deviceId = requireString(m, "device_id", "manifest");
        out.pubkey = TransferCodec.hexBytes(
                requireString(m, "pubkey", "manifest"), 32, "manifest.pubkey");
        if (!DepinCore.deviceIdFromPubkey(out.pubkey).equals(out.deviceId)) {
            throw new IllegalArgumentException(
                    "manifest: device_id does not derive from pubkey");
        }
        out.generation = requireInt(
                m, "transfer_generation", 1, "manifest");
        out.tailSeq = requireInt(m, "tail_seq", 0, "manifest");
        out.tailCheckpointHash = requireString(
                m, "tail_checkpoint_hash", "manifest");
        if (!TransferCodec.isHex(out.tailCheckpointHash, 32)) {
            throw new IllegalArgumentException(
                    "manifest: tail_checkpoint_hash form");
        }
        out.tailTimestampUnix = requireInt(
                m, "tail_timestamp_unix", 0, "manifest");
        out.checkpointCount = requireInt(
                m, "checkpoint_count", 0, "manifest");
        Object rawArchives = m.get("archives");
        if (!(rawArchives instanceof List)) {
            throw new IllegalArgumentException("manifest: archives not a list");
        }
        Set<String> seenIds = new HashSet<>();
        for (Object o : Json.asArray(rawArchives)) {
            Map<String, Object> a = Json.asObject(o);
            requireExactKeys(a, ARCHIVE_KEYS, "manifest.archives[]");
            ArchiveEntry e = new ArchiveEntry();
            e.id = requireString(a, "id", "manifest.archives[]");
            if (!isArchiveId(e.id)) {
                throw new IllegalArgumentException(
                        "manifest: invalid archive id: " + e.id);
            }
            if (!seenIds.add(e.id)) {
                throw new IllegalArgumentException(
                        "manifest: duplicate archive id: " + e.id);
            }
            e.version = requireString(a, "version", "manifest.archives[]");
            e.count = requireInt(a, "count", 0, "manifest.archives[]");
            e.tailCheckpointHash = requireString(
                    a, "tail_checkpoint_hash", "manifest.archives[]");
            if (!TransferCodec.isHex(e.tailCheckpointHash, 32)) {
                throw new IllegalArgumentException(
                        "manifest: archive tail_checkpoint_hash form");
            }
            e.sha256 = requireString(a, "sha256", "manifest.archives[]");
            if (!TransferCodec.isHex(e.sha256, 32)) {
                throw new IllegalArgumentException(
                        "manifest: archive sha256 form");
            }
            out.archives.add(e);
        }
        out.anchorBundleHash = requireString(
                m, "anchor_bundle_hash", "manifest");
        if (!TransferCodec.isHex(out.anchorBundleHash, 32)) {
            throw new IllegalArgumentException(
                    "manifest: anchor_bundle_hash form");
        }
        out.anchorCreatedAtUnix = requireInt(
                m, "anchor_created_at_unix", 0, "manifest");
        Map<String, Object> params = Json.asObject(m.get("anchor_params"));
        requireExactKeys(params, ANCHOR_PARAM_KEYS, "manifest.anchor_params");
        requireString(params, "hash", "manifest.anchor_params");
        requireString(params, "signature", "manifest.anchor_params");
        requireInt(params, "iterations_per_checkpoint", 1,
                "manifest.anchor_params");
        requireInt(params, "checkpoint_interval_sec", 1,
                "manifest.anchor_params");
        out.anchorParams = params;
        out.createdAtUnix = requireInt(m, "created_at_unix", 0, "manifest");
        out.senderApp = requireString(m, "sender_app", "manifest");
        return out;
    }

    // ---------------- envelope ----------------

    public static final class Envelope {
        public Map<String, Object> raw;
        public Manifest manifest;
        public byte[] signature;
        public byte[] salt;
        public byte[] iv;
        public byte[] payloadSealed;

        /** package_sha256: from the re-constructed JCS bytes of
         *  the parsed envelope (all 7 fields). */
        public String packageSha256() {
            return Hex.encode(DepinCore.sha256(TransferCodec.jcs(raw)));
        }

        /** Verify the manifest signature under the manifest's
         *  own public key. */
        public boolean verifyManifestSignature() {
            return Ed25519.verify(manifest.pubkey,
                    manifest.jcsBytes(), signature);
        }
    }

    public static Envelope parseEnvelope(byte[] fileBytes) {
        Map<String, Object> env = Json.parseObject(new String(
                fileBytes, StandardCharsets.UTF_8));
        requireExactKeys(env, ENVELOPE_KEYS, "envelope");
        if (!ENVELOPE_FORMAT.equals(env.get("format"))) {
            throw new IllegalArgumentException("envelope: format mismatch");
        }
        Object version = env.get("version");
        if (!(version instanceof Long) || (Long) version != 1L) {
            throw new IllegalArgumentException("envelope: version must be 1");
        }
        Envelope out = new Envelope();
        out.raw = env;
        Map<String, Object> manifestMap = Json.asObject(env.get("manifest"));
        if (TransferCodec.jcs(manifestMap).length > MAX_MANIFEST_BYTES) {
            throw new IllegalArgumentException(
                    "envelope: manifest exceeds the 4 MiB cap");
        }
        out.manifest = validateManifest(manifestMap);
        out.signature = TransferCodec.b64DecodeStrict(
                requireString(env, "signature", "envelope"),
                "envelope.signature");
        if (out.signature.length != 64) {
            throw new IllegalArgumentException(
                    "envelope: signature must decode to 64 bytes");
        }
        out.salt = TransferCodec.b64DecodeStrict(
                requireString(env, "salt", "envelope"), "envelope.salt");
        if (out.salt.length != 16) {
            throw new IllegalArgumentException(
                    "envelope: salt must decode to 16 bytes");
        }
        out.iv = TransferCodec.b64DecodeStrict(
                requireString(env, "iv", "envelope"), "envelope.iv");
        if (out.iv.length != 12) {
            throw new IllegalArgumentException(
                    "envelope: iv must decode to 12 bytes");
        }
        out.payloadSealed = TransferCodec.b64DecodeStrict(
                requireString(env, "payload", "envelope"),
                "envelope.payload");
        return out;
    }

    /**
     * Build a complete package (sender side). The plaintext is
     * the fixed-order entry sequence: "seed",
     * "confirm_secret", "journal", then one entry per archive
     * in manifest order. Returns the envelope map; the file
     * content is its JCS bytes (the writer rule).
     */
    public static Map<String, Object> buildPackage(
            byte[] seed, Map<String, Object> manifestMap,
            byte[] confirmSecret, byte[] journalBytes,
            List<byte[]> archiveBytesInManifestOrder,
            String transferCode, SecureRandom random)
            throws GeneralSecurityException {
        Manifest manifest = validateManifest(manifestMap);
        if (archiveBytesInManifestOrder.size() != manifest.archives.size()) {
            throw new IllegalArgumentException(
                    "archive byte count does not match the manifest");
        }
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        TransferCodec.writePayloadEntry(plain, "seed", seed);
        TransferCodec.writePayloadEntry(
                plain, "confirm_secret", confirmSecret);
        TransferCodec.writePayloadEntry(plain, "journal", journalBytes);
        for (int i = 0; i < manifest.archives.size(); i++) {
            TransferCodec.writePayloadEntry(plain,
                    manifest.archives.get(i).id,
                    archiveBytesInManifestOrder.get(i));
        }
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] iv = new byte[12];
        random.nextBytes(iv);
        SecretKey key = TransferCodec.deriveKey(transferCode, salt);
        byte[] sealed = TransferCodec.sealPayload(
                key, iv, manifest.jcsBytes(), plain.toByteArray());
        byte[] signature = Ed25519.sign(seed, manifest.jcsBytes());
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("format", ENVELOPE_FORMAT);
        env.put("version", 1L);
        env.put("manifest", manifestMap);
        env.put("signature", TransferCodec.b64Encode(signature));
        env.put("salt", TransferCodec.b64Encode(salt));
        env.put("iv", TransferCodec.b64Encode(iv));
        env.put("payload", TransferCodec.b64Encode(sealed));
        return env;
    }

    /** Decrypt a parsed envelope's payload with a transfer
     *  code. Throws on GCM failure (wrong code, tampered
     *  manifest / ciphertext). */
    public static byte[] decryptPayload(Envelope env, String transferCode)
            throws GeneralSecurityException {
        SecretKey key = TransferCodec.deriveKey(transferCode, env.salt);
        return TransferCodec.openPayload(
                key, env.iv, env.manifest.jcsBytes(), env.payloadSealed);
    }

    // Final check round 3, H-07: the former
    // decryptPackageToFile + PayloadFileReader pair is
    // REMOVED. They decrypted the whole payload into a
    // plaintext file inside the staging root and streamed
    // the seed / confirm_secret entries out of it through
    // further plaintext temp files; a process death in that
    // window left raw key material on disk until a later
    // recovery swept it. Intake now decrypts in memory via
    // {@link #decryptPayload} (the envelope is already held
    // whole in memory by the caller) and extracts entries
    // from the buffer \u2014 see TransferReceiver's
    // expandPayloadInMemory. No code path in this build
    // writes a decrypted payload, seed or confirm_secret to
    // disk in plaintext.

    // ---------------- shared message validation ----------------

    private static long msgGeneration(Map<String, Object> m, String what) {
        return requireInt(m, "transfer_generation", 1, what);
    }

    private static String msgPackageSha(Map<String, Object> m, String what) {
        String s = requireString(m, "package_sha256", what);
        if (!TransferCodec.isHex(s, 32)) {
            throw new IllegalArgumentException(
                    what + ": package_sha256 form");
        }
        return s;
    }

    private static String msgInstallId(Map<String, Object> m, String what) {
        String s = requireString(m, "receiver_install_id", what);
        if (!TransferCodec.isInstallId(s)) {
            throw new IllegalArgumentException(
                    what + ": receiver_install_id form");
        }
        return s;
    }

    private static byte[] msgSignature(Map<String, Object> m, String what) {
        byte[] sig = TransferCodec.b64DecodeStrict(
                requireString(m, "signature", what), what + ".signature");
        if (sig.length != 64) {
            throw new IllegalArgumentException(
                    what + ": signature must decode to 64 bytes");
        }
        return sig;
    }

    private static void verifySigned(Map<String, Object> m, byte[] pubkey,
                                     String what) {
        byte[] sig = msgSignature(m, what);
        if (!Ed25519.verify(pubkey,
                TransferCodec.signatureInput(m), sig)) {
            throw new IllegalArgumentException(
                    what + ": signature verification failed");
        }
    }

    private static Map<String, Object> signedMessage(
            byte[] seed, Map<String, Object> body) {
        Map<String, Object> m = new LinkedHashMap<>(body);
        byte[] sig = Ed25519.sign(seed, TransferCodec.jcs(m));
        m.put("signature", TransferCodec.b64Encode(sig));
        return m;
    }

    // ---- (2) staging notice ----

    public static Map<String, Object> buildNotice(
            long generation, String packageSha256,
            String receiverInstallId, byte[] confirmSecret)
            throws GeneralSecurityException {
        byte[] mac = TransferCodec.hmacSha256(confirmSecret,
                TransferCodec.noticeMacInput(generation,
                        TransferCodec.hexBytes(
                                packageSha256, 32, "package_sha256"),
                        receiverInstallId));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", NOTICE_FORMAT);
        m.put("transfer_generation", generation);
        m.put("package_sha256", packageSha256);
        m.put("receiver_install_id", receiverInstallId);
        m.put("notice_mac", Hex.encode(mac));
        return m;
    }

    /** Validate a notice's schema (MAC checked separately
     *  against the sender's own confirm_secret). */
    public static void validateNotice(Map<String, Object> m) {
        requireExactKeys(m, keySet("format", "transfer_generation",
                "package_sha256", "receiver_install_id", "notice_mac"),
                "notice");
        if (!NOTICE_FORMAT.equals(m.get("format"))) {
            throw new IllegalArgumentException("notice: format mismatch");
        }
        msgGeneration(m, "notice");
        msgPackageSha(m, "notice");
        msgInstallId(m, "notice");
        String mac = requireString(m, "notice_mac", "notice");
        if (!TransferCodec.isHex(mac, 32)) {
            throw new IllegalArgumentException("notice: notice_mac form");
        }
    }

    /** Verify a notice MAC against a confirm_secret. */
    public static boolean verifyNoticeMac(Map<String, Object> m,
                                          byte[] confirmSecret)
            throws GeneralSecurityException {
        long gen = Json.asLong(m.get("transfer_generation"));
        byte[] pkg = TransferCodec.hexBytes(
                Json.asString(m.get("package_sha256")), 32,
                "package_sha256");
        String id = Json.asString(m.get("receiver_install_id"));
        byte[] expected = TransferCodec.hmacSha256(confirmSecret,
                TransferCodec.noticeMacInput(gen, pkg, id));
        byte[] presented = TransferCodec.hexBytes(
                Json.asString(m.get("notice_mac")), 32, "notice_mac");
        return TransferCodec.slowEquals(expected, presented);
    }

    // ---- (3) permit ----

    public static Map<String, Object> buildPermit(
            byte[] seed, long generation, String receiverInstallId,
            String packageSha256, long issuedAtUnix,
            long expiresAtUnix, long expiresAtFinalized) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", PERMIT_FORMAT);
        m.put("transfer_generation", generation);
        m.put("receiver_install_id", receiverInstallId);
        m.put("package_sha256", packageSha256);
        m.put("issued_at_unix", issuedAtUnix);
        m.put("expires_at_unix", expiresAtUnix);
        m.put("expires_at_finalized", expiresAtFinalized);
        return signedMessage(seed, m);
    }

    /** Validate a permit's schema AND signature (against the
     *  identity public key). Returns the permit map. */
    public static Map<String, Object> validatePermit(
            Map<String, Object> m, byte[] pubkey) {
        requireExactKeys(m, keySet("format", "transfer_generation",
                "receiver_install_id", "package_sha256", "issued_at_unix",
                "expires_at_unix", "expires_at_finalized", "signature"),
                "permit");
        if (!PERMIT_FORMAT.equals(m.get("format"))) {
            throw new IllegalArgumentException("permit: format mismatch");
        }
        msgGeneration(m, "permit");
        msgInstallId(m, "permit");
        msgPackageSha(m, "permit");
        requireInt(m, "issued_at_unix", 0, "permit");
        requireInt(m, "expires_at_unix", 0, "permit");
        requireInt(m, "expires_at_finalized", 0, "permit");
        verifySigned(m, pubkey, "permit");
        return m;
    }

    // ---- (4) cancel token ----

    public static Map<String, Object> buildCancelToken(
            byte[] seed, long generation, String packageSha256) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", CANCEL_FORMAT);
        m.put("transfer_generation", generation);
        m.put("package_sha256", packageSha256);
        return signedMessage(seed, m);
    }

    public static Map<String, Object> validateCancelToken(
            Map<String, Object> m, byte[] pubkey) {
        requireExactKeys(m, keySet("format", "transfer_generation",
                "package_sha256", "signature"), "cancel");
        if (!CANCEL_FORMAT.equals(m.get("format"))) {
            throw new IllegalArgumentException("cancel: format mismatch");
        }
        msgGeneration(m, "cancel");
        msgPackageSha(m, "cancel");
        verifySigned(m, pubkey, "cancel");
        return m;
    }

    // ---- (5) receipt ----

    public static Map<String, Object> buildReceipt(
            long generation, String receiverInstallId,
            String packageSha256, byte[] confirmSecret)
            throws GeneralSecurityException {
        byte[] mac = TransferCodec.hmacSha256(confirmSecret,
                TransferCodec.confirmMacInput(generation,
                        receiverInstallId,
                        TransferCodec.hexBytes(
                                packageSha256, 32, "package_sha256")));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", RECEIPT_FORMAT);
        m.put("transfer_generation", generation);
        m.put("receiver_install_id", receiverInstallId);
        m.put("package_sha256", packageSha256);
        m.put("confirmation_mac", Hex.encode(mac));
        return m;
    }

    public static void validateReceipt(Map<String, Object> m) {
        requireExactKeys(m, keySet("format", "transfer_generation",
                "receiver_install_id", "package_sha256",
                "confirmation_mac"), "receipt");
        if (!RECEIPT_FORMAT.equals(m.get("format"))) {
            throw new IllegalArgumentException("receipt: format mismatch");
        }
        msgGeneration(m, "receipt");
        msgInstallId(m, "receipt");
        msgPackageSha(m, "receipt");
        String mac = requireString(m, "confirmation_mac", "receipt");
        if (!TransferCodec.isHex(mac, 32)) {
            throw new IllegalArgumentException(
                    "receipt: confirmation_mac form");
        }
    }

    /** Verify a receipt's confirmation MAC from the canonical
     *  values (never from transport spellings). */
    public static boolean verifyReceiptMac(Map<String, Object> m,
                                           byte[] confirmSecret)
            throws GeneralSecurityException {
        long gen = Json.asLong(m.get("transfer_generation"));
        String id = Json.asString(m.get("receiver_install_id"));
        byte[] pkg = TransferCodec.hexBytes(
                Json.asString(m.get("package_sha256")), 32,
                "package_sha256");
        byte[] expected = TransferCodec.hmacSha256(confirmSecret,
                TransferCodec.confirmMacInput(gen, id, pkg));
        byte[] presented = TransferCodec.hexBytes(
                Json.asString(m.get("confirmation_mac")), 32,
                "confirmation_mac");
        return TransferCodec.slowEquals(expected, presented);
    }

    // ---- (6) completion ack ----

    public static Map<String, Object> buildAck(
            byte[] seed, long generation, String packageSha256,
            String receiverInstallId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", ACK_FORMAT);
        m.put("transfer_generation", generation);
        m.put("package_sha256", packageSha256);
        m.put("receiver_install_id", receiverInstallId);
        return signedMessage(seed, m);
    }

    public static Map<String, Object> validateAck(
            Map<String, Object> m, byte[] pubkey) {
        requireExactKeys(m, keySet("format", "transfer_generation",
                "package_sha256", "receiver_install_id", "signature"),
                "ack");
        if (!ACK_FORMAT.equals(m.get("format"))) {
            throw new IllegalArgumentException("ack: format mismatch");
        }
        msgGeneration(m, "ack");
        msgPackageSha(m, "ack");
        msgInstallId(m, "ack");
        verifySigned(m, pubkey, "ack");
        return m;
    }

    // ---- (7) abandon (cannot-confirm response) ----

    /**
     * Build an abandon response. {@code permitSha256} is the
     * full-value hash of the permit being answered, or null for
     * the NONE form — in which case the key is ABSENT from the
     * object (never JSON null).
     */
    public static Map<String, Object> buildAbandon(
            byte[] seed, long generation, String packageSha256,
            String receiverInstallId, String permitSha256OrNull) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", ABANDON_FORMAT);
        m.put("transfer_generation", generation);
        m.put("package_sha256", packageSha256);
        m.put("receiver_install_id", receiverInstallId);
        if (permitSha256OrNull != null) {
            if (!TransferCodec.isHex(permitSha256OrNull, 32)) {
                throw new IllegalArgumentException(
                        "abandon: permit_sha256 form");
            }
            m.put("permit_sha256", permitSha256OrNull);
        }
        return signedMessage(seed, m);
    }

    public static Map<String, Object> validateAbandon(
            Map<String, Object> m, byte[] pubkey) {
        Set<String> keys = keySet("format",
                "transfer_generation", "package_sha256",
                "receiver_install_id", "signature");
        for (String k : m.keySet()) {
            if (!keys.contains(k) && !k.equals("permit_sha256")) {
                throw new IllegalArgumentException(
                        "abandon: unknown key: " + k);
            }
        }
        for (String k : keys) {
            if (!m.containsKey(k)) {
                throw new IllegalArgumentException(
                        "abandon: missing key: " + k);
            }
        }
        if (!ABANDON_FORMAT.equals(m.get("format"))) {
            throw new IllegalArgumentException("abandon: format mismatch");
        }
        msgGeneration(m, "abandon");
        msgPackageSha(m, "abandon");
        msgInstallId(m, "abandon");
        if (m.containsKey("permit_sha256")) {
            Object v = m.get("permit_sha256");
            if (!(v instanceof String)
                    || !TransferCodec.isHex((String) v, 32)) {
                throw new IllegalArgumentException(
                        "abandon: permit_sha256 must be lowercase hex "
                                + "64 chars when present (null is invalid)");
            }
        }
        verifySigned(m, pubkey, "abandon");
        return m;
    }

    /** Parse a message file (any of the six): the file content
     *  is a single JSON object. Re-serialisation differences are
     *  irrelevant — all bindings are computed from parsed
     *  values. */
    public static Map<String, Object> parseMessage(byte[] fileBytes) {
        if (fileBytes.length > MAX_MANIFEST_BYTES) {
            throw new IllegalArgumentException(
                    "message exceeds the 4 MiB cap");
        }
        return Json.parseObject(new String(
                fileBytes, StandardCharsets.UTF_8));
    }
}
