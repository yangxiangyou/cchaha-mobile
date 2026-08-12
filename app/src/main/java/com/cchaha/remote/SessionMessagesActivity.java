package com.cchaha.remote;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 原生消息流（Codex 式）：
 * 解决 WebView 加载大会话超时（120s）的问题——直接调消息 API 全量拉取（约 6 秒），
 * 支持发送消息（POST /chat 异步）和手动/自动刷新。
 */
public class SessionMessagesActivity extends Activity {

    static final String EXTRA_SESSION_ID = "session_id";
    static final String EXTRA_SESSION_TITLE = "session_title";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Storage storage;
    private MessageCache messageCache;
    private String baseUrl = "";
    private String token = "";
    private String sessionId = "";
    private String sessionTitle = "";

    private ListView messageList;
    private TextView statusText;
    private EditText inputBox;
    private MessageAdapter adapter;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipe;
    private boolean loading = false;
    private boolean visible = true;   // Activity 是否在前台
    private int lastMessageCount = 0; // 上次消息数（用于新回复检测）

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashCatcher.install(this);
        Window w = getWindow();
        w.setStatusBarColor(0xFF111418);
        w.setNavigationBarColor(0xFF111418);
        setContentView(R.layout.activity_session_messages);

        sessionId = getIntent().getStringExtra(EXTRA_SESSION_ID);
        sessionTitle = getIntent().getStringExtra(EXTRA_SESSION_TITLE);
        if (sessionId == null) sessionId = "";

        messageList = findViewById(R.id.msg_list);
        statusText = findViewById(R.id.msg_status);
        inputBox = findViewById(R.id.msg_input);
        Button send = findViewById(R.id.msg_send);
        Button refresh = findViewById(R.id.msg_refresh);
        Button full = findViewById(R.id.msg_full);
        TextView title = findViewById(R.id.msg_title);
        swipe = findViewById(R.id.msg_swipe);
        swipe.setColorSchemeColors(0xFF4DA3FF);
        swipe.setOnRefreshListener(this::loadMessages); // 收尾在加载完成/失败回调里

        messageCache = new MessageCache(this);

        // Android 13+ 发通知需要运行时权限（新回复通知；拒绝则静默降级）
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        title.setText(sessionTitle != null && !sessionTitle.isEmpty() ? sessionTitle : "会话");

        Storage.SavedHost host = new Storage(this).getCurrentHost();
        if (host != null) {
            String url = host.url;
            baseUrl = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
            int i = url.indexOf("token=");
            if (i >= 0) {
                String rest = url.substring(i + 6);
                int j = rest.indexOf('&');
                token = j > 0 ? rest.substring(0, j) : rest;
            }
        }

        adapter = new MessageAdapter();
        messageList.setAdapter(adapter);

        // 秒开：先渲染本地缓存（后台线程读文件 + 解析），再全量刷新
        final String sid = sessionId;
        executor.execute(() -> {
            String cachedJson = messageCache.load(sid);
            if (cachedJson != null) {
                try {
                    List<SessionApi.Message> cached = SessionApi.parseMessages(cachedJson);
                    mainHandler.post(() -> {
                        if (isFinishing()) return;
                        adapter.refresh(cached);
                        lastMessageCount = cached.size();
                        statusText.setText(R.string.msg_cache_loading);
                        if (cached.size() > 0) messageList.setSelection(cached.size() - 1); // 滚到底部
                    });
                    return; // 有缓存：无加载中文案，后台刷新继续
                } catch (Exception ignored) { }
            }
            mainHandler.post(() -> {
                if (!isFinishing() && adapter.getCount() == 0) {
                    statusText.setText(R.string.msg_loading);
                }
            });
        });

        loadMessages();

        refresh.setOnClickListener(v -> loadMessages());

        // 兜底入口：切 WebView 完整版（权限批准/附件等原生未覆盖功能）
        full.setOnClickListener(v -> {
            Intent i = new Intent(this, MainActivity.class);
            if (!sessionId.isEmpty()) i.putExtra(MainActivity.EXTRA_OPEN_SESSION, sessionId);
            if (sessionTitle != null && !sessionTitle.isEmpty()) {
                i.putExtra(MainActivity.EXTRA_SESSION_TITLE, sessionTitle);
            }
            startActivity(i);
        });

