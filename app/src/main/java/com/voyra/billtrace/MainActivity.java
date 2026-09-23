package com.voyra.billtrace;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
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
        s.setAllowFileAccess(true);
        s.setDefaultTextEncodingName("utf-8");
        s.setTextZoom(100);

        webView.setWebViewClient(new WebViewClient());
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
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    /** 先要短信权限，拿到就直接回填历史账目。 */
    public void requestSmsThenImport() {
        if (hasSmsPermission()) {
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
            return;
        }
        pendingImport = true;
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS}, REQ_SMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] granted) {
        super.onRequestPermissionsResult(code, perms, granted);
        if (code != REQ_SMS) return;
        if (granted.length > 0 && granted[0] == PackageManager.PERMISSION_GRANTED) {
            if (pendingImport) {
                pendingImport = false;
                requestSmsThenImport();
            }
        } else {
            pendingImport = false;
            Toast.makeText(this, "没有短信权限，只能靠通知监听记账", Toast.LENGTH_LONG).show();
            reloadData();
        }
    }

    public String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0.4.0";
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
