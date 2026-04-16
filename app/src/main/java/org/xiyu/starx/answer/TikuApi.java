package org.xiyu.starx.answer;

import org.xiyu.starx.util.ActiveConnection;
import org.xiyu.starx.util.Logx;
import org.xiyu.starx.util.QuestionCache;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 免费题库 API — 支持多源查询，命中一个即返回
 * 端点由服务端动态下发
 */
public class TikuApi {

    private static String[] ENDPOINTS = new String[0];
    private static final List<SourceConfig> SOURCES = new ArrayList<>();

    private static final int TIMEOUT_MS = 5000;
    private static final String ADAPTER_SEARCH_PATH = "/adapter-service/search";
    private static final String ZXSEEK_DEFAULT_API = "https://api.wkexam.com/api/";
    private static final String MODE_CUSTOM_GET = "custom_get";
    private static final String MODE_CUSTOM_POST_JSON = "custom_post_json";
    private static final String PLACEMENT_QUERY = "query";
    private static final String PLACEMENT_HEADER = "header";
    private static final String PLACEMENT_BODY = "body";

    public static void init(List<String> endpoints) {
        init(endpoints, null);
    }

    public static void init(List<String> endpoints, String sourceConfigJson) {
        if (endpoints != null && !endpoints.isEmpty()) {
            ENDPOINTS = endpoints.toArray(new String[0]);
        } else {
            ENDPOINTS = new String[0];
        }
        SOURCES.clear();
        List<SourceConfig> parsed = parseSourceConfigs(sourceConfigJson);
        if (parsed.isEmpty()) {
            parsed.addAll(defaultSources());
        }
        SOURCES.addAll(parsed);
    }

    /**
     * 查询题库，返回答案文本；未命中返回 null
     */
    public static String query(String question) {
        return query(question, -1, null);
    }

    public static String query(String question, int type, String options) {
        if (question == null || question.trim().isEmpty()) return null;

        // 清洗题目：去掉 HTML 标签和多余空格
        String cleaned = question.replaceAll("<[^>]*>", "").replaceAll("\\s+", " ").trim();
        if (cleaned.length() < 2) return null;

        String mappedType = type >= 0 ? String.valueOf(type) : null;

        for (SourceConfig source : SOURCES) {
            if (!source.enabled || source.baseUrl.isEmpty()) continue;
            if (Thread.currentThread().isInterrupted()) return null;
            try {
                String answer = querySource(source, cleaned, type, mappedType, options);
                if (answer != null && !answer.isEmpty() && !QuestionCache.isInvalidAnswerText(answer)) {
                    Logx.i("TikuApi: 命中[" + source.name + "] => " + answer);
                    return answer;
                }
            } catch (Throwable t) {
                Logx.w("TikuApi: source failed [" + source.name + "]: " + t.getMessage());
            }
        }

        for (String endpoint : ENDPOINTS) {
            if (Thread.currentThread().isInterrupted()) return null;
            try {
                String answer = queryEndpoint(endpoint, cleaned);
                if (answer != null && !answer.isEmpty()) {
                    Logx.i("TikuApi: 命中 [" + abbreviateEndpoint(endpoint) + "] => " + answer);
                    return answer;
                }
            } catch (Throwable t) {
                Logx.w("TikuApi: endpoint failed: " + t.getMessage());
            }
        }
        return null;
    }

    private static String querySource(SourceConfig source, String question, int type, String mappedType, String options) throws Exception {
        if (isCustomTemplateSource(source)) {
            return queryCustomTemplateSource(source, question, type, mappedType, options);
        }
        if (isZxSeekCompatibleSource(source)) {
            return queryZxSeekSource(source, question);
        }
        if (isAdapterCompatibleSource(source)) {
            return queryAdapterSource(source, question, type, options);
        }
        return LemTkApi.queryWithConfig(source.baseUrl, source.token, question, mappedType, options);
    }

    private static boolean isCustomTemplateSource(SourceConfig source) {
        if (source == null) return false;
        String mode = source.mode != null ? source.mode.trim().toLowerCase(Locale.ROOT) : "";
        return MODE_CUSTOM_GET.equals(mode) || MODE_CUSTOM_POST_JSON.equals(mode);
    }

