package org.xiyu.starx.answer;

import org.xiyu.starx.util.ActiveConnection;
import org.xiyu.starx.util.Logx;
import org.xiyu.starx.util.QuestionCache;
import org.xiyu.starx.util.RateLimiter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * 柠檬题库 API — 专为超星学习通优化的免费题库
 *
 * 普通查询 /api/v1/cx   — 免费（无需 Token）
 * 高级查询 /api/v1/mcx  — 需 Token，变形题目命中率更高
 *
 * @see <a href="https://docs.lemtk.xyz/get-started.html">柠檬题库文档</a>
 */
public class LemTkApi {

    private static final String DEFAULT_BASE_URL = "https://api.vanse.top";
    private static final int TIMEOUT_MS = 8000;
    private static final String RATE_KEY = "lemtk";

    private static String baseUrl = DEFAULT_BASE_URL;
    private static String token = null;
    private static boolean enabled = true;

    /**
     * 初始化柠檬题库配置
     *
     * @param url   自定义域名（null 则使用默认 api.vanse.top）
     * @param tk    Bearer Token（null/空 = 免费模式）
     */
    public static void init(String url, String tk) {
        if (url != null && !url.trim().isEmpty()) {
            baseUrl = url.trim();
            if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (tk != null && !tk.trim().isEmpty()) {
            token = tk.trim();
        } else {
            token = null;
        }
        Logx.i("LemTkApi: init url=" + baseUrl + " token=" + (token != null ? "***" : "none"));
    }

    public static void setEnabled(boolean e) {
        enabled = e;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * 题型映射：StarX 内部使用的类型到柠檬题库的 type 参数
     * 单选:0 多选:1 填空:2 判断:3 简答:4
     */
    public static String mapQuestionType(String internalType) {
        if (internalType == null) return null;
        switch (internalType.trim()) {
            case "single":
            case "单选":
            case "0":
                return "0";
            case "multiple":
            case "多选":
            case "1":
                return "1";
            case "fill":
            case "填空":
            case "2":
                return "2";
            case "judge":
            case "判断":
            case "3":
                return "3";
            case "essay":
            case "简答":
            case "4":
                return "4";
            default:
                return null;
        }
    }

    /**
     * 查询题库
     *
     * @param question 题目文本
     * @return 答案文本（多选答案以 # 分隔），未命中返回 null
     */
    public static String query(String question) {
        return query(question, null, null);
    }

    /**
     * 带题型和选项的查询
     *
     * @param question 题目文本
     * @param type     题型（可选）
     * @param options  选项，以 | 分隔（可选）
     * @return 答案文本，未命中返回 null
     */
    public static String query(String question, String type, String options) {
        return queryWithConfig(baseUrl, token, question, type, options);
    }

    public static String queryWithConfig(String url, String tk, String question, String type, String options) {
        if (!enabled) return null;
        if (question == null || question.trim().isEmpty()) return null;

        String requestBaseUrl = normalizeBaseUrl(url);
        String requestToken = normalizeToken(tk);

        // 清洗题目
        String cleaned = question.replaceAll("<[^>]*>", "").replaceAll("\\s+", " ").trim();
        if (cleaned.length() < 2) return null;

        // 限流 — 非阻塞：冷却未结束则跳过，让后续题库 / AI 接手
        if (!RateLimiter.tryAcquire(RATE_KEY)) {
            Logx.i("LemTkApi: rate limited, skip");
            return null;
        }
        if (Thread.currentThread().isInterrupted()) return null;

        // 有 Token 时优先使用高级搜索
        if (requestToken != null) {
            if (Thread.currentThread().isInterrupted()) return null;
            try {
                String answer = queryApi(requestBaseUrl, requestToken, "/api/v1/mcx", cleaned, type, options, true);
                if (answer != null) {
                    Logx.i("LemTkApi: 命中[高级] => " + answer);
                    return answer;
                }
            } catch (Throwable t) {
                Logx.w("LemTkApi: mcx failed: " + t.getMessage());
            }
        }

        // 免费查询
        if (Thread.currentThread().isInterrupted()) return null;
        try {
            String answer = queryApi(requestBaseUrl, requestToken, "/api/v1/cx", cleaned, type, options, requestToken != null);
            if (answer != null) {
                Logx.i("LemTkApi: 命中[普通] => " + answer);
                return answer;
            }
        } catch (Throwable t) {
            Logx.w("LemTkApi: cx failed: " + t.getMessage());
        }

        return null;
    }

    private static String normalizeBaseUrl(String url) {
        String normalized = (url == null || url.trim().isEmpty()) ? DEFAULT_BASE_URL : url.trim();
        if (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private static String normalizeToken(String tk) {
        if (tk == null || tk.trim().isEmpty()) return null;
        return tk.trim();
    }

    private static String queryApi(String requestBaseUrl, String requestToken, String path, String question, String type,
                                   String options, boolean useToken) throws Exception {
        // 构建 GET URL
        StringBuilder urlBuilder = new StringBuilder(requestBaseUrl).append(path);
        urlBuilder.append("?question=").append(URLEncoder.encode(question, "UTF-8"));
        if (type != null && !type.isEmpty()) {
            String mapped = mapQuestionType(type);
            if (mapped != null) {
                urlBuilder.append("&type=").append(mapped);
            }
        }
        if (options != null && !options.isEmpty()) {
            urlBuilder.append("&options=").append(URLEncoder.encode(options, "UTF-8"));
        }

        URL url = new URL(urlBuilder.toString());
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        ActiveConnection.set(conn);
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "StarX/1.2");
            if (useToken && requestToken != null) {
                conn.setRequestProperty("Authorization", "Bearer " + requestToken);
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                Logx.w("LemTkApi: HTTP " + code + " for " + path);
                return null;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            return parseResponse(sb.toString());
        } finally {
            ActiveConnection.clear();
            conn.disconnect();
        }
    }

    /**
     * 解析柠檬题库响应
     * 成功: {"code":1000,"msg":"success","data":{"answer":"xxx#yyy"}}
     */
    private static String parseResponse(String body) {
        if (body == null || body.isEmpty()) return null;

        try {
            // 检查 code
            int codeIdx = body.indexOf("\"code\"");
            if (codeIdx == -1) return null;
            int colonIdx = body.indexOf(":", codeIdx);
            if (colonIdx == -1) return null;
            int start = colonIdx + 1;
            while (start < body.length() && body.charAt(start) == ' ') start++;
            int end = start;
            while (end < body.length() && Character.isDigit(body.charAt(end))) end++;
            String codeStr = body.substring(start, end);
            if (!"1000".equals(codeStr)) {
                Logx.w("LemTkApi: code=" + codeStr);
                return null;
            }

            // 提取 answer
            int ansIdx = body.indexOf("\"answer\"");
            if (ansIdx == -1) return null;
            int ansColon = body.indexOf(":", ansIdx);
            if (ansColon == -1) return null;
            int quoteStart = body.indexOf("\"", ansColon + 1);
            if (quoteStart == -1) return null;
            int quoteEnd = quoteStart + 1;
            while (quoteEnd < body.length()) {
                if (body.charAt(quoteEnd) == '\\') {
                    quoteEnd += 2;
                } else if (body.charAt(quoteEnd) == '"') {
                    break;
                } else {
                    quoteEnd++;
                }
            }
            if (quoteEnd >= body.length()) return null;
            String answer = unescapeJson(body.substring(quoteStart + 1, quoteEnd)).trim();
            return answer.isEmpty() || QuestionCache.isInvalidAnswerText(answer) ? null : answer;
        } catch (Throwable t) {
            Logx.w("LemTkApi: parse error: " + t.getMessage());
            return null;
        }
    }

    /** 基础 JSON 字符串反转义 */
    private static String unescapeJson(String s) {
        if (s == null || s.indexOf('\\') == -1) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '"': sb.append('"'); i++; break;
                    case '\\': sb.append('\\'); i++; break;
                    case 'n': sb.append('\n'); i++; break;
                    case 'r': sb.append('\r'); i++; break;
                    case 't': sb.append('\t'); i++; break;
                    case '/': sb.append('/'); i++; break;
                    case 'u':
                        if (i + 5 < s.length()) {
                            try {
                                int cp = Integer.parseInt(s.substring(i + 2, i + 6), 16);
                                sb.append((char) cp);
                                i += 5;
                            } catch (NumberFormatException e) {
                                sb.append(c);
                            }
                        } else {
                            sb.append(c);
                        }
                        break;
                    default: sb.append(c); break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
