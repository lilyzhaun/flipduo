package com.flipduo.module;

import android.content.SharedPreferences;

/** Validated once at the remote preference boundary. Units are physical cover pixels or percentages. */
public final class DockConfig {
    public static final String GROUP = "flipduo_controls";
    public static final int CAMERA_X_MIN = -24, CAMERA_X_MAX = 24, CAMERA_Y_MIN = -48, CAMERA_Y_MAX = 26;
    public static final int CAMERA_RADIUS_MIN = 140, CAMERA_RADIUS_MAX = 200, CAMERA_RADIUS_DEFAULT = 166;
    public static final int CAMERA_STROKE_MIN = 2, CAMERA_STROKE_MAX = 12, CAMERA_STROKE_DEFAULT = 6;
    public boolean enabled = true, hideStatus = true, haptics = true, animations = true, showClock = true, showTrio = true, systemCorners = true;
    public int offsetY = 12, scale = 96, iconScale = 100, statusScale = 100, animationMs = 200, revision;
    public String order = "BACK,HOME,RECENTS,NOTIFICATIONS,CONTROL_CENTER,MORE";
    public String positions = new DockLayout().encode();
    public int statusBarHeight = 64;
    public boolean notificationFullscreen = false;
    public boolean hideCoverBottomGuard = false;
    public String coverInputMethod = "";
    public int coverInputMethodUser = -1;
    public boolean framedScreenshots = true;
    public boolean eventIsland = true;
    public boolean islandLock = true;
    public boolean landscapeCompat = true;
    public CoverBackground background = CoverBackground.BLACK;
    public boolean recentsCrop = true;
    public boolean symmetricCorners = false;
    public NavigationLongPress backLongPress = NavigationLongPress.NONE, homeLongPress = NavigationLongPress.NONE;
    public NavigationLongPress recentsLongPress = NavigationLongPress.MINI_WINDOW;
    public String backLongPressApp = "", homeLongPressApp = "", recentsLongPressApp = "";
    public boolean cameraSecondRing = false;
    public boolean cameraMainRing = true, cameraLowerRing = true;
    public boolean cameraNotifications = true, cameraBreathing = true;
    public boolean cameraDebugRings = false;
    public int cameraMainStroke = CAMERA_STROKE_DEFAULT, cameraLowerStroke = CAMERA_STROKE_DEFAULT;
    public int cameraMainX, cameraMainY, cameraLowerX, cameraLowerY;
    public int cameraMainRadius = CAMERA_RADIUS_DEFAULT, cameraLowerRadius = CAMERA_RADIUS_DEFAULT;
    public int cameraTimerColor = CameraColors.TIMER, cameraProgressColor = CameraColors.PROGRESS;
    public int cameraChargeColor = CameraColors.CHARGE, cameraTimerBreathColor = CameraColors.TIMER;
    public int cameraNotificationColor = CameraColors.NOTIFICATION;
    public int trioStroke = 100, trioWifiScale = 100, trioWifiStroke = 100;
    public int trioNetworkScale = 100, trioNetworkWeight = 100;
    public boolean trioBatteryNumber = true;
    public int trioBatteryScale = 100, trioBatteryWeight = 100;
    public boolean hideCoverCamera = false;
    public boolean hideFullscreenButtons = false, unrestrictCoverApps = false, recentsCardLongPress = false;
    /** F03: optional target tick on the lower (charging) ring; off by default, display only. */
    public boolean cameraChargeTarget = false;
    public int cameraChargeTargetPercent = ChargeTarget.DEFAULT;
    /** F03 step 2: one light confirmation when the target is first reached in a plug-in session; off by default. */
    public boolean cameraChargeArrival = false;
    /** F04: quiet ordinary new-notification breathing; off by default, presentation only. */
    public boolean visualFocus = false;
    /** F04 option: while focused, hide the island's notification number (entry stays usable). */
    public boolean visualFocusHideCount = false;
    public RecentsStyle recentsStyle = RecentsStyle.SYSTEM;

    boolean hidesCoverCameraShortcut() { return enabled && hideCoverCamera; }

