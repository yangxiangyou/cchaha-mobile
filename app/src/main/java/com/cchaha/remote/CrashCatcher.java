package com.cchaha.remote;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局崩溃捕获：崩溃时把堆栈写入文件并跳转 CrashActivity 展示，
 * 避免"点一下就回桌面"的无反馈退出，方便用户反馈真实错误。
 */
public final class CrashCatcher {

    private static final String TAG = "CrashCatcher";
    private static final String CRASH_FILE = "crash.log";
    private static volatile boolean installed = false;

    private CrashCatcher() { }

    /** 注册全局崩溃处理器（Application.onCreate 或首个 Activity 调用，幂等） */
    public static void install(final Context context) {
        if (installed) return;
        installed = true;
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                String stack = stackToString(throwable, context);
                writeCrash(context, stack);
                Log.e(TAG, stack);

                // 跳转错误展示页（不调用系统默认处理——它会立即杀进程，错误页来不及显示）
                // 用户看到错误信息、复制或退出后，进程再自行结束
                Activity top = TopActivityHolder.get();
                if (top != null && !top.isFinishing()) {
                    Intent i = new Intent(top, CrashActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    i.putExtra(CrashActivity.EXTRA_STACK, stack);
                    top.startActivity(i);
                } else {
                    // 无前台 Activity 可展示：直接退出
                    android.os.Process.killProcess(android.os.Process.myPid());
                }
            } catch (Exception ignored) {
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        });
    }

    /** 崩溃页用户点"退出"时调用 */
    public static void exit() {
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    /** 记录前台 Activity（供崩溃时跳转） */
    public static void trackActivity(Activity activity) {
        TopActivityHolder.set(activity);
    }

    public static void untrackActivity(Activity activity) {
        TopActivityHolder.clear(activity);
    }

    /** 读取最近一次崩溃内容 */
    public static String readLastCrash(Context context) {
        File f = new File(context.getFilesDir(), CRASH_FILE);
        if (!f.exists()) return null;
        try {
            byte[] data = new byte[(int) Math.min(f.length(), 8192)];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(data);
            in.close();
            if (n > 0) return new String(data, 0, n, "UTF-8");
        } catch (Exception ignored) { }
        return null;
    }

    public static void clearCrash(Context context) {
        File f = new File(context.getFilesDir(), CRASH_FILE);
        if (f.exists()) f.delete();
    }

    private static String stackToString(Throwable t, Context context) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("===== cchaha Mobile Crash " +
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + " =====");
        pw.println("Device: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        pw.println("Android: " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")");
        pw.println("App: " + appVersion(context));
        pw.println();
        t.printStackTrace(pw);
        return sw.toString();
    }

    private static String appVersion(Context context) {
        try {
            android.content.pm.PackageInfo pi = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return pi.versionName + " (" + pi.versionCode + ")";
        } catch (Throwable e) {
            return "unknown";
        }
    }

    private static void writeCrash(Context context, String content) {
        try {
            FileWriter w = new FileWriter(new File(context.getFilesDir(), CRASH_FILE), false);
            w.write(content);
            w.close();
        } catch (Exception ignored) { }
    }

    /** 前台 Activity 跟踪（弱引用，避免长期持有阻止回收） */
    private static final class TopActivityHolder {
        private static volatile java.lang.ref.WeakReference<Activity> top =
                new java.lang.ref.WeakReference<>(null);

        static void set(Activity a) { top = new java.lang.ref.WeakReference<>(a); }
        static void clear(Activity a) {
            Activity cur = top.get();
            if (cur == a) top = new java.lang.ref.WeakReference<>(null);
        }
        static Activity get() { return top.get(); }
    }
}
