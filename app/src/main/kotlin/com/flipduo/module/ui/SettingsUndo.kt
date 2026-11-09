// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module.ui

/**
 * B05: "undo this page's last saved change". Groups follow the page that edits the fields, so an undo
 * only ever restores values the user sees on that page. Master switch, legacy key order and revision
 * belong to no group. Pure Kotlin: no Android, preferences or framework.
 */
internal enum class SettingsGroup(val title: String) {
    CONTROLS("控件布局"), CAMERA("镜头圆环"), STATUS("时钟显示"), TRIO("三合一"), CORNERS("应用侧圆角"),
    BEHAVIOR("外屏行为"), NAVIGATION("导航键长按"), FEEDBACK("触感与动画"), SCREENSHOTS("外屏截图"),
    COMPATIBILITY("兼容设置"), FULLSCREEN("应用全屏");

    /** This group's business values, compared to detect which groups a commit touched. */
    fun values(c: Controls): List<Any?> = when (this) {
        CONTROLS -> listOf(c.offsetY, c.scale, c.iconScale, c.positions)
        CAMERA -> listOf(c.cameraMainRing, c.cameraLowerRing, c.cameraNotifications, c.cameraBreathing, c.cameraSecondRing,
            c.cameraDebugRings, c.cameraMainX, c.cameraMainY, c.cameraLowerX, c.cameraLowerY, c.cameraMainRadius,
            c.cameraLowerRadius, c.cameraMainStroke, c.cameraLowerStroke, c.cameraTimerColor, c.cameraProgressColor,
            c.cameraChargeColor, c.cameraTimerBreathColor, c.cameraNotificationColor, c.cameraChargeTarget, c.cameraChargeTargetPercent, c.visualFocus, c.visualFocusHideCount, c.cameraChargeArrival)
        STATUS -> listOf(c.showClock)
        TRIO -> listOf(c.showTrio, c.statusScale, c.trioStroke, c.trioWifiScale, c.trioWifiStroke, c.trioNetworkScale,
            c.trioNetworkWeight, c.trioBatteryNumber, c.trioBatteryScale, c.trioBatteryWeight)
        CORNERS -> listOf(c.systemCorners, c.symmetricCorners)
        BEHAVIOR -> listOf(c.background, c.recentsCrop, c.recentsStyle, c.recentsCardLongPress, c.eventIsland, c.islandLock,
            c.landscapeCompat, c.hideStatus, c.hideCoverCamera, c.statusBarHeight, c.notificationFullscreen, c.hideCoverBottomGuard)
        NAVIGATION -> listOf(c.backLongPress, c.homeLongPress, c.recentsLongPress, c.backLongPressApp, c.homeLongPressApp,
            c.recentsLongPressApp)
        FEEDBACK -> listOf(c.haptics, c.animations, c.animationMs)
        SCREENSHOTS -> listOf(c.framedScreenshots)
        COMPATIBILITY -> listOf(c.unrestrictCoverApps, c.innerRecentsCompat)
        FULLSCREEN -> listOf(c.fullscreenApps, c.fullscreenScales, c.hideFullscreenButtons, c.trueFullscreenApps)
    }

