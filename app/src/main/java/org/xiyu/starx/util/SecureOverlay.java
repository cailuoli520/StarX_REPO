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
        WindowManager wm = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT);

        params.token = activity.getWindow().getAttributes().token;
        params.gravity = gravity;
        params.x = x;
        params.y = y;

        // Android 12+ 额外标记: 排除出屏幕捕获
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                // API 34: Window.setContentSensitivity / CONTENT_SENSITIVITY_AUTO
                // 这里通过 FLAG_SECURE 已实现同等效果
            } catch (Throwable ignored) {}
        }

        wm.addView(view, params);
        Logx.i("SecureOverlay: added secure view (gravity=" + gravity + ")");
        return params;
    }

    /**
     * 从窗口移除安全浮窗
     */
    public static void removeSecureView(Activity activity, View view) {
        try {
            WindowManager wm = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
            wm.removeViewImmediate(view);
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
