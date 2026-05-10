package org.xiyu.starx.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * Hook 目标应用内的提示统一带隐私标签，避免系统 Toast 和应用内提示口径不一致。
 * <p>
 * 由 MainModule 在 boot 阶段调用 {@link #setEnabled(boolean)} 注入"全局 Toast 开关"。
 * 关闭后，所有 PrivateToast.show 调用降级为 Logx.f，避免学习通页面被弹窗打扰；
 * 同时提供 {@link #suppressToast(Context, CharSequence, int)} 让 Toast.makeText hook
 * 能够复用同一开关。
 */
public final class PrivateToast {
    /** 已弃用：早期前缀会随 Toast 一起被截屏录制，反而泄露身份。保留常量仅供日志归类。 */
    private static final String LOG_TAG = "[priv-toast] ";
    private static volatile boolean sEnabled = true;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private PrivateToast() {}

    /** 由 MainModule 启动时根据用户偏好注入。默认 true。 */
    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
        Logx.i("PrivateToast: enabled=" + enabled);
    }

    public static boolean isEnabled() {
        return sEnabled;
    }

    public static void show(final Context context, final String message, final int duration) {
        if (context == null || message == null || message.trim().isEmpty()) {
            return;
        }
        final String text = message.trim(); // 不再加可见前缀，避免被截屏
        if (!sEnabled) {
            // 关闭时仅日志输出，不打扰用户
            Logx.f("[toast-muted] " + LOG_TAG + text);
            return;
        }
        Runnable showOnMain = () -> {
            try {
                Toast.makeText(context, text, duration).show();
            } catch (Throwable t) {
                Logx.w("PrivateToast.show failed: " + t.getMessage());
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showOnMain.run();
        } else {
            MAIN.post(showOnMain);
        }
    }

    /**
     * 兼容旧调用：之前其它代码可能拼了 [隐私] 前缀，此处统一剥离，保证最终展示干净。
     */
    public static String withLabel(String message) {
        String text = message == null ? "" : message.trim();
        if (text.startsWith("[隐私]")) text = text.substring("[隐私]".length()).trim();
        else if (text.startsWith("【隐私】")) text = text.substring("【隐私】".length()).trim();
        return text;
    }

    /**
     * 供 Toast.makeText hook 调用，判断是否要拦截宿主自身的 Toast。
     * 当全局开关关闭时返回 true，调用方应返回一个 no-op Toast 或直接吞掉。
     */
    public static boolean shouldSuppressHostToast() {
        return !sEnabled;
    }

    /** Toast hook 可调用此方法将原始内容转写到日志，便于排障。 */
    public static void suppressToast(Context ctx, CharSequence text, int duration) {
        Logx.f("[toast-host-muted] " + (text == null ? "" : text.toString()));
    }
}
