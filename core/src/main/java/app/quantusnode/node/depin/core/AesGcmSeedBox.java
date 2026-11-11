package app.quantusnode.node.depin.core;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES-GCM wrap/unwrap for the device identity seed (see
 * SeedVault for the storage design).
 *
 * 統合設計の手順2で Android 層（別パッケージ）が消費するため、
 * public なトップレベルクラスへ昇格した。可視性と配置のみの
 * 変更で、ロジックは無変更。
 */
public final class AesGcmSeedBox {
    private AesGcmSeedBox() {}

    public static final class Wrapped {
        public final byte[] iv;
        public final byte[] ciphertext;
        public Wrapped(byte[] iv, byte[] ciphertext) {
            this.iv = iv;
            this.ciphertext = ciphertext;
        }
    }

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    public static Wrapped wrap(SecretKey key, byte[] seed) throws GeneralSecurityException {
        byte[] iv = new byte[IV_BYTES];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        return new Wrapped(iv, cipher.doFinal(seed));
    }

    public static byte[] unwrap(SecretKey key, Wrapped wrapped) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(TAG_BITS, wrapped.iv));
        return cipher.doFinal(wrapped.ciphertext);
    }
}
