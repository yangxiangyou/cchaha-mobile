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

    }

    private static long parseTime(String iso) {
        try {
            return java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 拉取会话列表。baseUrl 如 https://192.168.1.20:8080，token 为 H5 token。失败抛异常。 */
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

    /** 一条消息内容块（渲染增强用：按块类型展示不同样式） */
    public static class Block {
        public String type;     // text / thinking / tool_use / tool_result / image / image_url / file / attachment
        public String text;     // 块文本（thinking/tool_use 等）
        public String toolName; // tool_use 的工具名（如 Bash、Read）

        Block(String type, String text, String toolName) {
            this.type = type == null ? "" : type;
            this.text = text;
            this.toolName = toolName;
        }
    }

    /** 一条消息（渲染模型：id/type/时间 + 内容块 + 兼容文本） */
    public static class Message {
        public String id;
        public String type;       // user / assistant / thinking / tool_use / tool_result ...
        public String text;       // 提取后的显示文本（兼容旧缓存）
        public long timestampMs;
        public java.util.List<Block> blocks = new ArrayList<>();

        Message(JSONObject o) {
            id = o.optString("id");
            type = o.optString("type", "unknown");
            timestampMs = parseTime(o.optString("timestamp"));
            StringBuilder sb = new StringBuilder();
            JSONArray content = o.optJSONArray("content");
            if (content != null) {
                for (int i = 0; i < content.length(); i++) {
                    JSONObject c = content.optJSONObject(i);
                    if (c == null) continue;
                    String ct = c.optString("type");
                    String txt = c.optString("text");
                    if (txt == null || txt.isEmpty()) txt = c.optString("thinking");
                    if (txt != null && !txt.isEmpty()) {
                        String toolName = "tool_use".equals(ct) ? c.optString("name") : "";
                        blocks.add(new Block(ct, txt, toolName));
                        if ("thinking".equals(ct)) sb.append("[思考] ");
                        else if ("tool_use".equals(ct)) sb.append("[工具] ");
                        else if ("tool_result".equals(ct)) sb.append("[结果] ");
                        sb.append(txt).append("\n");
                    } else if ("image".equals(ct) || "image_url".equals(ct)) {
                        blocks.add(new Block(ct, "", ""));
                        sb.append("[图片]\n");
                    } else if ("file".equals(ct) || "attachment".equals(ct)) {
                        blocks.add(new Block(ct, "", ""));
                        sb.append("[文件]\n");
                    }
                }
            }
            text = sb.toString().trim();
            if (text.isEmpty()) text = "(无文本内容)";
        }
    }

    /** 拉取会话全部消息（cc-haha API 忽略 limit，总是返回全量；大会话约 6 秒） */
    public static List<Message> fetchMessages(String baseUrl, String token, String sessionId) throws Exception {
        return parseMessages(fetchMessagesJson(baseUrl, token, sessionId));
    }

    /** 拉取消息并返回原始 JSON 字符串（供本地缓存直接存储，避免二次序列化） */
    public static String fetchMessagesJson(String baseUrl, String token, String sessionId) throws Exception {
        String urlStr = baseUrl + "/api/sessions/" + sessionId + "/messages";
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(120000); // 大会话需要更久
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Referer", baseUrl + "/?token=" + token);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("API 返回 " + code);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    /** 从消息 JSON 字符串解析消息列表（缓存读取时复用） */
    public static List<Message> parseMessages(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray arr = root.optJSONArray("messages");
        List<Message> list = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                Message m = new Message(arr.getJSONObject(i));
                if (m.id != null && !m.id.isEmpty()) list.add(m);
            }
        }
        return list;
    }

    /** 创建会话（workDir 可空，留空用服务端默认目录）；返回 sessionId，失败抛异常 */
    public static String createSession(String baseUrl, String token, String workDir) throws Exception {
        String urlStr = baseUrl + "/api/sessions";
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Referer", baseUrl + "/?token=" + token);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            String body = (workDir == null || workDir.trim().isEmpty())
                    ? "{}"
                    : "{\"workDir\":\"" + escapeJson(workDir.trim()) + "\"}";
            conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            int code = conn.getResponseCode();
            if (code != 200 && code != 201) throw new Exception("API 返回 " + code);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            JSONObject root = new JSONObject(sb.toString());
            String sid = root.optString("sessionId", "");
            if (sid.isEmpty()) throw new Exception("响应缺少 sessionId");
            return sid;
        } finally {
            conn.disconnect();
        }
    }

    /** 发送消息（202 异步接受） */
    public static boolean sendMessage(String baseUrl, String token, String sessionId, String content) {
        try {
            String urlStr = baseUrl + "/api/sessions/" + sessionId + "/chat";
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            try {
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Referer", baseUrl + "/?token=" + token);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                String body = "{\"content\":\"" + escapeJson(content) + "\",\"type\":\"user\"}";
                conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
                int code = conn.getResponseCode();
                return code == 200 || code == 202;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "send failed", e);
            return false;
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
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
