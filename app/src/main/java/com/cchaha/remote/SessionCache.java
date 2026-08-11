package com.cchaha.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话列表本地缓存：打开 App 秒显上次的会话列表，
 * 服务端不可用时（cc-haha 假死/断网）依然能看到历史会话。
 */
public final class SessionCache {

    private static final String TAG = "SessionCache";
    private static final String PREFS = "session_cache";
    private static final String KEY_JSON = "sessions_json";
    private static final String KEY_SAVED_AT = "saved_at";
    private static final int MAX_ITEMS = 200;

    private final SharedPreferences prefs;

    public SessionCache(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void save(List<SessionApi.SessionInfo> sessions) {
        try {
            JSONArray arr = new JSONArray();
            int n = Math.min(sessions.size(), MAX_ITEMS);
            for (int i = 0; i < n; i++) {
                SessionApi.SessionInfo s = sessions.get(i);
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("title", s.title);
                o.put("createdAtMs", s.createdAtMs);
                o.put("modifiedAtMs", s.modifiedAtMs);
                o.put("messageCount", s.messageCount);
                o.put("projectRoot", s.projectRoot);
                o.put("workDir", s.workDir);
                o.put("modelId", s.modelId);
                arr.put(o);
            }
            prefs.edit()
                    .putString(KEY_JSON, arr.toString())
                    .putLong(KEY_SAVED_AT, System.currentTimeMillis())
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "save failed", e);
        }
    }

    /** 读取缓存（无缓存返回空列表） */
    public List<SessionApi.SessionInfo> load() {
        List<SessionApi.SessionInfo> list = new ArrayList<>();
        try {
            String json = prefs.getString(KEY_JSON, "");
            if (json.isEmpty()) return list;
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                SessionApi.SessionInfo s = new SessionApi.SessionInfo(o);
                list.add(s);
            }
        } catch (Exception e) {
            Log.e(TAG, "load failed", e);
        }
        return list;
    }

    public long savedAtMs() {
        return prefs.getLong(KEY_SAVED_AT, 0);
    }
}
