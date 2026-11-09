package com.flipduo.module;

import android.content.SharedPreferences;

/** One canonical choice. The old boolean is read only for migration / older loaded hosts. */
public enum CoverBackground {
    SYSTEM("system", "系统原色"), BLACK("black", "纯黑"), BLUR_FILL("blur_fill", "模糊填充");

    public static final String KEY = "cover_background";
    public final String key, title;
    CoverBackground(String key, String title) { this.key = key; this.title = title; }

    public static CoverBackground read(SharedPreferences prefs) {
        String value = prefs.getString(KEY, "");
        // The removed glass mode becomes native color, never silently changes to black.
        if ("glass".equals(value)) return SYSTEM;
        for (CoverBackground mode : values()) if (mode.key.equals(value)) return mode;
        return prefs.getBoolean("black_background", true) ? BLACK : SYSTEM;
    }
}
