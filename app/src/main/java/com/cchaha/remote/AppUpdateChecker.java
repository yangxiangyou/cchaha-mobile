package com.cchaha.remote;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

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
 * 网络失败（如 api.github.com 不可达）时静默，不影响使用。
 */
public final class AppUpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final String API_URL =
            "https://api.github.com/repos/yangxiangyou/cchaha-mobile/releases/latest";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private AppUpdateChecker() { }

    /** 后台检查，有新版本弹提示 */
    public static void check(final Context context) {
        executor.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection();
                try {
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    conn.setRequestProperty("Accept", "application/vnd.github+json");
                    conn.setRequestProperty("User-Agent", "cchaha-mobile");
                    int code = conn.getResponseCode();
                    if (code != 200) return; // 静默失败
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                    }
                    JSONObject root = new JSONObject(sb.toString());
                    String latest = root.optString("tag_name", "");
                    String body = root.optString("body", "");
                    String url = root.optString("html_url", "");

                    String current = context.getPackageManager()
                            .getPackageInfo(context.getPackageName(), 0).versionName;
                    if (latest.startsWith("v")) latest = latest.substring(1);
                    if (latest.isEmpty() || latest.equals(current)) return;

                    final String fLatest = latest, fBody = body, fUrl = url;
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                            showDialog(context, fLatest, fBody, fUrl));
                } finally {
                    conn.disconnect();
                }
            } catch (Exception e) {
                Log.w(TAG, "update check failed", e);
            }
        });
    }

    private static void showDialog(Context context, String version, String body, String url) {
        String notes = body != null && body.length() > 300 ? body.substring(0, 300) + "…" : body;
        new AlertDialog.Builder(context)
                .setTitle("发现新版本 v" + version)
                .setMessage(notes == null || notes.isEmpty() ? "有新版本可用" : notes)
                .setPositiveButton("去下载", (d, w) -> {
                    if (url != null && !url.isEmpty()) {
                        try {
                            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                        } catch (Exception ignored) { }
                    }
                })
                .setNegativeButton("稍后", null)
                .show();
    }
}