    NavigationLongPress longPress(DockSpec.Action key) {
        return switch (key) {
            case BACK -> backLongPress;
            case HOME -> homeLongPress;
            case RECENTS -> recentsLongPress;
            default -> NavigationLongPress.NONE;
        };
    }
    String longPressApp(DockSpec.Action key) {
        return switch (key) {
            case BACK -> backLongPressApp;
            case HOME -> homeLongPressApp;
            case RECENTS -> recentsLongPressApp;
            default -> "";
        };
    }

    static DockConfig read(SharedPreferences prefs) {
        DockConfig c = new DockConfig();
        c.enabled = prefs.getBoolean("enabled", true); c.hideStatus = prefs.getBoolean("hide_status", true);
        c.symmetricCorners = prefs.getBoolean("symmetric_corners", false);
        c.haptics = prefs.getBoolean("haptics", true); c.animations = prefs.getBoolean("animations", true);
        c.showClock = prefs.getBoolean("show_clock", true); c.showTrio = prefs.getBoolean("show_trio", true);
        c.hideCoverCamera = prefs.getBoolean("hide_cover_camera", false);
        c.hideFullscreenButtons = prefs.getBoolean("hide_fullscreen_buttons", false);
        c.unrestrictCoverApps = prefs.getBoolean("unrestrict_cover_apps", false);
        c.recentsCardLongPress = prefs.getBoolean("recents_card_long_press", false);
        c.cameraChargeTarget = prefs.getBoolean("camera_charge_target", false);
        c.cameraChargeTargetPercent = ChargeTarget.clamp(prefs.getInt("camera_charge_target_percent", ChargeTarget.DEFAULT));
        c.cameraChargeArrival = prefs.getBoolean("camera_charge_arrival", false);
        c.visualFocus = prefs.getBoolean("visual_focus", false);
        c.visualFocusHideCount = prefs.getBoolean("visual_focus_hide_count", false);
        c.recentsStyle = RecentsStyle.read(prefs.getString("recents_style", null));
        c.offsetY = bound(prefs.getInt("offset_y", 12), 0, 14); c.scale = bound(prefs.getInt("scale", 96), 80, 100);
        c.iconScale = bound(prefs.getInt("icon_scale", 100), 70, 130); c.statusScale = bound(prefs.getInt("status_scale", 100), 80, 120);
        c.trioStroke = bound(prefs.getInt("trio_stroke", 100), 60, 140);
        c.trioWifiScale = bound(prefs.getInt("trio_wifi_scale", 100), 70, 130);
        c.trioWifiStroke = bound(prefs.getInt("trio_wifi_stroke", 100), 60, 140);
        c.trioNetworkScale = bound(prefs.getInt("trio_network_scale", 100), 70, 140);
        c.trioNetworkWeight = bound(prefs.getInt("trio_network_weight", 100), 70, 130);
        c.trioBatteryNumber = prefs.getBoolean("trio_battery_number", true);
        c.trioBatteryScale = bound(prefs.getInt("trio_battery_scale", 100), 70, 130);
        c.trioBatteryWeight = bound(prefs.getInt("trio_battery_weight", 100), 70, 130);
        c.animationMs = bound(prefs.getInt("animation_ms", 200), 120, 280);
        c.systemCorners = prefs.contains("system_corners") ? prefs.getBoolean("system_corners", true) : prefs.getInt("corner_radius", 32) != 0;
        c.positions = DockLayout.decode(prefs.getString("positions", "")).encode();
        c.revision = prefs.getInt("revision", 0);
        c.statusBarHeight = bound(prefs.getInt("status_bar_height", 64), 48, 114);
        c.notificationFullscreen = prefs.getBoolean("notification_fullscreen", false);
        c.hideCoverBottomGuard = prefs.getBoolean("hide_cover_bottom_guard", false);
        c.coverInputMethod = prefs.getString(CoverInputMethodConfig.KEY, "");
        c.coverInputMethodUser = prefs.getInt(CoverInputMethodConfig.USER_KEY, -1);
        c.framedScreenshots = prefs.getBoolean("framed_screenshots", true);
        c.eventIsland = prefs.getBoolean("event_island", true);
        c.islandLock = prefs.getBoolean("island_lock", true);
        c.landscapeCompat = prefs.getBoolean("landscape_compat", true);
        c.background = CoverBackground.read(prefs);
        c.recentsCrop = prefs.getBoolean("recents_crop", c.background == CoverBackground.BLACK);
        c.cameraSecondRing = prefs.getBoolean("camera_second_ring", false);
        c.cameraMainRing = prefs.getBoolean("camera_main_ring", true);
        c.cameraLowerRing = prefs.getBoolean("camera_lower_ring", true);
        c.cameraNotifications = prefs.getBoolean("camera_notifications", true);
        c.cameraBreathing = prefs.getBoolean("camera_breathing", true);
        c.cameraDebugRings = prefs.getBoolean("camera_debug_rings", false);
        c.cameraMainStroke = bound(prefs.getInt("camera_main_stroke", CAMERA_STROKE_DEFAULT), CAMERA_STROKE_MIN, CAMERA_STROKE_MAX);
        c.cameraLowerStroke = bound(prefs.getInt("camera_lower_stroke", CAMERA_STROKE_DEFAULT), CAMERA_STROKE_MIN, CAMERA_STROKE_MAX);
        c.cameraMainX = bound(prefs.getInt("camera_main_x", 0), CAMERA_X_MIN, CAMERA_X_MAX);
        c.cameraMainY = bound(prefs.getInt("camera_main_y", 0), CAMERA_Y_MIN, CAMERA_Y_MAX);
        c.cameraLowerX = bound(prefs.getInt("camera_lower_x", 0), CAMERA_X_MIN, CAMERA_X_MAX);
        c.cameraLowerY = bound(prefs.getInt("camera_lower_y", 0), CAMERA_Y_MIN, CAMERA_Y_MAX);
        c.cameraMainRadius = bound(prefs.getInt("camera_main_radius", CAMERA_RADIUS_DEFAULT), CAMERA_RADIUS_MIN, CAMERA_RADIUS_MAX);
        c.cameraLowerRadius = bound(prefs.getInt("camera_lower_radius", CAMERA_RADIUS_DEFAULT), CAMERA_RADIUS_MIN, CAMERA_RADIUS_MAX);
        c.cameraTimerColor = prefs.getInt("camera_timer_color", CameraColors.TIMER);
        c.cameraProgressColor = prefs.getInt("camera_progress_color", CameraColors.PROGRESS);
        c.cameraChargeColor = prefs.getInt("camera_charge_color", CameraColors.CHARGE);
        c.cameraTimerBreathColor = prefs.getInt("camera_timer_breath_color", CameraColors.TIMER);
        c.cameraNotificationColor = prefs.getInt("camera_notification_color", CameraColors.NOTIFICATION);
        c.backLongPress = NavigationLongPress.parse(prefs.getString("long_press_back", null), c.backLongPress);
        c.homeLongPress = NavigationLongPress.parse(prefs.getString("long_press_home", null), c.homeLongPress);
        c.recentsLongPress = NavigationLongPress.parse(prefs.getString("long_press_recents", null), c.recentsLongPress);
        c.backLongPressApp = NavigationLongPress.appPackage(prefs.getString("long_press_back_app", ""));
        c.homeLongPressApp = NavigationLongPress.appPackage(prefs.getString("long_press_home_app", ""));
        c.recentsLongPressApp = NavigationLongPress.appPackage(prefs.getString("long_press_recents_app", ""));
        String order = prefs.getString("order", c.order);
        if (validOrder(order)) c.order = order;
        return c;
    }
    private static int bound(int v, int low, int high) { return Math.max(low, Math.min(high, v)); }
    static boolean validOrder(String order) {
        if (order == null) return false;
        String[] values = order.split(","); if (values.length != 6) return false;
        java.util.HashSet<String> allowed = new java.util.HashSet<>(java.util.Arrays.asList("BACK", "HOME", "RECENTS", "NOTIFICATIONS", "CONTROL_CENTER", "MORE"));
        for (String value : values) if (!allowed.remove(value)) return false;
        return allowed.isEmpty();
    }
}
