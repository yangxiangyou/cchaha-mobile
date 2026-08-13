package com.cchaha.remote;

import android.annotation.SuppressLint;
import android.app.Activity;

import java.util.List;
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
    private android.app.AlertDialog deepLinkDialog = null;

    enum ConnState { CONNECTING, CONNECTED, ERROR, DISCONNECTED }

    private FrameLayout webContainer;
    private View loadingMask;
    private ProgressBar maskProgress;
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
    private int loadGeneration = 0; // 加载代际：用户新操作使旧重连/回调失效
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
        loadingMask = findViewById(R.id.loading_mask);
        maskProgress = findViewById(R.id.mask_progress);
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
        // 安全：已保存主机直接连；未知主机需用户确认（防恶意深链注入白名单）
        Uri data = getIntent().getData();
        if (data != null && UrlUtils.isUsable(data.toString())) {
            handleDeepLink(data);
            return;
        }

        // 消息页"完整版"进入：优先加载消息页所属设备（通知跨设备场景不串设备）
        String hostUrl = getIntent().getStringExtra(SessionMessagesActivity.EXTRA_HOST_URL);
        String hostToken = getIntent().getStringExtra(SessionMessagesActivity.EXTRA_HOST_TOKEN);
        Storage.SavedHost current = storage.getCurrentHost();
        if (hostUrl != null && !hostUrl.isEmpty()) {
            loadUrl(joinHostUrl(hostUrl, hostToken));
        } else if (current != null) {
            loadUrl(current.url);
        } else {
            // 没地址：去连接页
            Intent i = new Intent(this, SetupActivity.class);
            i.putExtra(SetupActivity.EXTRA_MANUAL, true);
            startActivity(i);
            finish();
        }
    }

    /** baseUrl + token 拼完整加载 URL（token 空则原样地址） */
    private static String joinHostUrl(String base, String token) {
        if (token == null || token.isEmpty()) return base;
        String enc;
        try { enc = java.net.URLEncoder.encode(token, "UTF-8"); }
        catch (Exception e) { enc = token; }
        return base + "/?token=" + enc;
    }

    /**
     * 移动端 H5 的会话列表是默认收起的抽屉（汉堡按钮）：
     * 注入 JS 自动点开，让用户一进 WebView 就看到会话列表，
     * 而不是"新建会话"引导页。轮询 30 秒，失败静默（用户可手动点）。
     */
    private void openSessionDrawer() {
        if (webView == null) return;
        // window 标记去重：页面多次 onPageFinished（重定向等）不会叠加轮询
        webView.evaluateJavascript(
                "(function(){if(window.__hahaDrawerOpened)return;" +
                "var n=0,t=setInterval(function(){" +
                "var b=document.querySelector('[data-testid=\"mobile-sidebar-toggle\"]');" +
                "if(b){clearInterval(t);b.click();window.__hahaDrawerOpened=true;return;}" +
                "if(++n>150)clearInterval(t);},200);})();", null);
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
        // 转义为 JS 字符串字面量（防会话名注入脚本）
        final String match = org.json.JSONObject.quote(target);
        final String sid = org.json.JSONObject.quote(pendingSessionId);
        // pending 保留到 JS 侧确认成功（window 标记）：页面 reload/回退后仍可重试定位
        webView.evaluateJavascript(
                "(function(){if(window.__hahaAutoOpened)return;" +
                "var target=" + match + ",sid=" + sid + ",steps=0;" +
                "var t=setInterval(function(){steps++;" +
                "if(!window.__hahaPicked){var pk=[...document.querySelectorAll('button')].find(function(b){return /选择项目/.test(b.textContent||'')});" +
                "if(pk){pk.click();window.__hahaPicked=true;}}" +
                "var it=[...document.querySelectorAll('[class*=\"cursor-pointer\"],button')].find(function(e){" +
                "var x=(e.textContent||'').trim();return x.length>4&&x.length<80&&(x.indexOf(target.slice(0,12))>=0||target.indexOf(x.slice(0,10))>=0);});" +
                "if(it){it.click();window.__hahaAutoOpened=true;clearInterval(t);}" +
                "if(steps>40)clearInterval(t);},1000);})();", null);
    }

    /** 主机是否在已保存主机列表（SSL 放行与页面拦截白名单用；host:port 归一比较，防同主机异端口绕过） */
    private boolean isAllowedHost(String url) {
        try {
            String auth = UrlUtils.authorityOf(url);
            if (auth.isEmpty()) return false;
            if (currentUrl != null && !currentUrl.isEmpty()) {
                if (auth.equals(UrlUtils.authorityOf(currentUrl))) return true;
            }
            if (storage != null) {
                List<Storage.SavedHost> hosts = storage.getHosts();
                for (Storage.SavedHost h : hosts) {
                    if (auth.equals(UrlUtils.authorityOf(h.url))) return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
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
                String scheme = request.getUrl().getScheme();
                // http(s) 在 App 内加载；其他协议（mailto/tel/geo…）交系统处理，避免卡在 WebView 里
                if (scheme != null && !scheme.startsWith("http")) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, R.string.invalid_url, Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // 主文档 HTML 打补丁：屏蔽 H5 8 秒启动看门狗错误页（仅对已保存主机）
                if (request != null && request.isForMainFrame()
                        && ("http".equalsIgnoreCase(request.getUrl().getScheme())
                            || "https".equalsIgnoreCase(request.getUrl().getScheme()))
                        && isAllowedHost(request.getUrl().toString())) {
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
                if (loadingMask != null) loadingMask.setVisibility(View.VISIBLE);
                setConnState(ConnState.CONNECTING);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                if (loadingMask != null) loadingMask.setVisibility(View.GONE);
                boolean isHttp = url != null && (url.startsWith("http://") || url.startsWith("https://"));
                // 真实 http(s) 页面加载完成即视为连接成功（302 重定向后 URL 会变化，以最终 URL 为准）；
                // 错误页（data: URL）不重置错误态，避免"假绿灯"掩盖失败
                if (isHttp && !errorState) {
                    errorState = false;
                    mainFrameFailCount = 0;
                    currentUrl = url; // 重定向后同步最终地址
                    setConnState(ConnState.CONNECTED);
                    urlInput.setText(displayUrl(url));
                } else if (errorState) {
                    setConnState(ConnState.ERROR);
                }
                refreshButtons();
                if (isHttp) {
                    injectNarrowScreenFix();
                    openSessionDrawer();
                    autoOpenSession();
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request == null || !request.isForMainFrame()) return;
                progress.setVisibility(View.GONE);
                if (loadingMask != null) loadingMask.setVisibility(View.GONE);
                mainFrameFailCount++;

                // 自动重连一次：可能是临时闪断（带代际标记，用户操作后旧重连不生效）
                if (mainFrameFailCount <= 1 && !isFinishing() && !currentUrl.isEmpty()) {
                    setConnState(ConnState.CONNECTING);
                    final int gen = ++loadGeneration;
                    final String retryUrl = currentUrl;
                    view.postDelayed(() -> {
                        // mainFrameFailCount 已被 onPageFinished 清零说明页面其实加载成功了，
                        // 不再重载（避免"已连接又闪一下"）；loadUrlTemporary 保持"临时连接不保存"语义
                        if (!isFinishing() && gen == loadGeneration
                                && mainFrameFailCount > 0
                                && !currentUrl.isEmpty() && retryUrl.equals(currentUrl)) {
                            loadUrlTemporary(retryUrl);
                        }
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
                // 已保存主机（含当前地址）：放行（局域网/隧道自签、域名经 IP 访问场景）
                // 其他主机：拒绝并显示可读错误页，避免系统 SSL 报错页
                if (error != null && error.getUrl() != null && isAllowedHost(error.getUrl())) {
                    handler.proceed();
                } else {
                    handler.cancel();
                    if (error != null && error.getUrl() != null
                            && error.getUrl().startsWith("http")) {
                        showErrorPage();
                    }
                }
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
                        if (loadingMask != null) loadingMask.setVisibility(View.VISIBLE);
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
                if (maskProgress != null) maskProgress.setProgress(newProgress);
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
                loadUrlTemporary(currentUrl); // 错误页状态下重新加载原地址（不改写设备保存状态）
            } else {
                webView.reload();
            }
        });
        btnScan.setOnClickListener(v -> startScanner());
        btnHome.setOnClickListener(v -> {
            // 回原生会话列表（不 finish 自己：列表页返回键可回到 H5，避免"返回即退出"）
            startActivity(new Intent(this, SessionListActivity.class));
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
                    // 恢复加载当前地址；loadUrlTemporary 不改写设备保存状态
                    if (errorState && !currentUrl.isEmpty()) loadUrlTemporary(currentUrl);
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
        loadGeneration++; // 新加载使旧的重连/回调失效
        if (storage != null) {
            Storage.SavedHost host = storage.upsertHost(url);
            if (host != null) storage.setCurrentHost(host.id);
        }
        urlInput.setText(displayUrl(url));
        errorState = false;
        mainFrameFailCount = 0;
        setConnState(ConnState.CONNECTING);
        webView.loadUrl(url);
    }

    /** 地址栏脱敏显示：去掉 query（含 token），防截屏/录屏泄露 */
    private static String displayUrl(String url) {
        return UrlUtils.displayUrl(url);
    }

    private void showErrorPage() {
        // 品牌化错误页：朱红品牌字 + 检查清单（对齐 cc-haha 视觉语言）
        String html = "<html><body style='background:#111418;color:#9aa5b1;"
                + "font-family:sans-serif;display:flex;align-items:center;justify-content:center;"
                + "height:100vh;text-align:center;margin:0;padding:24px;box-sizing:border-box'>"
                + "<div style='max-width:340px'>"
                + "<div style='color:#96442b;font-size:13px;letter-spacing:2px;font-weight:bold'>CCHAHA MOBILE</div>"
                + "<h2 style='color:#e6e6e6;margin:14px 0 8px;font-size:19px'>"
                + getString(R.string.err_cant_connect) + "</h2>"
                + "<p style='font-size:13px;line-height:1.9;text-align:left'>"
                + getString(R.string.err_check_hint) + "</p>"
                + "<div style='margin-top:22px;color:#96442b;font-size:13px'>"
                + getString(R.string.err_tap_refresh) + "</div></div></body></html>";
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
            handleDeepLink(data);
        }
    }

    /** 深链处理：已保存主机直接连；未知主机弹确认（临时连接，不自动保存） */
    private void handleDeepLink(Uri data) {
        final String url = data.toString();
        String host = data.getHost();
        boolean known = host != null && hostSaved(url); // host:port 归一比较
        if (known) {
            loadUrl(url);
            return;
        }
        if (isFinishing() || isDestroyed()) return;
        // 对话框互斥：连续深链不叠加
        if (deepLinkDialog != null && deepLinkDialog.isShowing()) {
            deepLinkDialog.setMessage(getString(R.string.deeplink_confirm_msg, host == null ? url : host));
            return;
        }
        deepLinkDialog = new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.deeplink_confirm_title)
                .setMessage(getString(R.string.deeplink_confirm_msg, host == null ? url : host))
                .setPositiveButton(R.string.deeplink_confirm_ok, (d, w) -> loadUrlTemporary(url))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        deepLinkDialog.show();
    }

    /** 主机是否已保存（白名单判断用；host:port 归一比较） */
    private boolean hostSaved(String url) {
        if (storage == null) return false;
        try {
            String auth = UrlUtils.authorityOf(url);
            if (auth.isEmpty()) return false;
            List<Storage.SavedHost> hosts = storage.getHosts();
            for (Storage.SavedHost h : hosts) {
                if (auth.equals(UrlUtils.authorityOf(h.url))) return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "hostSaved failed", e);
        }
        return false;
    }

    /** 临时加载（深链未确认主机）：不保存、不设当前主机 */
    private void loadUrlTemporary(String url) {
        currentUrl = url;
        urlInput.setText(displayUrl(url));
        errorState = false;
        mainFrameFailCount = 0;
        setConnState(ConnState.CONNECTING);
        webView.loadUrl(url);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从设备页返回/其他页面修改过主机：重读最新列表，避免旧内存列表覆盖新改动
        if (storage != null) storage.reload();
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
        // 文件选择器未返回时置空回调，防止悬挂导致后续上传卡死
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
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
            // 没有可返回的历史则回到会话列表（不 finish：列表页再返回一次才退出）
            startActivity(new Intent(this, SessionListActivity.class));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
