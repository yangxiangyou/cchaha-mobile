package com.cchaha.remote;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 崩溃信息展示页：显示最近一次崩溃的堆栈，一键复制，方便反馈问题。
 */
public class CrashActivity extends Activity {

    static final String EXTRA_STACK = "stack";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setStatusBarColor(0xFF111418);
        w.setNavigationBarColor(0xFF111418);
        setContentView(R.layout.activity_crash);

        TextView text = findViewById(R.id.crash_text);
        Button copy = findViewById(R.id.crash_copy);
        Button retry = findViewById(R.id.crash_retry);

        String stack = getIntent().getStringExtra(EXTRA_STACK);
        if (stack == null) stack = CrashCatcher.readLastCrash(this);
        text.setText(stack != null ? stack : "No crash recorded");

        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("crash", text.getText().toString()));
            Toast.makeText(this, "已复制，可以粘贴发给开发者", Toast.LENGTH_SHORT).show();
        });

        retry.setOnClickListener(v -> {
            CrashCatcher.clearCrash(this);
            Intent i = new Intent(this, SetupActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
            finish();
        });
    }

    @Override
    public void onBackPressed() {
        // 崩溃页按返回 = 退出应用（避免回到半损坏状态）
        CrashCatcher.exit();
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
}
