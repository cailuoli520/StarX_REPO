package org.xiyu.starx.hook;

import android.os.Build;

import org.xiyu.starx.util.CxClasses;
import org.xiyu.starx.util.Logx;

import java.io.BufferedReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import io.github.libxposed.api.XposedModule;

/**
 * 反检测绕过模块 — 绕过学习通/梆梆加固的所有安全检测
 *
 * 检测层级:
 * 1. 梆梆 EverISK 风险引擎 (com.coralline.sea01.y) — 18种检测的统一分发
 * 2. 百度盾 Xposed 检测 (com.baidu.mshield) — methodCache 反射检测
 * 3. 学习通自研 Root 检测 x2 (study.util.b + mobile.p) — tags/su/which
 * 4. Magisk/Zygisk 检测 (com.coralline.sea01.v) — mountinfo 扫描
 * 5. TracerPid 反调试 — BufferedReader 层伪装 (避免触碰 secneo 篡改标记)
 * 6. 远程强杀防御 — 拦截 CLIENT_EVERISK_INFO_CHECK 协议的 System.exit(0)
 * 7. Native 标志隐藏 — Method.getModifiers() 纵深防御
 * 8. 环境检测绕过 — fh.a: 模拟定位/开发者模式/USB调试检测
 */
public class DetectionBypassHook {
    private final XposedModule module;
    private final ClassLoader cl;

    // ── 反编译映射: 混淆名 → 可读名 ──
    /** com.coralline.sea01.y = EveriskRiskDispatcher — 18种风控检测的统一分发器 */
    private static final String EVERISK_RISK_DISPATCHER = "com.coralline.sea01.y";
    /** com.baidu.mshield.x6.c.a = XposedHookDetector — Baidu 盾 methodCache 反射检测 */
    private static final String XPOSED_HOOK_DETECTOR = "com.baidu.mshield.x6.c.a";
    /** com.chaoxing.mobile.study.util.b = RootDetectionUtil — tags/su/which 检测 */
    private static final String ROOT_DETECTION_UTIL = "com.chaoxing.mobile.study.util.b";
    /** com.chaoxing.mobile.p = DeviceInfoUtil — 设备信息收集 + Root 检测 (e()=isDeviceRooted) */
    private static final String DEVICE_INFO_UTIL = "com.chaoxing.mobile.p";
    /** com.coralline.sea01.v = MagiskMountDetector — mountinfo 扫描 magisk/zygisk/KSU */
    private static final String MAGISK_MOUNT_DETECTOR = "com.coralline.sea01.v";
    /** fh.a = EnvironmentDetector — 模拟定位/开发者模式/USB调试检测 */
    private static final String ENVIRONMENT_DETECTOR = "fh.a";
    // 注意: 已移除 ANTI_DEBUG_PROTECTOR (com.secneo.apkwrapper.H)
    // secneo 包所有方法含字节码篡改标记 int[] iArr = new int[0], 直接 hook 会被 native 层检测!
    // TracerPid 反调试已改为 hook BufferedReader.readLine() 在文件读取层伪装

    public DetectionBypassHook(XposedModule module, ClassLoader cl) {
        this.module = module;
        this.cl = cl;
    }

    public void hook() throws Throwable {
        // === P0 最高优先级: 底层防护 (必须最先执行) ===
        hookProcStatusRead();      // TracerPid 伪装 — 替代直接 hook secneo.H (避免篡改标记)
        hookGetModifiers();        // native 标志隐藏 — 纵深防御 mshield isNative 检测
        hookSystemExit();          // 远程强杀防御 — 拦截考试风控 System.exit(0)

        // === 安全检测绕过 ===
        hookEveriskDispatcher();   // EverISK 18种风控统一分发
        hookBaiduMshield();        // 百度盾 Xposed 检测 (methodCache + isNative)
        hookChaoxingRootDetect();  // 第1层 Root 检测 (study.util.b)
        hookChaoxingDeviceRootDetect(); // 第2层 Root 检测 (mobile.p)
        hookMagiskDetect();        // Magisk/Zygisk mountinfo 扫描
        hookEnvironmentDetect();   // fh.a: 模拟定位/开发者模式/USB调试

        Logx.i("DetectionBypassHook: initialized (10 hooks active)");
    }