    /** [target] with only this group's fields taken from [from]; revision and every other group stay as in [target]. */
    fun restore(target: Controls, from: Controls): Controls = when (this) {
        CONTROLS -> target.copy(offsetY = from.offsetY, scale = from.scale, iconScale = from.iconScale, positions = from.positions)
        CAMERA -> target.copy(cameraMainRing = from.cameraMainRing, cameraLowerRing = from.cameraLowerRing,
            cameraNotifications = from.cameraNotifications, cameraBreathing = from.cameraBreathing,
            cameraSecondRing = from.cameraSecondRing, cameraDebugRings = from.cameraDebugRings,
            cameraMainX = from.cameraMainX, cameraMainY = from.cameraMainY, cameraLowerX = from.cameraLowerX,
            cameraLowerY = from.cameraLowerY, cameraMainRadius = from.cameraMainRadius, cameraLowerRadius = from.cameraLowerRadius,
            cameraMainStroke = from.cameraMainStroke, cameraLowerStroke = from.cameraLowerStroke,
            cameraTimerColor = from.cameraTimerColor, cameraProgressColor = from.cameraProgressColor,
            cameraChargeColor = from.cameraChargeColor, cameraTimerBreathColor = from.cameraTimerBreathColor,
            cameraNotificationColor = from.cameraNotificationColor, cameraChargeTarget = from.cameraChargeTarget,
            cameraChargeTargetPercent = from.cameraChargeTargetPercent, visualFocus = from.visualFocus,
            visualFocusHideCount = from.visualFocusHideCount, cameraChargeArrival = from.cameraChargeArrival)
        STATUS -> target.copy(showClock = from.showClock)
        TRIO -> target.copy(showTrio = from.showTrio, statusScale = from.statusScale, trioStroke = from.trioStroke,
            trioWifiScale = from.trioWifiScale, trioWifiStroke = from.trioWifiStroke, trioNetworkScale = from.trioNetworkScale,
            trioNetworkWeight = from.trioNetworkWeight, trioBatteryNumber = from.trioBatteryNumber,
            trioBatteryScale = from.trioBatteryScale, trioBatteryWeight = from.trioBatteryWeight)
        CORNERS -> target.copy(systemCorners = from.systemCorners, symmetricCorners = from.symmetricCorners)
        BEHAVIOR -> target.copy(background = from.background, recentsCrop = from.recentsCrop, recentsStyle = from.recentsStyle,
            recentsCardLongPress = from.recentsCardLongPress, eventIsland = from.eventIsland, islandLock = from.islandLock,
            landscapeCompat = from.landscapeCompat, hideStatus = from.hideStatus, hideCoverCamera = from.hideCoverCamera,
            statusBarHeight = from.statusBarHeight, notificationFullscreen = from.notificationFullscreen,
            hideCoverBottomGuard = from.hideCoverBottomGuard)
        NAVIGATION -> target.copy(backLongPress = from.backLongPress, homeLongPress = from.homeLongPress,
            recentsLongPress = from.recentsLongPress, backLongPressApp = from.backLongPressApp,
            homeLongPressApp = from.homeLongPressApp, recentsLongPressApp = from.recentsLongPressApp)
        FEEDBACK -> target.copy(haptics = from.haptics, animations = from.animations, animationMs = from.animationMs)
        SCREENSHOTS -> target.copy(framedScreenshots = from.framedScreenshots)
        COMPATIBILITY -> target.copy(unrestrictCoverApps = from.unrestrictCoverApps, innerRecentsCompat = from.innerRecentsCompat)
        FULLSCREEN -> target.copy(fullscreenApps = from.fullscreenApps, fullscreenScales = from.fullscreenScales,
            trueFullscreenApps = from.trueFullscreenApps,
            hideFullscreenButtons = from.hideFullscreenButtons)
    }

    companion object {
        fun changed(old: Controls, new: Controls): Set<SettingsGroup> = entries.filterTo(LinkedHashSet()) { it.values(old) != it.values(new) }
    }
}

/**
 * One undo level per group for this app process (survives activity recreation, not process death).
 * A commit records, for each group it touched, the state just before it. Undo restores only that group,
 * goes through the normal save path as a new revision, and is not itself undoable.
 */
internal class SettingsUndo {
    private val before = LinkedHashMap<SettingsGroup, Controls>()

    @Synchronized fun committed(old: Controls, new: Controls) {
        for (group in SettingsGroup.changed(old, new)) before[group] = old
    }

    @Synchronized fun available(): Set<SettingsGroup> = before.keys.toSet()

    /** Restored snapshot for [group], or null when nothing to undo. Consumes the undo point. */
    @Synchronized fun take(group: SettingsGroup, current: Controls): Controls? {
        val previous = before.remove(group) ?: return null
        return group.restore(current, previous)
    }
}
