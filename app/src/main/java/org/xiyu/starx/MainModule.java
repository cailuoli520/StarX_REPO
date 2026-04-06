package org.xiyu.starx;

import android.app.Application;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import org.xiyu.starx.answer.AnswerProvider;
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

        // ======== 许可证验证 — 无许可则模块为空壳 ========
        LicenseManager license = new LicenseManager(this);
        HookConfig config = license.getConfig();
        if (config == null) {
            Logx.w("StarX: license not active, module dormant");
            return;
        }

        // 从服务端配置加载类名和端点
        CxClasses.init(config.classes);
        TikuApi.init(config.tikuEndpoints);
        Logx.i("StarX: license OK, config v" + config.version);

        // === 最高优先级: 反检测绕过 (必须在其他 Hook 之前) ===
        try {
            new DetectionBypassHook(this, cl).hook();
        } catch (Throwable t) {
            Logx.e("DetectionBypassHook failed", t);
        }

        // === 功能 Hook ===
        try {
            new AdsHook(this, cl).hook();
        } catch (Throwable t) {
            Logx.e("AdsHook failed", t);
        }

        try {
            new SignInHook(this, cl).hook();
        } catch (Throwable t) {
            Logx.e("SignInHook failed", t);
        }

        try {
            new AntiCheatHook(this, cl).hook();
        } catch (Throwable t) {
            Logx.e("AntiCheatHook failed", t);
        }

        try {
            new WindowHook(this, cl).hook();
        } catch (Throwable t) {
            Logx.e("WindowHook failed", t);
        }

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
            } catch (Throwable t) {
                Logx.w("AI config load failed: " + t.getMessage());
            }
            new ExamHook(this, cl, answerProvider, config.jsInject).hook();
        } catch (Throwable t) {
            Logx.e("ExamHook failed", t);
        }

        try {
            new VideoHook(this, cl).hook();
        } catch (Throwable t) {
            Logx.e("VideoHook failed", t);
        }

        Logx.i("All hooks initialized");
    }
}