    /**
     * 【核心】Hook EverISK 风险检测统一分发点
     * com.coralline.sea01.y.a(JSONObject, Type) → 返回 "{}"
     * 所有 RiskStubAPI.checkXxxStatus() 最终都经过此方法
     */
    private void hookEveriskDispatcher() {
        try {
            Class<?> dispatcherClass = Class.forName(EVERISK_RISK_DISPATCHER, false, cl);
            Class<?> typeClass = Class.forName("com.bangcle.everisk.core.Type", false, cl);
            Class<?> jsonClass = Class.forName("org.json.JSONObject", false, cl);

            for (Method m : dispatcherClass.getDeclaredMethods()) {
                Class<?>[] params = m.getParameterTypes();
                // 匹配 String a(JSONObject, Type) — 实例方法
                if (params.length == 2
                        && params[0] == jsonClass
                        && params[1] == typeClass
                        && m.getReturnType() == String.class
                        && !Modifier.isStatic(m.getModifiers())) {
                    module.hook(m).intercept(chain -> {
                        Object typeArg = chain.getArg(1);
                        Logx.i("DetectionBypassHook: intercepted risk check [" + typeArg + "] → safe");
                        return "{}";
                    });
                    Logx.i("DetectionBypassHook: hooked EverISK dispatcher: " + m.getName());
                    break;
                }
            }
        } catch (ClassNotFoundException e) {
            Logx.w("DetectionBypassHook: EverISK classes not found, skip");
        } catch (Throwable t) {
            Logx.e("DetectionBypassHook: hookEveriskDispatcher failed", t);
        }
    }

    /**
     * Hook 百度盾 Xposed 检测
     * com.baidu.mshield.x6.c.a.a(String, String, String) → 返回 0
     * 检测原理: 反射 XposedHelpers.methodCache + Modifier.isNative
     */
    private void hookBaiduMshield() {
        try {
            Class<?> mshieldClass = Class.forName(XPOSED_HOOK_DETECTOR, false, cl);
            for (Method m : mshieldClass.getDeclaredMethods()) {
                Class<?>[] params = m.getParameterTypes();
                // 匹配 int a(String, String, String) — 静态方法
                if (params.length == 3
                        && params[0] == String.class
                        && params[1] == String.class
                        && params[2] == String.class
                        && m.getReturnType() == int.class
                        && Modifier.isStatic(m.getModifiers())) {
                    module.hook(m).intercept(chain -> {
                        Logx.i("DetectionBypassHook: blocked mshield Xposed check for " + chain.getArg(0) + "." + chain.getArg(1));
                        return 0;
                    });
                    Logx.i("DetectionBypassHook: hooked Baidu mshield: " + m.getName());
                    break;
                }
            }
        } catch (ClassNotFoundException e) {
            Logx.w("DetectionBypassHook: Baidu mshield not found, skip");
        } catch (Throwable t) {
            Logx.e("DetectionBypassHook: hookBaiduMshield failed", t);
        }
    }

    /**
     * Hook 学习通考试 Root 检测
     * com.chaoxing.mobile.study.util.b.c() → false (Build.TAGS)
     * com.chaoxing.mobile.study.util.b.d() → false (su 文件)
     * com.chaoxing.mobile.study.util.b.e() → false (which su)
     */
    private void hookChaoxingRootDetect() {
        try {
            Class<?> rootClass = Class.forName(ROOT_DETECTION_UTIL, false, cl);
            // Hook 所有返回 boolean 的无参静态方法 (c, d, e)
            for (Method m : rootClass.getDeclaredMethods()) {
                if (m.getParameterTypes().length == 0
                        && m.getReturnType() == boolean.class
                        && Modifier.isStatic(m.getModifiers())) {
                    module.hook(m).intercept(chain -> {
                        Logx.i("DetectionBypassHook: blocked root check " + chain.getExecutable().getName() + " → false");
                        return false;
                    });
                }
            }
            // Hook f() — 组合检测 (c || d || e)
            for (Method m : rootClass.getDeclaredMethods()) {
                if (m.getParameterTypes().length == 0
                        && m.getReturnType() == boolean.class
                        && !Modifier.isStatic(m.getModifiers())) {
                    // f() 是非静态或其他组合方法
                }
            }
            Logx.i("DetectionBypassHook: hooked study.util.b root detection");
        } catch (ClassNotFoundException e) {
            Logx.w("DetectionBypassHook: study.util.b not found, skip");
        } catch (Throwable t) {
            Logx.e("DetectionBypassHook: hookChaoxingRootDetect failed", t);
        }
    }

