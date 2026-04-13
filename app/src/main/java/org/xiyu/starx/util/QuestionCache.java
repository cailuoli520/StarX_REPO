package org.xiyu.starx.util;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.security.MessageDigest;

/**
 * 本地题库缓存 — LRU + TTL 策略
 *
 * SQLite 存储: question_hash → answer + source + timestamp
 * 最多 500 条，超出淘汰最旧；7 天过期自动清理
 */
public class QuestionCache {

    private static final String DB_NAME = "starx_question_cache.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "cache";
    private static final int MAX_ENTRIES = 500;
    private static final long TTL_MS = 7L * 24 * 60 * 60 * 1000; // 7 days

    private static QuestionCache instance;
    private final DbHelper dbHelper;

    private QuestionCache(Context context) {
        dbHelper = new DbHelper(context.getApplicationContext());
    }

    public static synchronized void init(Context context) {
        if (instance == null && context != null) {
            instance = new QuestionCache(context);
        }
    }

    public static QuestionCache get() {
        if (instance == null) {
            // 延迟重试：hook 时 currentApplication 可能为 null
            try {
                Class<?> atClass = Class.forName("android.app.ActivityThread");
                Object app = atClass.getMethod("currentApplication").invoke(null);
                if (app != null) {
                    init((android.content.Context) app);
                }
            } catch (Throwable ignored) {}
        }
        return instance;
    }

    /**
     * 查询缓存
     *
     * @return CacheEntry 或 null（未命中/已过期）
     */
    public CacheEntry lookup(String question) {
        return lookup(question, null);
    }

    /**
     * 查询缓存（含选项，避免同题干不同选项的缓存冲突）
     * 如果精确 hash 未命中且 options 非空，回退到仅题干 hash（兼容离线导入条目）
     */
    public CacheEntry lookup(String question, String options) {
        if (question == null || question.trim().isEmpty()) return null;
        String hash = hash(question, options);
        CacheEntry entry = lookupByHash(hash);
        if (entry == null && options != null && !options.isEmpty()) {
            // 回退：仅匹配离线导入条目，避免运行期无 options 缓存污染有 options 查询
            CacheEntry fallback = lookupByHash(hash(question, null));
            if (fallback != null && "离线导入".equals(fallback.source)) {
                entry = fallback;
            }
        }
        return entry;
    }

