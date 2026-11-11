package app.quantusnode.node.depin.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Transfer format v1 primitives (integration design section 4.1):
 * JCS canonical JSON, the strict Base64 profile, the transfer
 * code, key derivation, the AES-GCM payload cipher binding, the
 * two domain-separated HMACs, and the full-value hash rule.
 *
 * Everything here is byte-level and side-effect free; the state
 * machines (TransferSender / TransferReceiver) build on it. The
 * rules pinned by the design and enforced here:
 *
 *  - Canonical JSON is JCS (RFC 8785). For the value shapes this
 *    format allows (ASCII keys, integers, strings, booleans,
 *    arrays, objects) the existing Json.canonical writer already
 *    produces JCS bytes: keys sort by UTF-16 code unit (String's
 *    natural order), strings escape only what JCS mandates and
 *    keep non-ASCII as raw UTF-8, integers print in decimal.
 *    jcs() additionally REJECTS non-integral numbers outright —
 *    the transfer schemas admit integers only, so a Double in a
 *    value being canonicalised is a programming error, never a
 *    value to normalise.
 *  - Base64 fields use RFC 4648 section 4, padding present, no
 *    whitespace. Decoding is strict: the decoded bytes are
 *    re-encoded and the string must match EXACTLY (this rejects
 *    non-canonical trailing-bit variants such as "CB==" for
 *    "CA==", missing padding, and MIME line breaks).
 *  - Hex fields are never Base64 and vice versa; each schema
 *    field's encoding is fixed by TransferPackage / the message
 *    builders, and validators check the exact form.
 *  - The full-value hash of a message is SHA-256 over the JCS
 *    bytes of the COMPLETE object (signature included). A signed
 *    message's signature input is the JCS bytes of the object
 *    WITHOUT the signature field.
 */
public final class TransferCodec {
    private TransferCodec() {}

    // ---------------- JCS ----------------

    /** JCS canonical bytes of a transfer-format value. Rejects
     *  non-integral numbers anywhere in the structure. */
    public static byte[] jcs(Object value) {
        requireJcsValue(value);
        return Json.canonicalBytes(value);
    }

    /** JCS canonical bytes as a String (the writer form of a
     *  package file: the envelope's canonical bytes themselves,
     *  no trailing newline). */
    /** Canonical-bytes equality (byte[] has no value equals). */
    public static boolean jcsEquals(Object a, Object b) {
        return java.util.Arrays.equals(jcs(a), jcs(b));
    }

    public static String jcsString(Object value) {
        return new String(jcs(value), StandardCharsets.UTF_8);
    }

    private static void requireJcsValue(Object v) {
        if (v == null || v instanceof String || v instanceof Boolean
                || v instanceof Long || v instanceof Integer) {
            return;
        }
        if (v instanceof Map) {
            for (Object x : ((Map<?, ?>) v).values()) requireJcsValue(x);
            return;
        }
        if (v instanceof List) {
            for (Object x : (List<?>) v) requireJcsValue(x);
            return;
        }
        throw new IllegalArgumentException(
                "value is not JCS-representable in transfer format v1: "
                        + v.getClass().getName());
    }

    /** Full-value hash (design section 4.1): SHA-256 of the JCS
     *  bytes of the complete message object, signature included,
     *  as lowercase hex. */
    public static String fullValueHash(Map<String, Object> message) {
        return Hex.encode(DepinCore.sha256(jcs(message)));
    }

