package com.flipduo.module;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

/** Compatibility alias for shortcuts created before the full settings application. */
public final class SettingsActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        try {
            startActivity(new Intent().setClassName(getPackageName(), "com.flipduo.module.MainActivity")); finish();
        } catch (android.content.ActivityNotFoundException unavailable) {
            TextView text = new TextView(this); text.setPadding(32, 64, 32, 32); text.setTextSize(18);
            text.setText("请安装包含 miuix 设置界面的完整 FlipDuo 安装包。旧截屏取色已停用。"); setContentView(text);
        }
    }
}
