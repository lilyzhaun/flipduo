// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

/** Layout selection, deliberately separate from the navigation key's long-press action. */
public enum RecentsStyle {
    SYSTEM("跟随系统"), VERTICAL("纵向"), HORIZONTAL("横向");
    public final String title;
    RecentsStyle(String title) { this.title = title; }
    public static RecentsStyle read(String value) {
        for (RecentsStyle style : values()) if (style.name().equals(value)) return style;
        return SYSTEM;
    }
    static int targetIndex(boolean quickSwitch, int target, int running) {
        return !quickSwitch && target == -1 && running >= 0 ? running : target;
    }
    static float dismissY(float value, float scale) { return scale < .999f ? -Math.abs(value) : value; }
    static int effectiveValue(boolean enabled, RecentsStyle selected, int nativeValue) {
        return !enabled || selected == SYSTEM ? nativeValue : selected == HORIZONTAL ? 1 : 0;
    }
}
