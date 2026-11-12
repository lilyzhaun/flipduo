// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.content.SharedPreferences;
import android.view.inputmethod.InputMethodInfo;
import java.util.List;

/** Exact IME service, scoped to the Android user who made the choice. Empty means OEM policy. */
public final class CoverInputMethodConfig {
    public static final String KEY = "cover_input_method", USER_KEY = "cover_input_method_user";
    public static final int POLICY_VERSION = 149;
    final boolean enabled;
    final String id;
    final int user;

    private CoverInputMethodConfig(boolean enabled, String id, int user) {
        this.enabled = enabled; this.id = id; this.user = user;
    }

    static CoverInputMethodConfig read(SharedPreferences prefs) {
        return new CoverInputMethodConfig(prefs.getBoolean("enabled", true),
                prefs.getString(KEY, ""), prefs.getInt(USER_KEY, -1));
    }

    /** Shared by the picker and framework policy. Withdrawal never rewrites a saved choice or system settings. */
    public static boolean isSelectable(String id) {
        return id != null && !id.isEmpty() && !"com.tencent.wetype".equals(id) && !id.startsWith("com.tencent.wetype/");
    }

    boolean appliesTo(int currentUser) { return enabled && isSelectable(id) && user >= 0 && user == currentUser; }

    String resolve(int currentUser, List<InputMethodInfo> enabledMethods) {
        if (!appliesTo(currentUser)) return null;
        for (InputMethodInfo method : enabledMethods) if (id.equals(method.getId())) return id;
        return null; // Removed/disabled services use the original OEM policy without overwriting the saved choice.
    }
}
