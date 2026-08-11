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
    private String baseUrl = "";
    private String token = "";
    private String sessionId = "";
    private String sessionTitle = "";

    private ListView messageList;
    private TextView statusText;
    private EditText inputBox;
    private MessageAdapter adapter;
    private boolean loading = false;

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
        TextView title = findViewById(R.id.msg_title);

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
        statusText.setText(R.string.msg_loading);

        loadMessages();

        refresh.setOnClickListener(v -> loadMessages());

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
        statusText.setText(R.string.msg_loading);
        final String url = baseUrl, tk = token, sid = sessionId;
        executor.execute(() -> {
            try {
                List<SessionApi.Message> messages = SessionApi.fetchMessages(url, tk, sid);
                mainHandler.post(() -> {
                    adapter.refresh(messages);
                    statusText.setText(getString(R.string.msg_updated, messages.size()));
                    messageList.setSelection(messages.size() - 1); // 滚到底部
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    statusText.setText(R.string.msg_failed);
                    Toast.makeText(this, R.string.msg_failed, Toast.LENGTH_SHORT).show();
                });
            } finally {
                loading = false;
            }
        });
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
            bubble.setText(m.text);

            String time = m.timestampMs > 0
                    ? new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(m.timestampMs)) : "";
            meta.setText((isUser ? "我" : "Claude") + " · " + time);
            return v;
        }
    }
}
