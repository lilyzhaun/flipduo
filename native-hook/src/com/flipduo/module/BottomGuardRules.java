// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.view.Gravity;
import android.view.WindowManager;

/** Identity comes from the captured WMS window, never a screen-edge rectangle heuristic. */
public final class BottomGuardRules {
    public static final String KEY = "hide_cover_bottom_guard";
    public static final long POLICY_VERSION = 127;
    static boolean target(String owner, CharSequence title, int type, int flags, int gravity,
                          int width, int height, boolean keyguard) {
        int inert = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        return !keyguard && "com.miui.home".equals(owner) && "AntiMistakeTouchView".contentEquals(title == null ? "" : title)
                && type == WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY && (flags & inert) == inert
                && (gravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM
                && CutoutAppConfig.isCoverDisplay(width, height);
    }
    private BottomGuardRules() { }
}
