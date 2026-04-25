package org.xiyu.starx.util;

import android.content.Context;
import android.widget.Toast;

/**
 * Hook 目标应用内的提示统一带隐私标签，避免系统 Toast 和应用内提示口径不一致。
 */
public final class PrivateToast {
    private static final String LABEL = "[隐私] ";

    private PrivateToast() {}

    public static void show(Context context, String message, int duration) {
        if (context == null || message == null || message.trim().isEmpty()) {
            return;
        }
        Toast.makeText(context, withLabel(message.trim()), duration).show();
    }

    public static String withLabel(String message) {
        String text = message == null ? "" : message.trim();
        if (text.startsWith("[隐私]") || text.startsWith("【隐私】")) {
            return text;
        }
        return LABEL + text;
    }
}
