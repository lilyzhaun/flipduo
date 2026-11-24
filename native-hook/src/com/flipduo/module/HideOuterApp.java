// SPDX-License-Identifier: AGPL-3.0-only
// Package list and return-value rules adapted from parallelcc/MixFlipMod, HideOuterHook.kt and AppConfig.kt,
// commit 6b02e8cec6497efd2a07b9ba1307fb9c2f979867. https://github.com/parallelcc/MixFlipMod
package com.flipduo.module;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** The seven stock app processes. This setting is independent of both the status bar and fullscreen. */
public enum HideOuterApp {
    CALENDAR("com.android.calendar", "日历"), CONTACTS("com.android.contacts", "通讯录与拨号"),
    CLOCK("com.android.deskclock", "时钟"), MESSAGES("com.android.mms", "短信"),
    RECORDER("com.android.soundrecorder", "录音机"), CALCULATOR("com.miui.calculator", "计算器"),
    GALLERY("com.miui.gallery", "相册");

    public static final String KEY = "hide_outer_apps";
    public final String pkg, title;
    HideOuterApp(String pkg, String title) { this.pkg = pkg; this.title = title; }

    static HideOuterApp find(String pkg) {
        for (HideOuterApp app : values()) if (app.pkg.equals(pkg)) return app;
        return null;
    }
    public static Set<String> read(SharedPreferences prefs) {
        Set<String> raw = prefs.getStringSet(KEY, Collections.emptySet());
        Set<String> selected = new LinkedHashSet<>();
        for (HideOuterApp app : values()) if (raw.contains(app.pkg)) selected.add(app.pkg);
        return selected;
    }
    boolean enabled(SharedPreferences prefs) {
        return prefs.getBoolean("enabled", true) && prefs.getStringSet(KEY, Collections.emptySet()).contains(pkg);
    }
    public static boolean hasAppCode(ApplicationInfo app) {
        // Do not mistake resource splits in a future native Gallery for a Java Gallery build.
        return (app.flags & ApplicationInfo.FLAG_HAS_CODE) != 0 || (!GALLERY.pkg.equals(app.packageName)
                && app.splitSourceDirs != null && app.splitSourceDirs.length != 0);
    }
}
