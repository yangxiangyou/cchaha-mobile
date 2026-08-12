package com.cchaha.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 地址存储：多台电脑地址管理 + 当前地址。
 * 数据以 JSON 加密后存入 SharedPreferences（密钥在系统 Keystore）。
 */
public final class Storage {

    private static final String TAG = "Storage";
    private static final String PREFS = "haha_remote";
    private static final String KEY_HOSTS_ENC = "hosts_enc";      // 加密后的地址列表 JSON
    private static final String KEY_CURRENT = "current_id";

    /** 一个已保存的连接地址 */
    public static class SavedHost {
        public String id;          // 唯一 id（随机）
        public String name;        // 展示名
        public String url;         // 完整 H5 链接（含 token）
        public long lastUsedAt;    // 最近使用时间

        SavedHost(String id, String name, String url, long lastUsedAt) {
            this.id = id;
            this.name = name;
            this.url = url;
            this.lastUsedAt = lastUsedAt;
        }
    }

    private final SharedPreferences prefs;
    private final CryptoStore crypto;
    private final List<SavedHost> hosts = new ArrayList<>();
    private String currentId = "";

    public Storage(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        crypto = new CryptoStore();
        currentId = prefs.getString(KEY_CURRENT, "");
        loadHosts();
    }

    /** 从持久层重读（Activity onResume 时调用，避免多实例内存列表互相覆盖的 lost update） */
    public void reload() {
        hosts.clear();
        loadHosts();
    }

    private void loadHosts() {
        hosts.clear();
        try {
            String enc = prefs.getString(KEY_HOSTS_ENC, "");
            if (enc == null || enc.isEmpty()) return;
            String json = crypto.decrypt(enc);
            if (json == null) {
                // 解密失败：可能是 keystore 降级期的明文存储（keystore 恢复后解密不匹配）。
                // 先尝试按明文 JSON 解析（对象 { 或数组 [ 均可能），能解析则保留数据；否则才是真损坏，清除。
                if (enc.startsWith("{") || enc.startsWith("[")) {
                    Log.w(TAG, "hosts stored plaintext (keystore was degraded) — reusing data");
                    parseHostsJson(enc);
                    return;
                }
                Log.w(TAG, "hosts decrypt failed, clearing corrupted data");
                prefs.edit().remove(KEY_HOSTS_ENC).apply();
                return;
            }
            parseHostsJson(json);
        } catch (Exception e) {
            Log.e(TAG, "load hosts failed", e);
        }
    }

    private void parseHostsJson(String json) {
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                SavedHost h = new SavedHost(
                        o.optString("id"),
                        o.optString("name"),
                        o.optString("url"),
                        o.optLong("lastUsedAt", 0));
                if (UrlUtils.isUsable(h.url)) hosts.add(h);
            }
        } catch (Exception e) {
            Log.e(TAG, "parse hosts failed", e);
        }
    }

    private void persist() {
        try {
            JSONArray arr = new JSONArray();
            for (SavedHost h : hosts) {
                JSONObject o = new JSONObject();
                o.put("id", h.id);
                o.put("name", h.name);
                o.put("url", h.url);
                o.put("lastUsedAt", h.lastUsedAt);
                arr.put(o);
            }
            prefs.edit().putString(KEY_HOSTS_ENC, crypto.encrypt(arr.toString())).apply();
        } catch (Exception e) {
            Log.e(TAG, "persist hosts failed", e);
        }
    }

    public List<SavedHost> getHosts() {
        return new ArrayList<>(hosts);
    }

    /** 添加或更新（按 url 去重：同一地址只保留一条） */
    public SavedHost upsertHost(String url) {
        String normalized = UrlUtils.normalize(url);
        if (normalized == null) return null;
        long now = System.currentTimeMillis();
        for (SavedHost h : hosts) {
            if (h.url.equals(normalized)) {
                h.lastUsedAt = now;
                if (h.name == null || h.name.isEmpty()) h.name = UrlUtils.extractLabel(normalized);
                persist();
                return h;
            }
        }
        SavedHost h = new SavedHost(java.util.UUID.randomUUID().toString(),
                UrlUtils.extractLabel(normalized), normalized, now);
        hosts.add(h);
        persist();
        return h;
    }

    public void removeHost(String id) {
        for (int i = 0; i < hosts.size(); i++) {
            if (hosts.get(i).id.equals(id)) {
                hosts.remove(i);
                if (currentId.equals(id)) {
                    currentId = "";
                    prefs.edit().remove(KEY_CURRENT).apply();
                }
                persist();
                return;
            }
        }
    }

    /** 重命名（展示名） */
    public void renameHost(String id, String name) {
        for (SavedHost h : hosts) {
            if (h.id.equals(id)) {
                h.name = (name == null || name.trim().isEmpty()) ? UrlUtils.extractLabel(h.url) : name.trim();
                persist();
                return;
            }
        }
    }

    public SavedHost getHost(String id) {
        for (SavedHost h : hosts) {
            if (h.id.equals(id)) return h;
        }
        return null;
    }

    public SavedHost getCurrentHost() {
        return getHost(currentId);
    }

    public void setCurrentHost(String id) {
        SavedHost h = getHost(id);
        if (h == null) return;
        currentId = id;
        h.lastUsedAt = System.currentTimeMillis();
        prefs.edit().putString(KEY_CURRENT, id).apply();
        persist();
    }

}
