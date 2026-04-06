package org.xiyu.starx.license;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import org.xiyu.starx.util.Logx;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.api.XposedModule;

public class LicenseManager {

    // !! 部署 Vercel 后把项目 URL 填到这里 !!
    // 格式: https://你的项目名.vercel.app
    public static final String SERVER_URL = "https://starxserver.vercel.app";

    private static final String CACHE_PREF = "sx_c_v2";
    private static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000L; // 24 小时
    private static final int TIMEOUT_MS = 8000;

    private final XposedModule module;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public LicenseManager(XposedModule module) {
        this.module = module;
    }

    /**
     * 获取 Hook 配置 — 优先缓存，缓存过期联网验证
     */
    public HookConfig getConfig() {
        String token;
        try {
            var prefs = module.getRemotePreferences("config");
            token = prefs.getString("license_token", "");
            if (token == null || token.isEmpty()) {
                Logx.w("LicenseManager: no token found");
                return null;
            }
        } catch (Throwable t) {
            Logx.w("LicenseManager: prefs error: " + t.getMessage());
            return null;
        }

        // 优先从 remote prefs 读取激活时保存的 device_id
        // (ANDROID_ID 按应用签名区分，在目标进程重新生成会不一致)
        String deviceId;
        try {
            var prefs = module.getRemotePreferences("config");
            deviceId = prefs.getString("device_id", "");
            if (deviceId == null || deviceId.isEmpty()) {
                deviceId = getDeviceId();
            }
        } catch (Throwable t) {
            deviceId = getDeviceId();
        }
        if (deviceId == null) return null;

        // 优先用缓存
        HookConfig cached = loadCache(deviceId, token);
        if (cached != null) {
            Logx.i("LicenseManager: using cached config");
            refreshInBackground(token, deviceId);
            return cached;
        }

        // 无缓存 → 同步验证
        Logx.i("LicenseManager: cache miss, verifying...");
        return verifySync(token, deviceId, 10000);
    }

    private String getDeviceId() {
        try {
            Application app = (Application) Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentApplication").invoke(null);
            if (app == null) return null;
            return DeviceFingerprint.generate(app);
        } catch (Throwable t) {
            Logx.w("LicenseManager: device id error: " + t.getMessage());
            return null;
        }
    }

    private HookConfig loadCache(String deviceId, String token) {
        try {
            Application app = (Application) Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentApplication").invoke(null);
            if (app == null) return null;
            SharedPreferences sp = app.getSharedPreferences(CACHE_PREF, Context.MODE_PRIVATE);
            String encData = sp.getString("d", null);
            long ts = sp.getLong("t", 0);
            if (encData == null || ts == 0) return null;
            if (System.currentTimeMillis() - ts > CACHE_TTL_MS) return null;

            byte[] key = CryptoUtils.sha256((token + deviceId + "cache").getBytes("UTF-8"));
            byte[] plaintext = CryptoUtils.aesGcmDecrypt(key, CryptoUtils.fromBase64(encData));
            return HookConfig.parse(new String(plaintext, "UTF-8"));
        } catch (Throwable t) {
            Logx.w("LicenseManager: cache error: " + t.getMessage());
            return null;
        }
    }

    private void saveCache(String deviceId, String token, String configJson) {
        try {
            Application app = (Application) Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentApplication").invoke(null);
            if (app == null) return;
            byte[] key = CryptoUtils.sha256((token + deviceId + "cache").getBytes("UTF-8"));
            byte[] encrypted = CryptoUtils.aesGcmEncrypt(key, configJson.getBytes("UTF-8"));
            SharedPreferences sp = app.getSharedPreferences(CACHE_PREF, Context.MODE_PRIVATE);
            sp.edit()
                    .putString("d", CryptoUtils.toBase64(encrypted))
                    .putLong("t", System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignored) {}
    }

    private HookConfig verifySync(String token, String deviceId, long timeoutMs) {
        Future<HookConfig> future = executor.submit(() -> doVerify(token, deviceId));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            future.cancel(true);
            Logx.w("LicenseManager: verify timeout: " + t.getMessage());
            return null;
        }
    }

    private void refreshInBackground(String token, String deviceId) {
        executor.submit(() -> {
            HookConfig cfg = doVerify(token, deviceId);
            if (cfg != null) Logx.i("LicenseManager: background refresh ok");
        });
    }

    private HookConfig doVerify(String token, String deviceId) {
        try {
            long ts = System.currentTimeMillis();
            JSONObject body = new JSONObject();
            body.put("token", token);
            body.put("device_id", deviceId);
            body.put("ts", ts);

            String resp = post(SERVER_URL + "/api/verify", body.toString());
            if (resp == null) return null;

            JSONObject json = new JSONObject(resp);
            if (!json.optBoolean("ok", false)) {
                Logx.w("LicenseManager: rejected: " + json.optString("error"));
                return null;
            }

            String configEnc = json.getString("config");
            long serverTs = json.getLong("ts");

            // 解密: key = SHA256(token + serverTs)
            byte[] decKey = CryptoUtils.sha256((token + serverTs).getBytes("UTF-8"));
            byte[] plaintext = CryptoUtils.aesGcmDecrypt(decKey, CryptoUtils.fromBase64(configEnc));
            String configJson = new String(plaintext, "UTF-8");

            saveCache(deviceId, token, configJson);
            return HookConfig.parse(configJson);
        } catch (Throwable t) {
            Logx.w("LicenseManager: verify error: " + t.getMessage());
            return null;
        }
    }

    // =====================================================================
    // 激活接口 — 从 MainActivity 调用
    // =====================================================================

    public static ActivateResult activate(String code, Context context) {
        try {
            String deviceId = DeviceFingerprint.generate(context);
            String deviceInfo = DeviceFingerprint.getDeviceInfo();
            JSONObject body = new JSONObject();
            body.put("code", code.trim().toUpperCase());
            body.put("device_id", deviceId);
            body.put("device_info", deviceInfo);

            String resp = post(SERVER_URL + "/api/activate", body.toString());

            JSONObject json = new JSONObject(resp);
            if (!json.optBoolean("ok", false)) {
                return new ActivateResult(false, json.optString("error", "激活失败"), null, 0, null);
            }
            String token = json.getString("token");
            long expiresAt = json.getLong("expires_at");
            return new ActivateResult(true, "激活成功", token, expiresAt, deviceId);
        } catch (Throwable t) {
            return new ActivateResult(false, "激活失败，请确保已开启代理工具(VPN)后重试", null, 0, null);
        }
    }

    private static String post(String urlStr, String body) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        OutputStream os = conn.getOutputStream();
        os.write(body.getBytes("UTF-8"));
        os.flush();
        os.close();
        int code = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 400 ? conn.getErrorStream() : conn.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        conn.disconnect();
        return sb.toString();
    }

    public static class ActivateResult {
        public final boolean success;
        public final String message;
        public final String token;
        public final long expiresAt;
        public final String deviceId;

        public ActivateResult(boolean success, String message, String token, long expiresAt, String deviceId) {
            this.success = success;
            this.message = message;
            this.token = token;
            this.expiresAt = expiresAt;
            this.deviceId = deviceId;
        }
    }
}
