package com.voyra.billtrace;

import android.os.Build;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
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
            // 回填是全表扫描，可能跑好几秒；界面靠这个字段显示进行中并挡住重复点击
            o.put("scanning", SmsReceiver.isBackfillRunning());
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

    /** 前端能一次要走的条数上限。桥是公开调用面，不能让它把整表拉进 WebView。 */
    private static final int MAX_PAGE = 500;

    private static int page(int limit) {
        if (limit <= 0) return 200;
        return limit > MAX_PAGE ? MAX_PAGE : limit;
    }

    @JavascriptInterface
    public String list(int limit) {
        return txnsJson(db().list(page(limit)));
    }

    /** 键集分页的下一页；ts/id 传当前已加载列表最后一条的对应值。 */
    @JavascriptInterface
    public String listMore(long ts, long id, int limit) {
        return txnsJson(db().listAfter(ts, id, page(limit)));
    }

    /**
     * 搜商户 / 分类中文名 / 金额。检索必须在 SQL 侧做：前端只加载了当前页，
     * 在 S.list 上 filter 会静默漏掉没翻页到的账。
     */
    @JavascriptInterface
    public String search(String q) {
        String raw = q == null ? "" : q.trim();
        if (raw.isEmpty()) return "[]";
        // LIKE 的通配符转义在 TxnStore.escapeLike 里，那边有纯单测
        String like = TxnStore.escapeLike(raw);
        String lower = raw.toLowerCase(java.util.Locale.CHINA);
        List<String> cats = new ArrayList<String>();
        for (String id : Rules.CAT_IDS) {
            String name = Rules.catName(id);
            if (name != null && name.toLowerCase(java.util.Locale.CHINA).contains(lower)) cats.add(id);
        }
        Double amount = null;
        try {
            double v = Double.parseDouble(raw);
            if (v > 0 && v <= 1e9) amount = Double.valueOf(Math.round(v * 100) / 100.0);
        } catch (NumberFormatException ignored) {
        }
        return txnsJson(db().search(like, cats.toArray(new String[cats.size()]), amount, MAX_PAGE));
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

    /**
     * 账本损坏到打不开时，把坏库改名保留并重建空库。
     * 坏文件不删，用户之后还能找出来自己救数据。
     */
    @JavascriptInterface
    public boolean recoverDatabase() {
        try {
            boolean ok = TxnStore.recoverDatabase(act);
            act.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(act, "已重建空账本，损坏的库保留在本机应用目录", Toast.LENGTH_LONG).show();
                    act.reloadData();
                }
            });
            return ok;
        } catch (Throwable e) {
            Log.e(TAG, "recoverDatabase failed", e);
            return false;
        }
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

    /* ---------- 软件更新 ----------
       四个动作分开暴露，是因为中间那步「下载」由系统的 DownloadManager 异步完成：
       页面要能自己按需查进度、决定什么时候打开安装界面。
       网络只发生在 check 与 download 两处，且都只下载、不上传任何数据。 */

    /** 检查更新：{ok, current, latest, size, url, notes} */
    @JavascriptInterface
    public String updateCheck() {
        return Updater.check(act);
    }

    /** 开始下载安装包（系统下载服务，通知栏自带进度） */
    @JavascriptInterface
    public String updateDownload(String url, String version) {
        return Updater.download(act, url, version);
    }

    /** 查询下载进度：{state, bytes, total} */
    @JavascriptInterface
    public String updateProgress() {
        return Updater.progress(act);
    }

    /** 打开系统安装界面（用户自己点「下一步」完成安装） */
    @JavascriptInterface
    public String updateInstall() {
        return Updater.install(act);
    }

    // 以下两个不是桥方法，只供 Java 内部使用；暴露给 JS 没有收益，只会扩大桥的攻击面。

    private boolean isListenerEnabled() {
        return act.hasNotificationAccess();
    }

    private boolean hasSms() {
        return act.hasSmsPermission();
    }
}
