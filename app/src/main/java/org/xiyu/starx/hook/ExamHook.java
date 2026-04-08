package org.xiyu.starx.hook;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.xiyu.starx.answer.AnswerProvider;
import org.xiyu.starx.util.CxClasses;
import org.xiyu.starx.util.Logx;
import org.xiyu.starx.util.SecureOverlay;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedModule;

/**
 * 考试自动答题 Hook
 *
 * 三层答题系统:
 * 1. 视频内嵌测验 — hook VideoTestView.setTestData, 利用 isRight 自动选答案
 * 2. WebView 考试/作业 — 注入 JS 提取题目, 查题库/AI, 自动填写
 * 3. 被动答案记录 — 从网络响应/对象字段中提取 rightAnswer 到日志
 *
 * 反切屏检测:
 * 4. JS 层面拦截 visibilitychange / blur 事件, 伪装 document.hidden=false
 */
public class ExamHook {
    private final XposedModule module;
    private final ClassLoader cl;
    private final AnswerProvider answerProvider;
    private final String jsInject;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * JS 反切屏检测脚本 — 在考试页面加载时注入
     *
     * 覆盖三个层面:
     * 1. document.hidden / document.visibilityState → 始终 false / "visible"
     * 2. visibilitychange 事件监听 → 吞掉注册
     * 3. window blur / pagehide / focusout 事件 → 吞掉注册
     *
     * 注意: 必须在页面 JS 执行前注入, 否则已注册的 listener 无法拦截
     */
    private static final String VISIBILITY_BYPASS_JS =
            "(function(){" +
            "try{" +
            "Object.defineProperty(document,'hidden',{get:function(){return false},configurable:true});" +
            "Object.defineProperty(document,'visibilityState',{get:function(){return 'visible'},configurable:true});" +
            "Object.defineProperty(document,'webkitHidden',{get:function(){return false},configurable:true});" +
            "Object.defineProperty(document,'webkitVisibilityState',{get:function(){return 'visible'},configurable:true});" +
            "var _ael=EventTarget.prototype.addEventListener;" +
            "EventTarget.prototype.addEventListener=function(t,l,o){" +
            "if(t==='visibilitychange'||t==='webkitvisibilitychange')return;" +
            "if(this===window&&(t==='blur'||t==='pagehide'||t==='focusout'))return;" +
            "return _ael.call(this,t,l,o);};" +
            "var _onv=Object.getOwnPropertyDescriptor(Document.prototype,'onvisibilitychange');" +
            "Object.defineProperty(document,'onvisibilitychange',{get:function(){return null},set:function(){},configurable:true});" +
            "}catch(e){}" +
            "})()";

    public ExamHook(XposedModule module, ClassLoader cl, AnswerProvider answerProvider, String jsInject) {
        this.module = module;
        this.cl = cl;
        this.answerProvider = answerProvider;
        this.jsInject = jsInject;
    }

    public void hook() throws Throwable {
        hookVideoTestAutoAnswer();
        hookWebViewExam();
        hookActivityQuestion();
        hookNetworkAnswerExtract();
        Logx.i("ExamHook: initialized (auto-answer enabled)");
    }

    // =======================================================================
    // 1. 视频内嵌测验自动答题
    // =======================================================================

