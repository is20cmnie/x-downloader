package com.muse.xdownloader;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/** 把解析到的媒体逐个下到 Download/推特下载/<作者账号>/ 里 */
public class Downloader {

    public interface Progress {
        void onProgress(String text);
    }

    /** 字节级进度回调：done=已下字节数，total=总字节数（拿不到时为 -1） */
    public interface ByteProgress {
        void onBytes(long done, long total);
    }

    /** 返回保存位置说明。失败会抛异常并带上第几个文件。 */
    public static String downloadAll(Context ctx, TweetInfo info, Progress progress) throws Exception {
        int total = info.media.size();
        String where = "";
        for (int i = 0; i < total; i++) {
            progress.onProgress("正在下载第 " + (i + 1) + " 个 / 共 " + total + " 个…");
            where = downloadSingle(ctx, info, i);
        }
        return where;
    }

    /**
     * 只下第 index 个媒体（序号按列表位置，和全部下载完全一致的命名与保存位置）。
     * 返回保存位置说明，失败抛异常。
     */
    public static String downloadSingle(Context ctx, TweetInfo info, int index) throws Exception {
        return downloadSingle(ctx, info, index, null, null);
    }

    /**
     * 同上，多带两个参数：
     * cookie —— 调用方（如下载服务）提前取好的登录 Cookie 字符串，为空时自己从 CookieManager 取；
     * bp —— 字节级进度回调，可以为 null。
     */
    public static String downloadSingle(Context ctx, TweetInfo info, int index,
                                        String cookie, ByteProgress bp) throws Exception {
        String folder = folderOf(info);
        TweetInfo.Media m = info.media.get(index);
        String ext = guessExt(m);
        // 文件名习惯和 dy下载器一样：日期-作者账号-文案前约20字-序号；文案为空时用推文 ID 代替
        String baseName = info.dateStr + "-" + folder + "-" + titlePartOf(info) + "-" + (index + 1);
        downloadOne(ctx, m, folder, baseName, ext, cookie, bp);
        return "Download/推特下载/" + folder + "/";
    }

    /** 保存目录里的作者文件夹名（已做非法字符处理） */
    public static String folderOf(TweetInfo info) {
        return sanitize(info.screenName);
    }

    /** 第 index 个媒体最终保存的文件名（不含同名冲突时追加的后缀），给通知栏显示用 */
    public static String fileNameOf(TweetInfo info, int index) {
        TweetInfo.Media m = info.media.get(index);
        return info.dateStr + "-" + folderOf(info) + "-" + titlePartOf(info)
                + "-" + (index + 1) + "." + guessExt(m);
    }

    private static String titlePartOf(TweetInfo info) {
        String t = info.text == null ? "" : info.text.trim();
        if (t.isEmpty()) return info.tweetId == null || info.tweetId.isEmpty() ? "无标题" : info.tweetId;
        // 去掉链接和换行，取前约 20 个字，非法字符替换掉
        t = t.replaceAll("https?://\\S+", "").replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return info.tweetId;
        if (t.length() > 20) t = t.substring(0, 20);
        t = sanitize(t).trim();
        return t.isEmpty() ? info.tweetId : t;
    }

    private static void downloadOne(Context ctx, TweetInfo.Media m, String folder,
                                    String baseName, String ext,
                                    String cookie, ByteProgress bp) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(m.url).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", TweetParser.UA);
            // 下载请求带上登录 Cookie（有些图/视频地址没登录会下不到）
            String ck = cookie;
            if (ck == null || ck.isEmpty()) {
                try {
                    ck = android.webkit.CookieManager.getInstance().getCookie("https://x.com");
                } catch (Exception ignored) {}
            }
            if (ck != null && !ck.isEmpty()) conn.setRequestProperty("Cookie", ck);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("下载被拒绝（" + code + "）");
            long totalBytes = conn.getContentLengthLong(); // 拿不到时为 -1
            if (bp != null) bp.onBytes(0, totalBytes);
            InputStream in = conn.getInputStream();
            if (Build.VERSION.SDK_INT >= 29) {
                saveViaMediaStore(ctx, in, folder, baseName, ext, m.isVideo(), totalBytes, bp);
            } else {
                saveViaFile(in, folder, baseName, ext, totalBytes, bp);
            }
            in.close();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // 安卓 10 及以上：走 MediaStore，不需要存储权限
    private static void saveViaMediaStore(Context ctx, InputStream in, String folder,
                                          String baseName, String ext, boolean isVideo,
                                          long totalBytes, ByteProgress bp) throws Exception {
        ContentResolver resolver = ctx.getContentResolver();
        String mime = mimeOf(ext, isVideo);
        String relPath = Environment.DIRECTORY_DOWNLOADS + "/推特下载/" + folder;
        // 同名文件自动加后缀，避免冲突/报错
        for (int attempt = 0; attempt < 50; attempt++) {
            String name = baseName + (attempt == 0 ? "" : "_" + (attempt + 1)) + "." + ext;
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
            cv.put(MediaStore.Downloads.MIME_TYPE, mime);
            cv.put(MediaStore.Downloads.RELATIVE_PATH, relPath);
            cv.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) continue; // 多半是重名，换个名字再试
            try {
                OutputStream out = resolver.openOutputStream(uri);
                if (out == null) throw new Exception("打不开保存位置");
                copy(in, out, totalBytes, bp);
                out.close();
                ContentValues done = new ContentValues();
                done.put(MediaStore.Downloads.IS_PENDING, 0);
                resolver.update(uri, done, null, null);
                return;
            } catch (Exception e) {
                try { resolver.delete(uri, null, null); } catch (Exception ignored) {}
                throw e;
            }
        }
        throw new Exception("保存失败：同名文件太多");
    }

    // 安卓 9：传统外部存储目录（需要 WRITE_EXTERNAL_STORAGE 权限，主界面会先申请）
    private static void saveViaFile(InputStream in, String folder, String baseName, String ext,
                                    long totalBytes, ByteProgress bp) throws Exception {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "推特下载/" + folder);
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("建不了文件夹");
        File f = new File(dir, baseName + "." + ext);
        int attempt = 1;
        while (f.exists()) {
            attempt++;
            f = new File(dir, baseName + "_" + attempt + "." + ext);
        }
        FileOutputStream out = new FileOutputStream(f);
        copy(in, out, totalBytes, bp);
        out.close();
    }

    private static void copy(InputStream in, OutputStream out, long totalBytes, ByteProgress bp) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n;
        long done = 0;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            done += n;
            if (bp != null) bp.onBytes(done, totalBytes);
        }
        out.flush();
    }

    static String guessExt(TweetInfo.Media m) {
        String u = m.url;
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        int dot = u.lastIndexOf('.');
        int slash = u.lastIndexOf('/');
        if (dot > slash) {
            String ext = u.substring(dot + 1).toLowerCase(Locale.US);
            if (ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png")
                    || ext.equals("webp") || ext.equals("mp4")) {
                return ext.equals("jpeg") ? "jpg" : ext;
            }
        }
        // pbs 图片地址的格式在 format 参数里
        if (m.url.contains("format=png")) return "png";
        return m.isVideo() ? "mp4" : "jpg";
    }

    private static String mimeOf(String ext, boolean isVideo) {
        if (isVideo) return "video/mp4";
        if ("png".equals(ext)) return "image/png";
        if ("webp".equals(ext)) return "image/webp";
        return "image/jpeg";
    }

    private static String sanitize(String s) {
        if (s == null || s.isEmpty()) return "unknown";
        return s.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
    }
}
