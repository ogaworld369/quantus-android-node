package app.quantusnode.node.depin;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import app.quantusnode.node.depin.core.AesGcmSeedBox;
import app.quantusnode.node.depin.core.Ed25519;
import app.quantusnode.node.depin.core.Json;
import app.quantusnode.node.depin.core.SeedVault;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Android implementation of SeedVault: an Android-Keystore AES-256
 * key (generated in the Keystore, non-exportable) wraps the Ed25519
 * seed; the wrapped form is a small JSON file in app-private
 * storage. Backup and device transfer are disabled for this app's
 * data, so this file never leaves the device through platform
 * backup channels.
 *
 * Identity separation: the identity held here is a NEW, disposable
 * shadow identity minted by the integrated observer itself. It
 * shares nothing with the standalone companion app's identity or
 * with any real, already-established identity — not the seed, not
 * the Keystore alias, and not the storage location. The companion
 * shipped under its own alias and file name in its own app
 * sandbox; this class deliberately uses a node-app-namespaced
 * alias ("quantus_node_depin_seed_wrap_v2") and the observer's own
 * files/depin/identity.enc, so the two identities can never be
 * confused, merged or loaded in each other's place. There is
 * likewise no legacy v1 alias cleanup here: the integrated
 * observer has never shipped a v1 key, so there is nothing to
 * clean up.
 *
 * Seed lifetime: this class keeps NO plaintext seed cache — there
 * is deliberately no seed field at all. Every loadOrCreateSeed()
 * call unwraps the seed fresh from the Keystore-wrapped file and
 * hands the caller the only copy, which the caller zeroes
 * immediately after use. Checkpoints are 600 s apart, so the
 * unwrap cost is irrelevant; caching the seed for the process
 * lifetime would leave the device's signing key resting in memory
 * for no benefit.
 */
public final class AndroidSeedVault implements SeedVault {
    // v2 alias, in the node app's own namespace: v1 of the
    // companion's key was generated with
    // setRandomizedEncryptionRequired(true), which KeyMint pairs
    // with a ban on caller-supplied IVs — and AesGcmSeedBox always
    // supplies its own IV, so every v1 wrap was rejected
    // (CALLER_NONCE_PROHIBITED). KeyGenParameterSpec is immutable
    // once a key exists, so a key with the wrong flag can only be
    // fixed by generating under a new alias; the integrated
    // observer starts directly at v2, with the flag pinned by
    // SeedVault.KEYSTORE_RANDOMIZED_ENCRYPTION_REQUIRED.
    private static final String KEY_ALIAS =
            "quantus_node_depin_seed_wrap_v2";

    private final File file;

    public AndroidSeedVault(Context context) {
        this.file = DepinFiles.identityFile(context);
    }

    /** Final check round 4 (I-14): process-wide lock for the
     *  two get-or-create steps below. Every call site builds
     *  its own AndroidSeedVault, so the instance monitor
     *  ("synchronized") serialises nothing between callers:
     *  two first-use callers could both find no Keystore key
     *  and both generate one under the same alias — the
     *  second generation REPLACES the first key, and whatever
     *  the first caller had already wrapped (the identity
     *  file, a transfer-vault entry) can never be unwrapped
     *  again — or both mint a seed, each returning its own
     *  while only one reaches the file. Reentrant, so the
     *  seed path may call the key path inside it. */
    private static final Object CREATE_LOCK = new Object();

