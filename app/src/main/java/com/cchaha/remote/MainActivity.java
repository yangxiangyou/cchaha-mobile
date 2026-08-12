package com.cchaha.remote;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.webkit.DownloadListener;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Toast;


/**
 * cchaha Mobile - 主界面
 * WebView 全屏加载电脑上 cc-haha 的 H5 地址，实现 Codex 手机端式遥控。
 *
 * 功能：扫码连接 / 深链唤起 / 状态指示 / 自动重连 / 锁屏保持 / 断网提示 /
 *      文件上传下载 / WebView 崩溃自愈
 */
@SuppressLint({"SetJavaScriptEnabled", "NewApi"})
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_SCAN = 1002;
    static final String EXTRA_OPEN_SESSION = "open_session_id";
    static final String EXTRA_SESSION_TITLE = "open_session_title";

    private String pendingSessionId = null;
    private String pendingSessionTitle = null;

    enum ConnState { CONNECTING, CONNECTED, ERROR, DISCONNECTED }

    private FrameLayout webContainer;
    private WebView webView;
    private EditText urlInput;
    private View statusDot;
    private ProgressBar progress;
    private ImageButton btnBack;
    private ImageButton btnFwd;
    private PowerManager.WakeLock wakeLock;
    private ValueCallback<Uri[]> filePathCallback;
    private ConnectivityManager.NetworkCallback networkCallback;

    private Storage storage;
    private String currentUrl = "";
    private boolean errorState = false;
    private int mainFrameFailCount = 0;
    private boolean screenOn = true;
    private boolean netDownNotified = false;
    private ConnState connState = ConnState.DISCONNECTED;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                screenOn = false;
                acquireWakeLock(); // 锁屏：保持网络活跃，电脑端任务继续跑
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                screenOn = true;
                releaseWakeLock();
            }
        }
    };

    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(4L * 60 * 60 * 1000); // 超时保险 4 小时
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashCatcher.install(this);
        Window w = getWindow();
        w.setStatusBarColor(0xFF111418);
        w.setNavigationBarColor(0xFF111418);

        storage = new Storage(this);
        setContentView(R.layout.activity_main);

        webContainer = findViewById(R.id.web_container);
        urlInput = findViewById(R.id.url_input);
        statusDot = findViewById(R.id.status_dot);
        progress = findViewById(R.id.progress);
        btnBack = findViewById(R.id.btn_back);
        btnFwd = findViewById(R.id.btn_fwd);
        ImageButton btnReload = findViewById(R.id.btn_reload);
        ImageButton btnScan = findViewById(R.id.btn_scan);
        ImageButton btnHome = findViewById(R.id.btn_home);

        createWebView();
        bindButtons(btnReload, btnScan, btnHome);

        // 回车加载新地址
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            String url = urlInput.getText().toString().trim();
            if (!url.isEmpty() && UrlUtils.isUsable(UrlUtils.normalize(url))) {
                loadUrl(UrlUtils.normalize(url));
                urlInput.clearFocus();
            } else if (!url.isEmpty()) {
                Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show();
            }
            return true;
        });

        // 点击页面区域收键盘
        webView.setOnTouchListener((v, event) -> {
            if (urlInput.hasFocus()) urlInput.clearFocus();
            return false;
        });

        // 锁屏保持网络活跃（配合电脑端 30s 断连宽限）
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "haha-remote:wakelock");
        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, screenFilter);

        registerNetworkMonitor();

        // 原生会话列表点击进入：记录待定位的会话
        if (getIntent() != null) {
            pendingSessionId = getIntent().getStringExtra(EXTRA_OPEN_SESSION);
            pendingSessionTitle = getIntent().getStringExtra(EXTRA_SESSION_TITLE);
        }

        // 深链：外部 URL 唤起（浏览器点 H5 链接 / haha:// 协议）
        Uri data = getIntent().getData();
        if (data != null && UrlUtils.isUsable(data.toString())) {
            Storage.SavedHost host = storage.upsertHost(data.toString());
            if (host != null) {
                storage.setCurrentHost(host.id);
                loadUrl(host.url);
                return;
            }
        }

        Storage.SavedHost current = storage.getCurrentHost();
        if (current != null) {
            loadUrl(current.url);
        } else {
            // 没地址：去连接页
            Intent i = new Intent(this, SetupActivity.class);
            i.putExtra(SetupActivity.EXTRA_MANUAL, true);
            startActivity(i);
            finish();
        }
    }

    /**
     * 尽力自动定位会话：页面加载后注入 JS，
     * 自动点开"选择项目"（如需要）并按标题文本匹配点击目标会话。
     * cc-haha H5 不支持 URL 定位会话，此为页面自动化兜底；失败则用户手动点。
     */
    private void autoOpenSession() {
        if (pendingSessionId == null || webView == null) return;
        String target = pendingSessionTitle != null ? pendingSessionTitle : pendingSessionId;
        if (target.length() > 24) target = target.substring(0, 24);
        final String match = target.replace("'", "\\'");
        final String sid = pendingSessionId;
        pendingSessionId = null;
        pendingSessionTitle = null;
        webView.evaluateJavascript(
                "(function(){var target='" + match + "',sid='" + sid + "',steps=0;" +
                "var t=setInterval(function(){steps++;" +
                "if(!window.__hahaPicked){var pk=[...document.querySelectorAll('button')].find(function(b){return /选择项目/.test(b.textContent||'')});" +
                "if(pk){pk.click();window.__hahaPicked=true;}}" +
                "var it=[...document.querySelectorAll('[class*=\"cursor-pointer\"],button')].find(function(e){" +
                "var x=(e.textContent||'').trim();return x.length>4&&x.length<80&&(x.indexOf(target.slice(0,12))>=0||target.indexOf(x.slice(0,10))>=0);});" +
                "if(it){it.click();clearInterval(t);}" +
                "if(steps>40)clearInterval(t);},1000);})();", null);
    }

    /** 新建 WebView（可被 render 崩溃后重建复用） */
    @SuppressLint("SetJavaScriptEnabled")
    private void createWebView() {
        webView = new WebView(this);
        webContainer.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        // 关键：H5 靠 localStorage 记住连接 token，必须开 DOM storage
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // 安全收紧：不暴露文件/内容 provider
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false; // 全部在 App 内加载
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // 主文档 HTML 打补丁：屏蔽 H5 8 秒启动看门狗错误页（手机加载慢会闪错）
                if (request != null && request.isForMainFrame()
                        && ("http".equalsIgnoreCase(request.getUrl().getScheme())
                            || "https".equalsIgnoreCase(request.getUrl().getScheme()))) {
                    String patched = H5StartupPatcher.fetchPatched(request.getUrl().toString());
                    if (patched != null) {
                        return new WebResourceResponse("text/html", "UTF-8",
                                new java.io.ByteArrayInputStream(
                                        patched.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progress.setVisibility(View.VISIBLE);
                setConnState(ConnState.CONNECTING);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                errorState = false;
                mainFrameFailCount = 0;
                setConnState(ConnState.CONNECTED);
                urlInput.setText(url);
                refreshButtons();
                injectNarrowScreenFix();
                autoOpenSession();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request == null || !request.isForMainFrame()) return;
                progress.setVisibility(View.GONE);
                mainFrameFailCount++;

                // 自动重连一次：可能是临时闪断
                if (mainFrameFailCount <= 1 && !isFinishing() && !currentUrl.isEmpty()) {
                    setConnState(ConnState.CONNECTING);
                    view.postDelayed(() -> {
                        if (!isFinishing() && !currentUrl.isEmpty()) loadUrl(currentUrl);
                    }, 2000);
                    return;
                }

                errorState = true;
                setConnState(ConnState.ERROR);
                showErrorPage();
            }

            @Override
            public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler,
                                           android.net.http.SslError error) {
                // 局域网自签证书场景：允许继续（信任网络内使用）
                handler.proceed();
            }

            // WebView 渲染进程崩溃（内存压力/系统杀进程）：重建而不白屏
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (!isFinishing() && !isDestroyed()) {
                    Log.w(TAG, "render process gone, rebuilding webview");
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        webContainer.removeView(webView);
                        if (webView != null) {
                            webView.destroy();
                            webView = null;
                        }
                        createWebView();
                        if (!currentUrl.isEmpty()) webView.loadUrl(currentUrl);
                        Toast.makeText(MainActivity.this, R.string.webview_recovered, Toast.LENGTH_SHORT).show();
                    });
                    return true; // 吞掉崩溃，交给重建逻辑
                }
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE_CHOOSER);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        // 附件下载：交给系统浏览器处理
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, R.string.download_failed, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void bindButtons(ImageButton btnReload, ImageButton btnScan, ImageButton btnHome) {
        btnBack.setOnClickListener(v -> { if (webView.canGoBack()) webView.goBack(); });
        btnFwd.setOnClickListener(v -> { if (webView.canGoForward()) webView.goForward(); });
        btnReload.setOnClickListener(v -> {
            if (errorState && !currentUrl.isEmpty()) {
                loadUrl(currentUrl); // 错误页状态下重新加载原地址
            } else {
                webView.reload();
            }
        });
        btnScan.setOnClickListener(v -> startScanner());
        btnHome.setOnClickListener(v -> {
            // 回原生会话列表
            startActivity(new Intent(this, SessionListActivity.class));
            finish();
        });
    }

    private void registerNetworkMonitor() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLost(Network network) {
                runOnUiThread(() -> {
                    setConnState(ConnState.DISCONNECTED);
                    if (!netDownNotified) {
                        netDownNotified = true;
                        Toast.makeText(MainActivity.this, R.string.network_lost, Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override
            public void onAvailable(Network network) {
                runOnUiThread(() -> {
                    netDownNotified = false;
                    if (errorState && !currentUrl.isEmpty()) loadUrl(currentUrl);
                });
            }
        };
        try {
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception e) {
            Log.w(TAG, "network callback register failed", e);
        }
    }

    /** 打开扫码界面，扫电脑屏幕上的 H5 二维码（自研 CameraX 页面） */
    private void startScanner() {
        try {
            startActivityForResult(new Intent(this, ScanActivity.class), REQ_SCAN);
        } catch (Exception e) {
            Toast.makeText(this, R.string.scan_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 底部操作条一行化修复：cc-haha 页面底部工具条（新会话/审批/模型选择/发送按钮）
     * 在手机宽度下按钮互相重叠或换行成多行，视觉杂乱。
     * 注入 CSS：不换行 + 按钮缩小到 38px + 间距收紧到 2px，
     * 让所有按钮挤在一行且不重叠（图标按钮缩到 32px，模型选择器文字截断）。
     * 用 style 标签注入——SPA 内部路由重建 DOM 后依然生效。
     */
    private void injectNarrowScreenFix() {
        if (webView == null) return;
        webView.evaluateJavascript(
                "(function(){if(document.getElementById('haha-narrow-fix'))return;" +
                "var s=document.createElement('style');s.id='haha-narrow-fix';" +
                "s.textContent='" +
                "div[class*=\"border-t\"]:has(> div[class*=\"justify-end\"]){gap:4px!important;}" +
                "div[class*=\"shrink\"]:has(> button){gap:4px!important;}" +
                "div[class*=\"shrink\"]:has(> button) button{width:36px!important;height:38px!important;min-height:38px!important;padding:0 4px!important;}" +
                "[class*=\"justify-end\"][class*=\"gap-2\"]{flex-wrap:nowrap!important;gap:2px!important;padding-bottom:2px!important;padding-right:4px!important;}" +
                "[class*=\"justify-end\"][class*=\"gap-2\"]>*{min-width:0!important;flex-shrink:1!important;}" +
                "[class*=\"justify-end\"][class*=\"gap-2\"] button{height:38px!important;min-height:38px!important;padding-left:6px!important;padding-right:6px!important;flex-shrink:1!important;min-width:0!important;}" +
                "[class*=\"justify-end\"][class*=\"gap-2\"] button .material-symbols-outlined{font-size:17px!important;}" +
                "[class*=\"justify-end\"][class*=\"gap-2\"] button[class*=\"shrink-0\"]{width:auto!important;flex-shrink:1!important;}';" +
                "document.head.appendChild(s);})();", null);
    }

    private void loadUrl(String url) {
        currentUrl = url;
        if (storage != null) {
            Storage.SavedHost host = storage.upsertHost(url);
            if (host != null) storage.setCurrentHost(host.id);
        }
        urlInput.setText(url);
        errorState = false;
        mainFrameFailCount = 0;
        setConnState(ConnState.CONNECTING);
        webView.loadUrl(url);
    }

    private void showErrorPage() {
        String html = "<html><body style='background:#111418;color:#9aa5b1;"
                + "font-family:sans-serif;display:flex;align-items:center;justify-content:center;"
                + "height:100vh;text-align:center;margin:0'>"
                + "<div><h2 style='color:#e6e6e6'>" + getString(R.string.err_cant_connect) + "</h2>"
                + "<p>" + getString(R.string.err_check_hint) + "</p>"
                + "<p style='color:#4da3ff'>" + getString(R.string.err_tap_refresh) + "</p></div></body></html>";
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    /** 状态指示灯：灰=未连接 黄=连接中 绿=已连接 红=连接失败（保持圆形） */
    private void setConnState(ConnState state) {
        connState = state;
        if (statusDot == null) return;
        int color;
        switch (state) {
            case CONNECTED:   color = 0xFF3DDC84; break; // 绿
            case CONNECTING:  color = 0xFFFFB300; break; // 黄
            case ERROR:       color = 0xFFEF5350; break; // 红
            default:          color = 0xFF66707A; break; // 灰
        }
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        statusDot.setBackground(dot);
    }

    private void refreshButtons() {
        boolean canBack = webView != null && webView.canGoBack();
        boolean canFwd = webView != null && webView.canGoForward();
        btnBack.setAlpha(canBack ? 1f : 0.35f);
        btnFwd.setAlpha(canFwd ? 1f : 0.35f);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_SCAN) {
            if (resultCode == RESULT_OK && data != null) {
                String url = data.getStringExtra(ScanActivity.EXTRA_URL);
                if (url != null && UrlUtils.isUsable(url)) {
                    loadUrl(url);
                } else {
                    Toast.makeText(this, R.string.not_haha_qr, Toast.LENGTH_LONG).show();
                }
            }
        } else if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(resultCode == RESULT_OK
                        ? WebChromeClient.FileChooserParams.parseResult(resultCode, data) : null);
                filePathCallback = null;
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // 单例模式深链再次唤起
        Uri data = intent.getData();
        if (data != null && UrlUtils.isUsable(data.toString())) {
            Storage.SavedHost host = storage.upsertHost(data.toString());
            if (host != null) {
                storage.setCurrentHost(host.id);
                loadUrl(host.url);
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        CrashCatcher.trackActivity(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        CrashCatcher.untrackActivity(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 切到别的 App（屏幕还亮）时释放锁；锁屏由 SCREEN_OFF 广播接管
        if (screenOn) releaseWakeLock();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) { }
        if (networkCallback != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) { }
        }
        releaseWakeLock();
        if (webView != null) {
            webContainer.removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (webView != null && webView.canGoBack()) {
                webView.goBack();
                return true;
            }
            // 没有可返回的历史则回到会话列表
            startActivity(new Intent(this, SessionListActivity.class));
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