    /** Signature input for a signed message: JCS bytes of the
     *  object with the signature field removed. */
    public static byte[] signatureInput(Map<String, Object> message) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>(message);
        copy.remove("signature");
        return jcs(copy);
    }

    // ---------------- strict Base64 ----------------

    private static final Base64.Encoder B64_ENC = Base64.getEncoder();
    private static final Base64.Decoder B64_DEC = Base64.getDecoder();

    public static String b64Encode(byte[] data) {
        return B64_ENC.encodeToString(data);
    }

    /**
     * Strict Base64 decode (design section 4.1 profile): the
     * basic decoder already rejects characters outside the
     * standard alphabet (including CR/LF and whitespace); the
     * re-encode comparison additionally requires the string to
     * be the canonical encoding of its bytes, which rejects
     * padding deviations and non-zero trailing bits.
     */
    public static byte[] b64DecodeStrict(String s, String field) {
        if (s == null) {
            throw new IllegalArgumentException(field + ": missing base64 value");
        }
        final byte[] decoded;
        try {
            decoded = B64_DEC.decode(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + ": invalid base64", e);
        }
        if (!B64_ENC.encodeToString(decoded).equals(s)) {
            throw new IllegalArgumentException(
                    field + ": base64 is not the canonical encoding");
        }
        return decoded;
    }

    // ---------------- hex field forms ----------------

    /** A lowercase hex string of exactly {@code bytes} bytes. */
    public static boolean isHex(String s, int bytes) {
        if (s == null || s.length() != bytes * 2) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!ok) return false;
        }
        return true;
    }

    public static byte[] hexBytes(String s, int bytes, String field) {
        if (!isHex(s, bytes)) {
            throw new IllegalArgumentException(
                    field + ": expected lowercase hex of " + bytes + " bytes");
        }
        return Hex.decode(s);
    }

    /** An installation ID: 128 bits as 32 lowercase hex chars. */
    public static boolean isInstallId(String s) {
        return isHex(s, 16);
    }

    public static String newInstallId(SecureRandom random) {
        byte[] b = new byte[16];
        random.nextBytes(b);
        return Hex.encode(b);
    }

    // ---------------- transfer code ----------------

    /** The transfer-code alphabet (design section 4.1): Base32
     *  style, ambiguous letters I, L, O, U excluded. */
    public static final String CODE_ALPHABET =
            "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    public static final int CODE_LENGTH = 12;
    /** PBKDF2 iteration count for the transfer-code KDF. */
    public static final int KDF_ITERATIONS = 600_000;

    public static String generateCode(SecureRandom random) {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        byte[] one = new byte[1];
        for (int i = 0; i < CODE_LENGTH; i++) {
            random.nextBytes(one);
            // 256 = 8 * 32, so the low 5 bits are uniform.
            sb.append(CODE_ALPHABET.charAt(one[0] & 0x1f));
        }
        return sb.toString();
    }

    /** Display form: groups separated by hyphens (display only;
     *  the canonical form has no separators). */
    public static String codeForDisplay(String canonicalCode) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < canonicalCode.length(); i++) {
            if (i > 0 && i % 4 == 0) sb.append('-');
            sb.append(canonicalCode.charAt(i));
        }
        return sb.toString();
    }

    /**
     * Normalise user input to the canonical code form: hyphens
     * are removed; NOTHING else is transformed (no case folding,
     * no whitespace stripping). Returns null when the result is
     * not a well-formed code (wrong length, character outside
     * the alphabet, lowercase) — such input is not accepted as
     * a code at all.
     */
    public static String normalizeCode(String input) {
        if (input == null) return null;
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '-') continue;
            sb.append(c);
        }
        String s = sb.toString();
        if (s.length() != CODE_LENGTH) return null;
        for (int i = 0; i < s.length(); i++) {
            if (CODE_ALPHABET.indexOf(s.charAt(i)) < 0) return null;
        }
        return s;
    }

    /** PBKDF2-HMAC-SHA256 over the canonical code's ASCII bytes.
     *  The alphabet is ASCII-only, so the password char-to-byte
     *  conversion is identical on the JVM and on Android. */
    public static SecretKey deriveKey(String canonicalCode, byte[] salt)
            throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(
                canonicalCode.toCharArray(), salt, KDF_ITERATIONS, 256);
        try {
            SecretKeyFactory f =
                    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return new SecretKeySpec(f.generateSecret(spec).getEncoded(),
                    "AES");
        } finally {
            spec.clearPassword();
        }
    }

    // ---------------- payload cipher ----------------

    /** AES-256-GCM seal with the manifest's JCS bytes as AAD.
     *  Returns ciphertext with the 16-byte tag appended. */
    public static byte[] sealPayload(SecretKey key, byte[] iv,
                                     byte[] manifestJcs, byte[] plaintext)
            throws GeneralSecurityException {
        javax.crypto.Cipher cipher =
                javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key,
                new javax.crypto.spec.GCMParameterSpec(128, iv));
        cipher.updateAAD(manifestJcs);
        return cipher.doFinal(plaintext);
    }

    /** AES-256-GCM open; throws on any authentication failure
     *  (wrong code, wrong AAD / tampered manifest, tampered
     *  ciphertext). */
    public static byte[] openPayload(SecretKey key, byte[] iv,
                                     byte[] manifestJcs, byte[] sealed)
            throws GeneralSecurityException {
        javax.crypto.Cipher cipher =
                javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key,
                new javax.crypto.spec.GCMParameterSpec(128, iv));
        cipher.updateAAD(manifestJcs);
        return cipher.doFinal(sealed);
    }

    // ---------------- length-prefixed field encoding ----------------

    /** One MAC / payload field: 4-byte big-endian length prefix
     *  followed by the field bytes. */
    public static void writeLengthPrefixed(ByteArrayOutputStream out,
                                           byte[] field) {
        int n = field.length;
        out.write((n >>> 24) & 0xff);
        out.write((n >>> 16) & 0xff);
        out.write((n >>> 8) & 0xff);
        out.write(n & 0xff);
        out.write(field, 0, field.length);
    }

    /** A generation number as decimal ASCII, no leading zeros. */
    public static byte[] generationAscii(long generation) {
        return Long.toString(generation).getBytes(StandardCharsets.US_ASCII);
    }

    // ---------------- the two MACs ----------------

    public static final String NOTICE_LABEL = "QDP1/staging-notice/v1";
    public static final String CONFIRM_LABEL = "QDP1/transfer-confirm/v1";

    /**
     * Staging-notice MAC input (design section 4.1 (2)): label,
     * then length-prefixed fields in the order generation,
     * package hash (raw 32 bytes), destination install ID,
     * receiver install ID. Both ID positions are filled from
     * the notice's single receiver_install_id value.
     */
    public static byte[] noticeMacInput(long generation,
                                        byte[] packageSha256,
                                        String receiverInstallId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] label = NOTICE_LABEL.getBytes(StandardCharsets.UTF_8);
        out.write(label, 0, label.length);
        writeLengthPrefixed(out, generationAscii(generation));
        writeLengthPrefixed(out, packageSha256);
        byte[] id = receiverInstallId.getBytes(StandardCharsets.UTF_8);
        writeLengthPrefixed(out, id);   // destination position
        writeLengthPrefixed(out, id);   // receiver position
        return out.toByteArray();
    }

    /**
     * Receipt confirmation MAC input (design section 4.4):
     * label, then length-prefixed fields in the order
     * generation, receiver install ID, package hash (raw 32
     * bytes). Computed from the canonical VALUES, never from
     * their transport spellings.
     */
    public static byte[] confirmMacInput(long generation,
                                         String receiverInstallId,
                                         byte[] packageSha256) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] label = CONFIRM_LABEL.getBytes(StandardCharsets.UTF_8);
        out.write(label, 0, label.length);
        writeLengthPrefixed(out, generationAscii(generation));
        writeLengthPrefixed(out,
                receiverInstallId.getBytes(StandardCharsets.UTF_8));
        writeLengthPrefixed(out, packageSha256);
        return out.toByteArray();
    }

    public static byte[] hmacSha256(byte[] key, byte[] input)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(input);
    }

    /** Constant-time equality for MAC / hash comparisons. */
    public static boolean slowEquals(byte[] a, byte[] b) {
        return java.security.MessageDigest.isEqual(a, b);
    }

    // ---------------- payload entry framing ----------------

    /**
     * One plaintext payload entry: name (UTF-8, 4-byte BE length
     * prefix) + content (8-byte BE length prefix) + content.
     */
    public static void writePayloadEntry(ByteArrayOutputStream out,
                                         String name, byte[] content) {
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        writeLengthPrefixed(out, n);
        long len = content.length;
        for (int i = 7; i >= 0; i--) {
            out.write((int) (len >>> (8 * i)) & 0xff);
        }
        out.write(content, 0, content.length);
    }

    /** One decrypted payload entry. */
    public static final class PayloadEntry {
        public final String name;
        public final byte[] content;

        public PayloadEntry(String name, byte[] content) {
            this.name = name;
            this.content = content;
        }
    }

    /** A cursor over a decrypted payload, reading entries in
     *  order. Any structural deviation raises
     *  IllegalArgumentException (the package is rejected as a
     *  whole; partial material is never staged). */
    public static final class PayloadReader {
        private final byte[] data;
        private int pos = 0;

        public PayloadReader(byte[] data) {
            this.data = data;
        }

        public boolean atEnd() {
            return pos == data.length;
        }

        /** The next entry, or null at a clean end of input. */
        public PayloadEntry next() {
            if (pos == data.length) return null;
            if (data.length - pos < 4) {
                throw new IllegalArgumentException("payload truncated (name)");
            }
            int nameLen = readU32();
            if (nameLen < 0 || nameLen > data.length - pos) {
                throw new IllegalArgumentException("payload name length invalid");
            }
            String name = new String(data, pos, nameLen,
                    StandardCharsets.UTF_8);
            pos += nameLen;
            if (data.length - pos < 8) {
                throw new IllegalArgumentException(
                        "payload truncated (content length)");
            }
            long contentLen = readU64();
            if (contentLen < 0 || contentLen > data.length - pos) {
                throw new IllegalArgumentException(
                        "payload content length invalid");
            }
            byte[] content = Arrays.copyOfRange(
                    data, pos, pos + (int) contentLen);
            pos += (int) contentLen;
            return new PayloadEntry(name, content);
        }

        private int readU32() {
            int v = ((data[pos] & 0xff) << 24) | ((data[pos + 1] & 0xff) << 16)
                    | ((data[pos + 2] & 0xff) << 8) | (data[pos + 3] & 0xff);
            pos += 4;
            return v;
        }

        private long readU64() {
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (data[pos + i] & 0xffL);
            }
            pos += 8;
            return v;
        }
    }
}
