package org.xiyu.starx.hook;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.xiyu.starx.util.ClassFinder;
import org.xiyu.starx.util.CxClasses;
import org.xiyu.starx.util.Logx;
import org.xiyu.starx.util.PrivateToast;
import org.xiyu.starx.util.SecureOverlay;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/**
 * 视频 Hook — 倍速解锁 + 任务点一键完成 + 浮动控制面板
 *
 * 核心发现:
 * - DotViewModel.b() 仅为分析打点，不影响任务完成状态
 * - 任务点完成由 CourseViewModel.S() 通过 reportUrl 上报控制
 * - enc 算法: MD5("[clazzId][puid][jobid][objectId][playSec*1000][d_yHJ!$pdA~5][dur*1000][clipTime]")
 */
public class VideoHook {
    private final XposedModule module;
    private final ClassLoader cl;

    private static final String COURSE_VM_CLASS =
            "com.chaoxing.mobile.player.course.viewmodel.CourseViewModel";
    private static final String COURSE_VIDEO_CLASS =
            "com.chaoxing.mobile.player.course.model.CourseVideo";
    private static final String COURSE_FRAGMENT_CLASS =
            "com.chaoxing.mobile.player.course.CoursePlayerFragment";
    private static final String ACCOUNT_MGR_CLASS =
            "com.chaoxing.study.account.AccountManager";

    // ── 混淆类候选名 (全版本通杀) ──
    private static final String[] HTTP_CLIENT_CANDIDATES = {"j9.f", "k9.f", "i9.f", "j9.g"};
    private static final String[] RETROFIT_HOLDER_CANDIDATES = {"de.s$a", "de.t$a", "de.r$a"};
    private static final String[] COURSE_EVENT_CANDIDATES = {
            "com.chaoxing.mobile.player.course.a", "com.chaoxing.mobile.player.course.b"
    };
    private static final String[] ACCOUNT_INSTANCE_CANDIDATES = {"E", "F", "D", "G"};
    private static final String[] ACCOUNT_GETACCOUNT_CANDIDATES = {"F", "G", "E", "H"};

    // ── 混淆方法名候选 ──
    /** HttpClientManager.getOkHttpClient() — 混淆名候选 */
    private static final String[] GET_CLIENT_METHOD_CANDIDATES = {"h", "g", "i", "f"};
    /** RetrofitClientHolder.noRedirectClient 字段 — 混淆名候选 */
    private static final String[] NO_REDIRECT_FIELD_CANDIDATES = {"f112762c", "f112763c", "c", "a"};
    /** CourseVideoEvent.setVideoJson() — 混淆方法名候选 */
    private static final String[] SET_VIDEO_JSON_CANDIDATES = {"d", "e", "c"};
    /** CourseVideoEvent.setCourseDotRes() — 混淆方法名候选 */
    private static final String[] SET_DOT_RES_CANDIDATES = {"c", "d", "b"};

    private final ClassFinder finder;

    private static final String ENC_SALT = "d_yHJ!$pdA~5";
    private static final String URL_FMT =
            "?otherInfo=%s&playingTime=%s&duration=%d&akid=null&jobid=%s"
                    + "&clipTime=%s&clazzId=%s&objectId=%s&userid=%s&isdrag=%d"
                    + "&enc=%s&rt=%s&dtype=Video&view=json";

    /** 已发送过快速完成的视频标识 (objectId_chapterId)，防止重复 */
    private final Set<String> completedVideos = Collections.synchronizedSet(new HashSet<>());

    // 诊断：最近一次真实 S() 调用的参数/this（用于无包装密钥时复用）
    private volatile Object[] lastSArgs;
    private volatile Object lastSVm;
    private volatile Method sMethodRef;
    // 服务端 {"isPassed":true} 出现过则认为真正完成；每次批量重置
    private volatile boolean lastBatchPassed;

    // ── 真实流量监听：捕获用户正常播放时 App 自身发出的进度/完成上报 ──
    /** 最近一次观察到的真实视频上报 URL（含 enc / clipTime / playingTime 等参数） */
    private volatile String lastObservedReportUrl;
    /** 最近一次观察到的真实 enc 值（来自 App 内部 MD5 计算后拼接的 URL） */
    private volatile String lastObservedEnc;
    /** 最近一次观察到 reportUrl 的时间戳（用于关联 ResponseBody） */
    private volatile long lastObservedReportAt;
    /** App 自身请求被服务端真实标记为完成（自然观看路径），用于校验 enc 算法是否仍然有效 */
    private volatile boolean lastNaturalPassObserved;
    /** 最近一次自然观察到 isPassed=true 的 objectId */
    private volatile String lastPassedObjectId;
    /** 监听日志去重：最近一次落盘的 enc */
    private volatile String lastLoggedEnc;
    /** 监听日志去重：最近一次落盘时间 */
    private volatile long lastLoggedAt;
    /** 监听日志去重窗口（毫秒）：相同 enc 在窗口内的重复 build() 不再写文件 */
    private static final long LOG_DEDUP_WINDOW_MS = 1000L;

    public VideoHook(XposedModule module, ClassLoader cl) {
        this.module = module;
        this.cl = cl;
        this.finder = new ClassFinder(cl);
    }

    public void hook() throws Throwable {
        hookSpeedList();
        hookSpeedRestriction();
        hookProgressReport();
        hookPlayerUI();
        hookReportTrafficMonitor();
        Logx.i("VideoHook: initialized");
    }

    // ──────────────────────────────────────────────────────────────
    //  1. 倍速列表扩展 — 追加 3x / 5x / 8x / 16x
    // ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void hookSpeedList() {
        Class<?> speedItemClass;
        Constructor<?> speedItemCtor;
        try {
            speedItemClass = Class.forName(CxClasses.SPEED_ITEM, false, cl);
            speedItemCtor = speedItemClass.getDeclaredConstructor(String.class, float.class);
            speedItemCtor.setAccessible(true);
        } catch (Throwable t) {
            Logx.w("VideoHook: SpeedItem class not found: " + t.getMessage());
            return;
        }

        try {
            Class<?> svpClass = Class.forName(CxClasses.STANDARD_VIDEO_PLAYER, false, cl);
            Method setSpeedData = svpClass.getDeclaredMethod("setSpeedData", List.class);
            final Constructor<?> ctor = speedItemCtor;
            module.hook(setSpeedData).intercept(chain -> {
                List<Object> list = (List<Object>) chain.getArg(0);
                List<Object> extended = new ArrayList<>(list);
                addExtraSpeeds(extended, ctor);
                return chain.proceed(new Object[]{extended});
            });
            Logx.i("VideoHook: hooked StandardVideoPlayer.setSpeedData");
        } catch (Throwable t) {
            Logx.w("VideoHook: StandardVideoPlayer hook failed: " + t.getMessage());
        }

