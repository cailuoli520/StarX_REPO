package org.xiyu.starx.answer;

import org.xiyu.starx.util.Logx;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;

/**
 * 免费题库 API — 支持多源查询，命中一个即返回
 * 端点由服务端动态下发
 */
public class TikuApi {

    private static String[] ENDPOINTS = new String[0];

    private static final int TIMEOUT_MS = 5000;

    public static void init(List<String> endpoints) {
        if (endpoints != null && !endpoints.isEmpty()) {
            ENDPOINTS = endpoints.toArray(new String[0]);
        }
    }

    /**
     * 查询题库，返回答案文本；未命中返回 null
     */
    public static String query(String question) {
        if (question == null || question.trim().isEmpty()) return null;

        // 清洗题目：去掉 HTML 标签和多余空格
        String cleaned = question.replaceAll("<[^>]*>", "").replaceAll("\\s+", " ").trim();
        if (cleaned.length() < 2) return null;

        for (String endpoint : ENDPOINTS) {
            try {
                String answer = queryEndpoint(endpoint, cleaned);
                if (answer != null && !answer.isEmpty()) {
                    Logx.i("TikuApi: 命中 [" + endpoint.substring(8, 30) + "...] => " + answer);
                    return answer;
                }
            } catch (Throwable t) {
                Logx.w("TikuApi: endpoint failed: " + t.getMessage());
            }
        }
        return null;
    }

    private static String queryEndpoint(String endpoint, String question) throws Exception {
        String urlStr = endpoint + URLEncoder.encode(question, "UTF-8");
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            int code = conn.getResponseCode();
            if (code != 200) return null;

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            String body = sb.toString().trim();
            if (body.isEmpty() || body.equals("null") || body.equals("false")
                    || body.equals("\"\"") || body.contains("未找到") || body.contains("没有找到")) {
                return null;
            }

            // 有些接口返回 JSON {"answer":"xxx"}, 有些直接返回文本
            if (body.startsWith("{")) {
                String answer = extractJsonValue(body, "answer");
                if (answer == null) answer = extractJsonValue(body, "data");
                return answer;
            }
            // 去除首尾引号
            if (body.startsWith("\"") && body.endsWith("\"")) {
                body = body.substring(1, body.length() - 1);
            }
            return body.isEmpty() ? null : body;
        } finally {
            conn.disconnect();
        }
    }

    /** 简易 JSON 值提取 */
    private static String extractJsonValue(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx == -1) return null;
        int colonIdx = json.indexOf(":", idx + pattern.length());
        if (colonIdx == -1) return null;
        int start = colonIdx + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start >= json.length()) return null;

        if (json.charAt(start) == '"') {
            int end = json.indexOf("\"", start + 1);
            if (end == -1) return null;
            String val = json.substring(start + 1, end);
            return val.isEmpty() ? null : val;
        } else if (json.charAt(start) == 'n') {
            return null; // null
        } else {
            int end = start;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
            String val = json.substring(start, end).trim();
            return val.equals("null") ? null : val;
        }
    }
}
