package org.xiyu.starx.util;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 令牌桶限流器 — 防止过于频繁的 API 请求导致被封禁
 */
public class RateLimiter {

    private static final ConcurrentHashMap<String, Long> lastCallMap = new ConcurrentHashMap<>();
    private static final long DEFAULT_INTERVAL_MS = 5000;

    /**
     * 阻塞等待直至允许发出请求（默认 5s 间隔）
     *
     * @param key 限流标识（如 "lemtk", "tiku"）
     */
    public static void acquire(String key) {
        acquire(key, DEFAULT_INTERVAL_MS);
    }

    /**
     * 阻塞等待直至允许发出请求
     *
     * @param key        限流标识
     * @param intervalMs 最小间隔毫秒数
     */
    public static void acquire(String key, long intervalMs) {
        // 持有 per-key 锁期间 sleep：同 key 串行排队，不同 key 互不影响
        synchronized (getLock(key)) {
            long now = System.currentTimeMillis();
            Long last = lastCallMap.get(key);
            long wait = (last != null) ? intervalMs - (now - last) : 0;
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    // 被中断：未执行请求，不记录时间戳，不消耗配额
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            lastCallMap.put(key, System.currentTimeMillis());
        }
    }

    /**
     * 非阻塞检查：是否满足间隔条件
     *
     * @return true 表示可以请求
     */
    public static boolean tryAcquire(String key) {
        return tryAcquire(key, DEFAULT_INTERVAL_MS);
    }

    public static boolean tryAcquire(String key, long intervalMs) {
        // 绕过 per-key 锁，避免被 acquire 的 sleep 阻塞
        boolean[] allowed = {false};
        lastCallMap.compute(key, (k, last) -> {
            long now = System.currentTimeMillis();
            if (last != null && (now - last) < intervalMs) {
                return last;
            }
            allowed[0] = true;
            return now;
        });
        return allowed[0];
    }

    private static final ConcurrentHashMap<String, Object> lockMap = new ConcurrentHashMap<>();

    private static Object getLock(String key) {
        return lockMap.computeIfAbsent(key, k -> new Object());
    }
}
