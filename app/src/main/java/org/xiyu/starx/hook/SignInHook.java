package org.xiyu.starx.hook;

import android.app.Dialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.widget.Button;
import android.widget.EditText;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

import org.xiyu.starx.util.CxClasses;
import org.xiyu.starx.util.Logx;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.libxposed.api.XposedModule;

public class SignInHook {
        private static final String PREFS_SIGN = "sign_config";
        private static final String KEY_MANUAL_LAT = "fake_lat";
        private static final String KEY_MANUAL_LNG = "fake_lng";
        private static final String KEY_MANUAL_ADDR = "fake_addr";
        private static final String KEY_AUTO_ENABLED = "auto_target_enabled";
        private static final String KEY_AUTO_LAT = "auto_target_lat";
        private static final String KEY_AUTO_LNG = "auto_target_lng";
        private static final String KEY_AUTO_ADDR = "auto_target_addr";
        private static final String KEY_AUTO_SOURCE_URL = "auto_target_source_url";
        private static final String KEY_SIGN_CODE = "assist_sign_code";
        private static final String KEY_QR_PAYLOAD = "assist_qr_payload";
        private static final String KEY_H5_QR_PAYLOAD = "assist_qr_h5_payload";
        private static final String KEY_NATIVE_QR_PAYLOAD = "assist_qr_native_payload";
        private static final String KEY_PHOTO_URI = "assist_photo_uri";
        private static final String KEY_RUNTIME_SIGN_CODE = "assist_runtime_sign_code";
        private static final String KEY_RUNTIME_SIGN_CODE_AT = "assist_runtime_sign_code_at";
        private static final String KEY_LAST_MODE = "assist_last_mode";
        private static final String KEY_LAST_ACTION = "assist_last_action";
        private static final String KEY_LAST_URL = "assist_last_url";
        private static final String KEY_LAST_AT = "assist_last_at";
        private static final long[] AUTO_CAPTURE_DELAYS_MS = new long[]{600L, 1800L, 3600L};
        private static final long PHOTO_ASSIST_WINDOW_MS = 45000L;
        private static final long RUNTIME_SIGN_CODE_TTL_MS = 900000L;
        private static final String NATIVE_PASSWORD_DIALOG_CLASS = "com.chaoxing.mobile.conferencesw.view.PwdEnterConfirmDialog";
        private static final String NATIVE_PASSWORD_DIALOG_CLICK_CLASS = "com.chaoxing.mobile.conferencesw.view.PwdEnterConfirmDialog$c";
        private static final String NATIVE_PASSWORD_SOURCE = "native://meet/checkJoinPwd";

