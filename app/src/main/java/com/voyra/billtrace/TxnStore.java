package com.voyra.billtrace;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 本地账本。所有数据只在这台手机上，不上传。 */
public class TxnStore extends SQLiteOpenHelper {

    private static final String DB = "billtrace.db";
    /** v2：加 deleted 软删除列。改这里必须同时写 onUpgrade。 */
    private static final int VER = 2;
    private static final long DEDUP_WINDOW_MS = 120000L;
    private static final String TAG = "BillTrace";

    private static TxnStore instance;

    public static synchronized TxnStore get(Context ctx) {
        if (instance == null) instance = new TxnStore(ctx.getApplicationContext());
        return instance;
    }

    private TxnStore(Context ctx) {
        super(ctx, DB, null, VER);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE txns(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "ts INTEGER NOT NULL," +
                "amount REAL NOT NULL," +
                "merchant TEXT, category TEXT, sub TEXT, app TEXT," +
                "source TEXT, account TEXT, raw TEXT," +
                "confidence INTEGER DEFAULT 80, confirmed INTEGER DEFAULT 1," +
                "deleted INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_ts ON txns(ts DESC)");
        db.execSQL("CREATE TABLE rules(merchant TEXT PRIMARY KEY, category TEXT, sub TEXT, app TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // 空实现会让老用户升级后查到不存在的列而全部报错，绝不能留空。
        if (oldV < 2) db.execSQL("ALTER TABLE txns ADD COLUMN deleted INTEGER DEFAULT 0");
    }

    /**
     * 入库，带两级去重：同一笔钱在 2 分钟内被通知和短信同时捕获时只留信息最全的一条。
     * 返回写入的 id；判定为重复返回 -1。
     *
     * synchronized + 事务：判重是先 SELECT 再 INSERT，两步之间如果让另一个线程插进来，
     * 同一笔钱会记两次（通知与短信同时到达、或连点"导入历史短信"时真实会发生）。
     */
    public synchronized long insert(Txn t) {
        if (t == null || t.amount == 0) return -1;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long r = insertLocked(db, t);
            db.setTransactionSuccessful();
            return r;
        } catch (Throwable e) {
            Log.w(TAG, "insert: " + e.getMessage());
            return -1;
        } finally {
            db.endTransaction();
        }
    }

    private long insertLocked(SQLiteDatabase db, Txn t) {
        applyLearnedRule(db, t);

        Cursor c = db.rawQuery(
                "SELECT id, merchant, account, confidence FROM txns WHERE deleted=0 AND ABS(amount - ?) < 0.005 AND ts BETWEEN ? AND ? ORDER BY confidence DESC LIMIT 1",
                new String[]{String.valueOf(t.amount), String.valueOf(t.timeMillis - DEDUP_WINDOW_MS), String.valueOf(t.timeMillis + DEDUP_WINDOW_MS)});
        long existId = -1;
        String existMerchant = null, existAccount = null;
        int existConf = 0;
        if (c.moveToFirst()) {
            existId = c.getLong(0);
            existMerchant = c.getString(1);
            existAccount = c.getString(2);
            existConf = c.getInt(3);
        }
        c.close();

        if (existId > 0) {
            boolean dirty = false;
            ContentValues v = new ContentValues();
            if ((existMerchant == null || existMerchant.isEmpty()) && t.merchant != null && !t.merchant.isEmpty()) {
                v.put("merchant", t.merchant);
                dirty = true;
            }
            if ((existAccount == null || existAccount.isEmpty()) && t.account != null && !t.account.isEmpty()) {
                v.put("account", t.account);
                dirty = true;
            }
            if (t.confidence > existConf) {
                v.put("confidence", t.confidence);
                v.put("source", t.source);
                dirty = true;
            }
            if (dirty) db.update("txns", v, "id=?", new String[]{String.valueOf(existId)});
            return -1;
        }

        ContentValues v = new ContentValues();
        v.put("ts", t.timeMillis);
        v.put("amount", t.amount);
        v.put("merchant", t.merchant);
        v.put("category", t.category);
        v.put("sub", t.sub);
        v.put("app", t.app);
        v.put("source", t.source);
        v.put("account", t.account);
        v.put("raw", t.raw);
        v.put("confidence", t.confidence);
        v.put("confirmed", t.confirmed);
        return db.insert("txns", null, v);
    }

