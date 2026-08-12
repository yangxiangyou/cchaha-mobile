package com.cchaha.remote;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;


import java.util.List;

/**
 * 连接设置页：管理多台电脑的 H5 地址（添加/选择/重命名/删除），支持扫码与深链。
 */
@SuppressLint("NewApi")
public class SetupActivity extends Activity {

    static final String EXTRA_MANUAL = "manual"; // 从主界面回来（换地址），禁止自动跳转
    private static final int REQ_SCAN = 2001;

    private Storage storage;
    private ListView hostList;
    private EditText urlInput;
    private EditText tokenInput;
    private HostAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashCatcher.install(this);
        Window w = getWindow();
        // 浅色首页：白底 + 深色状态栏图标（对齐 cc-haha 原版浅色风格）
        w.setStatusBarColor(0xFFFFFFFF);
        w.setNavigationBarColor(0xFFFFFFFF);
        w.getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        setContentView(R.layout.activity_setup);

        storage = new Storage(this);
        hostList = findViewById(R.id.host_list);
        urlInput = findViewById(R.id.setup_url);
        tokenInput = findViewById(R.id.setup_token);
        Button add = findViewById(R.id.setup_add);
        Button scan = findViewById(R.id.setup_scan);
        TextView hint = findViewById(R.id.setup_hint);

        boolean manual = getIntent().getBooleanExtra(EXTRA_MANUAL, false);

        // 深链唤起（浏览器点 H5 链接）：直接加入并连接
        Uri data = getIntent().getData();
        if (data != null && UrlUtils.isUsable(data.toString())) {
            Storage.SavedHost host = storage.upsertHost(data.toString());
            if (host != null) {
                storage.setCurrentHost(host.id);
                startSessionList();
                return;
            }
        }

        // 冷启动且有当前地址：直接进原生会话列表；从主界面回来则显示列表
        if (!manual && storage.getCurrentHost() != null) {
            startSessionList();
            return;
        }

        hint.setText(R.string.setup_hint);

        adapter = new HostAdapter(storage, storage.getHosts());
        hostList.setAdapter(adapter);

        hostList.setOnItemClickListener((parent, view, position, id) -> {
            Storage.SavedHost host = adapter.getItem(position);
            if (host != null) {
                storage.setCurrentHost(host.id);
                startMain();
            }
        });

        // 长按：重命名 / 删除
        hostList.setOnItemLongClickListener((parent, view, position, id) -> {
            Storage.SavedHost host = adapter.getItem(position);
            if (host != null) showHostActions(host);
            return true;
        });