        send.setOnClickListener(v -> {
            String content = inputBox.getText().toString().trim();
            if (content.isEmpty()) return;
            inputBox.setText("");
            statusText.setText(R.string.msg_sending);
            final String msg = content;
            executor.execute(() -> {
                boolean ok = SessionApi.sendMessage(baseUrl, token, sessionId, msg);
                mainHandler.post(() -> {
                    if (ok) {
                        statusText.setText(R.string.msg_sent);
                        // 5 秒后自动刷新拿回复
                        mainHandler.postDelayed(this::loadMessages, 5000);
                    } else {
                        statusText.setText(R.string.msg_send_failed);
                        Toast.makeText(this, R.string.msg_send_failed, Toast.LENGTH_SHORT).show();
                        inputBox.setText(msg);
                    }
                });
            });
        });
    }

    private void loadMessages() {
        if (loading || baseUrl.isEmpty() || token.isEmpty() || sessionId.isEmpty()) return;
        loading = true;
        // 无缓存（列表为空）时才显示"加载中"；有缓存的后台刷新保持缓存提示
        if (adapter.getCount() == 0) {
            statusText.setText(R.string.msg_loading);
        }
        final String url = baseUrl, tk = token, sid = sessionId;
        executor.execute(() -> {
            try {
                String json = SessionApi.fetchMessagesJson(url, tk, sid);
                messageCache.save(sid, json);
                List<SessionApi.Message> messages = SessionApi.parseMessages(json);
                mainHandler.post(() -> {
                    // 新回复检测：消息变多且界面不在前台 → 通知
                    if (lastMessageCount > 0 && messages.size() > lastMessageCount && !visible) {
                        notifyNewReply(messages.size() - lastMessageCount);
                    }
                    lastMessageCount = messages.size();
                    adapter.refresh(messages);
                    statusText.setText(getString(R.string.msg_updated, messages.size()));
                    if (messages.size() > 0) messageList.setSelection(messages.size() - 1); // 滚到底部
                    swipe.setRefreshing(false); // 完成即收尾
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (adapter.getCount() == 0) {
                        statusText.setText(R.string.msg_failed);
                        Toast.makeText(this, R.string.msg_failed, Toast.LENGTH_SHORT).show();
                    } else {
                        // 有缓存：保留缓存内容，只提示刷新失败
                        statusText.setText(R.string.msg_refresh_failed_keep);
                    }
                    swipe.setRefreshing(false);
                });
            } finally {
                loading = false;
            }
        });
    }

    /** 新回复通知（界面不在前台时） */
    private void notifyNewReply(int count) {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            android.app.Notification.Builder b = new android.app.Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.ic_dialog_email)
                    .setContentTitle(sessionTitle != null ? sessionTitle : "会话")
                    .setContentText("收到 " + count + " 条新消息")
                    .setAutoCancel(true);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(
                        "replies", "会话回复", android.app.NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
                b.setChannelId("replies");
            }
            nm.notify((int) (System.currentTimeMillis() % 100000), b.build());
        } catch (Exception ignored) { }
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        visible = false;
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    /** 消息适配器：user 右对齐，其他左对齐 */
    private class MessageAdapter extends BaseAdapter {
        private List<SessionApi.Message> items = new ArrayList<>();

        void refresh(List<SessionApi.Message> newItems) {
            items = new ArrayList<>(newItems);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public SessionApi.Message getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @SuppressLint("InflateParams")
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message, parent, false);
            }
            SessionApi.Message m = getItem(position);
            TextView bubble = v.findViewById(R.id.msg_bubble);
            TextView meta = v.findViewById(R.id.msg_meta);

            boolean isUser = "user".equals(m.type);
            bubble.setBackgroundResource(isUser ? R.drawable.bubble_user : R.drawable.bubble_assistant);
            bubble.setText(buildStyledText(m.text));

            String time = m.timestampMs > 0
                    ? new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(m.timestampMs)) : "";
            meta.setText((isUser ? "我" : "Claude") + " · " + time);
            return v;
        }

        /** 代码块（```...```）用等宽字体 + 深色背景 */
        private CharSequence buildStyledText(String text) {
            if (text == null || !text.contains("```")) return text;
            android.text.SpannableString sp = new android.text.SpannableString(text);
            int start = 0;
            while (true) {
                int i = text.indexOf("```", start);
                if (i < 0) break;
                int j = text.indexOf("```", i + 3);
                if (j < 0) break;
                int codeStart = i;
                int codeEnd = j + 3;
                sp.setSpan(new android.text.style.TypefaceSpan("monospace"),
                        codeStart, codeEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sp.setSpan(new android.text.style.BackgroundColorSpan(0xFF0D1117),
                        codeStart, codeEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                start = j + 3;
            }
            return sp;
        }
    }
}
