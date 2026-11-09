package com.muse.xdownloader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * 下载服务（前台服务）：真正的下载在这里跑，不在界面里。
 * 这样用户切到别的软件、锁屏，下载都不会被打断；通知栏里能看到进度，
 * 主界面通过本地广播同步显示进度条和每行按钮状态。
 *
 * 队列语义：下载进行中再点新的下载，新任务排到队列后面依次执行，
 * 不并发、不打断正在下的。全部排完后服务自己停掉，不长驻。
 */
public class DownloadService extends Service {

    /** Intent extra：一段 JSON，内容是 {推文信息, 要下的序号列表, cookie} */
    public static final String EXTRA_JOB = "job_json";
    /** 进度广播的 action（只发给本应用自己） */
    public static final String ACTION_EVENT = "com.muse.xdownloader.DOWNLOAD_EVENT";

    private static final String CHANNEL_ID = "download_progress";
    private static final int NOTIF_PROGRESS = 1001;
    private static final int NOTIF_DONE = 1002;

    /** 有没有任务正在下或排队（主界面用来决定提示文字的说法） */
    public static volatile boolean activeOrQueued = false;

    private static class Job {
        TweetInfo info;
        List<Integer> indices = new ArrayList<>();
        String cookie = "";
    }

    private final Queue<Job> queue = new ArrayDeque<>();
    private Thread worker = null;
    private NotificationManager nm;
    private boolean foregroundStarted = false;

