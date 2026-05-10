package org.xiyu.starx.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 1.3.4 引入：用于在新装/升级后短期内开启冗余诊断日志，方便用户回传问题。
 *
 * 使用：
 *   DebugFlags.initOnInstall(ctx)  — 仅在 Application.onCreate 一次性调用，
 *                                    若从未设置则把 verboseUntil 设为 now + 7 天。
 *   DebugFlags.isVerbose(ctx)      — 各 Hook 在打 URL/HTML 头等冗余日志前调用。
 */
public final class DebugFlags {
    private static final String PREF_NAME = "starx_debug";
    private static final String KEY_VERBOSE_UNTIL = "verbose_until";
    private static final long DEFAULT_WINDOW_MS = 7L * 24 * 60 * 60 * 1000;

    private static volatile long sCachedUntil = -1L;

    private DebugFlags() {}

    public static void initOnInstall(Context ctx) {
        if (ctx == null) return;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            long until = sp.getLong(KEY_VERBOSE_UNTIL, 0L);
            if (until <= 0L) {
                until = System.currentTimeMillis() + DEFAULT_WINDOW_MS;
                sp.edit().putLong(KEY_VERBOSE_UNTIL, until).apply();
            }
            sCachedUntil = until;
        } catch (Throwable ignored) {}
    }

    public static boolean isVerbose(Context ctx) {
        long until = sCachedUntil;
        if (until <= 0L && ctx != null) {
            try {
                SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                until = sp.getLong(KEY_VERBOSE_UNTIL, 0L);
                sCachedUntil = until;
            } catch (Throwable ignored) {}
        }
        return until > 0L && System.currentTimeMillis() < until;
    }

    /** 无 ctx 版：通过 ActivityThread 反射获取当前 Application。 */
    public static boolean isVerbose() {
        if (sCachedUntil > 0L) return System.currentTimeMillis() < sCachedUntil;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) return isVerbose((Context) app);
        } catch (Throwable ignored) {}
        return false;
    }

    /** 强制启用 verbose 一段时间（用户主动诊断）。 */
    public static void extend(Context ctx, long extraMs) {
        if (ctx == null || extraMs <= 0) return;
        try {
            long until = Math.max(System.currentTimeMillis(), sCachedUntil) + extraMs;
            ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_VERBOSE_UNTIL, until).apply();
            sCachedUntil = until;
        } catch (Throwable ignored) {}
    }
}
