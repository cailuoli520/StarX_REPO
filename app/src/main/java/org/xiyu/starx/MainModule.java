package org.xiyu.starx;

import android.app.Application;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import org.xiyu.starx.answer.AnswerProvider;
import org.xiyu.starx.answer.LemTkApi;
import org.xiyu.starx.answer.TikuApi;
import org.xiyu.starx.hook.AdsHook;
import org.xiyu.starx.hook.AntiCheatHook;
import org.xiyu.starx.hook.DetectionBypassHook;
import org.xiyu.starx.hook.ExamHook;
import org.xiyu.starx.hook.SignInHook;
import org.xiyu.starx.hook.VideoHook;
import org.xiyu.starx.hook.WindowHook;
import org.xiyu.starx.license.HookConfig;
import org.xiyu.starx.license.LicenseManager;
import org.xiyu.starx.util.CxClasses;
import org.xiyu.starx.util.Logx;
import org.xiyu.starx.util.PrivateToast;
import org.xiyu.starx.util.QuestionCache;

import io.github.libxposed.api.XposedModule;

public class MainModule extends XposedModule {

    private volatile boolean initialized = false;

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        Logx.init(this);
        Logx.i("StarX loaded in process: " + param.getProcessName());
    }

    @Override
    @RequiresApi(Build.VERSION_CODES.Q)
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        if (!CxClasses.TARGET_PACKAGE.equals(param.getPackageName())) return;
        Logx.i("onPackageLoaded: " + param.getPackageName());
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!CxClasses.TARGET_PACKAGE.equals(param.getPackageName())) return;
        Logx.i("onPackageReady: " + param.getPackageName());

        if (!param.isFirstPackage()) return;

        ClassLoader cl = param.getClassLoader();

        // 梆梆加固等壳会导致 onPackageReady 时 Application 尚未初始化
        // 延迟到 Application.onCreate() 后再执行许可证验证和 Hook
        try {
            var appOnCreate = Application.class.getDeclaredMethod("onCreate");
            hook(appOnCreate).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Application app = (Application) chain.getThisObject();
                    if (app != null && CxClasses.TARGET_PACKAGE.equals(app.getPackageName())) {
                        try { org.xiyu.starx.util.DebugFlags.initOnInstall(app); } catch (Throwable ignored) {}
                        initializeHooks(cl);
                    }
                } catch (Throwable t) {
                    Logx.e("[boot] FATAL: initializeHooks threw, app continues", t);
                }
                return result;
            });
            Logx.i("Deferred init: hooked Application.onCreate()");
        } catch (Throwable t) {
            Logx.e("Failed to hook Application.onCreate, trying direct init", t);
            // 回退方案: 直接尝试初始化
            try {
                initializeHooks(cl);
            } catch (Throwable t2) {
                Logx.e("[boot] FATAL: direct initializeHooks threw", t2);
            }
        }
    }

    private synchronized void initializeHooks(ClassLoader cl) {
        if (initialized) return;
        initialized = true;

        Logx.f("[boot] initializeHooks: starting...");

        // ======== 许可证验证 — 未激活时仅运行免费功能 ========
        LicenseManager license;
        HookConfig config;
        try {
            Logx.f("[boot] LicenseManager: pre");
            license = new LicenseManager(this);
            config = license.getConfig();
            Logx.f("[boot] LicenseManager: ok config=" + (config == null ? "null" : ("v" + config.version)));
        } catch (Throwable t) {
            Logx.e("[boot] LicenseManager FAILED, fallback to free mode", t);
            config = null;
        }
        if (config == null) {
            Logx.w("StarX: license not active, running in free mode (ads skip only)");
            // 免费功能：广告跳过 — 不依赖服务端配置，本地特征匹配
            try {
                new AdsHook(this, cl).hook();
                Logx.i("StarX[free]: AdsHook enabled");
            } catch (Throwable t) {
                Logx.e("StarX[free]: AdsHook failed", t);
            }
            return;
        }

        // 从服务端配置加载类名和端点
        CxClasses.init(config.classes);
        Logx.i("StarX: license OK, config v" + config.version);

        // === 初始化多题库 ===
        try {
            var prefs = getRemotePreferences("config");
            String tikuSourcesJson = prefs.getString("tiku_sources_json", "");
            TikuApi.init(config.tikuEndpoints, tikuSourcesJson);
            Logx.i("TikuApi initialized");
        } catch (Throwable t) {
            Logx.w("TikuApi init failed: " + t.getMessage());
        }

        // === 初始化题库缓存 ===
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object app = atClass.getMethod("currentApplication").invoke(null);
            if (app != null) {
                QuestionCache.init((android.app.Application) app);
                Logx.i("QuestionCache initialized");
            }
        } catch (Throwable t) {
            Logx.w("QuestionCache init failed: " + t.getMessage());
        }

        // === 读取功能开关 ===
        boolean detectionOn = true, adsOn = true, signinOn = true,
                anticheatOn = true, windowOn = true, videoOn = true, examOn = true;
        try {
            var switchPrefs = getRemotePreferences("config");
            detectionOn = switchPrefs.getBoolean("hook_detection_enabled", true);
            adsOn = switchPrefs.getBoolean("hook_ads_enabled", true);
            signinOn = switchPrefs.getBoolean("hook_signin_enabled", true);
            anticheatOn = switchPrefs.getBoolean("hook_anticheat_enabled", true);
            windowOn = switchPrefs.getBoolean("hook_window_enabled", true);
            videoOn = switchPrefs.getBoolean("hook_video_enabled", true);
            examOn = switchPrefs.getBoolean("hook_exam_enabled", true);
            // 全局 Toast 开关：默认开启（接管所有 PrivateToast）
            boolean toastOn = switchPrefs.getBoolean("hook_toast_enabled", true);
            PrivateToast.setEnabled(toastOn);
        } catch (Throwable t) {
            Logx.w("Failed to read hook switches, all enabled by default");
        }

        // === 最高优先级: 反检测绕过 (必须在其他 Hook 之前) ===
        if (detectionOn) {
            try {
                Logx.f("[boot] DetectionBypassHook: pre");
                new DetectionBypassHook(this, cl).hook();
                Logx.f("[boot] DetectionBypassHook: ok");
            } catch (Throwable t) {
                Logx.e("[boot] DetectionBypassHook failed", t);
            }
        } else {
            Logx.i("DetectionBypassHook: disabled by user");
        }

        // === 功能 Hook ===
        if (adsOn) {
            try {
                Logx.f("[boot] AdsHook: pre");
                new AdsHook(this, cl).hook();
                Logx.f("[boot] AdsHook: ok");
            } catch (Throwable t) {
                Logx.e("[boot] AdsHook failed", t);
            }
        } else {
            Logx.i("AdsHook: disabled by user");
        }

        if (signinOn) {
            try {
                Logx.f("[boot] SignInHook: pre");
                new SignInHook(this, cl).hook();
                Logx.f("[boot] SignInHook: ok");
            } catch (Throwable t) {
                Logx.e("[boot] SignInHook failed", t);
            }
        } else {
            Logx.i("SignInHook: disabled by user");
        }

        if (anticheatOn) {
            try {
                Logx.f("[boot] AntiCheatHook: pre");
                new AntiCheatHook(this, cl).hook();
                Logx.f("[boot] AntiCheatHook: ok");
            } catch (Throwable t) {
                Logx.e("[boot] AntiCheatHook failed", t);
            }
        } else {
            Logx.i("AntiCheatHook: disabled by user");
        }

        if (windowOn) {
            try {
                Logx.f("[boot] WindowHook: pre");
                new WindowHook(this, cl).hook();
                Logx.f("[boot] WindowHook: ok");
            } catch (Throwable t) {
                Logx.e("[boot] WindowHook failed", t);
            }
        } else {
            Logx.i("WindowHook: disabled by user");
        }

        if (examOn) {
            try {
                AnswerProvider answerProvider = new AnswerProvider();
                try {
                    var prefs = getRemotePreferences("config");
                    String openaiKey = prefs.getString("ai_openai_key", "");
                    String openaiUrl = prefs.getString("ai_openai_url", "");
                    String openaiModel = prefs.getString("ai_openai_model", "");
                    String geminiKey = prefs.getString("ai_gemini_key", "");
                    String geminiModel = prefs.getString("ai_gemini_model", "");
                    answerProvider.setOpenAI(openaiKey, openaiUrl, openaiModel);
                    answerProvider.setGemini(geminiKey, geminiModel);
                    answerProvider.setCacheEnabled(prefs.getBoolean("cache_enabled", true));
                } catch (Throwable t) {
                    Logx.w("AI config load failed: " + t.getMessage());
                }
                Logx.f("[boot] ExamHook: pre");
                new ExamHook(this, cl, answerProvider, config.jsInject).hook();
                Logx.f("[boot] ExamHook: ok");
            } catch (Throwable t) {
                Logx.e("[boot] ExamHook failed", t);
            }
        } else {
            Logx.i("ExamHook: disabled by user");
        }

        if (videoOn) {
            try {
                Logx.f("[boot] VideoHook: pre");
                new VideoHook(this, cl).hook();
                Logx.f("[boot] VideoHook: ok");
            } catch (Throwable t) {
                Logx.e("[boot] VideoHook failed", t);
            }
        } else {
            Logx.i("VideoHook: disabled by user");
        }

        Logx.f("[boot] All hooks initialized");
    }
}
