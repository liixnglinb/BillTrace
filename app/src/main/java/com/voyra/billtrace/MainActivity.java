package com.voyra.billtrace;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_SMS = 1001;
    private WebView webView;
    private boolean pendingImport = false;
    private boolean smsAskedAndBlocked = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 21) {
            Window w = getWindow();
            w.setStatusBarColor(0xFFFFFFFF);
            w.setNavigationBarColor(0xFFFFFFFF);
            if (Build.VERSION.SDK_INT >= 23) {
                w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
        }

        webView = new WebView(this);
        webView.setBackgroundColor(0xFFFFFFFF);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // 不开 setAllowFileAccess：加载 file:///android_asset/ 用不到它，
        // 开了反而让桥所在页面能读设备上任意 file:// 路径。
        s.setDefaultTextEncodingName("utf-8");
        s.setTextZoom(100);

        // 只允许回到自己的资产页。桥是跟着 WebView 而不是跟着页面走的，
        // 一旦导航到外部站点，window.BT 就暴露给那个源了。
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return url == null || !url.startsWith("file:///android_asset/");
            }
        });
        webView.addJavascriptInterface(new Bridge(this), "BT");
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadData();
    }

    /** 页面回到前台时刷一遍真实数据。 */
    public void reloadData() {
        if (webView == null) return;
        webView.post(new Runnable() {
            @Override
            public void run() {
                webView.evaluateJavascript("window.BTRefresh&&window.BTRefresh();", null);
            }
        });
    }

    public boolean hasSmsPermission() {
        return checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 系统弹窗已经不会再出现了：Android 11+ 拒绝两次后自动变成「禁止后不再询问」，
     * 此后 requestPermissions() 不弹框直接回调 DENIED，只能去设置页手动开。
     */
    public boolean isSmsPermanentlyDenied() {
        return smsAskedAndBlocked && !hasSmsPermission();
    }

    /** 先要短信权限，拿到就直接回填历史账目。 */
    public void requestSmsThenImport() {
        if (hasSmsPermission()) {
            doBackfill();
            return;
        }
        if (isSmsPermanentlyDenied()) {
            openSmsPermissionSettings();
            return;
        }
        pendingImport = true;
        requestPermissions(new String[]{Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS}, REQ_SMS);
    }

    private void doBackfill() {
        SmsReceiver.backfill(this, new SmsReceiver.Callback() {
            @Override
            public void done(final int scanned, final int added) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, "已扫描 " + scanned + " 条短信，新增 " + added + " 笔", Toast.LENGTH_LONG).show();
                        reloadData();
                    }
                });
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] granted) {
        super.onRequestPermissionsResult(code, perms, granted);
        if (code != REQ_SMS) return;
        boolean ok = granted.length > 0 && granted[0] == PackageManager.PERMISSION_GRANTED;
        if (ok) {
            smsAskedAndBlocked = false;
            if (pendingImport) {
                pendingImport = false;
                doBackfill();
            }
            return;
        }
        pendingImport = false;
        // 回调这一刻 rationale 为 false，说明系统已经把「不再询问」钉死了。
        smsAskedAndBlocked = !shouldShowRequestPermissionRationale(Manifest.permission.READ_SMS);
        Toast.makeText(this, smsAskedAndBlocked
                ? "短信权限已被系统记住「不再询问」，需要去设置页手动打开"
                : "没有短信权限，只能靠通知监听记账", Toast.LENGTH_LONG).show();
        reloadData();
    }

    /**
     * 打开通知使用权设置页。国产 ROM 常常不注册原生那个 action，原生页打不开时
     * 一路往下退，保证用户总能落到一个真实存在的页面，而不是「点了没反应」。
     */
    public void openNotificationSettings() {
        // 原生「通知使用权」列表页。
        if (tryStart(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) {
            if (!hasNotificationAccess()) {
                toast("在列表里找到「账迹」并打开开关，返回后这里会自动更新");
            }
            return;
        }
        // MIUI/HyperOS 常把上面那页换掉，走它自己的权限编辑器；包名不对时这里自然失败。
        Intent miui = new Intent("miui.intent.action.APP_PERM_EDITOR");
        miui.setPackage("com.miui.securitycenter");
        miui.putExtra("extra_pkgname", getPackageName());
        if (tryStart(miui)) {
            toast("已打开小米权限页：其他权限 → 通知使用权");
            return;
        }
        // 注意不能退到应用信息页：通知使用权属于「特殊应用权限」，那里根本找不到它。
        if (tryStart(new Intent(Settings.ACTION_SETTINGS))) {
            toast("请在设置里搜索「通知使用权」或「通知读取权限」");
            return;
        }
        toast("这个系统没有可跳转的权限页面，请到 设置 → 通知 → 通知使用权 手动开启");
    }

    /** 通知使用权到底开没开，以系统记录为准。 */
    public boolean hasNotificationAccess() {
        try {
            String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            if (flat == null || flat.isEmpty()) return false;
            return flat.contains(getPackageName());
        } catch (Exception e) {
            return getSharedPreferences(PayNotifyListener.PREFS, MODE_PRIVATE)
                    .getBoolean(PayNotifyListener.KEY_LISTENER, false);
        }
    }

    /** 短信权限被系统记住「不再询问」后，只能从应用信息页手动开。 */
    public void openSmsPermissionSettings() {
        if (openAppDetails()) {
            toast("请在 权限 → 短信 → 读取短信 里选「允许」");
            return;
        }
        if (!tryStart(new Intent(Settings.ACTION_SETTINGS))) {
            toast("打不开设置页，请手动进入 设置 → 应用 → 账迹 → 权限");
        }
    }

    private boolean openAppDetails() {
        Intent d = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        d.setData(Uri.parse("package:" + getPackageName()));
        return tryStart(d);
    }

    private boolean tryStart(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    public String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            // 不写死版本号字面量，否则每次发版要记得改这里。空串让页面显示「—」。
            return "";
        }
    }

    @Override
    public void onBackPressed() {
        if (webView == null) {
            super.onBackPressed();
            return;
        }
        // 弹层开着就先关弹层，页面自己会回 true
        webView.evaluateJavascript("(function(){return window.BTBack?window.BTBack():false})()",
                new android.webkit.ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        if ("true".equals(value)) return;
                        if (webView.canGoBack()) webView.goBack();
                        else finish();
                    }
                });
    }
}
