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
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

/**
 * Haha Remote - 主界面
 * WebView 全屏加载电脑上 cc-haha 的 H5 地址，实现 Codex 手机端式遥控。
 *
 * 功能：扫码连接 / 深链唤起 / 状态指示 / 自动重连 / 锁屏保持 / 断网提示 /
 *      文件上传下载 / WebView 崩溃自愈
 */
@SuppressLint({"SetJavaScriptEnabled", "NewApi"})
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int REQ_FILE_CHOOSER = 1001;

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
            Intent i = new Intent(this, SetupActivity.class);
            i.putExtra(SetupActivity.EXTRA_MANUAL, true);
            startActivity(i);
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

    /** 打开扫码界面，扫电脑屏幕上的 H5 二维码 */
    private void startScanner() {
        try {
            new IntentIntegrator(this)
                    .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                    .setPrompt(getString(R.string.scan_prompt))
                    .setOrientationLocked(true)
                    .setBeepEnabled(false)
                    .initiateScan();
        } catch (Exception e) {
            Toast.makeText(this, R.string.scan_failed, Toast.LENGTH_SHORT).show();
        }
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
        if (requestCode == IntentIntegrator.REQUEST_CODE) {
            IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
            if (result != null && result.getContents() != null) {
                String url = result.getContents().trim();
                if (UrlUtils.isUsable(url)) {
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
            Intent i = new Intent(this, SetupActivity.class);
            i.putExtra(SetupActivity.EXTRA_MANUAL, true);
            startActivity(i);
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
