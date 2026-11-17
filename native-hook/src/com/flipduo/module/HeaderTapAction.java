// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.content.Intent;
import android.provider.AlarmClock;
import android.provider.Settings;

/** Destinations for the two independently configurable header shortcuts. */
public enum HeaderTapAction {
    WIFI("Wi-Fi 设置"), CLOCK("时钟"), CALENDAR("日历"), MODULE_SETTINGS("FlipDuo 设置"), NONE("无动作");

    public final String title;
    HeaderTapAction(String title) { this.title = title; }

    public static HeaderTapAction read(String stored, HeaderTapAction fallback) {
        for (HeaderTapAction action : values()) if (action.name().equals(stored)) return action;
        return fallback;
    }

    Intent intent() {
        return switch (this) {
            case WIFI -> new Intent(Settings.ACTION_WIFI_SETTINGS);
            case CLOCK -> new Intent(AlarmClock.ACTION_SHOW_ALARMS);
            case CALENDAR -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR);
            case MODULE_SETTINGS -> new Intent().setClassName("com.flipduo.module", "com.flipduo.module.MainActivity");
            case NONE -> null;
        };
    }
}
