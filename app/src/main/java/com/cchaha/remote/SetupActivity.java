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
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.util.List;

/**
 * 连接设置页：管理多台电脑的 H5 地址（添加/选择/重命名/删除），支持扫码与深链。
 */
@SuppressLint("NewApi")
public class SetupActivity extends Activity {

    static final String EXTRA_MANUAL = "manual"; // 从主界面回来（换地址），禁止自动跳转

    private Storage storage;
    private ListView hostList;
    private EditText urlInput;
    private HostAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setStatusBarColor(0xFF111418);
        w.setNavigationBarColor(0xFF111418);
        setContentView(R.layout.activity_setup);

        storage = new Storage(this);
        hostList = findViewById(R.id.host_list);
        urlInput = findViewById(R.id.setup_url);
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
                startMain();
                return;
            }
        }

        // 冷启动且有当前地址：直接进主界面；从主界面回来则显示列表
        if (!manual && storage.getCurrentHost() != null) {
            startMain();
            return;
        }

        // 已有地址时的快捷入口
        if (!storage.getHosts().isEmpty() && manual) {
            // 从主界面回来：显示列表即可
        }

        hint.setText(R.string.setup_hint);

        adapter = new HostAdapter(storage.getHosts());
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
            String normalized = UrlUtils.normalize(url);
            if (normalized == null) {
                Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show();
                return;
            }
            Storage.SavedHost host = storage.upsertHost(normalized);
            if (host != null) {
                storage.setCurrentHost(host.id);
                urlInput.setText("");
                adapter.refresh(storage.getHosts());
                startMain();
            }
        });

        scan.setOnClickListener(v -> {
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
        });
    }

    private void startMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    /** 长按菜单：重命名 / 删除 */
    private void showHostActions(Storage.SavedHost host) {
        String[] actions = {getString(R.string.action_rename), getString(R.string.action_delete)};
        new AlertDialog.Builder(this)
                .setTitle(host.name)
                .setItems(actions, (d, which) -> {
                    if (which == 0) showRenameDialog(host);
                    else if (which == 1) {
                        storage.removeHost(host.id);
                        adapter.refresh(storage.getHosts());
                        Toast.makeText(this, R.string.host_deleted, Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
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
        if (requestCode == IntentIntegrator.REQUEST_CODE) {
            IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
            if (result != null && result.getContents() != null) {
                String url = result.getContents().trim();
                if (UrlUtils.isUsable(url)) {
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
        private List<Storage.SavedHost> hosts;

        HostAdapter(List<Storage.SavedHost> hosts) {
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
            current.setVisibility(View.GONE);
            return v;
        }
    }
}
