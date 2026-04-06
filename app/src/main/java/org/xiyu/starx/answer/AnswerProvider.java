package org.xiyu.starx.answer;

import org.xiyu.starx.util.Logx;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 答案获取协调器 — 题库优先，AI 兜底
 *
 * 调用链: 题库 API → OpenAI 兼容 → Gemini → null
 */
public class AnswerProvider {

    public interface Callback {
        void onAnswer(String answer, String source);
        void onFailed();
    }

    private AiApi openaiApi;
    private AiApi geminiApi;
    private final ExecutorService executor = Executors.newCachedThreadPool();

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

    /**
     * 同步查询答案（应在后台线程调用）
     * @return 答案，未找到返回 null
     */
    public Result query(String question) {
        // 1. 题库优先
        try {
            String tikuAnswer = TikuApi.query(question);
            if (tikuAnswer != null) {
                return new Result(tikuAnswer, "题库");
            }
        } catch (Throwable t) {
            Logx.w("AnswerProvider: tiku error: " + t.getMessage());
        }

        // 2. OpenAI 兼容
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

        // 3. Gemini
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

    /**
     * 异步查询答案
     */
    public void queryAsync(String question, Callback callback) {
        executor.submit(() -> {
            Result result = query(question);
            if (result != null) {
                callback.onAnswer(result.answer, result.source);
            } else {
                callback.onFailed();
            }
        });
    }

    /**
     * 带超时的同步查询
     */
    public Result queryWithTimeout(String question, long timeoutMs) {
        Future<Result> future = executor.submit(() -> query(question));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            future.cancel(true);
            Logx.w("AnswerProvider: timeout after " + timeoutMs + "ms");
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
}