    private void applyLearnedRule(SQLiteDatabase db, Txn t) {
        if (t.merchant == null || t.merchant.isEmpty()) return;
        Cursor c = db.rawQuery("SELECT category, sub, app FROM rules WHERE merchant=?", new String[]{t.merchant});
        if (c.moveToFirst()) {
            t.category = c.getString(0);
            t.sub = c.getString(1);
            String app = c.getString(2);
            if (app != null && !app.isEmpty()) t.app = app;
            t.confirmed = 1;
        }
        c.close();
    }

    public List<Txn> list(int limit) {
        List<Txn> out = new ArrayList<Txn>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, ts, amount, merchant, category, sub, app, source, account, raw, confidence, confirmed FROM txns WHERE deleted=0 ORDER BY ts DESC LIMIT ?",
                new String[]{String.valueOf(limit)});
        while (c.moveToNext()) out.add(read(c));
        c.close();
        return out;
    }

    public List<Txn> pending() {
        List<Txn> out = new ArrayList<Txn>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, ts, amount, merchant, category, sub, app, source, account, raw, confidence, confirmed FROM txns WHERE deleted=0 AND confirmed=0 ORDER BY ts DESC LIMIT 200", null);
        while (c.moveToNext()) out.add(read(c));
        c.close();
        return out;
    }

    private Txn read(Cursor c) {
        Txn t = new Txn();
        t.id = c.getLong(0);
        t.timeMillis = c.getLong(1);
        t.amount = c.getDouble(2);
        t.merchant = c.getString(3) == null ? "" : c.getString(3);
        t.category = c.getString(4) == null ? "qita" : c.getString(4);
        t.sub = c.getString(5) == null ? "" : c.getString(5);
        t.app = c.getString(6) == null ? "" : c.getString(6);
        t.source = c.getString(7) == null ? "" : c.getString(7);
        t.account = c.getString(8) == null ? "" : c.getString(8);
        t.raw = c.getString(9) == null ? "" : c.getString(9);
        t.confidence = c.getInt(10);
        t.confirmed = c.getInt(11);
        return t;
    }

    /** {支出, 收入, 笔数}，区间 [from, to)。 */
    public double[] sum(long from, long to) {
        double[] r = new double[3];
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT amount FROM txns WHERE deleted=0 AND ts >= ? AND ts < ?", new String[]{String.valueOf(from), String.valueOf(to)});
        while (c.moveToNext()) {
            double a = c.getDouble(0);
            if (a < 0) r[0] += -a; else r[1] += a;
            r[2] += 1;
        }
        c.close();
        return r;
    }

    public long monthStart(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    public long dayStart(long now, int dayOffset) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        cal.add(Calendar.DAY_OF_YEAR, dayOffset);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    /** 最近 n 天的每日支出，index 0 是 n-1 天前。单条 GROUP BY，不再一天查一次。 */
    public double[] dailyExpense(int n) {
        double[] out = new double[n];
        long from = dayStart(System.currentTimeMillis(), -(n - 1));
        long to = from + n * 86400000L;
        // from/to 是 long 原始类型，拼接不构成注入面
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT (ts - " + from + ")/86400000 AS d, SUM(-amount) FROM txns " +
                "WHERE deleted=0 AND amount < 0 AND ts >= " + from + " AND ts < " + to + " GROUP BY d", null);
        while (c.moveToNext()) {
            int idx = c.getInt(0);
            if (idx >= 0 && idx < n) out[idx] = c.getDouble(1);
        }
        c.close();
        return out;
    }

    /** 分类占比：Map<category, 支出合计>。 */
    public Map<String, Double> byCategory(long from, long to) {
        Map<String, Double> m = new HashMap<String, Double>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT category, SUM(-amount) FROM txns WHERE deleted=0 AND amount < 0 AND ts >= ? AND ts < ? GROUP BY category ORDER BY 2 DESC",
                new String[]{String.valueOf(from), String.valueOf(to)});
        while (c.moveToNext()) m.put(c.getString(0), c.getDouble(1));
        c.close();
        return m;
    }

    public int pendingCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM txns WHERE deleted=0 AND confirmed=0", null);
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    public int count() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM txns WHERE deleted=0", null);
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    /** 用户改了分类，记住这个商户，下次自动用新分类。 */
    public void updateCategory(long id, String cat, String sub, boolean remember) {
        SQLiteDatabase db = getWritableDatabase();
        String merchant = "";
        Cursor c = db.rawQuery("SELECT merchant FROM txns WHERE id=? AND deleted=0", new String[]{String.valueOf(id)});
        if (c.moveToFirst()) merchant = c.getString(0) == null ? "" : c.getString(0);
        c.close();

        ContentValues v = new ContentValues();
        v.put("category", cat);
        v.put("sub", sub);
        v.put("confirmed", 1);
        db.update("txns", v, "id=?", new String[]{String.valueOf(id)});

        if (remember && !merchant.isEmpty()) {
            ContentValues r = new ContentValues();
            r.put("merchant", merchant);
            r.put("category", cat);
            r.put("sub", sub);
            db.insertWithOnConflict("rules", null, r, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    /** 软删除：界面一键误触还能撤销，数据不出库。 */
    public void delete(long id) {
        ContentValues v = new ContentValues();
        v.put("deleted", 1);
        getWritableDatabase().update("txns", v, "id=?", new String[]{String.valueOf(id)});
    }

    /** 撤销删除：用详情弹层里的完整字段回插，保留原时间。 */
    public long restore(String json) {
        if (json == null || json.isEmpty()) return -1;
        try {
            JSONObject o = new JSONObject(json);
            Txn t = new Txn();
            t.timeMillis = o.optLong("ts", System.currentTimeMillis());
            t.amount = o.optDouble("amt", 0);
            t.merchant = o.optString("m", "");
            t.category = o.optString("cat", "qita");
            t.sub = o.optString("sub", "");
            t.app = o.optString("app", "");
            t.source = o.optString("src", "");
            t.account = o.optString("acc", "");
            t.raw = o.optString("raw", "");
            t.confidence = (int) o.optDouble("conf", 80);
            t.confirmed = 1;
            return insert(t);
        } catch (Throwable e) {
            Log.w(TAG, "restore: " + e.getMessage());
            return -1;
        }
    }

    public String exportCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("时间,金额,商户,分类,子类,来源,账户\n");
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT ts, amount, merchant, category, sub, source, account FROM txns WHERE deleted=0 ORDER BY ts DESC", null);
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA);
        while (c.moveToNext()) {
            sb.append(csv(f.format(new java.util.Date(c.getLong(0))))).append(',')
              .append(csvNum(c.getDouble(1))).append(',')
              .append(csv(c.getString(2))).append(',')
              .append(csv(Rules.catName(c.getString(3)))).append(',')
              .append(csv(c.getString(4))).append(',')
              .append(csv(c.getString(5))).append(',')
              .append(csv(c.getString(6))).append('\n');
        }
        c.close();
        return sb.toString();
    }

    /**
     * 文本字段：统一加引号、内部引号翻倍；以 = + - @ Tab 开头的值前置单引号，
     * 防 Excel 公式注入——商户名可能来自短信正文的正则抽取，属于外部可控输入。
     * 包内可见只为让单元测试能直接断言转义行为。
     */
    static String csv(String s) {
        if (s == null) s = "";
        if (!s.isEmpty()) {
            char c0 = s.charAt(0);
            if (c0 == '=' || c0 == '+' || c0 == '-' || c0 == '@' || c0 == '\t' || c0 == '\'') s = "'" + s;
        }
        return '"' + s.replace("\"", "\"\"").replace("\r\n", " ").replace("\n", " ").replace("\r", " ") + '"';
    }

    /**
     * 金额列由我们自己 %.2f 格式化，不存在注入面，绝不能加公式前缀——
     * 否则所有负数支出会变成 Excel 文本，导出后没法求和。
     */
    static String csvNum(double v) {
        return '"' + String.format(java.util.Locale.CHINA, "%.2f", v) + '"';
    }
}
