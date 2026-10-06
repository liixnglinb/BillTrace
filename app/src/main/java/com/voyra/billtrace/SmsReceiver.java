package com.voyra.billtrace;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.Telephony;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import java.util.Locale;

/**
 * 短信引擎：银行卡刷卡、转账、工资到账大多只有银行短信会通知，通知监听抓不到。
 * 除了实时接收，还会在授权后回填历史短信，装完当天就能看到过去的账。
 */
public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG = "BillTrace";
    private static final String[] BANK_HINTS = {"银行", "储蓄卡", "信用卡", "借记卡", "尾号", "账户", "余额"};

    private static final ExecutorService BACKFILL = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BACKFILL_RUNNING = new AtomicBoolean(false);

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            if (intent == null) return;
            String action = intent.getAction();
            if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(action)) return;

            android.telephony.SmsMessage[] msgs = null;
            if (Build.VERSION.SDK_INT >= 19) {
                msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent);
            }
            if (msgs == null || msgs.length == 0) return;

            StringBuilder body = new StringBuilder();
            String address = "";
            long when = 0;
            for (android.telephony.SmsMessage m : msgs) {
                if (m == null) continue;
                if (address.isEmpty() && m.getOriginatingAddress() != null) address = m.getOriginatingAddress();
                if (m.getMessageBody() != null) body.append(m.getMessageBody());
                if (when == 0) when = m.getTimestampMillis();
            }
            handle(ctx, address, body.toString(), when);
        } catch (Throwable e) {
            Log.w(TAG, "onReceive: " + e.getMessage());
        }
    }

    private void handle(Context ctx, String address, String body, long when) {
        if (body == null || body.isEmpty()) return;
        if (!looksLikeBank(address, body)) return;
        Txn t = PayParser.parse(body, address, "短信", when);
        if (t == null) return;
        long id = TxnStore.get(ctx).insert(t);
        // 只记来源与分类：商户名可能含短信原文，金额属于财务隐私，都不进日志。
        if (id > 0) Log.i(TAG, "短信入库 ok src=sms cat=" + t.category);
    }

    static boolean looksLikeBank(String address, String body) {
        if (address != null) {
            String digits = address.replaceAll("[^0-9]", "");
            if (digits.length() >= 5 && digits.contains("955")) return true;
            if (digits.startsWith("106") && body != null && hasBankHint(body)) return true;
        }
        return body != null && hasBankHint(body) && body.contains("元");
    }

    private static boolean hasBankHint(String body) {
        for (String h : BANK_HINTS) if (body.contains(h)) return true;
        return false;
    }

    /**
     * 回填历史短信。跑在单线程后台池里，完成后回调条数。
     *
     * @return false 表示已有一趟在跑、这次没有再起（否则连点几次会同时开几个全表扫描，
     *         而判重又不是原子的，同一笔短信会入库两遍）。
     */
    public static boolean backfill(final Context ctx, final Callback cb) {
        if (!BACKFILL_RUNNING.compareAndSet(false, true)) return false;
        BACKFILL.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    runBackfill(ctx, cb);
                } finally {
                    BACKFILL_RUNNING.set(false);
                }
            }
        });
        return true;
    }

    private static void runBackfill(final Context ctx, final Callback cb) {
        int added = 0, scanned = 0;
        Cursor c = null;
        try {
            Uri uri = Telephony.Sms.Inbox.CONTENT_URI;
            String[] proj = {Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE};
            c = ctx.getContentResolver().query(uri, proj, null, null, Telephony.Sms.DATE + " DESC");
            if (c != null) {
                while (c.moveToNext() && scanned < 8000) {
                    scanned++;
                    String addr = c.getString(0);
                    String body = c.getString(1);
                    long date = c.getLong(2);
                    if (body == null || body.isEmpty()) continue;
                    if (!looksLikeBank(addr, body)) continue;
                    Txn t = PayParser.parse(body, addr, "短信", date);
                    if (t == null) continue;
                    if (TxnStore.get(ctx).insert(t) > 0) added++;
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "backfill: " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }
        if (cb != null) cb.done(scanned, added);
    }

    /** 界面用它决定「导入历史短信」那一行要不要显示进行中并挡住重复点击。 */
    public static boolean isBackfillRunning() {
        return BACKFILL_RUNNING.get();
    }

    public interface Callback {
        void done(int scanned, int added);
    }
}
