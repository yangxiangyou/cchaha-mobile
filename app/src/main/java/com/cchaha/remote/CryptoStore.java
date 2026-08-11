package com.cchaha.remote;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 基于 Android Keystore 的加密存储。
 * 连接 URL 含 token（等于电脑的钥匙），明文存 SharedPreferences 有风险；
 * 用系统级 Keystore 的 AES-GCM 加密，密钥硬件/系统保护，不落盘明文。
 */
public final class CryptoStore {

    private static final String TAG = "CryptoStore";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "haha_remote_key";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    private SecretKey key;

    public CryptoStore() {
        key = loadOrCreateKey();
    }

    private SecretKey loadOrCreateKey() {
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            SecretKey existing = (SecretKey) ks.getKey(KEY_ALIAS, null);
            if (existing != null) return existing;
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
            return kg.generateKey();
        } catch (Exception e) {
            // 极少数设备 Keystore 异常：降级为不可用，由调用方决定（提示用户）
            Log.e(TAG, "keystore init failed", e);
            return null;
        }
    }

    /** 可用性：Keystore 正常且密钥就绪 */
    public boolean isAvailable() {
        return key != null;
    }

    /** 加密并 Base64 编码：base64(iv + ciphertext) */
    public String encrypt(String plain) {
        if (plain == null) return null;
        if (key == null) return plain; // 降级：明文（记录日志）
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] ciphertext = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            byte[] out = new byte[GCM_IV_BYTES + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, GCM_IV_BYTES);
            System.arraycopy(ciphertext, 0, out, GCM_IV_BYTES, ciphertext.length);
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "encrypt failed", e);
            return plain;
        }
    }

    /** 解密 Base64(iv+ciphertext)；失败返回 null（数据已损坏或密钥被清除） */
    public String decrypt(String encoded) {
        if (encoded == null) return null;
        if (key == null) return encoded; // 与 encrypt 降级对称
        try {
            byte[] in = Base64.decode(encoded, Base64.NO_WRAP);
            if (in.length <= GCM_IV_BYTES) return null;
            byte[] iv = new byte[GCM_IV_BYTES];
            System.arraycopy(in, 0, iv, 0, GCM_IV_BYTES);
            byte[] ciphertext = new byte[in.length - GCM_IV_BYTES];
            System.arraycopy(in, GCM_IV_BYTES, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "decrypt failed", e);
            return null;
        }
    }
}