    private static String queryCustomTemplateSource(SourceConfig source, String question, int type,
                                                    String mappedType, String options) throws Exception {
        String mode = source.mode != null ? source.mode.trim().toLowerCase(Locale.ROOT) : "";
        boolean postJson = MODE_CUSTOM_POST_JSON.equals(mode);
        String urlStr = buildCustomTemplateUrl(source, question, type, mappedType, options);
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        ActiveConnection.set(conn);
        try {
            conn.setRequestMethod(postJson ? "POST" : "GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "StarX/1.2");

            for (TemplateParamConfig param : source.templateParams) {
                if (param == null || !PLACEMENT_HEADER.equals(param.placement) || param.key.isEmpty()) {
                    continue;
                }
                conn.setRequestProperty(param.key, resolveTemplateText(param.value, question, type, mappedType, options));
            }

            if (postJson) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                JSONObject body = buildCustomTemplateBody(source, question, type, mappedType, options);
                byte[] payload = body.toString().getBytes("UTF-8");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                Logx.w("TikuApi(custom): HTTP " + code + " for " + urlStr);
                return null;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            return parseCustomTemplateResponse(sb.toString(), source.answerPath);
        } finally {
            ActiveConnection.clear();
            conn.disconnect();
        }
    }

    private static String buildCustomTemplateUrl(SourceConfig source, String question, int type,
                                                 String mappedType, String options) throws Exception {
        String normalized = source.baseUrl == null ? "" : source.baseUrl.trim();
        StringBuilder url = new StringBuilder(normalized);
        boolean hasQuery = normalized.contains("?");
        boolean hasTrailingSeparator = normalized.endsWith("?") || normalized.endsWith("&");
        for (TemplateParamConfig param : source.templateParams) {
            if (param == null || !PLACEMENT_QUERY.equals(param.placement) || param.key.isEmpty()) {
                continue;
            }
            String value = resolveTemplateText(param.value, question, type, mappedType, options);
            if (!hasQuery) {
                url.append('?');
                hasQuery = true;
                hasTrailingSeparator = true;
            } else if (!hasTrailingSeparator) {
                url.append('&');
                hasTrailingSeparator = true;
            }
            url.append(URLEncoder.encode(param.key, "UTF-8"))
                    .append('=')
                    .append(URLEncoder.encode(value, "UTF-8"));
            hasTrailingSeparator = false;
        }
        return url.toString();
    }

    private static JSONObject buildCustomTemplateBody(SourceConfig source, String question, int type,
                                                      String mappedType, String options) throws Exception {
        JSONObject body = new JSONObject();
        for (TemplateParamConfig param : source.templateParams) {
            if (param == null || !PLACEMENT_BODY.equals(param.placement) || param.key.isEmpty()) {
                continue;
            }
            body.put(param.key, resolveTemplateBodyValue(param.value, question, type, mappedType, options));
        }
        return body;
    }

    private static String resolveTemplateText(String template, String question, int type,
                                              String mappedType, String options) {
        String resolved = template == null ? "" : template;
        resolved = resolved.replace("{{question}}", question != null ? question : "");
        resolved = resolved.replace("{{type}}", type >= 0 ? String.valueOf(type) : "");
        resolved = resolved.replace("{{mapped_type}}", mappedType != null ? mappedType : "");
        resolved = resolved.replace("{{options}}", options != null ? options : "");
        resolved = resolved.replace("{{options_json}}", buildAdapterOptionArray(options).toString());
        return resolved.trim();
    }

    private static Object resolveTemplateBodyValue(String template, String question, int type,
                                                   String mappedType, String options) {
        String trimmed = template == null ? "" : template.trim();
        if ("{{options_json}}".equals(trimmed)) {
            return buildAdapterOptionArray(options);
        }
        if ("{{type}}".equals(trimmed) && type >= 0) {
            return type;
        }
        if ("{{mapped_type}}".equals(trimmed) && mappedType != null && !mappedType.isEmpty()) {
            return mappedType;
        }
        String resolved = resolveTemplateText(template, question, type, mappedType, options);
        return parseTemplateLiteral(resolved);
    }

    private static Object parseTemplateLiteral(String resolved) {
        if (resolved == null) return "";
        String trimmed = resolved.trim();
        if (trimmed.isEmpty()) return "";
        if ("true".equalsIgnoreCase(trimmed)) return true;
        if ("false".equalsIgnoreCase(trimmed)) return false;
        if (trimmed.matches("^-?\\d+$")) {
            try {
                return Long.parseLong(trimmed);
            } catch (NumberFormatException ignored) {
            }
        }
        if (trimmed.matches("^-?\\d+\\.\\d+$")) {
            try {
                return Double.parseDouble(trimmed);
            } catch (NumberFormatException ignored) {
            }
        }
        if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            try {
                return new JSONTokener(trimmed).nextValue();
            } catch (Throwable ignored) {
            }
        }
        return resolved;
    }

