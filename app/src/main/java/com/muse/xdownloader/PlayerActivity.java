package com.muse.xdownloader;

import android.app.Activity;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.util.HashMap;
import java.util.Map;

/**
 * 视频播放预览：直接播解析到的 mp4 直链（和下载同一个地址），
 * 请求时带上登录 Cookie，有些视频地址没登录播不了。
 * 能拖进度、能暂停，播不了就提示用户直接下载后看。
 *
 * v1.0.4 修比例拉伸：以前布局里 VideoView 双向 match_parent，被硬撑满整个区域，
 * 16:9 的视频就拉伸变形。现在布局只给 wrap_content 并居中，onPrepared 里按视频
 * 原始宽高比算出适配尺寸再 setLayoutParams（见 fitVideoSize）。
 */
public class PlayerActivity extends Activity {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";

    private VideoView videoView;
    private ProgressBar loading;
    private MediaController mediaController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        TextView titleView = findViewById(R.id.txtPlayerTitle);
        loading = findViewById(R.id.playerLoading);
        videoView = findViewById(R.id.videoView);
        findViewById(R.id.btnPlayerBack).setOnClickListener(v -> finish());

        String url = getIntent().getStringExtra(EXTRA_URL);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        titleView.setText(title == null || title.isEmpty() ? "视频预览" : title);

        if (url == null || url.isEmpty()) {
            Toast.makeText(this, "这个视频暂时播放不了，你可以直接下载后看", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        mediaController = new MediaController(this);
        mediaController.setAnchorView(videoView);
        videoView.setMediaController(mediaController);

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", TweetParser.UA);
        try {
            String cookie = CookieManager.getInstance().getCookie("https://x.com");
            if (cookie != null && !cookie.isEmpty()) headers.put("Cookie", cookie);
        } catch (Exception ignored) {}

        loading.setVisibility(View.VISIBLE);
        videoView.setOnPreparedListener(mp -> {
            loading.setVisibility(View.GONE);
            fitVideoSize(mp); // 先按视频真实比例把 VideoView 尺寸定好，再开播
            videoView.start(); // 打开就自动播
        });
        videoView.setOnErrorListener((mp, what, extra) -> {
            loading.setVisibility(View.GONE);
            Toast.makeText(this, "这个视频暂时播放不了，你可以直接下载后看", Toast.LENGTH_LONG).show();
            return true; // 错误自己处理了，不弹系统对话框
        });
        // setVideoURI 带请求头的重载是 API 21+，本应用 minSdk 28，没问题
        videoView.setVideoURI(Uri.parse(url), headers);
    }

    /**
     * 按视频原始宽高比算适配尺寸，写进 VideoView 的 LayoutParams：
     * 可用区域 = 播放页根布局宽 ×（根布局高 − 顶部栏高）；
     * 先以可用宽为基准算高，算出的高超过可用高时改以高为基准反算宽。
     * 视频宽高取不到（0）时退回 16:9。尺寸定好后重新给 MediaController 设锚点，
     * 保证控制条贴着视频底部、位置对得上。
     */
    private void fitVideoSize(MediaPlayer mp) {
        View root = findViewById(R.id.playerRoot);
        View topBar = findViewById(R.id.playerTopBar);
        int availW = root.getWidth() > 0 ? root.getWidth()
                : getResources().getDisplayMetrics().widthPixels;
        int rootH = root.getHeight() > 0 ? root.getHeight()
                : getResources().getDisplayMetrics().heightPixels;
        int availH = rootH - (topBar != null ? topBar.getHeight() : 0);
        if (availW <= 0) availW = getResources().getDisplayMetrics().widthPixels;
        if (availH <= 0) availH = getResources().getDisplayMetrics().heightPixels;

        int vw = 0, vh = 0;
        try {
            vw = mp.getVideoWidth();
            vh = mp.getVideoHeight();
        } catch (Exception ignored) {}
        float ratio = (vw > 0 && vh > 0) ? (float) vw / (float) vh : 16f / 9f;

        int w = availW;
        int h = Math.max(1, (int) (w / ratio));
        if (h > availH) {
            h = availH;
            w = Math.max(1, (int) (h * ratio));
        }
        ViewGroup.LayoutParams lp = videoView.getLayoutParams();
        lp.width = w;
        lp.height = h;
        videoView.setLayoutParams(lp);
        if (mediaController != null) mediaController.setAnchorView(videoView);
        videoView.requestLayout();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (videoView != null && videoView.isPlaying()) videoView.pause();
    }
}