        // VIP sign assist
        private static final String KEY_VIP_TEACHER_LAT = "vip_teacher_lat";
        private static final String KEY_VIP_TEACHER_LNG = "vip_teacher_lng";
        private static final String KEY_VIP_TEACHER_ADDR = "vip_teacher_addr";
        private static final String KEY_VIP_LOCATION_RANGE = "vip_location_range";
        private static final String KEY_VIP_SIGN_TYPE = "vip_sign_type";
        private static final String KEY_VIP_ACTIVE_ID = "vip_last_active_id";
        private static final String KEY_VIP_CLASSMATE_PHOTO_ID = "vip_classmate_photo_id";
        private static final String KEY_VIP_FETCHED_AT = "vip_fetched_at";
        private static final long VIP_FETCH_STALE_MS = 300000L;
        private static final String VIP_API_BASE = "https://mobilelearn.chaoxing.com";
        private static final Pattern ACTIVE_ID_PATTERN = Pattern.compile("[?&](?:activeId|activePrimaryId)=(\\d+)", Pattern.CASE_INSENSITIVE);
        private static final Pattern OBJECT_ID_PATTERN = Pattern.compile("objectId[\"':=\\s]+(\\d{8,})");
        private static final Pattern IMG_SRC_OBJECT_PATTERN = Pattern.compile("src=\"[^\"]*?/(\\d{10,})(?:/|\\?|\")");
        private static final String SIGN_PAGE_ASSIST_JS = """
                        (function() {
                            try {
                                function readConfig() {
                                    try {
                                        var raw = window.__starxSignAssistConfig;
                                        if (!raw) return {};
                                        if (typeof raw === 'string') return JSON.parse(raw);
                                        return raw;
                                    } catch (e) {
                                        return {};
                                    }
                                }
                                function push(list, label, value) {
                                    if (value === undefined || value === null) return;
                                    var text = String(value);
                                    if (!text) return;
                                    list.push(label + '::' + text);
                                }
                                function collectFrom(win, prefix, list) {
                                    try {
                                        push(list, prefix + 'href', win.location && win.location.href);
                                    } catch (e) {}
                                    try {
                                        push(list, prefix + 'html', win.document && win.document.documentElement ? win.document.documentElement.outerHTML : '');
                                    } catch (e) {}
                                    var keys = ['activeDetail', 'activeInfo', 'active', 'signData', 'preSignData', 'pptSignData', 'pageData', 'originData', 'config', 'extData', 'locationData', 'signType', 'rcode', 'signCode', 'activePrimaryId'];
                                    for (var i = 0; i < keys.length; i++) {
                                        var key = keys[i];
                                        try {
                                            if (typeof win[key] !== 'undefined') {
                                                push(list, prefix + key, JSON.stringify(win[key]));
                                            }
                                        } catch (e) {}
                                    }
                                }
                                function normalizePair(a, b) {
                                    var lat = parseFloat(a);
                                    var lng = parseFloat(b);
                                    if (!isFinite(lat) || !isFinite(lng)) return null;
                                    if (Math.abs(lat) > 90 && Math.abs(lng) <= 90) {
                                        var temp = lat;
                                        lat = lng;
                                        lng = temp;
                                    }
                                    if (Math.abs(lat) > 90 || Math.abs(lng) > 180) return null;
                                    return { lat: lat, lng: lng };
                                }
                                function scorePair(pair, explicit, addr) {
                                    var score = explicit ? 100 : 10;
                                    if (pair.lat >= 3 && pair.lat <= 60 && pair.lng >= 70 && pair.lng <= 140) score += 20;
                                    if (addr) score += 5;
                                    return score;
                                }
                                function scanAddress(source) {
                                    var patterns = [
                                        /"(?:address|addr|locationText|positionText|locationName|signAddress|placeName)"\\s*[:=]\\s*"([^"\\\\]{2,120})"/ig,
                                        /(?:地址|地点|位置)[:：]\\s*([^<,"\\n]{2,120})/ig
                                    ];
                                    for (var i = 0; i < patterns.length; i++) {
                                        var match = patterns[i].exec(source);
                                        if (match && match[1]) return match[1].trim();
                                    }
                                    return '';
                                }
                                function scanSources(sources) {
                                    var best = { matched: false, lat: 0, lng: 0, addr: '', source: '' };
                                    var patterns = [
                                        {
                                            explicit: true,
                                            regex: /(?:lat|latitude|checkLat|locationLat|positionLat|signLat|gpsY)\\D{0,40}(-?\\d{1,3}\\.\\d+)\\D{0,120}(?:lng|lon|longitude|checkLng|locationLng|positionLng|signLng|gpsX)\\D{0,40}(-?\\d{1,3}\\.\\d+)/ig
                                        },
                                        {
                                            explicit: true,
                                            regex: /(?:lng|lon|longitude|checkLng|locationLng|positionLng|signLng|gpsX)\\D{0,40}(-?\\d{1,3}\\.\\d+)\\D{0,120}(?:lat|latitude|checkLat|locationLat|positionLat|signLat|gpsY)\\D{0,40}(-?\\d{1,3}\\.\\d+)/ig,
                                            swap: true
                                        },
                                        {
                                            explicit: false,
                                            regex: /(-?\\d{1,3}\\.\\d{4,})\\s*[,，]\\s*(-?\\d{1,3}\\.\\d{4,})/g
                                        }
                                    ];
                                    for (var s = 0; s < sources.length; s++) {
                                        var source = sources[s];
                                        var addr = scanAddress(source);
                                        for (var p = 0; p < patterns.length; p++) {
                                            var pattern = patterns[p];
                                            pattern.regex.lastIndex = 0;
                                            var match;
                                            while ((match = pattern.regex.exec(source)) !== null) {
                                                var pair = pattern.swap ? normalizePair(match[2], match[1]) : normalizePair(match[1], match[2]);
                                                if (!pair) continue;
                                                var score = scorePair(pair, pattern.explicit, addr);
                                                if (!best.matched || score > best.score) {
                                                    best = {
                                                        matched: true,
                                                        lat: pair.lat,
                                                        lng: pair.lng,
                                                        addr: addr,
                                                        source: source.slice(0, 200),
                                                        score: score
                                                    };
                                                }
                                            }
                                        }
                                    }
                                    delete best.score;
                                    return best;
                                }
                                function getText(win) {
                                    try {
                                        return win.document && win.document.body ? String(win.document.body.innerText || '') : '';
                                    } catch (e) {
                                        return '';
                                    }
                                }
                                function firstElement(win, selectors) {
                                    if (!win || !win.document) return null;
                                    for (var i = 0; i < selectors.length; i++) {
                                        try {
                                            var element = win.document.querySelector(selectors[i]);
                                            if (element) return element;
                                        } catch (e) {}
                                    }
                                    return null;
                                }
                                function setValue(element, value) {
                                    if (!element || !value) return false;
                                    var tag = String(element.tagName || '').toLowerCase();
                                    if (tag !== 'input' && tag !== 'textarea') return false;
                                    try {
                                        element.focus();
                                        element.value = value;
                                        element.dispatchEvent(new Event('input', { bubbles: true }));
                                        element.dispatchEvent(new Event('change', { bubbles: true }));
                                        return true;
                                    } catch (e) {
                                        return false;
                                    }
                                }
                                function clickElement(element) {
                                    if (!element) return false;
                                    try {
                                        element.click();
                                        return true;
                                    } catch (e) {
                                        try {
                                            var event = document.createEvent('MouseEvents');
                                            event.initEvent('click', true, true);
                                            element.dispatchEvent(event);
                                            return true;
                                        } catch (ignored) {
                                            return false;
                                        }
                                    }
                                }
                                function parseRcode(raw) {
                                    if (!raw) return '';
                                    raw = String(raw).trim();
                                    if (!raw) return '';
                                    if (/^\\{/.test(raw)) {
                                        try {
                                            var json = JSON.parse(raw);
                                            if (json && json.rcode) return String(json.rcode);
                                            return '';
                                        } catch (e) {}
                                    }
                                    var match = raw.match(/[?&]rcode=([^&#]+)/i);
                                    if (match && match[1]) {
                                        try {
                                            return decodeURIComponent(match[1]);
                                        } catch (e) {
                                            return match[1];
                                        }
                                    }
                                    if (/^[A-Za-z0-9_-]{6,}$/.test(raw) && raw.indexOf('http') !== 0) {
                                        return raw;
                                    }
                                    return '';
                                }
                                function normalizeSignCode(raw) {
                                    if (raw === undefined || raw === null) return '';
                                    raw = String(raw).trim().replace(/^["']+|["']+$/g, '');
                                    if (!raw || raw.length < 3 || raw.length > 16) return '';
                                    if (/https?:\\/\\//i.test(raw)) return '';
                                    if (/[一-龥]/.test(raw)) return '';
                                    if (/[\\s{}\\[\\]]/.test(raw)) return '';
                                    if (!/^[A-Za-z0-9_-]+$/.test(raw)) return '';
                                    if (/^(null|undefined|false|true)$/i.test(raw)) return '';
                                    return raw;
                                }
                                function scanSignCode(sources, text, html) {
                                    var pool = [];
                                    for (var i = 0; i < sources.length; i++) pool.push(sources[i]);
                                    if (text) pool.push(text);
                                    if (html) pool.push(html);
                                    var patterns = [
                                        /"(?:signCode|gestureCode|gesturePwd|signPwd|password|pwd)"\\s*[:=]\\s*"([^"\\\\]{3,16})"/ig,
                                        /(?:signCode|gestureCode|gesturePwd|signPwd|password|pwd)\\s*[:=]\\s*['"]?([A-Za-z0-9_-]{3,16})['"]?/ig,
                                        /(?:签到码|手势|口令|密码)[:：\\s]*([A-Za-z0-9_-]{3,16})/ig
                                    ];
                                    for (var p = 0; p < patterns.length; p++) {
                                        var pattern = patterns[p];
                                        for (var j = 0; j < pool.length; j++) {
                                            var source = String(pool[j] || '');
                                            pattern.lastIndex = 0;
                                            var match;
                                            while ((match = pattern.exec(source)) !== null) {
                                                var candidate = normalizeSignCode(match[1]);
                                                if (candidate) return candidate;
                                            }
                                        }
                                    }
                                    return '';
                                }
                                function scanRcode(sources) {
                                    for (var i = 0; i < sources.length; i++) {
                                        var token = parseRcode(sources[i]);
                                        if (token) return token;
                                    }
                                    return '';
                                }
                                function detectMode(text, html, url, sources, hasLocation) {
                                    var lower = (text + ' ' + html + ' ' + url + ' ' + sources.join(' ')).toLowerCase();
                                    if (lower.indexOf('拍照签到') >= 0 || lower.indexOf('上传照片') >= 0 || lower.indexOf('拍照上传') >= 0 || lower.indexOf('camera') >= 0 || lower.indexOf('type=file') >= 0) return 'photo';
                                    if (lower.indexOf('手势签到') >= 0 || lower.indexOf('签到码') >= 0 || lower.indexOf('口令签到') >= 0 || lower.indexOf('gesture') >= 0 || lower.indexOf('signcode') >= 0 || lower.indexOf('signpwd') >= 0) return 'gesture';
                                    if (lower.indexOf('二维码签到') >= 0 || lower.indexOf('扫码签到') >= 0 || lower.indexOf('rcode') >= 0 || lower.indexOf('qrcode') >= 0 || lower.indexOf('newsign/studentsign') >= 0) return 'qr';
                                    if (hasLocation || lower.indexOf('位置签到') >= 0 || lower.indexOf('location') >= 0 || lower.indexOf('gps') >= 0) return 'location';
                                    return 'general';
                                }
                                function findSubmit(win) {
                                    if (!win || !win.document) return null;
                                    var elements = win.document.querySelectorAll('button, a, div[role=button], span[role=button], input[type=button], input[type=submit]');
                                    for (var i = 0; i < elements.length; i++) {
                                        var element = elements[i];
                                        var text = String(element.innerText || element.value || element.textContent || '').replace(/\s+/g, '').toLowerCase();
                                        if (!text) continue;
                                        if (/取消|返回|关闭|拍照|相册|上传照片|重试|刷新/.test(text)) continue;
                                        if (/签到|提交|确认|确定|开始|进入/.test(text)) return element;
                                    }
                                    return null;
                                }
                                var config = readConfig();
                                var sources = [];
                                collectFrom(window, '', sources);
                                for (var i = 0; i < window.frames.length; i++) {
                                    try {
                                        collectFrom(window.frames[i], 'frame' + i + '_', sources);
                                    } catch (e) {}
                                }
                                var href = '';
                                var html = '';
                                try {
                                    href = window.location && window.location.href ? String(window.location.href) : '';
                                } catch (e) {}
                                try {
                                    html = window.document && window.document.documentElement ? String(window.document.documentElement.outerHTML || '') : '';
                                } catch (e) {}
                                var locationResult = scanSources(sources);
                                var text = getText(window);
                                var lower = (text + ' ' + html).toLowerCase();
                                var result = {
                                    signPage: true,
                                    locationMatched: !!locationResult.matched,
                                    lat: locationResult.lat || 0,
                                    lng: locationResult.lng || 0,
                                    addr: locationResult.addr || '',
                                    mode: detectMode(text, html, href, sources, !!locationResult.matched),
                                    needsPhoto: false,
                                    filledCode: false,
                                    filledQr: false,
                                    clickedSubmit: false,
                                    qrToken: '',
                                    detectedCode: '',
                                    usedDetectedCode: false,
                                    usedDetectedQr: false
                                };
                                var fileInput = firstElement(window, ['input[type=file]', 'input[accept*=image]']);
                                if (fileInput) {
                                    result.needsPhoto = true;
                                    if (result.mode === 'general') result.mode = 'photo';
                                }
                                var codeInput = firstElement(window, [
                                    'input[type=password]',
                                    'input[type=text][name*=sign]',
                                    'input[type=text][id*=sign]',
                                    'input[type=text][name*=pwd]',
                                    'input[type=text][id*=pwd]',
                                    'input[type=text][name*=password]',
                                    'input[type=text][id*=password]',
                                    'input[type=number]',
                                    'input[name*=gesture]',
                                    'input[id*=gesture]',
                                    'input[name*=signCode]',
                                    'input[id*=signCode]',
                                    'input[name*=signPwd]',
                                    'input[id*=signPwd]',
                                    'input[placeholder*=手势]',
                                    'input[placeholder*=口令]',
                                    'input[placeholder*=签到码]',
                                    'input[placeholder*=密码]',
                                    '.code input',
                                    '.signCode input',
                                    'textarea'
                                ]);
                                var qrInput = firstElement(window, [
                                    'input[name*=rcode]',
                                    'input[id*=rcode]',
                                    'input[placeholder*=二维码]',
                                    'textarea'
                                ]);
                                if (!codeInput && (lower.indexOf('签到码') >= 0 || lower.indexOf('手势') >= 0 || lower.indexOf('口令') >= 0 || lower.indexOf('密码') >= 0)) {
                                    codeInput = firstElement(window, [
                                        'input[type=text]',
                                        'input[type=number]',
                                        'textarea'
                                    ]);
                                }
                                var configuredCode = config.signCode ? String(config.signCode).trim() : '';
                                var detectedCode = configuredCode ? '' : scanSignCode(sources, text, html);
                                var signCode = configuredCode || detectedCode;
                                var configuredQr = parseRcode(config.qrPayload);
                                var detectedQr = '';
                                if (!configuredQr) {
                                    detectedQr = parseRcode(href);
                                    if (!detectedQr) detectedQr = scanRcode(sources);
                                }
                                var qrToken = configuredQr || detectedQr;
                                result.qrToken = qrToken;
                                result.detectedCode = detectedCode;
                                result.usedDetectedCode = !configuredCode && !!detectedCode;
                                result.usedDetectedQr = !configuredQr && !!detectedQr;
                                if (result.mode === 'general' && signCode && codeInput) result.mode = 'gesture';
                                if (result.mode === 'general' && qrToken && qrInput) result.mode = 'qr';
                                if (signCode && (result.mode === 'gesture' || lower.indexOf('签到码') >= 0 || lower.indexOf('手势') >= 0 || lower.indexOf('口令') >= 0 || lower.indexOf('密码') >= 0)) {
                                    result.filledCode = setValue(codeInput || qrInput, signCode);
                                }
                                if (qrToken && (result.mode === 'qr' || lower.indexOf('rcode') >= 0 || lower.indexOf('二维码') >= 0 || lower.indexOf('扫码') >= 0)) {
                                    result.filledQr = setValue(qrInput || codeInput, qrToken);
                                }
                                var submit = findSubmit(window);
                                if (submit && !result.needsPhoto && (result.filledCode || result.filledQr || result.mode === 'location' || lower.indexOf('立即签到') >= 0 || lower.indexOf('开始签到') >= 0)) {
                                    result.clickedSubmit = clickElement(submit);
                                }
                                return JSON.stringify(result);
                            } catch (error) {
                                return JSON.stringify({ signPage: false, error: String(error) });
                            }
                        })();
                        """;

