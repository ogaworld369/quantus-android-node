package app.quantusnode.node.depin.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Strict read-only parsing and structural verification of a
 * journal file (or an archive file — same shape), used by the
 * transfer machinery for sender-side export and receiver-side
 * staged verification (design section 4.3 steps 3–5).
 *
 * Unlike {@link JournalStore}, this class NEVER mutates the
 * file: no truncation, no quarantine, no migration. The staged
 * bytes are evidence; verification reports on them as they are.
 * What is verified here (the structural tier):
 *  - the header is exactly the journal header shape and names
 *    the expected identity when one is given;
 *  - every entry line parses under the strict checkpoint
 *    schema, its seq continues the chain, its prev hash links,
 *    its checkpoint hash recomputes under the file's format
 *    profile, and its entry signature verifies under the
 *    header's public key.
 * The sequential-work tier (recomputing every chain_state) is
 * {@link Reverification}'s job, sliced under the section 3.5
 * budget; the two tiers together are the design's step-3
 * re-verification.
 */
public final class JournalFile {
    private JournalFile() {}

    public static final class Parsed {
        public String version;
        public DepinCore.FormatProfile profile;
        public String deviceId;
        public byte[] pubkey;
        public final List<DepinCore.Checkpoint> checkpoints =
                new ArrayList<>();
        public final List<byte[]> entrySigs = new ArrayList<>();

        public int count() {
            return checkpoints.size();
        }

        public DepinCore.Checkpoint last() {
            return checkpoints.isEmpty()
                    ? null : checkpoints.get(checkpoints.size() - 1);
        }
    }

    private static DepinCore.FormatProfile profileFor(String version) {
        switch (version) {
            case "0.5": return DepinCore.FMT_V05;
            case "0.4": return DepinCore.FMT_V04;
            case "0.3": return DepinCore.FMT_V03;
            default:
                throw new IllegalArgumentException(
                        "unknown journal format version: " + version);
        }
    }

    /**
     * Parse and structurally verify journal bytes, verifying
     * every entry signature.
     *
     * @param expectedDeviceId when non-null, the header must
     *        name exactly this device (a foreign journal is
     *        rejected, never adopted).
     */
    public static Parsed parse(byte[] raw, String expectedDeviceId) {
        return parse(raw, expectedDeviceId, true);
    }

