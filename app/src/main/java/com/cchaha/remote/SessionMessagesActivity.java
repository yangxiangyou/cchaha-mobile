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
    /** 通知携带所属设备的地址/token：点通知回到发出通知的那台设备，避免串设备 */
    static final String EXTRA_HOST_URL = "host_url";
    static final String EXTRA_HOST_TOKEN = "host_token";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    // 发送独立线程：避免被大会话 120s 拉取阻塞
    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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
    private volatile boolean loading = false; // 跨线程可见，防重复拉取
    private boolean visible = true;   // Activity 是否在前台
    private int lastMessageCount = 0; // 上次消息数（用于新回复检测）

    // 轮询：前台 30 秒自动出新消息；后台（锁屏/切走）60 秒检查一次，有新回复时推送通知
    private static final long POLL_INTERVAL_MS = 30_000;
    private static final long POLL_INTERVAL_BG_MS = 60_000;
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (loading) {
                mainHandler.postDelayed(this, visible ? POLL_INTERVAL_MS : POLL_INTERVAL_BG_MS);
                return;
            }
            loadMessages();
            mainHandler.postDelayed(this, visible ? POLL_INTERVAL_MS : POLL_INTERVAL_BG_MS);
        }
    };

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

        title.setText(sessionTitle != null && !sessionTitle.isEmpty()
                ? sessionTitle : getString(R.string.msg_default_title));

        // 优先用通知携带的设备地址（点通知回到发出通知的那台电脑）；否则当前设备
        String hostUrl = getIntent().getStringExtra(EXTRA_HOST_URL);
        String hostToken = getIntent().getStringExtra(EXTRA_HOST_TOKEN);
        if (hostUrl != null && !hostUrl.isEmpty()) {
            baseUrl = UrlUtils.trimTrailingSlash(hostUrl);
            token = hostToken == null ? "" : hostToken;
        } else {
            Storage.SavedHost host = new Storage(this).getCurrentHost();
            if (host != null) {
                String url = host.url;
                baseUrl = UrlUtils.trimTrailingSlash(url.contains("?")
                        ? url.substring(0, url.indexOf('?')) : url);
                token = UrlUtils.extractToken(url);
            }
        }

        adapter = new MessageAdapter();
        messageList.setAdapter(adapter);
        // 空态提示（无消息时居中显示）
        View emptyView = findViewById(R.id.msg_empty);
        if (emptyView != null) messageList.setEmptyView(emptyView);

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

        // 兜底入口：切 WebView 完整版（权限批准/附件等原生未覆盖功能）；
        // 带设备信息：通知进入的跨设备会话切完整版时仍定位到原设备
        full.setOnClickListener(v -> {
            Intent i = new Intent(this, MainActivity.class);
            if (!sessionId.isEmpty()) i.putExtra(MainActivity.EXTRA_OPEN_SESSION, sessionId);
            if (sessionTitle != null && !sessionTitle.isEmpty()) {
                i.putExtra(MainActivity.EXTRA_SESSION_TITLE, sessionTitle);
            }
            i.putExtra(EXTRA_HOST_URL, baseUrl);
            i.putExtra(EXTRA_HOST_TOKEN, token);
            startActivity(i);
        });

        send.setOnClickListener(v -> {
            String content = inputBox.getText().toString().trim();
            if (content.isEmpty()) return;
            inputBox.setText("");
            statusText.setText(R.string.msg_sending);
            final String msg = content;
            try {
                sendExecutor.execute(() -> {
                    boolean ok = SessionApi.sendMessage(baseUrl, token, sessionId, msg);
                    mainHandler.post(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (ok) {
                            statusText.setText(R.string.msg_sent);
                            // 5 秒后自动刷新拿回复；若届时正在拉取则重排，保证不丢
                            mainHandler.postDelayed(() -> {
                                if (loading) {
                                    mainHandler.postDelayed(this::loadMessages, 5000);
                                    return;
                                }
                                loadMessages();
                            }, 5000);
                        } else {
                            statusText.setText(R.string.msg_send_failed);
                            Toast.makeText(this, R.string.msg_send_failed, Toast.LENGTH_SHORT).show();
                            inputBox.setText(msg);
                        }
                    });
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // 已销毁：静默
            }
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
        try {
            executor.execute(() -> {
                try {
                    String json = SessionApi.fetchMessagesJson(url, tk, sid);
                    List<SessionApi.Message> messages = SessionApi.parseMessages(json);
                    messageCache.save(sid, json); // 解析成功后才写缓存
                    mainHandler.post(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        // 会话已切换（onNewIntent）：旧会话的拉取结果不得覆盖新会话
                        if (!sid.equals(sessionId)) return;
                        // 新回复检测：消息变多且界面不在前台 → 通知
                        if (lastMessageCount > 0 && messages.size() > lastMessageCount && !visible) {
                            notifyNewReply(messages.size() - lastMessageCount);
                        }
                        // 无变化（条数相同且最后一条一致）：不重排列表，省滚动开销
                        boolean unchanged = lastMessageCount > 0
                                && messages.size() == lastMessageCount
                                && !messages.isEmpty()
                                && adapter.lastMessageId() != null
                                && adapter.lastMessageId().equals(messages.get(messages.size() - 1).id);
                        lastMessageCount = messages.size();
                        if (unchanged) {
                            swipe.setRefreshing(false);
                            loading = false;
                            return;
                        }
                        adapter.refresh(messages);
                        statusText.setText(getString(R.string.msg_updated, messages.size()));
                        // 仅在用户已接近底部时跟随滚动（浏览历史时不打扰）；未布局视为底部
                        if (messages.size() > 0) {
                            int last = messageList.getLastVisiblePosition();
                            if (last < 0 || last >= messages.size() - 4) {
                                messageList.setSelection(messages.size() - 1);
                            }
                        }
                        swipe.setRefreshing(false); // 完成即收尾
                        loading = false;
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (!sid.equals(sessionId)) return;
                        if (adapter.getCount() == 0) {
                            statusText.setText(R.string.msg_failed);
                            Toast.makeText(this, R.string.msg_failed, Toast.LENGTH_SHORT).show();
                        } else {
                            // 有缓存：保留缓存内容，只提示刷新失败
                            statusText.setText(R.string.msg_refresh_failed_keep);
                        }
                        swipe.setRefreshing(false);
                        loading = false;
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Activity 已销毁，线程池已关闭：静默
            loading = false;
        }
    }

    /** 新回复通知（界面不在前台时）：固定 key 按会话复用更新，点击回到会话 */
    private void notifyNewReply(int count) {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            Intent intent = new Intent(this, SessionMessagesActivity.class);
            intent.putExtra(EXTRA_SESSION_ID, sessionId);
            intent.putExtra(EXTRA_SESSION_TITLE, sessionTitle);
            intent.putExtra(EXTRA_HOST_URL, baseUrl);
            intent.putExtra(EXTRA_HOST_TOKEN, token);
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            // requestCode 按会话区分：不同会话的通知点击互不串扰
            int reqCode = sessionId == null ? 0 : sessionId.hashCode();
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                    this, reqCode, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE);
            android.app.Notification.Builder b = new android.app.Notification.Builder(this)
                    .setSmallIcon(R.drawable.ic_stat_reply)
                    .setContentTitle(sessionTitle != null ? sessionTitle : getString(R.string.msg_default_title))
                    .setContentText(getString(R.string.msg_reply_notification, count))
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(
                        "replies", getString(R.string.msg_channel_name),
                        android.app.NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
                b.setChannelId("replies");
            }
            // 固定通知 key（按会话）：多次发现新回复复用同一条，更新计数不刷屏
            nm.notify("reply_" + sessionId, 0, b.build());
        } catch (Exception ignored) { }
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        // 前台恢复轮询（立即来一次，再按间隔）
        mainHandler.removeCallbacks(pollRunnable);
        mainHandler.post(pollRunnable);
    }

    @Override
    protected void onStop() {
        super.onStop();
        visible = false;
        // 后台不停止轮询（放慢到 60 秒），锁屏/切走时靠它发现新回复并推送通知
    }

    @Override
    protected void onDestroy() {
        // 清理全部挂起回调（轮询/发送后延迟刷新/加载回调），防止销毁后触碰 UI
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        sendExecutor.shutdownNow();
        super.onDestroy();
    }

    /** 通知点击/单实例复用：重读会话参数并重新加载（支持从通知切换到其他会话/设备） */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String newId = intent.getStringExtra(EXTRA_SESSION_ID);
        String newTitle = intent.getStringExtra(EXTRA_SESSION_TITLE);
        // 通知来自其他设备：先切换 baseUrl/token，再拉新会话（sid 校验防旧结果覆盖）
        String hostUrl = intent.getStringExtra(EXTRA_HOST_URL);
        String hostToken = intent.getStringExtra(EXTRA_HOST_TOKEN);
        boolean hostChanged = hostUrl != null && !hostUrl.isEmpty()
                && !hostUrl.equals(UrlUtils.trimTrailingSlash(baseUrl));
        if (hostChanged) {
            baseUrl = UrlUtils.trimTrailingSlash(hostUrl);
            token = hostToken == null ? "" : hostToken;
        }
        if (newId == null || newId.isEmpty() || (newId.equals(sessionId) && !hostChanged)) return;
        sessionId = newId;
        sessionTitle = newTitle != null ? newTitle : "";
        TextView title = findViewById(R.id.msg_title);
        if (title != null) title.setText(sessionTitle.isEmpty()
                ? getString(R.string.msg_default_title) : sessionTitle);
        lastMessageCount = 0;
        adapter.expandedIds.clear(); // 折叠状态不跨会话残留
        loading = false;             // 释放旧会话的拉取锁，立即拉新会话
        adapter.refresh(new ArrayList<>());
        statusText.setText(R.string.msg_loading);
        loadMessages();
    }

    /** 消息适配器：user 右对齐，其他左对齐；块级渲染（思考折叠/工具卡片/结果折叠） */
    private class MessageAdapter extends BaseAdapter {
        private List<SessionApi.Message> items = new ArrayList<>();
        private final java.util.Set<String> expandedIds = new java.util.HashSet<>();
        // 渲染结果按（消息 id + 展开状态）缓存：滚动不重复构建 Spannable
        private final java.util.Map<String, CharSequence> renderCache = new java.util.HashMap<>();
        private String lastMsgId;

        void refresh(List<SessionApi.Message> newItems) {
            items = new ArrayList<>(newItems);
            renderCache.clear();
            lastMsgId = items.isEmpty() ? null : items.get(items.size() - 1).id;
            notifyDataSetChanged();
        }

        String lastMessageId() {
            return lastMsgId;
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

        private static class ViewHolder {
            TextView bubble;
            TextView meta;
        }

        @SuppressLint("InflateParams")
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder h;
            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_message, parent, false);
                h = new ViewHolder();
                h.bubble = convertView.findViewById(R.id.msg_bubble);
                h.meta = convertView.findViewById(R.id.msg_meta);
                convertView.setTag(h);
            } else {
                h = (ViewHolder) convertView.getTag();
            }
            SessionApi.Message m = getItem(position);
            boolean isUser = "user".equals(m.type);
            h.bubble.setBackgroundResource(isUser ? R.drawable.bubble_user : R.drawable.bubble_assistant);
            String key = m.id + "|" + (expandedIds.contains(m.id) ? "1" : "0");
            CharSequence rendered = renderCache.get(key);
            if (rendered == null) {
                rendered = buildStyledText(m);
                renderCache.put(key, rendered);
            }
            h.bubble.setText(rendered);
            // 点击折叠/展开（思考、工具结果等长内容）
            final SessionApi.Message fm = m;
            final ViewHolder fh = h;
            h.bubble.setOnClickListener(v2 -> {
                boolean hasFoldable = false;
                for (SessionApi.Block b : fm.blocks) {
                    if ("thinking".equals(b.type) || "tool_result".equals(b.type)) {
                        hasFoldable = true;
                        break;
                    }
                }
                if (!hasFoldable) return;
                if (!expandedIds.add(fm.id)) expandedIds.remove(fm.id);
                renderCache.clear(); // 展开状态变化：缓存失效
                fh.bubble.setText(buildStyledText(fm));
            });

            String time = m.timestampMs > 0 ? fmtTime(m.timestampMs) : "";
            // 类型标签：思考/工具（辅助阅读长消息）
            String tag = "";
            for (SessionApi.Block b : m.blocks) {
                if ("thinking".equals(b.type)) { tag = getString(R.string.tag_thinking); break; }
                if ("tool_use".equals(b.type)) { tag = getString(R.string.tag_tool); break; }
            }
            String who = isUser ? getString(R.string.msg_who_me) : getString(R.string.msg_who_ai);
            h.meta.setText(who + (tag.isEmpty() ? "" : " · " + tag) + " · " + time);
            return convertView;
        }

        /** 时间显示：当天 HH:mm，跨天带日期 MM-dd HH:mm */
        private String fmtTime(long ms) {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTimeInMillis(ms);
            java.util.Calendar now = java.util.Calendar.getInstance();
            boolean sameDay = c.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)
                    && c.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR);
            SimpleDateFormat fmt = new SimpleDateFormat(
                    sameDay ? "HH:mm" : "MM-dd HH:mm", Locale.getDefault());
            return fmt.format(new Date(ms));
        }

        /** 块级渲染：思考灰字折叠、工具等宽卡片、结果折叠、图片占位 */
        private CharSequence buildStyledText(SessionApi.Message m) {
            boolean expanded = expandedIds.contains(m.id);
            if (m.blocks.isEmpty()) return styledCodeBlock(m.text); // 旧缓存兼容
            android.text.SpannableStringBuilder sp = new android.text.SpannableStringBuilder();
            for (int i = 0; i < m.blocks.size(); i++) {
                SessionApi.Block b = m.blocks.get(i);
                if (i > 0) sp.append("\n");
                String type = b.type;
                if ("text".equals(type)) {
                    // 超长纯文本折叠（代码块保持原样，折叠会破坏标记配对）
                    String t = b.text;
                    if (t != null && t.length() > 800 && !t.contains("```")) {
                        t = fold(t, expanded, 800);
                    }
                    sp.append(styledCodeBlock(t));
                } else if ("thinking".equals(type)) {
                    String txt = fold(b.text, expanded, 120);
                    int start = sp.length();
                    sp.append(getString(R.string.render_thinking)).append(txt);
                    sp.setSpan(new android.text.style.ForegroundColorSpan(0xFF8A94A0), start, sp.length(),
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sp.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.ITALIC), start, sp.length(),
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if ("tool_use".equals(type)) {
                    String name = (b.toolName != null && !b.toolName.isEmpty()) ? b.toolName : "工具";
                    String txt = fold(b.text, true, 200);
                    int start = sp.length();
                    sp.append("⚙ ").append(name).append("\n").append(txt);
                    sp.setSpan(new android.text.style.ForegroundColorSpan(0xFF4DA3FF), start,
                            start + 2 + name.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sp.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start,
                            start + 2 + name.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sp.setSpan(new android.text.style.TypefaceSpan("monospace"), start, sp.length(),
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if ("tool_result".equals(type)) {
                    String txt = fold(b.text, expanded, 120);
                    int start = sp.length();
                    sp.append(getString(R.string.render_result)).append(txt);
                    sp.setSpan(new android.text.style.ForegroundColorSpan(0xFF8A94A0), start, sp.length(),
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if ("image".equals(type) || "image_url".equals(type)) {
                    int start = sp.length();
                    sp.append("🖼 [图片] 请在完整版中查看");
                    sp.setSpan(new android.text.style.ForegroundColorSpan(0xFF8A94A0), start, sp.length(),
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if ("file".equals(type) || "attachment".equals(type)) {
                    int start = sp.length();
                    sp.append("📎 [文件] 请在完整版中查看");
                    sp.setSpan(new android.text.style.ForegroundColorSpan(0xFF8A94A0), start, sp.length(),
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (b.text != null && !b.text.isEmpty()) {
                    sp.append(styledCodeBlock(b.text));
                }
            }
            return sp;
        }

        /** 长文本折叠：超过 maxLen 截断并提示点击展开（按 code point 截断，不切 emoji 代理对） */
        private String fold(String text, boolean expanded, int maxLen) {
            if (text == null) return "";
            if (expanded || text.length() <= maxLen) return text;
            try {
                int cps = text.codePointCount(0, text.length());
                int cut = Math.min(maxLen, cps);
                int end = text.offsetByCodePoints(0, cut);
                return text.substring(0, end) + "…（点击展开）";
            } catch (IndexOutOfBoundsException e) {
                return text.substring(0, maxLen) + "…（点击展开）";
            }
        }

        /** 代码块（```...```）用等宽字体 + 深色背景 */
        private CharSequence styledCodeBlock(String text) {
            if (text == null) return "";
            if (!text.contains("```")) return text;
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
