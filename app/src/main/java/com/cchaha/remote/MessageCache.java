package com.cchaha.remote;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;

/**
 * 会话消息本地缓存（文件方式）：
 * 大会话消息 JSON 可达数 MB，SharedPreferences 全量重写不适合，
 * 按会话存到 filesDir/messages/&lt;sessionId&gt;.json，进入会话先渲染缓存再后台刷新。
 * 读写都应在后台线程调用（调用方保证）。
 */
public final class MessageCache {

    private static final String TAG = "MessageCache";
    private static final int MAX_FILES = 10;
    private static final String DIR = "messages";

    private final File dir;

    public MessageCache(Context context) {
        dir = new File(context.getFilesDir(), DIR);
    }

    /** 读取某会话的缓存消息 JSON；无缓存返回 null */
    public String load(String sessionId) {
        try {
            File f = fileFor(sessionId);
            if (f.exists()) {
                return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            Log.w(TAG, "load failed for " + sessionId, e);
        }
        return null;
    }

    /** 写入某会话的消息 JSON，并顺带清理超出上限的最旧文件 */
    public void save(String sessionId, String json) {
        try {
            if (!dir.exists()) dir.mkdirs();
            Files.write(fileFor(sessionId).toPath(), json.getBytes(StandardCharsets.UTF_8));
            prune();
        } catch (Exception e) {
            Log.w(TAG, "save failed for " + sessionId, e);
        }
    }

    private File fileFor(String sessionId) {
        // sessionId 可能含不安全字符，统一替换为下划线
        String safe = sessionId == null ? "unknown"
                : sessionId.replaceAll("[^a-zA-Z0-9._-]", "_");
        return new File(dir, safe + ".json");
    }

    /** 文件数超过上限时，按最后修改时间删除最旧的文件 */
    private void prune() {
        File[] files = dir.listFiles(f -> f.isFile() && f.getName().endsWith(".json"));
        if (files == null || files.length <= MAX_FILES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        int excess = files.length - MAX_FILES;
        for (int i = 0; i < excess; i++) {
            if (!files[i].delete()) {
                Log.w(TAG, "prune failed: " + files[i].getName());
            }
        }
    }
}
