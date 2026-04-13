package org.xiyu.starx.util;

import java.net.HttpURLConnection;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 每任务级 HTTP 连接取消句柄，避免线程池线程 ID 复用导致的串扰。
 * <p>
 * 使用方式：
 * <ol>
 *   <li>调用方创建实例，提交给工作线程后通过 {@link #install} 绑定</li>
 *   <li>HTTP 客户端在 I/O 前调用静态 {@link #set}，I/O 后调用 {@link #clear}</li>
 *   <li>取消方通过持有的实例调用 {@link #disconnect()} 强制断开</li>
 *   <li>工作线程结束时调用 {@link #uninstall} 清理 ThreadLocal</li>
 * </ol>
 */
public class ActiveConnection {
    private final AtomicReference<HttpURLConnection> ref = new AtomicReference<>();

    private static final ThreadLocal<ActiveConnection> CURRENT = new ThreadLocal<>();

    /** 将此句柄绑定到当前工作线程 */
    public static void install(ActiveConnection handle) {
        CURRENT.set(handle);
    }

    /** 解绑当前线程的句柄 */
    public static void uninstall() {
        CURRENT.remove();
    }

    /** 在当前线程的句柄上注册活跃连接（无句柄时为 no-op） */
    public static void set(HttpURLConnection conn) {
        ActiveConnection h = CURRENT.get();
        if (h != null && conn != null) h.ref.set(conn);
    }

    /** 清除当前线程句柄上的连接引用（无句柄时为 no-op） */
    public static void clear() {
        ActiveConnection h = CURRENT.get();
        if (h != null) h.ref.set(null);
    }

    /** 强制断开此句柄上注册的连接（线程安全，可从任意线程调用） */
    public void disconnect() {
        HttpURLConnection conn = ref.getAndSet(null);
        if (conn != null) {
            try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }
}
