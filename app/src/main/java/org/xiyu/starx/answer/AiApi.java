package org.xiyu.starx.answer;

import org.xiyu.starx.util.Logx;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * AI 答题 API — 支持 OpenAI 兼容端点 + Google Gemini
 *
 * OpenAI 兼容: DeepSeek / ChatGPT / 通义千问 / 任意兼容接口
 * Google Gemini: generativelanguage.googleapis.com
 */
public class AiApi {

    public enum Provider { OPENAI, GEMINI }

    private static final int TIMEOUT_MS = 15000;
    private static final String DEFAULT_OPENAI_URL = "https://api.openai.com/v1/chat/completions";
    private static final String DEFAULT_OPENAI_MODEL = "gpt-4o-mini";
    private static final String DEFAULT_GEMINI_MODEL = "gemini-2.0-flash";

    private static final String SYSTEM_PROMPT =
            "你是一个考试答题助手。用户给你一道题目（可能包含选项），你需要直接给出正确答案。\n"
                    + "规则：\n"
                    + "- 选择题只回答字母（如 A 或 ABD），不要解释\n"
                    + "- 判断题只回答\"对\"或\"错\"\n"
                    + "- 填空题直接给出填空内容，多空用 | 分隔\n"
                    + "- 简答题简洁回答要点\n"
                    + "- 不要输出任何多余内容，只输出答案";

    private Provider provider;
    private String apiKey;
    private String baseUrl;   // OpenAI 兼容端点
    private String model;

    public AiApi(Provider provider, String apiKey, String baseUrl, String model) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    /** 创建 OpenAI 兼容实例 */
    public static AiApi openai(String apiKey, String baseUrl, String model) {
        return new AiApi(
                Provider.OPENAI,
                apiKey,
                (baseUrl == null || baseUrl.isEmpty()) ? DEFAULT_OPENAI_URL : baseUrl,
                (model == null || model.isEmpty()) ? DEFAULT_OPENAI_MODEL : model
        );
    }

    /** 创建 Gemini 实例 */
    public static AiApi gemini(String apiKey, String model) {
        return new AiApi(
                Provider.GEMINI,
                apiKey,
                null,
                (model == null || model.isEmpty()) ? DEFAULT_GEMINI_MODEL : model
        );
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    /**
     * 向 AI 提问，返回答案文本；失败返回 null
     */
    public String ask(String question) {
        if (!isConfigured()) return null;
        if (question == null || question.trim().isEmpty()) return null;

        String cleaned = question.replaceAll("<[^>]*>", "").replaceAll("\\s+", " ").trim();
        try {
            String answer = (provider == Provider.GEMINI) ? askGemini(cleaned) : askOpenAI(cleaned);
            if (answer != null) {
                answer = answer.trim();
                Logx.i("AiApi[" + provider + "]: => " + answer);
            }
            return answer;
        } catch (Throwable t) {
            Logx.w("AiApi[" + provider + "]: failed: " + t.getMessage());
            return null;
        }
    }

    // ========== OpenAI 兼容 ==========

    private String askOpenAI(String question) throws Exception {
        String json = "{\"model\":\"" + escapeJson(model) + "\","
                + "\"messages\":["
                + "{\"role\":\"system\",\"content\":\"" + escapeJson(SYSTEM_PROMPT) + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + escapeJson(question) + "\"}"
                + "],\"max_tokens\":512,\"temperature\":0.1}";

        // 确保 URL 以 /chat/completions 结尾
        String url = baseUrl;
        if (!url.endsWith("/chat/completions")) {
            if (!url.endsWith("/")) url += "/";
            if (!url.endsWith("v1/")) url += "v1/";
            url += "chat/completions";
        }

        String resp = post(url, json, "Bearer " + apiKey);
        return extractOpenAIAnswer(resp);
    }

    private String extractOpenAIAnswer(String json) {
        // 提取 choices[0].message.content
        int contentIdx = json.indexOf("\"content\"");
        if (contentIdx == -1) return null;
        int colonIdx = json.indexOf(":", contentIdx);
        if (colonIdx == -1) return null;
        int quoteStart = json.indexOf("\"", colonIdx + 1);
        if (quoteStart == -1) return null;

        // 处理转义字符的结束引号
        int quoteEnd = quoteStart + 1;
        while (quoteEnd < json.length()) {
            if (json.charAt(quoteEnd) == '\\') {
                quoteEnd += 2; // 跳过转义
            } else if (json.charAt(quoteEnd) == '"') {
                break;
            } else {
                quoteEnd++;
            }
        }
        if (quoteEnd >= json.length()) return null;
        return unescapeJson(json.substring(quoteStart + 1, quoteEnd));
    }

    // ========== Google Gemini ==========

    private String askGemini(String question) throws Exception {
        String fullPrompt = SYSTEM_PROMPT + "\n\n题目：\n" + question;
        String json = "{\"contents\":[{\"parts\":[{\"text\":\"" + escapeJson(fullPrompt) + "\"}]}],"
                + "\"generationConfig\":{\"maxOutputTokens\":512,\"temperature\":0.1}}";

        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":generateContent?key=" + apiKey;

        String resp = post(url, json, null);
        return extractGeminiAnswer(resp);
    }

    private String extractGeminiAnswer(String json) {
        // 提取 candidates[0].content.parts[0].text
        int textIdx = json.indexOf("\"text\"");
        if (textIdx == -1) return null;
        int colonIdx = json.indexOf(":", textIdx);
        if (colonIdx == -1) return null;
        int quoteStart = json.indexOf("\"", colonIdx + 1);
        if (quoteStart == -1) return null;
        int quoteEnd = quoteStart + 1;
        while (quoteEnd < json.length()) {
            if (json.charAt(quoteEnd) == '\\') {
                quoteEnd += 2;
            } else if (json.charAt(quoteEnd) == '"') {
                break;
            } else {
                quoteEnd++;
            }
        }
        if (quoteEnd >= json.length()) return null;
        return unescapeJson(json.substring(quoteStart + 1, quoteEnd));
    }

    // ========== HTTP / Util ==========

    private String post(String urlStr, String body, String auth) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            if (auth != null) {
                conn.setRequestProperty("Authorization", auth);
            }

            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    code >= 400 ? conn.getErrorStream() : conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            if (code >= 400) {
                Logx.w("AiApi: HTTP " + code + " => " + sb);
                return null;
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String unescapeJson(String s) {
        return s.replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }
}
