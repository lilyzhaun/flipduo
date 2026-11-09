package com.flipduo.module;

import android.content.SharedPreferences;

/** Separate optional policy; absent settings retain the user's accepted 0.6.22 behavior. */
public final class InnerRecentsConfig {
    public static final String KEY = "inner_recents_compat";
    public static final long TOGGLE_POLICY_VERSION = 34;
    private InnerRecentsConfig() { }
    public static boolean read(SharedPreferences preferences) { return preferences.getBoolean(KEY, true); }
}
