package com.muse.xdownloader;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 登录页：用 WebView 打开 X 官方登录页面。
 * 用户自己在官方页面输入账号密码，本软件绝不接触、绝不保存密码。
 * 登录完成后，只读取浏览器 cookie 里的 auth_token 和 ct0 两个登录凭证，
 * 存进本应用私有存储，只用于向 X 官方接口请求用户自己能看到的推文。
 *
 * v1.0.5 加固（之前配置太裸，页面打不开还静默白屏）：
 *  - UA 换成真实 Chrome 安卓浏览器的，去掉系统 WebView 自带的 wv 标记；
 *  - 装 WebChromeClient：顶部加载进度条、多窗口（window.open 的验证窗口有人接）、JS 对话框直接放行不卡页；
 *  - WebViewClient 加强：非 http 链接不崩、主框架出错时显示带原因的错误面板+重试，不再白屏；
 *  - 底部加「换个入口重试」，在三个官方登录入口之间轮换。
 */
public class LoginActivity extends Activity {

    public static final String PREFS = "cred";
    public static final String KEY_AUTH = "auth_token";
    public static final String KEY_CT0 = "ct0";

    /** 真实 Chrome 安卓浏览器的 UA（没有 wv 标记，X 对内嵌浏览器没那么不友好） */
    private static final String CHROME_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7 Pro) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36";

    /** 三个官方登录入口，轮换重试用 */
    private static final String[] ENTRIES = {
            "https://x.com/i/flow/login",
            "https://x.com/login",
            "https://twitter.com/i/flow/login",
    };

    private FrameLayout container;
    private WebView web;
    private WebView popupWeb;
    private ProgressBar progress;
    private View panelError;
    private TextView tvErrorTitle;
    private TextView tvErrorDetail;
    private int entryIndex = 0;
    private boolean loginToastShown = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        container = findViewById(R.id.webContainer);
        progress = findViewById(R.id.progressLogin);
        panelError = findViewById(R.id.panelError);
        tvErrorTitle = findViewById(R.id.tvErrorTitle);
        tvErrorDetail = findViewById(R.id.tvErrorDetail);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);

        web = findViewById(R.id.webLogin);
        setupWebView(web);
        cm.setAcceptThirdPartyCookies(web, true);
        web.loadUrl(ENTRIES[entryIndex]);

        Button retry = findViewById(R.id.btnRetry);
        retry.setOnClickListener(v -> {
            hideError();
            String url = web.getUrl();
            if (url == null || url.isEmpty() || url.startsWith("about:")) {
                web.loadUrl(ENTRIES[entryIndex]);
            } else {
                web.reload();
            }
        });

        Button done = findViewById(R.id.btnDone);
        done.setOnClickListener(v -> finishLogin());

        Button alt = findViewById(R.id.btnAltEntry);
        alt.setOnClickListener(v -> {
            entryIndex = (entryIndex + 1) % ENTRIES.length;
            hideError();
            Toast.makeText(this, "换个入口重试中…", Toast.LENGTH_SHORT).show();
            web.loadUrl(ENTRIES[entryIndex]);
        });
    }

    /** 给一个 WebView 套上完整的浏览器配置（主窗口和弹出窗口共用） */
    private void setupWebView(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadsImagesAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setUserAgentString(CHROME_UA);
        w.setWebViewClient(new LoginWebViewClient());
        w.setWebChromeClient(new LoginChromeClient());
    }

    private void showError(String title, String detail) {
        tvErrorTitle.setText(title);
        tvErrorDetail.setText(detail);
        panelError.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        panelError.setVisibility(View.GONE);
    }

    private class LoginChromeClient extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progress.setProgress(newProgress);
            progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
        }

        /**
         * 接住 window.open：新建一个全屏子 WebView 叠在容器里显示弹出窗口，
         * 页面用 window.close() 关窗时走 onCloseWindow 把它移除。
         * X 登录中途做验证时会开这种窗口，没人接就会卡住不动。
         */
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog,
                                       boolean isUserGesture, Message resultMsg) {
            WebView popup = new WebView(LoginActivity.this);
            setupWebView(popup);
            CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true);
            popup.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            container.addView(popup);
            popupWeb = popup;
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(popup);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public void onCloseWindow(WebView window) {
            if (window == popupWeb && popupWeb != null) {
                container.removeView(popupWeb);
                popupWeb.destroy();
                popupWeb = null;
            }
        }

        // JS 弹窗直接放行：只为不让对话框没人点而把页面卡住
        @Override
        public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            result.confirm();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
            result.confirm();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message,
                                  String defaultValue, android.webkit.JsPromptResult result) {
            result.confirm(defaultValue == null ? "" : defaultValue);
            return true;
        }
    }

    private class LoginWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUrl(view, request.getUrl().toString());
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUrl(view, url);
        }

        private boolean handleUrl(WebView view, String url) {
            if (url.startsWith("https://") || url.startsWith("http://")
                    || url.startsWith("about:")) {
                return false; // 正常网页，留在 WebView 里打开
            }
            // intent://、twitter:// 这类非 http 链接：尝试交给系统打开，打不开就忽略+提示，别崩
            try {
                Intent intent;
                if (url.startsWith("intent://")) {
                    intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                } else {
                    intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                }
                view.getContext().startActivity(intent);
            } catch (ActivityNotFoundException | java.net.URISyntaxException e) {
                Toast.makeText(LoginActivity.this,
                        "这个链接需要在别的软件里打开，已跳过", Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            hideError();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            progress.setProgress(100);
            progress.setVisibility(View.GONE);
            // 已登录就提醒一次：可以点下面的按钮收尾了
            if (!loginToastShown) {
                String cookies = CookieManager.getInstance().getCookie("https://x.com");
                if (cookies != null && cookies.contains("auth_token=")) {
                    loginToastShown = true;
                    Toast.makeText(LoginActivity.this,
                            "已检测到登录，点下面的「我已登录完成」就能完成",
                            Toast.LENGTH_LONG).show();
                }
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    WebResourceError error) {
            if (!request.isForMainFrame()) return; // 子资源失败不打扰用户
            String desc = error.getDescription() != null
                    ? error.getDescription().toString() : "";
            showError("登录页没打开（错误：" + shortReason(error.getErrorCode()) + "）",
                    "系统原文：" + desc + "（错误码 " + error.getErrorCode()
                            + "，地址：" + request.getUrl() + "）\n"
                            + "可以检查网络后点重试，还不行就用下面的「换个入口重试」。"
                            + "如果一直打不开，把这一屏截图发给我看。");
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        WebResourceResponse errorResponse) {
            if (!request.isForMainFrame()) return;
            int code = errorResponse.getStatusCode();
            String phrase = errorResponse.getReasonPhrase() != null
                    ? errorResponse.getReasonPhrase() : "";
            showError("登录页没打开（错误：网页返回了状态码 " + code + "）",
                    "系统原文：" + phrase + "（地址：" + request.getUrl() + "）\n"
                            + "可以点重试，或用下面的「换个入口重试」。"
                            + "如果一直打不开，把这一屏截图发给我看。");
        }
    }

    private static String shortReason(int errorCode) {
        switch (errorCode) {
            case WebViewClient.ERROR_HOST_LOOKUP:
                return "连不上服务器，找不到这个网站";
            case WebViewClient.ERROR_CONNECT:
                return "连不上服务器";
            case WebViewClient.ERROR_TIMEOUT:
                return "连接超时，网太慢了";
            case WebViewClient.ERROR_FAILED_SSL_HANDSHAKE:
                return "安全连接握手失败";
            case WebViewClient.ERROR_IO:
                return "网络读写出错";
            case WebViewClient.ERROR_AUTHENTICATION:
                return "验证失败";
            case WebViewClient.ERROR_REDIRECT_LOOP:
                return "页面跳转次数太多";
            case WebViewClient.ERROR_FILE_NOT_FOUND:
                return "页面不存在";
            case WebViewClient.ERROR_UNSUPPORTED_SCHEME:
                return "不支持的链接类型";
            case WebViewClient.ERROR_BAD_URL:
                return "链接地址不对";
            default:
                return "网络出错";
        }
    }

    @Override
    public void onBackPressed() {
        if (popupWeb != null) {
            container.removeView(popupWeb);
            popupWeb.destroy();
            popupWeb = null;
            return;
        }
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (popupWeb != null) {
            popupWeb.destroy();
            popupWeb = null;
        }
        if (web != null) {
            web.destroy();
        }
        super.onDestroy();
    }

    private void finishLogin() {
        CookieManager cm = CookieManager.getInstance();
        String cookieStr = cm.getCookie("https://x.com");
        String auth = null, ct0 = null;
        if (cookieStr != null) {
            for (String part : cookieStr.split(";")) {
                String p = part.trim();
                if (p.startsWith("auth_token=")) auth = p.substring("auth_token=".length());
                else if (p.startsWith("ct0=")) ct0 = p.substring("ct0=".length());
            }
        }
        if (auth == null || auth.isEmpty() || ct0 == null || ct0.isEmpty()) {
            Toast.makeText(this, "还没检测到登录，请先在页面里把登录完成", Toast.LENGTH_LONG).show();
            return;
        }
        cm.flush(); // 把 cookie 落地，解析用的 WebView 和下载请求都能用到同一份登录状态
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        sp.edit().putString(KEY_AUTH, auth).putString(KEY_CT0, ct0).apply();
        Toast.makeText(this, "登录成功", Toast.LENGTH_LONG).show();
        finish();
    }
}
