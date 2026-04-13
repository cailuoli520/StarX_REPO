package org.xiyu.starx.hook;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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
import org.xiyu.starx.util.AnswerMatcher;
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
    private static final String CONFIG_PREFS = "config";
    private static final String KEY_EXAM_ENABLED = "hook_exam_enabled";
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

    /**
     * 本地 shim — monkey-patch _starx.queryAnswer
     *
     * 服务端旧脚本调用 _starx.queryAnswer(question) 时，shim 会：
     * 1. 从参数中提取纯题干，并定位当前题块
     * 2. 仅提取当前题块的选项列表，避免整页串题
     * 3. 升级调用为 _starx.queryAnswerWithOptions(question, type, options)
     *
     * 如果 DOM 提取失败则退化为 question-only，不影响旧逻辑可用性
     */
    private static final String QUERY_UPGRADE_SHIM =
            "(function(){" +
            "if(window._starxQueryUpgraded)return;" +
            "if(!window._starx||!window._starx.queryAnswerWithOptions)return;" +
            "window._starxQueryUpgraded=true;" +
            "var _usedRoots=[];" +
            "function trimText(s){return (s||'').replace(/^\\s+|\\s+$/g,'');}" +
            "function firstLine(s){s=trimText(s);if(!s)return s;return trimText(s.split(/\\n+/)[0]);}" +
            "function normalizeText(s){return trimText(s).replace(/\\s+/g,'');}" +
            "function extractOptionText(s){return trimText(s).replace(/^[A-Za-z][.、:：\\s]\\s*/,'');}" +
            "function findQuestionRoot(question){" +
            "var roots=document.querySelectorAll('.TiMu,.tiMu,.singleQuesId,[id^=\"question\"],.questionLi,.Cy_TItle,.queBox,.mark_item,.questionItem,.exam-item,.pad_question,.subjectDet');" +
            "if(!question){" +
            "if(roots.length===0)return null;" +
            "for(var i=0;i<roots.length;i++){if(_usedRoots.indexOf(roots[i])<0)return roots[i];}" +
            "return roots.length===1?roots[0]:null;" +
            "}" +
            "var qn=normalizeText(question),best=null,bestScore=0;" +
            "for(var i=0;i<roots.length;i++){" +
            "var root=roots[i];" +
            "var te=root.querySelector('.Zy_TItle .clearfix,.Zy_TItle,.mark_name,.stem,.mark_name_one,.q-title');" +
            "var title=firstLine(te?te.textContent:root.textContent);" +
            "if(!title)continue;" +
            "var tn=normalizeText(title),score=0;" +
            "if(tn===qn)score=3;" +
            "else if(tn.indexOf(qn)>=0||qn.indexOf(tn)>=0)score=2;" +
            "else if(title.indexOf(question)>=0||question.indexOf(title)>=0)score=1;" +
            "if(score>bestScore){best=root;bestScore=score;if(score===3)break;}" +
            "}" +
            "return best;" +
            "}" +
            "function collectOptions(root){" +
            "var opts=[];if(!root||!root.querySelectorAll)return opts;" +
            "var nodes=root.querySelectorAll('li.fl_l,li.clearfix,.answerBg,.option-item,.option_li,.radio_option,.checkbox_option,.optionUl li,.answerBg .radioItemCont,.answerBg .checkItemCont,.optionItem,.questionLi .optionUl li');" +
            "if(!nodes.length)nodes=root.querySelectorAll('[class*=option] li,[class*=Option] li');" +
            "for(var i=0;i<nodes.length;i++){" +
            "var t=extractOptionText(nodes[i].textContent||'');" +
            "if(t&&opts.indexOf(t)<0)opts.push(t);" +
            "}" +
            "return opts;" +
            "}" +
            "function collectImageUrls(root){" +
            "var urls=[];if(!root)return urls;" +
            "var stem=root.querySelector('.Zy_TItle .clearfix,.Zy_TItle,.mark_name,.stem,.mark_name_one,.q-title');" +
            "if(!stem)stem=root;" +
            "var imgs=stem.querySelectorAll('img');" +
            "for(var i=0;i<imgs.length;i++){" +
            "var el=imgs[i];" +
            "var s=el.getAttribute('data-src')||el.getAttribute('data-original')||el.currentSrc||el.getAttribute('src');" +
            "if(!s||s.indexOf('data:')===0)continue;" +
            "try{s=new URL(s,location.href).href;}catch(e){}" +
            "if(s.indexOf('http')===0&&urls.indexOf(s)<0)urls.push(s);" +
            "}" +
            "return urls;" +
            "}" +
            "function inferType(opts){" +
            "if(!opts||opts.length!==2)return -1;" +
            "var a=(opts[0]||'').toLowerCase(),b=(opts[1]||'').toLowerCase();" +
            "if((a.indexOf('对')>=0&&b.indexOf('错')>=0)||(a.indexOf('错')>=0&&b.indexOf('对')>=0)" +
            "||(a.indexOf('正确')>=0&&b.indexOf('错误')>=0)||(a.indexOf('错误')>=0&&b.indexOf('正确')>=0)" +
            "||(a.indexOf('是')>=0&&b.indexOf('否')>=0)||(a.indexOf('否')>=0&&b.indexOf('是')>=0)" +
            "||(a==='√'&&b==='×')||(a==='×'&&b==='√')||(a==='true'&&b==='false')||(a==='false'&&b==='true'))return 3;" +
            "return 0;" +
            "}" +
            "window._starx.queryAnswer=function(q){" +
            "try{" +
            "var question=firstLine(q)||trimText(q);" +
            "var root=findQuestionRoot(question);" +
            "if(root&&_usedRoots.indexOf(root)<0)_usedRoots.push(root);" +
            "var opts=collectOptions(root);" +
            "var imgs=collectImageUrls(root);" +
            "var type=inferType(opts);" +
            "var optStr=opts.length?opts.map(function(o){return o.replace(/\\|/g,'');}).join('|'):null;" +
            "var imgStr=imgs.length?imgs.join('\\n'):null;" +
            "if(window._starx.queryAnswerWithImages)return window._starx.queryAnswerWithImages(question||q,type,optStr,imgStr);" +
            "return window._starx.queryAnswerWithOptions(question||q,type,optStr);" +
            "}catch(e){try{window._starx.log('shim err:'+e);}catch(_e){}}" +
            "var question=firstLine(q)||trimText(q);" +
            "return window._starx.queryAnswerWithOptions(question||q,-1,null);" +
            "};" +
            "})()";

    public ExamHook(XposedModule module, ClassLoader cl, AnswerProvider answerProvider, String jsInject) {
        this.module = module;
        this.cl = cl;
        this.answerProvider = answerProvider;
        this.jsInject = jsInject;
    }

    public void hook() throws Throwable {
        if (!isExamEnabled()) {
            Logx.i("ExamHook: disabled by config, skip init");
            return;
        }
        hookVideoTestAutoAnswer();
        hookWebViewExam();
        hookOcrResult();
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
        if (!isExamEnabled()) return;
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
                    // 构建选项列表供 AnswerMatcher 使用
                    List<String> optionTexts = new ArrayList<>();
                    StringBuilder optSb = new StringBuilder();
                    for (Object opt : options) {
                        String n = (String) getName.invoke(opt);
                        String d = (String) getDesc.invoke(opt);
                        optionTexts.add(n + "." + d);
                        if (optSb.length() > 0) optSb.append("|");
                        optSb.append(d.replace("|", "")); // 剔除选项文本内的竖线，保持分隔符完整性
                    }
                    // 推断题型: 判断题(2选项且内容为对/错类) > 多选(暂无法区分) > 默认未知
                    int questionType = inferQuestionType(optionTexts);
                    // question 只传纯题干，options 单独传，避免缓存键和检索污染
                    AnswerProvider.Result result = answerProvider.queryWithTimeout(
                            questionText, questionType, optSb.toString(), 15000);
                    if (result != null) {
                        Logx.i("★ 视频测验[" + result.source + "] " + questionText + " => " + result.answer);
                        // 优先用 AnswerMatcher 进行智能匹配
                        List<String> matched = AnswerMatcher.matchOptions(result.answer, optionTexts);
                        if (!matched.isEmpty()) {
                            // 确认为单选(0)或判断(3)时才压缩为最佳候选；未知(-1)保留全部匹配
                            if (matched.size() > 1 && (questionType == 0 || questionType == 3)) {
                                String best = AnswerMatcher.matchBestOption(result.answer, optionTexts);
                                correctNames.add(best != null ? best : matched.get(0));
                            } else {
                                correctNames.addAll(matched);
                            }
                        } else {
                            // 回退: 解析字母答案
                            for (char c : result.answer.toUpperCase().toCharArray()) {
                                if (c >= 'A' && c <= 'F') correctNames.add(String.valueOf(c));
                            }
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
                if (!isExamEnabled()) return result;
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

    // =======================================================================
    // 2b. 宿主 OCR 结果拦截 — CLIENT_OCR_REC (62241) 返回
    // =======================================================================

    /**
     * Hook 宿主 OCR 返回 — 拦截 WebAppViewerFragment.onActivityResult
     *
     * 宿主协议 CLIENT_OCR_REC 使用 requestCode=62241 拉起 OCR 相机,
     * 返回的 JCameraResult.getOcrResult() 直接带识别文本。
     * 本 Hook 拦截该返回, 将 OCR 文本直接喂入 AnswerProvider 搜题链。
     */
    private void hookOcrResult() {
        String fragmentClassName = CxClasses.WEBAPP_VIEWER_FRAGMENT;
        if (fragmentClassName == null || fragmentClassName.isEmpty()) {
            Logx.w("ExamHook: WEBAPP_VIEWER_FRAGMENT not configured, skip OCR hook");
            return;
        }
        try {
            Class<?> fragmentClass = Class.forName(fragmentClassName, false, cl);
            Method onActivityResult;
            try {
                onActivityResult = fragmentClass.getDeclaredMethod(
                        "onActivityResult", int.class, int.class, Intent.class);
            } catch (NoSuchMethodException e) {
                onActivityResult = fragmentClass.getMethod(
                        "onActivityResult", int.class, int.class, Intent.class);
            }

            module.hook(onActivityResult).intercept(chain -> {
                // 先让原逻辑跑完（协议分发等）
                Object result = chain.proceed();
                if (!isExamEnabled()) return result;

                int requestCode = (int) chain.getArg(0);
                int resultCode = (int) chain.getArg(1);
                Intent intent = (Intent) chain.getArg(2);

                // 62241 = CLIENT_OCR_REC, -1 = Activity.RESULT_OK
                if (requestCode == 62241 && resultCode == -1 && intent != null) {
                    try {
                        Object cameraResult = intent.getParcelableExtra("data");
                        if (cameraResult != null) {
                            Method getOcrResult = cameraResult.getClass().getMethod("getOcrResult");
                            String ocrText = (String) getOcrResult.invoke(cameraResult);
                            if (ocrText != null && !ocrText.trim().isEmpty()) {
                                ocrText = ocrText.trim();
                                Logx.i("ExamHook: host OCR text => " + ocrText);

                                // 获取 Activity 上下文用于显示结果
                                Context ctx = null;
                                try {
                                    Object fragment = chain.getThisObject();
                                    Method getActivity = fragment.getClass().getMethod("getActivity");
                                    ctx = (Context) getActivity.invoke(fragment);
                                } catch (Throwable ignored) {}
                                final Context context = ctx;

                                // 异步搜题
                                final String queryText = ocrText;
                                answerProvider.queryAsync(queryText, new AnswerProvider.Callback() {
                                    @Override
                                    public void onAnswer(String answer, String source) {
                                        Logx.i("★ OCR搜题[" + source + "] => " + answer);
                                        if (context != null) {
                                            mainHandler.post(() -> showAnswerOverlay(context,
                                                    "[OCR · " + source + "] " + answer));
                                        }
                                    }

                                    @Override
                                    public void onFailed() {
                                        Logx.w("ExamHook: OCR search found no answer");
                                        if (context != null) {
                                            mainHandler.post(() -> android.widget.Toast.makeText(
                                                    context, "[隐私] OCR搜题未找到答案",
                                                    android.widget.Toast.LENGTH_SHORT).show());
                                        }
                                    }
                                });
                            }
                        }
                    } catch (Throwable t) {
                        Logx.w("ExamHook: OCR result hook error: " + t.getMessage());
                    }
                }
                return result;
            });
            Logx.i("ExamHook: hooked WebAppViewerFragment.onActivityResult for OCR (62241)");
        } catch (ClassNotFoundException e) {
            Logx.w("ExamHook: WebAppViewerFragment not found, skip OCR hook");
        } catch (Throwable t) {
            Logx.w("ExamHook: OCR hook failed: " + t.getMessage());
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
        if (webView == null || !isExamEnabled()) return;
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
            webView.evaluateJavascript(QUERY_UPGRADE_SHIM, null);
            // 先安装 shim，再执行服务端脚本，覆盖脚本初始化阶段的同步搜题调用
            webView.evaluateJavascript(jsInject, null);
            Logx.i("ExamHook: JS injected (with shim)");
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
            return queryAnswerWithOptions(question, -1, null);
        }

        /**
         * JS 桥接 — 带题型、选项和图片 URL 的查询（图片搜题入口）
         * @param question  题目文本
         * @param type      题型 (0单选 1多选 2填空 3判断 4简答)，-1=自动
         * @param options   选项，以 | 分隔纯内容，可为 null
         * @param imageUrls 题干中的图片 URL，以 \n 分隔，可为 null
         */
        @android.webkit.JavascriptInterface
        public String queryAnswerWithImages(String question, int type, String options, String imageUrls) {
            if (!isExamEnabled()) {
                Logx.i("ExamHook[JS→Java]: blocked by config");
                return "";
            }
            if ((question == null || question.trim().isEmpty())
                    && (imageUrls == null || imageUrls.trim().isEmpty())) {
                Logx.w("ExamHook[JS→Java]: no question or images, skipping");
                return "";
            }
            String q = question != null ? question.trim() : "";
            String preview = q.length() > 60 ? q.substring(0, 60) : q;
            boolean hasImages = imageUrls != null && !imageUrls.trim().isEmpty();
            Logx.i("ExamHook[JS→Java]: " + preview + (hasImages ? " [+img]" : ""));
            mainHandler.post(() -> android.widget.Toast.makeText(
                    webView.getContext(),
                    hasImages ? "[隐私] 正在识别图片并搜题..." : "[隐私] 正在搜题...",
                    android.widget.Toast.LENGTH_SHORT).show());

            // 解析图片 URL 列表
            java.util.List<String> imgList = null;
            if (hasImages) {
                imgList = new java.util.ArrayList<>();
                for (String u : imageUrls.split("\\n")) {
                    String trimmed = u.trim();
                    if (!trimmed.isEmpty()) imgList.add(trimmed);
                }
                if (imgList.isEmpty()) imgList = null;
            }

            AnswerProvider.Result r = answerProvider.queryWithTimeout(
                    q, type, options, imgList, 25000);
            if (r != null) {
                Logx.i("★ 答案[" + r.source + "] => " + r.answer);
                final String src = r.source;
                final String answer = r.answer;
                mainHandler.post(() -> showAnswerOverlay(webView.getContext(),
                        "[隐私 · " + src + "] " + answer));
                return r.answer;
            }
            mainHandler.post(() -> android.widget.Toast.makeText(
                    webView.getContext(), "[隐私] 未找到答案", android.widget.Toast.LENGTH_SHORT).show());
            return "";
        }
        @android.webkit.JavascriptInterface
        public String queryAnswerWithOptions(String question, int type, String options) {
            if (!isExamEnabled()) {
                Logx.i("ExamHook[JS→Java]: blocked by config");
                return "";
            }
            if (question == null || question.trim().isEmpty()) {
                Logx.w("ExamHook[JS→Java]: question is null/empty, skipping");
                return "";
            }
            question = question.trim();
            String preview = question.length() > 60 ? question.substring(0, 60) : question;
            Logx.i("ExamHook[JS→Java]: " + preview);
            mainHandler.post(() -> android.widget.Toast.makeText(
                    webView.getContext(), "[隐私] 正在搜题...", android.widget.Toast.LENGTH_SHORT).show());
            AnswerProvider.Result r = answerProvider.queryWithTimeout(question, type, options, 20000);
            if (r != null) {
                Logx.i("★ 答案[" + r.source + "] => " + r.answer);
                final String src = r.source;
                final String answer = r.answer;
                // 在安全浮窗中显示答案 (录屏/截屏不可见)
                mainHandler.post(() -> showAnswerOverlay(webView.getContext(),
                        "[隐私 · " + src + "] " + answer));
                return r.answer;
            }
            mainHandler.post(() -> android.widget.Toast.makeText(
                    webView.getContext(), "[隐私] 未找到答案", android.widget.Toast.LENGTH_SHORT).show());
            return "";
        }

        @android.webkit.JavascriptInterface
        public void log(String msg) { Logx.i("ExamHook[JS]: " + msg); }
    }

    /**
     * 推断题型: -1=未知 0=单选 1=多选 2=填空 3=判断 4=简答
     *
     * 启发式: 2个选项且内容是对/错类 → 判断(3)
     *         >2个选项 → 默认单选(0)，柠檬题库会自转
     *         无选项   → -1
     */
    private static int inferQuestionType(List<String> optionTexts) {
        if (optionTexts == null || optionTexts.isEmpty()) return -1;
        if (optionTexts.size() == 2) {
            String c0 = extractContent(optionTexts.get(0));
            String c1 = extractContent(optionTexts.get(1));
            if (isJudgePair(c0, c1)) return 3; // 判断题
            return 0; // 2 个非判断选项，视为单选
        }
        // >2 选项无法区分单选/多选，返回 -1 保留全部匹配结果
        return -1;
    }

    private static String extractContent(String option) {
        if (option == null) return "";
        return option.replaceFirst("^[A-Za-z][.、:：\\s]\\s*", "").trim();
    }

    private static boolean isJudgePair(String a, String b) {
        String la = a.toLowerCase();
        String lb = b.toLowerCase();
        return (la.contains("对") && lb.contains("错"))
                || (la.contains("错") && lb.contains("对"))
                || (la.contains("正确") && lb.contains("错误"))
                || (la.contains("错误") && lb.contains("正确"))
                || (la.contains("是") && lb.contains("否"))
                || (la.contains("否") && lb.contains("是"))
                || (la.equals("√") && lb.equals("×"))
                || (la.equals("×") && lb.equals("√"))
                || (la.equals("true") && lb.equals("false"))
                || (la.equals("false") && lb.equals("true"));
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
                    if (!isExamEnabled()) return result;
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
                if (!isExamEnabled()) return body;
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
                            int quoteEnd = findClosingQuote(body, quoteStart + 1);
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
                int valueEnd = findClosingQuote(region, valueStart + 1);
                if (valueEnd == -1) return null;
                return region.substring(valueStart + 1, valueEnd);
            } else {
                int valueEnd = valueStart;
                while (valueEnd < region.length() && region.charAt(valueEnd) != ',' && region.charAt(valueEnd) != '}') valueEnd++;
                return region.substring(valueStart, valueEnd).trim();
            }
        } catch (Throwable ignored) { return null; }
    }

    /** 找到字符串中下一个未转义的双引号位置 */
    private static int findClosingQuote(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') { i++; continue; } // 跳过转义
            if (c == '"') return i;
        }
        return -1;
    }

    private boolean isExamEnabled() {
        try {
            var prefs = module.getRemotePreferences(CONFIG_PREFS);
            return prefs.getBoolean(KEY_EXAM_ENABLED, true);
        } catch (Throwable t) {
            Logx.w("ExamHook: read config failed: " + t.getMessage());
            return true;
        }
    }

    // JS 注入脚本由服务端下发，存储在 jsInject 字段
}
