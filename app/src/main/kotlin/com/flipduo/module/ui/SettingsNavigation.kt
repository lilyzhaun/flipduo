package com.flipduo.module.ui

internal enum class SettingsPanel(val title: String, val summary: String) {
    MENU("设置", ""),
    BEHAVIOR("外屏行为", "事件岛、横屏与状态栏"),
    DESKTOP("外屏桌面", "桌面列数、搜索与双击锁屏"),
    HIDE_OUTER("隐藏外屏状态", "让所选系统应用使用普通布局"),
    INPUT_METHOD("外屏输入法", "单独选择外屏使用的键盘"),
    NAVIGATION("控制区动作", "三合一与时钟点按、导航键长按"),
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