    private SecretKey keystoreKey() throws Exception {
        synchronized (CREATE_LOCK) {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator gen = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Must stay paired with the caller-IV design of
                // AesGcmSeedBox; see SeedVault's pinned constant.
                .setRandomizedEncryptionRequired(
                        SeedVault.KEYSTORE_RANDOMIZED_ENCRYPTION_REQUIRED)
                .build());
        return gen.generateKey();
        }
    }

    /**
     * Unwrap and return the stored seed, or generate, wrap and
     * store a new one. EVERY call decrypts on demand: nothing is
     * cached in this class, and the returned array is the caller's
     * only copy — callers zero it in a finally block immediately
     * after use. Synchronized so two concurrent first-run callers
     * cannot race the create-and-store path.
     */
    @Override
    public synchronized byte[] loadOrCreateSeed() throws Exception {
        synchronized (CREATE_LOCK) {
        SecretKey key = keystoreKey();
        if (file.exists()) {
            return loadStoredSeed(key);
        }
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        AesGcmSeedBox.Wrapped wrapped = AesGcmSeedBox.wrap(key, seed);
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("format", "quantus-depin-identity-enc");
        stored.put("version", 1L);
        stored.put("iv", Base64.encodeToString(wrapped.iv, Base64.NO_WRAP));
        stored.put("ct", Base64.encodeToString(wrapped.ciphertext, Base64.NO_WRAP));
        storeWrappedAtomically(Json.pretty(stored));
        return seed;
        }
    }

    /** Residual F-25: the create used to write the wrapped
     *  seed directly onto the identity file, so a death
     *  mid-write left a torn file that every later load
     *  failed on — stranding the identity it had just
     *  minted. The write now lands on a unique sibling, is
     *  fsynced, and renames onto the target (the directory
     *  fsynced after, best effort), so the identity file
     *  is either absent or whole. */
    private void storeWrappedAtomically(String json)
            throws Exception {
        File dir = file.getParentFile();
        File tmp = new File(dir, file.getName()
                + ".tmp-" + System.nanoTime());
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        }
        if (!tmp.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new java.io.IOException(
                    "could not move the new identity file into place");
        }
        try (FileInputStream dirIn = new FileInputStream(dir)) {
            dirIn.getFD().sync();
        } catch (Throwable ignored) {
            // Best effort: the rename stands on filesystems
            // without directory fsync.
        }
    }

    /** Unwrap the stored seed; the file must exist. */
    private byte[] loadStoredSeed(SecretKey key) throws Exception {
        String text;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int off = 0;
            int n;
            while ((n = in.read(buf, off, buf.length - off)) > 0) {
                off += n;
            }
            text = new String(buf, 0, off, StandardCharsets.UTF_8);
        }
        Map<String, Object> stored = Json.parseObject(text);
        AesGcmSeedBox.Wrapped wrapped = new AesGcmSeedBox.Wrapped(
                Base64.decode(Json.asString(stored.get("iv")), Base64.NO_WRAP),
                Base64.decode(Json.asString(stored.get("ct")), Base64.NO_WRAP));
        byte[] seed = AesGcmSeedBox.unwrap(key, wrapped);
        if (seed.length != 32) {
            java.util.Arrays.fill(seed, (byte) 0);
            throw new IllegalStateException("stored seed has wrong length");
        }
        return seed;
    }

    /** True when a wrapped seed file exists. Never creates
     *  anything (final check round 2/3, G-07/F-25: several
     *  paths must be able to ask "is there a home identity?"
     *  without minting one by asking). */
    public synchronized boolean hasSeedFile() {
        return file.exists();
    }

    /** Unwrap and return the stored seed, or null when no
     *  seed file exists. NEVER creates one — the create-free
     *  counterpart of loadOrCreateSeed, for readers that must
     *  not resurrect an identity whose seed was deliberately
     *  erased (G-07). The returned array is the caller's only
     *  copy; zero it after use. */
    public synchronized byte[] loadSeedIfPresent() throws Exception {
        if (!file.exists()) {
            return null;
        }
        return loadStoredSeed(keystoreKey());
    }

    /** The public key of the stored seed, or null when no
     *  seed file exists. Never creates one. */
    public synchronized byte[] publicKeyIfPresent() throws Exception {
        byte[] seed = loadSeedIfPresent();
        if (seed == null) {
            return null;
        }
        try {
            return Ed25519.publickeyFromSeed(seed);
        } finally {
            java.util.Arrays.fill(seed, (byte) 0);
        }
    }

    /** Erase the wrapped seed FILE — the identity itself.
     *  The Keystore wrap key is deliberately NOT deleted: it
     *  is shared with the transfer vault's wraps (see
     *  wrapKeyForTransfer), so deleting it would orphan
     *  every transferred identity's material. Used by the
     *  G-07 erasure path once this identity has been sent
     *  out and the handover closed.
     *
     * @return true when a file existed and was deleted */
    public synchronized boolean eraseSeed() {
        if (!file.exists()) {
            return false;
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        return !file.exists();
    }

    @Override
    public byte[] publicKey() throws Exception {
        byte[] seed = loadOrCreateSeed();
        try {
            return Ed25519.publickeyFromSeed(seed);
        } finally {
            // The seed copy obtained just to derive the public
            // key is zeroed here, not left to the caller.
            java.util.Arrays.fill(seed, (byte) 0);
        }
    }

    /**
     * Emergency wipe hook, kept for the existing call sites
     * (service destroy, post-signing cleanup). There is no
     * plaintext cache or wrapped-material reference held by this
     * class, so there is nothing left to destroy; the wrapped
     * file and the Keystore key are the identity itself and are
     * never deleted here. The method stays as a stateless
     * safeguard so callers can keep a uniform
     * use-then-lock discipline without depending on this class's
     * internals.
     */
    public synchronized void lock() {
        // No state to wipe — see the class doc.
    }

    /**
     * The Keystore wrap key itself, for the transfer machinery
     * (integration design section 4): the transfer vault and the
     * staging records wrap their material under the SAME key the
     * shadow identity uses, so the section 4.2 wrap-key
     * reference rule has exactly one key to govern. Added in
     * build step 4; the key is non-exportable and never leaves
     * the Keystore.
     */
    public SecretKey wrapKeyForTransfer() throws Exception {
        return keystoreKey();
    }
}
