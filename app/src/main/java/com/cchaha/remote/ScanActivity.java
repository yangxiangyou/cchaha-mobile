package com.cchaha.remote;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 自研扫码页：CameraX 相机预览 + ZXing QR 解码。
 * 扫电脑屏幕上 cc-haha 的 H5 二维码，返回 URL 给调用方。
 */
public class ScanActivity extends ComponentActivity {

    private static final String TAG = "ScanActivity";
    static final String EXTRA_URL = "url";

    private static final int REQ_CAMERA = 9001;

    private PreviewView previewView;
    private View scanFrame;
    private TextView hintText;
    private Button flashButton;
    private ExecutorService cameraExecutor;
    private boolean scanned = false;
    private boolean flashOn = false;
    private Camera camera;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashCatcher.install(this);
        Window w = getWindow();
        w.setStatusBarColor(0xFF111418);
        w.setNavigationBarColor(0xFF111418);
        setContentView(R.layout.activity_scan);

        previewView = findViewById(R.id.scan_preview);
        scanFrame = findViewById(R.id.scan_frame);
        hintText = findViewById(R.id.scan_hint);
        flashButton = findViewById(R.id.scan_flash);
        Button cancel = findViewById(R.id.scan_cancel);

        cameraExecutor = Executors.newSingleThreadExecutor();

        flashButton.setOnClickListener(v -> toggleFlash());
        cancel.setOnClickListener(v -> finish());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                showPermissionDialog();
            }
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void startCamera() {
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            if (isFinishing() || isDestroyed()) return;
            try {
                ProcessCameraProvider provider = ProcessCameraProvider.getInstance(this).get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;
                camera = provider.bindToLifecycle(this, selector, preview, analysis);
                flashButton.setVisibility(View.VISIBLE);
            } catch (Exception e) {
                Log.e(TAG, "camera start failed", e);
                // 显示具体原因（相机被占用/设备无相机/初始化失败），便于定位
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    showCameraErrorDialog(e);
                });
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /** 相机权限被拒：引导去系统设置开启 */
    private void showPermissionDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.scan_perm_title)
                .setMessage(R.string.scan_perm_msg)
                .setPositiveButton(R.string.scan_perm_go_settings, (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception ignored) { }
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> finish())
                .setOnDismissListener(d -> finish())
                .show();
    }

    /** 相机启动失败：显示具体错误 + 重试 */
    private void showCameraErrorDialog(Exception e) {
        String detail = e != null ? (e.getClass().getSimpleName() + ": " + e.getMessage()) : "unknown";
        new AlertDialog.Builder(this)
                .setTitle(R.string.scan_failed)
                .setMessage(getString(R.string.scan_error_detail, detail))
                .setPositiveButton(R.string.scan_retry, (d, w) -> {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED) {
                        startCamera();
                    } else {
                        ActivityCompat.requestPermissions(this,
                                new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                    }
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> finish())
                .show();
    }

    /** 相机帧 → ZXing 解码（YUV 直接喂 PlanarYUVLuminanceSource，不用转位图） */
    private void analyzeFrame(ImageProxy image) {
        if (scanned) {
            image.close();
            return;
        }
        try {
            byte[] yuv = imageProxyToNv21(image);
            if (yuv == null) {
                image.close();
                return;
            }
            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                    yuv, image.getWidth(), image.getHeight(),
                    0, 0, image.getWidth(), image.getHeight(), false);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Result result = new QRCodeReader().decode(bitmap);
            if (result != null && result.getText() != null) {
                scanned = true;
                String url = result.getText().trim();
                runOnUiThread(() -> {
                    Intent i = new Intent();
                    i.putExtra(EXTRA_URL, url);
                    setResult(RESULT_OK, i);
                    finish();
                });
            }
        } catch (Exception ignored) {
            // 单帧解码失败很正常（模糊/未对准），继续下一帧
        } finally {
            image.close();
        }
    }

    /** ImageProxy → NV21（zxing 需要的 YUV420 格式） */
    private static byte[] imageProxyToNv21(ImageProxy image) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        if (planes.length < 3) return null;
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();
        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];

        // Y 平面
        int rowStride = planes[0].getRowStride();
        int pixelStride = planes[0].getPixelStride();
        int width = image.getWidth();
        int height = image.getHeight();
        if (rowStride == width && pixelStride == 1) {
            yBuffer.get(nv21, 0, ySize);
        } else {
            // 紧凑拷贝（处理 stride 不相等的情况）
            int pos = 0;
            byte[] row = new byte[rowStride];
            for (int r = 0; r < height; r++) {
                yBuffer.get(row, 0, rowStride);
                for (int c = 0; c < width; c += pixelStride) {
                    if (pos < ySize) nv21[pos++] = row[c];
                }
            }
            // 剩余 Y 数据（罕见情况兜底）
            while (pos < ySize && yBuffer.hasRemaining()) nv21[pos++] = yBuffer.get();
        }

        // UV 交错平面（VU 顺序：NV21 是 V 前 U 后，zxing 对 U/V 顺序不敏感，这里保持原始交错即可）
        int uvPos = ySize;
        byte[] uRow = new byte[uBuffer.remaining()];
        byte[] vRow = new byte[vBuffer.remaining()];
        uBuffer.get(uRow);
        vBuffer.get(vRow);
        // 简单交错：把 U、V 依次写入（分辨率减半，stride 差异在解码时由 source 处理）
        for (int i = 0; i < uRow.length && uvPos < nv21.length; i++) {
            nv21[uvPos++] = vRow[i];
            if (uvPos < nv21.length) nv21[uvPos++] = uRow[i];
        }
        return nv21;
    }

    private void toggleFlash() {
        if (camera == null) return;
        try {
            if (camera.getCameraInfo().hasFlashUnit()) {
                flashOn = !flashOn;
                camera.getCameraControl().enableTorch(flashOn);
                flashButton.setTextColor(flashOn ? Color.parseColor("#4DA3FF") : Color.WHITE);
            } else {
                Toast.makeText(this, R.string.no_flash, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.w(TAG, "flash failed", e);
        }
    }

    @Override
    protected void onDestroy() {
        if (cameraExecutor != null) cameraExecutor.shutdown();
        super.onDestroy();
    }
}
