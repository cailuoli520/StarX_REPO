package org.xiyu.starx.hook;

import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;

import org.xiyu.starx.util.CxClasses;
import org.xiyu.starx.util.Logx;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

public class SignInHook {
    private final XposedModule module;
    private final ClassLoader cl;

    // 默认坐标 (可通过 RemotePreferences 配置)
    private double fakeLat = 0.0;
    private double fakeLng = 0.0;
    private String fakeAddr = "";

    public SignInHook(XposedModule module, ClassLoader cl) {
        this.module = module;
        this.cl = cl;
    }

    public void hook() throws Throwable {
        loadConfig();
        hookBDLocation();
        hookMockGpsDetection();
        hookLocationManagerBackup();
        Logx.i("SignInHook: initialized");
    }

    private void loadConfig() {
        try {
            SharedPreferences prefs = module.getRemotePreferences("sign_config");
            fakeLat = Double.longBitsToDouble(prefs.getLong("fake_lat", Double.doubleToLongBits(0.0)));
            fakeLng = Double.longBitsToDouble(prefs.getLong("fake_lng", Double.doubleToLongBits(0.0)));
            fakeAddr = prefs.getString("fake_addr", "");
            if (fakeLat != 0.0 && fakeLng != 0.0) {
                Logx.i("SignInHook: loaded config lat=" + fakeLat + " lng=" + fakeLng + " addr=" + fakeAddr);
            } else {
                Logx.w("SignInHook: no location configured, location hooks will be passive");
            }
        } catch (Throwable t) {
            Logx.w("SignInHook: failed to load config: " + t.getMessage());
        }
    }

    private double readFakeLat() {
        try {
            SharedPreferences prefs = module.getRemotePreferences("sign_config");
            return Double.longBitsToDouble(prefs.getLong("fake_lat", Double.doubleToLongBits(0.0)));
        } catch (Throwable t) {
            return fakeLat;
        }
    }

    private double readFakeLng() {
        try {
            SharedPreferences prefs = module.getRemotePreferences("sign_config");
            return Double.longBitsToDouble(prefs.getLong("fake_lng", Double.doubleToLongBits(0.0)));
        } catch (Throwable t) {
            return fakeLng;
        }
    }

    private String readFakeAddr() {
        try {
            SharedPreferences prefs = module.getRemotePreferences("sign_config");
            String addr = prefs.getString("fake_addr", "");
            return addr != null ? addr : "";
        } catch (Throwable t) {
            return fakeAddr;
        }
    }

    private void hookBDLocation() throws Throwable {
        Class<?> bdLocationClass;
        try {
            bdLocationClass = Class.forName(CxClasses.BD_LOCATION, false, cl);
        } catch (ClassNotFoundException e) {
            Logx.w("SignInHook: BDLocation class not found, skip");
            return;
        }

        // Hook getLatitude()
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

        // Hook getLongitude()
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

        // Hook getAddrStr()
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

        // Hook getLocType() — 伪造定位类型为成功 (161 = 网络定位成功)
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

    /**
     * Hook MockGPS 检测方法 — 防止定位伪造被发现
     *
     * BDLocation 内置模拟检测:
     * - getMockGnssStrategy() > 0 → 认定为模拟定位
     * - getMockGnssProbability() > 0 → 模拟概率评分
     * - getReallLocation() → 当 strategy > 0 时返回真实位置 (蜜罐!)
     */
    private void hookMockGpsDetection() {
        Class<?> bdLocationClass;
        try {
            bdLocationClass = Class.forName(CxClasses.BD_LOCATION, false, cl);
        } catch (ClassNotFoundException e) {
            return;
        }

        // Hook getMockGnssStrategy() → 0 (无模拟)
        try {
            Method m = bdLocationClass.getDeclaredMethod("getMockGnssStrategy");
            module.hook(m).intercept(chain -> 0);
            Logx.i("SignInHook: hooked getMockGnssStrategy → 0");
        } catch (NoSuchMethodException ignored) {}

        // Hook getMockGnssProbability() → -1 (未知)
        try {
            Method m = bdLocationClass.getDeclaredMethod("getMockGnssProbability");
            module.hook(m).intercept(chain -> -1);
            Logx.i("SignInHook: hooked getMockGnssProbability → -1");
        } catch (NoSuchMethodException ignored) {}

        // Hook 已废弃但仍被调用的旧方法 (底层同一字段)
        try {
            Method m = bdLocationClass.getDeclaredMethod("getMockGpsStrategy");
            module.hook(m).intercept(chain -> 0);
            Logx.i("SignInHook: hooked getMockGpsStrategy → 0");
        } catch (NoSuchMethodException ignored) {}

        try {
            Method m = bdLocationClass.getDeclaredMethod("getMockGpsProbability");
            module.hook(m).intercept(chain -> -1);
            Logx.i("SignInHook: hooked getMockGpsProbability → -1");
        } catch (NoSuchMethodException ignored) {}

        // Hook getReallLocation() → null (蜜罐: 模拟时返回真实位置, 必须拦截!)
        try {
            Method m = bdLocationClass.getDeclaredMethod("getReallLocation");
            module.hook(m).intercept(chain -> null);
            Logx.i("SignInHook: hooked getReallLocation → null (honeypot blocked)");
        } catch (NoSuchMethodException ignored) {}
    }

    /**
     * Hook Android LocationManager 备用定位路径
     *
     * sign.util.b 直接调用 LocationManager.getLastKnownLocation()
     * 绕过百度 SDK, 必须在 framework 层拦截
     */
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
                // 需要设置 elapsedRealtimeNanos 以通过验证
                fakeLocation.setElapsedRealtimeNanos(android.os.SystemClock.elapsedRealtimeNanos());
                Logx.i("SignInHook: faked LocationManager.getLastKnownLocation → ("
                        + lat + "," + lng + ")");
                return fakeLocation;
            });
            Logx.i("SignInHook: hooked LocationManager.getLastKnownLocation");
        } catch (Throwable t) {
            Logx.w("SignInHook: LocationManager hook failed: " + t.getMessage());
        }
    }
}
