package org.xiyu.starx.hook;

import android.app.Activity;

import org.xiyu.starx.util.CxClasses;
import org.xiyu.starx.util.Logx;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import io.github.libxposed.api.XposedModule;

/**
 * 考试防作弊 Hook
 *
 * 功能:
 * 1. 窗口焦点伪装 — Activity.onWindowFocusChanged/hasWindowFocus 始终为 true
 * 2. 前台检测绕过 — onTopResumedActivityChanged 始终为 true
 * 3. 人脸采集绕过 — 特征匹配 FaceCollectManager 中的方法
 * 4. 行为上报拦截 — Hook OkHttp 拦截器过滤上报请求
 * 5. 直接拦截 — 行为收集器的上报方法
 */
public class AntiCheatHook {
    private final XposedModule module;
    private final ClassLoader cl;

    public AntiCheatHook(XposedModule module, ClassLoader cl) {
        this.module = module;
        this.cl = cl;
    }

    public void hook() throws Throwable {
        hookWindowFocus();
        hookTopResumedActivity();
        hookFaceCollectBySignature();
        hookBehaviorReporterDirect();
        hookOkHttpBehaviorFilter();
        Logx.i("AntiCheatHook: initialized");
    }

    /**
     * Hook Activity.onWindowFocusChanged — 始终回调 hasFocus=true
     * Hook Activity.hasWindowFocus — 始终返回 true
     */
    private void hookWindowFocus() {
        try {
            Method onWindowFocusChanged = Activity.class.getDeclaredMethod("onWindowFocusChanged", boolean.class);
            module.hook(onWindowFocusChanged).intercept(chain -> {
                return chain.proceed(new Object[]{true});
            });

            Method hasWindowFocus = Activity.class.getDeclaredMethod("hasWindowFocus");
            module.hook(hasWindowFocus).intercept(chain -> true);

            Logx.i("AntiCheatHook: hooked window focus detection");
        } catch (Throwable t) {
            Logx.e("AntiCheatHook: hookWindowFocus failed", t);
        }
    }

    /**
     * Hook Activity.onTopResumedActivityChanged — 始终回调 true
     */
    private void hookTopResumedActivity() {
        try {
            Method onTopResumed = Activity.class.getDeclaredMethod("onTopResumedActivityChanged", boolean.class);
            module.hook(onTopResumed).intercept(chain -> {
                return chain.proceed(new Object[]{true});
            });
            Logx.i("AntiCheatHook: hooked onTopResumedActivityChanged");
        } catch (Throwable t) {
            Logx.w("AntiCheatHook: onTopResumedActivityChanged not available: " + t.getMessage());
        }
    }

