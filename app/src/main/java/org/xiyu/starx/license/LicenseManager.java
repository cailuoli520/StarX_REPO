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
    // 缓存新鲜窗口：在此时间内直接命中缓存并后台静默刷新
    private static final long CACHE_FRESH_MS = 7L * 24 * 60 * 60 * 1000L;   // 7 天
    // 缓存宽限窗口：超过 fresh 但仍在 grace 之内时，先尝试联网；联网失败回退缓存（不让用户被迫一直开 VPN）
    private static final long CACHE_GRACE_MS = 30L * 24 * 60 * 60 * 1000L;  // 30 天
    private static final int TIMEOUT_MS = 15000;
    private static final int VERIFY_MAX_ATTEMPTS = 2;

    private final XposedModule module;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public LicenseManager(XposedModule module) {
        this.module = module;
    }

    /**
     * 获取 Hook 配置：
     *   1. 缓存新鲜 → 命中并后台刷新；
     *   2. 缓存超过新鲜窗口但仍在宽限期 → 联网验证，成功用新配置，
     *      网络失败则回退缓存（避免必须挂 VPN 才能用），服务器明确拒绝则不允许回退；
     *   3. 无缓存或超出宽限 → 必须联网验证。
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

        CacheEntry cached = loadCacheRaw(deviceId, token);
        if (cached != null && cached.ageMs <= CACHE_FRESH_MS) {
            Logx.i("LicenseManager: cache fresh (age=" + cached.ageMs + "ms), background refresh");
            refreshInBackground(token, deviceId);
            return cached.config;
        }

        // 缓存陈旧或不存在：尝试联网验证
        Logx.i("LicenseManager: cache "
                + (cached == null ? "miss" : "stale age=" + cached.ageMs + "ms")
                + ", verifying...");
        VerifyOutcome outcome = verifySyncOutcome(token, deviceId, TIMEOUT_MS);
        if (outcome.config != null) {
            return outcome.config;
        }

        // 服务器明确拒绝（token/device 不匹配、已过期、已吊销等）→ 绝不放行
        if (outcome.rejected) {
            Logx.f("[license] server rejected, refusing cache fallback");
            return null;
        }

        // 网络失败：若缓存仍在宽限期内，回退使用
        if (cached != null && cached.ageMs <= CACHE_GRACE_MS) {
            Logx.f("[license] network failed, using stale cache age=" + cached.ageMs + "ms");
            return cached.config;
        }
        Logx.f("[license] no usable cache (cached="
                + (cached == null ? "null" : ("age=" + cached.ageMs + "ms"))
                + "), free mode");
        return null;
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

    /** 加载缓存配置（不做 TTL 截断），返回 null 表示无缓存或解密失败。 */
    private CacheEntry loadCacheRaw(String deviceId, String token) {
        try {
            Application app = (Application) Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentApplication").invoke(null);
            if (app == null) return null;
            SharedPreferences sp = app.getSharedPreferences(CACHE_PREF, Context.MODE_PRIVATE);
            String encData = sp.getString("d", null);
            long ts = sp.getLong("t", 0);
            if (encData == null || ts == 0) return null;
            byte[] key = CryptoUtils.sha256((token + deviceId + "cache").getBytes("UTF-8"));
            byte[] plaintext = CryptoUtils.aesGcmDecrypt(key, CryptoUtils.fromBase64(encData));
            HookConfig cfg = HookConfig.parse(new String(plaintext, "UTF-8"));
            if (cfg == null) return null;
            long age = Math.max(0L, System.currentTimeMillis() - ts);
            return new CacheEntry(cfg, age);
        } catch (Throwable t) {
            Logx.w("LicenseManager: cache decrypt error: " + t.getMessage());
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

    private VerifyOutcome verifySyncOutcome(String token, String deviceId, long timeoutMs) {
        Future<VerifyOutcome> future = executor.submit(() -> doVerify(token, deviceId));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            future.cancel(true);
            Logx.w("LicenseManager: verify timeout: " + t.getMessage());
            return VerifyOutcome.netFail();
        }
    }

    private void refreshInBackground(String token, String deviceId) {
        executor.submit(() -> {
            VerifyOutcome r = doVerify(token, deviceId);
            if (r.config != null) Logx.i("LicenseManager: background refresh ok");
            else if (r.rejected) Logx.f("[license] background refresh rejected by server");
            else Logx.i("LicenseManager: background refresh network failed");
        });
    }

    private VerifyOutcome doVerify(String token, String deviceId) {
        PostResult pr;
        try {
            long ts = System.currentTimeMillis();
            JSONObject body = new JSONObject();
            body.put("token", token);
            body.put("device_id", deviceId);
            body.put("ts", ts);
            pr = postWithRetry(SERVER_URL + "/api/verify", body.toString());
        } catch (Throwable t) {
            Logx.w("LicenseManager: verify build error: " + t.getMessage());
            return VerifyOutcome.netFail();
        }
        if (pr.networkFailed || pr.body == null) {
            Logx.w("LicenseManager: verify network failed: " + pr.errorMessage);
            return VerifyOutcome.netFail();
        }
        try {
            JSONObject json = new JSONObject(pr.body);
            if (!json.optBoolean("ok", false)) {
                Logx.w("LicenseManager: rejected: " + json.optString("error"));
                return VerifyOutcome.reject();
            }
            String configEnc = json.getString("config");
            long serverTs = json.getLong("ts");
            byte[] decKey = CryptoUtils.sha256((token + serverTs).getBytes("UTF-8"));
            byte[] plaintext = CryptoUtils.aesGcmDecrypt(decKey, CryptoUtils.fromBase64(configEnc));
            String configJson = new String(plaintext, "UTF-8");
            saveCache(deviceId, token, configJson);
            HookConfig cfg = HookConfig.parse(configJson);
            if (cfg == null) {
                Logx.w("LicenseManager: verify parse returned null");
                return VerifyOutcome.netFail();
            }
            return VerifyOutcome.ok(cfg);
        } catch (Throwable t) {
            // 服务器返回了响应但内容异常，视为可重试的网络/格式问题，避免误吊销已激活用户。
            Logx.w("LicenseManager: verify parse error: " + t.getMessage());
            return VerifyOutcome.netFail();
        }
    }

    private static PostResult postWithRetry(String url, String body) {
        String lastError = null;
        for (int attempt = 1; attempt <= VERIFY_MAX_ATTEMPTS; attempt++) {
            try {
                String resp = post(url, body);
                return new PostResult(resp, false, null);
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                if (attempt < VERIFY_MAX_ATTEMPTS) {
                    try { Thread.sleep(500L * attempt); } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        return new PostResult(null, true, lastError);
    }

    private static final class CacheEntry {
        final HookConfig config;
        final long ageMs;
        CacheEntry(HookConfig c, long age) { this.config = c; this.ageMs = age; }
    }

    private static final class VerifyOutcome {
        final HookConfig config;
        final boolean networkFailed;
        final boolean rejected;
        private VerifyOutcome(HookConfig c, boolean nf, boolean rj) {
            this.config = c; this.networkFailed = nf; this.rejected = rj;
        }
        static VerifyOutcome ok(HookConfig c) { return new VerifyOutcome(c, false, false); }
        static VerifyOutcome netFail() { return new VerifyOutcome(null, true, false); }
        static VerifyOutcome reject() { return new VerifyOutcome(null, false, true); }
    }

    private static final class PostResult {
        final String body;
        final boolean networkFailed;
        final String errorMessage;
        PostResult(String b, boolean nf, String em) {
            this.body = b; this.networkFailed = nf; this.errorMessage = em;
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
