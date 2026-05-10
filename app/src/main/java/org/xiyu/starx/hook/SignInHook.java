package org.xiyu.starx.hook;

import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

import org.xiyu.starx.util.CxClasses;
import org.xiyu.starx.util.Logx;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.libxposed.api.XposedModule;

public class SignInHook {
    private static final String PREFS_SIGN = "sign_config";
    private static final String KEY_MANUAL_LAT = "fake_lat";
    private static final String KEY_MANUAL_LNG = "fake_lng";
    private static final String KEY_MANUAL_ADDR = "fake_addr";
    private static final String KEY_AUTO_ENABLED = "auto_target_enabled";
    private static final String KEY_AUTO_LAT = "auto_target_lat";
    private static final String KEY_AUTO_LNG = "auto_target_lng";
    private static final String KEY_AUTO_ADDR = "auto_target_addr";
    private static final String KEY_AUTO_SOURCE_URL = "auto_target_source_url";

    private static final Pattern ACTIVE_ID_PATTERN = Pattern.compile(
            "[?&](?:activeId|activePrimaryId)=(\\d+)", Pattern.CASE_INSENSITIVE);

    private final XposedModule module;
    private final ClassLoader cl;

    private double fakeLat = 0.0;
    private double fakeLng = 0.0;
    private String fakeAddr = "";
    private volatile String lastSeenActiveId = "";

    public SignInHook(XposedModule module, ClassLoader cl) {
        this.module = module;
        this.cl = cl;
    }

    public void hook() throws Throwable {
        try {
            android.content.Context warm = currentApplication();
            Logx.f("SignInHook.hook: ctx warmup = " + (warm == null ? "null" : warm.getPackageName()));
        } catch (Throwable t) {
            Logx.f("SignInHook.hook: ctx warmup err " + t.getMessage());
        }
        loadConfig();
        hookBDLocation();
        hookMockGpsDetection();
        hookLocationManagerBackup();
        hookSignPageAutoCapture();
        hookOkHttpActiveIdCapture();
        Logx.i("SignInHook: initialized");
    }

    private void toastIfPossible(String msg) {
        try {
            android.content.Context ctx = currentApplication();
            if (ctx != null) {
                org.xiyu.starx.util.PrivateToast.show(ctx, msg, android.widget.Toast.LENGTH_LONG);
            }
        } catch (Throwable ignored) {}
    }

    private static volatile android.content.Context sCachedAppCtx;