        add.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if (url.isEmpty()) {
                Toast.makeText(this, R.string.enter_url_first, Toast.LENGTH_SHORT).show();
                return;
            }
            // 地址一行 + token 一行：拼接（兼容粘贴完整链接）
            String normalized = buildConnectUrl(url, tokenInput.getText().toString().trim());
            if (normalized == null) {
                if (UrlUtils.normalize(url) == null) {
                    Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, R.string.token_required, Toast.LENGTH_SHORT).show();
                }
                return;
            }
            Storage.SavedHost host = storage.upsertHost(normalized);
            if (host != null) {
                storage.setCurrentHost(host.id);
                urlInput.setText("");
                tokenInput.setText("");
                adapter.refresh(storage.getHosts());
                startMain();
            }
        });

        scan.setOnClickListener(v -> {
            try {
                startActivityForResult(new Intent(this, ScanActivity.class), REQ_SCAN);
            } catch (Exception e) {
                Toast.makeText(this, R.string.scan_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void startMain() {
        // 点击设备/添加设备 → 直接进入 cc-haha 主界面（H5 完整版）；
        // 想用原生会话列表可从主界面首页按钮进入
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private void startSessionList() {
        startActivity(new Intent(this, SessionListActivity.class));
        finish();
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

    /** 长按菜单：编辑 / 重命名 / 删除 */
    private void showHostActions(Storage.SavedHost host) {
        String[] actions = {getString(R.string.action_edit), getString(R.string.action_rename),
                getString(R.string.action_delete)};
        new AlertDialog.Builder(this)
                .setTitle(host.name)
                .setItems(actions, (d, which) -> {
                    if (which == 0) showEditDialog(host);
                    else if (which == 1) showRenameDialog(host);
                    else if (which == 2) {
                        storage.removeHost(host.id);
                        adapter.refresh(storage.getHosts());
                        Toast.makeText(this, R.string.host_deleted, Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    /** 编辑设备：名称 / 地址 / token 三字段（地址或 token 填错时修正，无需删除重加） */
    private void showEditDialog(Storage.SavedHost host) {
        // 解析现有 URL：base 与 token（URL 内为编码形式，回填时解码还原）
        String base = UrlUtils.trimTrailingSlash(host.url.contains("?")
                ? host.url.substring(0, host.url.indexOf('?')) : host.url);
        String token = UrlUtils.extractToken(host.url);

        EditText nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setHint(R.string.action_rename);
        nameInput.setText(host.name);
        EditText urlInput2 = new EditText(this);
        urlInput2.setSingleLine(true);
        urlInput2.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput2.setHint(R.string.url_hint);
        urlInput2.setText(base);
        EditText tokenInput = new EditText(this);
        tokenInput.setSingleLine(true);
        tokenInput.setHint(R.string.token_hint);
        tokenInput.setText(token);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 8, 24, 0);
        layout.addView(nameInput);
        layout.addView(urlInput2);
        layout.addView(tokenInput);

        new AlertDialog.Builder(this)
                .setTitle(R.string.action_edit)
                .setView(layout)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String newName = nameInput.getText().toString().trim();
                    String newUrl = buildConnectUrl(urlInput2.getText().toString().trim(),
                            tokenInput.getText().toString().trim());
                    if (newUrl == null) {
                        Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Storage.SavedHost updated = storage.upsertHost(newUrl);
                    if (updated != null) {
                        if (!updated.id.equals(host.id)) {
                            // URL 变化产生新条目：删除旧条目
                            storage.removeHost(host.id);
                            storage.setCurrentHost(updated.id);
                        } else if (!newName.isEmpty()) {
                            storage.renameHost(updated.id, newName);
                        }
                        adapter.refresh(storage.getHosts());
                        Toast.makeText(this, R.string.host_updated, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 按地址 + token 拼连接 URL；非法输入返回 null */
    private String buildConnectUrl(String urlText, String tokenText) {
        String normalized = UrlUtils.normalize(urlText);
        if (normalized == null) return null;
        if (normalized.contains("?token=")) return normalized; // 完整链接粘贴
        String token = tokenText.trim();
        if (token.isEmpty()) return null;
        int q = normalized.indexOf('?');
        String base = q > 0 ? normalized.substring(0, q) : normalized;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String encoded;
        try {
            encoded = java.net.URLEncoder.encode(token, "UTF-8");
        } catch (Exception e) {
            encoded = token;
        }
        return q > 0 ? base + normalized.substring(q) + "&token=" + encoded
                     : base + "/?token=" + encoded;
    }

    private void showRenameDialog(Storage.SavedHost host) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        input.setText(host.name);
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_rename)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    storage.renameHost(host.id, input.getText().toString());
                    adapter.refresh(storage.getHosts());
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_SCAN) {
            if (resultCode == RESULT_OK && data != null) {
                String url = data.getStringExtra(ScanActivity.EXTRA_URL);
                if (url != null && UrlUtils.isUsable(url)) {
                    Storage.SavedHost host = storage.upsertHost(url);
                    if (host != null) {
                        storage.setCurrentHost(host.id);
                        adapter.refresh(storage.getHosts());
                        startMain();
                    }
                } else {
                    Toast.makeText(this, R.string.not_haha_qr, Toast.LENGTH_LONG).show();
                }
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    /** 地址列表适配器 */
    private static class HostAdapter extends BaseAdapter {
        private final Storage storage;
        private List<Storage.SavedHost> hosts;

        HostAdapter(Storage storage, List<Storage.SavedHost> hosts) {
            this.storage = storage;
            this.hosts = hosts;
        }

        void refresh(List<Storage.SavedHost> newHosts) {
            hosts = newHosts;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return hosts.size();
        }

        @Override
        public Storage.SavedHost getItem(int position) {
            return hosts.get(position);
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
                v = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_host, parent, false);
            }
            Storage.SavedHost h = getItem(position);
            TextView name = v.findViewById(R.id.host_name);
            TextView url = v.findViewById(R.id.host_url);
            TextView current = v.findViewById(R.id.host_current);
            name.setText(h.name);
            url.setText(h.url);
            // 当前主机标记（朱红小标签）
            Storage.SavedHost cur = storage.getCurrentHost();
            current.setVisibility(cur != null && cur.id.equals(h.id) ? View.VISIBLE : View.GONE);
            return v;
        }
    }
}
