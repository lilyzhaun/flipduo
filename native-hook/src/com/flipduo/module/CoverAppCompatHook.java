// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.content.SharedPreferences;
import android.content.ComponentName;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;

/** Feature reference: parallelcc/MixFlipMod (AGPL-3.0), SystemHook.hookCompatConfig.
 * Independent implementation of the actual ruyi OS3 / bixi OS4 ApplicationCompatManager contract.
 * These are INTEGER policy queries, not synthetic PackageManager boolean manifest properties. */
final class CoverAppCompatHook {
    private final SharedPreferences preferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener;
    private volatile boolean enabled;

    CoverAppCompatHook(NativeModule module, ClassLoader loader) throws ReflectiveOperationException {
        Method[] queries = methods(loader.loadClass("com.android.server.wm.ApplicationCompatManager"));
        preferences = module.getRemotePreferences(DockConfig.GROUP);
        readEnabled();
        listener = (prefs, key) -> {
            if (key == null || "enabled".equals(key) || "unrestrict_cover_apps".equals(key)) readEnabled();
        };
        preferences.registerOnSharedPreferenceChangeListener(listener);
        for (Method query : queries)
            module.hook(query).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Integer value = enabled ? CoverAppCompatRules.value((String) chain.getArg(0)) : null;
                if (value == null || chain.getArg(1) == null) return chain.proceed();
                if (chain.getArg(1) instanceof String target && target.isEmpty()) return chain.proceed();
                if (query.getReturnType() == boolean.class) return Boolean.TRUE;
                return value;
            });
        NativeModule.info("Cover eligibility v123 installed: four ApplicationCompatManager policy queries; integer 5/1, app/activity presence");
    }
    private void readEnabled() { enabled = preferences.getBoolean("enabled", true) && preferences.getBoolean("unrestrict_cover_apps", false); }
    static Method[] methods(Class<?> type) throws NoSuchMethodException {
        return new Method[]{
                type.getDeclaredMethod("getPropertyIntByApplication", String.class, String.class),
                type.getDeclaredMethod("getPropertyIntByActivity", String.class, ComponentName.class),
                type.getDeclaredMethod("hasPropertyByApplication", String.class, String.class),
                type.getDeclaredMethod("hasPropertyByActivity", String.class, ComponentName.class)};
    }
}
