package com.muse.xdownloader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/** 设置页：放登录状态和登录/退出（按状态只显示该有的那个，判断口径和主界面共用 MainActivity.isLoggedIn） */
public class SettingsActivity extends Activity {

    private TextView txtStatus;
    private Button btnLogin, btnLogout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        txtStatus = findViewById(R.id.txtLoginStatusSettings);
        btnLogin = findViewById(R.id.btnLogin);
        btnLogout = findViewById(R.id.btnLogout);
        btnLogin.setOnClickListener(v ->
                startActivity(new Intent(this, LoginActivity.class)));
        btnLogout.setOnClickListener(v -> confirmLogout());
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean logged = MainActivity.isLoggedIn(this);
        txtStatus.setText(logged ? "登录状态：已登录" : "登录状态：未登录");
        // v1.0.6：哪种状态只摆哪种按钮——没登录就没有退出可言，登好了也不用再显示登录
        btnLogin.setVisibility(logged ? View.GONE : View.VISIBLE);
        btnLogout.setVisibility(logged ? View.VISIBLE : View.GONE);
    }

    /** 退出前先弹确认框，防手滑；只有点了「退出登录」才真退 */
    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle("确定要退出登录吗？")
                .setMessage("退出后，想下载需要登录才能看的推文时，就得重新登录一次。")
                .setNegativeButton("取消", null)
                .setPositiveButton("退出登录", (dialog, which) -> logout())
                .show();
    }

    private void logout() {
        getSharedPreferences(LoginActivity.PREFS, MODE_PRIVATE).edit().clear().apply();
        CookieManager cm = CookieManager.getInstance();
        cm.removeAllCookies(null);
        cm.flush();
        refresh();
        Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show();
    }
}