    private void hookVideoTestAutoAnswer() {
        try {
            Class<?> videoTestViewClass = Class.forName(CxClasses.VIDEO_TEST_VIEW, false, cl);
            Class<?> testItemClass = Class.forName(CxClasses.TEST_ITEM, false, cl);

            Method setTestData = videoTestViewClass.getDeclaredMethod("setTestData", testItemClass);
            module.hook(setTestData).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object view = chain.getThisObject();
                    Object testItem = chain.getArg(0);
                    if (testItem != null) {
                        autoAnswerVideoTest(view, testItem, videoTestViewClass, testItemClass);
                    }
                } catch (Throwable t) {
                    Logx.w("ExamHook: video auto-answer error: " + t.getMessage());
                }
                return result;
            });
            Logx.i("ExamHook: hooked VideoTestView.setTestData");
        } catch (ClassNotFoundException e) {
            Logx.w("ExamHook: VideoTestView not found, skip video quiz hook");
        } catch (Throwable t) {
            Logx.w("ExamHook: video quiz hook failed: " + t.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void autoAnswerVideoTest(Object view, Object testItem, Class<?> viewClass, Class<?> itemClass) {
        try {
            Method getOptions = itemClass.getDeclaredMethod("getOptions");
            List<?> options = (List<?>) getOptions.invoke(testItem);
            if (options == null || options.isEmpty()) return;

            Class<?> optionClass = Class.forName(CxClasses.TEST_OPTION_ITEM, false, cl);
            Method isRight = optionClass.getDeclaredMethod("isRight");
            Method getName = optionClass.getDeclaredMethod("getName");
            Method getDesc = optionClass.getDeclaredMethod("getDescription");

            List<String> correctNames = new ArrayList<>();
            for (Object option : options) {
                if ((boolean) isRight.invoke(option)) {
                    String name = (String) getName.invoke(option);
                    correctNames.add(name);
                    String desc = (String) getDesc.invoke(option);
                    Logx.i("★ 视频测验正确答案: " + name + "." + desc);
                }
            }

            if (correctNames.isEmpty()) {
                // isRight 未标记，尝试用题库/AI 搜索
                Method getDescription = itemClass.getDeclaredMethod("getDescription");
                String questionText = (String) getDescription.invoke(testItem);
                if (questionText != null && !questionText.isEmpty()) {
                    StringBuilder qb = new StringBuilder(questionText).append("\n");
                    for (Object opt : options) {
                        qb.append(getName.invoke(opt)).append(".").append(getDesc.invoke(opt)).append("\n");
                    }
                    AnswerProvider.Result result = answerProvider.queryWithTimeout(qb.toString(), 15000);
                    if (result != null) {
                        Logx.i("★ 视频测验[" + result.source + "] " + questionText + " => " + result.answer);
                        // 解析字母答案
                        for (char c : result.answer.toUpperCase().toCharArray()) {
                            if (c >= 'A' && c <= 'F') correctNames.add(String.valueOf(c));
                        }
                    }
                }
                if (correctNames.isEmpty()) return;
            }

            // 写入用户选择字段
            Field[] fields = viewClass.getDeclaredFields();
            List<Field> arrayListFields = new ArrayList<>();
            for (Field f : fields) {
                if (f.getType() == ArrayList.class) arrayListFields.add(f);
            }
            if (arrayListFields.size() >= 2) {
                Field userAnswerField = arrayListFields.get(1);
                userAnswerField.setAccessible(true);
                ArrayList<String> userAnswer = (ArrayList<String>) userAnswerField.get(view);
                if (userAnswer != null) {
                    userAnswer.clear();
                    userAnswer.addAll(correctNames);
                    Logx.i("ExamHook: auto-filled video quiz: " + correctNames);
                }
            }
        } catch (Throwable t) {
            Logx.w("ExamHook: autoAnswerVideoTest error: " + t.getMessage());
        }
    }

    // =======================================================================
    // 2. WebView 考试/作业 — JS 注入自动答题
    // =======================================================================

    private void hookWebViewExam() {
        try {
            Method onPageFinished = android.webkit.WebViewClient.class.getDeclaredMethod(
                    "onPageFinished", WebView.class, String.class);
            module.hook(onPageFinished).intercept(chain -> {
                Object result = chain.proceed();
                String url = (String) chain.getArg(1);
                if (url != null && isExamUrl(url)) {
                    WebView wv = (WebView) chain.getArg(0);
                    Logx.i("ExamHook: exam page loaded: " + url);
                    mainHandler.postDelayed(() -> injectStarXScript(wv), 1500);
                    mainHandler.postDelayed(() -> injectStarXScript(wv), 4000);
                }
                return result;
            });
            Logx.i("ExamHook: WebView exam hook ready");
        } catch (Throwable t) {
            Logx.w("ExamHook: WebView hook failed: " + t.getMessage());
        }
    }

    private boolean isExamUrl(String url) {
        if (url.startsWith("javascript:")) return false;
        return url.contains("/exam/") || url.contains("/work/") || url.contains("/mooc2/work/")
                || url.contains("/knowledge/") || url.contains("/test/") || url.contains("/ztnodedetail/")
                || url.contains("doHomeWork") || url.contains("selectWorkQuestion")
                || url.contains("exam/test") || url.contains("task/work");
    }

    private void injectStarXScript(WebView webView) {
        if (webView == null) return;
        try {
            // 首先注入反切屏检测 JS — 必须在考试 JS 之前
            webView.evaluateJavascript(VISIBILITY_BYPASS_JS, null);
            Logx.i("ExamHook: visibility bypass JS injected");
        } catch (Throwable t) {
            Logx.w("ExamHook: visibility bypass inject failed: " + t.getMessage());
        }
        if (jsInject == null || jsInject.isEmpty()) {
            Logx.w("ExamHook: no JS inject script (license?)");
            return;
        }
        try {
            webView.addJavascriptInterface(new StarXJsBridge(webView), "_starx");
            webView.evaluateJavascript(jsInject, null);
            Logx.i("ExamHook: JS injected");
        } catch (Throwable t) {
            Logx.w("ExamHook: JS inject failed: " + t.getMessage());
        }
    }

    /** Java ↔ JS 桥接 */
    public class StarXJsBridge {
        private final WebView webView;
        StarXJsBridge(WebView wv) { this.webView = wv; }

        @android.webkit.JavascriptInterface
        public String queryAnswer(String question) {
            String preview = question.length() > 60 ? question.substring(0, 60) : question;
            Logx.i("ExamHook[JS→Java]: " + preview);
            mainHandler.post(() -> android.widget.Toast.makeText(
                    webView.getContext(), "正在搜题...", android.widget.Toast.LENGTH_SHORT).show());
            AnswerProvider.Result r = answerProvider.queryWithTimeout(question, 20000);
            if (r != null) {
                Logx.i("★ 答案[" + r.source + "] => " + r.answer);
                final String src = r.source;
                final String answer = r.answer;
                // 在安全浮窗中显示答案 (录屏/截屏不可见)
                mainHandler.post(() -> showAnswerOverlay(webView.getContext(),
                        "[" + src + "] " + answer));
                return r.answer;
            }
            mainHandler.post(() -> android.widget.Toast.makeText(
                    webView.getContext(), "✗ 未找到答案", android.widget.Toast.LENGTH_SHORT).show());
            return "";
        }

        @android.webkit.JavascriptInterface
        public void log(String msg) { Logx.i("ExamHook[JS]: " + msg); }
    }

    // =======================================================================
    // 安全答案浮窗 — FLAG_SECURE 不可被录屏/截屏捕获
    // =======================================================================

    private View answerOverlayView;

    /**
     * 在安全浮窗中显示答案文本。
     * 使用 FLAG_SECURE 窗口，录屏和截屏时该窗口不可见。
     * 5 秒后自动消失。
     */
    private void showAnswerOverlay(Context context, String text) {
        Activity activity = SecureOverlay.asActivity(context);
        if (activity == null || activity.isFinishing()) {
            // 降级为 Toast
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show();
            return;
        }

        // 移除旧浮窗
        if (answerOverlayView != null) {
            try {
                SecureOverlay.removeSecureView(activity, answerOverlayView);
            } catch (Throwable ignored) {}
            answerOverlayView = null;
        }

        try {
            // 创建答案显示面板
            int dp8 = dpToPx(context, 8);
            int dp12 = dpToPx(context, 12);

            TextView tv = new TextView(context);
            tv.setText(text);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tv.setTypeface(null, Typeface.BOLD);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.argb(200, 30, 60, 120));
            bg.setCornerRadius(dpToPx(context, 16));
            tv.setBackground(bg);
            tv.setPadding(dp12, dp8, dp12, dp8);
            tv.setMaxWidth(dpToPx(context, 280));

            // 点击关闭
            final Activity act = activity;
            tv.setOnClickListener(v -> {
                try {
                    SecureOverlay.removeSecureView(act, tv);
                } catch (Throwable ignored) {}
                answerOverlayView = null;
            });

            SecureOverlay.addSecureView(activity, tv,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, dpToPx(context, 80));
            answerOverlayView = tv;

            // 5 秒后自动消失
            mainHandler.postDelayed(() -> {
                if (answerOverlayView == tv) {
                    try {
                        SecureOverlay.removeSecureView(act, tv);
                    } catch (Throwable ignored) {}
                    answerOverlayView = null;
                }
            }, 5000);

            Logx.i("ExamHook: answer overlay shown (secure)");
        } catch (Throwable t) {
            Logx.w("ExamHook: secure overlay failed: " + t.getMessage());
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private static int dpToPx(Context ctx, int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    // =======================================================================
    // 3. 被动答案记录 (保留旧功能)
    // =======================================================================

    private void hookActivityQuestion() {
        try {
            Class<?> aqClass = Class.forName(CxClasses.ACTIVITY_QUESTION, false, cl);
            Field answerField = null, titleField = null;
            for (Field f : aqClass.getDeclaredFields()) {
                if ("rightAnswer".equals(f.getName())) answerField = f;
                else if ("title".equals(f.getName())) titleField = f;
            }
            if (answerField == null) return;
            final Field fAnswer = answerField;
            final Field fTitle = titleField;
            fAnswer.setAccessible(true);
            if (fTitle != null) fTitle.setAccessible(true);

            for (var ctor : aqClass.getDeclaredConstructors()) {
                module.hook(ctor).intercept(chain -> {
                    Object result = chain.proceed();
                    Object inst = chain.getThisObject();
                    if (inst != null) {
                        String answer = String.valueOf(fAnswer.get(inst));
                        if (answer != null && !"null".equals(answer) && !answer.isEmpty()) {
                            String title = fTitle != null ? String.valueOf(fTitle.get(inst)) : "?";
                            Logx.i("★ 答案[被动] " + title + " => " + answer);
                        }
                    }
                    return result;
                });
            }
            Logx.i("ExamHook: passive answer extraction ready");
        } catch (ClassNotFoundException e) {
            Logx.w("ExamHook: ActivityQuestion not found, skip");
        }
    }

    private void hookNetworkAnswerExtract() {
        try {
            Class<?> responseBodyClass = Class.forName("okhttp3.ResponseBody", false, cl);
            Class<?> requestClass = Class.forName("okhttp3.Request", false, cl);
            Class<?> responseClass = Class.forName("okhttp3.Response", false, cl);

            // Hook ResponseBody.string() — 在读取响应体时检查是否包含答案
            Method stringMethod = responseBodyClass.getDeclaredMethod("string");
            Method responseRequest = responseClass.getDeclaredMethod("request");
            Method requestUrl = requestClass.getDeclaredMethod("url");

            module.hook(stringMethod).intercept(chain -> {
                String body = (String) chain.proceed();
                if (body != null && body.contains("rightAnswer")) {
                    try {
                        // 简单解析: 查找 "rightAnswer":"xxx" 模式
                        int idx = 0;
                        while ((idx = body.indexOf("rightAnswer", idx)) != -1) {
                            // 找到 rightAnswer 后面的值
                            int colonIdx = body.indexOf(":", idx);
                            if (colonIdx == -1) break;
                            int quoteStart = body.indexOf("\"", colonIdx + 1);
                            if (quoteStart == -1) break;
                            int quoteEnd = body.indexOf("\"", quoteStart + 1);
                            if (quoteEnd == -1) break;
                            String answer = body.substring(quoteStart + 1, quoteEnd);
                            if (!answer.isEmpty()) {
                                // 尝试提取题目信息
                                String title = extractJsonField(body, idx, "title");
                                String qid = extractJsonField(body, idx, "questionId");
                                Logx.i("★ 答案[网络] Q[" + (qid != null ? qid : "?") + "] "
                                        + (title != null ? title : "?") + " => " + answer);
                            }
                            idx = quoteEnd + 1;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                return body;
            });
            Logx.i("ExamHook: hooked OkHttp ResponseBody for answer extraction");
        } catch (ClassNotFoundException e) {
            Logx.w("ExamHook: OkHttp not found, skip network answer extraction");
        } catch (Throwable t) {
            Logx.w("ExamHook: network answer hook failed: " + t.getMessage());
        }
    }

    /**
     * 简易 JSON 字段提取 — 在 rightAnswer 附近查找指定字段值
     */
    private String extractJsonField(String json, int nearIdx, String fieldName) {
        try {
            int searchStart = Math.max(0, nearIdx - 500);
            String region = json.substring(searchStart, Math.min(json.length(), nearIdx + 500));
            int fieldIdx = region.indexOf("\"" + fieldName + "\"");
            if (fieldIdx == -1) return null;
            int colonIdx = region.indexOf(":", fieldIdx);
            if (colonIdx == -1) return null;
            int valueStart = colonIdx + 1;
            while (valueStart < region.length() && region.charAt(valueStart) == ' ') valueStart++;
            if (valueStart >= region.length()) return null;
            if (region.charAt(valueStart) == '"') {
                int valueEnd = region.indexOf("\"", valueStart + 1);
                if (valueEnd == -1) return null;
                return region.substring(valueStart + 1, valueEnd);
            } else {
                int valueEnd = valueStart;
                while (valueEnd < region.length() && region.charAt(valueEnd) != ',' && region.charAt(valueEnd) != '}') valueEnd++;
                return region.substring(valueStart, valueEnd).trim();
            }
        } catch (Throwable ignored) { return null; }
    }

    // JS 注入脚本由服务端下发，存储在 jsInject 字段
}
