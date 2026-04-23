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
                Application app = (Application) chain.getThisObject();
                if (app != null && CxClasses.TARGET_PACKAGE.equals(app.getPackageName())) {
                    initializeHooks(cl);
                }
                return result;
            });
            Logx.i("Deferred init: hooked Application.onCreate()");
        } catch (Throwable t) {
            Logx.e("Failed to hook Application.onCreate, trying direct init", t);
            // 回退方案: 直接尝试初始化
            initializeHooks(cl);
        }
    }

    private synchronized void initializeHooks(ClassLoader cl) {
        if (initialized) return;
        initialized = true;

        Logx.i("initializeHooks: starting...");

        // ======== 许可证验证 — 未激活时仅运行免费功能 ========
        LicenseManager license = new LicenseManager(this);
        HookConfig config = license.getConfig();
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
        } catch (Throwable t) {
            Logx.w("Failed to read hook switches, all enabled by default");
        }

        // === 最高优先级: 反检测绕过 (必须在其他 Hook 之前) ===
        if (detectionOn) {
            try {
                new DetectionBypassHook(this, cl).hook();
            } catch (Throwable t) {
                Logx.e("DetectionBypassHook failed", t);
            }
        } else {
            Logx.i("DetectionBypassHook: disabled by user");
        }

        // === 功能 Hook ===
        if (adsOn) {
            try {
                new AdsHook(this, cl).hook();
            } catch (Throwable t) {
                Logx.e("AdsHook failed", t);
            }
        } else {
            Logx.i("AdsHook: disabled by user");
        }

        if (signinOn) {
            try {
                new SignInHook(this, cl).hook();
            } catch (Throwable t) {
                Logx.e("SignInHook failed", t);
            }
        } else {
            Logx.i("SignInHook: disabled by user");
        }

        if (anticheatOn) {
            try {
                new AntiCheatHook(this, cl).hook();
            } catch (Throwable t) {
                Logx.e("AntiCheatHook failed", t);
            }
        } else {
            Logx.i("AntiCheatHook: disabled by user");
        }

        if (windowOn) {
            try {
                new WindowHook(this, cl).hook();
            } catch (Throwable t) {
                Logx.e("WindowHook failed", t);
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
                new ExamHook(this, cl, answerProvider, config.jsInject).hook();
            } catch (Throwable t) {
                Logx.e("ExamHook failed", t);
            }
        } else {
            Logx.i("ExamHook: disabled by user");
        }

        if (videoOn) {
            try {
                new VideoHook(this, cl).hook();
            } catch (Throwable t) {
                Logx.e("VideoHook failed", t);
            }
        } else {
            Logx.i("VideoHook: disabled by user");
        }

        Logx.i("All hooks initialized");
    }
}
