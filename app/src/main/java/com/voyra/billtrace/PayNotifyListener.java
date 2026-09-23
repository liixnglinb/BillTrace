package com.voyra.billtrace;

import android.app.Notification;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.Locale;

/**
 * 主采集引擎：读支付类通知。付款成功的那一刻通知会到，解析后 2 秒内入库。
 * 只认账目关键词齐全的通知，聊天里出现的“我付了35.8元”不会被误记。
 */
public class PayNotifyListener extends NotificationListenerService {

    public static final String PREFS = "billtrace";
    public static final String KEY_LISTENER = "listener_connected";
    private static final String TAG = "BillTrace";

    private static final String[] PAY_PKGS = {
            "com.eg.android.alipaygphone",   // 支付宝
            "com.tencent.mm",                // 微信
            "com.unionpay",                  // 云闪付
            "com.unionpay.tsmservice",       // 银联
            "com.unionpay.mobilepay",
            "com.cmbchina.ccd.pluto.cmbactivity", // 招商银行
            "cmb.pb",
            "com.icbc", "com.icbc.im",
            "com.ccb.longjilife", "com.chinamworld.main",
            "com.bankcomm.bankcomm",
            "com.android.bankabc",
            "com.spdbccc.app",
            "com.yitong.mbank.psbc",
            "cn.com.spdb.mobilebank.per",
            "com.pingan.paces.ccms",
            "com.cgbchina.xpt",
            "com.citic.bank.mobile",
            "com.meituan.retail.v.android", "com.sankuai.meituan", "com.sankuai.meituan.takeoutnew",
            "com.taobao.taobao", "com.tmall.wireless",
            "com.jingdong.app.mall",
            "com.xunmeng.pinduoduo",
            "com.sdu.didi.psnger",
            "com.autonavi.minimap",
            "com.tencent.mobileqq",
            "cn.gov.pbc.dcep",
    };

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        prefs().edit().putBoolean(KEY_LISTENER, true).apply();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        prefs().edit().putBoolean(KEY_LISTENER, false).apply();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            if (sbn == null || sbn.getNotification() == null) return;
            String pkg = sbn.getPackageName();
            if (pkg == null) return;
            if (pkg.equals(getPackageName())) return;
            if (!isPayPkg(pkg)) return;

            Notification n = sbn.getNotification();
            Bundle ex = n.extras;
            if (ex == null) return;

            String title = str(ex.getCharSequence(Notification.EXTRA_TITLE));
            String text = str(ex.getCharSequence(Notification.EXTRA_TEXT));
            String big = str(ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
            String sub = str(ex.getCharSequence(Notification.EXTRA_SUB_TEXT));
            StringBuilder sb = new StringBuilder();
            sb.append(title).append(' ');
            if (!sub.isEmpty()) sb.append(sub).append(' ');
            if (!text.isEmpty()) sb.append(text).append(' ');
            if (!big.isEmpty()) sb.append(big).append(' ');
            CharSequence[] lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null) {
                for (CharSequence cs : lines) sb.append(str(cs)).append(' ');
            }
            String body = sb.toString().trim();
            if (body.length() < 4) return;

            String channel = "";
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                String cid = n.getChannelId();
                if (cid != null) channel = cid.toLowerCase(Locale.ROOT);
            }
            if (!passGuard(pkg, title, body, channel)) return;

            Txn t = PayParser.parse(body, pkg, "通知", sbn.getPostTime());
            if (t == null) return;
            long id = TxnStore.get(this).insert(t);
            if (id > 0) Log.i(TAG, "通知入库 " + t.merchant + " " + t.amount);
        } catch (Throwable e) {
            Log.w(TAG, "onNotificationPosted: " + e.getMessage());
        }
    }

    /** 微信/QQ 的聊天消息和支付通知同包名，必须再拦一道。 */
    private boolean passGuard(String pkg, String title, String body, String channel) {
        String p = pkg.toLowerCase(Locale.ROOT);
        if (p.equals("com.tencent.mm")) {
            boolean ok = title.contains("微信支付") || title.contains("支付") || title.contains("收款")
                    || body.contains("微信支付") || body.contains("已支付") || body.contains("支付成功")
                    || body.contains("零钱") || channel.contains("pay") || channel.contains("transfer");
            if (!ok) return false;
        }
        if (p.equals("com.tencent.mobileqq")) {
            boolean ok = title.contains("QQ钱包") || body.contains("QQ钱包") || body.contains("支付成功");
            if (!ok) return false;
        }
        return true;
    }

    private boolean isPayPkg(String pkg) {
        String p = pkg.toLowerCase(Locale.ROOT);
        for (String s : PAY_PKGS) if (p.equals(s)) return true;
        return false;
    }

    private String str(CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }
}