    private static android.content.Context currentApplication() {
        android.content.Context cached = sCachedAppCtx;
        if (cached != null) return cached;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object t = at.getMethod("currentActivityThread").invoke(null);
            if (t != null) {
                Object app = at.getMethod("getApplication").invoke(t);
                if (app != null) { sCachedAppCtx = (android.content.Context) app; return sCachedAppCtx; }
            }
        } catch (Throwable ignored) {}
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app != null) { sCachedAppCtx = (android.content.Context) app; return sCachedAppCtx; }
        } catch (Throwable ignored) {}
        try {
            Class<?> aah = Class.forName("android.app.AndroidAppHelper");
            Object app = aah.getMethod("currentApplication").invoke(null);
            if (app != null) { sCachedAppCtx = (android.content.Context) app; return sCachedAppCtx; }
        } catch (Throwable ignored) {}
        return null;
    }

    private void loadConfig() {
        try {
            fakeLat = readDoublePref(KEY_MANUAL_LAT, 0.0);
            fakeLng = readDoublePref(KEY_MANUAL_LNG, 0.0);
            fakeAddr = readStringPref(KEY_MANUAL_ADDR, "");
            boolean autoEnabled = readAutoTargetEnabled();
            double autoLat = readDoublePref(KEY_AUTO_LAT, 0.0);
            double autoLng = readDoublePref(KEY_AUTO_LNG, 0.0);
            String autoAddr = readStringPref(KEY_AUTO_ADDR, "");
            if (fakeLat != 0.0 && fakeLng != 0.0) {
                Logx.i("SignInHook: loaded config lat=" + fakeLat + " lng=" + fakeLng + " addr=" + fakeAddr);
            } else if (autoEnabled && autoLat != 0.0 && autoLng != 0.0) {
                Logx.i("SignInHook: loaded auto target lat=" + autoLat + " lng=" + autoLng + " addr=" + autoAddr);
            } else {
                Logx.w("SignInHook: no location configured, location hooks will be passive");
            }
        } catch (Throwable t) {
            Logx.w("SignInHook: failed to load config: " + t.getMessage());
        }
    }

    private SharedPreferences getSignPrefs() {
        return module.getRemotePreferences(PREFS_SIGN);
    }

    private double readDoublePref(String key, double defaultValue) {
        try {
            return Double.longBitsToDouble(getSignPrefs().getLong(key, Double.doubleToLongBits(defaultValue)));
        } catch (Throwable t) {
            return defaultValue;
        }
    }

    private String readStringPref(String key, String defaultValue) {
        try {
            String value = getSignPrefs().getString(key, defaultValue);
            return value != null ? value : defaultValue;
        } catch (Throwable t) {
            return defaultValue;
        }
    }

    private boolean readBooleanPref(String key, boolean defaultValue) {
        try {
            return getSignPrefs().getBoolean(key, defaultValue);
        } catch (Throwable t) {
            return defaultValue;
        }
    }

    private boolean readAutoTargetEnabled() {
        return readBooleanPref(KEY_AUTO_ENABLED, true);
    }

    private double readFakeLat() {
        double manualLat = readDoublePref(KEY_MANUAL_LAT, fakeLat);
        if (manualLat != 0.0) {
            return manualLat;
        }
        if (readAutoTargetEnabled()) {
            double autoLat = readDoublePref(KEY_AUTO_LAT, 0.0);
            if (autoLat != 0.0) return autoLat;
        }
        return fakeLat;
    }

    private double readFakeLng() {
        double manualLng = readDoublePref(KEY_MANUAL_LNG, fakeLng);
        if (manualLng != 0.0) {
            return manualLng;
        }
        if (readAutoTargetEnabled()) {
            double autoLng = readDoublePref(KEY_AUTO_LNG, 0.0);
            if (autoLng != 0.0) return autoLng;
        }
        return fakeLng;
    }

    private String readFakeAddr() {
        String manualAddr = readStringPref(KEY_MANUAL_ADDR, fakeAddr);
        if (!manualAddr.isEmpty()) {
            return manualAddr;
        }
        if (readAutoTargetEnabled()) {
            String autoAddr = readStringPref(KEY_AUTO_ADDR, "");
            if (!autoAddr.isEmpty()) return autoAddr;
        }
        return fakeAddr;
    }

    private void hookBDLocation() throws Throwable {
        Class<?> bdLocationClass;
        try {
            bdLocationClass = Class.forName(CxClasses.BD_LOCATION, false, cl);
        } catch (ClassNotFoundException e) {
            Logx.w("SignInHook: BDLocation class not found, skip");
            return;
        }

        try {
            Method getLatitude = bdLocationClass.getDeclaredMethod("getLatitude");
            module.hook(getLatitude).intercept(chain -> {
                double lat = readFakeLat();
                if (lat == 0.0) return chain.proceed();
                return lat;
            });
            Logx.i("SignInHook: hooked BDLocation.getLatitude()");
        } catch (NoSuchMethodException e) {
            Logx.w("SignInHook: getLatitude not found");
        }

        try {
            Method getLongitude = bdLocationClass.getDeclaredMethod("getLongitude");
            module.hook(getLongitude).intercept(chain -> {
                double lng = readFakeLng();
                if (lng == 0.0) return chain.proceed();
                return lng;
            });
            Logx.i("SignInHook: hooked BDLocation.getLongitude()");
        } catch (NoSuchMethodException e) {
            Logx.w("SignInHook: getLongitude not found");
        }

        try {
            Method getAddrStr = bdLocationClass.getDeclaredMethod("getAddrStr");
            module.hook(getAddrStr).intercept(chain -> {
                String addr = readFakeAddr();
                if (addr.isEmpty()) return chain.proceed();
                return addr;
            });
            Logx.i("SignInHook: hooked BDLocation.getAddrStr()");
        } catch (NoSuchMethodException e) {
            Logx.w("SignInHook: getAddrStr not found");
        }

        try {
            Method getLocType = bdLocationClass.getDeclaredMethod("getLocType");
            module.hook(getLocType).intercept(chain -> {
                if (readFakeLat() == 0.0) return chain.proceed();
                return 161;
            });
            Logx.i("SignInHook: hooked BDLocation.getLocType()");
        } catch (NoSuchMethodException e) {
            Logx.w("SignInHook: getLocType not found");
        }
    }

    private void hookMockGpsDetection() {
        Class<?> bdLocationClass;
        try {
            bdLocationClass = Class.forName(CxClasses.BD_LOCATION, false, cl);
        } catch (ClassNotFoundException e) {
            return;
        }

        try {
            Method m = bdLocationClass.getDeclaredMethod("getMockGnssStrategy");
            module.hook(m).intercept(chain -> 0);
            Logx.i("SignInHook: hooked getMockGnssStrategy -> 0");
        } catch (NoSuchMethodException ignored) {}

        try {
            Method m = bdLocationClass.getDeclaredMethod("getMockGnssProbability");
            module.hook(m).intercept(chain -> -1);
            Logx.i("SignInHook: hooked getMockGnssProbability -> -1");
        } catch (NoSuchMethodException ignored) {}

        try {
            Method m = bdLocationClass.getDeclaredMethod("getMockGpsStrategy");
            module.hook(m).intercept(chain -> 0);
            Logx.i("SignInHook: hooked getMockGpsStrategy -> 0");
        } catch (NoSuchMethodException ignored) {}

        try {
            Method m = bdLocationClass.getDeclaredMethod("getMockGpsProbability");
            module.hook(m).intercept(chain -> -1);
            Logx.i("SignInHook: hooked getMockGpsProbability -> -1");
        } catch (NoSuchMethodException ignored) {}

        try {
            Method m = bdLocationClass.getDeclaredMethod("getReallLocation");
            module.hook(m).intercept(chain -> null);
            Logx.i("SignInHook: hooked getReallLocation -> null (honeypot blocked)");
        } catch (NoSuchMethodException ignored) {}
    }

    private void hookLocationManagerBackup() {
        try {
            Method getLastKnown = LocationManager.class.getDeclaredMethod(
                    "getLastKnownLocation", String.class);
            module.hook(getLastKnown).intercept(chain -> {
                double lat = readFakeLat();
                double lng = readFakeLng();
                if (lat == 0.0 || lng == 0.0) return chain.proceed();
                Location fakeLocation = new Location("gps");
                fakeLocation.setLatitude(lat);
                fakeLocation.setLongitude(lng);
                fakeLocation.setAccuracy(30.0f);
                fakeLocation.setTime(System.currentTimeMillis());
                fakeLocation.setElapsedRealtimeNanos(android.os.SystemClock.elapsedRealtimeNanos());
                Logx.i("SignInHook: faked LocationManager.getLastKnownLocation -> ("
                        + lat + "," + lng + ")");
                return fakeLocation;
            });
            Logx.i("SignInHook: hooked LocationManager.getLastKnownLocation");
        } catch (Throwable t) {
            Logx.w("SignInHook: LocationManager hook failed: " + t.getMessage());
        }
    }

    private void hookSignPageAutoCapture() {
        try {
            Method setWebChromeClient = WebView.class.getDeclaredMethod("setWebChromeClient", WebChromeClient.class);
            module.hook(setWebChromeClient).intercept(chain -> chain.proceed());

            Method loadUrl = WebView.class.getDeclaredMethod("loadUrl", String.class);
            module.hook(loadUrl).intercept(chain -> {
                WebView webView = (WebView) chain.getThisObject();
                String url = (String) chain.getArg(0);
                Object result = chain.proceed();
                onWebViewUrlSeen(webView, url);
                return result;
            });
            try {
                Method loadUrl2 = WebView.class.getDeclaredMethod("loadUrl", String.class, java.util.Map.class);
                module.hook(loadUrl2).intercept(chain -> {
                    WebView webView = (WebView) chain.getThisObject();
                    String url = (String) chain.getArg(0);
                    Object result = chain.proceed();
                    onWebViewUrlSeen(webView, url);
                    return result;
                });
            } catch (Throwable ignored) {}
            try {
                Method postUrl = WebView.class.getDeclaredMethod("postUrl", String.class, byte[].class);
                module.hook(postUrl).intercept(chain -> {
                    WebView webView = (WebView) chain.getThisObject();
                    String url = (String) chain.getArg(0);
                    Object result = chain.proceed();
                    onWebViewUrlSeen(webView, url);
                    return result;
                });
            } catch (Throwable ignored) {}

            Method onPageFinished = android.webkit.WebViewClient.class.getDeclaredMethod(
                    "onPageFinished", WebView.class, String.class);
            module.hook(onPageFinished).intercept(chain -> {
                Object result = chain.proceed();
                WebView webView = (WebView) chain.getArg(0);
                String url = (String) chain.getArg(1);
                onWebViewUrlSeen(webView, url);
                return result;
            });
            Logx.f("SignInHook: sign page auto-capture ready");
        } catch (Throwable t) {
            Logx.f("SignInHook: sign page auto-capture hook failed: " + t.getMessage());
        }
    }

    /**
     * 在 OkHttp Request.Builder.build() 拦截：从 URL 抽 activeId，
     * 后续 Phase B 将在此处解析 getPPTActiveInfo 响应体以自动嗅探签到目标坐标。
     */
    private void hookOkHttpActiveIdCapture() {
        try {
            Class<?> builderCls = cl.loadClass("okhttp3.Request$Builder");
            Method buildM = builderCls.getDeclaredMethod("build");
            module.hook(buildM).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    if (result != null) {
                        Method urlM = result.getClass().getMethod("url");
                        Object httpUrl = urlM.invoke(result);
                        String urlStr = httpUrl != null ? httpUrl.toString() : null;
                        if (urlStr != null) {
                            String aid = extractActiveId(urlStr);
                            if (aid != null && !aid.isEmpty() && !aid.equals(lastSeenActiveId)) {
                                lastSeenActiveId = aid;
                                Logx.f("SignInHook: activeId=" + aid + " (okhttp)");
                            }
                        }
                    }
                } catch (Throwable ignored) {}
                return result;
            });
            Logx.f("SignInHook: OkHttp activeId capture ready");
        } catch (Throwable t) {
            Logx.f("SignInHook: OkHttp activeId capture hook failed: " + t.getMessage());
        }
    }

    private void onWebViewUrlSeen(WebView webView, String url) {
        if (url != null && !url.isEmpty()) {
            String aid = extractActiveId(url);
            if (aid != null && !aid.isEmpty() && !aid.equals(lastSeenActiveId)) {
                lastSeenActiveId = aid;
                Logx.f("SignInHook: activeId=" + aid + " (webview)");
            }
        }
    }

    private String extractActiveId(String url) {
        if (url == null) return null;
        Matcher m = ACTIVE_ID_PATTERN.matcher(url);
        return m.find() ? m.group(1) : null;
    }
}
