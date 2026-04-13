package org.xiyu.starx.answer;

import org.xiyu.starx.util.ActiveConnection;
import org.xiyu.starx.util.Logx;
import org.xiyu.starx.util.QuestionCache;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 答案获取协调器 — 缓存优先，多源题库，AI 兜底
 *
 * 调用链: 本地缓存 → 柠檬题库 → 题库 API → OpenAI 兼容 → Gemini → null
 */
public class AnswerProvider {

    public interface Callback {
        void onAnswer(String answer, String source);
        void onFailed();
    }

    private AiApi openaiApi;
    private AiApi geminiApi;
    private boolean cacheEnabled = true;
    private final ExecutorService executor = new ThreadPoolExecutor(
            2, 4, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(16));

    public void setOpenAI(String apiKey, String baseUrl, String model) {
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            this.openaiApi = AiApi.openai(apiKey, baseUrl, model);
            Logx.i("AnswerProvider: OpenAI configured, url=" + baseUrl);
        } else {
            this.openaiApi = null;
        }
    }

    public void setGemini(String apiKey, String model) {
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            this.geminiApi = AiApi.gemini(apiKey, model);
            Logx.i("AnswerProvider: Gemini configured");
        } else {
            this.geminiApi = null;
        }
    }

    public void setCacheEnabled(boolean enabled) {
        this.cacheEnabled = enabled;
    }

    /**
     * 同步查询答案（应在后台线程调用）
     * @return 答案，未找到返回 null
     */
    public Result query(String question) {
        return query(question, -1, null);
    }

    /**
     * 同步查询答案（带题型和选项，供柠檬题库使用）
     *
     * @param question 题目文本
     * @param type     题型 (0单选 1多选 2填空 3判断 4简答)，-1 表示未知
     * @param options  选项文本（如 "A.xxx\nB.yyy"），可 null
     */
    public Result query(String question, int type, String options) {
        // 0. 本地缓存（key 包含 question + options，避免同题干不同选项命中错误缓存）
        if (cacheEnabled) {
            QuestionCache cache = QuestionCache.get();
            if (cache != null) {
                try {
                    QuestionCache.CacheEntry entry = cache.lookup(question, options);
                    if (entry != null) {
                        Logx.i("AnswerProvider: cache hit, source=" + entry.source);
                        return new Result(entry.answer, "缓存(" + entry.source + ")");
                    }
                } catch (Throwable t) {
                    Logx.w("AnswerProvider: cache error: " + t.getMessage());
                }
            }
        }

        // 1. 柠檬题库优先
        try {
            String lemtkAnswer = LemTkApi.query(question, type >= 0 ? String.valueOf(type) : null, options);
            if (lemtkAnswer != null) {
                writeCache(question, lemtkAnswer, "柠檬题库", options);
                return new Result(lemtkAnswer, "柠檬题库");
            }
        } catch (Throwable t) {
            Logx.w("AnswerProvider: lemtk error: " + t.getMessage());
        }
        if (Thread.currentThread().isInterrupted()) return null;

        // 2. 题库 API
        try {
            String tikuAnswer = TikuApi.query(question);
            if (tikuAnswer != null) {
                writeCache(question, tikuAnswer, "题库", options);
                return new Result(tikuAnswer, "题库");
            }
        } catch (Throwable t) {
            Logx.w("AnswerProvider: tiku error: " + t.getMessage());
        }
        if (Thread.currentThread().isInterrupted()) return null;

        // 3. OpenAI 兼容（AI 答案不写入缓存，防止缓存污染）
        if (openaiApi != null && openaiApi.isConfigured()) {
            try {
                String aiAnswer = openaiApi.ask(question);
                if (aiAnswer != null) {
                    return new Result(aiAnswer, "AI-OpenAI");
                }
            } catch (Throwable t) {
                Logx.w("AnswerProvider: openai error: " + t.getMessage());
            }
        }
        if (Thread.currentThread().isInterrupted()) return null;

        // 4. Gemini（AI 答案不写入缓存，防止缓存污染）
        if (geminiApi != null && geminiApi.isConfigured()) {
            try {
                String geminiAnswer = geminiApi.ask(question);
                if (geminiAnswer != null) {
                    return new Result(geminiAnswer, "AI-Gemini");
                }
            } catch (Throwable t) {
                Logx.w("AnswerProvider: gemini error: " + t.getMessage());
            }
        }

        return null;
    }

    private void writeCache(String question, String answer, String source) {
        writeCache(question, answer, source, null);
    }

    private void writeCache(String question, String answer, String source, String options) {
        if (!cacheEnabled) return;
        QuestionCache cache = QuestionCache.get();
        if (cache != null) {
            try {
                cache.put(question, options, answer, source);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 异步查询答案
     */
    public void queryAsync(String question, Callback callback) {
        queryAsync(question, -1, null, callback);
    }

    /**
     * 异步查询答案（带题型和选项）
     */
    public void queryAsync(String question, int type, String options, Callback callback) {
        try {
            executor.submit(() -> {
                Result result = query(question, type, options);
                if (result != null) {
                    callback.onAnswer(result.answer, result.source);
                } else {
                    callback.onFailed();
                }
            });
        } catch (RejectedExecutionException e) {
            Logx.w("AnswerProvider: async rejected, pool saturated");
            callback.onFailed();
        }
    }

    /**
     * 带超时的同步查询
     */
    public Result queryWithTimeout(String question, long timeoutMs) {
        return queryWithTimeout(question, -1, null, timeoutMs);
    }

    /**
     * 带超时的同步查询（带题型和选项）
     */
    public Result queryWithTimeout(String question, int type, String options, long timeoutMs) {
        return queryWithTimeout(question, type, options, null, timeoutMs);
    }

    /**
     * 带超时的同步查询（带题型、选项和图片 URL — 图片搜题入口）
     *
     * OCR 预处理在超时边界内执行：下载图片 → AI 识别文字 → 拼入题干 → 走正常搜题链
     */
    public Result queryWithTimeout(String question, int type, String options,
                                     List<String> imageUrls, long timeoutMs) {
        ActiveConnection handle = new ActiveConnection();
        Future<Result> future;
        try {
            future = executor.submit(() -> {
                ActiveConnection.install(handle);
                try {
                    String q = augmentWithOcr(question, imageUrls);
                    return query(q, type, options);
                } finally {
                    ActiveConnection.uninstall();
                }
            });
        } catch (RejectedExecutionException e) {
            Logx.w("AnswerProvider: rejected, pool saturated");
            return null;
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            handle.disconnect();
            Logx.w("AnswerProvider: timeout after " + timeoutMs + "ms");
            return null;
        } catch (java.util.concurrent.ExecutionException e) {
            future.cancel(true);
            handle.disconnect();
            Logx.w("AnswerProvider: query failed: " + e.getCause());
            return null;
        } catch (InterruptedException e) {
            future.cancel(true);
            handle.disconnect();
            Thread.currentThread().interrupt();
            Logx.w("AnswerProvider: interrupted");
            return null;
        }
    }

    public static class Result {
        public final String answer;
        public final String source;

        public Result(String answer, String source) {
            this.answer = answer;
            this.source = source;
        }
    }

    // ========== 图片 OCR 预处理 ==========

    /**
     * 如果有图片 URL，下载并 OCR，把识别到的文字拼入题干
     */
    private String augmentWithOcr(String question, List<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()) return question;
        AiApi primaryApi = pickOcrApi();
        if (primaryApi == null) {
            Logx.w("AnswerProvider: no AI configured, skip image OCR");
            return question;
        }
        StringBuilder ocrSb = new StringBuilder();
        for (String imgUrl : imageUrls) {
            if (Thread.currentThread().isInterrupted()) break;
            try {
                String[] imgData = downloadImageAsBase64(imgUrl);
                if (imgData == null) continue;

                String text = null;
                // 主 API
                try {
                    text = primaryApi.ocrImage(imgData[0], imgData[1]);
                } catch (Throwable t) {
                    Logx.w("AnswerProvider: primary OCR failed: " + t.getMessage());
                }
                // 回退到备用 API
                if ((text == null || text.isEmpty()) && !Thread.currentThread().isInterrupted()) {
                    AiApi fallback = pickFallbackOcrApi(primaryApi);
                    if (fallback != null) {
                        try {
                            text = fallback.ocrImage(imgData[0], imgData[1]);
                        } catch (Throwable t) {
                            Logx.w("AnswerProvider: fallback OCR failed: " + t.getMessage());
                        }
                    }
                }

                if (text != null && !text.isEmpty()) {
                    if (ocrSb.length() > 0) ocrSb.append(" ");
                    ocrSb.append(text);
                }
            } catch (Throwable t) {
                Logx.w("AnswerProvider: OCR failed for " + imgUrl + ": " + t.getMessage());
            }
        }
        if (ocrSb.length() == 0) return question;
        String ocrText = ocrSb.toString();
        Logx.i("AnswerProvider: OCR text => " + ocrText);
        if (question == null || question.isEmpty()) return ocrText;
        return question + " " + ocrText;
    }

    private AiApi pickOcrApi() {
        if (openaiApi != null && openaiApi.isConfigured()) return openaiApi;
        if (geminiApi != null && geminiApi.isConfigured()) return geminiApi;
        return null;
    }

    private AiApi pickFallbackOcrApi(AiApi primary) {
        if (primary != openaiApi && openaiApi != null && openaiApi.isConfigured()) return openaiApi;
        if (primary != geminiApi && geminiApi != null && geminiApi.isConfigured()) return geminiApi;
        return null;
    }

    /**
     * 下载图片并转为 Base64 — 自动携带宿主 WebView Cookie
     *
     * @return [base64, mimeType]，失败返回 null
     */
    private static String[] downloadImageAsBase64(String imageUrl) throws Exception {
        URL url = new URL(imageUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        ActiveConnection.set(conn);
        try {
            try {
                String cookies = android.webkit.CookieManager.getInstance().getCookie(imageUrl);
                if (cookies != null) conn.setRequestProperty("Cookie", cookies);
            } catch (Throwable ignored) {}
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "StarX/1.2");

            int code = conn.getResponseCode();
            if (code != 200) {
                Logx.w("AnswerProvider: image download HTTP " + code);
                return null;
            }

            String contentType = conn.getContentType();
            if (contentType == null) contentType = "image/jpeg";
            if (contentType.contains(";")) contentType = contentType.split(";")[0].trim();

            InputStream is = conn.getInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            is.close();

            if (baos.size() == 0) return null;
            String base64 = android.util.Base64.encodeToString(
                    baos.toByteArray(), android.util.Base64.NO_WRAP);
            return new String[]{base64, contentType};
        } finally {
            ActiveConnection.clear();
            conn.disconnect();
        }
    }
}
