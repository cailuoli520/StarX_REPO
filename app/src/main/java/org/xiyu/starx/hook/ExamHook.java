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
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JsPromptResult;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import org.xiyu.starx.answer.AnswerProvider;
import org.xiyu.starx.util.AnswerMatcher;
import org.xiyu.starx.util.CxClasses;
import org.xiyu.starx.util.Logx;
import org.xiyu.starx.util.SecureOverlay;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    private static final String KEY_EXAM_TRIGGER = "hook_exam_trigger";
    private static final String KEY_EXAM_HTML_PIPELINE = "hook_exam_html_pipeline";
    private static final long HTML_PIPELINE_ANSWER_TIMEOUT_MS = 12000L;
    private static final long HTML_PIPELINE_CAPTURE_DELAY_MS = 2200L;
    private static final int HTML_PIPELINE_MAX_QUESTIONS = 48;
    private static final String PROMPT_BRIDGE_DEFAULT = "StarXBridgeV1";
    private static final long PROMPT_QUERY_TIMEOUT_MS = 6500L;
    private static final long PROMPT_IMAGE_QUERY_TIMEOUT_MS = 9000L;
    private static final long JS_BRIDGE_QUERY_TIMEOUT_MS = 9000L;
    private static final long JS_BRIDGE_IMAGE_QUERY_TIMEOUT_MS = 12000L;
    private static final long STATUS_OVERLAY_DEFAULT_MS = 2200L;
    private static final long VOLUME_COMBO_WINDOW_MS = 900L;
    private static final String EXAM_TRIGGER_VOLUME_DOWN = "volume_down";
    private static final String EXAM_TRIGGER_VOLUME_UP = "volume_up";
    private static final String EXAM_TRIGGER_VOLUME_UP_DOWN = "volume_up_down";
    private static final String EXAM_TRIGGER_AUTO = "auto";
    private static final String QUESTION_ROOT_SELECTORS = ".TiMu,.tiMu,.singleQuesId,[id^=\"question\"],.questionLi,.Cy_TItle,.queBox,.mark_item,.questionItem,.exam-item,.pad_question,.subjectDet,.mark_name,.question-wrap,.topic-item,.question-list li";
    private static final String QUESTION_OPTION_SELECTORS = "li.fl_l,li.clearfix,.answerBg,.option-item,.option_li,.radio_option,.checkbox_option,.optionUl li,.answerBg .radioItemCont,.answerBg .checkItemCont,.optionItem,.questionLi .optionUl li,[class*=option] li,[class*=Option] li";
    private static final String[] EXAM_URL_HINTS = new String[]{
            "/exam/",
            "/work/",
            "/mooc2/work/",
            "/knowledge/",
            "/test/",
            "/ztnodedetail/",
            "dohomework",
            "selectworkquestion",
            "exam/test",
            "task/work",
            "work/task-list",
            "work/stu-work",
            "work/task/library",
            "mooc-ans/exam/phone/task-list",
            "exam/phone/selftest-list",
            "phone/moocanalysis/selfscoredetail"
    };
    private final XposedModule module;
    private final ClassLoader cl;
    private final AnswerProvider answerProvider;
    private final String jsInject;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService promptExecutor = java.util.concurrent.Executors.newCachedThreadPool();
    private final java.util.Set<Integer> registeredWebViews = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private final java.util.Set<Class<?>> hookedPromptClients = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private final java.util.Map<Integer, WeakReference<WebView>> activeExamWebViews = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> htmlPipelineSolvedStems = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private final java.util.Map<Integer, Long> lastHtmlPipelineRunAt = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long lastVolumeUpPressedAt = 0L;

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

    private static final String PROMPT_BRIDGE_BOOTSTRAP =
            "(function(){" +
            "if(window.__starxPromptBridgeReady)return 'ready';" +
            "window.__starxPromptBridgeReady=true;" +
            "function nativeBridge(){try{return window._starxNative||null;}catch(e){return null;}}" +
            "function call(method,payload){" +
            "try{" +
            "return prompt(JSON.stringify({method:method,payload:payload||{}}),'StarXBridgeV1')||'';" +
            "}catch(e){return '';}}" +
            "var bridge=window._starx||{};" +
            "bridge.queryAnswer=function(question){try{var n=nativeBridge();if(n&&n.queryAnswer)return n.queryAnswer(question)||'';}catch(e){}return call('queryAnswer',{question:question});};" +
            "bridge.queryAnswerWithOptions=function(question,type,options){try{var n=nativeBridge();if(n&&n.queryAnswerWithOptions)return n.queryAnswerWithOptions(question,type,options)||'';}catch(e){}return call('queryAnswerWithOptions',{question:question,type:type,options:options});};" +
            "bridge.queryAnswerWithImages=function(question,type,options,imageUrls){try{var n=nativeBridge();if(n&&n.queryAnswerWithImages)return n.queryAnswerWithImages(question,type,options,imageUrls)||'';}catch(e){}return call('queryAnswerWithImages',{question:question,type:type,options:options,imageUrls:imageUrls});};" +
            "bridge.notifyStatus=function(message,durationMs){var ms=durationMs||0;try{var n=nativeBridge();if(n&&n.notifyStatus){n.notifyStatus(String(message||''),ms);return '';}}catch(e){}call('notifyStatus',{message:String(message||''),durationMs:ms});return '';};" +
            "bridge.log=function(msg){try{var n=nativeBridge();if(n&&n.log){n.log(String(msg||''));return '';}}catch(e){}call('log',{msg:String(msg)});return '';};" +
            "window._starx=bridge;" +
            "return 'installed';" +
            "})()";

    private static final String QUERY_MONITOR_JS =
            "(function(){" +
            "if(window.__starxMonitorReady)return 'ready';" +
            "window.__starxMonitorReady=true;" +
            "function slog(msg){try{window._starx&&window._starx.log&&window._starx.log(msg);}catch(e){}}" +
            "function wrap(name){" +
            "if(!window._starx||!window._starx[name]||window._starx[name].__starxWrapped)return;" +
            "var orig=window._starx[name];" +
            "var wrapped=function(){" +
            "var first=arguments.length?String(arguments[0]||''):'';" +
            "slog('call:'+name+':' + first.substring(0,80));" +
            "return orig.apply(this,arguments);" +
            "};" +
            "wrapped.__starxWrapped=true;" +
            "window._starx[name]=wrapped;" +
            "}" +
            "wrap('queryAnswer');" +
            "wrap('queryAnswerWithOptions');" +
            "wrap('queryAnswerWithImages');" +
            "document.addEventListener('click',function(e){" +
            "try{" +
            "var t=e.target;if(!t)return;" +
            "var txt=((t.innerText||t.textContent||t.value||'')+'').replace(/\\s+/g,' ').trim();" +
            "var hint=((t.id||'')+' '+(t.className||'')).toLowerCase();" +
            "if(txt.indexOf('搜题')>=0||hint.indexOf('starx')>=0||hint.indexOf('search')>=0){" +
            "slog('click:' + txt.substring(0,80) + ' #' + (t.id||'') + ' .' + (t.className||''));" +
            "}" +
            "}catch(err){}" +
            "},true);" +
            "return 'installed';" +
            "})()";

    private static final String SEARCH_BUTTON_LAYOUT_FIX_JS =            "(function(){" +
            "if(window.__starxLayoutFixReady)return 'ready';" +
            "window.__starxLayoutFixReady=true;" +
            "function textOf(el){return ((el&&(el.innerText||el.textContent||el.value))||'').replace(/\\s+/g,' ').trim();}" +
            "function compactText(text){return String(text||'').replace(/\\s+/g,'');}" +
            "function visible(el){if(!el||!el.getBoundingClientRect)return false;var st=getComputedStyle(el);if(st.display==='none'||st.visibility==='hidden'||st.opacity==='0')return false;var r=el.getBoundingClientRect();return r.width>40&&r.height>20&&r.bottom>0&&r.right>0;}" +
            "function setLabel(el,text){try{var tag=String(el.tagName||'').toLowerCase();if(tag==='input'||tag==='textarea'){el.value=text;}else{el.innerText=text;}}catch(e){}}" +
            "function isSearchBtn(el){var txt=compactText(textOf(el));var hint=((el&&el.id||'')+' '+(el&&el.className||'')).toLowerCase();return hint.indexOf('starx')>=0||hint.indexOf('search')>=0||txt==='搜题'||txt==='搜题中'||txt==='私密搜题'||(txt.indexOf('搜题')>=0&&txt.length<=8);}" +
            "function normalizeSearchBtn(el){var txt=compactText(textOf(el));if(!txt)return;var next=txt.indexOf('搜题中')>=0?'搜题中':'搜题';if(txt!==next)setLabel(el,next);}" +
            "function searchButtons(){var nodes=document.querySelectorAll('div,button,a,span,input[type=button],input[type=submit],[role=button]');var list=[];for(var i=0;i<nodes.length;i++){var el=nodes[i];if(isSearchBtn(el)&&visible(el)){normalizeSearchBtn(el);list.push(el);}}return list;}" +
            "function submitNodes(){return document.querySelectorAll('button,input[type=button],input[type=submit],a,div,span,[role=button],.submit,.subBtn,.submitBtn,.btn-submit,.btnBlue,.bluebtn,.mooc-btn,.answerSub,.chapter-submit,.chapterSubmit,.bottomBtn,.bottom-btn');}" +
            "function isSubmitLike(el){var txt=compactText(textOf(el));var hint=((el.id||'')+' '+(el.className||'')).toLowerCase();return /提交|交卷|完成|确认|保存|下一题|下一步|提交答案|确定/.test(txt)||/submit|commit|finish|save|answer|next|chapter|btnblue|subbtn|submitbtn|btn-submit|bottombtn|bottom-btn/.test(hint);}" +
            "function findBottomAnchor(){var nodes=submitNodes();var top=window.innerHeight;var found=false;for(var i=0;i<nodes.length;i++){var el=nodes[i];if(!el||isSearchBtn(el)||!visible(el))continue;var r=el.getBoundingClientRect();var st=getComputedStyle(el);var bottomPinned=(st.position==='fixed'||st.position==='sticky');if(r.top<window.innerHeight*0.45)continue;if(!(isSubmitLike(el)||(bottomPinned&&r.width>window.innerWidth*0.28&&r.height>=28)))continue;if(r.top<top){top=r.top;found=true;}}return found?top:null;}" +
            "function calcBottom(){var anchorTop=findBottomAnchor();if(anchorTop===null)return 132;var gap=52;var desired=Math.round(window.innerHeight-anchorTop+gap);var maxBottom=Math.max(136,window.innerHeight-96);return Math.min(desired,maxBottom);}" +
            "function placeToast(bottom){var nodes=document.querySelectorAll('div');for(var i=0;i<nodes.length;i++){var el=nodes[i];var txt=textOf(el);if(txt.indexOf('【隐私】')===0&&visible(el)){el.style.bottom=(bottom+72)+'px';}}}" +
            "function apply(){var buttons=searchButtons();if(!buttons.length)return;var bottom=calcBottom();for(var i=0;i<buttons.length;i++){var btn=buttons[i];normalizeSearchBtn(btn);btn.style.position='fixed';btn.style.right='12px';btn.style.left='auto';btn.style.bottom=bottom+'px';btn.style.transform='none';btn.style.zIndex='99999';btn.style.maxWidth='calc(100vw - 24px)';btn.style.boxSizing='border-box';}placeToast(bottom);}" +
            "window.addEventListener('resize',apply,true);" +
            "window.addEventListener('scroll',apply,true);" +
            "try{new MutationObserver(function(){apply();}).observe(document.documentElement||document.body,{childList:true,subtree:true,attributes:true});}catch(e){}" +
            "setTimeout(apply,120);setTimeout(apply,800);setTimeout(apply,1800);apply();" +
            "return 'installed';" +
            "})()";

    /**
     * 自动搜题监听器 — 当触发模式为 auto 时注入
     *
     * 策略：页面有题目时，首次延迟 1200ms 调用一次 __starxTriggerSearch()，
     * 随后监听 DOM 变化（.TiMu 等容器），题目变化时再次触发，节流 1800ms。
     * 用户仍可通过悬浮按钮或实体键手动触发；此监听只补齐"首次+翻题"自动化。
     */
    private static final String AUTO_SEARCH_WATCHER_JS =
            "(function(){" +
            "if(window.__starxAutoSearchReady)return 'ready';" +
            "window.__starxAutoSearchReady=true;" +
            "var ROOTS='" + ".TiMu,.tiMu,.singleQuesId,[id^=\\\"question\\\"],.questionLi,.Cy_TItle,.queBox,.mark_item,.questionItem,.exam-item,.pad_question,.subjectDet,.mark_name,.question-wrap,.topic-item,.question-list li" + "';" +
            "var lastSig='';var lastFireAt=0;var MIN_GAP=1800;" +
            "function visible(el){if(!el||!el.getBoundingClientRect)return false;var r=el.getBoundingClientRect();if(r.width<8||r.height<8)return false;var st=getComputedStyle(el);return st.display!=='none'&&st.visibility!=='hidden';}" +
            "function sig(){try{var nodes=document.querySelectorAll(ROOTS);if(!nodes||!nodes.length)return '';var ids=[];for(var i=0;i<nodes.length&&i<6;i++){var n=nodes[i];if(!visible(n))continue;var t=(n.innerText||n.textContent||'').replace(/\\s+/g,' ').trim().substring(0,60);ids.push((n.id||'')+'|'+t);}return ids.join('#');}catch(e){return '';}}" +
            "function fire(reason){try{var now=Date.now();if(now-lastFireAt<MIN_GAP)return;var s=sig();if(!s||s===lastSig)return;lastSig=s;lastFireAt=now;if(typeof window.__starxTriggerSearch==='function'){try{window._starx&&window._starx.log&&window._starx.log('auto:'+reason);}catch(_e){}window.__starxTriggerSearch();}}catch(e){}}" +
            "setTimeout(function(){fire('init');},1200);" +
            "setTimeout(function(){fire('retry');},2600);" +
            "try{new MutationObserver(function(muts){var relevant=false;for(var i=0;i<muts.length&&!relevant;i++){var m=muts[i];if(!m.target)continue;var cls=(m.target.className||'')+'';if(/TiMu|tiMu|questionLi|singleQuesId|queBox|mark_item|questionItem|exam-item|question-wrap|topic-item/.test(cls)){relevant=true;break;}if(m.addedNodes&&m.addedNodes.length){for(var j=0;j<m.addedNodes.length;j++){var node=m.addedNodes[j];if(!node||node.nodeType!==1)continue;if(node.matches&&node.matches(ROOTS)){relevant=true;break;}if(node.querySelector&&node.querySelector(ROOTS)){relevant=true;break;}}}}if(relevant)fire('mut');}).observe(document.documentElement||document.body,{childList:true,subtree:true});}catch(e){}" +
            "return 'installed';" +
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
        hookPromptBridge();
        hookWebViewExam();
        hookHardwareSearchTrigger();
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
            Method setWebChromeClient = WebView.class.getDeclaredMethod("setWebChromeClient", WebChromeClient.class);
            module.hook(setWebChromeClient).intercept(chain -> {
                WebChromeClient client = (WebChromeClient) chain.getArg(0);
                if (client != null) hookPromptBridge(client.getClass());
                return chain.proceed();
            });

            // Hook loadUrl — 在页面导航开始前注册 JavascriptInterface
            // addJavascriptInterface 必须在页面加载前注册，否则对已加载页面不可见
            Method loadUrl = WebView.class.getDeclaredMethod("loadUrl", String.class);
            module.hook(loadUrl).intercept(chain -> {
                WebView wv = (WebView) chain.getThisObject();
                String url = (String) chain.getArg(0);
                if (isExamEnabled() && url != null && shouldProbeExamPage(url)) {
                    ensureBridgeRegistered(wv);
                }
                return chain.proceed();
            });

            Method onPageFinished = android.webkit.WebViewClient.class.getDeclaredMethod(
                    "onPageFinished", WebView.class, String.class);
            module.hook(onPageFinished).intercept(chain -> {
                Object result = chain.proceed();
                if (!isExamEnabled()) return result;
                String url = (String) chain.getArg(1);
                if (url != null && isExamUrl(url)) {
                    WebView wv = (WebView) chain.getArg(0);
                    Logx.i("ExamHook: exam page loaded: " + url);
                    // 确保 bridge 已注册（覆盖非 loadUrl 入口的场景）
                    ensureBridgeRegistered(wv);
                    mainHandler.postDelayed(() -> injectStarXScript(wv), 1500);
                    mainHandler.postDelayed(() -> injectStarXScript(wv), 4000);
                    // HTML 管线：抓 outerHTML → jsoup 解析 → 查答 → 自动填写
                    mainHandler.postDelayed(() -> launchHtmlPipeline(wv), HTML_PIPELINE_CAPTURE_DELAY_MS);
                    // 注入独立悬浮 AI 搜题按钮（带标签）
                    mainHandler.postDelayed(() -> injectFloatingAiButton(wv), 1800);
                    mainHandler.postDelayed(() -> injectFloatingAiButton(wv), 4200);
                } else if (url != null && shouldProbeExamPage(url)) {
                    WebView wv = (WebView) chain.getArg(0);
                    ensureBridgeRegistered(wv);
                    mainHandler.postDelayed(() -> probeAndInjectExamPage(wv, url), 1200);
                    mainHandler.postDelayed(() -> probeAndInjectExamPage(wv, url), 3200);
                }
                return result;
            });
            Logx.i("ExamHook: WebView exam hook ready");
        } catch (Throwable t) {
            Logx.w("ExamHook: WebView hook failed: " + t.getMessage());
        }
    }

    /** 确保 _starx bridge 已注册到 WebView（幂等） */
    private void ensureBridgeRegistered(WebView wv) {
        int id = System.identityHashCode(wv);
        if (registeredWebViews.add(id)) {
            wv.addJavascriptInterface(new StarXJsBridge(wv), "_starx");
            wv.addJavascriptInterface(new StarXJsBridge(wv), "_starxNative");
            Logx.i("ExamHook: bridge registered on WebView@" + Integer.toHexString(id));
        }
    }

    private void hookPromptBridge() {
        hookPromptBridge(WebChromeClient.class);
        try {
            Method onJsPrompt = WebChromeClient.class.getDeclaredMethod(
                    "onJsPrompt", WebView.class, String.class, String.class, String.class, JsPromptResult.class);
            module.hook(onJsPrompt).intercept(chain -> {
                WebView webView = (WebView) chain.getArg(0);
                String message = (String) chain.getArg(2);
                String defaultValue = (String) chain.getArg(3);
                JsPromptResult promptResult = (JsPromptResult) chain.getArg(4);
                if (tryHandlePromptBridge(webView, message, defaultValue, promptResult)) {
                    return true;
                }
                return chain.proceed();
            });
            Logx.i("ExamHook: prompt bridge fallback hooked");
        } catch (Throwable t) {
            Logx.w("ExamHook: prompt bridge fallback hook failed: " + t.getMessage());
        }
    }

    private void hookPromptBridge(Class<?> clientClass) {
        if (clientClass == null || !hookedPromptClients.add(clientClass)) return;
        try {
            Method method = findMethodInHierarchy(clientClass,
                    "onJsPrompt",
                    WebView.class,
                    String.class,
                    String.class,
                    String.class,
                    JsPromptResult.class);
            if (method == null) return;
            module.hook(method).intercept(chain -> {
                WebView webView = (WebView) chain.getArg(0);
                String message = (String) chain.getArg(2);
                String defaultValue = (String) chain.getArg(3);
                JsPromptResult promptResult = (JsPromptResult) chain.getArg(4);
                if (tryHandlePromptBridge(webView, message, defaultValue, promptResult)) {
                    return true;
                }
                return chain.proceed();
            });
            Logx.i("ExamHook: prompt bridge hooked on " + clientClass.getName());
        } catch (Throwable t) {
            Logx.w("ExamHook: prompt bridge hook failed on " + clientClass.getName() + ": " + t.getMessage());
        }
    }

    private boolean tryHandlePromptBridge(WebView webView, String message, String defaultValue, JsPromptResult promptResult) {
        if (!PROMPT_BRIDGE_DEFAULT.equals(defaultValue) || message == null) return false;
        final String requestMessage = message;
        promptExecutor.execute(() -> handlePromptBridgeAsync(webView, requestMessage, promptResult));
        return true;
    }

    private void handlePromptBridgeAsync(WebView webView, String message, JsPromptResult promptResult) {
        String result = "";
        String method = "";
        long startedAt = System.currentTimeMillis();
        try {
            JSONObject request = new JSONObject(message);
            method = request.optString("method", "");
            JSONObject payload = request.optJSONObject("payload");
            if (payload == null) payload = new JSONObject();
            switch (method) {
                case "queryAnswer":
                    result = queryAnswerWithOptionsInternal(
                            webView,
                            payload.optString("question", null),
                            -1,
                            null,
                            PROMPT_QUERY_TIMEOUT_MS);
                    break;
                case "queryAnswerWithOptions":
                    result = queryAnswerWithOptionsInternal(
                            webView,
                            payload.optString("question", null),
                            payload.optInt("type", -1),
                            payload.optString("options", null),
                            PROMPT_QUERY_TIMEOUT_MS);
                    break;
                case "queryAnswerWithImages":
                    result = queryAnswerWithImagesInternal(
                            webView,
                            payload.optString("question", null),
                            payload.optInt("type", -1),
                            payload.optString("options", null),
                            payload.optString("imageUrls", null),
                            PROMPT_IMAGE_QUERY_TIMEOUT_MS);
                    break;
                case "notifyStatus":
                    notifyStatus(webView, payload.optString("message", ""), payload.optInt("durationMs", 0));
                    break;
                case "kickHtmlPipeline":
                    // JS 侧按钮触发 HTML 管线（不走截屏/OCR），绕开旧 doSearch 的 MediaProjection 路径
                    mainHandler.post(() -> {
                        lastHtmlPipelineRunAt.remove(System.identityHashCode(webView));
                        launchHtmlPipeline(webView);
                    });
                    result = "ok";
                    break;
                case "log":
                    Logx.i("ExamHook[JS]: " + payload.optString("msg", ""));
                    break;
                default:
                    Logx.w("ExamHook: unknown prompt bridge method: " + method);
                    break;
            }
        } catch (Throwable t) {
            Logx.w("ExamHook: prompt bridge parse failed: " + t.getMessage());
        }
        final String safeMethod = method.isEmpty() ? "unknown" : method;
        final String safeResult = result != null ? result : "";
        final long costMs = System.currentTimeMillis() - startedAt;
        mainHandler.post(() -> {
            try {
                promptResult.confirm(safeResult);
            } catch (Throwable t) {
                Logx.w("ExamHook: prompt result confirm failed: " + t.getMessage());
            }
        });
        Logx.i("ExamHook: prompt bridge handled " + safeMethod + " in " + costMs + "ms");
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
                                        // 尝试把答案直接写回当前考试 WebView —— 避免用户再手动对照勾选
                                        mainHandler.post(() -> applyOcrAnswerToActiveWebView(queryText, answer));
                                    }

                                    @Override
                                    public void onFailed() {
                                        Logx.w("ExamHook: OCR search found no answer");
                                        if (context != null) {
                                            mainHandler.post(() -> showStatusOverlay(
                                                    context,
                                                    "OCR 搜题未找到答案",
                                                    STATUS_OVERLAY_DEFAULT_MS));
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
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("javascript:") || lower.startsWith("about:blank")) return false;
        for (String hint : EXAM_URL_HINTS) {
            if (lower.contains(hint)) return true;
        }
        return (lower.contains("chaoxing.com") || lower.contains("xuexi365.com"))
                && (lower.contains("courseid=") || lower.contains("classid=") || lower.contains("cpi="))
                && (lower.contains("work") || lower.contains("exam") || lower.contains("test") || lower.contains("homework"));
    }

    private boolean shouldProbeExamPage(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("javascript:") || lower.startsWith("about:blank")) return false;
        if (!lower.startsWith("http")) return false;
        return isExamUrl(lower) || lower.contains("chaoxing.com") || lower.contains("xuexi365.com");
    }

    private void probeAndInjectExamPage(WebView webView, String url) {
        if (webView == null || !isExamEnabled()) return;
        try {
            webView.evaluateJavascript(buildQuestionPageProbeJs(), value -> {
                String normalized = value != null ? value.trim() : "0";
                if ("1".equals(normalized)) {
                    Logx.i("ExamHook: question probe matched => " + url);
                    injectStarXScript(webView);
                }
            });
        } catch (Throwable t) {
            Logx.w("ExamHook: question probe failed: " + t.getMessage());
        }
    }

    private String buildQuestionPageProbeJs() {
        return "(function(){"
                + "try{" 
                + "function examHref(h){if(!h)return false;h=String(h).toLowerCase();return h.indexOf('work/index_wap.html')>=0||h.indexOf('work/task-list')>=0||h.indexOf('work/stu-work')>=0||h.indexOf('work/task/library')>=0||h.indexOf('mooc-ans/exam/phone/task-list')>=0||h.indexOf('exam/phone/selftest-list')>=0||h.indexOf('/exam/')>=0||h.indexOf('/test/')>=0||h.indexOf('phone/moocanalysis/selfscoredetail')>=0;}"
                + "function textOf(node){return ((node&&(node.innerText||node.textContent||node.value))||'').replace(/\\s+/g,' ').trim();}"
                + "var roots=document.querySelectorAll(" + jsonEscapeForJs(QUESTION_ROOT_SELECTORS) + ");"
                + "if(roots&&roots.length>0)return 1;"
                + "var iframes=document.querySelectorAll('iframe');"
                + "for(var i=0;i<iframes.length;i++){try{var doc=iframes[i].contentDocument||iframes[i].contentWindow.document;if(doc&&doc.querySelector(" + jsonEscapeForJs(QUESTION_ROOT_SELECTORS) + "))return 1;var href=((doc&&doc.location&&doc.location.href)||'');if(examHref(href))return 1;}catch(e){}}"
                + "var options=document.querySelectorAll(" + jsonEscapeForJs(QUESTION_OPTION_SELECTORS) + ");"
                + "var submitLike=0;var nodes=document.querySelectorAll('button,input[type=submit],input[type=button],.btnBlue,.submitBtn,.answerSub,.nextBtn,.saveBtn,[role=button]');"
                + "for(var j=0;j<nodes.length;j++){var txt=textOf(nodes[j]).toLowerCase();if(/提交|交卷|保存|确认|下一题|下一步|submit|finish|next|save/.test(txt)){submitLike=1;break;}}"
                + "var bodyText=textOf(document.body).toLowerCase();"
                + "if(options.length>=2&&submitLike&&(bodyText.indexOf('作业')>=0||bodyText.indexOf('考试')>=0||bodyText.indexOf('测验')>=0||bodyText.indexOf('homework')>=0||bodyText.indexOf('exam')>=0))return 1;"
                + "return 0;"
                + "}catch(e){return 0;}"
                + "})()";
    }

    private void injectStarXScript(WebView webView) {
        if (webView == null || !isExamEnabled()) return;
        trackActiveExamWebView(webView);
        try {
            // 首先注入反切屏检测 JS — 必须在考试 JS 之前
            webView.evaluateJavascript(VISIBILITY_BYPASS_JS, null);
            Logx.i("ExamHook: visibility bypass JS injected");
        } catch (Throwable t) {
            Logx.w("ExamHook: visibility bypass inject failed: " + t.getMessage());
        }
        try {
            ensureBridgeRegistered(webView);
            String normalizedJsInject = normalizeInjectedUiScript(jsInject);

            webView.evaluateJavascript(PROMPT_BRIDGE_BOOTSTRAP, value ->
                    Logx.i("ExamHook: prompt bridge injected: " + value));
            webView.evaluateJavascript(QUERY_UPGRADE_SHIM, null);

            String questionSelectors = QUESTION_ROOT_SELECTORS;
            String mainUiInjectJs =
                "(function(){" +
                "try{" +
                "  var hasQuestions=!!document.querySelector(" + jsonEscapeForJs(questionSelectors) + ");" +
                "  var iframeCount=document.querySelectorAll('iframe').length;" +
                "  if(!hasQuestions&&iframeCount>0)return 'skip_main_ui:iframes='+iframeCount;" +
                "}catch(e){}" +
                normalizedJsInject +
                "return 'main_ui_injected';" +
                "})()";
            webView.evaluateJavascript(mainUiInjectJs, value ->
                    Logx.i("ExamHook: main search ui inject: " + value));
            webView.evaluateJavascript(QUERY_MONITOR_JS, value ->
                    Logx.i("ExamHook: query monitor injected: " + value));

            if (EXAM_TRIGGER_AUTO.equals(readExamTriggerMode())) {
                webView.evaluateJavascript(AUTO_SEARCH_WATCHER_JS, value ->
                        Logx.i("ExamHook: auto search watcher: " + value));
            }

            // 向所有同源 iframe 注入桥接；若 iframe 内实际有题，则恢复独立搜题按钮
            String iframeInjectJs =
                "(function(){" +
                "var iframes=document.querySelectorAll('iframe');" +
                "var count=0,uiCount=0;" +
                "var mainHasQuestions=false;" +
                "function isExamHref(href){if(!href)return false;href=String(href).toLowerCase();return href.indexOf('work/index_wap.html')>=0||href.indexOf('work/task-list')>=0||href.indexOf('work/stu-work')>=0||href.indexOf('work/task/library')>=0||href.indexOf('mooc-ans/exam/phone/task-list')>=0||href.indexOf('exam/phone/selftest-list')>=0||href.indexOf('/exam/')>=0||href.indexOf('/test/')>=0||href.indexOf('phone/moocanalysis/selfscoredetail')>=0;}" +
                "try{mainHasQuestions=!!document.querySelector(" + jsonEscapeForJs(questionSelectors) + ");}catch(e){}" +
                "for(var i=0;i<iframes.length;i++){" +
                "  try{" +
                "    var doc=iframes[i].contentDocument||iframes[i].contentWindow.document;" +
                "    var parent=(doc.head||doc.documentElement||doc.body);" +
                "    if(!doc||!parent)continue;" +
                "    var bridge=doc.createElement('script');" +
                "    bridge.textContent=" + jsonEscapeForJs(PROMPT_BRIDGE_BOOTSTRAP + ";" + QUERY_UPGRADE_SHIM + ";" + QUERY_MONITOR_JS) + ";" +
                "    parent.appendChild(bridge);" +
                "    count++;" +
                "    var href='';" +
                "    var hasQuestions=false;" +
                "    try{" +
                "      href=((doc.location&&doc.location.href)||'').toLowerCase();" +
                "      hasQuestions=!!doc.querySelector(" + jsonEscapeForJs(questionSelectors) + ");" +
                "    }catch(e){}" +
                "    if(!mainHasQuestions&&(hasQuestions||isExamHref(href))){" +
                "      var ui=doc.createElement('script');" +
                "      ui.textContent=" + jsonEscapeForJs(normalizedJsInject) + ";" +
                "      parent.appendChild(ui);" +
                "      uiCount++;" +
                "    }" +
                "  }catch(e){}" +
                "}" +
                "return 'injected_iframes='+count+',ui='+uiCount+',mainHasQuestions='+(mainHasQuestions?1:0);" +
                "})()";
            webView.evaluateJavascript(iframeInjectJs, value ->
                Logx.i("ExamHook: iframe inject: " + value));

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
            return queryAnswerWithOptionsInternal(webView, question, -1, null, JS_BRIDGE_QUERY_TIMEOUT_MS);
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
            return queryAnswerWithImagesInternal(webView, question, type, options, imageUrls, JS_BRIDGE_IMAGE_QUERY_TIMEOUT_MS);
        }
        @android.webkit.JavascriptInterface
        public String queryAnswerWithOptions(String question, int type, String options) {
            return queryAnswerWithOptionsInternal(webView, question, type, options, JS_BRIDGE_QUERY_TIMEOUT_MS);
        }

        @android.webkit.JavascriptInterface
        public void notifyStatus(String message, int durationMs) {
            ExamHook.this.notifyStatus(webView, message, durationMs);
        }

        @android.webkit.JavascriptInterface
        public void log(String msg) { Logx.i("ExamHook[JS]: " + msg); }

        /** 手动按钮触发的 HTML 管线入口：抓 outerHTML → jsoup → AnswerProvider → 注入答案。 */
        @android.webkit.JavascriptInterface
        public void kickHtmlPipeline() {
            try {
                mainHandler.post(() -> {
                    lastHtmlPipelineRunAt.remove(System.identityHashCode(webView));
                    launchHtmlPipeline(webView);
                });
            } catch (Throwable t) {
                Logx.w("ExamHook[JS→Java]: kickHtmlPipeline failed: " + t.getMessage());
            }
        }
    }

    private String queryAnswerWithImagesInternal(WebView webView,
                                                 String question,
                                                 int type,
                                                 String options,
                                                 String imageUrls,
                                                 long timeoutMs) {
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

        java.util.List<String> imgList = null;
        if (hasImages) {
            imgList = new java.util.ArrayList<>();
            for (String u : imageUrls.split("\\n")) {
                String trimmed = u.trim();
                if (!trimmed.isEmpty()) imgList.add(trimmed);
            }
            if (imgList.isEmpty()) imgList = null;
        }

        AnswerProvider.Result r = answerProvider.queryWithTimeout(q, type, options, imgList, timeoutMs);
        if (r != null) {
            Logx.i("★ 答案[" + r.source + "] => " + r.answer);
            return r.answer;
        }
        return "";
    }

    private String queryAnswerWithOptionsInternal(WebView webView,
                                                  String question,
                                                  int type,
                                                  String options,
                                                  long timeoutMs) {
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
                AnswerProvider.Result r = answerProvider.queryWithTimeout(question, type, options, timeoutMs);
            if (r != null) {
                Logx.i("★ 答案[" + r.source + "] => " + r.answer);
                return r.answer;
            }
            return "";
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
    private View statusOverlayView;
    private Runnable statusOverlayDismissRunnable;

    /**
     * 在安全浮窗中显示答案文本。
     * 使用 FLAG_SECURE 窗口，录屏和截屏时该窗口不可见。
     * 5 秒后自动消失。
     */
    private void showAnswerOverlay(Context context, String text) {
        Activity activity = SecureOverlay.asActivity(context);
        if (activity == null || activity.isFinishing()) {
            Logx.w("ExamHook: skip insecure answer overlay, activity unavailable");
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
        }
    }

    private void showStatusOverlay(Context context, String text, long durationMs) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        Activity activity = SecureOverlay.asActivity(context);
        if (activity == null || activity.isFinishing()) {
            Logx.w("ExamHook: skip insecure status overlay, activity unavailable: " + text);
            return;
        }

        if (statusOverlayDismissRunnable != null) {
            mainHandler.removeCallbacks(statusOverlayDismissRunnable);
            statusOverlayDismissRunnable = null;
        }
        if (statusOverlayView != null) {
            try {
                SecureOverlay.removeSecureView(activity, statusOverlayView);
            } catch (Throwable ignored) {}
            statusOverlayView = null;
        }

        try {
            int dp8 = dpToPx(context, 8);
            int dp12 = dpToPx(context, 12);
            String displayText = text.startsWith("[隐私]") ? text : "[隐私] " + text;

            TextView tv = new TextView(context);
            tv.setText(displayText);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.argb(228, 18, 24, 38));
            bg.setCornerRadius(dpToPx(context, 18));
            tv.setBackground(bg);
            tv.setPadding(dp12, dp8, dp12, dp8);
            tv.setMaxWidth(dpToPx(context, 300));

            final Activity act = activity;
            final TextView overlay = tv;
            tv.setOnClickListener(v -> {
                try {
                    SecureOverlay.removeSecureView(act, overlay);
                } catch (Throwable ignored) {}
                if (statusOverlayView == overlay) {
                    statusOverlayView = null;
                }
            });

            SecureOverlay.addSecureView(activity, tv,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, dpToPx(context, 132));
            statusOverlayView = tv;

            long safeDurationMs = durationMs > 0L ? durationMs : STATUS_OVERLAY_DEFAULT_MS;
            statusOverlayDismissRunnable = () -> {
                if (statusOverlayView == overlay) {
                    try {
                        SecureOverlay.removeSecureView(act, overlay);
                    } catch (Throwable ignored) {}
                    statusOverlayView = null;
                }
            };
            mainHandler.postDelayed(statusOverlayDismissRunnable, safeDurationMs);
        } catch (Throwable t) {
            Logx.w("ExamHook: secure status overlay failed: " + t.getMessage());
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

    private static Method findMethodInHierarchy(Class<?> type, String name, Class<?>... parameterTypes) {
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
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

    private void hookHardwareSearchTrigger() {
        try {
            Method dispatchKeyEvent = Activity.class.getDeclaredMethod("dispatchKeyEvent", KeyEvent.class);
            module.hook(dispatchKeyEvent).intercept(chain -> {
                if (!isExamEnabled()) {
                    return chain.proceed();
                }
                KeyEvent event = (KeyEvent) chain.getArg(0);
                if (shouldConsumeHardwareSearch(event)) {
                    return true;
                }
                return chain.proceed();
            });
            Logx.i("ExamHook: hardware search trigger ready");
        } catch (Throwable t) {
            Logx.w("ExamHook: hardware search trigger hook failed: " + t.getMessage());
        }
    }

    private boolean shouldConsumeHardwareSearch(KeyEvent event) {
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) {
            return false;
        }
        String mode = readExamTriggerMode();
        int keyCode = event.getKeyCode();
        long now = System.currentTimeMillis();

        if (EXAM_TRIGGER_AUTO.equals(mode)) {
            lastVolumeUpPressedAt = 0L;
            return false;
        }

        if (EXAM_TRIGGER_VOLUME_UP_DOWN.equals(mode)) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                if (pickActiveExamWebView() == null) {
                    return false;
                }
                lastVolumeUpPressedAt = now;
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                boolean matched = lastVolumeUpPressedAt > 0L
                        && now - lastVolumeUpPressedAt <= VOLUME_COMBO_WINDOW_MS;
                lastVolumeUpPressedAt = 0L;
                return matched && triggerHardwareSearch();
            }
            lastVolumeUpPressedAt = 0L;
            return false;
        }

        lastVolumeUpPressedAt = 0L;
        if (EXAM_TRIGGER_VOLUME_UP.equals(mode)) {
            return keyCode == KeyEvent.KEYCODE_VOLUME_UP && triggerHardwareSearch();
        }
        return keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && triggerHardwareSearch();
    }

    private String readExamTriggerMode() {
        try {
            var prefs = module.getRemotePreferences(CONFIG_PREFS);
            String raw = prefs.getString(KEY_EXAM_TRIGGER, EXAM_TRIGGER_VOLUME_DOWN);
            if (EXAM_TRIGGER_VOLUME_UP.equals(raw)
                    || EXAM_TRIGGER_VOLUME_UP_DOWN.equals(raw)
                    || EXAM_TRIGGER_AUTO.equals(raw)) {
                return raw;
            }
        } catch (Throwable t) {
            Logx.w("ExamHook: read trigger mode failed: " + t.getMessage());
        }
        return EXAM_TRIGGER_VOLUME_DOWN;
    }

    private void trackActiveExamWebView(WebView webView) {
        if (webView == null) {
            return;
        }
        activeExamWebViews.put(System.identityHashCode(webView), new WeakReference<>(webView));
    }

    private WebView pickActiveExamWebView() {
        WebView fallback = null;
        java.util.List<Integer> staleIds = new java.util.ArrayList<>();
        for (java.util.Map.Entry<Integer, WeakReference<WebView>> entry : activeExamWebViews.entrySet()) {
            WebView webView = entry.getValue().get();
            if (webView == null) {
                staleIds.add(entry.getKey());
                continue;
            }
            // 若 WebView 已导航离开考试页（例如返回首页），从活动集合中移除，避免误触
            String currentUrl = null;
            try {
                currentUrl = webView.getUrl();
            } catch (Throwable ignored) {
            }
            if (currentUrl != null && !currentUrl.isEmpty() && !isExamUrl(currentUrl)) {
                staleIds.add(entry.getKey());
                continue;
            }
            if (webView.getWindowToken() != null && webView.isShown()) {
                return webView;
            }
            if (fallback == null && webView.getWindowToken() != null) {
                fallback = webView;
            }
        }
        for (Integer staleId : staleIds) {
            activeExamWebViews.remove(staleId);
        }
        return fallback;
    }

    private boolean triggerHardwareSearch() {
        WebView webView = pickActiveExamWebView();
        if (webView == null) {
            return false;
        }
        // 只走 HTML 管线：jsoup 解析 + AnswerProvider 直答。
        // 不再调用服务端 doSearch，避免其触发 MediaProjection 导致系统绿色录屏指示条。
        mainHandler.post(() -> {
            try {
                Toast.makeText(webView.getContext(), "StarX · AI 搜题中…", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
            lastHtmlPipelineRunAt.remove(System.identityHashCode(webView));
            launchHtmlPipeline(webView);
        });
        return true;
    }

    /**
     * 将 OCR 搜题得到的答案写回当前考试 WebView。
     *
     * 策略：在当前可见的考试 WebView 中，按 OCR 原文定位最匹配的题块，
     * 针对选择题点选文本/字母匹配的选项；判断题支持对/错、正确/错误、√/×。
     * 若页面没有题块或找不到答案则静默失败，外层 overlay 仍会提示用户。
     */
    private void applyOcrAnswerToActiveWebView(String ocrText, String answer) {
        applyAnswerToQuestionByText(ocrText, answer, org.xiyu.starx.util.HtmlQuestionExtractor.Type.UNKNOWN, -1);
    }

    /**
     * 在考试 WebView 页面上方添加原生悬浮 AI 搜题按钮（带 "AI" 标签）。
     *
     * 关键：使用 FLAG_SECURE 的 SecureOverlay（应用内子窗口）承载，
     *      录屏/截图时整个按钮显示为黑块，避免被"截屏到"。
     * 点击直接触发 HTML 管线，不触发 OCR 截屏。
     * 幂等：绑定到 Activity，若已存在则跳过；Activity 销毁时自动清理。
     */
    private void injectFloatingAiButton(WebView wv) {
        if (wv == null) return;
        try {
            Activity act = SecureOverlay.asActivity(wv.getContext());
            if (act == null) return;
            int actId = System.identityHashCode(act);
            if (aiButtonAttached.contains(actId)) return;

            final WebView webViewRef = wv;
            android.widget.LinearLayout bar = new android.widget.LinearLayout(act);
            bar.setOrientation(android.widget.LinearLayout.VERTICAL);
            bar.setGravity(Gravity.CENTER);
            bar.setPadding(dp(act, 2), dp(act, 2), dp(act, 2), dp(act, 2));

            TextView lbl = new TextView(act);
            lbl.setText("StarX");
            lbl.setTextColor(Color.parseColor("#8b5cf6"));
            lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            lbl.setTypeface(Typeface.DEFAULT_BOLD);
            GradientDrawable lblBg = new GradientDrawable();
            lblBg.setColor(Color.parseColor("#F2FFFFFF"));
            lblBg.setCornerRadius(dp(act, 8));
            lbl.setBackground(lblBg);
            lbl.setPadding(dp(act, 6), dp(act, 1), dp(act, 6), dp(act, 1));
            android.widget.LinearLayout.LayoutParams lblLp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lblLp.bottomMargin = dp(act, 4);
            lblLp.gravity = Gravity.END;
            bar.addView(lbl, lblLp);

            android.widget.LinearLayout btn = new android.widget.LinearLayout(act);
            btn.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            btn.setGravity(Gravity.CENTER_VERTICAL);
            btn.setPadding(dp(act, 10), dp(act, 8), dp(act, 12), dp(act, 8));
            GradientDrawable btnBg = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{Color.parseColor("#6366f1"), Color.parseColor("#8b5cf6")});
            btnBg.setCornerRadius(dp(act, 22));
            btn.setBackground(btnBg);
            btn.setElevation(dp(act, 6));

            TextView tag = new TextView(act);
            tag.setText("AI");
            tag.setTextColor(Color.WHITE);
            tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            tag.setTypeface(Typeface.DEFAULT_BOLD);
            GradientDrawable tagBg = new GradientDrawable();
            tagBg.setColor(Color.parseColor("#38FFFFFF"));
            tagBg.setCornerRadius(dp(act, 6));
            tag.setBackground(tagBg);
            tag.setPadding(dp(act, 5), dp(act, 1), dp(act, 5), dp(act, 1));
            android.widget.LinearLayout.LayoutParams tagLp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            tagLp.rightMargin = dp(act, 6);
            btn.addView(tag, tagLp);

            TextView label = new TextView(act);
            label.setText("搜题");
            label.setTextColor(Color.WHITE);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            btn.addView(label);
            bar.addView(btn);

            final TextView labelRef = label;
            final int[] downXY = new int[2];
            final boolean[] moved = {false};
            final int slop = dp(act, 6);
            btn.setOnTouchListener((v, ev) -> {
                switch (ev.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downXY[0] = (int) ev.getRawX();
                        downXY[1] = (int) ev.getRawY();
                        moved[0] = false;
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        int dx = (int) ev.getRawX() - downXY[0];
                        int dy = (int) ev.getRawY() - downXY[1];
                        if (Math.abs(dx) > slop || Math.abs(dy) > slop) {
                            moved[0] = true;
                            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) bar.getLayoutParams();
                            if (lp != null) {
                                lp.x = Math.max(0, lp.x - dx);
                                lp.y = Math.max(0, lp.y + dy);
                                downXY[0] = (int) ev.getRawX();
                                downXY[1] = (int) ev.getRawY();
                                try {
                                    WindowManager wm = (WindowManager) act.getSystemService(Context.WINDOW_SERVICE);
                                    wm.updateViewLayout(bar, lp);
                                } catch (Throwable ignored) {}
                            }
                        }
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_UP:
                        if (!moved[0]) {
                            v.performClick();
                        }
                        return true;
                }
                return false;
            });
            btn.setOnClickListener(v -> {
                try {
                    labelRef.setText("搜题中…");
                    mainHandler.postDelayed(() -> {
                        try { labelRef.setText("搜题"); } catch (Throwable ignored) {}
                    }, 3500L);
                    lastHtmlPipelineRunAt.remove(System.identityHashCode(webViewRef));
                    launchHtmlPipeline(webViewRef);
                } catch (Throwable t) {
                    Logx.w("ExamHook: AI button click failed: " + t.getMessage());
                }
            });

            WindowManager.LayoutParams lp = SecureOverlay.addSecureView(
                    act, bar, Gravity.END | Gravity.BOTTOM, dp(act, 14), dp(act, 120));
            aiButtonAttached.add(actId);
            // 挂钩 Activity 销毁时清理
            act.getApplication().registerActivityLifecycleCallbacks(
                    new android.app.Application.ActivityLifecycleCallbacks() {
                        @Override public void onActivityCreated(Activity a, android.os.Bundle s) {}
                        @Override public void onActivityStarted(Activity a) {}
                        @Override public void onActivityResumed(Activity a) {}
                        @Override public void onActivityPaused(Activity a) {}
                        @Override public void onActivityStopped(Activity a) {}
                        @Override public void onActivitySaveInstanceState(Activity a, android.os.Bundle s) {}
                        @Override public void onActivityDestroyed(Activity a) {
                            if (a == act) {
                                try { SecureOverlay.removeSecureView(act, bar); } catch (Throwable ignored) {}
                                aiButtonAttached.remove(actId);
                                try { a.getApplication().unregisterActivityLifecycleCallbacks(this); } catch (Throwable ignored) {}
                            }
                        }
                    });
            Logx.i("ExamHook: native AI button attached to activity#" + Integer.toHexString(actId));
        } catch (Throwable t) {
            Logx.w("ExamHook: native AI button attach failed: " + t.getMessage());
        }
    }

    private final java.util.Set<Integer> aiButtonAttached = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private static int dp(Context ctx, int v) {
        return (int) (ctx.getResources().getDisplayMetrics().density * v + 0.5f);
    }

    // ==========================================================
    //  HTML 管线：outerHTML → jsoup → AnswerProvider → JS 填答
    // ==========================================================

    private boolean isHtmlPipelineEnabled() {
        if (!isExamEnabled()) return false;
        try {
            var prefs = module.getRemotePreferences(CONFIG_PREFS);
            return prefs.getBoolean(KEY_EXAM_HTML_PIPELINE, true);
        } catch (Throwable t) {
            return true;
        }
    }

    /** 外部入口：onPageFinished 中，考试/作业页命中后触发。 */
    private void launchHtmlPipeline(WebView wv) {
        if (wv == null || !isHtmlPipelineEnabled()) return;
        int id = System.identityHashCode(wv);
        long now = android.os.SystemClock.uptimeMillis();
        Long last = lastHtmlPipelineRunAt.get(id);
        if (last != null && now - last < 3000L) {
            return; // 3s 内重复触发跳过
        }
        lastHtmlPipelineRunAt.put(id, now);
        try {
            wv.evaluateJavascript("(function(){try{return document.documentElement.outerHTML;}catch(e){return '';}})()",
                    value -> {
                        String html = decodeJsStringLiteral(value);
                        if (html == null || html.length() < 200) {
                            Logx.i("ExamHook: html pipeline — page too small, skip");
                            return;
                        }
                        promptExecutor.submit(() -> runHtmlPipeline(wv, html));
                    });
        } catch (Throwable t) {
            Logx.w("ExamHook: html pipeline capture failed: " + t.getMessage());
        }
    }

    /** 在后台线程执行：解析题目 → 逐题查答 → 主线程注入选择。 */
    private void runHtmlPipeline(WebView wv, String html) {
        try {
            List<org.xiyu.starx.util.HtmlQuestionExtractor.Question> questions =
                    org.xiyu.starx.util.HtmlQuestionExtractor.parse(html);
            if (questions == null || questions.isEmpty()) return;
            int total = Math.min(questions.size(), HTML_PIPELINE_MAX_QUESTIONS);
            int solved = 0;
            for (int i = 0; i < total; i++) {
                if (Thread.currentThread().isInterrupted()) break;
                var q = questions.get(i);
                if (q.stem == null || q.stem.isEmpty()) continue;
                String dedupKey = q.stem + "|" + (q.options == null ? 0 : q.options.size());
                if (!htmlPipelineSolvedStems.add(dedupKey)) {
                    continue; // 本会话内已查过
                }
                String optionsText = q.optionsAsText();
                int typeCode = q.type.legacyCode();
                AnswerProvider.Result r = answerProvider.queryWithTimeout(
                        q.stem, typeCode, optionsText, null, HTML_PIPELINE_ANSWER_TIMEOUT_MS);
                if (r == null || r.answer == null || r.answer.trim().isEmpty()) {
                    Logx.i("ExamHook: html pipeline miss → " + truncate(q.stem, 40));
                    continue;
                }
                solved++;
                String stem = q.stem;
                String ans = r.answer;
                var type = q.type;
                int idx = i;
                Logx.i("★ HTML管线[" + r.source + "] #" + (i + 1) + " => " + ans);
                mainHandler.post(() -> applyAnswerToQuestionByText(stem, ans, type, idx));
            }
            Logx.i("ExamHook: html pipeline done, solved " + solved + "/" + total);
        } catch (Throwable t) {
            Logx.w("ExamHook: html pipeline run failed: " + t.getMessage());
        }
    }

    /**
     * 按题干文本定位题块并点选/填写答案；增强了 Zepto 兼容（dispatch touchend + click）。
     * indexHint >= 0 时作为位置回退（当题干无法唯一匹配时按顺序取第 n 个题块）。
     */
    private void applyAnswerToQuestionByText(String stemText,
                                             String answer,
                                             org.xiyu.starx.util.HtmlQuestionExtractor.Type type,
                                             int indexHint) {
        if (answer == null || answer.trim().isEmpty()) return;
        WebView webView = pickActiveExamWebView();
        if (webView == null) return;
        try {
            String jsStem = jsonEscapeForJs(stemText == null ? "" : stemText);
            String jsAns = jsonEscapeForJs(answer);
            String jsType = jsonEscapeForJs(type == null ? "unknown" : type.name().toLowerCase(Locale.ROOT));
            String script =
                "(function(){try{" +
                "var stem=" + jsStem + ",ans=" + jsAns + ",type=" + jsType + ",idxHint=" + indexHint + ";" +
                "function norm(s){return String(s||'').replace(/\\s+/g,'').toLowerCase();}" +
                "function stripPrefix(s){return String(s||'').replace(/^\\s*[A-Za-z][\\.、:：\\s]\\s*/,'').trim();}" +
                "function firePress(el){if(!el)return;try{var r=el.getBoundingClientRect();var t=new Touch({identifier:Date.now(),target:el,clientX:r.left+2,clientY:r.top+2});var te=new TouchEvent('touchend',{bubbles:true,cancelable:true,touches:[],targetTouches:[],changedTouches:[t]});el.dispatchEvent(te);}catch(_e){}try{el.click();}catch(_e){}}" +
                "var roots=document.querySelectorAll('.TiMu,.tiMu,.singleQuesId,.questionLi,.queBox,.mark_item,.questionItem,.exam-item,.pad_question,.subjectDet,.answer-item');" +
                "if(!roots||!roots.length)return 'no_roots';" +
                "var best=null,bestScore=0;var stemN=norm(stem);" +
                "for(var i=0;i<roots.length;i++){var r=roots[i];var te=r.querySelector('.Zy_TItle,.mark_name,.stem,.q-title,.answer-title,h2.titType,.timuStyle');var t=norm(te?te.textContent:r.textContent);if(!t)continue;var score=0;if(stemN&&(t.indexOf(stemN)>=0||stemN.indexOf(t)>=0))score=3;else{var c=0,w=stemN.length;for(var k=0;k<w&&k<40;k++){if(t.indexOf(stemN.charAt(k))>=0)c++;}score=c/Math.max(1,Math.min(w,40));}if(score>bestScore){best=r;bestScore=score;}}" +
                "if(!best && idxHint>=0 && idxHint<roots.length)best=roots[idxHint];" +
                "if(!best)return 'no_match';" +
                "if(type==='fill_blank'||type==='short_answer'){" +
                "var inputs=best.querySelectorAll('input[name^=blank],textarea[name^=blank],input.ans_input,textarea.ans_input,div.ueditor-container textarea');" +
                "if(!inputs.length)inputs=best.querySelectorAll('input[type=text],textarea');" +
                "var parts=String(ans).split(/[\\|｜]/);" +
                "var filled=0;for(var i=0;i<inputs.length;i++){var v=(parts[i]||parts[parts.length-1]||'').trim();if(!v)continue;var el=inputs[i];try{el.focus();el.value=v;el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));filled++;}catch(_e){}}" +
                "return 'fill_blank='+filled;" +
                "}" +
                "if(type==='true_false'){" +
                "var t=String(ans).trim();var pos=(t==='对'||t==='正确'||t==='T'||t==='true'||t==='√'||t==='Y'||t==='yes'||t.toLowerCase()==='true');" +
                "var opts=best.querySelectorAll('li.fl_l,li.clearfix,.answerBg,.option-item,.option_li,.optionItem,div.judgeoption,.optionUl li');" +
                "for(var i=0;i<opts.length;i++){var raw=(opts[i].innerText||opts[i].textContent||'').trim();var isT=/对|正确|true|√|Yes/i.test(raw);var isF=/错|错误|false|×|No/i.test(raw);if((pos&&isT)||(!pos&&isF)){firePress(opts[i].querySelector('input,label,a,span')||opts[i]);return 'tf_clicked';}}" +
                "return 'tf_no_match';" +
                "}" +
                "var opts=best.querySelectorAll('li.fl_l,li.clearfix,.answerBg,.option-item,.option_li,.optionItem,div.singleChoice,div.mulChoice,.singleoption,.optionUl li');" +
                "if(!opts||!opts.length)return 'no_options';" +
                "var letters=(String(ans).match(/[A-Za-z]/g)||[]).map(function(c){return c.toUpperCase();});" +
                "var ansText=norm(stripPrefix(ans));var clicked=0;" +
                "for(var i=0;i<opts.length;i++){" +
                "var opt=opts[i];var raw=(opt.innerText||opt.textContent||'').trim();var letterMatch=(raw.match(/^\\s*([A-Za-z])[\\.、:：\\s]/)||[])[1];letterMatch=letterMatch?letterMatch.toUpperCase():'';" +
                "var textN=norm(stripPrefix(raw));var hit=false;" +
                "if(letterMatch&&letters.indexOf(letterMatch)>=0)hit=true;" +
                "else if(ansText&&textN&&(textN===ansText||textN.indexOf(ansText)>=0||ansText.indexOf(textN)>=0))hit=true;" +
                "if(hit){firePress(opt.querySelector('input,label,a,span')||opt);clicked++;}" +
                "}" +
                "return 'clicked='+clicked;" +
                "}catch(e){return 'err:'+e.message;}})()";
            webView.evaluateJavascript(script, value -> Logx.i("ExamHook: answer apply #" + indexHint + " => " + value));
        } catch (Throwable t) {
            Logx.w("ExamHook: answer apply failed: " + t.getMessage());
        }
    }

    private static String decodeJsStringLiteral(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || "null".equals(s)) return null;
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            s = s.substring(1, s.length() - 1);
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case '"': sb.append('"'); break;
                    case '\'': sb.append('\''); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'u':
                        if (i + 4 < s.length()) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException nfe) {
                                sb.append(n);
                            }
                        } else {
                            sb.append(n);
                        }
                        break;
                    default: sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    /**
     * [历史占位] 原 OCR→WebView 答题实现，已由 applyAnswerToQuestionByText 统一替代。
     * 保留该名以便日志 grep 和 diff 审计。
     */
    @SuppressWarnings("unused")
    private void applyOcrAnswerToActiveWebView_legacyPlaceholder(String ocrText, String answer) {
        if (answer == null || answer.trim().isEmpty()) return;
        WebView webView = pickActiveExamWebView();
        if (webView == null) return;
        try {
            String jsOcr = jsonEscapeForJs(ocrText == null ? "" : ocrText);
            String jsAns = jsonEscapeForJs(answer);
            String script =
                "(function(){try{" +
                "var ocr=" + jsOcr + ";var ans=" + jsAns + ";" +
                "function norm(s){return String(s||'').replace(/\\s+/g,'').toLowerCase();}" +
                "function stripOptPrefix(s){return String(s||'').replace(/^[\\s]*[A-Za-z][\\.、:：\\s]\\s*/,'').trim();}" +
                "var roots=document.querySelectorAll('.TiMu,.tiMu,.singleQuesId,.questionLi,.queBox,.mark_item,.questionItem,.exam-item,.pad_question');" +
                "if(!roots||!roots.length)return 'no_roots';" +
                "var ocrN=norm(ocr),best=null,bestScore=0;" +
                "for(var i=0;i<roots.length;i++){var r=roots[i];var te=r.querySelector('.Zy_TItle,.mark_name,.stem,.q-title');var t=norm((te?te.textContent:r.textContent));if(!t)continue;var score=0;if(ocrN&&(t.indexOf(ocrN)>=0||ocrN.indexOf(t)>=0))score=2;else{var c=0,w=ocrN.length;for(var k=0;k<w&&k<32;k++){if(t.indexOf(ocrN.charAt(k))>=0)c++;}score=c/Math.max(1,Math.min(w,32));}if(score>bestScore){best=r;bestScore=score;}}" +
                "if(!best)return 'no_match';" +
                "var opts=best.querySelectorAll('li.fl_l,li.clearfix,.answerBg,.option-item,.option_li,.radio_option,.checkbox_option,.optionUl li,.optionItem');" +
                "if(!opts||!opts.length)return 'no_options';" +
                "var letters=(ans.match(/[A-Za-z]/g)||[]).map(function(c){return c.toUpperCase();});" +
                "var ansText=norm(stripOptPrefix(ans));" +
                "var clicked=0;" +
                "for(var i=0;i<opts.length;i++){" +
                "var opt=opts[i];var raw=(opt.innerText||opt.textContent||'').trim();var letterMatch=(raw.match(/^\\s*([A-Za-z])[\\.、:：\\s]/)||[])[1];letterMatch=letterMatch?letterMatch.toUpperCase():'';" +
                "var textN=norm(stripOptPrefix(raw));" +
                "var hit=false;" +
                "if(letterMatch&&letters.indexOf(letterMatch)>=0)hit=true;" +
                "else if(ansText&&textN&&(textN===ansText||textN.indexOf(ansText)>=0||ansText.indexOf(textN)>=0))hit=true;" +
                "if(hit){var tgt=opt.querySelector('input,label,a,span')||opt;try{tgt.click();}catch(e){try{opt.click();}catch(_e){}}clicked++;}" +
                "}" +
                "return 'clicked='+clicked;" +
                "}catch(e){return 'err:'+e.message;}})()";
            webView.evaluateJavascript(script, value -> Logx.i("ExamHook: ocr apply => " + value));
        } catch (Throwable t) {
            Logx.w("ExamHook: ocr apply failed: " + t.getMessage());
        }
    }

    private void notifyStatus(WebView webView, String message, int durationMs) {
        if (webView == null || message == null || message.trim().isEmpty()) {
            return;
        }
        long safeDurationMs = durationMs > 0 ? durationMs : STATUS_OVERLAY_DEFAULT_MS;
        mainHandler.post(() -> showStatusOverlay(webView.getContext(), message.trim(), safeDurationMs));
    }

    private static final String[] UI_SCRIPT_EXPECTED_MARKERS = new String[]{
            "var btn=document.createElement('div');",
            "document.body.appendChild(btn);",
            "btn.onclick=function(){btn.innerHTML='\\uD83D\\uDD12 \\u641C\\u9898\\u4E2D';btn.style.pointerEvents='none';doSearch();};"
    };

    /**
     * 保底 polyfill —— 当服务端 JS 形态漂移导致所有预期模式都没命中时注入。
     * 保证 window.__starxTriggerSearch 可用（触发一次 doSearch），避免自动/实体键触发路径哑火。
     */
    private static final String INJECT_UI_FALLBACK =
            ";(function(){try{" +
            "if(typeof window.__starxTriggerSearch==='function')return;" +
            "window.__starxHasSearchTarget=function(){try{return typeof getQuestions==='function'&&getQuestions().length?1:0;}catch(e){return 0;}};" +
            "window.__starxTriggerSearch=function(){try{if(typeof doSearch==='function'){doSearch();return 'ok';}return 'missing:doSearch';}catch(e){return 'err:'+e.message;}};" +
            "}catch(_e){}})();";

    private static String normalizeInjectedUiScript(String script) {
        if (script == null || script.isEmpty()) return "";
        String mutated = script
                .replace("var btn=document.createElement('div');", "var btn={innerHTML:'',style:{pointerEvents:'auto',bottom:'0px'}};")
                .replace("document.body.appendChild(btn);", "")
                .replace("var toast=document.createElement('div');", "var toast={style:{display:'none',bottom:'0px'},textContent:''};")
                .replace("document.body.appendChild(toast);", "")
                .replace(
                        "function showToast(m,ms){toast.textContent='【隐私】'+m;toast.style.display='block';\n  setTimeout(function(){toast.style.display='none';},ms||3000);}",
                        "function showToast(m,ms){try{window._starx&&window._starx.notifyStatus&&window._starx.notifyStatus(String(m||''),ms||0);}catch(e){try{window._starx&&window._starx.log&&window._starx.log('status:'+m);}catch(_e){}}}")
                .replace(
                        "ans.indexOf(q.options[i].text.substring(2))>=0",
                        "(normalizeOptionText(q.options[i].text)&&ans.indexOf(normalizeOptionText(q.options[i].text))>=0)")
                .replace(
                        "btn.onclick=function(){btn.innerHTML='\\uD83D\\uDD12 \\u641C\\u9898\\u4E2D';btn.style.pointerEvents='none';doSearch();};",
                        "window.__starxHasSearchTarget=function(){try{return getQuestions().length?1:0;}catch(e){return 0;}};\nbtn.onclick=function(){return window.__starxTriggerSearch();};\nwindow.__starxTriggerSearch=function(){btn.innerHTML='搜题中';btn.style.pointerEvents='none';return doSearch();};")
                .replace("🔍 私密搜题", "搜题")
                .replace("🔍私密搜题", "搜题")
                .replace("🔍 搜题", "搜题")
                .replace("🔍搜题", "搜题")
                .replace("私密搜题", "搜题");

        // 如果核心模式全部没命中，说明服务端 JS 结构已变；记日志并追加 polyfill
        int matched = 0;
        for (String marker : UI_SCRIPT_EXPECTED_MARKERS) {
            if (script.contains(marker)) matched++;
        }
        if (matched == 0) {
            Logx.w("ExamHook: injected UI script markers all missed — applying fallback polyfill");
            return mutated + INJECT_UI_FALLBACK;
        }
        if (matched < UI_SCRIPT_EXPECTED_MARKERS.length) {
            Logx.w("ExamHook: injected UI script markers partial match " + matched + "/" + UI_SCRIPT_EXPECTED_MARKERS.length);
            return mutated + INJECT_UI_FALLBACK;
        }
        return mutated;
    }

    /** 将 Java 字符串转为 JS 字符串字面量（含引号），可安全嵌入 evaluateJavascript */
    private static String jsonEscapeForJs(String s) {
        if (s == null) return "''";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '<':  sb.append("\\x3c"); break;  // 防止 </script> 闭合
                default:   sb.append(c);
            }
        }
        sb.append('\'');
        return sb.toString();
    }
}
