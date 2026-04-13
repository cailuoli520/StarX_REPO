package org.xiyu.starx.hook;

import android.app.Activity;

import org.xiyu.starx.util.ClassFinder;
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
    private final ClassFinder finder;

    /** 考试页面是否处于前台，用于 getRunningAppProcesses 等全局 hook 的守卫 */
    private static volatile boolean examInForeground = false;

    public AntiCheatHook(XposedModule module, ClassLoader cl) {
        this.module = module;
        this.cl = cl;
        this.finder = new ClassFinder(cl);
    }

    /**
     * 判断 Activity 是否属于考试/测验相关页面
     */
    private static boolean isExamActivity(Object activity) {
        if (activity == null) return false;
        String cls = activity.getClass().getName();
        // 优先匹配服务端下发的确切类名（可靠，不受混淆影响）
        String wa = CxClasses.WEBAPP_VIEWER_ACTIVITY;
        if (wa != null && !wa.isEmpty() && cls.equals(wa)) return true;
        // 回退到关键字启发式
        String lower = cls.toLowerCase();
        return lower.contains("exam") || lower.contains("test")
                || lower.contains("webapp") || lower.contains("quiz");
    }

    public void hook() throws Throwable {
        trackExamLifecycle();
        hookWindowFocus();
        hookTopResumedActivity();
        hookFaceCollectBySignature();
        hookBehaviorReporterDirect();
        hookOkHttpBehaviorFilter();
        hookScreenMonitor();
        hookExamEveriskProtocol();
        hookActivityProcessImportance();
        Logx.i("AntiCheatHook: initialized");
    }

    /**
     * 跟踪考试页面的生命周期，维护 examInForeground 标志。
     * 供 getRunningAppProcesses 等全局 hook 用作守卫条件。
     */
    private void trackExamLifecycle() {
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            module.hook(onResume).intercept(chain -> {
                if (isExamActivity(chain.getThisObject())) {
                    examInForeground = true;
                }
                return chain.proceed();
            });

            Method onPause = Activity.class.getDeclaredMethod("onPause");
            module.hook(onPause).intercept(chain -> {
                if (isExamActivity(chain.getThisObject())) {
                    examInForeground = false;
                }
                return chain.proceed();
            });
            Logx.i("AntiCheatHook: exam lifecycle tracking installed");
        } catch (Throwable t) {
            Logx.w("AntiCheatHook: exam lifecycle tracking failed: " + t.getMessage());
        }
    }

    /**
     * Hook Activity.onWindowFocusChanged — 始终回调 hasFocus=true
     * Hook Activity.hasWindowFocus — 始终返回 true
     */
    private void hookWindowFocus() {
        try {
            Method onWindowFocusChanged = Activity.class.getDeclaredMethod("onWindowFocusChanged", boolean.class);
            module.hook(onWindowFocusChanged).intercept(chain -> {
                if (isExamActivity(chain.getThisObject())) {
                    return chain.proceed(new Object[]{true});
                }
                return chain.proceed();
            });

            Method hasWindowFocus = Activity.class.getDeclaredMethod("hasWindowFocus");
            module.hook(hasWindowFocus).intercept(chain -> {
                if (isExamActivity(chain.getThisObject())) return true;
                return chain.proceed();
            });

            Logx.i("AntiCheatHook: hooked window focus detection (exam only)");
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
                if (isExamActivity(chain.getThisObject())) {
                    return chain.proceed(new Object[]{true});
                }
                return chain.proceed();
            });
            Logx.i("AntiCheatHook: hooked onTopResumedActivityChanged (exam only)");
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

    /**
     * 【新增】Hook ScreenMonitorUploadDispatcher — 阻止屏幕监控上传
     *
     * 超星考试会通过 ScreenMonitorUploadDispatcher 采集屏幕状态并上传服务端,
     * 记录切屏/分屏行为。拦截所有上传相关方法。
     */
    private void hookScreenMonitor() {
        String[] candidates = {
                "com.chaoxing.mobile.exam.ScreenMonitorUploadDispatcher",
                "com.chaoxing.mobile.exam.collect.ScreenMonitorUploadDispatcher"
        };
        Class<?> cls = finder.resolve(CxClasses.SCREEN_MONITOR_DISPATCHER, candidates);
        if (cls == null) {
            Logx.w("AntiCheatHook: ScreenMonitorUploadDispatcher not found, skip");
            return;
        }
        int hooked = 0;
        for (Method m : cls.getDeclaredMethods()) {
            // 拦截所有非静态 void 方法 (upload/dispatch/schedule 等)
            if (m.getReturnType() == void.class && !Modifier.isStatic(m.getModifiers())) {
                module.hook(m).intercept(chain -> {
                    Logx.i("AntiCheatHook: blocked ScreenMonitor." + chain.getExecutable().getName());
                    return null;
                });
                hooked++;
            }
        }
        Logx.i("AntiCheatHook: hooked ScreenMonitorUploadDispatcher (" + hooked + " methods)");
    }

    /**
     * 【新增】Hook ExamEveriskProtocol — 拦截考试风控协议
     *
     * CLIENT_EVERISK_INFO_CHECK 协议通过 ExamEveriskProtocol 调用 EverISK SDK,
     * 检测到异常后弹窗并强制退出。拦截其关键方法。
     */
    private void hookExamEveriskProtocol() {
        String[] candidates = {
                "com.chaoxing.mobile.webapp.jsprotocal.exam.ExamEveriskProtocol",
                "com.chaoxing.mobile.webapp.jsprotocol.exam.ExamEveriskProtocol"
        };
        Class<?> cls = finder.resolve(CxClasses.EXAM_EVERISK_PROTOCOL, candidates);
        if (cls == null) {
            Logx.w("AntiCheatHook: ExamEveriskProtocol not found, skip");
            return;
        }
        int hooked = 0;
        for (Method m : cls.getDeclaredMethods()) {
            Class<?>[] params = m.getParameterTypes();
            // 拦截返回 void 的方法 (handle/execute/check 类型)
            if (m.getReturnType() == void.class && !Modifier.isStatic(m.getModifiers())) {
                module.hook(m).intercept(chain -> {
                    Logx.i("AntiCheatHook: blocked ExamEverisk." + chain.getExecutable().getName());
                    return null;
                });
                hooked++;
            }
        }
        Logx.i("AntiCheatHook: hooked ExamEveriskProtocol (" + hooked + " methods)");
    }

    /**
     * 【新增】Hook ActivityManager.getRunningAppProcesses — 进程前台伪装
     *
     * 部分检测代码通过 getRunningAppProcesses().get(0).importance 判断应用是否在前台，
     * 切屏后 importance 从 IMPORTANCE_FOREGROUND(100) 变为更高值。
     * Hook 返回值中的 importance 字段, 始终设为 FOREGROUND。
     */
    private void hookActivityProcessImportance() {
        try {
            Method getProcesses = android.app.ActivityManager.class.getDeclaredMethod(
                    "getRunningAppProcesses");
            module.hook(getProcesses).intercept(chain -> {
                List<?> result = (List<?>) chain.proceed();
                // 仅在考试页面处于前台时才伪装进程重要性
                if (!examInForeground) return result;
                if (result != null && !result.isEmpty()) {
                    for (Object info : result) {
                        if (info instanceof android.app.ActivityManager.RunningAppProcessInfo) {
                            android.app.ActivityManager.RunningAppProcessInfo rapi =
                                    (android.app.ActivityManager.RunningAppProcessInfo) info;
                            if (rapi.processName != null
                                    && rapi.processName.contains("chaoxing")) {
                                rapi.importance =
                                        android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
                            }
                        }
                    }
                }
                return result;
            });
            Logx.i("AntiCheatHook: hooked getRunningAppProcesses (exam-guarded)");
        } catch (Throwable t) {
            Logx.w("AntiCheatHook: process importance hook failed: " + t.getMessage());
        }
    }
}
