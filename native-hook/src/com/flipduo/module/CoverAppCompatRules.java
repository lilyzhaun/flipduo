// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

/** Only the cover eligibility declaration; not an app fullscreen/scale/inner-display policy. */
public final class CoverAppCompatRules {
    public static final int POLICY_VERSION = 159;
    static final String CONTINUITY = "miui.continuity.policy";
    static final String SMALL_COVER = "android.window.PROPERTY_COMPAT_ALLOW_SMALL_COVER_SCREEN";
    static Integer value(String property) {
        if (CONTINUITY.equals(property)) return 5;
        if (SMALL_COVER.equals(property)) return 1;
        return null;
    }
    private CoverAppCompatRules() { }
}
