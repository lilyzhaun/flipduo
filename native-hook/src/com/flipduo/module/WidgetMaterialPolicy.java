// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

/**
 * When the stock widget cards must use the OEM's own no-blur material.
 *
 * In app scenes the OEM blurs whatever lies behind the FlipHome window and only adds translucent
 * tints (#33ADADAD / #1FBFBFBF). Under a module-owned cover background (black or blur fill) the slot
 * has nothing behind the window — the app is letterboxed to the left, the letterbox surfaces are
 * offscreen and our fill lives inside FlipHome's own window state — so the sample is empty and the
 * tint lands on black (#171717, black text). The OEM's isSupportBlur()==false branch is the native
 * design for "no blur source": opaque #F5F5F5 / #1C1C1C cards with matching text. The desktop
 * (wallpaper behind) and the native "system" background keep the OEM blur unchanged.
 */
final class WidgetMaterialPolicy {
    private WidgetMaterialPolicy() {}

    static boolean noBlurSource(boolean enabled, CoverBackground background, boolean desk) {
        return enabled && background != CoverBackground.SYSTEM && !desk;
    }
}
