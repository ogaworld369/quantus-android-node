package app.quantusnode.node.depin.core;

/**
 * Storage for the device's Ed25519 identity seed.
 *
 * Default design (bridge design memo, key-storage decision): the
 * seed itself is NOT held in the Android Keystore — Ed25519 support
 * there is device-dependent and not guaranteed. Instead a
 * Keystore-backed AES-256 key wraps (encrypts) the seed, and the
 * ciphertext is what rests in app-private storage. The seed exists
 * in cleartext only in memory, only while signing or generating.
 *
 * This class holds the provider-independent AES-GCM wrap/unwrap;
 * AndroidSeedVault supplies the Keystore key and the file storage.
 * The JVM harness exercises this exact wrap code with a plain JCE
 * key, so the cryptography is tested even though the Android
 * Keystore provider itself only exists on a device.
 */
public interface SeedVault {
    /**
     * The one value the Android Keystore wrap key may be generated
     * with: AesGcmSeedBox supplies a fresh SecureRandom IV with
     * every wrap and stores it next to the ciphertext (caller-IV
     * design). KeyMint accepts a caller-supplied nonce ONLY when
     * randomized encryption is not required; with `true` keystore2
     * rejects every wrap and unwrap with CALLER_NONCE_PROHIBITED.
     *
     * v0.1.0 shipped this as `true`, which is the confirmed root
     * cause of the on-device crash: seed encryption failed during
     * ObservationService init (keystore2 log on the user's Pixel 9a,
     * 2026-11-07). The JVM harness pins this constant so the pairing
     * cannot silently regress; if the IV design ever changes to
     * Cipher-generated IVs, this constant and the pin change with it.
     */
    boolean KEYSTORE_RANDOMIZED_ENCRYPTION_REQUIRED = false;

    /** Load the existing seed, or generate and store a new one. */
    byte[] loadOrCreateSeed() throws Exception;

    /** The device public key for the stored seed. */
    byte[] publicKey() throws Exception;
}
