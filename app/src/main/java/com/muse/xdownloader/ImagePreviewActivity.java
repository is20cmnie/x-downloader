package com.muse.xdownloader;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.View;
import android.webkit.CookieManager;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 图片全屏预览（v1.0.4 新增）：点预览列表里图片行的缩略图打开。
 * 黑底全屏看原图（就是下载用的 orig 地址），ImageView 用 FIT_CENTER 保持原比例、绝不拉伸。
 * 加载时走后台线程，带登录 Cookie；原图可能很大，先按屏幕尺寸算 inSampleSize 再解码，避免内存爆掉。
 * 不做捏合缩放，全屏看清即可。
 */
public class ImagePreviewActivity extends Activity {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";

    private ImageView imageView;
    private ProgressBar loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_preview);

        TextView titleView = findViewById(R.id.txtImageTitle);
        loading = findViewById(R.id.imageLoading);
        imageView = findViewById(R.id.imageFull);
        findViewById(R.id.btnImageBack).setOnClickListener(v -> finish());

        String url = getIntent().getStringExtra(EXTRA_URL);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        titleView.setText(title == null || title.isEmpty() ? "图片预览" : title);

        if (url == null || url.isEmpty()) {
            Toast.makeText(this, "图片加载不了", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        loading.setVisibility(View.VISIBLE);
        new Thread(() -> {
            Bitmap bmp = downloadBitmap(url);
            runOnUiThread(() -> {
                loading.setVisibility(View.GONE);
                if (bmp != null) {
                    imageView.setImageBitmap(bmp);
                } else {
                    Toast.makeText(this, "图片加载不了", Toast.LENGTH_LONG).show();
                    finish();
                }
            });
        }).start();
    }

    /** 下载原图字节并按屏幕尺寸采样解码；失败返回 null */
    private Bitmap downloadBitmap(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", TweetParser.UA);
            try {
                String cookie = CookieManager.getInstance().getCookie("https://x.com");
                if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
            } catch (Exception ignored) {}
            if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 300) return null;

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
            in.close();
            byte[] data = baos.toByteArray();
            if (data.length == 0) return null;

            // 先只读尺寸，再按屏幕最大边算采样率，避免原图全尺寸解码爆内存
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            DisplayMetrics dm = getResources().getDisplayMetrics();
            int maxDim = Math.max(dm.widthPixels, dm.heightPixels);
            int sample = 1;
            while ((bounds.outWidth / sample) > maxDim || (bounds.outHeight / sample) > maxDim) {
                sample *= 2;
            }
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, opt);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
