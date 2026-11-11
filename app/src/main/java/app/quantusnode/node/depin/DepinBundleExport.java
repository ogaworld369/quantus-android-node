package app.quantusnode.node.depin;

import android.content.Context;

import app.quantusnode.node.depin.core.BundleExport;
import app.quantusnode.node.depin.core.CompanionStatus;
import app.quantusnode.node.depin.core.DepinCore;
import app.quantusnode.node.depin.core.Ed25519;
import app.quantusnode.node.depin.core.JournalStore;
import app.quantusnode.node.depin.core.TransferIo;
import app.quantusnode.node.depin.core.TransferVault;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Android-side proof-bundle export (integration build step 5),
 * the integrated app's counterpart of the companion's
 * buildCurrentBundle + file save. It runs ONLY in the :depin
 * process, on the observer service's export thread: the journal
 * and the identity seed never cross into the main process. The
 * built JSON is staged at DepinFiles.pendingBundleFile and the
 * main process merely streams that file to the destination the
 * user picked.
 *
 * Seed selection mirrors the writer resolution DepinRuntime
 * used to construct the journal (section 3.2): the shadow
 * vault's seed normally; the transferred-in identity's seed
 * from the transfer vault when the active binding is a received
 * identity; a HELD gate refuses the export with the gate's
 * reason. As a cross-check, the seed's device id must equal the
 * identity the running observer publishes in CompanionStatus —
 * a mismatch means the resolution inputs changed under us, and
 * the export fails loudly instead of signing a bundle under the
 * wrong identity. The seed copy is zeroed as soon as the bundle
 * is built, as on every other path.
 */
public final class DepinBundleExport {
    private DepinBundleExport() {}

    /** A staged export: the JSON waits at the pending-bundle
     *  file; fileName is the suggested destination name. */
    public static final class Staged {
        public String fileName;
        public int checkpointCount;
        public long bytes;
    }

    /**
     * Build the current bundle and stage it for the UI process.
     * Returns null when the journal has no checkpoints yet.
     * Blocking (journal construction, bundle assembly, file
     * I/O): call from a background thread only.
     */
    public static Staged buildAndStage(Context context) throws Exception {
        JournalStore journal = DepinRuntime.journal(context);
        List<DepinCore.Checkpoint> all = journal.checkpoints();
        if (all.isEmpty()) {
            return null;
        }
        AndroidSeedVault vault = new AndroidSeedVault(context);
        byte[] seed = null;
        try {
            String shadowDeviceId = DepinCore.deviceIdFromPubkey(
                    vault.publicKey());
            DepinTransferRuntime.WriterResolution resolution =
                    DepinTransferRuntime.resolveWriter(
                            context, shadowDeviceId);
            if (resolution.kind
                    == DepinTransferRuntime.WriterResolution.HELD) {
                throw new IllegalStateException(
                        "transfer gate: " + resolution.reason);
            }
            if (resolution.kind
                    == DepinTransferRuntime.WriterResolution.RECEIVED) {
                seed = DepinTransferRuntime.vault(context).get(
                        TransferVault.receiverKey(resolution.generation));
                if (seed == null) {
                    throw new IllegalStateException(
                            "transferred identity seed missing");
                }
            } else {
                seed = vault.loadOrCreateSeed();
            }
            String seedDeviceId = DepinCore.deviceIdFromPubkey(
                    Ed25519.publickeyFromSeed(seed));
            String liveDeviceId = CompanionStatus.deviceId;
            if (liveDeviceId != null
                    && !liveDeviceId.equals(seedDeviceId)) {
                throw new IllegalStateException(
                        "identity mismatch: export seed is "
                                + seedDeviceId + " but the observer "
                                + "is recording as " + liveDeviceId);
            }
            String json = BundleExport.buildJson(
                    seed, all, System.currentTimeMillis() / 1000L);
            if (json == null) {
                return null;
            }
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            File target = DepinFiles.pendingBundleFile(context);
            // M-4: the staging temp name is unique per build —
            // a fixed ".tmp" name let overlapping builds (the
            // service serialises them now; this is the second
            // line of defence) interleave into the same file.
            File tmp = new File(target.getParentFile(),
                    target.getName() + ".tmp-"
                            + Long.toHexString(System.nanoTime())
                            + "-" + Thread.currentThread().getId());
            try {
                TransferIo.writeFileSync(tmp, bytes);
                if (!tmp.renameTo(target)) {
                    // Same-directory rename is expected to succeed;
                    // fall back to a direct synced write rather than
                    // fail the export over it.
                    TransferIo.writeFileSync(target, bytes);
                }
            } finally {
                if (tmp.exists()) {
                    tmp.delete();
                }
            }
            TransferIo.fsyncDir(target.getParentFile());
            Staged out = new Staged();
            out.fileName = suggestFileName(seedDeviceId);
            out.checkpointCount = all.size();
            out.bytes = bytes.length;
            return out;
        } finally {
            if (seed != null) {
                Arrays.fill(seed, (byte) 0);
            }
            vault.lock();
        }
    }

    /** Drop a staged bundle that will not be saved (picker
     *  cancelled, or saved and consumed). Never throws. */
    public static void discardStaged(Context context) {
        try {
            File f = DepinFiles.pendingBundleFile(context);
            if (f.exists()) {
                f.delete();
            }
        } catch (Throwable ignored) {
            // A leftover staged bundle is overwritten by the
            // next export; failing to delete it must not surface.
        }
    }

    /** depin-bundle-<id fragment>-<timestamp>.json, the
     *  companion's naming rule. */
    private static String suggestFileName(String deviceId) {
        String shortId = deviceId == null
                ? "device"
                : deviceId.substring(5, Math.min(17, deviceId.length()));
        String date = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date());
        return "depin-bundle-" + shortId + "-" + date + ".json";
    }
}
