package org.xiyu.starx.util;

import android.util.Log;

import io.github.libxposed.api.XposedModule;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class Logx {
    private static final String TAG = "StarX";
    private static XposedModule sModule;

    // ── 文件落盘（用于无法持续监听 logcat 的场景） ──
    private static final Object FILE_LOCK = new Object();
    private static volatile File sLogFile;
    private static volatile boolean sFileInitTried;
    private static final long MAX_LOG_SIZE = 2L * 1024 * 1024; // 2MB 滚动
    private static final SimpleDateFormat TS_FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

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

    /**
     * 同 i()，但额外把消息写入宿主 App 文件目录下的日志文件。
     * 路径：/storage/emulated/0/Android/data/&lt;hostPkg&gt;/files/StarX/monitor.log
     * 失败回退到 internal filesDir/StarX/monitor.log。
     * 适合用户离线后回看监听记录（视频上报 enc / isPassed 历史等）。
     */
    public static void f(String msg) {
        i(msg);
        appendToFile(msg);
    }

    private static void appendToFile(String msg) {
        try {
            File file = ensureLogFile();
            if (file == null) return;
            synchronized (FILE_LOCK) {
                if (file.length() > MAX_LOG_SIZE) {
                    File bak = new File(file.getParentFile(), "monitor.log.1");
                    try {
                        if (bak.exists()) bak.delete();
                        file.renameTo(bak);
                    } catch (Throwable ignored) {}
                }
                try (Writer w = new OutputStreamWriter(
                        new FileOutputStream(file, true), "UTF-8")) {
                    w.write(TS_FMT.format(new Date()));
                    w.write(' ');
                    w.write(msg);
                    w.write('\n');
                }
            }
        } catch (Throwable ignored) {}
    }

    private static File ensureLogFile() {
        File f = sLogFile;
        if (f != null) return f;
        if (sFileInitTried) return null;
        synchronized (FILE_LOCK) {
            if (sLogFile != null) return sLogFile;
            if (sFileInitTried) return null;
            sFileInitTried = true;
            try {
                Class<?> atCls = Class.forName("android.app.ActivityThread");
                Object app = atCls.getMethod("currentApplication").invoke(null);
                if (app == null) return null;
                File baseDir = null;
                try {
                    File ext = (File) app.getClass()
                            .getMethod("getExternalFilesDir", String.class)
                            .invoke(app, (Object) null);
                    if (ext != null) baseDir = ext;
                } catch (Throwable ignored) {}
                if (baseDir == null) {
                    baseDir = (File) app.getClass().getMethod("getFilesDir").invoke(app);
                }
                if (baseDir == null) return null;
                File dir = new File(baseDir, "StarX");
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, "monitor.log");
                sLogFile = file;
                Log.i(TAG, "Logx: file logging at " + file.getAbsolutePath());
                return file;
            } catch (Throwable t) {
                Log.w(TAG, "Logx: file init failed: " + t.getMessage());
                return null;
            }
        }
    }
}