    private final XposedModule module;
    private final ClassLoader cl;
        private final Set<String> pendingCaptureKeys = Collections.synchronizedSet(new HashSet<>());
        private final Set<String> pendingVipFetchActiveIds = Collections.synchronizedSet(new HashSet<>());
        private final Set<Class<?>> hookedChooserClients = Collections.synchronizedSet(new HashSet<>());
        private final Set<String> hookedChooserMethods = Collections.synchronizedSet(new HashSet<>());
        private final Set<Integer> autoSubmittedNativeDialogs = Collections.synchronizedSet(new HashSet<>());
        private final Map<Object, WebView> chromeClientOwners = Collections.synchronizedMap(new WeakHashMap<>());
        private final Map<Integer, Long> photoAssistWindows = new ConcurrentHashMap<>();
        private final Map<Integer, String> detectedSignModes = new ConcurrentHashMap<>();

    // 默认坐标 (可通过 RemotePreferences 配置)
    private double fakeLat = 0.0;
    private double fakeLng = 0.0;
    private String fakeAddr = "";
        private volatile String lastCapturedSignature = "";
        private volatile String lastAssistSignature = "";
        private volatile String lastVipFetchActiveId = "";
        private volatile long vipFreshCacheTime = 0L;
        private volatile boolean vipFreshCacheResult = false;
        private static final long VIP_FRESH_CACHE_MS = 100L;

    public SignInHook(XposedModule module, ClassLoader cl) {
        this.module = module;
        this.cl = cl;
    }

    public void hook() throws Throwable {
        loadConfig();
        hookBDLocation();
        hookMockGpsDetection();
        hookLocationManagerBackup();
        hookSignPageAutoCapture();
        hookNativeQrSign();
        hookNativePasswordSign();
        Logx.i("SignInHook: initialized");
    }

    private void loadConfig() {
        try {
            fakeLat = readDoublePref(KEY_MANUAL_LAT, 0.0);
            fakeLng = readDoublePref(KEY_MANUAL_LNG, 0.0);
            fakeAddr = readStringPref(KEY_MANUAL_ADDR, "");
            boolean autoEnabled = readAutoTargetEnabled();
            double autoLat = readDoublePref(KEY_AUTO_LAT, 0.0);
            double autoLng = readDoublePref(KEY_AUTO_LNG, 0.0);
            String autoAddr = readStringPref(KEY_AUTO_ADDR, "");
            if (fakeLat != 0.0 && fakeLng != 0.0) {
                Logx.i("SignInHook: loaded config lat=" + fakeLat + " lng=" + fakeLng + " addr=" + fakeAddr);
            } else if (autoEnabled && autoLat != 0.0 && autoLng != 0.0) {
                Logx.i("SignInHook: loaded auto target lat=" + autoLat + " lng=" + autoLng + " addr=" + autoAddr);
            } else {
                Logx.w("SignInHook: no location configured, location hooks will be passive");
            }
        } catch (Throwable t) {
            Logx.w("SignInHook: failed to load config: " + t.getMessage());
        }
    }

    private SharedPreferences getSignPrefs() {
        return module.getRemotePreferences(PREFS_SIGN);
    }

    private double readDoublePref(String key, double defaultValue) {
        try {
            return Double.longBitsToDouble(getSignPrefs().getLong(key, Double.doubleToLongBits(defaultValue)));
        } catch (Throwable t) {
            return defaultValue;
        }
    }

    private String readStringPref(String key, String defaultValue) {
        try {
            String value = getSignPrefs().getString(key, defaultValue);
            return value != null ? value : defaultValue;
        } catch (Throwable t) {
            return defaultValue;
        }
    }

    private boolean readBooleanPref(String key, boolean defaultValue) {
        try {
            return getSignPrefs().getBoolean(key, defaultValue);
        } catch (Throwable t) {
            return defaultValue;
        }
    }

    private boolean readAutoTargetEnabled() {
        return readBooleanPref(KEY_AUTO_ENABLED, true);
    }

    private double readFakeLat() {
        double manualLat = readDoublePref(KEY_MANUAL_LAT, fakeLat);
        if (manualLat != 0.0) {
            return manualLat;
        }
        if (readAutoTargetEnabled()) {
            double vipLat = readDoublePref(KEY_VIP_TEACHER_LAT, 0.0);
            if (vipLat != 0.0 && isVipDataFreshCached()) return vipLat;
            double autoLat = readDoublePref(KEY_AUTO_LAT, 0.0);
            if (autoLat != 0.0) return autoLat;
        }
        return fakeLat;
    }

    private double readFakeLng() {
        double manualLng = readDoublePref(KEY_MANUAL_LNG, fakeLng);
        if (manualLng != 0.0) {
            return manualLng;
        }
        if (readAutoTargetEnabled()) {
            double vipLng = readDoublePref(KEY_VIP_TEACHER_LNG, 0.0);
            if (vipLng != 0.0 && isVipDataFreshCached()) return vipLng;
            double autoLng = readDoublePref(KEY_AUTO_LNG, 0.0);
            if (autoLng != 0.0) return autoLng;
        }
        return fakeLng;
    }

    private String readFakeAddr() {
        String manualAddr = readStringPref(KEY_MANUAL_ADDR, fakeAddr);
        if (!manualAddr.isEmpty()) {
            return manualAddr;
        }
        if (readAutoTargetEnabled()) {
            String vipAddr = readStringPref(KEY_VIP_TEACHER_ADDR, "");
            if (!vipAddr.isEmpty() && isVipDataFreshCached()) return vipAddr;
            String autoAddr = readStringPref(KEY_AUTO_ADDR, "");
            if (!autoAddr.isEmpty()) return autoAddr;
        }
        return fakeAddr;
    }

    private boolean isVipDataFreshCached() {
        long now = System.currentTimeMillis();
        if (now - vipFreshCacheTime < VIP_FRESH_CACHE_MS) {
            return vipFreshCacheResult;
        }
        boolean fresh = isVipDataFresh();
        vipFreshCacheResult = fresh;
        vipFreshCacheTime = now;
        return fresh;
    }

    private boolean isVipDataFresh() {
        try {
            long fetchedAt = getSignPrefs().getLong(KEY_VIP_FETCHED_AT, 0L);
            if (fetchedAt <= 0L || System.currentTimeMillis() - fetchedAt >= VIP_FETCH_STALE_MS) {
                return false;
            }
            String storedActiveId = readStringPref(KEY_VIP_ACTIVE_ID, "");
            return !lastVipFetchActiveId.isEmpty() && lastVipFetchActiveId.equals(storedActiveId);
        } catch (Throwable t) {
            return false;
        }
    }