        try {
            Class<?> csvClass = Class.forName(CxClasses.CX_SPEED_VIEW, false, cl);
            Method setSpeedList = csvClass.getDeclaredMethod("setSpeedList", List.class);
            final Constructor<?> ctor = speedItemCtor;
            module.hook(setSpeedList).intercept(chain -> {
                List<Object> list = (List<Object>) chain.getArg(0);
                List<Object> extended = new ArrayList<>(list);
                addExtraSpeeds(extended, ctor);
                return chain.proceed(new Object[]{extended});
            });
            Logx.i("VideoHook: hooked CXSpeedView.setSpeedList");
        } catch (Throwable t) {
            Logx.w("VideoHook: CXSpeedView hook failed: " + t.getMessage());
        }
    }

    private void addExtraSpeeds(List<Object> list, Constructor<?> ctor) {
        Set<Float> existing = collectExistingSpeeds(list);
        float[] extras = {3.0f, 5.0f, 8.0f, 16.0f};
        for (float speed : extras) {
            if (existing.contains(speed)) continue;
            try {
                list.add(ctor.newInstance(speed + "x", speed));
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 双策略提取已有倍速集合:
     * 1) 解析 String 标签（兼容 "1.5x" / "1.5X" / "2倍" / "2.0倍速"）
     * 2) 若标签解析无结果，回退到收集所有 float 字段值
     */
    private static Set<Float> collectExistingSpeeds(List<Object> list) {
        Set<Float> speeds = new HashSet<>();
        for (Object item : list) {
            try {
                boolean parsedFromLabel = false;
                for (Field f : item.getClass().getDeclaredFields()) {
                    if (f.getType() != String.class) continue;
                    f.setAccessible(true);
                    String label = (String) f.get(item);
                    Float parsed = parseSpeedLabel(label);
                    if (parsed != null) {
                        speeds.add(parsed);
                        parsedFromLabel = true;
                    }
                }
                if (!parsedFromLabel) {
                    for (Field f : item.getClass().getDeclaredFields()) {
                        if (f.getType() != float.class) continue;
                        f.setAccessible(true);
                        speeds.add(f.getFloat(item));
                    }
                }
            } catch (Throwable ignored) {}
        }
        return speeds;
    }

    private static Float parseSpeedLabel(String label) {
        if (label == null || label.isEmpty()) return null;
        String s = label.trim();
        if (s.endsWith("倍速")) s = s.substring(0, s.length() - 2);
        else if (s.endsWith("倍")) s = s.substring(0, s.length() - 1);
        else if (s.endsWith("x") || s.endsWith("X")) s = s.substring(0, s.length() - 1);
        try {
            float val = Float.parseFloat(s);
            return (val > 0 && val <= 100) ? val : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  2. 解除倍速限制
    //     CourseVideoPlayer.setCanSpeed(false) → 强制改为 true
    //     CourseVideoPlayer.h1() 进度回调中会在超过已观看进度时
    //     强制 1x + 禁倍速按钮，hook setCanSpeed 一劳永逸
    // ──────────────────────────────────────────────────────────────

    private void hookSpeedRestriction() {
        // 方案 A: Hook CourseVideoPlayer.setCanSpeed(boolean) — 最稳定
        try {
            Class<?> cvpClass = Class.forName(
                    "com.chaoxing.mobile.player.course.CourseVideoPlayer", false, cl);
            Method setCanSpeed = cvpClass.getMethod("setCanSpeed", boolean.class);
            module.hook(setCanSpeed).intercept(chain -> {
                boolean timeModEnabled = false;
                try {
                    var prefs = module.getRemotePreferences("config");
                    timeModEnabled = prefs.getBoolean("hook_video_time_enabled", false);
                } catch (Throwable ignored) {}
                if (timeModEnabled) {
                    // 无论原值是什么，强制 canSpeed = true
                    return chain.proceed(new Object[]{true});
                }
                return chain.proceed();
            });
            Logx.i("VideoHook: hooked CourseVideoPlayer.setCanSpeed → forced true");
        } catch (Throwable t) {
            Logx.w("VideoHook: setCanSpeed hook failed: " + t.getMessage());
        }

        // 方案 B: 同时 hook h1() 里的进度检查 — 防止播放中途被重置为 1x
        //   h1(int total, int current, int buffered) 中当 current >= lastMaxPlayed
        //   且 !canSpeed 时会 f0(1.0f, true) 强制 1x。
        //   直接 hook f0(float, boolean) 在启用时忽略强制 1x 调用。
        try {
            Class<?> absViewClass = Class.forName(
                    "com.chaoxing.videoplayer.base.ABSVideoView", false, cl);
            // d0(float, boolean) 或 f0(float, boolean) — setSpeed 最终调的
            Method speedSetter = null;
            for (Method m : absViewClass.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && p[0] == float.class && p[1] == boolean.class
                        && m.getReturnType() == void.class) {
                    speedSetter = m;
                    break;
                }
            }
            if (speedSetter != null) {
                module.hook(speedSetter).intercept(chain -> {
                    float newSpeed = (float) chain.getArg(0);
                    // 如果试图强制重置为 1x 且 timeModEnabled，拦截
                    if (newSpeed == 1.0f) {
                        try {
                            var prefs = module.getRemotePreferences("config");
                            if (prefs.getBoolean("hook_video_time_enabled", false)) {
                                // 检查是否来自强制重置 (boolean arg = true 代表 notify)
                                // 只在播放器当前速度 > 1 时阻止重置
                                Object player = chain.getThisObject();
                                Method getSpeed = player.getClass().getMethod("getSpeed");
                                float curSpeed = (float) getSpeed.invoke(player);
                                if (curSpeed > 1.0f) {
                                    Logx.i("VideoHook: blocked speed reset 1x (cur=" + curSpeed + ")");
                                    return null;
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                    return chain.proceed();
                });
                Logx.i("VideoHook: hooked speed setter to prevent forced 1x reset");
            }
        } catch (Throwable t) {
            Logx.w("VideoHook: speed setter hook failed: " + t.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  3. 进度上报 Hook — 在 CourseViewModel.S() 中自动完成
    //     当 "时长修改" 开启时, 首次 TYPE_PLAYING 触发后台批量上报
    // ──────────────────────────────────────────────────────────────

    private void hookProgressReport() {
        try {
            Class<?> courseVmClass = Class.forName(COURSE_VM_CLASS, false, cl);
            Class<?> courseVideoClass = Class.forName(COURSE_VIDEO_CLASS, false, cl);

            // 定位 S() 方法: (Context, int, int, int, String, LifecycleOwner, ?) → LiveData
            Method sMethod = findProgressMethod(courseVmClass);
            if (sMethod == null) {
                Logx.w("VideoHook: CourseViewModel.S() not found");
                return;
            }

            // 定位 u() 方法: 返回 CourseVideo, 无参数
            Method getVideoMethod = findGetVideoMethod(courseVmClass, courseVideoClass);
            sMethodRef = sMethod;
            sMethod.setAccessible(true);

            module.hook(sMethod).intercept(chain -> {
                // 诊断：输出所有参数，确认 S() 的参数结构
                try {
                    java.util.List<Object> a = chain.getArgs();
                    StringBuilder sb = new StringBuilder("VideoHook[S-call] ");
                    for (int i = 0; i < a.size(); i++) {
                        Object v = a.get(i);
                        sb.append("#").append(i).append("=")
                          .append(v == null ? "null" : (v.getClass().getSimpleName() + ":" + String.valueOf(v).replaceAll("\\s+", " ")))
                          .append("  ");
                    }
                    Logx.i(sb.toString());
                    // 捕获最近一次真实 S() 调用上下文（给手动完成复用）
                    lastSArgs = a.toArray();
                    lastSVm = chain.getThisObject();
                } catch (Throwable ignored) {}
                boolean timeModEnabled = false;
                try {
                    var prefs = module.getRemotePreferences("config");
                    timeModEnabled = prefs.getBoolean("hook_video_time_enabled", false);
                } catch (Throwable ignored) {}

                if (!timeModEnabled) return chain.proceed();

                int isdrag = (int) chain.getArg(2);

                // TYPE_PLAYING(0) 或 TYPE_COMPLETE(4) 时执行快速完成
                if ((isdrag == 0 || isdrag == 4) && getVideoMethod != null) {
                    try {
                        Object vm = chain.getThisObject();
                        Object courseVideo = getVideoMethod.invoke(vm);
                        if (courseVideo != null) {
                            String videoKey = getVideoKey(courseVideo, courseVideoClass);

                            // ★ 已完成的视频: 让后续定时器调用正常通过, 不再修改
                            if (completedVideos.contains(videoKey)) {
                                return chain.proceed();
                            }

                            completedVideos.add(videoKey);

                            // ★ 仅通过后台批量上报完成视频，不修改当前 S() 调用参数
                            // 修改 S() 参数会导致 app 自身也发 COMPLETE 报告，与批量上报冲突
                            // 批量上报完成后再通过 EventBus 更新章节页 UI
                            fireBatchComplete(courseVideo, courseVideoClass, () -> {
                                new Handler(Looper.getMainLooper()).post(() ->
                                        postCompletionEvent(null));
                            }, videoKey);
                            Logx.i("VideoHook: batch complete initiated for " + videoKey);
                        }
                    } catch (Throwable t) {
                        Logx.w("VideoHook: progress override failed: " + t.getMessage());
                    }
                }
                return chain.proceed();
            });

            Logx.i("VideoHook: hooked CourseViewModel progress");
        } catch (Throwable t) {
            Logx.w("VideoHook: progress hook failed: " + t.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  4. 浮动控制面板 — 一键完成 + 快速倍速
    // ──────────────────────────────────────────────────────────────

    private void hookPlayerUI() {
        try {
            Class<?> fragmentClass = Class.forName(COURSE_FRAGMENT_CLASS, false, cl);
            Class<?> courseVmClass = Class.forName(COURSE_VM_CLASS, false, cl);
            Class<?> courseVideoClass = Class.forName(COURSE_VIDEO_CLASS, false, cl);

            // Hook onActivityCreated — 此时 player 和 viewModel 均已就绪
            Method onActivityCreated = fragmentClass.getMethod(
                    "onActivityCreated", Bundle.class);

            // 通过类型定位关键字段
            Field viewModelField = findFieldByType(fragmentClass, courseVmClass);
            Field playerField = findPlayerField(fragmentClass);

            if (viewModelField != null) viewModelField.setAccessible(true);
            if (playerField != null) playerField.setAccessible(true);

            Method getVideoMethod = findGetVideoMethod(courseVmClass, courseVideoClass);

            final Field fVm = viewModelField;
            final Field fPlayer = playerField;
            final Method fGetVideo = getVideoMethod;

            module.hook(onActivityCreated).intercept(chain -> {
                Object result = chain.proceed();

                boolean timeModEnabled = false;
                try {
                    var prefs = module.getRemotePreferences("config");
                    timeModEnabled = prefs.getBoolean("hook_video_time_enabled", false);
                } catch (Throwable ignored) {}

                if (!timeModEnabled) return result;

                try {
                    Object fragment = chain.getThisObject();
                    // 使用 Fragment.getView() 获取根视图
                    Method getView = fragment.getClass().getMethod("getView");
                    View rootView = (View) getView.invoke(fragment);
                    if (rootView != null) {
                        rootView.post(() -> injectControlPanel(
                                fragment, rootView, fVm, fPlayer,
                                courseVmClass, courseVideoClass, fGetVideo));
                    }
                } catch (Throwable t) {
                    Logx.w("VideoHook: UI injection failed: " + t.getMessage());
                }
                return result;
            });

            Logx.i("VideoHook: player UI hook ready");
        } catch (Throwable t) {
            Logx.w("VideoHook: player UI hook failed: " + t.getMessage());
        }
    }

    // ---------- UI 注入 ----------

    private static final int PANEL_TAG_KEY = 0x7F0CAFE1;

    private void injectControlPanel(Object fragment, View rootView,
                                    Field vmField, Field playerField,
                                    Class<?> courseVmClass, Class<?> courseVideoClass,
                                    Method getVideoMethod) {
        try {
            Context ctx = rootView.getContext();

            // 外层面板 ─ 半透明圆角背景
            LinearLayout panel = new LinearLayout(ctx);
            panel.setOrientation(LinearLayout.HORIZONTAL);
            panel.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable panelBg = new GradientDrawable();
            panelBg.setColor(Color.argb(160, 0, 0, 0));
            panelBg.setCornerRadius(dp(ctx, 20));
            panel.setBackground(panelBg);
            int hPad = dp(ctx, 10);
            int vPad = dp(ctx, 5);
            panel.setPadding(hPad, vPad, hPad, vPad);

            // 「一键完成」按钮
            TextView completeBtn = makeButton(ctx, "完成", Color.argb(200, 76, 175, 80));
            completeBtn.setOnClickListener(v ->
                    onCompleteClick(fragment, vmField, courseVmClass,
                            courseVideoClass, getVideoMethod, ctx));
            panel.addView(completeBtn);

            // 倍速快捷按钮
            float[] speeds = {2f, 4f, 8f, 16f};
            for (float speed : speeds) {
                TextView btn = makeButton(ctx, (int) speed + "x",
                        Color.argb(200, 63, 81, 181));
                btn.setOnClickListener(v ->
                        onSpeedClick(fragment, playerField, speed, ctx));
                panel.addView(btn);
            }

            // 添加到安全浮窗 (FLAG_SECURE — 不可被录屏/截屏捕获)
            try {
                Activity activity = SecureOverlay.asActivity(rootView.getContext());
                if (activity == null) throw new RuntimeException("not an Activity context");

                ViewGroup decorView = (ViewGroup) activity.getWindow().getDecorView();
                // 防止重复注入 (tag on decorView)
                if (decorView.getTag(PANEL_TAG_KEY) != null) {
                    Logx.i("VideoHook: panel already injected on decor");
                    return;
                }

                panel.setClickable(true);
                panel.setFocusable(true);
                SecureOverlay.addSecureView(activity, panel,
                        Gravity.TOP | Gravity.START, dp(ctx, 48), dp(ctx, 8));
                decorView.setTag(PANEL_TAG_KEY, panel);
                Logx.i("VideoHook: control panel injected via SecureOverlay");
            } catch (Throwable t2) {
                // 回退: 添加到 Fragment 根视图 (无防录屏)
                Logx.w("VideoHook: SecureOverlay failed (" + t2.getMessage()
                        + "), falling back to DecorView");
                try {
                    Activity activity = (Activity) rootView.getContext();
                    ViewGroup decorView = (ViewGroup) activity.getWindow().getDecorView();
                    if (decorView.getTag(PANEL_TAG_KEY) != null) return;
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT);
                    lp.gravity = Gravity.TOP | Gravity.START;
                    lp.topMargin = dp(ctx, 8);
                    lp.leftMargin = dp(ctx, 48);
                    panel.setClickable(true);
                    panel.setFocusable(true);
                    decorView.addView(panel, lp);
                    decorView.setTag(PANEL_TAG_KEY, panel);
                } catch (Throwable t3) {
                    if (rootView instanceof ViewGroup) {
                        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.WRAP_CONTENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT);
                        lp.gravity = Gravity.TOP | Gravity.START;
                        lp.topMargin = dp(ctx, 8);
                        lp.leftMargin = dp(ctx, 48);
                        ((ViewGroup) rootView).addView(panel, lp);
                        rootView.setTag(PANEL_TAG_KEY, panel);
                        Logx.i("VideoHook: control panel injected (fallback)");
                    }
                }
            }
        } catch (Throwable t) {
            Logx.w("VideoHook: panel creation failed: " + t.getMessage());
        }
    }

    private static TextView makeButton(Context ctx, String text, int bgColor) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTypeface(null, Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(ctx, 12));
        tv.setBackground(bg);
        tv.setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4));
        tv.setGravity(Gravity.CENTER);
        tv.setClickable(true);
        tv.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(ctx, 3), 0, dp(ctx, 3), 0);
        tv.setLayoutParams(lp);
        return tv;
    }

    // ---------- 一键完成 ----------

    private void onCompleteClick(Object fragment, Field vmField,
                                 Class<?> courseVmClass, Class<?> courseVideoClass,
                                 Method getVideoMethod, Context ctx) {
        try {
            if (vmField == null || getVideoMethod == null) {
                PrivateToast.show(ctx, "视频数据不可用", Toast.LENGTH_SHORT);
                return;
            }
            Object vm = vmField.get(fragment);
            if (vm == null) return;
            Object courseVideo = getVideoMethod.invoke(vm);
            if (courseVideo == null) {
                PrivateToast.show(ctx, "当前无视频任务", Toast.LENGTH_SHORT);
                return;
            }

            String videoKey = getVideoKey(courseVideo, courseVideoClass);
            if (completedVideos.contains(videoKey)) {
                PrivateToast.show(ctx, "已提交过完成请求", Toast.LENGTH_SHORT);
                return;
            }
            completedVideos.add(videoKey);

            // 获取 fragment 上的 videoJson 字符串 (CoursePlayerData, 用于 EventBus)
            String videoJson = null;
            try {
                Class<?> clz = fragment.getClass();
                while (clz != null && clz != Object.class && videoJson == null) {
                    for (Field f : clz.getDeclaredFields()) {
                        if (f.getType() == String.class) {
                            f.setAccessible(true);
                            String val = (String) f.get(fragment);
                            if (val != null && val.contains("reportUrl") && val.contains("objectId")) {
                                videoJson = val;
                                Logx.i("VideoHook: found videoJson in " + clz.getSimpleName()
                                        + "." + f.getName() + " (len=" + val.length() + ")");
                                break;
                            }
                        }
                    }
                    clz = clz.getSuperclass();
                }
            } catch (Throwable ex) {
                Logx.w("VideoHook: videoJson scan error: " + ex.getMessage());
            }
            if (videoJson == null) {
                Logx.w("VideoHook: videoJson not found on fragment, EventBus event will be partial");
            }

            final String fVideoJson = videoJson;

            PrivateToast.show(ctx, "正在提交...", Toast.LENGTH_SHORT);
            fireBatchComplete(courseVideo, courseVideoClass, () -> {
                new Handler(Looper.getMainLooper()).post(() -> {
                    PrivateToast.show(ctx, "✓ 视频任务已完成（服务端已接受）", Toast.LENGTH_SHORT);
                    // ★ 通过 EventBus 通知章节页面更新任务点状态
                    postCompletionEvent(fVideoJson);
                });
            }, videoKey);
            // 如果 onSuccess 未触发（服务端 isPassed=false），2 分钟内给用户一个反馈
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!lastBatchPassed) {
                    PrivateToast.show(ctx,
                            "⚠ 服务端未接受（签名过期，换设备会同步失败）",
                            Toast.LENGTH_LONG);
                }
            }, 15_000);
        } catch (Throwable t) {
            Logx.w("VideoHook: complete click failed: " + t.getMessage());
            PrivateToast.show(ctx, "操作失败: " + t.getMessage(), Toast.LENGTH_SHORT);
        }
    }

    // ---------- 快速倍速 ----------

    private void onSpeedClick(Object fragment, Field playerField,
                              float speed, Context ctx) {
        try {
            if (playerField == null) return;
            Object player = playerField.get(fragment);
            if (player == null) return;
            // ABSVideoView.setSpeed(float)
            Method setSpeed = player.getClass().getMethod("setSpeed", float.class);
            setSpeed.invoke(player, speed);
            PrivateToast.show(ctx, speed + "x", Toast.LENGTH_SHORT);
        } catch (Throwable t) {
            Logx.w("VideoHook: setSpeed failed: " + t.getMessage());
        }
    }

    // ---------- EventBus 完成通知 ----------

    /**
     * 通过 EventBus 发送 CourseVideoEvent，通知章节页面将任务点从黄色→绿色。
     *
     * 事件类: CourseVideoEvent (com.chaoxing.mobile.player.course.a)
     *   videoJson     字段 f68878a (via setVideoJson/d()) = CoursePlayerData JSON
     *   courseDotRes  字段 f68879b (via setCourseDotRes/c()) = CourseDotRes JSON
     *
     * ChapterCardFragment 订阅此事件后调用:
     *   javascript:proxy_completed(videoJson, courseDotResJson)
     */
    private void postCompletionEvent(String videoJson) {
        try {
            // 获取 EventBus
            Class<?> eventBusClass = Class.forName("org.greenrobot.eventbus.EventBus", false, cl);
            Object eventBus = eventBusClass.getMethod("getDefault").invoke(null);

            // 创建 CourseVideoEvent 事件对象
            Class<?> eventClass = finder.resolve(
                    CxClasses.COURSE_VIDEO_EVENT, COURSE_EVENT_CANDIDATES);
            if (eventClass == null) {
                Logx.w("VideoHook: CourseVideoEvent class not found");
                return;
            }
            Object event = eventClass.getDeclaredConstructor().newInstance();

            // setVideoJson — 设置 CoursePlayerData JSON
            if (videoJson != null) {
                boolean set = false;
                for (String mName : SET_VIDEO_JSON_CANDIDATES) {
                    try {
                        Method setVideoJson = eventClass.getDeclaredMethod(mName, String.class);
                        setVideoJson.invoke(event, videoJson);
                        set = true;
                        break;
                    } catch (NoSuchMethodException ignored) {}
                }
                if (!set) {
                    // 回退: 直接写第一个 String 字段
                    Field f = eventClass.getDeclaredFields()[0];
                    f.setAccessible(true);
                    f.set(event, videoJson);
                }
            }

            // setCourseDotRes — 设置任务点结果 JSON (result=1)
            String dotResJson = "{\"result\":1,\"isPassed\":true}";
            boolean dotSet = false;
            for (String mName : SET_DOT_RES_CANDIDATES) {
                try {
                    Method setCourseDotRes = eventClass.getDeclaredMethod(mName, String.class);
                    setCourseDotRes.invoke(event, dotResJson);
                    dotSet = true;
                    break;
                } catch (NoSuchMethodException ignored) {}
            }
            if (!dotSet) {
                // 回退: 直接写第二个字段
                if (eventClass.getDeclaredFields().length > 1) {
                    Field f = eventClass.getDeclaredFields()[1];
                    f.setAccessible(true);
                    f.set(event, dotResJson);
                }
            }

            // 发送事件
            eventBusClass.getMethod("post", Object.class).invoke(eventBus, event);
            Logx.i("VideoHook: posted CourseVideoEvent via EventBus");
        } catch (Throwable t) {
            Logx.w("VideoHook: EventBus post failed: " + t.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  批量进度上报 — 模拟完整观看过程
    // ──────────────────────────────────────────────────────────────

    private void fireBatchComplete(Object courseVideo, Class<?> cvClass, String videoKey) {
        fireBatchComplete(courseVideo, cvClass, null, videoKey);
    }

    private void fireBatchComplete(Object courseVideo, Class<?> cvClass,
                                   Runnable onSuccess, String videoKey) {
        new Thread(() -> {
            lastBatchPassed = false;
            try {
                String reportUrl = str(cvClass, courseVideo, "getReportUrl");
                if (reportUrl == null || reportUrl.isEmpty()) {
                    if (videoKey != null) completedVideos.remove(videoKey);
                    Logx.w("VideoHook: reportUrl is empty, skip batch");
                    return;
                }
                String otherInfo = str(cvClass, courseVideo, "getOtherInfo");
                String jobid = str(cvClass, courseVideo, "getJobid");
                String clazzId = str(cvClass, courseVideo, "getClazzId");
                String objectId = str(cvClass, courseVideo, "getObjectid");
                int durationSec = (int) cvClass.getMethod("getDuration").invoke(courseVideo);
                double rt = (double) cvClass.getMethod("getRt").invoke(courseVideo);
                int vbegin = (int) cvClass.getMethod("getVbegin").invoke(courseVideo);
                int vend = (int) cvClass.getMethod("getVend").invoke(courseVideo);
                String clipTime = (vbegin < 0 || vend <= 0)
                        ? ("0_" + durationSec) : (vbegin + "_" + vend);

                String puid = getPuid();

                // ★ DEBUG: 输出所有关键参数
                Logx.i("VideoHook[DBG] reportUrl=" + reportUrl);
                Logx.i("VideoHook[DBG] jobid=" + jobid + " clazzId=" + clazzId
                        + " objectId=" + objectId + " puid=" + puid);
                Logx.i("VideoHook[DBG] duration=" + durationSec + " rt=" + rt
                        + " clipTime=" + clipTime + " otherInfo=" + otherInfo);

                // ── 方案 B：高 rt 突破（瞬时完成尝试）──
                //   v6.7+ 服务端按 rt 校验播放速率：playingDelta ≤ rt × realDelta
                //   先把 rt 拉到极高（8.0），赌服务端按上报值放行，可在 ~3 秒内通过。
                //   一旦 ② 拿到 isPassed=true 直接结束；失败则进入方案 A 慢速节奏上报。
                int passSec = Math.max(1, (int) Math.round(durationSec * 0.45));
                double boostRt = Math.max(rt, 8.0);

                sendReport(reportUrl, otherInfo, "0", durationSec, jobid,
                        clipTime, clazzId, objectId, puid, 3, boostRt, 0);
                Thread.sleep(1500);

                sendReport(reportUrl, otherInfo, String.valueOf(passSec), durationSec,
                        jobid, clipTime, clazzId, objectId, puid, 0, boostRt, passSec);
                Thread.sleep(2500);

                if (lastBatchPassed) {
                    Logx.i("VideoHook: [PhaseB] passed via boostRt=" + boostRt
                            + " at " + passSec + "s");
                    sendReport(reportUrl, otherInfo, String.valueOf(durationSec), durationSec,
                            jobid, clipTime, clazzId, objectId, puid, 4, boostRt, durationSec);
                } else {
                    // ── 方案 A：慢速节奏上报（保证通过率，但耗时 ≈ 视频时长 × 0.55）──
                    //   每 tick 真实间隔 5s，playingTime 推进 min(rt,1.0) × 5 ≈ 4-5s（保守 4s）
                    //   达到 45% 阈值后等服务端确认 isPassed；不通过则继续推进直到结束。
                    Logx.w("VideoHook: [PhaseB] failed, falling back to [PhaseA] slow-tick");
                    int realStep = 5; // 每次真实 sleep 5s
                    int playStep = Math.max(1, (int) Math.floor(Math.min(rt, 1.0) * realStep));
                    int pt = 0;
                    while (pt < durationSec && !lastBatchPassed) {
                        pt = Math.min(durationSec, pt + playStep);
                        sendReport(reportUrl, otherInfo, String.valueOf(pt), durationSec,
                                jobid, clipTime, clazzId, objectId, puid, 0, rt, pt);
                        if (pt >= durationSec) break;
                        Thread.sleep(realStep * 1000L);
                    }
                    // TYPE_COMPLETE
                    sendReport(reportUrl, otherInfo, String.valueOf(durationSec), durationSec,
                            jobid, clipTime, clazzId, objectId, puid, 4, rt, durationSec);
                }

                Logx.i("VideoHook: batch complete sent (d=" + durationSec + "s), serverPassed=" + lastBatchPassed);
                if (!lastBatchPassed) {
                    // 服务端未接受 → 回退本地状态，让用户看到真实失败
                    if (videoKey != null) completedVideos.remove(videoKey);
                    Logx.w("VideoHook: server rejected (isPassed=false, likely enc/签名过期 for v6.7.4+), need to keep watching naturally");
                }
                if (onSuccess != null && lastBatchPassed) onSuccess.run();
            } catch (Throwable t) {
                if (videoKey != null) completedVideos.remove(videoKey);
                Logx.w("VideoHook: batch complete failed: " + t.getMessage());
            }
        }, "StarX-BatchComplete").start();
    }

    /**
     * 获取应用全局 OkHttpClient
     * 首选: HttpClientManager.getOkHttpClient() — 已配置 CookieJar / User-Agent 拦截器
     * 备选: RetrofitClientHolder.noRedirectClient — 进度上报专用客户端
     */
    private Object getAppOkHttpClient() {
        // 首选: HttpClientManager.getOkHttpClient() — 使用候选名尝试
        Class<?> cls = finder.resolve(CxClasses.HTTP_CLIENT_MANAGER, HTTP_CLIENT_CANDIDATES);
        if (cls != null) {
            for (String methodName : GET_CLIENT_METHOD_CANDIDATES) {
                try {
                    return cls.getDeclaredMethod(methodName).invoke(null);
                } catch (Throwable ignored) {}
            }
            Logx.w("VideoHook: HttpClientManager found but no getter method matched");
        }
        // 备选: RetrofitClientHolder.noRedirectClient
        Class<?> retCls = finder.resolve(CxClasses.RETROFIT_CLIENT_HOLDER, RETROFIT_HOLDER_CANDIDATES);
        if (retCls != null) {
            for (String fieldName : NO_REDIRECT_FIELD_CANDIDATES) {
                try {
                    Field f = retCls.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    return f.get(null);
                } catch (Throwable ignored) {}
            }
            Logx.w("VideoHook: RetrofitClientHolder found but no field matched");
        }
        return null;
    }

    private void sendReport(String reportUrl, String otherInfo, String playingTime,
                            int durationSec, String jobid, String clipTime,
                            String clazzId, String objectId, String puid,
                            int isdrag, double rt, int playingSeconds) {
        try {
            String enc = calcEnc(clazzId, puid, jobid, objectId,
                    playingSeconds, durationSec, clipTime);
            Logx.i("VideoHook[DBG] enc input: [" + s(clazzId) + "][" + s(puid) + "]["
                    + (jobid != null ? jobid : "") + "][" + s(objectId) + "]["
                    + ((long) playingSeconds * 1000) + "][" + ENC_SALT + "]["
                    + ((long) durationSec * 1000) + "][" + s(clipTime) + "]");
            Logx.i("VideoHook[DBG] enc=" + enc);
            String query = String.format(URL_FMT,
                    otherInfo != null ? otherInfo : "",
                    playingTime,
                    durationSec,
                    jobid != null ? jobid : "",
                    clipTime,
                    clazzId != null ? clazzId : "",
                    objectId != null ? objectId : "",
                    puid != null ? puid : "",
                    isdrag,
                    enc,
                    String.valueOf(rt));
            String fullUrl = reportUrl + query;

            // 使用应用自身的 OkHttpClient (已配置 CookieJar + 拦截器)
            Object client = getAppOkHttpClient();
            if (client != null) {
                int code = executeOkHttpGet(client, fullUrl);
                Logx.i("VideoHook: report sent (isdrag=" + isdrag
                        + ",pt=" + playingTime + ") → " + code);
            } else {
                Logx.w("VideoHook: no OkHttpClient available, skip report");
            }
        } catch (Throwable t) {
            Logx.w("VideoHook: sendReport failed: " + t.getMessage());
        }
    }

    /** 通过反射调用 OkHttpClient.newCall(Request).execute() */
    private int executeOkHttpGet(Object client, String url) throws Exception {
        // okhttp3.Request.Builder().url(url).build()
        Class<?> reqBuilderClass = Class.forName("okhttp3.Request$Builder", false, cl);
        Object builder = reqBuilderClass.getDeclaredConstructor().newInstance();
        builder = reqBuilderClass.getMethod("url", String.class).invoke(builder, url);
        Object request = reqBuilderClass.getMethod("build").invoke(builder);

        // client.newCall(request).execute()
        Class<?> reqClass = Class.forName("okhttp3.Request", false, cl);
        Object call = client.getClass().getMethod("newCall", reqClass).invoke(client, request);
        Object response = call.getClass().getMethod("execute").invoke(call);

        int code = (int) response.getClass().getMethod("code").invoke(response);

        // 读取 + 关闭 response body
        try {
            Object body = response.getClass().getMethod("body").invoke(response);
            if (body != null) {
                String bodyStr = (String) body.getClass().getMethod("string").invoke(body);
                if (bodyStr != null && bodyStr.length() > 0) {
                    String truncated = bodyStr.length() > 200
                            ? bodyStr.substring(0, 200) : bodyStr;
                    Logx.i("VideoHook: response body: " + truncated);
                    if (bodyStr.contains("\"isPassed\":true") || bodyStr.contains("\"isPassed\": true")) {
                        lastBatchPassed = true;
                    }
                }
            }
        } catch (Throwable ignored) {}

        return code;
    }

    // ──────────────────────────────────────────────────────────────
    //  工具方法
    // ──────────────────────────────────────────────────────────────

    /** 定位 CourseViewModel 的进度上报方法 (Context, int, int, int, String, LifecycleOwner, ?) */
    private Method findProgressMethod(Class<?> courseVmClass) {
        for (Method m : courseVmClass.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 7
                    && p[0] == Context.class
                    && p[1] == int.class && p[2] == int.class && p[3] == int.class
                    && p[4] == String.class) {
                return m;
            }
        }
        return null;
    }

    /** 定位 CourseViewModel.u(): 返回 CourseVideo, 无参数 */
    private Method findGetVideoMethod(Class<?> vmClass, Class<?> videoClass) {
        for (Method m : vmClass.getDeclaredMethods()) {
            if (m.getReturnType() == videoClass && m.getParameterTypes().length == 0) {
                return m;
            }
        }
        return null;
    }

    /** 通过类型定位字段 */
    private Field findFieldByType(Class<?> ownerClass, Class<?> fieldType) {
        for (Field f : ownerClass.getDeclaredFields()) {
            if (f.getType() == fieldType) return f;
        }
        return null;
    }

    /** 定位 CourseVideoPlayer 字段 (名称含 "VideoPlayer") */
    private Field findPlayerField(Class<?> fragmentClass) {
        for (Field f : fragmentClass.getDeclaredFields()) {
            if (f.getType().getName().contains("VideoPlayer")) return f;
        }
        return null;
    }

    /** 重置 S() 的去重字段，确保调用不被丢弃 */
    private void resetDedup(Object vm, Class<?> vmClass) {
        try {
            List<Field> intFields = new ArrayList<>();
            for (Field f : vmClass.getDeclaredFields()) {
                if (f.getType() == int.class && !Modifier.isStatic(f.getModifiers())) {
                    intFields.add(f);
                }
            }
            // 去重字段是最后两个非静态 int 字段
            if (intFields.size() >= 2) {
                Field fSec = intFields.get(intFields.size() - 2);
                Field fType = intFields.get(intFields.size() - 1);
                fSec.setAccessible(true);
                fType.setAccessible(true);
                fSec.set(vm, -1);
                fType.set(vm, -1);
            }
        } catch (Throwable t) {
            Logx.w("VideoHook: resetDedup failed: " + t.getMessage());
        }
    }

    /** 获取视频唯一标识 */
    private String getVideoKey(Object courseVideo, Class<?> cvClass) {
        try {
            String oid = str(cvClass, courseVideo, "getObjectid");
            String kid = str(cvClass, courseVideo, "getKnowledgeId");
            return oid + "_" + kid;
        } catch (Throwable t) {
            return String.valueOf(System.identityHashCode(courseVideo));
        }
    }

    /** 获取用户 puid: AccountManager.getInstance().getAccount().getPuid() */
    private String getPuid() {
        try {
            Class<?> amClass = Class.forName(ACCOUNT_MGR_CLASS, false, cl);
            // AccountManager.getInstance() — 尝试候选混淆名
            Object mgr = null;
            String instanceMethod = CxClasses.ACCOUNT_GET_INSTANCE_METHOD;
            if (instanceMethod != null && !instanceMethod.isEmpty()) {
                try { mgr = amClass.getMethod(instanceMethod).invoke(null); } catch (Throwable ignored) {}
            }
            if (mgr == null) {
                for (String m : ACCOUNT_INSTANCE_CANDIDATES) {
                    try { mgr = amClass.getMethod(m).invoke(null); break; } catch (Throwable ignored) {}
                }
            }
            if (mgr == null) return "";
            // AccountManager.getAccount() — 尝试候选混淆名
            Object account = null;
            String accountMethod = CxClasses.ACCOUNT_GET_ACCOUNT_METHOD;
            if (accountMethod != null && !accountMethod.isEmpty()) {
                try { account = mgr.getClass().getMethod(accountMethod).invoke(mgr); } catch (Throwable ignored) {}
            }
            if (account == null) {
                for (String m : ACCOUNT_GETACCOUNT_CANDIDATES) {
                    try { account = mgr.getClass().getMethod(m).invoke(mgr); break; } catch (Throwable ignored) {}
                }
            }
            if (account == null) return "";
            String puid = (String) account.getClass().getMethod("getPuid").invoke(account);
            return puid != null ? puid : "";
        } catch (Throwable t) {
            Logx.w("VideoHook: getPuid failed: " + t.getMessage());
            return "";
        }
    }

    /** 反射读取 String getter */
    private static String str(Class<?> clz, Object obj, String method) throws Exception {
        return (String) clz.getMethod(method).invoke(obj);
    }

    // ---------- enc 计算 ----------

    static String calcEnc(String clazzId, String puid, String jobid,
                          String objectId, int playingSeconds, int durationSec,
                          String clipTime) {
        String raw = "[" + s(clazzId) + "][" + s(puid) + "]["
                + (jobid != null ? jobid : "") + "][" + s(objectId) + "]["
                + ((long) playingSeconds * 1000) + "][" + ENC_SALT + "]["
                + ((long) durationSec * 1000) + "][" + s(clipTime) + "]";
        return md5(raw);
    }

    private static String s(String v) { return v != null ? v : ""; }

    static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static int dp(Context ctx, float dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp,
                ctx.getResources().getDisplayMetrics());
    }

    // ──────────────────────────────────────────────────────────────
    //  真实视频上报流量监听
    //  目的：用户正常观看视频时，App 自身会向 /multimedia/log 等接口发出进度/完成
    //       上报。Hook OkHttp 的 Request.Builder.build() 与 ResponseBody.string()，
    //       在不改写任何参数的前提下：
    //        ① 抓出 App 自己计算好的真实 enc / clipTime / 各参数；
    //        ② 监听服务端响应中的 isPassed 值，确认本机当前 enc 算法是否仍然被认可。
    //       这样：
    //        - 一键完成（fireBatchComplete）若复用相同 enc 算法，可把"自然观看 isPassed=true"
    //          作为 enc 算法仍生效的实时探针，并把 lastBatchPassed 标记为通过；
    //        - 若服务端升级了签名规则，监听日志会立即可见 isPassed=false，便于诊断。
    // ──────────────────────────────────────────────────────────────
    private void hookReportTrafficMonitor() {
        try {
            Class<?> reqBuilderClass = Class.forName("okhttp3.Request$Builder", false, cl);
            Class<?> reqClass = Class.forName("okhttp3.Request", false, cl);
            Method buildMethod = reqBuilderClass.getDeclaredMethod("build");
            Method requestUrlMethod = reqClass.getDeclaredMethod("url");

            module.hook(buildMethod).intercept(chain -> {
                Object request = chain.proceed();
                try {
                    if (request != null) {
                        Object httpUrl = requestUrlMethod.invoke(request);
                        String url = httpUrl != null ? httpUrl.toString() : null;
                        if (url != null && isVideoReportUrl(url)) {
                            long now = System.currentTimeMillis();
                            lastObservedReportUrl = url;
                            lastObservedReportAt = now;
                            String enc = extractQueryParam(url, "enc");
                            if (enc != null && !enc.isEmpty()) lastObservedEnc = enc;
                            // 同一 enc 在 LOG_DEDUP_WINDOW_MS 窗口内只落盘一次
                            // （拦截器链的多层 build() 会重复触发同一上报）
                            boolean dup = enc != null
                                    && enc.equals(lastLoggedEnc)
                                    && (now - lastLoggedAt) < LOG_DEDUP_WINDOW_MS;
                            if (!dup) {
                                lastLoggedEnc = enc;
                                lastLoggedAt = now;
                                String localEnc = computeEncFromUrl(url);
                                String tail = url.length() > 220 ? url.substring(url.length() - 220) : url;
                                if (localEnc != null && enc != null) {
                                    Logx.f("VideoHook[Monitor] real report URL ..." + tail
                                            + " observedEnc=" + enc
                                            + " localEnc=" + localEnc
                                            + " encMatch=" + localEnc.equalsIgnoreCase(enc));
                                } else {
                                    Logx.f("VideoHook[Monitor] real report URL ..." + tail);
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}
                return request;
            });

            Class<?> responseBodyClass = Class.forName("okhttp3.ResponseBody", false, cl);
            Method stringMethod = responseBodyClass.getDeclaredMethod("string");
            module.hook(stringMethod).intercept(chain -> {
                Object body = chain.proceed();
                try {
                    if (body instanceof String) {
                        String text = (String) body;
                        long delta = System.currentTimeMillis() - lastObservedReportAt;
                        if (lastObservedReportUrl != null && delta >= 0 && delta < 8000
                                && text.length() < 8192
                                && (text.contains("\"isPassed\"") || text.contains("\"jobid\""))) {
                            boolean passed = text.contains("\"isPassed\":true")
                                    || text.contains("\"isPassed\": true");
                            String oid = extractQueryParam(lastObservedReportUrl, "objectId");
                            String preview = text.length() > 240 ? text.substring(0, 240) : text;
                            Logx.f("VideoHook[Monitor] natural report response (oid=" + oid
                                    + ", passed=" + passed + "): " + preview);
                            if (passed) {
                                lastNaturalPassObserved = true;
                                lastPassedObjectId = oid;
                                lastBatchPassed = true;
                            }
                        }
                    }
                } catch (Throwable ignored) {}
                return body;
            });
            Logx.i("VideoHook: report traffic monitor ready");
        } catch (ClassNotFoundException e) {
            Logx.w("VideoHook: OkHttp not found, skip report traffic monitor");
        } catch (Throwable t) {
            Logx.w("VideoHook: report traffic monitor hook failed: " + t.getMessage());
        }
    }

    /** 判断 URL 是否为视频任务点进度上报：含 enc + jobid，或路径明确包含 multimedia/playduration/dotype=Video */
    private static boolean isVideoReportUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("dtype=video") || lower.contains("multimedia") || lower.contains("playduration")) {
            return true;
        }
        return lower.contains("enc=") && lower.contains("jobid=") && lower.contains("clazzid=");
    }

    /** 从 URL 查询串中提取参数；不存在返回 null */
    private static String extractQueryParam(String url, String name) {
        if (url == null || name == null) return null;
        int qm = url.indexOf('?');
        if (qm < 0) return null;
        String query = url.substring(qm + 1);
        int hash = query.indexOf('#');
        if (hash >= 0) query = query.substring(0, hash);
        String[] parts = query.split("&");
        String prefix = name + "=";
        for (String p : parts) {
            if (p.startsWith(prefix)) {
                return p.substring(prefix.length());
            }
        }
        return null;
    }

    /** 用本机 calcEnc 重算给定 URL 的 enc，便于与服务端真实接受的 enc 对比 */
    private static String computeEncFromUrl(String url) {
        try {
            String clazzId = extractQueryParam(url, "clazzId");
            String userid = extractQueryParam(url, "userid");
            String jobid = extractQueryParam(url, "jobid");
            String objectId = extractQueryParam(url, "objectId");
            String playingTime = extractQueryParam(url, "playingTime");
            String duration = extractQueryParam(url, "duration");
            String clipTime = extractQueryParam(url, "clipTime");
            if (playingTime == null || duration == null) return null;
            int playSec = Integer.parseInt(playingTime);
            int durSec = Integer.parseInt(duration);
            return calcEnc(clazzId, userid, jobid, objectId, playSec, durSec, clipTime);
        } catch (Throwable t) {
            return null;
        }
    }
}
