package org.xiyu.starx.util;

/**
 * 版本无关类发现工具 — 全版本通杀
 *
 * 优先使用服务端下发的类名 (CxClasses)，
 * 失败时依次尝试已知历史版本的候选名称。
 */
public final class ClassFinder {
    private final ClassLoader cl;

    public ClassFinder(ClassLoader cl) {
        this.cl = cl;
    }

    /**
     * 解析类: 先用 CxClasses 值, 为空则依次尝试 fallback 候选名
     *
     * @param cxValue  CxClasses 字段值 (可能为空串)
     * @param fallbacks 已知历史版本的候选类名
     * @return 成功加载的 Class, 或 null
     */
    public Class<?> resolve(String cxValue, String... fallbacks) {
        if (cxValue != null && !cxValue.isEmpty()) {
            try {
                return Class.forName(cxValue, false, cl);
            } catch (ClassNotFoundException ignored) {}
        }
        return findFirst(fallbacks);
    }

    /**
     * 依次尝试多个候选类名, 返回第一个成功加载的
     */
    public Class<?> findFirst(String... names) {
        for (String name : names) {
            if (name == null || name.isEmpty()) continue;
            try {
                return Class.forName(name, false, cl);
            } catch (ClassNotFoundException ignored) {}
        }
        return null;
    }

    public ClassLoader getClassLoader() {
        return cl;
    }
}