    private CacheEntry lookupByHash(String hash) {
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = dbHelper.getReadableDatabase();
            cursor = db.query(TABLE,
                    new String[]{"answer", "source", "timestamp"},
                    "hash = ?", new String[]{hash},
                    null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                long ts = cursor.getLong(2);
                if (System.currentTimeMillis() - ts > TTL_MS) {
                    db.delete(TABLE, "hash = ?", new String[]{hash});
                    return null;
                }
                ContentValues update = new ContentValues();
                update.put("timestamp", System.currentTimeMillis());
                db.update(TABLE, update, "hash = ?", new String[]{hash});
                return new CacheEntry(
                        cursor.getString(0),
                        cursor.getString(1)
                );
            }
        } catch (Throwable t) {
            Logx.w("QuestionCache: lookup error: " + t.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    /**
     * 写入缓存
     */
    public void put(String question, String answer, String source) {
        put(question, null, answer, source);
    }

    /**
     * 写入缓存（含选项）
     */
    public void put(String question, String options, String answer, String source) {
        if (question == null || answer == null) return;
        String hash = hash(question, options);
        SQLiteDatabase db = null;
        try {
            db = dbHelper.getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("hash", hash);
            cv.put("question", question.length() > 200 ? question.substring(0, 200) : question);
            cv.put("answer", answer);
            cv.put("source", source);
            cv.put("timestamp", System.currentTimeMillis());
            db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            evict(db);
        } catch (Throwable t) {
            Logx.w("QuestionCache: put error: " + t.getMessage());
        }
    }

    /**
     * 淘汰超出上限的旧条目
     */
    private void evict(SQLiteDatabase db) {
        try {
            Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
            if (c != null && c.moveToFirst()) {
                int count = c.getInt(0);
                c.close();
                if (count > MAX_ENTRIES) {
                    int toDelete = count - MAX_ENTRIES;
                    db.execSQL("DELETE FROM " + TABLE + " WHERE hash IN (" +
                            "SELECT hash FROM " + TABLE + " ORDER BY timestamp ASC LIMIT " + toDelete + ")");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 清除所有缓存
     */
    public void clear() {
        try {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            db.delete(TABLE, null, null);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 从 JSON 文件导入离线题库
     * 格式: [{"question":"xxx","answer":"yyy"}, ...]
     *
     * @return 导入成功的条数
     */
    public int importFromJson(String json) {
        if (json == null || json.trim().isEmpty()) return 0;
        int imported = 0;
        SQLiteDatabase db = null;
        try {
            db = dbHelper.getWritableDatabase();
            db.beginTransaction();

            // 手动解析 JSON 数组（避免依赖 org.json 在 hook 侧 classloader 问题）
            int idx = json.indexOf('[');
            if (idx == -1) return 0;
            int arrayEnd = json.lastIndexOf(']');
            if (arrayEnd == -1) return 0;
            String content = json.substring(idx + 1, arrayEnd);

            int pos = 0;
            while (pos < content.length()) {
                int objStart = content.indexOf('{', pos);
                if (objStart == -1) break;
                // 用深度计数找到匹配的 }，正确处理值中包含 {} 的情况
                int objEnd = findMatchingBrace(content, objStart);
                if (objEnd == -1) break;
                String obj = content.substring(objStart, objEnd + 1);

                String q = extractField(obj, "question");
                String a = extractField(obj, "answer");
                if (q != null && a != null && !q.isEmpty() && !a.isEmpty()) {
                    String hash = hash(q, null);
                    ContentValues cv = new ContentValues();
                    cv.put("hash", hash);
                    cv.put("question", q.length() > 200 ? q.substring(0, 200) : q);
                    cv.put("answer", a);
                    cv.put("source", "离线导入");
                    cv.put("timestamp", System.currentTimeMillis());
                    db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                    imported++;
                }
                pos = objEnd + 1;
            }

            db.setTransactionSuccessful();
        } catch (Throwable t) {
            Logx.w("QuestionCache: import error: " + t.getMessage());
        } finally {
            if (db != null) {
                try { db.endTransaction(); } catch (Throwable ignored) {}
            }
        }
        Logx.i("QuestionCache: imported " + imported + " entries");
        return imported;
    }

    private static String extractField(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx == -1) return null;
        int colon = json.indexOf(":", idx + pattern.length());
        if (colon == -1) return null;
        int qStart = json.indexOf("\"", colon + 1);
        if (qStart == -1) return null;
        int qEnd = qStart + 1;
        while (qEnd < json.length()) {
            if (json.charAt(qEnd) == '\\') { qEnd += 2; continue; }
            if (json.charAt(qEnd) == '"') break;
            qEnd++;
        }
        if (qEnd >= json.length()) return null;
        return unescapeJson(json.substring(qStart + 1, qEnd));
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

    /**
     * 找到与 objStart 处 { 匹配的 }，正确跳过字符串内容和嵌套大括号
     */
    private static int findMatchingBrace(String s, int objStart) {
        int depth = 0;
        boolean inString = false;
        for (int i = objStart; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\') { i++; continue; } // 跳过转义
                if (c == '"') inString = false;
            } else {
                if (c == '"') { inString = true; }
                else if (c == '{') { depth++; }
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
        }
        return -1;
    }

    private static String hash(String question, String options) {
        try {
            String cleaned = question.replaceAll("<[^>]*>", "").replaceAll("\\s+", "").trim().toLowerCase();
            if (options != null && !options.isEmpty()) {
                cleaned += "|" + options.replaceAll("\\s+", "").toLowerCase();
            }
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(cleaned.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Throwable t) {
            return String.valueOf(question.hashCode());
        }
    }

    public static class CacheEntry {
        public final String answer;
        public final String source;

        public CacheEntry(String answer, String source) {
            this.answer = answer;
            this.source = source;
        }
    }

    private static class DbHelper extends SQLiteOpenHelper {
        DbHelper(Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE + " (" +
                    "hash TEXT PRIMARY KEY," +
                    "question TEXT," +
                    "answer TEXT NOT NULL," +
                    "source TEXT," +
                    "timestamp INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX idx_cache_ts ON " + TABLE + "(timestamp)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE);
            onCreate(db);
        }
    }
}
