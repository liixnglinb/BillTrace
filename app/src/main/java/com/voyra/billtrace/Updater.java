package com.voyra.billtrace;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 软件更新：检查版本 → 下载安装包 → 交给系统的安装界面。
 *
 * 与桌面端那几款的差别：Android 不允许应用自己静默装自己，安装必须由用户在系统
 * 安装界面点「下一步」完成 —— 所以这里只负责把 APK 下下来并 startActivity 打开它，
 * 剩下的交给系统安装器。这正好是「下载完成后弹出安装界面由用户点下一步」的形态。
 *
 * 网络只有这一处：检查更新会 GET 一次站点上的版本代理（不上传任何数据）。
 * 下载走系统 DownloadManager，它在通知栏自带进度，不占用本应用的内存与线程。
 *
 * 线程：@JavascriptInterface 方法跑在 WebView 的 JavaBridge 线程上，不是 UI 线程，
 * 所以这里的同步网络请求不会触发 NetworkOnMainThreadException，也不会卡住界面。
 */
class Updater {

    private static final String TAG = "BillTrace";

    /** 站点上的版本代理（Cloudflare，国内可达性比 api.github.com 稳）。 */
    private static final String CHECK_URL = "https://lxlrwxs.top/bt-api/latest";

    /** 应用内检查更新走站点代理，拿不到时退回 GitHub 固定 tag 直链。 */
    private static final String FALLBACK_URL =
            "https://github.com/liixnglinb/BillTrace/releases/download/latest/BillTrace.apk";

    private static long downloadId = -1L;
    private static String pendingVersion = "";

    private static String err(Throwable e) {
        return err(e == null ? "未知错误" : (e.getClass().getSimpleName() + ": " + e.getMessage()));
    }

    private static String err(String msg) {
        try {
            return new JSONObject().put("ok", false).put("error", msg == null ? "未知错误" : msg).toString();
        } catch (Throwable ignored) {
            return "{\"ok\":false,\"error\":\"unknown\"}";
        }
    }

    /** 检查更新。返回 {ok, current, latest, hasUpdate, size, url, notes} 或 {ok:false, error}。 */
    static String check(MainActivity act) {
        try {
            String body = httpGet(CHECK_URL);
            JSONObject r = new JSONObject(body);
            String latest = r.optString("version", "").trim();
            if (latest.isEmpty()) return err("版本信息不完整，请稍后重试");

            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("current", act.versionName());
            o.put("latest", latest);
            o.put("size", r.optLong("size", 0L));
            String url = r.optString("url", "").trim();
            o.put("url", url.isEmpty() ? FALLBACK_URL : url);
            o.put("notes", r.optString("notes", ""));
            return o.toString();
        } catch (Throwable e) {
            Log.e(TAG, "update check failed", e);
            return err("检查更新失败，请检查网络后重试");
        }
    }

    /** 用系统 DownloadManager 下载安装包（通知栏自带进度，断网可续）。 */
    static String download(MainActivity act, String url, String version) {
        try {
            String target = (url == null || url.trim().isEmpty()) ? FALLBACK_URL : url.trim();
            String ver = (version == null || version.trim().isEmpty()) ? "latest" : version.trim();

            DownloadManager dm = (DownloadManager) act.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return err("系统下载服务不可用");

            // 上一次的残留先撤掉：同一个 id 只能对应一次下载
            if (downloadId != -1L) {
                try { dm.remove(downloadId); } catch (Throwable ignored) { }
                downloadId = -1L;
            }

            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(target));
            req.setTitle("账迹 " + ver + " 更新包");
            req.setDescription("下载完成后会打开安装界面");
            req.setMimeType("application/vnd.android.package-archive");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
            // 落在应用自己的外部目录：不需要存储权限，卸载时随应用一起清掉
            req.setDestinationInExternalFilesDir(act, Environment.DIRECTORY_DOWNLOADS,
                    "BillTrace-" + ver + ".apk");

            downloadId = dm.enqueue(req);
            pendingVersion = ver;

            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("version", ver);
            return o.toString();
        } catch (Throwable e) {
            Log.e(TAG, "update download failed", e);
            return err("开始下载失败：" + e.getClass().getSimpleName());
        }
    }

    /**
     * 查询下载进度。返回 {state, bytes, total}。
     * state: idle（没在下载）/ running / paused / done / failed。
     * total 在拿到 Content-Length 之前是 -1，前端据此显示"已下载 x MB"而不是卡在 0%。
     */
    static String progress(MainActivity act) {
        try {
            JSONObject o = new JSONObject();
            if (downloadId == -1L) {
                o.put("state", "idle");
                return o.toString();
            }
            DownloadManager dm = (DownloadManager) act.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                o.put("state", "idle");
                return o.toString();
            }
            Cursor c = dm.query(new DownloadManager.Query().setFilterById(downloadId));
            if (c == null) {
                o.put("state", "idle");
                return o.toString();
            }
            try {
                if (!c.moveToFirst()) {
                    o.put("state", "idle");
                    return o.toString();
                }
                int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                long done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                o.put("bytes", done);
                o.put("total", total);
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    o.put("state", "done");
                } else if (status == DownloadManager.STATUS_FAILED) {
                    o.put("state", "failed");
                    o.put("reason", c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)));
                } else if (status == DownloadManager.STATUS_PAUSED) {
                    o.put("state", "paused");
                } else {
                    o.put("state", "running");
                }
                return o.toString();
            } finally {
                c.close();
            }
        } catch (Throwable e) {
            Log.e(TAG, "update progress failed", e);
            return err(e);
        }
    }

    /**
     * 打开系统安装界面。Android 8 起要先拿到「安装未知应用」授权，
     * 没授权就直接把用户送到那个设置页 —— 否则 startActivity 只会抛异常，
     * 用户看到的是"点了没反应"。
     */
    static String install(MainActivity act) {
        try {
            if (downloadId == -1L) return err("还没有下载完成的安装包");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && !act.getPackageManager().canRequestPackageInstalls()) {
                Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + act.getPackageName()));
                s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(s);
                JSONObject o = new JSONObject();
                o.put("ok", false);
                o.put("needPermission", true);
                o.put("error", "请先允许「账迹」安装未知来源的应用，回来后点一次「安装」");
                return o.toString();
            }

            DownloadManager dm = (DownloadManager) act.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return err("系统下载服务不可用");
            Uri uri = dm.getUriForDownloadedFile(downloadId);
            if (uri == null) return err("安装包不可用，请重新下载");

            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);

            JSONObject o = new JSONObject();
            o.put("ok", true);
            return o.toString();
        } catch (Throwable e) {
            Log.e(TAG, "update install failed", e);
            return err("打开安装程序失败：" + e.getClass().getSimpleName());
        }
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "BillTrace-Updater");
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            InputStream in = conn.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
