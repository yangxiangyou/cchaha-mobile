package com.cchaha.remote;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.ConcurrentHashMap;
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
    // 带换行锚定：替换后不再匹配原串，保证幂等
    private static final String PATCH_TARGET = "function renderStartupError(reason) {\n";
    private static final String PATCH_REPLACEMENT =
            "function renderStartupError(reason) { return; // cchaha-mobile: suppress\n";
    private static final int TIMEOUT_MS = 15000;
    private static final Map<String, String> cache = new ConcurrentHashMap<>();

    private H5StartupPatcher() { }

    /** 替换 HTML 中的看门狗渲染函数（幂等） */
    static String patch(String html) {
        if (html == null || !html.contains(PATCH_TARGET)) return html;
        return html.replace(PATCH_TARGET, PATCH_REPLACEMENT);
    }

    /** 拉取主文档 HTML 并打补丁（应在后台线程调用）；失败返回 null */
    public static String fetchPatched(String url) {
        String cached = cache.get(url);
        if (cached != null) return cached;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            try {
                if (conn instanceof HttpsURLConnection) {
                    ((HttpsURLConnection) conn).setSSLSocketFactory(trustAllFactory());
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
                if (!html.contains("renderStartupError")) return null; // 非 H5 主文档，不缓存
                cache.put(url, html);
                return html;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "fetch failed: " + url, e);
            return null;
        }
    }

    /** 全信任 TLS（与 WebView 对局域网/隧道自签证书的处理一致） */
    private static SSLSocketFactory trustAllFactory() throws Exception {
        TrustManager[] trustAll = { new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        } };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAll, new SecureRandom());
        return ctx.getSocketFactory();
    }
}