    private void hookBDLocation() throws Throwable {
        Class<?> bdLocationClass;
        try {
            bdLocationClass = Class.forName(CxClasses.BD_LOCATION, false, cl);
        } catch (ClassNotFoundException e) {
            Logx.w("SignInHook: BDLocation class not found, skip");
            return;
        }

        // Hook getLatitude()
        try {
            Method getLatitude = bdLocationClass.getDeclaredMethod("getLatitude");
            module.hook(getLatitude).intercept(chain -> {
                double lat = readFakeLat();
                if (lat == 0.0) return chain.proceed();
                return lat;
            });
            Logx.i("SignInHook: hooked BDLocation.getLatitude()");
        } catch (NoSuchMethodException e) {
            Logx.w("SignInHook: getLatitude not found");
        }

        // Hook getLongitude()
        try {
            Method getLongitude = bdLocationClass.getDeclaredMethod("getLongitude");
            module.hook(getLongitude).intercept(chain -> {
                double lng = readFakeLng();
                if (lng == 0.0) return chain.proceed();
                return lng;
            });
            Logx.i("SignInHook: hooked BDLocation.getLongitude()");
        } catch (NoSuchMethodException e) {
            Logx.w("SignInHook: getLongitude not found");
        }

        // Hook getAddrStr()
        try {
            Method getAddrStr = bdLocationClass.getDeclaredMethod("getAddrStr");
            module.hook(getAddrStr).intercept(chain -> {
                String addr = readFakeAddr();
                if (addr.isEmpty()) return chain.proceed();
                return addr;
            });
            Logx.i("SignInHook: hooked BDLocation.getAddrStr()");
        } catch (NoSuchMethodException e) {
            Logx.w("SignInHook: getAddrStr not found");
        }

        // Hook getLocType() — 伪造定位类型为成功 (161 = 网络定位成功)
        try {
            Method getLocType = bdLocationClass.getDeclaredMethod("getLocType");
            module.hook(getLocType).intercept(chain -> {
                if (readFakeLat() == 0.0) return chain.proceed();
                return 161;
            });
            Logx.i("SignInHook: hooked BDLocation.getLocType()");
        } catch (NoSuchMethodException e) {
            Logx.w("SignInHook: getLocType not found");
        }
    }

    /**
     * Hook MockGPS 检测方法 — 防止定位伪造被发现
     *
     * BDLocation 内置模拟检测:
     * - getMockGnssStrategy() > 0 → 认定为模拟定位
     * - getMockGnssProbability() > 0 → 模拟概率评分
     * - getReallLocation() → 当 strategy > 0 时返回真实位置 (蜜罐!)
     */
    private void hookMockGpsDetection() {
        Class<?> bdLocationClass;
        try {
            bdLocationClass = Class.forName(CxClasses.BD_LOCATION, false, cl);
        } catch (ClassNotFoundException e) {
            return;
        }

        // Hook getMockGnssStrategy() → 0 (无模拟)
        try {
            Method m = bdLocationClass.getDeclaredMethod("getMockGnssStrategy");
            module.hook(m).intercept(chain -> 0);
            Logx.i("SignInHook: hooked getMockGnssStrategy → 0");
        } catch (NoSuchMethodException ignored) {}

        // Hook getMockGnssProbability() → -1 (未知)
        try {
            Method m = bdLocationClass.getDeclaredMethod("getMockGnssProbability");
            module.hook(m).intercept(chain -> -1);
            Logx.i("SignInHook: hooked getMockGnssProbability → -1");
        } catch (NoSuchMethodException ignored) {}

        // Hook 已废弃但仍被调用的旧方法 (底层同一字段)
        try {
            Method m = bdLocationClass.getDeclaredMethod("getMockGpsStrategy");
            module.hook(m).intercept(chain -> 0);
            Logx.i("SignInHook: hooked getMockGpsStrategy → 0");
        } catch (NoSuchMethodException ignored) {}

        try {
            Method m = bdLocationClass.getDeclaredMethod("getMockGpsProbability");
            module.hook(m).intercept(chain -> -1);
            Logx.i("SignInHook: hooked getMockGpsProbability → -1");
        } catch (NoSuchMethodException ignored) {}

        // Hook getReallLocation() → null (蜜罐: 模拟时返回真实位置, 必须拦截!)
        try {
            Method m = bdLocationClass.getDeclaredMethod("getReallLocation");
            module.hook(m).intercept(chain -> null);
            Logx.i("SignInHook: hooked getReallLocation → null (honeypot blocked)");
        } catch (NoSuchMethodException ignored) {}
    }

    /**
     * Hook Android LocationManager 备用定位路径
     *
     * sign.util.b 直接调用 LocationManager.getLastKnownLocation()
     * 绕过百度 SDK, 必须在 framework 层拦截
     */
    private void hookLocationManagerBackup() {
        try {
            Method getLastKnown = LocationManager.class.getDeclaredMethod(
                    "getLastKnownLocation", String.class);
            module.hook(getLastKnown).intercept(chain -> {
                double lat = readFakeLat();
                double lng = readFakeLng();
                if (lat == 0.0 || lng == 0.0) return chain.proceed();
                Location fakeLocation = new Location("gps");
                fakeLocation.setLatitude(lat);
                fakeLocation.setLongitude(lng);
                fakeLocation.setAccuracy(30.0f);
                fakeLocation.setTime(System.currentTimeMillis());
                // 需要设置 elapsedRealtimeNanos 以通过验证
                fakeLocation.setElapsedRealtimeNanos(android.os.SystemClock.elapsedRealtimeNanos());
                Logx.i("SignInHook: faked LocationManager.getLastKnownLocation → ("
                        + lat + "," + lng + ")");
                return fakeLocation;
            });
            Logx.i("SignInHook: hooked LocationManager.getLastKnownLocation");
        } catch (Throwable t) {
            Logx.w("SignInHook: LocationManager hook failed: " + t.getMessage());
        }
    }

    private void hookSignPageAutoCapture() {
        try {
            Method setWebChromeClient = WebView.class.getDeclaredMethod("setWebChromeClient", WebChromeClient.class);
            module.hook(setWebChromeClient).intercept(chain -> {
                WebView webView = (WebView) chain.getThisObject();
                WebChromeClient client = (WebChromeClient) chain.getArg(0);
                if (webView != null && client != null) {
                    chromeClientOwners.put(client, webView);
                    hookFileChooserBridge(client.getClass());
                }
                return chain.proceed();
            });

            Method loadUrl = WebView.class.getDeclaredMethod("loadUrl", String.class);
            module.hook(loadUrl).intercept(chain -> {
                WebView webView = (WebView) chain.getThisObject();
                String url = (String) chain.getArg(0);
                Object result = chain.proceed();
                scheduleAutoCapture(webView, url);
                return result;
            });

            Method onPageFinished = android.webkit.WebViewClient.class.getDeclaredMethod(
                    "onPageFinished", WebView.class, String.class);
            module.hook(onPageFinished).intercept(chain -> {
                Object result = chain.proceed();
                WebView webView = (WebView) chain.getArg(0);
                String url = (String) chain.getArg(1);
                scheduleAutoCapture(webView, url);
                return result;
            });
            Logx.i("SignInHook: sign page auto-capture ready");
        } catch (Throwable t) {
            Logx.w("SignInHook: sign page auto-capture hook failed: " + t.getMessage());
        }
    }

    private void scheduleAutoCapture(WebView webView, String url) {
        if (webView == null || !shouldRunSignAssist() || !isSignUrl(url)) {
            return;
        }
        String stableUrl = url != null ? url : "";
        int webViewId = System.identityHashCode(webView);
        for (long delayMs : AUTO_CAPTURE_DELAYS_MS) {
            String key = webViewId + "|" + stableUrl + "|" + delayMs;
            if (!pendingCaptureKeys.add(key)) {
                continue;
            }
            webView.postDelayed(() -> {
                try {
                    extractAutoLocation(webView, stableUrl);
                } finally {
                    pendingCaptureKeys.remove(key);
                }
            }, delayMs);
        }
        scheduleVipFetch(webView, stableUrl);
    }