    /**
     * 【重构】人脸采集绕过 — 特征匹配代替硬编码混淆名
     *
     * FaceCollectManager 的特征:
     * - 无参返回 boolean 的方法 → 活跃状态检测 (原 j0) → 返回 false
     * - 无参返回 int 的方法 → 启用状态检测 (原 c0) → 返回 0
     * - 返回 void 且参数含 Activity 的方法 → 采集启动 → 拦截
     */
    private void hookFaceCollectBySignature() {
        try {
            Class<?> fcmClass = Class.forName(CxClasses.FACE_COLLECT_MANAGER, false, cl);
            int hookedCount = 0;

            for (Method m : fcmClass.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] params = m.getParameterTypes();

                // 无参 boolean → 采集活跃状态 → false
                if (params.length == 0 && m.getReturnType() == boolean.class) {
                    module.hook(m).intercept(chain -> {
                        Logx.i("AntiCheatHook: FaceCollect." + chain.getExecutable().getName() + "() → false");
                        return false;
                    });
                    hookedCount++;
                }

                // 无参 int → 采集启用标志 → 0
                if (params.length == 0 && m.getReturnType() == int.class) {
                    module.hook(m).intercept(chain -> {
                        Logx.i("AntiCheatHook: FaceCollect." + chain.getExecutable().getName() + "() → 0");
                        return 0;
                    });
                    hookedCount++;
                }
            }

            Logx.i("AntiCheatHook: hooked FaceCollectManager (" + hookedCount + " methods by signature)");
        } catch (ClassNotFoundException e) {
            Logx.w("AntiCheatHook: FaceCollectManager not found, skip");
        }
    }

    /**
     * 【重构】行为上报拦截 — 直接 Hook 行为收集器
     * 使用特征匹配: 在 com.chaoxing.mobile.exam.collect 包下找单例模式+上报方法
     */
    private void hookBehaviorReporterDirect() {
        try {
            Class<?> reporterClass = Class.forName(CxClasses.EXAM_BEHAVIOR_REPORTER, false, cl);

            // 匹配所有接受 (String, String) 参数的方法 — 行为上报
            for (Method m : reporterClass.getDeclaredMethods()) {
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 2
                        && params[0] == String.class
                        && params[1] == String.class
                        && !Modifier.isStatic(m.getModifiers())) {
                    module.hook(m).intercept(chain -> {
                        String arg0 = (String) chain.getArg(0);
                        String arg1 = (String) chain.getArg(1);
                        // 过滤敏感事件: on_pause, switch_app, lose_focus, copy, screenshot
                        if (arg1 != null) {
                            String event = arg1.toLowerCase();
                            if (event.contains("pause") || event.contains("switch")
                                    || event.contains("focus") || event.contains("copy")
                                    || event.contains("screenshot") || event.contains("blur")) {
                                Logx.i("AntiCheatHook: blocked behavior report [" + arg0 + "] " + arg1);
                                return null;
                            }
                        }
                        return chain.proceed();
                    });
                    Logx.i("AntiCheatHook: hooked behavior reporter method: " + m.getName());
                }
            }
        } catch (Throwable t) {
            Logx.w("AntiCheatHook: behavior reporter direct hook failed: " + t.getMessage());
        }
    }

    /**
     * 【新增】OkHttp 网络层拦截 — 过滤行为上报的 HTTP 请求
     * 作为 hookBehaviorReporterDirect 的补充，在网络层兜底
     */
    private void hookOkHttpBehaviorFilter() {
        try {
            Class<?> requestClass = Class.forName("okhttp3.Request", false, cl);
            Method urlMethod = requestClass.getDeclaredMethod("url");

            // Hook 具体实现类 RealInterceptorChain.proceed(Request) 而非抽象接口
            Class<?> realChainClass = null;
            String[] chainCandidates = {
                    "okhttp3.internal.http.RealInterceptorChain",
                    "okhttp3.internal.connection.RealInterceptorChain"
            };
            for (String name : chainCandidates) {
                try {
                    realChainClass = Class.forName(name, false, cl);
                    break;
                } catch (ClassNotFoundException ignored) {}
            }

            if (realChainClass == null) {
                Logx.w("AntiCheatHook: RealInterceptorChain not found, skip network filter");
                return;
            }

            Method proceedMethod = realChainClass.getDeclaredMethod("proceed", requestClass);

            module.hook(proceedMethod).intercept(chain -> {
                Object request = chain.getArg(0);
                if (request != null) {
                    try {
                        Object url = urlMethod.invoke(request);
                        String urlStr = url != null ? url.toString().toLowerCase() : "";
                        if (urlStr.contains("exam/collect") || urlStr.contains("behavior/report")
                                || urlStr.contains("anti-cheat") || urlStr.contains("monitor/exam")) {
                            Logx.i("AntiCheatHook: blocked network report: " + urlStr);
                            Class<?> responseBuilderClass = Class.forName("okhttp3.Response$Builder", false, cl);
                            Class<?> protocolClass = Class.forName("okhttp3.Protocol", false, cl);
                            Class<?> responseBodyClass = Class.forName("okhttp3.ResponseBody", false, cl);
                            Class<?> mediaTypeClass = Class.forName("okhttp3.MediaType", false, cl);

                            Object protocol = protocolClass.getField("HTTP_1_1").get(null);
                            Object jsonType = mediaTypeClass.getDeclaredMethod("parse", String.class)
                                    .invoke(null, "application/json");
                            Object fakeBody = responseBodyClass.getDeclaredMethod("create", mediaTypeClass, String.class)
                                    .invoke(null, jsonType, "{\"success\":true}");

                            Object builder = responseBuilderClass.getDeclaredConstructor().newInstance();
                            responseBuilderClass.getDeclaredMethod("request", requestClass).invoke(builder, request);
                            responseBuilderClass.getDeclaredMethod("protocol", protocolClass).invoke(builder, protocol);
                            responseBuilderClass.getDeclaredMethod("code", int.class).invoke(builder, 200);
                            responseBuilderClass.getDeclaredMethod("message", String.class).invoke(builder, "OK");
                            responseBuilderClass.getDeclaredMethod("body", responseBodyClass).invoke(builder, fakeBody);
                            return responseBuilderClass.getDeclaredMethod("build").invoke(builder);
                        }
                    } catch (Throwable ignored) {}
                }
                return chain.proceed();
            });
            Logx.i("AntiCheatHook: hooked OkHttp RealInterceptorChain for behavior filtering");
        } catch (ClassNotFoundException e) {
            Logx.w("AntiCheatHook: OkHttp classes not found, skip network filter");
        } catch (Throwable t) {
            Logx.w("AntiCheatHook: OkHttp interceptor hook failed: " + t.getMessage());
        }
    }
}
