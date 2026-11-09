package com.flipduo.module.ui

internal enum class SettingsPanel(val title: String, val summary: String) {
    MENU("设置", ""),
    BEHAVIOR("外屏行为", "事件岛、横屏与状态栏"),
    NAVIGATION("导航键长按", "返回、桌面与最近任务"),
    FEEDBACK("触感与动画", "控件反馈与过渡时长"),
    SCREENSHOTS("外屏截图", "带壳截图与截图作用域"),
    COMPATIBILITY("兼容设置", "内屏最近任务与系统框架");

    val route: String get() = "settings_$name"
}

/** The same overlay stack owns About and settings categories; nested pickers retain their parent. */
internal fun managerPagesAfterPush(pages: List<String>, route: String): List<String> {
    val current = pages.lastOrNull()
    return if (pages.isEmpty() || current == "about" && route == "license"
        || current == SettingsPanel.NAVIGATION.route && route.startsWith("long_app_")) pages + route
    else pages
}
