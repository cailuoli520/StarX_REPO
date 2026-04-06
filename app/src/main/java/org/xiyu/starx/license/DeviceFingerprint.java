package org.xiyu.starx.license;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import java.security.MessageDigest;

public final class DeviceFingerprint {
    private DeviceFingerprint() {}

    @SuppressLint("HardwareIds")
    public static String generate(Context context) {
        StringBuilder sb = new StringBuilder();
        try {
            String androidId = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (androidId != null) sb.append(androidId);
        } catch (Throwable ignored) {}
        sb.append("|").append(Build.FINGERPRINT);
        sb.append("|").append(Build.MODEL);
        sb.append("|").append(Build.MANUFACTURER);
        sb.append("|").append(Build.BOARD);
        sb.append("|").append(Build.HARDWARE);
        return sha256Hex(sb.toString());
    }

    public static String getDeviceInfo() {
        return Build.MANUFACTURER + " " + Build.MODEL;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Throwable t) {
            return String.valueOf(input.hashCode());
        }
    }
}
