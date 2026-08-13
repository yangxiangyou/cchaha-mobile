package com.cchaha.remote;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * cc-haha H5 API 客户端（原生会话列表用）。
 * 认证机制：请求头 Referer 携带页面 URL（含 token），服务端从 Referer 提取 token 校验。
 */
public final class SessionApi {

    private static final String TAG = "SessionApi";
    private static final int TIMEOUT_MS = 20000;

    /** 全信任 TLS 单例：局域网/隧道场景证书可能与访问地址主机名不匹配（域名证书经 IP 访问） */
    private static volatile SSLSocketFactory trustAllFactory;

    /**
     * 对 HTTPS 连接应用 App 级信任：跳过证书 CA 校验与主机名校验。
     * 该 App 只连用户自配的 H5 地址（私网/隧道自签证书场景），与 WebView 的放行策略一致。
     */
    private static void applyTrust(HttpURLConnection conn) {
        if (!(conn instanceof HttpsURLConnection)) return;
        try {
            HttpsURLConnection hc = (HttpsURLConnection) conn;
            if (trustAllFactory == null) {
                synchronized (SessionApi.class) {
                    if (trustAllFactory == null) {
                        TrustManager[] trustAll = { new X509TrustManager() {
                            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        } };
                        SSLContext ctx = SSLContext.getInstance("TLS");
                        ctx.init(null, trustAll, new SecureRandom());
                        trustAllFactory = ctx.getSocketFactory();
                    }
                }
            }
            hc.setSSLSocketFactory(trustAllFactory);
            hc.setHostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {
            Log.w(TAG, "applyTrust failed", e);
        }
    }

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
            // 标题留空，由 UI 层按语言显示兜底文案（SessionApi 无 Context，不写死中文）
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
        String referer = baseUrl + "/?token=" + encodeToken(token);
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            applyTrust(conn);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Referer", referer);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new Exception("API 返回 " + code + "：" + readErrorBody(conn));
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
            JSONArray content = o.optJSONArray("content");
            if (content != null) {
                for (int i = 0; i < content.length(); i++) {
                    JSONObject c = content.optJSONObject(i);
                    if (c == null) continue;
                    String ct = c.optString("type");
                    String txt = blockText(c);
                    if (txt != null && !txt.isEmpty()) {
                        String toolName = "tool_use".equals(ct) ? c.optString("name") : "";
                        blocks.add(new Block(ct, txt, toolName));
                    } else if ("tool_use".equals(ct)) {
                        // 无文本的 tool_use 也保留块（渲染工具名卡片）
                        blocks.add(new Block(ct, "", c.optString("name", "")));
                    } else if ("image".equals(ct) || "image_url".equals(ct)) {
                        blocks.add(new Block(ct, "", ""));
                    } else if ("file".equals(ct) || "attachment".equals(ct)) {
                        blocks.add(new Block(ct, "", ""));
                    }
                }
            }
            // 兼容旧缓存：仅当无内容块（旧格式消息）时才拼显示文本，省内存；
            // 文本留空由 UI 层按语言兜底
            if (blocks.isEmpty()) {
                text = o.optString("text", "");
            }
        }

        /** 提取块文本：text/thinking 直接读；tool_result 的 content 是嵌套数组，递归拼接 */
        private static String blockText(JSONObject c) {
            String ct = c.optString("type");
            String txt = c.optString("text");
            if (txt == null || txt.isEmpty()) txt = c.optString("thinking");
            if (txt != null && !txt.isEmpty()) return txt;
            if ("tool_result".equals(ct) || "redacted_thinking".equals(ct)) {
                JSONArray nested = c.optJSONArray("content");
                if (nested != null) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < nested.length(); i++) {
                        JSONObject nc = nested.optJSONObject(i);
                        if (nc == null) continue;
                        String nt = nc.optString("text");
                        if (nt != null && !nt.isEmpty()) {
                            sb.append(nt);
                            if (i < nested.length() - 1) sb.append('\n');
                        }
                    }
                    if (sb.length() > 0) return sb.toString();
                }
            }
            return "";
        }
    }

    /** 拉取消息并返回原始 JSON 字符串（供本地缓存直接存储，避免二次序列化） */
    public static String fetchMessagesJson(String baseUrl, String token, String sessionId) throws Exception {
        String urlStr = baseUrl + "/api/sessions/" + sessionId + "/messages";
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            applyTrust(conn);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(120000); // 大会话需要更久
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Referer", baseUrl + "/?token=" + encodeToken(token));
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("API 返回 " + code + "：" + readErrorBody(conn));
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

    /** 发送消息（202 异步接受）；失败抛异常（含服务端错误详情，便于用户看到真实原因） */
    public static void sendMessage(String baseUrl, String token, String sessionId, String content)
            throws Exception {
        String urlStr = baseUrl + "/api/sessions/" + sessionId + "/chat";
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            applyTrust(conn);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Referer", baseUrl + "/?token=" + encodeToken(token));
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            String body = "{\"content\":\"" + escapeJson(content) + "\",\"type\":\"user\"}";
            conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            int code = conn.getResponseCode();
            if (code != 200 && code != 202) {
                throw new Exception("API 返回 " + code + "：" + readErrorBody(conn));
            }
        } finally {
            conn.disconnect();
        }
    }

    /** 读取错误响应体（含服务端 message），便于用户/日志定位失败原因 */
    private static String readErrorBody(HttpURLConnection conn) {
        try {
            java.io.InputStream es = conn.getErrorStream();
            if (es == null) return "";
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(es, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            String raw = sb.toString();
            try {
                JSONObject o = new JSONObject(raw);
                String msg = o.optString("message", "");
                return msg.isEmpty() ? raw : msg;
            } catch (Exception ignored) { }
            return raw.length() > 200 ? raw.substring(0, 200) : raw;
        } catch (Exception e) {
            return "";
        }
    }

    /** token 编码为 URL query 形式（Referer/prewarm 拼接用；Authorization 用原始 token） */
    private static String encodeToken(String token) {
        if (token == null) return "";
        try {
            return java.net.URLEncoder.encode(token, "UTF-8");
        } catch (Exception e) {
            return token;
        }
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(java.util.Locale.US, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

}
