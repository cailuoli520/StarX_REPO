package org.xiyu.starx.license;

import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class CryptoUtils {
    private CryptoUtils() {}

    public static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    public static byte[] aesGcmDecrypt(byte[] key, byte[] ivAndCiphertext) throws Exception {
        byte[] iv = new byte[12];
        byte[] ciphertext = new byte[ivAndCiphertext.length - 12];
        System.arraycopy(ivAndCiphertext, 0, iv, 0, 12);
        System.arraycopy(ivAndCiphertext, 12, ciphertext, 0, ciphertext.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, iv));
        return cipher.doFinal(ciphertext);
    }

    public static byte[] aesGcmEncrypt(byte[] key, byte[] plaintext) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, iv));
        byte[] ct = cipher.doFinal(plaintext);
        byte[] result = new byte[12 + ct.length];
        System.arraycopy(iv, 0, result, 0, 12);
        System.arraycopy(ct, 0, result, 12, ct.length);
        return result;
    }

    public static String toBase64(byte[] data) {
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }

    public static byte[] fromBase64(String b64) {
        return Base64.decode(b64, Base64.NO_WRAP);
    }
}
