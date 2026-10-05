package com.voyra.billtrace;

import android.os.Build;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/** 页面和真实数据之间的桥。页面只拿这里给的数据，不再有任何写死的演示账目。 */
public class Bridge {

    private static final String TAG = "BillTrace";

    private final MainActivity act;

    public Bridge(MainActivity act) {
        this.act = act;
    }

    private TxnStore db() {
        return TxnStore.get(act);
    }

    /**
     * 出错时带 error 字段回去，而不是返回 "{}"。返回空对象会让页面把「读取失败」
     * 误判成「没有授权」，用户就会去反复开一个已经开好的权限。
     */
    @JavascriptInterface
    public String status() {
        try {
            JSONObject o = new JSONObject();
            o.put("listener", isListenerEnabled());
            o.put("sms", hasSms());
            o.put("smsBlocked", act.isSmsPermanentlyDenied());
            o.put("count", db().count());
            o.put("pending", db().pendingCount());
            o.put("ver", act.versionName());
            return o.toString();
        } catch (Throwable e) {
            Log.e(TAG, "status failed", e);
            return errorJson(e);
        }
    }

    private static String errorJson(Throwable e) {
        try {
            return new JSONObject().put("error", e.getClass().getSimpleName() + ": " + e.getMessage()).toString();
        } catch (Exception ignored) {
            return "{\"error\":\"unknown\"}";
        }
    }

    @JavascriptInterface
    public String list(int limit) {
        return txnsJson(db().list(limit <= 0 ? 200 : limit));
    }

    @JavascriptInterface
    public String pendingList() {
        return txnsJson(db().pending());
    }

    private String txnsJson(List<Txn> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Txn t : list) {
                JSONObject o = new JSONObject();
                o.put("id", t.id);
                o.put("ts", t.timeMillis);
                o.put("amt", t.amount);
                o.put("m", t.merchant);
                o.put("cat", t.category);
                o.put("sub", t.sub);
                o.put("app", t.app);
                o.put("src", t.source);
                o.put("acc", t.account);
                o.put("raw", t.raw);
                o.put("conf", t.confidence);
                o.put("ok", t.confirmed);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        return arr.toString();
    }

    @JavascriptInterface
    public String month() {
        return rangeJson(db().monthStart(System.currentTimeMillis()), System.currentTimeMillis());
    }

    @JavascriptInterface
    public String today() {
        long from = db().dayStart(System.currentTimeMillis(), 0);
        return rangeJson(from, from + 86400000L);
    }

    private String rangeJson(long from, long to) {
        try {
            double[] s = db().sum(from, to);
            JSONObject o = new JSONObject();
            o.put("expense", s[0]);
            o.put("income", s[1]);
            o.put("count", (int) s[2]);
            return o.toString();
        } catch (Throwable e) {
            Log.e(TAG, "rangeJson failed", e);
            return errorJson(e);
        }
    }

    @JavascriptInterface
    public String daily(int n) {
        JSONArray arr = new JSONArray();
        try {
            for (double v : db().dailyExpense(n <= 0 ? 7 : n)) arr.put(v);
        } catch (Throwable e) {
            Log.e(TAG, "daily failed", e);
        }
        return arr.toString();
    }

    @JavascriptInterface
    public String cats(int days) {
        JSONArray arr = new JSONArray();
        try {
            int n = days <= 0 ? 30 : days;
            long to = System.currentTimeMillis();
            long from = db().dayStart(to, -(n - 1));
            Map<String, Double> m = db().byCategory(from, to + 86400000L);
            for (Map.Entry<String, Double> e : m.entrySet()) {
                JSONObject o = new JSONObject();
                o.put("id", e.getKey());
                o.put("amt", e.getValue());
                arr.put(o);
            }
        } catch (Throwable e) {
            Log.e(TAG, "cats failed", e);
        }
        return arr.toString();
    }

