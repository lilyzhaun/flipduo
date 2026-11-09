// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

/** Stable preference IDs shared by the management app and native controls. Labels are not stored. */
public enum NavigationLongPress {
    NONE("无动作", null),
    LOCK("锁屏", "lock"),
    SCREENSHOT("截图", "screenshot"),
    NOTIFICATIONS("通知中心", "notifications"),
    CONTROL_CENTER("控制中心", "quick_settings"),
    VOLUME("音量面板", "volume"),
    MINI_WINDOW("当前应用变为小窗", null),
    SETTINGS("打开 FlipDuo 设置", null),
    TORCH("手电筒开关", "torch"),
    POWER_MENU("电源菜单", "power_menu"),
    PREVIOUS_APP("上一个应用", "previous_app"),
    LAUNCH_APP("打开指定应用", null),
    CAMERA("相机", null),
    ASSISTANT("系统语音助手", null),
    SYSTEM_SETTINGS("系统设置", null),
    BACK("返回", "back"),
    HOME("返回桌面", "home"),
    RECENTS("最近任务", "recents");

    public final String title;
    final String tool;
    NavigationLongPress(String title, String tool) { this.title = title; this.tool = tool; }
    public static NavigationLongPress parse(String value, NavigationLongPress fallback) {
        for (NavigationLongPress action : values()) if (action.name().equals(value)) return action;
        return fallback;
    }
    public static String appPackage(String value) {
        return value != null && value.length() <= 255 && value.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+") ? value : "";
    }
}