    /**
     * Parse with the entry-signature step optionally skipped
     * (final check round 3, H-10). Structure, sequence,
     * prev-hash linkage and the recomputed checkpoint_hash of
     * every entry are verified in BOTH modes; only the
     * Ed25519 verification is conditional. The light mode is
     * sound only for bytes whose full verification has
     * already completed and whose identity with those bytes
     * is established by a whole-file SHA-256 comparison —
     * the caller (TransferReceiver.verifyStagedEntities)
     * owns that discipline and records the verified hash in
     * the staging record. Never call the light mode on bytes
     * that have not passed a full parse under the same hash.
     */
    public static Parsed parse(byte[] raw, String expectedDeviceId,
                               boolean verifySignatures) {
        List<String> lines = splitLines(raw);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("journal is empty");
        }
        Map<String, Object> header = Json.parseObject(lines.get(0));
        for (String k : header.keySet()) {
            if (!k.equals("type") && !k.equals("format")
                    && !k.equals("version") && !k.equals("device_id")
                    && !k.equals("pubkey")) {
                throw new IllegalArgumentException(
                        "unknown journal header field: " + k);
            }
        }
        if (!"header".equals(header.get("type"))) {
            throw new IllegalArgumentException("first line is not a header");
        }
        if (!"quantus-depin-journal".equals(header.get("format"))) {
            throw new IllegalArgumentException("journal format mismatch");
        }
        Parsed out = new Parsed();
        out.version = DepinCore.strictString(header.get("version"), "version");
        out.profile = profileFor(out.version);
        out.deviceId = DepinCore.strictString(
                header.get("device_id"), "device_id");
        out.pubkey = DepinCore.strictHex32(header.get("pubkey"), "pubkey");
        if (!DepinCore.deviceIdFromPubkey(out.pubkey).equals(out.deviceId)) {
            throw new IllegalArgumentException(
                    "journal header device_id does not match its pubkey");
        }
        if (expectedDeviceId != null
                && !expectedDeviceId.equals(out.deviceId)) {
            throw new IllegalArgumentException(
                    "journal belongs to a different identity");
        }
        byte[] prevHash = DepinCore.ZERO32;
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) {
                throw new IllegalArgumentException(
                        "blank line inside journal at " + i);
            }
            Map<String, Object> entry = Json.parseObject(line);
            Object sigObj = entry.get("entry_sig");
            if (!(sigObj instanceof String)) {
                throw new IllegalArgumentException(
                        "entry " + (i - 1) + ": missing entry_sig");
            }
            byte[] sig = Hex.decodeCanonical((String) sigObj);
            if (sig.length != 64) {
                throw new IllegalArgumentException(
                        "entry " + (i - 1) + ": entry_sig length");
            }
            Map<String, Object> cpMap =
                    new java.util.LinkedHashMap<>(entry);
            cpMap.remove("entry_sig");
            DepinCore.Checkpoint cp = DepinCore.Checkpoint.fromMap(cpMap);
            if (cp.seq != out.checkpoints.size()) {
                throw new IllegalArgumentException(
                        "entry " + (i - 1) + ": seq does not continue the chain");
            }
            if (!Arrays.equals(cp.prevCheckpointHash, prevHash)) {
                throw new IllegalArgumentException(
                        "entry " + (i - 1) + ": prev_checkpoint_hash linkage broken");
            }
            if (!Arrays.equals(
                    DepinCore.checkpointHash(out.profile, cp),
                    cp.checkpointHash)) {
                throw new IllegalArgumentException(
                        "entry " + (i - 1) + ": checkpoint_hash mismatch");
            }
            if (verifySignatures && !DepinCore.verifyJournalEntrySig(
                    out.pubkey, cp.checkpointHash, cp.seq, sig)) {
                throw new IllegalArgumentException(
                        "entry " + (i - 1) + ": entry signature invalid");
            }
            out.checkpoints.add(cp);
            out.entrySigs.add(sig);
            prevHash = cp.checkpointHash;
        }
        return out;
    }

    public static Parsed parse(File f, String expectedDeviceId)
            throws IOException {
        return parse(TransferIo.readAll(f, 512L * 1024 * 1024),
                expectedDeviceId);
    }

    /** Split into lines with the 1 MiB per-line cap (design
     *  section 3.4); a final line without a terminator is still
     *  returned (its completeness is for the caller to judge —
     *  for a journal being verified, an unterminated tail entry
     *  parses or fails on its own content). */
    static List<String> splitLines(byte[] raw) {
        List<String> lines = new ArrayList<>();
        int pos = 0;
        while (pos < raw.length) {
            int nl = -1;
            for (int i = pos; i < raw.length; i++) {
                if (raw[i] == (byte) '\n') { nl = i; break; }
            }
            int end = (nl < 0) ? raw.length : nl;
            if (end - pos > TransferIo.MAX_LINE_BYTES) {
                throw new IllegalArgumentException(
                        "journal line exceeds the 1 MiB cap");
            }
            lines.add(new String(raw, pos, end - pos,
                    StandardCharsets.UTF_8));
            if (nl < 0) break;
            pos = nl + 1;
        }
        return lines;
    }

    /**
     * Reconstruct the anchor bundle's hash from a parsed live
     * journal plus the manifest's pinned metadata (design
     * section 4.1, anchor pinning): checkpoints come from the
     * journal; created_at_unix and params come from the
     * manifest's fixed values, never regenerated. Returns the
     * recomputed bundle_hash (hex), which the caller compares
     * against the manifest's anchor_bundle_hash.
     */
    public static String anchorBundleHash(Parsed journal,
                                          long anchorCreatedAtUnix,
                                          Map<String, Object> anchorParams) {
        Map<String, Object> bundle = new java.util.LinkedHashMap<>();
        bundle.put("format", "quantus-depin-proof");
        bundle.put("version", journal.version);
        bundle.put("device_id", journal.deviceId);
        bundle.put("pubkey", Hex.encode(journal.pubkey));
        bundle.put("created_at_unix", anchorCreatedAtUnix);
        bundle.put("params", anchorParams);
        List<Object> cps = new ArrayList<>();
        for (DepinCore.Checkpoint c : journal.checkpoints) {
            cps.add(c.toMap());
        }
        bundle.put("checkpoints", cps);
        return Hex.encode(
                DepinCore.bundleHash(journal.profile, bundle));
    }

    /** Build the sender-side anchor values from a parsed live
     *  journal and the identity seed: assembles the bundle the
     *  same way the export path does, so the manifest pins
     *  exactly what a receiver can reconstruct. Returns the
     *  assembled bundle map (its bundle_hash is the anchor). */
    public static Map<String, Object> buildAnchorBundle(
            byte[] seed, Parsed journal, long createdAtUnix,
            int iterations) {
        return DepinCore.assembleBundle(journal.profile, seed,
                journal.checkpoints, createdAtUnix, iterations);
    }
}