    @JavascriptInterface
    public void setCategory(long id, String cat, String sub, boolean remember) {
        db().updateCategory(id, cat, sub == null ? "" : sub, remember);
    }

    /** 软删除，配合页面上的撤销条；数据仍在库里，可用 restore 回插。 */
    @JavascriptInterface
    public void remove(long id) {
        db().delete(id);
    }

    /** 撤销删除：字段 JSON 由页面在删除前收集。 */
    @JavascriptInterface
    public void restore(String json) {
        db().restore(json);
    }

    @JavascriptInterface
    public void addManual(double amount, String merchant, String cat, String sub) {
        Txn t = new Txn();
        t.timeMillis = System.currentTimeMillis();
        t.amount = amount;
        t.merchant = merchant == null || merchant.isEmpty() ? "手动记账" : merchant;
        String[] cls = Rules.classify(t.merchant, "");
        t.category = cat == null || cat.isEmpty() ? cls[0] : cat;
        t.sub = sub == null || sub.isEmpty() ? cls[1] : sub;
        t.app = cls[2];
        t.source = "手动";
        t.account = "";
        t.raw = "手动添加";
        t.confidence = 100;
        t.confirmed = 1;
        db().insert(t);
    }

    @JavascriptInterface
    public void importSms() {
        if (!hasSms()) {
            act.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    act.requestSmsThenImport();
                }
            });
            return;
        }
        boolean started = SmsReceiver.backfill(act, new SmsReceiver.Callback() {
            @Override
            public void done(final int scanned, final int added) {
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(act, "已扫描 " + scanned + " 条短信，新增 " + added + " 笔", Toast.LENGTH_LONG).show();
                        act.reloadData();
                    }
                });
            }
        });
        if (!started) {
            act.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(act, "正在扫描短信，请稍候", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    @JavascriptInterface
    public void openNotificationSettings() {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                act.openNotificationSettings();
            }
        });
    }

    /** 短信权限被系统记住「不再询问」时，页面用它把用户送到应用信息页。 */
    @JavascriptInterface
    public void openSmsPermissionSettings() {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                act.openSmsPermissionSettings();
            }
        });
    }

    @JavascriptInterface
    public String exportCsv() {
        return db().exportCsv();
    }

    /** 导出 CSV 到「下载」文件夹，Android 10 以下落到应用目录。带 BOM 方便 Excel 打开中文。 */
    @JavascriptInterface
    public void saveFile(final String csv) {
        if (csv == null || csv.isEmpty()) {
            toast("还没有记录可导出");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String msg = writeCsv(csv);
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(act, msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "billtrace-export").start();
    }

    private String writeCsv(String csv) {
        String name = "账迹-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.CHINA)
                .format(new java.util.Date()) + ".csv";
        byte[] data;
        try {
            data = ("\uFEFF" + csv).getBytes("UTF-8");
        } catch (Exception e) {
            data = csv.getBytes();
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/csv");
                v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS);
                android.net.Uri uri = act.getContentResolver()
                        .insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    java.io.OutputStream os = act.getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        os.write(data);
                        os.close();
                        return "已导出到「下载」文件夹：" + name;
                    }
                }
            }
            java.io.File dir = act.getExternalFilesDir(null);
            if (dir == null) dir = act.getFilesDir();
            java.io.File f = new java.io.File(dir, name);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            fos.write(data);
            fos.close();
            return "已导出：" + f.getAbsolutePath();
        } catch (Throwable e) {
            return "导出失败：" + e.getMessage();
        }
    }

    @JavascriptInterface
    public void toast(String msg) {
        final String m = msg;
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(act, m, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // 以下两个不是桥方法，只供 Java 内部使用；暴露给 JS 没有收益，只会扩大桥的攻击面。

    private boolean isListenerEnabled() {
        return act.hasNotificationAccess();
    }

    private boolean hasSms() {
        return act.hasSmsPermission();
    }
}