    // 当前文件的进度节流（字节回调会非常频繁，通知和广播都限一下频率）
    private int lastPct = -1;
    private long lastProgressAt = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "下载进度", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("下载图片和视频时显示进度");
        nm.createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // startForegroundService 启动后必须尽快挂上前台通知，先挂再干别的
        if (!foregroundStarted) {
            startForeground(NOTIF_PROGRESS, buildProgressNotification("准备下载…", -1));
            foregroundStarted = true;
        }
        Job job = null;
        if (intent != null) job = parseJob(intent.getStringExtra(EXTRA_JOB));
        if (job != null && !job.indices.isEmpty()) {
            synchronized (queue) { queue.add(job); }
            activeOrQueued = true;
            ensureWorker();
        } else if (!isWorking()) {
            // 没带有效任务、也没有在下的东西，别让空服务挂着
            stopForeground(true);
            foregroundStarted = false;
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        activeOrQueued = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------- 队列 ----------

    private boolean isWorking() {
        synchronized (queue) { return worker != null && worker.isAlive(); }
    }

    private void ensureWorker() {
        synchronized (queue) {
            if (worker != null && worker.isAlive()) return;
            worker = new Thread(this::runQueue, "download-worker");
            worker.start();
        }
    }

    private void runQueue() {
        while (true) {
            Job job;
            synchronized (queue) { job = queue.poll(); }
            if (job == null) break;
            runJob(job);
        }
        activeOrQueued = false;
        // 队列下完就撤：前台通知撤掉（完成通知是另一个，会留着让用户点开看），服务停掉
        stopForeground(true);
        foregroundStarted = false;
        stopSelf();
    }

    private void runJob(Job job) {
        int total = job.indices.size();
        int ok = 0, fail = 0;
        String where = "";
        for (int pos = 0; pos < total; pos++) {
            int mediaIndex = job.indices.get(pos);
            String fileName = "";
            try { fileName = Downloader.fileNameOf(job.info, mediaIndex); } catch (Exception ignored) {}
            lastPct = -1;
            lastProgressAt = 0;
            broadcastItem(job, "item_start", mediaIndex, pos + 1, total, -1, fileName);
            updateProgressNotification(
                    "正在下载 第" + (pos + 1) + "个/共" + total + "个 · " + fileName, -1, true);
            final int fPos = pos, fMediaIndex = mediaIndex;
            final String fFileName = fileName;
            try {
                String w = Downloader.downloadSingle(this, job.info, mediaIndex, job.cookie,
                        (done, all) -> {
                            int pct = all > 0 ? (int) (done * 100 / all) : -1;
                            onFileBytes(job, fMediaIndex, fPos + 1, total, pct, fFileName);
                        });
                if (w != null && !w.isEmpty()) where = w;
                ok++;
                broadcastItem(job, "item_done", mediaIndex, pos + 1, total, 100, fileName, true);
            } catch (Exception e) {
                // 单个失败不中断整个队列，跳过继续下一个，最后在完成通知里汇总
                fail++;
                broadcastItem(job, "item_done", mediaIndex, pos + 1, total, -1, fileName, false);
            }
        }
        if (where.isEmpty()) {
            where = "Download/推特下载/" + Downloader.folderOf(job.info) + "/";
        }
        broadcastJobDone(job, ok, fail, where);
        postDoneNotification(ok, fail);
    }

    /** 字节进度回调（下载线程上）：百分比变了且离上次超过约 300 毫秒才刷新通知和广播 */
    private void onFileBytes(Job job, int mediaIndex, int current, int total, int pct, String fileName) {
        long now = System.currentTimeMillis();
        if (pct == lastPct && now - lastProgressAt < 300) return;
        if (now - lastProgressAt < 300 && pct >= 0 && pct < 100) return;
        lastPct = pct;
        lastProgressAt = now;
        updateProgressNotification(
                "正在下载 第" + current + "个/共" + total + "个 · " + fileName, pct, false);
        broadcastItem(job, "progress", mediaIndex, current, total, pct, fileName);
    }

    // ---------- 广播（给主界面同步进度条和行按钮状态） ----------

    private void broadcastItem(Job job, String kind, int mediaIndex, int current, int total,
                               int percent, String fileName) {
        broadcastItem(job, kind, mediaIndex, current, total, percent, fileName, null);
    }

    private void broadcastItem(Job job, String kind, int mediaIndex, int current, int total,
                               int percent, String fileName, Boolean success) {
        Intent i = new Intent(ACTION_EVENT);
        i.setPackage(getPackageName());
        i.putExtra("kind", kind);
        i.putExtra("tweetId", job.info.tweetId);
        i.putExtra("mediaIndex", mediaIndex);
        i.putExtra("current", current);
        i.putExtra("total", total);
        i.putExtra("percent", percent);
        i.putExtra("fileName", fileName);
        if (success != null) i.putExtra("success", success);
        sendBroadcast(i);
    }

    private void broadcastJobDone(Job job, int ok, int fail, String where) {
        Intent i = new Intent(ACTION_EVENT);
        i.setPackage(getPackageName());
        i.putExtra("kind", "job_done");
        i.putExtra("tweetId", job.info.tweetId);
        i.putExtra("okCount", ok);
        i.putExtra("failCount", fail);
        i.putExtra("where", where);
        sendBroadcast(i);
    }

    // ---------- 通知 ----------

    private PendingIntent openAppIntent() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildProgressNotification(String text, int pct) {
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("推特下载器")
                .setContentText(text)
                .setContentIntent(openAppIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (pct >= 0) b.setProgress(100, pct, false);
        else b.setProgress(0, 0, true); // 不知道总大小就转圈，别假装有百分比
        return b.build();
    }

    private void updateProgressNotification(String text, int pct, boolean force) {
        // 通知刷新太频繁会被系统限流，这里和广播共用节流（force 时直接发）
        try {
            nm.notify(NOTIF_PROGRESS, buildProgressNotification(text, pct));
        } catch (Exception ignored) {}
    }

    private void postDoneNotification(int ok, int fail) {
        String text;
        if (fail == 0) text = "下载完成 · 共" + ok + "个文件";
        else text = ok + "个成功，" + fail + "个失败";
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("推特下载器")
                .setContentText(text)
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build();
        try { nm.notify(NOTIF_DONE, n); } catch (Exception ignored) {}
    }

    // ---------- 任务 JSON 解析 ----------

    private Job parseJob(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            TweetInfo info = new TweetInfo();
            info.tweetId = o.optString("tweetId", "");
            info.screenName = o.optString("screenName", "");
            info.authorName = o.optString("authorName", "");
            info.text = o.optString("text", "");
            info.dateStr = o.optString("dateStr", "");
            JSONArray media = o.optJSONArray("media");
            if (media != null) {
                for (int i = 0; i < media.length(); i++) {
                    JSONObject mj = media.optJSONObject(i);
                    if (mj == null) continue;
                    String url = mj.optString("url", "");
                    if (url.isEmpty()) continue;
                    TweetInfo.Media m = new TweetInfo.Media(url, mj.optString("type", "photo"));
                    m.thumbUrl = mj.optString("thumbUrl", "");
                    m.detail = mj.optString("detail", "");
                    info.media.add(m);
                }
            }
            Job job = new Job();
            job.info = info;
            job.cookie = o.optString("cookie", "");
            JSONArray idx = o.optJSONArray("indices");
            if (idx != null) {
                for (int i = 0; i < idx.length(); i++) {
                    int v = idx.optInt(i, -1);
                    if (v >= 0 && v < info.media.size()) job.indices.add(v);
                }
            }
            return job;
        } catch (Exception e) {
            return null;
        }
    }
}