    private static String parseCustomTemplateResponse(String body, String answerPath) {
        if (body == null || body.trim().isEmpty()) return null;
        String trimmed = body.trim();
        try {
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                Object root = new JSONTokener(trimmed).nextValue();
                if (answerPath != null && !answerPath.trim().isEmpty()) {
                    String extracted = normalizeJsonPathValue(extractJsonPathValue(root, answerPath.trim()));
                    if (extracted != null && !extracted.isEmpty() && !QuestionCache.isInvalidAnswerText(extracted)) {
                        return extracted;
                    }
                }
                if (root instanceof JSONObject) {
                    String answer = parseZxSeekResponse(trimmed);
                    if (answer == null) answer = parseAdapterResponse(trimmed, -1);
                    if (answer == null) answer = normalizeJsonPathValue(root);
                    return QuestionCache.isInvalidAnswerText(answer) ? null : answer;
                }
                String answer = normalizeJsonPathValue(root);
                return QuestionCache.isInvalidAnswerText(answer) ? null : answer;
            }
        } catch (Throwable t) {
            Logx.w("TikuApi(custom): parse failed: " + t.getMessage());
        }

        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed.isEmpty() || QuestionCache.isInvalidAnswerText(trimmed) ? null : trimmed;
    }

    private static Object extractJsonPathValue(Object root, String path) {
        if (root == null || path == null || path.isEmpty()) return root;
        Object current = root;
        for (String part : splitJsonPath(path)) {
            if (part == null || part.trim().isEmpty()) continue;
            current = stepJsonPath(current, part.trim());
            if (current == null || current == JSONObject.NULL) {
                return null;
            }
        }
        return current;
    }

    private static List<String> splitJsonPath(String path) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int bracketDepth = 0;
        for (int i = 0; i < path.length(); i++) {
            char ch = path.charAt(i);
            if (ch == '.' && bracketDepth == 0) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            if (ch == '[') bracketDepth++;
            if (ch == ']' && bracketDepth > 0) bracketDepth--;
            current.append(ch);
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    private static Object stepJsonPath(Object current, String part) {
        String fieldName = part;
        int bracketIndex = part.indexOf('[');
        if (bracketIndex >= 0) {
            fieldName = part.substring(0, bracketIndex);
        }
        if (!fieldName.isEmpty()) {
            if (!(current instanceof JSONObject)) return null;
            current = ((JSONObject) current).opt(fieldName);
        }

        int index = fieldName.length();
        while (index < part.length()) {
            int start = part.indexOf('[', index);
            if (start < 0) break;
            int end = part.indexOf(']', start + 1);
            if (end < 0) return null;
            if (!(current instanceof JSONArray)) return null;
            String rawIndex = part.substring(start + 1, end).trim();
            int arrayIndex;
            try {
                arrayIndex = Integer.parseInt(rawIndex);
            } catch (NumberFormatException e) {
                return null;
            }
            current = ((JSONArray) current).opt(arrayIndex);
            index = end + 1;
        }
        return current;
    }

    private static String normalizeJsonPathValue(Object payload) {
        if (payload == null || payload == JSONObject.NULL) return null;
        if (payload instanceof String || payload instanceof Number || payload instanceof Boolean) {
            return normalizeAdapterText(String.valueOf(payload));
        }
        if (payload instanceof JSONArray) {
            JSONArray arr = (JSONArray) payload;
            List<String> values = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                String value = normalizeJsonPathValue(arr.opt(i));
                if (value != null && !value.isEmpty()) {
                    values.add(value);
                }
            }
            return values.isEmpty() ? null : String.join("#", values);
        }
        if (payload instanceof JSONObject) {
            JSONObject obj = (JSONObject) payload;
            String nested = extractAdapterAnswer(obj, -1);
            if (nested != null && !nested.isEmpty()) return nested;

            String zxAnswer = extractZxSeekAnswer(obj);
            if (zxAnswer != null && !zxAnswer.isEmpty()) return zxAnswer;

            String direct = firstNonEmpty(
                    normalizeJsonPathValue(obj.opt("answer")),
                    normalizeJsonPathValue(obj.opt("data")),
                    normalizeJsonPathValue(obj.opt("result")),
                    normalizeAdapterText(obj.optString("value", "")),
                    normalizeAdapterText(obj.optString("text", "")),
                    normalizeAdapterText(obj.optString("content", ""))
            );
            return direct.isEmpty() ? null : direct;
        }
        return null;
    }

    private static boolean isZxSeekCompatibleSource(SourceConfig source) {
        if (source == null) return false;
        String mode = source.mode != null ? source.mode.trim().toLowerCase(Locale.ROOT) : "";
        if ("zxseek".equals(mode) || "wkexam".equals(mode) || "eduquest".equals(mode)) return true;
        if ("adapter".equals(mode) || "tikuadapter".equals(mode)
                || "lemtk".equals(mode) || "lemtk-compatible".equals(mode)) {
            return false;
        }

        String baseUrl = source.baseUrl != null ? source.baseUrl.toLowerCase(Locale.ROOT) : "";
        String name = source.name != null ? source.name.toLowerCase(Locale.ROOT) : "";
        return baseUrl.contains("api.wkexam.com")
                || baseUrl.contains("wkexam.com/api")
                || baseUrl.contains("zxseek.com")
                || name.contains("zxseek")
                || name.contains("wkexam")
                || name.contains("eduquest");
    }

    private static String queryZxSeekSource(SourceConfig source, String question) throws Exception {
        String urlStr = buildZxSeekUrl(source.baseUrl, source.token, question);
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        ActiveConnection.set(conn);
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "StarX/1.2");

            int code = conn.getResponseCode();
            if (code != 200) {
                Logx.w("TikuApi(zxseek): HTTP " + code + " for " + urlStr);
                return null;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            return parseZxSeekResponse(sb.toString());
        } finally {
            ActiveConnection.clear();
            conn.disconnect();
        }
    }

    private static String buildZxSeekUrl(String baseUrl, String token, String question) throws Exception {
        String normalized = normalizeZxSeekBaseUrl(baseUrl);
        StringBuilder url = new StringBuilder(normalized);
        if (normalized.contains("?")) {
            if (!normalized.endsWith("?") && !normalized.endsWith("&")) {
                url.append('&');
            }
        } else {
            url.append('?');
        }
        String trimmedToken = token != null ? token.trim() : "";
        if (!trimmedToken.isEmpty()) {
            url.append("token=").append(URLEncoder.encode(trimmedToken, "UTF-8")).append('&');
        }
        url.append("q=").append(URLEncoder.encode(question, "UTF-8"));
        return url.toString();
    }

    private static String normalizeZxSeekBaseUrl(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        if (normalized.isEmpty()) return ZXSEEK_DEFAULT_API;
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains("zxseek.com/api.html")
                || "https://zxseek.com".equals(lower)
                || "http://zxseek.com".equals(lower)
                || "zxseek.com".equals(lower)) {
            return ZXSEEK_DEFAULT_API;
        }
        return normalized;
    }

    private static boolean isAdapterCompatibleSource(SourceConfig source) {
        if (source == null) return false;
        String mode = source.mode != null ? source.mode.trim().toLowerCase(Locale.ROOT) : "";
        if ("adapter".equals(mode) || "tikuadapter".equals(mode)) return true;
        if ("lemtk".equals(mode) || "lemtk-compatible".equals(mode)) return false;

        String baseUrl = source.baseUrl != null ? source.baseUrl.toLowerCase(Locale.ROOT) : "";
        String name = source.name != null ? source.name.toLowerCase(Locale.ROOT) : "";
        return baseUrl.contains("/adapter-service") || name.contains("adapter");
    }

    private static String queryAdapterSource(SourceConfig source, String question, int type, String options) throws Exception {
        String urlStr = buildAdapterSearchUrl(source.baseUrl, source.token);
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        ActiveConnection.set(conn);
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "StarX/1.2");

            JSONObject body = new JSONObject();
            body.put("question", question);
            if (type >= 0) {
                body.put("type", type);
            }
            JSONArray optionArray = buildAdapterOptionArray(options);
            if (optionArray.length() > 0) {
                body.put("options", optionArray);
            }

            byte[] payload = body.toString().getBytes("UTF-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                Logx.w("TikuApi(adapter): HTTP " + code + " for " + urlStr);
                return null;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            return parseAdapterResponse(sb.toString(), type);
        } finally {
            ActiveConnection.clear();
            conn.disconnect();
        }
    }

    private static String buildAdapterSearchUrl(String baseUrl, String rawParams) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        if (normalized.endsWith("/") && normalized.indexOf('?') == -1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (!lower.contains(ADAPTER_SEARCH_PATH)) {
            if (lower.endsWith("/adapter-service")) {
                normalized = normalized + "/search";
            } else if (!lower.contains("/adapter-service")) {
                normalized = normalized + ADAPTER_SEARCH_PATH;
            }
        }

        String extraParams = normalizeAdapterQueryParams(rawParams);
        if (!extraParams.isEmpty()) {
            normalized = normalized + (normalized.contains("?") ? "&" : "?") + extraParams;
        }
        return normalized;
    }

    private static String normalizeAdapterQueryParams(String rawParams) {
        if (rawParams == null || rawParams.trim().isEmpty()) return "";
        String normalized = rawParams.trim();
        while (normalized.startsWith("?") || normalized.startsWith("&")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static JSONArray buildAdapterOptionArray(String rawOptions) {
        JSONArray array = new JSONArray();
        if (rawOptions == null || rawOptions.trim().isEmpty()) return array;
        String[] parts = rawOptions.split("\\|");
        for (String part : parts) {
            if (part == null) continue;
            String cleaned = part.trim().replaceFirst("^[A-Za-z][.、:：\\s]\\s*", "").trim();
            if (!cleaned.isEmpty()) {
                array.put(cleaned);
            }
        }
        return array;
    }

    private static String parseAdapterResponse(String body, int requestedType) {
        if (body == null || body.trim().isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(body);

            Object directAnswer = root.opt("answer");
            if (directAnswer instanceof JSONObject) {
                return extractAdapterAnswer((JSONObject) directAnswer, root.optInt("type", requestedType));
            }
            if (directAnswer instanceof String) {
                String answer = normalizeAdapterText((String) directAnswer);
                return answer.isEmpty() || QuestionCache.isInvalidAnswerText(answer) ? null : answer;
            }

            JSONObject data = root.optJSONObject("data");
            if (data != null) {
                Object nestedAnswer = data.opt("answer");
                if (nestedAnswer instanceof JSONObject) {
                    return extractAdapterAnswer((JSONObject) nestedAnswer, data.optInt("type", root.optInt("type", requestedType)));
                }
                if (nestedAnswer instanceof String) {
                    String answer = normalizeAdapterText((String) nestedAnswer);
                    return answer.isEmpty() || QuestionCache.isInvalidAnswerText(answer) ? null : answer;
                }
            }
        } catch (Throwable t) {
            Logx.w("TikuApi(adapter): parse failed: " + t.getMessage());
        }
        return null;
    }

    private static String parseZxSeekResponse(String body) {
        if (body == null || body.trim().isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(body);
            String answer = extractZxSeekAnswer(root.opt("answer"));
            if (!answer.isEmpty() && !QuestionCache.isInvalidAnswerText(answer)) {
                return answer;
            }
            answer = extractZxSeekAnswer(root.opt("data"));
            if (!answer.isEmpty() && !QuestionCache.isInvalidAnswerText(answer)) {
                return answer;
            }
            answer = extractZxSeekAnswer(root.opt("result"));
            if (!answer.isEmpty() && !QuestionCache.isInvalidAnswerText(answer)) {
                return answer;
            }
        } catch (Throwable t) {
            Logx.w("TikuApi(zxseek): parse failed: " + t.getMessage());
        }
        return null;
    }

    private static String extractZxSeekAnswer(Object payload) {
        if (payload == null) return "";
        if (payload instanceof String) {
            return normalizeAdapterText((String) payload);
        }
        if (payload instanceof JSONArray) {
            return joinAdapterTextArray((JSONArray) payload);
        }
        if (!(payload instanceof JSONObject)) {
            return "";
        }
        JSONObject obj = (JSONObject) payload;
        Object answerNode = obj.opt("answer");
        if (answerNode instanceof JSONObject) {
            String nested = extractAdapterAnswer((JSONObject) answerNode, obj.optInt("type", -1));
            if (nested != null && !nested.isEmpty()) return nested;
        }
        if (answerNode instanceof JSONArray) {
            String nested = joinAdapterTextArray((JSONArray) answerNode);
            if (!nested.isEmpty()) return nested;
        }
        if (answerNode instanceof String) {
            String nested = normalizeAdapterText((String) answerNode);
            if (!nested.isEmpty()) return nested;
        }

        String answer = firstNonEmpty(
                normalizeAdapterText(obj.optString("answerText", "")),
                normalizeAdapterText(obj.optString("bestAnswer", "")),
                normalizeAdapterText(obj.optString("content", "")),
                normalizeAdapterText(obj.optString("result", ""))
        );
        if (!answer.isEmpty()) return answer;

        answer = joinAdapterTextArray(obj.optJSONArray("bestAnswer"));
        if (!answer.isEmpty()) return answer;

        answer = joinAdapterTextArray(obj.optJSONArray("answers"));
        if (!answer.isEmpty()) return answer;

        return "";
    }

    private static String extractAdapterAnswer(JSONObject answerObj, int type) {
        String answerKeys = joinAdapterKeyArray(answerObj.optJSONArray("answerKey"));
        String answerKeyText = normalizeAdapterKeyText(answerObj.optString("answerKeyText", ""));
        String bestAnswer = joinAdapterTextArray(answerObj.optJSONArray("bestAnswer"));
        String answerText = normalizeAdapterText(answerObj.optString("answerText", ""));

        boolean choiceLike = type == 0 || type == 1 || type == 3 || !answerKeys.isEmpty() || !answerKeyText.isEmpty();
        String answer = choiceLike
                ? firstNonEmpty(answerKeys, answerKeyText, bestAnswer, answerText)
                : firstNonEmpty(bestAnswer, answerText, answerKeys, answerKeyText);
        if (answer.isEmpty() || QuestionCache.isInvalidAnswerText(answer)) {
            return null;
        }
        return answer;
    }

    private static String joinAdapterKeyArray(JSONArray arr) {
        if (arr == null || arr.length() == 0) return "";
        List<String> values = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            String value = normalizeAdapterText(arr.optString(i, "")).toUpperCase(Locale.ROOT);
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values.isEmpty() ? "" : String.join("#", values);
    }

    private static String normalizeAdapterKeyText(String raw) {
        String normalized = normalizeAdapterText(raw).replaceAll("[,，\\s]+", "");
        if (normalized.isEmpty()) return "";
        if (normalized.matches("^[A-Za-z]+$")) {
            List<String> values = new ArrayList<>();
            for (int i = 0; i < normalized.length(); i++) {
                values.add(String.valueOf(Character.toUpperCase(normalized.charAt(i))));
            }
            return String.join("#", values);
        }
        return normalized;
    }

    private static String joinAdapterTextArray(JSONArray arr) {
        if (arr == null || arr.length() == 0) return "";
        List<String> values = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            String value = normalizeAdapterText(arr.optString(i, ""));
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values.isEmpty() ? "" : String.join("#", values);
    }

    private static String normalizeAdapterText(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static String abbreviateEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isEmpty()) return "unknown";
        if (endpoint.length() <= 24) return endpoint;
        return endpoint.substring(0, 24) + "...";
    }

    private static List<SourceConfig> parseSourceConfigs(String rawJson) {
        List<SourceConfig> result = new ArrayList<>();
        if (rawJson == null || rawJson.trim().isEmpty()) return result;
        try {
            JSONArray arr = new JSONArray(rawJson);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                String name = obj.optString("name", "题库" + (i + 1));
                String baseUrl = obj.optString("baseUrl", "").trim();
                String token = obj.optString("token", "").trim();
                String mode = obj.optString("mode", "").trim();
                String answerPath = obj.optString("answerPath", "").trim();
                boolean enabled = obj.optBoolean("enabled", false);
                if (baseUrl.isEmpty()) continue;
                result.add(new SourceConfig(
                        name,
                        baseUrl,
                        token,
                        enabled,
                        mode,
                        answerPath,
                        parseTemplateParams(obj.optJSONArray("templateParams"))
                ));
            }
        } catch (Throwable t) {
            Logx.w("TikuApi: parse source config failed: " + t.getMessage());
        }
        return result;
    }

    private static List<TemplateParamConfig> parseTemplateParams(JSONArray arr) {
        List<TemplateParamConfig> result = new ArrayList<>();
        if (arr == null) return result;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) continue;
            String key = obj.optString("key", "").trim();
            String value = obj.optString("value", "").trim();
            String placement = obj.optString("placement", PLACEMENT_QUERY).trim().toLowerCase(Locale.ROOT);
            if (!PLACEMENT_HEADER.equals(placement) && !PLACEMENT_BODY.equals(placement)) {
                placement = PLACEMENT_QUERY;
            }
            if (key.isEmpty() && value.isEmpty()) continue;
            result.add(new TemplateParamConfig(key, value, placement));
        }
        return result;
    }

    private static List<SourceConfig> defaultSources() {
        List<SourceConfig> result = new ArrayList<>();
        result.add(new SourceConfig("柠檬推荐节点", "https://api.vanse.top", "", true, "lemtk"));
        result.add(new SourceConfig("柠檬官方节点", "https://api.lemtk.xyz", "", false, "lemtk"));
        result.add(new SourceConfig("ZXSeek / Wkexam", ZXSEEK_DEFAULT_API, "", false, "zxseek"));
        return result;
    }

    private static final class TemplateParamConfig {
        final String key;
        final String value;
        final String placement;

        TemplateParamConfig(String key, String value, String placement) {
            this.key = key != null ? key : "";
            this.value = value != null ? value : "";
            this.placement = placement != null ? placement : PLACEMENT_QUERY;
        }
    }

    private static final class SourceConfig {
        final String name;
        final String baseUrl;
        final String token;
        final boolean enabled;
        final String mode;
        final String answerPath;
        final List<TemplateParamConfig> templateParams;

        SourceConfig(String name, String baseUrl, String token, boolean enabled, String mode) {
            this(name, baseUrl, token, enabled, mode, "", new ArrayList<>());
        }

        SourceConfig(String name, String baseUrl, String token, boolean enabled, String mode,
                     String answerPath, List<TemplateParamConfig> templateParams) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.token = token;
            this.enabled = enabled;
            this.mode = mode;
            this.answerPath = answerPath != null ? answerPath : "";
            this.templateParams = templateParams != null ? templateParams : new ArrayList<>();
        }
    }

    private static String queryEndpoint(String endpoint, String question) throws Exception {
        String urlStr = endpoint + URLEncoder.encode(question, "UTF-8");
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        ActiveConnection.set(conn);
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
                if (answer == null) answer = parseZxSeekResponse(body);
                if (answer == null) answer = parseAdapterResponse(body, -1);
                if (answer == null) answer = extractJsonValue(body, "data");
                return QuestionCache.isInvalidAnswerText(answer) ? null : answer;
            }
            // 去除首尾引号
            if (body.startsWith("\"") && body.endsWith("\"")) {
                body = body.substring(1, body.length() - 1);
            }
            return body.isEmpty() || QuestionCache.isInvalidAnswerText(body) ? null : body;
        } finally {
            ActiveConnection.clear();
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
