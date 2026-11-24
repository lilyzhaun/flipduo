// SPDX-License-Identifier: AGPL-3.0-only
// Adapted from HideOuterHook.kt by parallelcc, MixFlipMod commit 6b02e8cec6497efd2a07b9ba1307fb9c2f979867.
// https://github.com/parallelcc/MixFlipMod
package com.flipduo.module;

import android.content.ContentResolver;
import android.content.SharedPreferences;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;

/** Installed only in a selected app. Never writes posture, system properties or display configuration. */
final class HideOuterHook implements SharedPreferences.OnSharedPreferenceChangeListener {
    private final HideOuterApp app;
    private volatile boolean enabled;

    HideOuterHook(HideOuterApp app) { this.app = app; }

    void install(XposedInterface module, ClassLoader loader, SharedPreferences prefs) {
        if (!app.enabled(prefs)) return; // MIUIX caches its result in <clinit>; opt-in takes effect on app restart.
        prefs.registerOnSharedPreferenceChangeListener(this);
        enabled = app.enabled(prefs);
        optional(module, loader, "android.provider.Settings$Global", "getInt", chain -> {
            Object result = chain.proceed();
            return enabled && "device_posture".equals(chain.getArg(1)) && Integer.valueOf(1).equals(result) ? 3 : result;
        }, ContentResolver.class, String.class, int.class);
        optional(module, loader, "android.hardware.devicestate.DeviceStateManager", "getCurrentState", chain -> {
            Object result = chain.proceed();
            return enabled && Integer.valueOf(0).equals(result) ? 3 : result;
        });
        optional(module, loader, "miuix.core.util.SystemProperties", "getInt", chain ->
                enabled && "persist.sys.multi_display_type".equals(chain.getArg(0)) ? 1 : chain.proceed(), String.class, int.class);
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (key == null || HideOuterApp.KEY.equals(key) || "enabled".equals(key)) enabled = app.enabled(prefs);
    }
    private void optional(XposedInterface module, ClassLoader loader, String owner, String name,
                          XposedInterface.Hooker hooker, Class<?>... params) {
        try {
            Method method = Class.forName(owner, false, loader).getDeclaredMethod(name, params);
            if (method.getReturnType() != int.class) return;
            module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(hooker);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError missing) {
            NativeLog.warning(app.pkg + ": unavailable outer-layout reader " + owner + "." + name, missing);
        }
    }
}
