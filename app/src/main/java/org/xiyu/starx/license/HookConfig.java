package org.xiyu.starx.license;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xiyu.starx.util.Logx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class HookConfig {
    public final int version;
    public final Map<String, String> classes;
    public final String jsInject;
    public final List<String> tikuEndpoints;
    public final long expiresAt;

    private HookConfig(int version, Map<String, String> classes, String jsInject,
                        List<String> tikuEndpoints, long expiresAt) {
        this.version = version;
        this.classes = classes;
        this.jsInject = jsInject;
        this.tikuEndpoints = tikuEndpoints;
        this.expiresAt = expiresAt;
    }

    public String cls(String key) {
        String v = classes.get(key);
        return v != null ? v : "";
    }

    public static HookConfig parse(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            int v = obj.optInt("v", 1);

            Map<String, String> classes = new HashMap<>();
            JSONObject cls = obj.getJSONObject("classes");
            Iterator<String> keys = cls.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                classes.put(key, cls.getString(key));
            }

            String js = obj.optString("js_inject", "");

            List<String> tiku = new ArrayList<>();
            JSONArray arr = obj.optJSONArray("tiku_endpoints");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) tiku.add(arr.getString(i));
            }

            long expires = obj.optLong("expires_at", 0);
            return new HookConfig(v, classes, js, tiku, expires);
        } catch (Throwable t) {
            Logx.w("HookConfig: parse failed: " + t.getMessage());
            return null;
        }
    }
}
