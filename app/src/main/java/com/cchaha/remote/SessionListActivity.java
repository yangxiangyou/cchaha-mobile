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
 * 原生会话列表（Codex 式体验）：
 * 打开 App 立即显示本地缓存的会话列表（秒开），后台从 cc-haha API 刷新。
 * 服务端不可用时显示缓存 + 提示，不白屏。
 */
public class SessionListActivity extends Activity {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Storage storage;
    private SessionCache cache;
    private ListView listView;
    private TextView statusText;
    private SessionAdapter adapter;
    private String baseUrl = "";
    private String token = "";
    private List<SessionApi.SessionInfo> allSessions = new ArrayList<>(); // 全量（供搜索过滤）

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashCatcher.install(this);
        Window w = getWindow();
        w.setStatusBarColor(0xFF111418);
        w.setNavigationBarColor(0xFF111418);
        setContentView(R.layout.activity_session_list);

        storage = new Storage(this);
        cache = new SessionCache(this);
        listView = findViewById(R.id.session_list);
        statusText = findViewById(R.id.session_status);
        Button refresh = findViewById(R.id.session_refresh);
        Button newSession = findViewById(R.id.session_new);
        TextView title = findViewById(R.id.session_title);
        EditText searchBox = findViewById(R.id.session_search);
        androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipe =
                findViewById(R.id.session_swipe);
        swipe.setColorSchemeColors(0xFF4DA3FF);

        Storage.SavedHost host = storage.getCurrentHost();
        if (host != null) {
            baseUrl = host.url.contains("?") ? host.url.substring(0, host.url.indexOf('?')) : host.url;
            token = extractToken(host.url);
            title.setText(host.name);
        } else {
            title.setText(getString(R.string.app_name));
        }

        // 1. 秒开：先显示缓存
        List<SessionApi.SessionInfo> cached = cache.load();
        allSessions = new ArrayList<>(cached);
        adapter = new SessionAdapter(cached);
        listView.setAdapter(adapter);
        if (cached.isEmpty()) {
            statusText.setText(R.string.session_loading);
        } else {
            statusText.setText(getString(R.string.session_cached_at, fmtTime(cache.savedAtMs())));
        }

        // 2. 后台刷新
        refreshSessions();

        refresh.setOnClickListener(v -> refreshSessions());
        swipe.setOnRefreshListener(() -> {
            refreshSessions();
            mainHandler.postDelayed(() -> swipe.setRefreshing(false), 3000);
        });
        newSession.setOnClickListener(v -> openMain(null, null));

        // 搜索过滤
        searchBox.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                String q = s.toString().trim().toLowerCase();
                List<SessionApi.SessionInfo> filtered = new ArrayList<>();
                for (SessionApi.SessionInfo item : allSessions) {
                    if (q.isEmpty()
                            || (item.title != null && item.title.toLowerCase().contains(q))
                            || (item.projectRoot != null && item.projectRoot.toLowerCase().contains(q))
                            || (item.modelId != null && item.modelId.toLowerCase().contains(q))) {
                        filtered.add(item);
                    }
                }
                adapter.refresh(filtered);
            }
        });

        // 自动更新检查（后台，失败静默）
        AppUpdateChecker.check(this);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            SessionApi.SessionInfo s = adapter.getItem(position);
            if (s == null) return;
            // 大会话走原生消息流（WebView 加载大会话会 120s 超时）
            if (s.messageCount >= 1000) {
                Intent i = new Intent(this, SessionMessagesActivity.class);
                i.putExtra(SessionMessagesActivity.EXTRA_SESSION_ID, s.id);
                i.putExtra(SessionMessagesActivity.EXTRA_SESSION_TITLE, s.title);
                startActivity(i);
            } else {
                openMain(s.id, s.title);
            }
        });
    }

    /** 从完整 H5 URL 提取 token（?token=xxx） */
    private String extractToken(String url) {
        int i = url.indexOf("token=");
        if (i < 0) return "";
        String rest = url.substring(i + 6);
        int j = rest.indexOf('&');
        return j > 0 ? rest.substring(0, j) : rest;
    }

    private void refreshSessions() {
        if (baseUrl.isEmpty() || token.isEmpty()) {
            statusText.setText(R.string.session_no_host);
            return;
        }
        statusText.setText(R.string.session_refreshing);
        final String url = baseUrl;
        final String tk = token;
        executor.execute(() -> {
            try {
                List<SessionApi.SessionInfo> sessions = SessionApi.fetchSessions(url, tk);
                cache.save(sessions);
                mainHandler.post(() -> {
                    allSessions = new ArrayList<>(sessions);
                    adapter.refresh(sessions);
                    statusText.setText(getString(R.string.session_updated, fmtTime(System.currentTimeMillis())));
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (adapter.getCount() == 0) {
                        statusText.setText(R.string.session_failed);
                    } else {
                        statusText.setText(getString(R.string.session_cached_stale, fmtTime(cache.savedAtMs())));
                        Toast.makeText(this, R.string.session_refresh_failed, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    /** 打开主界面（WebView）。带 sessionId+title 时页面加载后尝试自动定位该会话 */
    private void openMain(String sessionId, String title) {
        Intent i = new Intent(this, MainActivity.class);
        if (sessionId != null) i.putExtra(MainActivity.EXTRA_OPEN_SESSION, sessionId);
        if (title != null) i.putExtra(MainActivity.EXTRA_SESSION_TITLE, title);
        startActivity(i);
    }

    private String fmtTime(long ms) {
        if (ms <= 0) return "-";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(ms));
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    /** 列表适配器：标题 + 时间/模型 + 项目 */
    private class SessionAdapter extends BaseAdapter {
        private List<SessionApi.SessionInfo> items;

        SessionAdapter(List<SessionApi.SessionInfo> items) {
            this.items = new ArrayList<>(items);
        }

        void refresh(List<SessionApi.SessionInfo> newItems) {
            items = new ArrayList<>(newItems);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public SessionApi.SessionInfo getItem(int position) {
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
                v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_session, parent, false);
            }
            SessionApi.SessionInfo s = getItem(position);
            TextView title = v.findViewById(R.id.session_item_title);
            TextView meta = v.findViewById(R.id.session_item_meta);
            TextView time = v.findViewById(R.id.session_item_time);

            title.setText(s.title);
            String project = s.projectRoot;
            if (project != null && project.length() > 30) project = "…" + project.substring(project.length() - 30);
            String model = (s.modelId != null && !s.modelId.isEmpty()) ? s.modelId : "";
            meta.setText((project != null && !project.isEmpty() ? project : "未指定项目")
                    + (model.isEmpty() ? "" : " · " + model)
                    + " · " + s.messageCount + " 条");
            time.setText(relTime(s.modifiedAtMs));
            return v;
        }

        private String relTime(long ms) {
            if (ms <= 0) return "";
            long diff = System.currentTimeMillis() - ms;
            long min = diff / 60000;
            if (min < 1) return "刚刚";
            if (min < 60) return min + " 分钟前";
            long h = min / 60;
            if (h < 24) return h + " 小时前";
            long d = h / 24;
            if (d < 7) return d + " 天前";
            return new SimpleDateFormat("MM-dd", Locale.getDefault()).format(new Date(ms));
        }
    }
}
