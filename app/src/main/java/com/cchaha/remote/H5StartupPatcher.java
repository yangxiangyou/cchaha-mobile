package com.cchaha.remote;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 给 cc-haha H5 主文档 HTML 打补丁：屏蔽 8 秒启动看门狗错误页。
 *
 * 背景：H5 的 index.html 内置看门狗——页面加载后 8 秒内 React 未挂载就渲染
 * "Desktop startup failed" 错误页。手机 WebView 加载完整桌面 bundle 慢（公网
 * 隧道 + 手机 JS 执行慢），8 秒超时被触发，错误页闪现几秒后 React 挂载覆盖。
 * 该看门狗直接调用闭包内函数，JS 注入无法拦截，只能重写主文档 HTML。
 *
 * 补丁方式：把 renderStartupError 函数体首行替换为直接 return（错误页永不
 * 渲染），加载流程本身不受影响。失败时返回 null，WebView 走原始逻辑。
 */
public final class H5StartupPatcher {

    private static final String TAG = "H5Patcher";
    /**
     * 正则匹配看门狗渲染函数声明（宽松：兼容上游换行/缩进/参数名格式变化——
     * 曾因上游把 "{ 换行" 改成别的格式而静默失效，导致错误页回归）。
     */
    private static final String PATCH_REGEX =
            "function renderStartupError\\s*\\([^)]*\\)\\s*\\{";
    /** 补丁成功标记（同时用于幂等判定与缓存有效性判定） */
    private static final String PATCH_MARKER = "cchaha-mobile: suppress";
    private static final String PATCH_REPLACEMENT =
            "function renderStartupError(reason) { return; // " + PATCH_MARKER + "\n";
    private static final int TIMEOUT_MS = 15000;
    /** 有界 LRU 缓存（按 origin+path 键，去 token；上限 8，防内存无限增长） */
    private static final Map<String, String> cache = new LinkedHashMap<String, String>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 8;
        }
    };

    private H5StartupPatcher() { }

    /** 缓存键：origin + path（去 token 与参数，HTML 与 token 无关） */
    private static String cacheKey(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String scheme = uri.getScheme() != null ? uri.getScheme() : "";
            String host = uri.getHost() != null ? uri.getHost() : "";
            int port = uri.getPort();
            String path = uri.getPath() != null ? uri.getPath() : "/";
            return scheme + "://" + host + (port > 0 ? ":" + port : "") + path;
        } catch (Exception e) {
            return url;
        }
    }

    /** 替换 HTML 中的看门狗渲染函数（幂等；格式变化仍可匹配） */
    static String patch(String html) {
        if (html == null || html.contains(PATCH_MARKER)) return html;
        return html.replaceFirst(PATCH_REGEX, PATCH_REPLACEMENT);
    }

    /** 拉取主文档 HTML 并打补丁（应在后台线程调用）；失败返回 null */
    public static String fetchPatched(String url) {
        String key = cacheKey(url);
        synchronized (cache) {
            String cached = cache.get(key);
            if (cached != null) return cached;
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            try {
                if (conn instanceof HttpsURLConnection) {
                    HttpsURLConnection hc = (HttpsURLConnection) conn;
                    hc.setSSLSocketFactory(trustAllFactory());
                    // 与 App 对 H5 的整体信任一致：跳过主机名校验
                    // （证书签给域名、经 IP/隧道访问时主机名不匹配）
                    hc.setHostnameVerifier((hostname, session) -> true);
                }
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                conn.setRequestProperty("User-Agent", "cchaha-mobile");
                conn.setRequestProperty("Accept", "text/html");
                if (conn.getResponseCode() != 200) return null;
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append('\n');
                }
                String html = patch(sb.toString());
                // 补丁确认生效（含标记）才缓存：若上游结构变化导致 patch 失败，
                // 不缓存未补丁的 HTML（否则 WebView 每次都拿到会触发错误页的版本）
                if (!html.contains(PATCH_MARKER)) {
                    Log.w(TAG, "patch not applied (upstream HTML changed?)");
                    return null;
                }
                synchronized (cache) {
                    cache.put(key, html);
                }
                return html;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            // 日志脱敏：去掉 query（含 token）
            int q = url.indexOf('?');
            Log.w(TAG, "fetch failed: " + (q > 0 ? url.substring(0, q) : url), e);
            return null;
        }
    }

    /** 全信任 TLS 单例（与 WebView 对局域网/隧道自签证书的处理一致） */
    private static volatile SSLSocketFactory trustAllFactory;

    private static SSLSocketFactory trustAllFactory() throws Exception {
        if (trustAllFactory == null) {
            synchronized (H5StartupPatcher.class) {
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
        return trustAllFactory;
    }
}
