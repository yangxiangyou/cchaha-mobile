package com.cchaha.remote;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
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

    /** 缓存新鲜阈值：进入页面时缓存在此时间内则跳过全量刷新 */
    private static final long CACHE_FRESH_MS = 30_000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Storage storage;
    private SessionCache cache;
    private ListView listView;
    private TextView statusText;
    private SessionAdapter adapter;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipe;
    private WebView prewarmWebView; // 预热 WebView（防 GC）
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
        swipe = findViewById(R.id.session_swipe);
        swipe.setColorSchemeColors(0xFF4DA3FF);
        swipe.setOnRefreshListener(this::forceRefresh); // 收尾在完成/失败回调里

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

        // 2. 后台刷新（进入时走 TTL：缓存新鲜则跳过）
        enterRefresh();

        refresh.setOnClickListener(v -> forceRefresh());
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

        // WebView 预热：H5 首页经 frp 隧道很慢（实测 20s+），提前加载写磁盘缓存，
        // 之后进完整版/新建会话时同 URL 命中缓存，黑屏时间大幅缩短
        mainHandler.postDelayed(this::prewarmH5, 1000);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            SessionApi.SessionInfo s = adapter.getItem(position);
            if (s == null) return;
            // 全面原生路线：所有会话一律进原生消息页（秒开，缓存先行）；
            // WebView 仅保留为兜底入口（完整版按钮/新建会话）
            Intent i = new Intent(this, SessionMessagesActivity.class);
            i.putExtra(SessionMessagesActivity.EXTRA_SESSION_ID, s.id);
            i.putExtra(SessionMessagesActivity.EXTRA_SESSION_TITLE, s.title);
            startActivity(i);
        });
    }

    /** 后台预热 H5（不 attach 到界面）：加载完整页面写入磁盘缓存，进 WebView 时提速 */
    private void prewarmH5() {
        if (isFinishing() || isDestroyed() || prewarmWebView != null
                || baseUrl.isEmpty() || token.isEmpty()) return;
        try {
            WebView wv = new WebView(this);
            prewarmWebView = wv;
            WebSettings s = wv.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setCacheMode(WebSettings.LOAD_DEFAULT);
            wv.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    // 预热完成即释放（磁盘缓存已写入，下次同 URL 加载命中缓存）
                    try { view.destroy(); } catch (Exception ignored) { }
                    if (prewarmWebView == view) prewarmWebView = null;
                }

                @Override
                public boolean onRenderProcessGone(WebView view,
                                                   android.webkit.RenderProcessGoneDetail detail) {
                    // 预热失败静默，不影响主进程
                    if (prewarmWebView == view) prewarmWebView = null;
                    return true;
                }
            });
            wv.loadUrl(baseUrl + "/?token=" + token);
        } catch (Exception e) {
            Log.w("SessionList", "prewarm failed", e);
            prewarmWebView = null;
        }
    }

    /** 从完整 H5 URL 提取 token（?token=xxx） */
    private String extractToken(String url) {
        int i = url.indexOf("token=");
        if (i < 0) return "";
        String rest = url.substring(i + 6);
        int j = rest.indexOf('&');
        return j > 0 ? rest.substring(0, j) : rest;
    }

    /** 进入页面时的自动刷新：缓存新鲜（30 秒内）直接跳过，不打扰用户 */
    private void enterRefresh() {
        long age = System.currentTimeMillis() - cache.savedAtMs();
        if (adapter.getCount() > 0 && age < CACHE_FRESH_MS) return;
        forceRefresh();
    }

    /** 强制刷新（手动按钮 / 下拉刷新 / 缓存过期） */
    private void forceRefresh() {
        if (baseUrl.isEmpty() || token.isEmpty()) {
            statusText.setText(R.string.session_no_host);
            swipe.setRefreshing(false);
            return;
        }
        // 有缓存时保持"缓存于 xx"文案，不显示"正在刷新"占位；无缓存才显示加载
        if (adapter.getCount() == 0) {
            statusText.setText(R.string.session_loading);
        }
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
                    swipe.setRefreshing(false);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (adapter.getCount() == 0) {
                        statusText.setText(R.string.session_failed);
                    } else {
                        statusText.setText(getString(R.string.session_cached_stale, fmtTime(cache.savedAtMs())));
                        Toast.makeText(this, R.string.session_refresh_failed, Toast.LENGTH_SHORT).show();
                    }
                    swipe.setRefreshing(false);
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
        // 清理全部挂起回调（含预热延迟），防止销毁后触碰 UI/创建 WebView
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        if (prewarmWebView != null) {
            try { prewarmWebView.destroy(); } catch (Exception ignored) { }
            prewarmWebView = null;
        }
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
