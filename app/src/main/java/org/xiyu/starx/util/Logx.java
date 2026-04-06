package org.xiyu.starx.util;

import android.util.Log;

import io.github.libxposed.api.XposedModule;

public final class Logx {
    private static final String TAG = "StarX";
    private static XposedModule sModule;

    private Logx() {}

    public static void init(XposedModule module) {
        sModule = module;
    }

    public static void i(String msg) {
        Log.i(TAG, msg);
        if (sModule != null) {
            try { sModule.log(Log.INFO, TAG, msg); } catch (Throwable ignored) {}
        }
    }

    public static void w(String msg) {
        Log.w(TAG, msg);
        if (sModule != null) {
            try { sModule.log(Log.WARN, TAG, msg); } catch (Throwable ignored) {}
        }
    }

    public static void e(String msg) {
        Log.e(TAG, msg);
        if (sModule != null) {
            try { sModule.log(Log.ERROR, TAG, msg); } catch (Throwable ignored) {}
        }
    }

    public static void e(String msg, Throwable t) {
        if (sModule != null) {
            sModule.log(Log.ERROR, TAG, msg, t);
        } else {
            Log.e(TAG, msg, t);
        }
    }
}
