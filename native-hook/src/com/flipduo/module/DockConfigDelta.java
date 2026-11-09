// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Which parts of NativeDock a config change touches. Every instance field is compared; a changed field
 * that is not categorised below falls back to {@link #FULL}, so a new setting can never be skipped by
 * mistake. Revision equality is never used as a shortcut.
 */
final class DockConfigDelta {
    /** Measure/layout inputs, including fields read by applyMenuMotion() from onLayout(). */
    static final int LAYOUT = 1;
    /** Button action mapping, long-press state and their accessibility labels. */
    static final int ACTIONS = 2;
    /** Draw-only inputs of the header and buttons. */
    static final int REDRAW = 4;
    static final int FULL = LAYOUT | ACTIONS | REDRAW;

    private static final Field[] FIELDS;
    private static final Map<String, Integer> CATEGORY = new HashMap<>();

    static {
        List<Field> fields = new ArrayList<>();
        for (Field f : DockConfig.class.getDeclaredFields()) if (!Modifier.isStatic(f.getModifiers())) fields.add(f);
        fields.sort(Comparator.comparing(Field::getName));
        FIELDS = fields.toArray(new Field[0]);
        for (String name : new String[]{"offsetY", "positions", "islandLock"}) CATEGORY.put(name, LAYOUT);
        CATEGORY.put("scale", LAYOUT | REDRAW);
        CATEGORY.put("eventIsland", FULL);
        for (String name : new String[]{"order", "backLongPress", "homeLongPress", "recentsLongPress",
                "backLongPressApp", "homeLongPressApp", "recentsLongPressApp"}) CATEGORY.put(name, ACTIONS);
        for (String name : new String[]{"iconScale", "statusScale", "showClock", "showTrio", "trioStroke", "trioWifiScale",
                "trioWifiStroke", "trioNetworkScale", "trioNetworkWeight", "trioBatteryNumber", "trioBatteryScale",
                "trioBatteryWeight", "visualFocus", "visualFocusHideCount"}) CATEGORY.put(name, REDRAW);
        // Not read by NativeDock, or only by steps applyConfig() still runs every time
        // (button visibility, appearance rebuild, timers) or read at the moment of use.
        for (String name : new String[]{"enabled", "hideFullscreenButtons", "haptics", "animations", "animationMs", "revision",
                "hideStatus", "systemCorners", "statusBarHeight", "notificationFullscreen", "hideCoverBottomGuard", "framedScreenshots", "landscapeCompat", "background",
                "recentsCrop", "symmetricCorners", "hideCoverCamera", "unrestrictCoverApps", "recentsCardLongPress",
                "recentsStyle", "cameraSecondRing", "cameraMainRing", "cameraLowerRing", "cameraNotifications",
                "cameraBreathing", "cameraDebugRings", "cameraMainStroke", "cameraLowerStroke", "cameraMainX", "cameraMainY",
                "cameraLowerX", "cameraLowerY", "cameraMainRadius", "cameraLowerRadius", "cameraTimerColor",
                "cameraProgressColor", "cameraChargeColor", "cameraTimerBreathColor", "cameraNotificationColor",
                "cameraChargeTarget", "cameraChargeTargetPercent", "cameraChargeArrival"}) CATEGORY.put(name, 0);
    }

    private DockConfigDelta() {}

    /** FULL for the first application. The same instance means no change: configs are never mutated once published. */
    static int between(DockConfig previous, DockConfig next) {
        if (previous == null) return FULL;
        if (previous == next) return 0;
        int out = 0;
        for (Field f : FIELDS) {
            if (Objects.equals(value(f, previous), value(f, next))) continue;
            Integer category = CATEGORY.get(f.getName());
            out |= category == null ? FULL : category;
            if (out == FULL) break;
        }
        return out;
    }

    /** Instance fields without a category; they are treated as FULL. Used by tests and diagnostics. */
    static TreeSet<String> uncategorised() {
        TreeSet<String> out = new TreeSet<>();
        for (Field f : FIELDS) if (!CATEGORY.containsKey(f.getName())) out.add(f.getName());
        return out;
    }

    /** Category of one field, or null when uncategorised (treated as FULL). */
    static Integer category(String name) { return CATEGORY.get(name); }

    static List<String> fieldNames() {
        List<String> out = new ArrayList<>();
        for (Field f : FIELDS) out.add(f.getName());
        return out;
    }

    private static Object value(Field f, DockConfig c) {
        try { return f.get(c); }
        catch (IllegalAccessException error) { return new Object(); } // unreadable: compare unequal, i.e. FULL
    }

}
