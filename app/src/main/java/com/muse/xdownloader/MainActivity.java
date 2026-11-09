package com.muse.xdownloader;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    // 兼容 x.com / twitter.com / mobile.twitter.com，分享文本里混着别的话也能提出来
    private static final Pattern LINK_PATTERN = Pattern.compile(
            "(?:https?://)?(?:www\\.|mobile\\.)?(?:x\\.com|twitter\\.com)/([A-Za-z0-9_]+)/status(?:es)?/(\\d+)");
    private static final Pattern ID_ONLY_PATTERN = Pattern.compile("status(?:es)?/(\\d+)");

    private TextView txtLoginStatus, txtPreview, txtProgress;
    private View layoutLoginPrompt;
    private EditText editLink;
    private Button btnDownload, btnParse;
    private WebView webParse;
    private TweetInfo currentInfo;
    private ProgressBar progressBar;

    // 预览列表（v1.0.2）：逐个媒体一行，带预览图和单独下载按钮
    private LinearLayout mediaList;
    private final List<Button> rowBtns = new ArrayList<>();
    private final Set<Integer> rowDoneIdx = new HashSet<>();
    private final Set<Integer> rowBusyIdx = new HashSet<>();
    private volatile boolean allDownloading = false;
    private final ExecutorService thumbPool = Executors.newFixedThreadPool(3);
    private static final Map<String, Bitmap> THUMB_CACHE =
            Collections.synchronizedMap(new HashMap<>());

    // 解析过程中的状态（WebView 拦截线程和轮询线程共用）
    private volatile boolean pageFinished = false;
    private volatile TweetInfo gqlInfo = null;
    private volatile boolean parseRunning = false;
    private volatile String currentTweetId = "";
    private volatile String currentHintUser = null;

    // 页面里收集媒体的 JS：找 video/img，给出包含 video.twimg / pbs 地址的 HTML 片段（回 Java 再用正则精提），
    // 另外带回正文区文字和“有没有视频、是不是只有 blob 流地址”的标记
    private static final String COLLECT_JS =
            "(function(){"
            + "var videos=[],imgs=[],posters=[];"
            + "var vids=document.querySelectorAll('video');"
            + "var hasVideo=vids.length>0, sawBlob=false, sawReal=false;"
            + "for(var i=0;i<vids.length;i++){var v=vids[i];"
            + " var srcs=[];"
            + " var src=v.currentSrc||v.src||'';"
            + " var ss=v.querySelectorAll('source');"
            + " for(var j=0;j<ss.length;j++){ if(ss[j].src) srcs.push(ss[j].src); }"
            + " if(src) srcs.push(src);"
            + " for(var j2=0;j2<srcs.length;j2++){ videos.push(srcs[j2]); if(v.poster) posters.push([srcs[j2], v.poster]); }"
            + "}"
            + "for(var k=0;k<videos.length;k++){ if(videos[k].indexOf('blob:')===0) sawBlob=true; else sawReal=true; }"
            + "var im=document.querySelectorAll('img');"
            + "for(var m=0;m<im.length;m++){ var s=im[m].src||''; if(s.indexOf('pbs.twimg.com/media')>=0) imgs.push(s); }"
            + "var html=''; try{ html=document.documentElement.innerHTML||''; }catch(e){}"
            + "var snips=[], idx=0, cnt=0;"
            + "while((idx=html.indexOf('video.twimg.com',idx))>=0 && cnt<40){ var a=Math.max(0,idx-500); snips.push(html.substring(a,idx+500)); idx+=15; cnt++; }"
            + "idx=0; cnt=0;"
            + "while((idx=html.indexOf('pbs.twimg.com/media',idx))>=0 && cnt<40){ var b=Math.max(0,idx-250); snips.push(html.substring(b,idx+250)); idx+=20; cnt++; }"
            + "var art=''; var el=document.querySelector('article'); if(el){ art=(el.innerText||'').substring(0,1200); }"
            + "var o={videos:videos,imgs:imgs,posters:posters,snippets:snips.join('\\n'),article:art,hasVideo:hasVideo,sawBlob:sawBlob,sawReal:sawReal};"
            + "return JSON.stringify(o);"
            + "})()";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        txtLoginStatus = findViewById(R.id.txtLoginStatus);
        layoutLoginPrompt = findViewById(R.id.layoutLoginPrompt);
        txtPreview = findViewById(R.id.txtPreview);
        txtProgress = findViewById(R.id.txtProgress);
        progressBar = findViewById(R.id.progressBar);
        editLink = findViewById(R.id.editLink);
        btnDownload = findViewById(R.id.btnDownload);
        btnParse = findViewById(R.id.btnParse);
        webParse = findViewById(R.id.webParse);
        mediaList = findViewById(R.id.mediaList);

        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnLoginMain).setOnClickListener(v ->
                startActivity(new Intent(this, LoginActivity.class)));
        findViewById(R.id.btnPaste).setOnClickListener(v -> pasteFromClipboard());
        btnParse.setOnClickListener(v -> startParse());
        btnDownload.setOnClickListener(v -> startDownload());

        setupParseWebView();
        handleIncomingIntent(getIntent());
        refreshLoginStatus();
        askStartupPermissions();
    }

    /**
     * v1.0.4：权限改成刚进软件就申请，不再等到第一次下载才问。
     * - 安卓 13+ 没给通知权限的，申请 POST_NOTIFICATIONS（给了通知栏才能显示下载进度）
     * - 安卓 9 没给存储权限的，申请 WRITE_EXTERNAL_STORAGE（它决定能不能存进 Download 文件夹）
     * 两个都缺就一次申请完。用户拒绝了也照常能用（没通知权限只是没通知进度，
     * 安卓 9 没存储权限时下载会失败并给中文提示，见 ensureStoragePermission），
     * 系统不允许再弹时（永久拒绝）requestPermissions 会直接回调拒绝，不会崩、也不会自制弹窗反复轰炸。
     */
    private void askStartupPermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (Build.VERSION.SDK_INT == 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (need.isEmpty()) return;
        try {
            requestPermissions(need.toArray(new String[0]), 102);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLoginStatus();
    }

    // 下载服务的进度广播：onStart 注册、onStop 注销，配对着来
    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(DownloadService.ACTION_EVENT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(dlReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(dlReceiver, filter);
        }
    }

    @Override
    protected void onStop() {
        try { unregisterReceiver(dlReceiver); } catch (Exception ignored) {}
        super.onStop();
    }

    /** 下载服务发来的进度：刷新进度条、状态文字和每行按钮状态（只认当前这条推文的） */
    private final BroadcastReceiver dlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (currentInfo == null) return;
            String tid = intent.getStringExtra("tweetId");
            if (tid == null || !tid.equals(currentInfo.tweetId)) return;
            String kind = intent.getStringExtra("kind");
            if (kind == null) return;
            int midx = intent.getIntExtra("mediaIndex", -1);
            if ("item_start".equals(kind) || "progress".equals(kind)) {
                int cur = intent.getIntExtra("current", 0);
                int tot = intent.getIntExtra("total", 0);
                int pct = intent.getIntExtra("percent", -1);
                String fn = intent.getStringExtra("fileName");
                progressBar.setVisibility(View.VISIBLE);
                if (pct >= 0) {
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(pct);
                } else {
                    progressBar.setIndeterminate(true); // 不知道总大小就转圈，别卡在 0
                }
                txtProgress.setText("正在下载第" + cur + "个/共" + tot + "个"
                        + (fn != null && !fn.isEmpty() ? " · " + fn : "")
                        + (pct >= 0 ? " · " + pct + "%" : "…"));
                if (midx >= 0 && !rowBusyIdx.contains(midx)) {
                    rowBusyIdx.add(midx);
                    updateRowButton(midx);
                }
            } else if ("item_done".equals(kind)) {
                boolean ok = intent.getBooleanExtra("success", false);
                if (midx >= 0) {
                    rowBusyIdx.remove(midx);
                    if (ok) rowDoneIdx.add(midx);
                    updateRowButton(midx);
                }
            } else if ("job_done".equals(kind)) {
                int okc = intent.getIntExtra("okCount", 0);
                int failc = intent.getIntExtra("failCount", 0);
                String where = intent.getStringExtra("where");
                progressBar.setIndeterminate(false);
                progressBar.setProgress(100); // 下完满格，完成文字保留着
                if (failc == 0) {
                    txtProgress.setText("全部下载完成，一共 " + okc
                            + " 个文件。\n保存位置：手机的 " + where + " 文件夹里");
                } else {
                    txtProgress.setText("下载结束：" + okc + " 个成功，" + failc
                            + " 个没下成功。\n保存位置：手机的 " + where
                            + " 文件夹里\n没成功的可以再点它那一行下载一次。");
                }
            }
        }
    };

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncomingIntent(intent);
    }

    // ---------- 登录状态（登录/退出都在设置页里，这里只显示一行小字） ----------

    /** 全应用唯一的登录状态判断口径（主界面和设置页共用）：凭证里 auth_token 存在且非空就是已登录 */
    static boolean isLoggedIn(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(LoginActivity.PREFS, MODE_PRIVATE);
        String a = sp.getString(LoginActivity.KEY_AUTH, "");
        return a != null && !a.isEmpty();
    }

    private void refreshLoginStatus() {
        boolean logged = isLoggedIn(this);
        txtLoginStatus.setText(logged ? "登录状态：已登录" : "登录状态：未登录");
        // v1.0.6：未登录时主页直接摆登录区，不用先去设置里找；登录好后登录区隐藏，主页只剩一行状态小字
        if (layoutLoginPrompt != null) {
            layoutLoginPrompt.setVisibility(logged ? View.GONE : View.VISIBLE);
        }
    }

    // ---------- 链接进来（分享 / 打开链接 / 粘贴） ----------

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String text = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())
                && "text/plain".equals(intent.getType())) {
            text = intent.getStringExtra(Intent.EXTRA_TEXT);
        } else if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri data = intent.getData();
            if (data != null) text = data.toString();
        }
        if (text != null && !text.isEmpty()) {
            editLink.setText(text);
            editLink.setSelection(editLink.getText().length());
        }
    }

    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip()) {
            ClipData cd = cm.getPrimaryClip();
            if (cd != null && cd.getItemCount() > 0) {
                CharSequence t = cd.getItemAt(0).getText();
                if (t != null) {
                    editLink.setText(t.toString());
                    editLink.setSelection(editLink.getText().length());
                    return;
                }
            }
        }
        Toast.makeText(this, "剪贴板里没有文字，先去复制一条推文链接", Toast.LENGTH_LONG).show();
    }

    /** 从任意文本里提取 {用户名, 推文ID}，提不到返回 null */
    static String[] extractLink(String text) {
        if (text == null) return null;
        Matcher m = LINK_PATTERN.matcher(text);
        if (m.find()) return new String[]{m.group(1), m.group(2)};
        Matcher m2 = ID_ONLY_PATTERN.matcher(text);
        if (m2.find()) return new String[]{null, m2.group(1)};
        return null;
    }

    // ---------- 解析用 WebView 的设置 ----------

    private void setupParseWebView() {
        WebSettings s = webParse.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webParse, true);

        webParse.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageFinished = true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    String u = request.getUrl().toString();
                    // 只拦页面自己发出的推文详情请求：URL 里的 queryId 是页面当次用的，不写死
                    if (u.contains("/i/api/graphql/")
                            && (u.contains("TweetDetail") || u.contains("TweetResultByRestId"))) {
                        final String urlCopy = u;
                        final Map<String, String> headersCopy = request.getRequestHeaders();
                        new Thread(() -> fetchInterceptedGraphql(urlCopy, headersCopy)).start();
                    }
                } catch (Exception ignored) {}
                return null; // 不挡页面自己的加载，只在旁边取一份来解析
            }
        });
    }

    /** 用页面请求原来的地址和请求头，自己再取一份响应体来解析媒体（在后台线程跑） */
    private void fetchInterceptedGraphql(String urlStr, Map<String, String> reqHeaders) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", TweetParser.UA);
            if (reqHeaders != null) {
                for (Map.Entry<String, String> e : reqHeaders.entrySet()) {
                    String k = e.getKey();
                    if (k == null) continue;
                    String lk = k.toLowerCase();
                    if (lk.equals("host") || lk.equals("content-length") || lk.equals("connection")) continue;
                    try { conn.setRequestProperty(k, e.getValue()); } catch (Exception ignored) {}
                }
            }
            String cookie = CookieManager.getInstance().getCookie("https://x.com");
            if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return;
            InputStream in = conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            TweetInfo info = TweetParser.parseGraphqlBody(sb.toString(), currentTweetId, currentHintUser);
            if (info != null && !info.media.isEmpty()) {
                gqlInfo = info; // 轮询线程看到后会合并并提前收尾，不用等满时限
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---------- 解析主流程 ----------

    private void startParse() {
        if (parseRunning) {
            Toast.makeText(this, "正在解析上一条，请稍等", Toast.LENGTH_SHORT).show();
            return;
        }
        String raw = editLink.getText().toString().trim();
        String[] parts = extractLink(raw);
        if (parts == null) {
            Toast.makeText(this, "没认出推文链接，请粘贴一条完整的推文链接", Toast.LENGTH_LONG).show();
            return;
        }
        String hintUser = parts[0];
        String tweetId = parts[1];
        btnDownload.setVisibility(Button.GONE);
        mediaList.removeAllViews();
        rowBtns.clear();
        rowDoneIdx.clear();
        rowBusyIdx.clear();
        allDownloading = false;
        currentInfo = null;
        txtPreview.setText("正在快速解析…");
        txtProgress.setText("");
        progressBar.setVisibility(View.GONE);
        progressBar.setIndeterminate(false);
        progressBar.setProgress(0);

        parseRunning = true;
        btnParse.setEnabled(false);
        currentTweetId = tweetId;
        currentHintUser = hintUser;

        // 第一段：先走快速通道（一个轻量请求，不用打开整个网页）
        new Thread(() -> {
            TweetInfo fast = tryFastParse(tweetId, hintUser);
            if (fast != null) {
                runOnUiThread(() -> {
                    parseRunning = false;
                    btnParse.setEnabled(true);
                    showPreview(fast);
                });
            } else {
                // 第二段：快速通道没拿到，自动改走已登录页面解析（慢一些，但登录才能看的也能下）
                runOnUiThread(() -> {
                    txtPreview.setText("快速解析没拿到，改用登录页面解析（稍慢）…");
                    startWebViewParse(tweetId, hintUser);
                });
            }
        }).start();
    }

    /** 快速通道：直接取公开接口的解析结果；失败/没媒体返回 null（调用方转兜底）。在后台线程跑。 */
    private TweetInfo tryFastParse(String tweetId, String hintUser) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("https://api.fxtwitter.com/status/" + tweetId).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", TweetParser.UA);
            if (conn.getResponseCode() != 200) return null;
            InputStream in = conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return TweetParser.parseFxBody(sb.toString(), tweetId, hintUser);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 兜底路线：在已登录 WebView 里打开推文页面提取（v1.0.1 路线，逻辑不变）。须在主线程调用。 */
    private void startWebViewParse(String tweetId, String hintUser) {
        gqlInfo = null;
        pageFinished = false;

        String pageUrl = hintUser != null
                ? "https://x.com/" + hintUser + "/status/" + tweetId
                : "https://x.com/i/status/" + tweetId;

        // 先在主线程把页面打开，再开轮询线程收集结果
        webParse.stopLoading();
        webParse.loadUrl(pageUrl);

        new Thread(() -> {
            try {
                TweetInfo info = collectWithPolling(tweetId, hintUser);
                runOnUiThread(() -> {
                    parseRunning = false;
                    btnParse.setEnabled(true);
                    showPreview(info);
                });
            } catch (ParseFail fail) {
                runOnUiThread(() -> {
                    parseRunning = false;
                    btnParse.setEnabled(true);
                    txtPreview.setText("");
                    Toast.makeText(this, fail.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private static class ParseFail extends Exception {
        ParseFail(String msg) { super(msg); }
    }

    /**
     * 轮询收集（最多约 30 秒只是上限）：
     * - 拦截到的 GraphQL 已给出媒体 → 合并后立即收尾；
     * - DOM 收集到的媒体连续两次完全一样（结果稳定）→ 立即收尾。
     * 常见情况几秒内就能结束，不会傻等满 30 秒。
     */
    private TweetInfo collectWithPolling(String tweetId, String hintUser) throws ParseFail {
        LinkedHashMap<String, TweetInfo.Media> merged = new LinkedHashMap<>();
        String prevSnapshot = null;
        boolean sawBlobVideo = false;
        String articleText = "";
        long deadline = System.currentTimeMillis() + 30000;

        while (System.currentTimeMillis() < deadline) {
            // 先并拦截结果
            TweetInfo g = gqlInfo;
            if (g != null) {
                for (TweetInfo.Media m : g.media) {
                    if (!merged.containsKey(m.url)) merged.put(m.url, m);
                }
            }
            JSONObject dom = evaluateCollectJs();
            if (dom != null) {
                mergeDomResult(dom, merged);
                if (dom.optBoolean("hasVideo") && dom.optBoolean("sawBlob") && !dom.optBoolean("sawReal")) {
                    // 有视频标签但地址全是 blob（流式），记下来，看后面能不能找到 mp4 直链补上
                    boolean hasMp4 = false;
                    for (TweetInfo.Media m : merged.values()) if (m.isVideo()) { hasMp4 = true; break; }
                    if (!hasMp4) sawBlobVideo = true; else sawBlobVideo = false;
                } else if (dom.optBoolean("sawReal")) {
                    sawBlobVideo = false;
                }
                String art = dom.optString("article", "");
                if (!art.isEmpty()) articleText = art;
            }
            // 再次并一次拦截结果（可能刚到）
            g = gqlInfo;
            boolean gqlReady = false;
            if (g != null && !g.media.isEmpty()) {
                for (TweetInfo.Media m : g.media) {
                    if (!merged.containsKey(m.url)) merged.put(m.url, m);
                }
                gqlReady = true;
            }

            if (!merged.isEmpty()) {
                if (gqlReady) break; // GraphQL 已给出媒体，直接收尾
                String snapshot = snapshotOf(merged);
                if (snapshot.equals(prevSnapshot)) break; // 连续两次结果一样 = 稳定，收尾
                prevSnapshot = snapshot;
            }
            sleepQuiet(700);
        }

        if (merged.isEmpty()) {
            if (sawBlobVideo) {
                throw new ParseFail("这条视频在网页里只有流地址（边播边传的那种），第一版还下不了这种，"
                        + "先不硬下假文件。你可以过一会儿再试一次。");
            }
            throw new ParseFail("这条推文没解析出来。可能的原因：链接不对、推文已被删除、"
                    + "没登录或登录过期（先去左上角设置里登录）、"
                    + "或者推特的页面又改版了。");
        }

        // 同一个视频的不同清晰度只留一个（顺序上 GraphQL 的最高码率排在最前面，优先保留）
        ArrayList<TweetInfo.Media> deduped = new ArrayList<>();
        Set<String> seenVideo = new HashSet<>();
        for (TweetInfo.Media m : merged.values()) {
            if (m.isVideo()) {
                String key = TweetParser.videoKey(m.url);
                if (!seenVideo.add(key)) continue;
            }
            deduped.add(m);
        }

        TweetInfo info = new TweetInfo();
        info.tweetId = tweetId;
        info.media.addAll(deduped);
        TweetInfo g = gqlInfo;
        if (g != null) {
            info.authorName = g.authorName;
            info.screenName = g.screenName;
            info.text = g.text;
            if (g.dateStr != null && !g.dateStr.isEmpty()) info.dateStr = g.dateStr;
        }
        if (info.screenName == null || info.screenName.isEmpty()) {
            info.screenName = hintUser != null ? hintUser : "unknown";
        }
        if (info.authorName == null || info.authorName.isEmpty()) info.authorName = info.screenName;
        if (info.text == null) info.text = "";
        if (info.dateStr == null || info.dateStr.isEmpty()) info.dateStr = TweetParser.todayStr();
        if (sawBlobVideo) {
            boolean hasVideo = false;
            for (TweetInfo.Media m : info.media) if (m.isVideo()) { hasVideo = true; break; }
            if (!hasVideo) {
                info.warning = "提醒：这条里的视频只有流地址，第一版下不了；下面这些图片可以正常下载。";
            }
        }
        return info;
    }

    private String snapshotOf(LinkedHashMap<String, TweetInfo.Media> merged) {
        ArrayList<String> keys = new ArrayList<>(merged.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (String k : keys) sb.append(k).append('|');
        return sb.toString();
    }

    private void mergeDomResult(JSONObject dom, LinkedHashMap<String, TweetInfo.Media> merged) {
        // 视频地址 -> 封面图（<video> 的 poster 属性），给预览列表用
        Map<String, String> posterBySrc = new HashMap<>();
        JSONArray posters = dom.optJSONArray("posters");
        if (posters != null) {
            for (int i = 0; i < posters.length(); i++) {
                JSONArray pair = posters.optJSONArray(i);
                if (pair != null && pair.length() >= 2) {
                    posterBySrc.put(pair.optString(0, ""), pair.optString(1, ""));
                }
            }
        }
        JSONArray videos = dom.optJSONArray("videos");
        if (videos != null) {
            for (int i = 0; i < videos.length(); i++) {
                String u = videos.optString(i, "");
                if (u.isEmpty() || u.startsWith("blob:")) continue;
                if (u.contains(".m3u8")) continue; // 流地址不当成 mp4
                if (!merged.containsKey(u)) {
                    merged.put(u, new TweetInfo.Media(u, "video", posterBySrc.get(u)));
                }
            }
        }
        JSONArray imgs = dom.optJSONArray("imgs");
        if (imgs != null) {
            for (int i = 0; i < imgs.length(); i++) {
                String u = imgs.optString(i, "");
                if (u.isEmpty()) continue;
                String orig = TweetParser.toOrigUrl(u);
                if (!merged.containsKey(orig)) {
                    merged.put(orig, new TweetInfo.Media(orig, "photo", TweetParser.toThumbUrl(u)));
                }
            }
        }
        String snips = dom.optString("snippets", "");
        if (!snips.isEmpty()) {
            TweetParser.extractFromHtmlText(snips, merged);
        }
    }

    /** 在主线程执行收集 JS 并等结果（轮询线程调用） */
    private JSONObject evaluateCollectJs() {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> out = new AtomicReference<>(null);
        webParse.post(() -> {
            try {
                webParse.evaluateJavascript(COLLECT_JS, value -> {
                    out.set(value);
                    latch.countDown();
                });
            } catch (Exception e) {
                latch.countDown();
            }
        });
        try {
            latch.await(4, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}
        String raw = out.get();
        if (raw == null || raw.equals("null")) return null;
        try {
            // evaluateJavascript 返回的是被 JSON 编码过一次的字符串，先解一层
            String inner = new JSONArray("[" + raw + "]").getString(0);
            return new JSONObject(inner);
        } catch (Exception e) {
            return null;
        }
    }

    private void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private void showPreview(TweetInfo info) {
        currentInfo = info;
        StringBuilder sb = new StringBuilder();
        sb.append("作者：").append(info.authorName).append("（@").append(info.screenName).append("）\n");
        if (!info.text.isEmpty()) {
            String t = info.text.length() > 200 ? info.text.substring(0, 200) + "…" : info.text;
            sb.append("正文：").append(t).append("\n");
        }
        sb.append("一共 ").append(info.media.size()).append(" 个媒体，可以单个下，也可以全部一起下。");
        if (info.warning != null && !info.warning.isEmpty()) {
            sb.append("\n").append(info.warning);
        }
        txtPreview.setText(sb.toString());
        buildMediaList(info);
        btnDownload.setVisibility(Button.VISIBLE);
    }

    // ---------- 预览列表：每行一个预览图 + 说明 + 单独下载 ----------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildMediaList(TweetInfo info) {
        mediaList.removeAllViews();
        rowBtns.clear();
        rowDoneIdx.clear();
        rowBusyIdx.clear();
        for (int i = 0; i < info.media.size(); i++) {
            final int idx = i;
            final TweetInfo.Media m = info.media.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = dp(10);
            row.setLayoutParams(rowLp);

            // 左边预览图：先放占位（灰底 + 视频/图片字样），图加载到再盖上
            FrameLayout thumbBox = new FrameLayout(this);
            thumbBox.setLayoutParams(new LinearLayout.LayoutParams(dp(84), dp(84)));
            thumbBox.setBackgroundColor(getResources().getColor(R.color.button_secondary_bg));
            TextView ph = new TextView(this);
            ph.setGravity(Gravity.CENTER);
            ph.setTextSize(13);
            ph.setTextColor(getResources().getColor(R.color.text_hint));
            ph.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            thumbBox.addView(ph);
            thumbBox.addView(iv);
            row.addView(thumbBox);

            // 中间说明文字
            TextView label = new TextView(this);
            String txt = "第 " + (i + 1) + " 个 · " + (m.isVideo() ? "视频" : "图片");
            if (!m.isVideo()) txt += "\n点图片查看大图";
            if (m.detail != null && !m.detail.isEmpty()) txt += "\n" + m.detail;
            label.setText(txt);
            label.setTextSize(14);
            label.setTextColor(getResources().getColor(R.color.text_primary));
            LinearLayout.LayoutParams labLp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            labLp.leftMargin = dp(12);
            label.setLayoutParams(labLp);
            row.addView(label);

            // 视频行加播放入口：点预览图或「▶ 播放」小按钮都能在线播放（图片行不加）
            if (m.isVideo()) {
                Button play = new Button(this);
                play.setText("▶ 播放");
                play.setTextColor(getResources().getColor(R.color.button_secondary_text));
                play.setBackgroundTintList(ColorStateList.valueOf(
                        getResources().getColor(R.color.button_secondary_bg)));
                LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                playLp.rightMargin = dp(8);
                play.setLayoutParams(playLp);
                play.setOnClickListener(v -> openPlayer(idx));
                row.addView(play);
                thumbBox.setOnClickListener(v -> openPlayer(idx));
            } else {
                // 图片行（v1.0.4 新增）：点预览图打开全屏大图预览
                thumbBox.setOnClickListener(v -> openImagePreview(idx));
            }

            // 右边单独下载按钮
            Button b = new Button(this);
            b.setText("下载");
            b.setTextColor(getResources().getColor(R.color.button_text));
            b.setBackgroundTintList(ColorStateList.valueOf(getResources().getColor(R.color.button_bg)));
            b.setOnClickListener(v -> startSingleDownload(idx));
            row.addView(b);
            rowBtns.add(b);

            mediaList.addView(row);
            loadThumb(iv, ph, m.thumbUrl, m.isVideo());
        }
    }

    /** 后台异步加载预览图（带登录 Cookie），内存里简单缓存；失败就留占位 */
    private void loadThumb(ImageView iv, TextView ph, String url, boolean isVideo) {
        ph.setText(isVideo ? "视频" : "图片");
        if (url == null || url.isEmpty()) return;
        Bitmap cached = THUMB_CACHE.get(url);
        if (cached != null) {
            iv.setImageBitmap(cached);
            ph.setVisibility(View.GONE);
            return;
        }
        iv.setTag(url);
        thumbPool.execute(() -> {
            Bitmap bmp = null;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", TweetParser.UA);
                String cookie = CookieManager.getInstance().getCookie("https://x.com");
                if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    InputStream in = conn.getInputStream();
                    bmp = BitmapFactory.decodeStream(in);
                    in.close();
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
            if (bmp == null) return;
            if (bmp.getWidth() > 512 || bmp.getHeight() > 512) {
                float r = Math.min(512f / bmp.getWidth(), 512f / bmp.getHeight());
                bmp = Bitmap.createScaledBitmap(bmp,
                        Math.max(1, (int) (bmp.getWidth() * r)),
                        Math.max(1, (int) (bmp.getHeight() * r)), true);
            }
            THUMB_CACHE.put(url, bmp);
            final Bitmap fb = bmp;
            runOnUiThread(() -> {
                if (url.equals(iv.getTag())) {
                    iv.setImageBitmap(fb);
                    ph.setVisibility(View.GONE);
                }
            });
        });
    }

    private void updateRowButton(int idx) {
        if (idx < 0 || idx >= rowBtns.size()) return;
        Button b = rowBtns.get(idx);
        if (rowDoneIdx.contains(idx)) {
            b.setText("已下载");
            b.setEnabled(false);
        } else if (rowBusyIdx.contains(idx)) {
            b.setText("下载中…");
            b.setEnabled(false);
        } else if (allDownloading) {
            b.setText("下载");
            b.setEnabled(false);
        } else {
            b.setText("下载");
            b.setEnabled(true);
        }
    }

    private void updateAllRowButtons() {
        for (int i = 0; i < rowBtns.size(); i++) updateRowButton(i);
    }

    // ---------- 下载 ----------

    /** 安卓 9 需要先拿到存储权限；返回 true 表示可以直接下载 */
    private boolean ensureStoragePermission() {
        if (Build.VERSION.SDK_INT == 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 100);
            Toast.makeText(this, "请先允许存储权限，允许后再点一次下载", Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    /** 打开图片全屏预览（看的是原图地址，和下载同一个地址） */
    private void openImagePreview(int index) {
        if (currentInfo == null || index < 0 || index >= currentInfo.media.size()) return;
        TweetInfo.Media m = currentInfo.media.get(index);
        Intent it = new Intent(this, ImagePreviewActivity.class);
        it.putExtra(ImagePreviewActivity.EXTRA_URL, m.url);
        it.putExtra(ImagePreviewActivity.EXTRA_TITLE,
                "@" + currentInfo.screenName + " · 第 " + (index + 1) + " 张图片");
        startActivity(it);
    }

    /** 打开视频播放预览（播的是解析到的 mp4 直链，和下载同一个地址） */
    private void openPlayer(int index) {
        if (currentInfo == null || index < 0 || index >= currentInfo.media.size()) return;
        TweetInfo.Media m = currentInfo.media.get(index);
        Intent it = new Intent(this, PlayerActivity.class);
        it.putExtra(PlayerActivity.EXTRA_URL, m.url);
        it.putExtra(PlayerActivity.EXTRA_TITLE,
                "@" + currentInfo.screenName + " · 第 " + (index + 1) + " 个视频");
        startActivity(it);
    }

    /** 单独下载列表里的某一个：打包成任务交给下载服务（在后台下，界面只管看进度广播） */
    private void startSingleDownload(int index) {
        if (currentInfo == null || index < 0 || index >= currentInfo.media.size()) return;
        if (rowBusyIdx.contains(index) || rowDoneIdx.contains(index)) return;
        List<Integer> one = new ArrayList<>();
        one.add(index);
        enqueueDownload(one);
    }

    /** 全部下载：把还没下过的都打包成一个任务交给下载服务 */
    private void startDownload() {
        if (currentInfo == null) return;
        List<Integer> rest = new ArrayList<>();
        for (int i = 0; i < currentInfo.media.size(); i++) {
            if (!rowDoneIdx.contains(i) && !rowBusyIdx.contains(i)) rest.add(i);
        }
        if (rest.isEmpty()) {
            Toast.makeText(this, "这条推文的媒体都已经下过了", Toast.LENGTH_SHORT).show();
            return;
        }
        enqueueDownload(rest);
    }

    /**
     * 把一次下载（一个或多个序号）交给 DownloadService。
     * 服务内部有队列：正在下别的任务时，新任务排队等着，不打断、不并发。
     * 安卓 9 的存储权限还是在界面这边先检查完再启动服务。
     */
    private void enqueueDownload(List<Integer> indices) {
        if (currentInfo == null || indices.isEmpty()) return;
        if (!ensureStoragePermission()) return;
        // 通知权限已改到刚进软件时申请（v1.0.4），这里不再问
        boolean alreadyBusy = DownloadService.activeOrQueued;
        try {
            Intent svc = new Intent(this, DownloadService.class);
            svc.putExtra(DownloadService.EXTRA_JOB, buildJobJson(currentInfo, indices).toString());
            startForegroundService(svc); // minSdk 28，可以直接用这个系统方法
        } catch (Exception e) {
            Toast.makeText(this, "下载没能启动：" + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        for (int i : indices) {
            rowBusyIdx.add(i);
            updateRowButton(i);
        }
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        progressBar.setProgress(0);
        txtProgress.setText(alreadyBusy
                ? "已加入下载队列，前面还有任务在下，下完会自动接着下这个…"
                : "开始下载…（切到别的软件、锁屏都不会断，通知栏里也能看进度）");
    }

    /** 任务打包成 JSON：推文信息 + 全部媒体 + 这次要下的序号 + 当前登录 Cookie */
    private JSONObject buildJobJson(TweetInfo info, List<Integer> indices) throws Exception {
        JSONObject job = new JSONObject();
        job.put("tweetId", info.tweetId);
        job.put("screenName", info.screenName);
        job.put("authorName", info.authorName);
        job.put("text", info.text);
        job.put("dateStr", info.dateStr);
        JSONArray media = new JSONArray();
        for (TweetInfo.Media m : info.media) {
            JSONObject mj = new JSONObject();
            mj.put("url", m.url);
            mj.put("type", m.type);
            mj.put("thumbUrl", m.thumbUrl == null ? "" : m.thumbUrl);
            mj.put("detail", m.detail == null ? "" : m.detail);
            media.put(mj);
        }
        job.put("media", media);
        JSONArray idx = new JSONArray();
        for (int i : indices) idx.put(i);
        job.put("indices", idx);
        String cookie = "";
        try {
            String c = CookieManager.getInstance().getCookie("https://x.com");
            if (c != null) cookie = c;
        } catch (Exception ignored) {}
        job.put("cookie", cookie);
        return job;
    }

    // v1.0.4：原“第一次下载时问一次通知权限”的 maybeAskNotificationPermission 已删除，
    // 权限统一在 onCreate 的 askStartupPermissions 里进软件时申请。
}