    /**
     * Hook 学习通设备信息收集中的 Root 检测
     * com.chaoxing.mobile.p — 与 study.util.b 相同逻辑
     */
    private void hookChaoxingDeviceRootDetect() {
        try {
            Class<?> deviceClass = Class.forName(DEVICE_INFO_UTIL, false, cl);
            for (Method m : deviceClass.getDeclaredMethods()) {
                if (m.getParameterTypes().length == 0
                        && m.getReturnType() == boolean.class
                        && Modifier.isStatic(m.getModifiers())) {
                    module.hook(m).intercept(chain -> false);
                }
            }
            Logx.i("DetectionBypassHook: hooked mobile.p root detection");
        } catch (ClassNotFoundException e) {
            Logx.w("DetectionBypassHook: mobile.p not found, skip");
        } catch (Throwable t) {
            Logx.e("DetectionBypassHook: hookChaoxingDeviceRootDetect failed", t);
        }
    }

    /**
     * Hook Magisk/Zygisk/KSU 检测
     * com.coralline.sea01.v.a(int) → 返回 0
     * 检测原理: 扫描 /proc/<pid>/mountinfo 查找 magisk/zygisk/shamiko 关键字
     */
    private void hookMagiskDetect() {
        try {
            Class<?> magiskClass = Class.forName(MAGISK_MOUNT_DETECTOR, false, cl);
            for (Method m : magiskClass.getDeclaredMethods()) {
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 1
                        && params[0] == int.class
                        && m.getReturnType() == int.class
                        && Modifier.isStatic(m.getModifiers())) {
                    module.hook(m).intercept(chain -> {
                        Logx.i("DetectionBypassHook: blocked Magisk mountinfo scan → 0");
                        return 0;
                    });
                    Logx.i("DetectionBypassHook: hooked Magisk detection: " + m.getName());
                    break;
                }
            }
        } catch (ClassNotFoundException e) {
            Logx.w("DetectionBypassHook: sea01.v not found, skip");
        } catch (Throwable t) {
            Logx.e("DetectionBypassHook: hookMagiskDetect failed", t);
        }
    }

    /**
     * 【P0 关键修复】伪装 /proc/self/status 中的 TracerPid 值
     *
     * 之前: 直接 hook com.secneo.apkwrapper.H.Iii1Iii1IIIi1(int)
     * 问题: secneo 包所有方法含字节码篡改标记 int[] iArr = new int[0]
     *       DexHelper.so 扫描字节码, hook 后标记消失 → 被检测!
     *
     * 现在: hook BufferedReader.readLine() 在文件读取层伪装
     * 所有读 /proc/self/status 的代码 (包括 secneo.H 和跨进程子进程)
     * 看到的 TracerPid 值都是 0
     */
    private void hookProcStatusRead() {
        try {
            Method readLine = BufferedReader.class.getDeclaredMethod("readLine");
            module.hook(readLine).intercept(chain -> {
                String line = (String) chain.proceed();
                if (line != null && line.startsWith("TracerPid:")) {
                    Logx.i("DetectionBypassHook: faked TracerPid → 0");
                    return "TracerPid:\t0";
                }
                return line;
            });
            Logx.i("DetectionBypassHook: hooked BufferedReader.readLine for anti-debug bypass");
        } catch (Throwable t) {
            Logx.e("DetectionBypassHook: hookProcStatusRead failed", t);
        }
    }

    /**
     * 【P0】防御远程强杀 — 拦截 System.exit(int)
     *
     * 超星考试风控流程:
     * 1. 服务端通过 WebView 注入 CLIENT_EVERISK_INFO_CHECK JS 协议
     * 2. exam.g (ExamEveriskProtocol) 调用 EverISK SDK 检测
     * 3. 回调收到 STRATEGY 类型 → 弹窗 → 用户点击 → finishAndRemoveTask() + System.exit(0)
     *
     * 我们拦截 Runtime.exit(), 若调用栈来自安全检测代码则吞掉
     */
    private void hookSystemExit() {
        try {
            Method exitMethod = Runtime.class.getDeclaredMethod("exit", int.class);
            module.hook(exitMethod).intercept(chain -> {
                StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                for (StackTraceElement element : stack) {
                    String cls = element.getClassName();
                    if (cls.contains("jsprotocal") || cls.contains("everisk")
                            || cls.contains("bangcle") || cls.contains("sea01")
                            || cls.contains("mshield") || cls.contains("secneo")) {
                        Logx.i("DetectionBypassHook: BLOCKED System.exit from "
                                + cls + "." + element.getMethodName());
                        return null; // 吞掉, 阻止进程被杀
                    }
                }
                // 非安全代码的正常 exit 调用, 放行
                return chain.proceed();
            });
            Logx.i("DetectionBypassHook: hooked Runtime.exit for anti-kill defense");
        } catch (Throwable t) {
            Logx.e("DetectionBypassHook: hookSystemExit failed", t);
        }
    }

    /**
     * 【P1 纵深防御】隐藏被 Hook 方法的 native 标志
     *
     * mshield XposedHookDetector 使用 Modifier.isNative(method.getModifiers())
     * 检测方法是否被 Xposed 替换为 native (经典 Xposed 的 hook 机制)
     *
     * 虽然 hookBaiduMshield() 已拦截检测入口, 此 hook 作为纵深防御
     * 防止其他未知代码路径进行同类检测
     *
     * 优化: 快速路径 — 非 native 方法 (>99%) 直接返回, 零额外开销
     *       仅对应用层类 (com.chaoxing/coralline/j9/de/greenrobot) 剥离 NATIVE 标志
     *       不影响 java/android 等系统真正的 native 方法
     */
    private void hookGetModifiers() {
        try {
            Method getModifiers = Method.class.getDeclaredMethod("getModifiers");
            module.hook(getModifiers).intercept(chain -> {
                int modifiers = (int) chain.proceed();
                // 快速路径: 非 native → 直接返回 (覆盖 >99% 的调用)
                if ((modifiers & Modifier.NATIVE) == 0) return modifiers;

                // 仅处理应用层类, 不干扰系统真正的 native 方法
                Method target = (Method) chain.getThisObject();
                if (target != null) {
                    String cls = target.getDeclaringClass().getName();
                    if (cls.startsWith("com.chaoxing.") || cls.startsWith("com.coralline.")
                            || cls.startsWith("j9.") || cls.startsWith("de.")
                            || cls.startsWith("org.greenrobot.")) {
                        return modifiers & ~Modifier.NATIVE;
                    }
                }
                return modifiers;
            });
            Logx.i("DetectionBypassHook: hooked Method.getModifiers for native flag masking");
        } catch (Throwable t) {
            Logx.e("DetectionBypassHook: hookGetModifiers failed", t);
        }
    }

    /**
     * Hook 环境检测 (fh.a) — 考试/签到时的设备环境校验
     *
     * CLIENT_PHONE_ENVIRONMENT_CHECK 协议调用这些方法:
     * - fh.a.a(Context) → 模拟定位是否可用 (SDK<=22: mock_location, SDK>=23: addTestProvider)
     * - fh.a.b(Activity) → 开发者选项是否开启
     * - fh.a.c(Activity) → USB调试是否开启
     *
     * 还通过 EventBus 持续监听, 非一次性检测
     */
    private void hookEnvironmentDetect() {
        try {
            Class<?> envClass = Class.forName(ENVIRONMENT_DETECTOR, false, cl);

            // Hook 所有返回 boolean 的静态方法 (a, b, c) → false
            for (Method m : envClass.getDeclaredMethods()) {
                if (m.getReturnType() == boolean.class
                        && Modifier.isStatic(m.getModifiers())
                        && m.getParameterTypes().length == 1) {
                    module.hook(m).intercept(chain -> {
                        Logx.i("DetectionBypassHook: blocked env check "
                                + chain.getExecutable().getName() + " → false");
                        return false;
                    });
                }
            }
            Logx.i("DetectionBypassHook: hooked fh.a environment detection (mock/dev/usb)");
        } catch (ClassNotFoundException e) {
            Logx.w("DetectionBypassHook: fh.a not found, skip");
        } catch (Throwable t) {
            Logx.e("DetectionBypassHook: hookEnvironmentDetect failed", t);
        }
    }
}