    private boolean isSignUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("pptsign")
                || lower.contains("gotasksign")
                || lower.contains("presign")
                || lower.contains("newsign")
                || lower.contains("studentsign")
                || lower.contains("producesignview")
                || lower.contains("widget/sign")
                || lower.contains("/sign/");
    }

    private void extractAutoLocation(WebView webView, String fallbackUrl) {
        try {
            String currentUrl = webView.getUrl();
            String effectiveUrl = (currentUrl != null && !currentUrl.isEmpty()) ? currentUrl : fallbackUrl;
            if (!isSignUrl(effectiveUrl)) {
                return;
            }
            webView.evaluateJavascript(buildSignAssistJs(), value -> handleSignAssistResult(webView, value, effectiveUrl));
        } catch (Throwable t) {
            Logx.w("SignInHook: auto-capture inject failed: " + t.getMessage());
        }
    }

    private void handleSignAssistResult(WebView webView, String jsValue, String sourceUrl) {
        try {
            String decoded = decodeJsString(jsValue);
            if (decoded.isEmpty()) {
                return;
            }
            JSONObject json = new JSONObject(decoded);
            int webViewId = System.identityHashCode(webView);
            String mode = normalizeSignMode(json.optString("mode", ""));
            if (!mode.isEmpty()) {
                detectedSignModes.put(webViewId, mode);
            }

            if (json.optBoolean("needsPhoto", false) || "photo".equals(mode)) {
                photoAssistWindows.put(webViewId, System.currentTimeMillis() + PHOTO_ASSIST_WINDOW_MS);
            } else {
                photoAssistWindows.remove(webViewId);
            }

            if (readAutoTargetEnabled() && json.optBoolean("locationMatched", false)) {
                double lat = json.optDouble("lat", 0.0);
                double lng = json.optDouble("lng", 0.0);
                if (isValidCoordinate(lat, lng)) {
                    String addr = json.optString("addr", "").trim();
                    persistAutoLocation(lat, lng, addr, sourceUrl);
                }
            }

            String detectedCode = json.optString("detectedCode", "");
            if (!detectedCode.isEmpty()) {
                persistDetectedSignCode(detectedCode, sourceUrl);
            }

            persistAssistState(mode, buildAssistAction(json), sourceUrl);
        } catch (Throwable t) {
            Logx.w("SignInHook: auto-capture parse failed: " + t.getMessage());
        }
    }

    private String decodeJsString(String jsValue) {
        if (jsValue == null || jsValue.isEmpty() || "null".equals(jsValue)) {
            return "";
        }
        try {
            return new JSONArray("[" + jsValue + "]").getString(0);
        } catch (Throwable t) {
            return jsValue;
        }
    }

    private boolean isValidCoordinate(double lat, double lng) {
        return lat >= -90.0 && lat <= 90.0 && lng >= -180.0 && lng <= 180.0
                && !(lat == 0.0 && lng == 0.0);
    }

    private void persistAutoLocation(double lat, double lng, String addr, String sourceUrl) {
        String signature = String.format(Locale.US, "%.6f,%.6f|%s", lat, lng, addr);
        synchronized (this) {
            if (signature.equals(lastCapturedSignature)) {
                return;
            }
            lastCapturedSignature = signature;
        }
        try {
            getSignPrefs()
                    .edit()
                    .putLong(KEY_AUTO_LAT, Double.doubleToLongBits(lat))
                    .putLong(KEY_AUTO_LNG, Double.doubleToLongBits(lng))
                    .putString(KEY_AUTO_ADDR, addr)
                    .putString(KEY_AUTO_SOURCE_URL, sourceUrl != null ? sourceUrl : "")
                    .apply();
            Logx.i("SignInHook: auto-captured sign target lat=" + lat + " lng=" + lng
                    + " addr=" + addr + " from=" + sourceUrl);
        } catch (Throwable t) {
            Logx.w("SignInHook: failed to persist auto target: " + t.getMessage());
        }
    }

    private boolean shouldRunSignAssist() {
        return true;
    }

    private String readConfiguredSignCode() {
        return normalizeAssistSignCode(readStringPref(KEY_SIGN_CODE, ""));
    }

    private String readLegacyQrPayload() {
        return readStringPref(KEY_QR_PAYLOAD, "").trim();
    }

    private String readConfiguredH5QrPayload() {
        String directValue = readStringPref(KEY_H5_QR_PAYLOAD, "").trim();
        if (!directValue.isEmpty()) {
            return directValue;
        }
        String legacy = readLegacyQrPayload();
        return normalizeQrJsonPayload(legacy).isEmpty() ? legacy : "";
    }

    private String readConfiguredNativeQrPayload() {
        String directValue = readStringPref(KEY_NATIVE_QR_PAYLOAD, "").trim();
        if (!directValue.isEmpty()) {
            return directValue;
        }
        String legacy = readLegacyQrPayload();
        return normalizeQrJsonPayload(legacy).isEmpty() ? "" : legacy;
    }

    private String readConfiguredPhotoUri() {
        return readStringPref(KEY_PHOTO_URI, "").trim();
    }

    private String readPreferredNativeSignCode() {
        String configured = readConfiguredSignCode();
        if (!configured.isEmpty()) {
            return configured;
        }
        String detected = normalizeAssistSignCode(readStringPref(KEY_RUNTIME_SIGN_CODE, ""));
        if (detected.isEmpty()) {
            return "";
        }
        long detectedAt = 0L;
        try {
            detectedAt = getSignPrefs().getLong(KEY_RUNTIME_SIGN_CODE_AT, 0L);
        } catch (Throwable ignored) {
        }
        if (detectedAt <= 0L || System.currentTimeMillis() - detectedAt > RUNTIME_SIGN_CODE_TTL_MS) {
            return "";
        }
        return detected;
    }

    private String buildSignAssistJs() {
        JSONObject config = new JSONObject();
        try {
            config.put("signCode", readConfiguredSignCode());
            config.put("qrPayload", readConfiguredH5QrPayload());
            config.put("autoSubmit", true);
        } catch (Throwable ignored) {
        }
        return "window.__starxSignAssistConfig=" + config.toString() + ";\n" + SIGN_PAGE_ASSIST_JS;
    }

    private String normalizeSignMode(String mode) {
        if (mode == null || mode.isEmpty()) {
            return "";
        }
        String lower = mode.toLowerCase(Locale.ROOT);
        if (lower.contains("photo")) return "photo";
        if (lower.contains("gesture")) return "gesture";
        if (lower.contains("qr")) return "qr";
        if (lower.contains("location")) return "location";
        return "general";
    }

    private String normalizeAssistSignCode(String rawCode) {
        if (rawCode == null) {
            return "";
        }
        String trimmed = rawCode.trim().replaceAll("^[\"']+|[\"']+$", "");
        if (trimmed.length() < 3 || trimmed.length() > 16) {
            return "";
        }
        if (!trimmed.matches("^[A-Za-z0-9_-]+$")) {
            return "";
        }
        if (trimmed.equalsIgnoreCase("null")
                || trimmed.equalsIgnoreCase("undefined")
                || trimmed.equalsIgnoreCase("true")
                || trimmed.equalsIgnoreCase("false")) {
            return "";
        }
        return trimmed;
    }

    private String buildAssistAction(JSONObject json) {
        StringBuilder builder = new StringBuilder();
        boolean usedDetectedCode = json.optBoolean("usedDetectedCode", false);
        boolean usedDetectedQr = json.optBoolean("usedDetectedQr", false);
        appendAction(builder, usedDetectedCode, "已自动提取口令");
        appendAction(builder, usedDetectedQr, "已自动提取二维码");
        appendAction(builder, json.optBoolean("filledCode", false), usedDetectedCode ? "已填入口令" : "已填充口令");
        appendAction(builder, json.optBoolean("filledQr", false), usedDetectedQr ? "已填入二维码" : "已填充二维码");
        appendAction(builder, json.optBoolean("clickedSubmit", false), "已触发签到");
        appendAction(builder, json.optBoolean("needsPhoto", false), "等待上传照片");
        if (builder.length() == 0 && json.optBoolean("locationMatched", false)) {
            builder.append("已识别定位页");
        }
        if (builder.length() == 0 && !json.optString("detectedCode", "").isEmpty()) {
            builder.append("已识别签到码");
        }
        if (builder.length() == 0 && !json.optString("qrToken", "").isEmpty()) {
            builder.append("检测到二维码参数");
        }
        if (builder.length() == 0) {
            builder.append("已识别签到页");
        }
        return builder.toString();
    }

    private void persistDetectedSignCode(String rawCode, String sourceUrl) {
        String code = normalizeAssistSignCode(rawCode);
        if (code.isEmpty()) {
            return;
        }
        try {
            getSignPrefs()
                    .edit()
                    .putString(KEY_RUNTIME_SIGN_CODE, code)
                    .putLong(KEY_RUNTIME_SIGN_CODE_AT, System.currentTimeMillis())
                    .apply();
            Logx.i("SignInHook: cached detected sign code from=" + sourceUrl);
        } catch (Throwable t) {
            Logx.w("SignInHook: failed to cache detected sign code: " + t.getMessage());
        }
    }

    private void appendAction(StringBuilder builder, boolean enabled, String text) {
        if (!enabled) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(" / ");
        }
        builder.append(text);
    }

    private void persistAssistState(String mode, String action, String sourceUrl) {
        String modeLabel = formatModeLabel(mode);
        String safeAction = action != null ? action : "";
        String safeUrl = sourceUrl != null ? sourceUrl : "";
        String signature = modeLabel + "|" + safeAction + "|" + safeUrl;
        if (signature.equals(lastAssistSignature)) {
            return;
        }
        lastAssistSignature = signature;
        try {
            getSignPrefs()
                    .edit()
                    .putString(KEY_LAST_MODE, modeLabel)
                    .putString(KEY_LAST_ACTION, safeAction)
                    .putString(KEY_LAST_URL, safeUrl)
                    .putLong(KEY_LAST_AT, System.currentTimeMillis())
                    .apply();
            Logx.i("SignInHook: detected sign mode=" + modeLabel + " action=" + safeAction + " url=" + safeUrl);
        } catch (Throwable t) {
            Logx.w("SignInHook: failed to persist sign assist state: " + t.getMessage());
        }
    }

    private String formatModeLabel(String mode) {
        if (mode == null || mode.isEmpty()) return "";
        if ("photo".equals(mode)) return "拍照签到";
        if ("gesture".equals(mode)) return "手势签到";
        if ("qr".equals(mode)) return "二维码签到";
        if ("location".equals(mode)) return "位置签到";
        if ("general".equals(mode)) return "通用签到页";
        // VIP code passes already-formatted Chinese labels — return as-is
        if (mode.contains("签到") || "VIP".equals(mode)) return mode;
        return "";
    }

    // ========== VIP Sign Assist ==========

    private void scheduleVipFetch(WebView webView, String url) {
        String activeId = extractActiveId(url);
        if (activeId == null || activeId.isEmpty()) return;
        boolean sameActivity = activeId.equals(lastVipFetchActiveId);
        lastVipFetchActiveId = activeId;
        if (sameActivity && isVipDataFresh()) return;
        if (!pendingVipFetchActiveIds.add(activeId)) return;
        new Thread(() -> {
            try {
                fetchVipSignData(activeId, webView);
            } catch (Throwable t) {
                Logx.w("SignInHook: VIP fetch error: " + t.getMessage());
            } finally {
                pendingVipFetchActiveIds.remove(activeId);
            }
        }, "starx-vip-fetch").start();
    }

    private String extractActiveId(String url) {
        if (url == null) return null;
        Matcher m = ACTIVE_ID_PATTERN.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private void fetchVipSignData(String activeId, WebView webView) {
        // 0. Clear stale VIP data from previous activity
        try {
            getSignPrefs().edit()
                    .remove(KEY_VIP_TEACHER_LAT)
                    .remove(KEY_VIP_TEACHER_LNG)
                    .remove(KEY_VIP_TEACHER_ADDR)
                    .remove(KEY_VIP_LOCATION_RANGE)
                    .remove(KEY_VIP_CLASSMATE_PHOTO_ID)
                    .remove(KEY_VIP_SIGN_TYPE)
                    .remove(KEY_VIP_FETCHED_AT)
                    .apply();
        } catch (Throwable ignored) {}

        // 1. Fetch activity info from API
        JSONObject info = vipHttpGetJson(VIP_API_BASE + "/v2/apis/active/getPPTActiveInfo?activeId=" + activeId);
        if (info == null) {
            Logx.w("SignInHook: VIP getPPTActiveInfo failed for " + activeId);
            return;
        }
        JSONObject data = info.has("data") ? info.optJSONObject("data") : info;
        if (data == null) data = info;

        int otherId = data.optInt("otherId", -1);
        int ifphoto = data.optInt("ifphoto", 0);
        int attendNum = data.optInt("attendNum", 0);

        // 2. Extract teacher preset location
        double teacherLat = parseCoordStr(data.optString("latitude", "-1"));
        double teacherLng = parseCoordStr(data.optString("longitude", "-1"));
        String teacherAddr = data.optString("locationText", "").trim();
        int locationRange = data.optInt("locationRange", 0);

        // 3. Detect sign code from activity info fields
        String foundCode = "";
        for (String field : new String[]{"signCode", "signPwd", "gestureCode", "rcode"}) {
            String val = normalizeAssistSignCode(data.optString(field, ""));
            if (!val.isEmpty()) { foundCode = val; break; }
        }
        final String detectedSignCode = foundCode;

        // 4. Persist VIP data
        try {
            SharedPreferences.Editor editor = getSignPrefs().edit();
            editor.putString(KEY_VIP_ACTIVE_ID, activeId);
            editor.putInt(KEY_VIP_SIGN_TYPE, otherId);
            editor.putLong(KEY_VIP_FETCHED_AT, System.currentTimeMillis());
            if (isValidCoordinate(teacherLat, teacherLng)) {
                editor.putLong(KEY_VIP_TEACHER_LAT, Double.doubleToLongBits(teacherLat));
                editor.putLong(KEY_VIP_TEACHER_LNG, Double.doubleToLongBits(teacherLng));
                editor.putString(KEY_VIP_TEACHER_ADDR, teacherAddr);
                editor.putInt(KEY_VIP_LOCATION_RANGE, locationRange);
            }
            editor.apply();
        } catch (Throwable t) {
            Logx.w("SignInHook: VIP persist failed: " + t.getMessage());
        }

        StringBuilder action = new StringBuilder();
        String typeLabel = formatSignTypeLabel(otherId);
        if (!typeLabel.isEmpty()) action.append("类型=").append(typeLabel);

        // 5. Handle per-type VIP logic
        if (isValidCoordinate(teacherLat, teacherLng)) {
            Logx.i("SignInHook: VIP teacher location: " + teacherLat + "," + teacherLng
                    + " addr=" + teacherAddr + " range=" + locationRange);
            if (action.length() > 0) action.append(" / ");
            action.append("已获取教师预设位置");
        }

        if (!detectedSignCode.isEmpty()) {
            persistDetectedSignCode(detectedSignCode, "vip-api:" + activeId);
            if (action.length() > 0) action.append(" / ");
            action.append("已获取签到码");
        }

        // 6. Photo borrowing: fetch classmate objectId
        boolean isPhoto = (otherId == 0 || ifphoto == 1);
        if (isPhoto) {
            String classmatePhotoId = fetchClassmatePhotoId(activeId);
            if (!classmatePhotoId.isEmpty()) {
                try {
                    getSignPrefs().edit()
                            .putString(KEY_VIP_CLASSMATE_PHOTO_ID, classmatePhotoId)
                            .apply();
                } catch (Throwable ignored) {}
                if (action.length() > 0) action.append(" / ");
                action.append("已获取同学照片");
                // Trigger direct sign with borrowed photo on UI thread
                if (webView != null) {
                    webView.post(() -> attemptVipDirectPhotoSign(webView, activeId, classmatePhotoId));
                }
            }
        }

        // 7. For gesture/signCode: attempt direct sign via JS
        if ((otherId == 3 || otherId == 5) && !detectedSignCode.isEmpty()) {
            final String codeTypeLabel = (otherId == 3) ? "手势签到" : "签到码签到";
            if (webView != null) {
                webView.post(() -> attemptVipDirectCodeSign(webView, activeId, detectedSignCode, codeTypeLabel));
            }
        }

        if (action.length() == 0) action.append("已获取活动信息");
        persistAssistState(typeLabel.isEmpty() ? "VIP" : typeLabel, action.toString(), "vip-api:" + activeId);
    }

    private String formatSignTypeLabel(int otherId) {
        switch (otherId) {
            case 0: return "拍照签到";
            case 1: return "普通签到";
            case 2: return "二维码签到";
            case 3: return "手势签到";
            case 4: return "位置签到";
            case 5: return "签到码签到";
            default: return "";
        }
    }

    private double parseCoordStr(String str) {
        if (str == null || str.isEmpty() || "-1".equals(str.trim())) return 0.0;
        try {
            return Double.parseDouble(str.trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private String fetchClassmatePhotoId(String activeId) {
        // Try analysis page to find already-signed students' photo objectIds
        String[] endpoints = {
                VIP_API_BASE + "/pptSign/analysis?vs=1&DB_STRATEGY=PRIMARY_KEY&STRATEGY_PARA=activeId&activeId=" + activeId,
                VIP_API_BASE + "/pptSign/analysis2?DB_STRATEGY=PRIMARY_KEY&STRATEGY_PARA=activeId&activeId=" + activeId
        };
        for (String endpoint : endpoints) {
            try {
                String html = vipHttpGetText(endpoint);
                if (html == null || html.length() < 50) continue;
                // Look for objectId in HTML
                Matcher m1 = OBJECT_ID_PATTERN.matcher(html);
                if (m1.find()) {
                    Logx.i("SignInHook: VIP found classmate photo objectId=" + m1.group(1));
                    return m1.group(1);
                }
                Matcher m2 = IMG_SRC_OBJECT_PATTERN.matcher(html);
                if (m2.find()) {
                    Logx.i("SignInHook: VIP found classmate photo from img src=" + m2.group(1));
                    return m2.group(1);
                }
            } catch (Throwable t) {
                Logx.w("SignInHook: VIP analysis fetch failed: " + t.getMessage());
            }
        }
        return "";
    }

    private void attemptVipDirectPhotoSign(WebView webView, String activeId, String objectId) {
        if (webView == null || !webView.isAttachedToWindow() || activeId == null || activeId.isEmpty()
                || objectId == null || objectId.isEmpty()) {
            return;
        }
        try {
            String currentUrl = webView.getUrl();
            if (!isSignUrl(currentUrl)) {
                Logx.w("SignInHook: VIP photo direct sign skipped — WebView navigated away: " + currentUrl);
                return;
            }
            double lat = readFakeLat();
            double lng = readFakeLng();
            String addr = readFakeAddr();
            String latStr = (lat != 0.0) ? String.format(Locale.US, "%.6f", lat) : "-1";
            String lngStr = (lng != 0.0) ? String.format(Locale.US, "%.6f", lng) : "-1";
            String addrEncoded = Uri.encode(addr).replace("'", "%27");
            String js = "(function(){" +
                    "try{" +
                    "var uid='';document.cookie.split(';').forEach(function(c){" +
                    "c=c.trim();if(c.indexOf('_uid=')===0||c.indexOf('UID=')===0)uid=c.split('=').slice(1).join('=');" +
                    "});" +
                    "if(!uid)return JSON.stringify({ok:false,r:'no_uid'});" +
                    "var url='/pptSign/stuSignajax?activeId=" + activeId +
                    "&uid='+uid+'&clientip=&useragent=&latitude=" + latStr +
                    "&longitude=" + lngStr +
                    "&fid=0&name=&address=" + addrEncoded +
                    "&objectId=" + objectId +
                    "&signCode=&enc=&appType=15&ifTiJiao=1';" +
                    "var xhr=new XMLHttpRequest();" +
                    "xhr.open('GET',url,false);" +
                    "xhr.send(null);" +
                    "if(xhr.status===200){return JSON.stringify({ok:true,result:xhr.responseText.substring(0,200)});}" +
                    "else{return JSON.stringify({ok:false,error:'HTTP '+xhr.status});}" +
                    "}catch(e){return JSON.stringify({ok:false,error:String(e)});}" +
                    "})()";
            webView.evaluateJavascript(js, value -> {
                String decoded = decodeJsString(value);
                Logx.i("SignInHook: VIP photo direct sign result: " + decoded);
                try {
                    JSONObject res = new JSONObject(decoded);
                    if (res.optBoolean("ok", false)) {
                        persistAssistState("拍照签到", "已借用同学照片直接签到: " + res.optString("result", ""), "vip:" + activeId);
                    } else {
                        Logx.w("SignInHook: VIP photo direct sign failed: " + res.optString("error", res.optString("r", "")));
                    }
                } catch (Throwable ignored) {}
            });
        } catch (Throwable t) {
            Logx.w("SignInHook: VIP direct photo sign failed: " + t.getMessage());
        }
    }

    private void attemptVipDirectCodeSign(WebView webView, String activeId, String signCode, String typeLabel) {
        if (webView == null || !webView.isAttachedToWindow() || activeId == null || activeId.isEmpty()
                || signCode == null || signCode.isEmpty()) {
            return;
        }
        try {
            String currentUrl = webView.getUrl();
            if (!isSignUrl(currentUrl)) {
                Logx.w("SignInHook: VIP code direct sign skipped — WebView navigated away: " + currentUrl);
                return;
            }
            double lat = readFakeLat();
            double lng = readFakeLng();
            String addr = readFakeAddr();
            String latStr = (lat != 0.0) ? String.format(Locale.US, "%.6f", lat) : "-1";
            String lngStr = (lng != 0.0) ? String.format(Locale.US, "%.6f", lng) : "-1";
            String addrEncoded = Uri.encode(addr).replace("'", "%27");
            String js = "(function(){" +
                    "try{" +
                    "var uid='';document.cookie.split(';').forEach(function(c){" +
                    "c=c.trim();if(c.indexOf('_uid=')===0||c.indexOf('UID=')===0)uid=c.split('=').slice(1).join('=');" +
                    "});" +
                    "if(!uid)return JSON.stringify({ok:false,r:'no_uid'});" +
                    "var url='/pptSign/stuSignajax?activeId=" + activeId +
                    "&uid='+uid+'&clientip=&useragent=&latitude=" + latStr +
                    "&longitude=" + lngStr +
                    "&fid=0&name=&address=" + addrEncoded +
                    "&objectId=&signCode=" + signCode +
                    "&enc=&appType=15&ifTiJiao=1';" +
                    "var xhr=new XMLHttpRequest();" +
                    "xhr.open('GET',url,false);" +
                    "xhr.send(null);" +
                    "if(xhr.status===200){return JSON.stringify({ok:true,result:xhr.responseText.substring(0,200)});}" +
                    "else{return JSON.stringify({ok:false,error:'HTTP '+xhr.status});}" +
                    "}catch(e){return JSON.stringify({ok:false,error:String(e)});}" +
                    "})()";
            webView.evaluateJavascript(js, value -> {
                String decoded = decodeJsString(value);
                Logx.i("SignInHook: VIP code direct sign result: " + decoded);
                try {
                    JSONObject res = new JSONObject(decoded);
                    if (res.optBoolean("ok", false)) {
                        persistAssistState(typeLabel, "已自动提交直签: " + res.optString("result", ""), "vip:" + activeId);
                    } else {
                        Logx.w("SignInHook: VIP code direct sign failed: " + res.optString("error", res.optString("r", "")));
                    }
                } catch (Throwable ignored) {}
            });
        } catch (Throwable t) {
            Logx.w("SignInHook: VIP direct code sign failed: " + t.getMessage());
        }
    }

    private JSONObject vipHttpGetJson(String urlStr) {
        String text = vipHttpGetText(urlStr);
        if (text == null || text.isEmpty()) return null;
        try {
            return new JSONObject(text);
        } catch (Throwable t) {
            return null;
        }
    }

    private String vipHttpGetText(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            String cookies = android.webkit.CookieManager.getInstance().getCookie(VIP_API_BASE);
            if (cookies != null && !cookies.isEmpty()) {
                conn.setRequestProperty("Cookie", cookies);
            }
            conn.setRequestProperty("User-Agent", "Dalvik/2.1.0 (Linux; Android)");
            int code = conn.getResponseCode();
            if (code != 200) {
                Logx.w("SignInHook: VIP HTTP " + code + " for " + urlStr);
                return null;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    conn.getInputStream(), "UTF-8"), 8192);
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            return sb.toString();
        } catch (Throwable t) {
            Logx.w("SignInHook: VIP HTTP failed " + urlStr + ": " + t.getMessage());
            return null;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    // ========== End VIP Sign Assist ==========

    private void hookNativeQrSign() {
        try {
            Class<?> signInfoClass = Class.forName("com.chaoxing.mobile.sign.ui.SignInfoActivity", false, cl);
            Method consumeQrPayload = findMethodInHierarchy(signInfoClass, "j5", String.class);
            Method openScanner = findMethodInHierarchy(signInfoClass, "o5");
            if (consumeQrPayload == null || openScanner == null) {
                return;
            }
            consumeQrPayload.setAccessible(true);
            module.hook(openScanner).intercept(chain -> {
                String configuredPayload = readConfiguredNativeQrPayload();
                String payload = normalizeQrJsonPayload(configuredPayload);
                if (payload.isEmpty()) {
                    if (!configuredPayload.isEmpty()) {
                        persistAssistState("qr", "当前二维码配置仅适用于 H5 页，原生扫码仍需 signId/time", "");
                    }
                    return chain.proceed();
                }
                try {
                    consumeQrPayload.invoke(chain.getThisObject(), payload);
                    persistAssistState("qr", "已注入原生二维码载荷", "");
                    return null;
                } catch (Throwable invokeError) {
                    Logx.w("SignInHook: native QR inject failed: " + invokeError.getMessage());
                    return chain.proceed();
                }
            });
            Logx.i("SignInHook: native QR sign bridge ready");
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable t) {
            Logx.w("SignInHook: native QR sign bridge failed: " + t.getMessage());
        }
    }

    private void hookNativePasswordSign() {
        try {
            Class<?> dialogClass = Class.forName(NATIVE_PASSWORD_DIALOG_CLASS, false, cl);
            Method showMethod = findMethodInHierarchy(dialogClass, "show");
            if (showMethod != null) {
                module.hook(showMethod).intercept(chain -> {
                    Object result = chain.proceed();
                    Object thisObject = chain.getThisObject();
                    if (thisObject != null && NATIVE_PASSWORD_DIALOG_CLASS.equals(thisObject.getClass().getName())) {
                        scheduleNativePasswordAutofill(thisObject);
                    }
                    return result;
                });
            }

            Class<?> clickClass = Class.forName(NATIVE_PASSWORD_DIALOG_CLICK_CLASS, false, cl);
            Method onClickMethod = findMethodInHierarchy(clickClass, "onClick", DialogInterface.class, int.class);
            if (onClickMethod != null) {
                module.hook(onClickMethod).intercept(chain -> {
                    ensureNativePasswordPrepared(chain.getThisObject());
                    return chain.proceed();
                });
            }
            Logx.i("SignInHook: native password sign bridge ready");
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable t) {
            Logx.w("SignInHook: native password sign bridge failed: " + t.getMessage());
        }
    }

    private void ensureNativePasswordPrepared(Object clickHandler) {
        String code = readPreferredNativeSignCode();
        if (code.isEmpty()) {
            return;
        }
        EditText editText = extractEditTextField(clickHandler);
        if (editText == null) {
            return;
        }
        String current = normalizeAssistSignCode(String.valueOf(editText.getText()));
        if (!code.equals(current)) {
            editText.setText(code);
            editText.setSelection(code.length());
            persistAssistState("gesture", "已兜底注入原生口令", NATIVE_PASSWORD_SOURCE);
        }
    }

    private void scheduleNativePasswordAutofill(Object dialogObj) {
        if (!(dialogObj instanceof Dialog)) {
            return;
        }
        String code = readPreferredNativeSignCode();
        if (code.isEmpty()) {
            return;
        }
        Dialog dialog = (Dialog) dialogObj;
        int dialogId = System.identityHashCode(dialog);
        if (!autoSubmittedNativeDialogs.add(dialogId)) {
            return;
        }
        android.view.Window window = dialog.getWindow();
        if (window == null) {
            autoSubmittedNativeDialogs.remove(dialogId);
            return;
        }
        // 对话框消失时清理集合条目，避免同一对话框对象复用或长会话内缓慢泄漏
        try {
            dialog.setOnDismissListener(d -> autoSubmittedNativeDialogs.remove(dialogId));
        } catch (Throwable ignored) {
        }
        window.getDecorView().postDelayed(() -> autoSubmitNativePasswordDialog(dialog, dialogId, code), 180L);
    }

    private void autoSubmitNativePasswordDialog(Dialog dialog, int dialogId, String code) {
        boolean keepSubmitted = false;
        try {
            if (!dialog.isShowing()) {
                return;
            }
            EditText input = findNativePasswordInput(dialog);
            if (input == null) {
                return;
            }
            input.requestFocus();
            input.setText(code);
            input.setSelection(code.length());

            Button positiveButton = findPositiveButton(dialog);
            if (positiveButton != null && positiveButton.isEnabled()) {
                positiveButton.performClick();
                keepSubmitted = true;
                persistAssistState("gesture", "已自动注入原生口令并提交", NATIVE_PASSWORD_SOURCE);
            } else {
                persistAssistState("gesture", "已填充原生口令，等待确认", NATIVE_PASSWORD_SOURCE);
            }
        } catch (Throwable t) {
            Logx.w("SignInHook: native password auto submit failed: " + t.getMessage());
        } finally {
            if (!keepSubmitted) {
                autoSubmittedNativeDialogs.remove(dialogId);
            }
        }
    }

    private EditText findNativePasswordInput(Dialog dialog) {
        if (dialog == null) {
            return null;
        }
        try {
            int editId = dialog.getContext().getResources().getIdentifier("editPwd", "id", "com.chaoxing.mobile");
            if (editId != 0) {
                android.view.View view = dialog.findViewById(editId);
                if (view instanceof EditText) {
                    return (EditText) view;
                }
            }
        } catch (Throwable ignored) {
        }
        return extractEditTextField(dialog);
    }

    private Button findPositiveButton(Object dialogObj) {
        if (dialogObj == null) {
            return null;
        }
        try {
            Method method = findMethodInHierarchy(dialogObj.getClass(), "n");
            if (method != null) {
                Object result = method.invoke(dialogObj);
                if (result instanceof Button) {
                    return (Button) result;
                }
            }
        } catch (Throwable ignored) {
        }
        if (dialogObj instanceof Dialog) {
            try {
                int buttonId = ((Dialog) dialogObj).getContext().getResources().getIdentifier("btnOk", "id", "com.fanzhou");
                if (buttonId != 0) {
                    android.view.View view = ((Dialog) dialogObj).findViewById(buttonId);
                    if (view instanceof Button) {
                        return (Button) view;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private EditText extractEditTextField(Object holder) {
        if (holder == null) {
            return null;
        }
        for (Class<?> cursor = holder.getClass(); cursor != null && cursor != Object.class; cursor = cursor.getSuperclass()) {
            for (Field field : cursor.getDeclaredFields()) {
                if (!EditText.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(holder);
                    if (value instanceof EditText) {
                        return (EditText) value;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private String normalizeQrJsonPayload(String rawPayload) {
        if (rawPayload == null) {
            return "";
        }
        String trimmed = rawPayload.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        try {
            JSONObject json = new JSONObject(trimmed);
            if (!json.optString("signId", "").isEmpty() && !json.optString("time", "").isEmpty()) {
                return json.toString();
            }
        } catch (Throwable ignored) {
        }
        try {
            Uri uri = Uri.parse(trimmed);
            String signId = uri.getQueryParameter("signId");
            String time = uri.getQueryParameter("time");
            if (signId != null && !signId.isEmpty() && time != null && !time.isEmpty()) {
                JSONObject json = new JSONObject();
                json.put("signId", signId);
                json.put("time", time);
                return json.toString();
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private void hookFileChooserBridge(Class<?> clientClass) {
        if (clientClass == null || !isKnownHostChooserClient(clientClass) || !hookedChooserClients.add(clientClass)) {
            return;
        }
        try {
            hookSingleFileChooserMethod(findMethodInHierarchy(clientClass, "G", ValueCallback.class));
            hookSingleFileChooserMethod(findMethodInHierarchy(clientClass, "H", ValueCallback.class, String.class));
            hookSingleFileChooserMethod(findMethodInHierarchy(clientClass, "I", ValueCallback.class, String.class, String.class));
            hookMultiFileChooserMethod(findMethodInHierarchy(
                    clientClass,
                    "onShowFileChooser",
                    WebView.class,
                    ValueCallback.class,
                    WebChromeClient.FileChooserParams.class));
        } catch (Throwable t) {
            Logx.w("SignInHook: file chooser hook failed on " + clientClass.getName() + ": " + t.getMessage());
        }
    }

    private boolean isKnownHostChooserClient(Class<?> clientClass) {
        for (Class<?> cursor = clientClass; cursor != null && cursor != Object.class; cursor = cursor.getSuperclass()) {
            if ("hw.l".equals(cursor.getName())) {
                return true;
            }
        }
        return false;
    }

    private void hookSingleFileChooserMethod(Method method) {
        if (method == null) {
            return;
        }
        String methodKey = method.toGenericString();
        if (!hookedChooserMethods.add(methodKey)) {
            return;
        }
        method.setAccessible(true);
        try {
            module.hook(method).intercept(chain -> {
                WebView owner = findOwnerWebView(chain.getThisObject());
                Object callback = chain.getArg(0);
                if (tryAutoProvidePhoto(owner, callback, false)) {
                    return null;
                }
                return chain.proceed();
            });
        } catch (Throwable hookError) {
            Logx.w("SignInHook: legacy chooser hook failed on " + method.getName() + ": " + hookError.getMessage());
        }
    }

    private void hookMultiFileChooserMethod(Method method) {
        if (method == null) {
            return;
        }
        String methodKey = method.toGenericString();
        if (!hookedChooserMethods.add(methodKey)) {
            return;
        }
        method.setAccessible(true);
        try {
            module.hook(method).intercept(chain -> {
                WebView owner = (WebView) chain.getArg(0);
                Object callback = chain.getArg(1);
                if (tryAutoProvidePhoto(owner, callback, true)) {
                    return true;
                }
                return chain.proceed();
            });
        } catch (Throwable hookError) {
            Logx.w("SignInHook: file chooser hook failed on " + method.getName() + ": " + hookError.getMessage());
        }
    }

    private WebView findOwnerWebView(Object chromeClient) {
        if (chromeClient == null) {
            return null;
        }
        synchronized (chromeClientOwners) {
            return chromeClientOwners.get(chromeClient);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean tryAutoProvidePhoto(WebView webView, Object callback, boolean multiple) {
        if (!(callback instanceof ValueCallback) || !isPhotoAssistReady(webView)) {
            return false;
        }
        String currentUrl = webView != null ? webView.getUrl() : "";
        if (!isSignUrl(currentUrl)) {
            return false;
        }
        Uri photoUri = resolveConfiguredPhotoUri();
        if (photoUri == null) {
            return false;
        }
        try {
            ValueCallback rawCallback = (ValueCallback) callback;
            rawCallback.onReceiveValue(multiple ? new Uri[]{photoUri} : photoUri);
            photoAssistWindows.remove(System.identityHashCode(webView));
            persistAssistState("photo", "已自动注入照片", currentUrl);
            return true;
        } catch (Throwable t) {
            Logx.w("SignInHook: auto photo inject failed: " + t.getMessage());
            return false;
        }
    }

    private boolean isPhotoAssistReady(WebView webView) {
        if (webView == null) {
            return false;
        }
        int webViewId = System.identityHashCode(webView);
        Long until = photoAssistWindows.get(webViewId);
        if (until == null) {
            return false;
        }
        if (until < System.currentTimeMillis()) {
            photoAssistWindows.remove(webViewId);
            return false;
        }
        return true;
    }

    private Uri resolveConfiguredPhotoUri() {
        String rawUri = readConfiguredPhotoUri();
        if (rawUri.isEmpty()) {
            return null;
        }
        try {
            Uri parsed = Uri.parse(rawUri);
            if (parsed.getScheme() != null && !parsed.getScheme().isEmpty()) {
                return parsed;
            }
        } catch (Throwable ignored) {
        }
        File file = new File(rawUri);
        if (file.exists()) {
            return Uri.fromFile(file);
        }
        return null;
    }

    private Method findMethodInHierarchy(Class<?> type, String name, Class<?>... parameterTypes) {
        for (Class<?> cursor = type; cursor != null && cursor != Object.class; cursor = cursor.getSuperclass()) {
            try {
                Method method = cursor.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }
}
