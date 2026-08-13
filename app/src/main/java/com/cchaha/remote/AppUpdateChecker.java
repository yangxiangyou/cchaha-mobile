package com.cchaha.remote;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 自动更新检查：查询 GitHub Releases 最新版，有新版本时弹窗提示。
 * 国内手机网络不可达 api.github.com，端点改为镜像优先（gh-proxy.com 代理 API）+ 直连兜底，
 * 全部失败时静默，不影响使用。下载链接同样经镜像加速。
 */
public final class AppUpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final String RELEASES_LATEST_PATH =
            "repos/yangxiangyou/cchaha-mobile/releases/latest";
    /** 按序尝试：镜像代理优先，直连兜底（公益镜像无 SLA，必须保留直连） */
    private static final String[] API_ENDPOINTS = {
            "https://gh-proxy.com/https://api.github.com/" + RELEASES_LATEST_PATH,
            "https://api.github.com/" + RELEASES_LATEST_PATH,
    };
    /** APK 下载走镜像前缀（浏览器打开即经镜像加速下载） */
    private static final String DOWNLOAD_MIRROR_PREFIX = "https://gh-proxy.com/";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    /** 单进程内只检查一次（多列表页实例会重复触发弹窗） */
    private static volatile boolean checked = false;

    private AppUpdateChecker() { }

    /** 语义化版本比较：a > b 返回正数，相等 0，小于负数（非数字段按 0） */
    static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int y = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        // 去掉非数字后缀（如 "1.4.10-beta" → 10）
        String num = s.replaceAll("[^0-9].*$", "");
        if (num.isEmpty()) return 0;
        try {
            return Integer.parseInt(num);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 后台检查，有新版本弹提示（进程内仅一次） */
    public static void check(final Context context) {
        if (checked) return;
        checked = true;
        executor.execute(() -> {
            try {
                String latest = "", body = "", url = "";
                boolean ok = false;
                for (String endpoint : API_ENDPOINTS) {
                    try {
                        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
                        try {
                            conn.setConnectTimeout(10000);
                            conn.setReadTimeout(10000);
                            conn.setRequestProperty("Accept", "application/vnd.github+json");
                            conn.setRequestProperty("User-Agent", "cchaha-mobile");
                            if (conn.getResponseCode() != 200) continue; // 试下一个端点
                            StringBuilder sb = new StringBuilder();
                            try (BufferedReader br = new BufferedReader(
                                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = br.readLine()) != null) sb.append(line);
                            }
                            JSONObject root = new JSONObject(sb.toString());
                            latest = root.optString("tag_name", "");
                            body = root.optString("body", "");
                            // 优先取 APK 资产直链（镜像化），无资产退回 Release 页面
                            JSONArray assets = root.optJSONArray("assets");
                            if (assets != null && assets.length() > 0) {
                                String assetUrl = assets.optJSONObject(0)
                                        .optString("browser_download_url", "");
                                if (!assetUrl.isEmpty()) url = DOWNLOAD_MIRROR_PREFIX + assetUrl;
                            }
                            if (url.isEmpty()) url = root.optString("html_url", "");
                            if (!latest.isEmpty()) { ok = true; break; }
                        } finally {
                            conn.disconnect();
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "endpoint failed: " + endpoint, e);
                    }
                }
                if (!ok) {
                    checked = false; // 全部失败：复位，下次进入可重试（断网不永久禁用）
                    return;
                }

                String current = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0).versionName;
                if (latest.startsWith("v")) latest = latest.substring(1);
                if (latest.isEmpty() || compareVersions(latest, current) <= 0) return;

                final String fLatest = latest, fBody = body, fUrl = url;
                final Context appCtx = context.getApplicationContext();
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if (context instanceof android.app.Activity
                            && ((android.app.Activity) context).isFinishing()) return;
                    showDialog(appCtx, fLatest, fBody, fUrl);
                });
            } catch (Exception e) {
                Log.w(TAG, "update check failed", e);
                checked = false; // 异常也复位：本次检查失败不永久禁用
            }
        });
    }

    private static void showDialog(Context context, String version, String body, String url) {
        String notes = body != null && body.length() > 300 ? body.substring(0, 300) + "…" : body;
        new AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.update_available_title, version))
                .setMessage(notes == null || notes.isEmpty()
                        ? context.getString(R.string.update_available_msg) : notes)
                .setPositiveButton(R.string.update_go, (d, w) -> {
                    if (url != null && !url.isEmpty()) {
                        try {
                            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                        } catch (Exception ignored) { }
                    }
                })
                .setNegativeButton(R.string.update_later, null)
                .show();
    }
}
