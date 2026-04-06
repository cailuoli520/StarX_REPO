package org.xiyu.starx.hook;

import android.app.Activity;
import android.content.Intent;

import org.xiyu.starx.util.CxClasses;
import org.xiyu.starx.util.Logx;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import io.github.libxposed.api.XposedModule;

/**
 * 广告跳过 — 使用特征匹配代替硬编码混淆方法名
 *
 * 策略:
 * 1. 特征匹配: 在 SplashActivity 中查找接受 Ad 参数的方法 → 拦截广告展示
 * 2. 特征匹配: 查找无参 void 方法对 → 广告判断 → 跳转主页
 * 3. 兜底: Hook startActivity 拦截广告相关 Intent
 */
public class AdsHook {
    private final XposedModule module;
    private final ClassLoader cl;

    private volatile boolean adSkipped = false;

    public AdsHook(XposedModule module, ClassLoader cl) {
        this.module = module;
        this.cl = cl;
    }

    public void hook() throws Throwable {
        Class<?> splashClass = Class.forName(CxClasses.SPLASH_ACTIVITY, false, cl);

        hookAdDisplayMethod(splashClass);
        hookAdTimerBypass(splashClass);
        hookStartActivityFilter(splashClass);

        Logx.i("AdsHook: initialized");
    }

    /**
     * 特征匹配: 查找 SplashActivity 中接受 Ad 类型参数的方法
     * 原始方法: F5(Ad) — 混淆后名称会变，但参数类型 Ad 不变
     */
    private void hookAdDisplayMethod(Class<?> splashClass) {
        try {
            Class<?> adClass = Class.forName("com.chaoxing.mobile.activity.Ad", false, cl);
            boolean found = false;
            for (Method m : splashClass.getDeclaredMethods()) {
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 1 && params[0] == adClass && !Modifier.isStatic(m.getModifiers())) {
                    module.hook(m).intercept(chain -> {
                        Logx.i("AdsHook: blocked ad display → " + chain.getExecutable().getName());
                        return null;
                    });
                    Logx.i("AdsHook: hooked ad display method: " + m.getName() + "(Ad)");
                    found = true;
                    break;
                }
            }
            if (!found) {
                Logx.w("AdsHook: no method(Ad) found in SplashActivity");
            }
        } catch (ClassNotFoundException e) {
            Logx.w("AdsHook: Ad class not found, skip ad display hook");
        }
    }

    /**
     * 特征匹配: 在 SplashActivity 中查找广告计时器跳过逻辑
     * 查找无参 void 非静态方法中调用 startActivity/finish 的方法 → 快速跳转主页
     * 具体: 查找方法 A() 调用方法 B(), 其中 B() 触发 startActivity(MainTabActivity)
     *
     * 回退策略: 若找不到精确匹配，hook SplashActivity.onResume 设置极短延时自动 finish
     */
    private void hookAdTimerBypass(Class<?> splashClass) {
        try {
            // 查找名称中包含 "main" 或 "home" 的 Activity
            Class<?> mainTabClass = null;
            String[] candidates = {
                    "com.chaoxing.mobile.main.ui.MainTabActivity",
                    "com.chaoxing.mobile.main.MainTabActivity",
                    "com.chaoxing.mobile.activity.MainTabActivity",
                    "com.chaoxing.mobile.MainTabActivity"
            };
            for (String name : candidates) {
                try {
                    mainTabClass = Class.forName(name, false, cl);
                    break;
                } catch (ClassNotFoundException ignored) {
                }
            }

            if (mainTabClass != null) {
                final Class<?> targetActivity = mainTabClass;
                // Hook SplashActivity.onResume → 直接启动主页并 finish
                Method onResume = splashClass.getDeclaredMethod("onResume");
                module.hook(onResume).intercept(chain -> {
                    Object result = chain.proceed();
                    Activity splash = (Activity) chain.getThisObject();
                    if (!adSkipped && !splash.isFinishing()) {
                        adSkipped = true;
                        try {
                            Intent intent = new Intent(splash, targetActivity);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            splash.startActivity(intent);
                            splash.finish();
                            Logx.i("AdsHook: fast-forwarded to main activity");
                        } catch (Throwable t) {
                            adSkipped = false;
                            Logx.w("AdsHook: fast-forward failed: " + t.getMessage());
                        }
                    }
                    return result;
                });
                Logx.i("AdsHook: hooked SplashActivity.onResume → fast-forward to " + targetActivity.getSimpleName());
            } else {
                Logx.w("AdsHook: MainTabActivity not found, skip timer bypass");
            }
        } catch (Throwable t) {
            Logx.w("AdsHook: hookAdTimerBypass failed: " + t.getMessage());
        }
    }

    /**
     * 兜底: 在 SplashActivity 上下文中 hook startActivity
     * 过滤广告相关的 Intent (包含 "ad"/"splash"/"promote" 关键字)
     */
    private void hookStartActivityFilter(Class<?> splashClass) {
        try {
            Method startActivity = Activity.class.getDeclaredMethod("startActivity", Intent.class);
            module.hook(startActivity).intercept(chain -> {
                Object thisObj = chain.getThisObject();
                if (thisObj != null && splashClass.isInstance(thisObj)) {
                    Intent intent = (Intent) chain.getArg(0);
                    if (intent != null && intent.getComponent() != null) {
                        String className = intent.getComponent().getClassName().toLowerCase();
                        if (className.contains("ad") && !className.contains("main")) {
                            Logx.i("AdsHook: blocked ad activity launch: " + className);
                            return null;
                        }
                    }
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            Logx.w("AdsHook: hookStartActivityFilter failed: " + t.getMessage());
        }
    }
}
