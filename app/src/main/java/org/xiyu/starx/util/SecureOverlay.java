package org.xiyu.starx.util;

import android.app.Activity;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * 安全浮窗工具 — 创建不可被录屏/截屏捕获的覆盖层
 *
 * 原理: 使用 TYPE_APPLICATION_PANEL + FLAG_SECURE，
 * 该窗口使用 Activity token 附加到当前窗口层级，
 * FLAG_SECURE 使其内容在 MediaProjection / 截屏时显示为黑色/透明。
 *
 * 适用场景:
 * - 视频快完成浮动按钮
 * - 考试搜题/答案提示浮窗
 *
 * 限制:
 * - 需要有效的 Activity (非 Service/Application上下文)
 * - 生命周期需调用者管理 (Activity 销毁前 remove)
 */
public class SecureOverlay {

    /**
     * 将 View 以安全浮窗形式添加到 Activity 上方
     *
     * @param activity  宿主 Activity
     * @param view      要显示的 View
     * @param gravity   对齐方式 (Gravity.TOP | Gravity.START 等)
     * @param x         水平偏移 (dp)
     * @param y         垂直偏移 (dp)
     * @return WindowManager.LayoutParams 用于后续更新/移除
     */
    public static WindowManager.LayoutParams addSecureView(
            Activity activity, View view, int gravity, int x, int y) {
        return addView(activity, view, gravity, x, y, true);
    }

    /**
     * 将 View 以普通应用内浮层形式添加到 Activity 上方。
     *
     * 用于非敏感状态提示。它不会使用 FLAG_SECURE，因此截图时不会出现安全窗口黑块。
     */
    public static WindowManager.LayoutParams addNormalView(
            Activity activity, View view, int gravity, int x, int y) {
        return addView(activity, view, gravity, x, y, false);
    }

    private static WindowManager.LayoutParams addView(
            Activity activity, View view, int gravity, int x, int y, boolean secure) {
        WindowManager wm = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        if (secure) {
            flags |= WindowManager.LayoutParams.FLAG_SECURE;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                flags,
                PixelFormat.TRANSLUCENT);

        // 对 Activity 子窗口而言，正确的 token 是 decorView 的 windowToken，
        // getAttributes().token 在某些 ROM / Android 14+ 上会返回无效 BinderProxy。
        android.os.IBinder token = null;
        try {
            View decor = activity.getWindow().getDecorView();
            token = decor.getWindowToken();
        } catch (Throwable ignored) {}
        if (token == null) {
            token = activity.getWindow().getAttributes().token;
        }
        params.token = token;
        params.gravity = gravity;
        params.x = x;
        params.y = y;

        try {
            wm.addView(view, params);
            Logx.i("SecureOverlay: added " + (secure ? "secure" : "normal")
                    + " view (gravity=" + gravity + ")");
            return params;
        } catch (Throwable t) {
            Logx.w("SecureOverlay: TYPE_APPLICATION_PANEL failed, fallback to decor child: " + t.getMessage());
            // 回退：把 view 作为 decorView 的子 View 挂上。安全浮层会顺带保护整个
            // Activity 窗口；普通浮层保持原样，避免非敏感提示变成截图黑块。
            try {
                android.view.Window w = activity.getWindow();
                if (secure) {
                    w.setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                            WindowManager.LayoutParams.FLAG_SECURE);
                }
                android.widget.FrameLayout.LayoutParams flp =
                        new android.widget.FrameLayout.LayoutParams(
                                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
                flp.gravity = gravity;
                flp.leftMargin = (gravity & Gravity.START) != 0 ? x : 0;
                flp.rightMargin = (gravity & Gravity.END) != 0 ? x : 0;
                flp.topMargin = (gravity & Gravity.TOP) != 0 ? y : 0;
                flp.bottomMargin = (gravity & Gravity.BOTTOM) != 0 ? y : 0;
                android.view.ViewGroup decor = (android.view.ViewGroup) w.getDecorView();
                decor.addView(view, flp);
                Logx.i("SecureOverlay: decor-child fallback attached"
                        + (secure ? " (Activity window forced FLAG_SECURE)" : ""));
            } catch (Throwable t2) {
                Logx.w("SecureOverlay: decor-child fallback failed: " + t2.getMessage());
            }
            return params;
        }
    }

    /**
     * 从窗口移除安全浮窗
     */
    public static void removeSecureView(Activity activity, View view) {
        // 先尝试作为 WindowManager 子窗口移除；若 view 其实挂在 decorView 上，
        // 则退而使用 parent.removeView。
        try {
            WindowManager wm = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
            wm.removeViewImmediate(view);
            return;
        } catch (Throwable ignored) {}
        try {
            android.view.ViewParent p = view.getParent();
            if (p instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) p).removeView(view);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 判断 Context 是否为 Activity
     */
    public static Activity asActivity(Context ctx) {
        if (ctx instanceof Activity) return (Activity) ctx;
        // ContextWrapper 链
        while (ctx instanceof android.content.ContextWrapper) {
            ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
            if (ctx instanceof Activity) return (Activity) ctx;
        }
        return null;
    }
}
