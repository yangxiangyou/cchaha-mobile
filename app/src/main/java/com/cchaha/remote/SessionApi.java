package com.cchaha.remote;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * cc-haha H5 API 客户端（原生会话列表用）。
 * 认证机制：请求头 Referer 携带页面 URL（含 token），服务端从 Referer 提取 token 校验。
 */
public final class SessionApi {

    private static final String TAG = "SessionApi";
    private static final int TIMEOUT_MS = 20000;

    /** 一个会话的摘要 */
    public static class SessionInfo {
        public String id;
        public String title;
        public long createdAtMs;
        public long modifiedAtMs;
        public int messageCount;
        public String projectRoot;
        public String workDir;
        public String modelId;

        SessionInfo(JSONObject o) {
            id = o.optString("id");
            title = o.optString("title");
            // 兼容两种来源：API 的 ISO 字符串 / 本地缓存的毫秒时间戳
            createdAtMs = o.optLong("createdAtMs", parseTime(o.optString("createdAt")));
            modifiedAtMs = o.optLong("modifiedAtMs", parseTime(o.optString("modifiedAt")));
            messageCount = o.optInt("messageCount");
            projectRoot = o.optString("projectRoot");
            workDir = o.optString("workDir");
            modelId = o.optString("runtimeModelId");
            if (title == null || title.trim().isEmpty()) title = "(无标题会话)";
        }

        private static long parseTime(String iso) {
            try {
                return java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli();
            } catch (Exception e) {
                return 0;
            }
        }
    }

    /** 拉取会话列表。baseUrl 如 https://82.157.189.235，token 为 H5 token。失败抛异常。 */
    public static List<SessionInfo> fetchSessions(String baseUrl, String token) throws Exception {
        String urlStr = baseUrl + "/api/sessions?limit=200";
        String referer = baseUrl + "/?token=" + token;
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Referer", referer);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new Exception("API 返回 " + code);
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            JSONObject root = new JSONObject(sb.toString());
            JSONArray arr = root.optJSONArray("sessions");
            List<SessionInfo> list = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    SessionInfo s = new SessionInfo(arr.getJSONObject(i));
                    if (s.id != null && !s.id.isEmpty()) list.add(s);
                }
            }
            return list;
        } finally {
            conn.disconnect();
        }
    }

    /** 快速健康检查（sidecar 是否活着） */
    public static boolean ping(String baseUrl, String token) {
        try {
            String urlStr = baseUrl + "/api/status";
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            try {
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Referer", baseUrl + "/?token=" + token);
                return conn.getResponseCode() == 200;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "ping failed", e);
            return false;
        }
    }
}
