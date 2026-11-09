// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.SurfaceControl;
import android.view.WindowManager;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Rendering-only handling of the native launcher overlay in the existing system scope.
 * The launcher has no DEX on bixi OS4; hooking a Java View there would miss this window. */
final class CoverBottomGuardPolicy {
    private final SharedPreferences preferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener;
    private volatile boolean enabled;

    CoverBottomGuardPolicy(NativeModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> animator = loader.loadClass("com.android.server.wm.WindowStateAnimator");
        Class<?> window = loader.loadClass("com.android.server.wm.WindowState");
        Field win = field(animator, "mWin"), alpha = field(animator, "mAlpha"), policy = field(animator, "mPolicy");
        Field attrs = field(window, "mAttrs");
        Method prepare = animator.getDeclaredMethod("prepareSurfaceLocked", SurfaceControl.Transaction.class);
        Method owner = window.getDeclaredMethod("getOwningPackage"); owner.setAccessible(true);
        Method locked = policy.getType().getMethod("isKeyguardLocked");
        Method display = loader.loadClass("com.android.server.wm.WindowContainer").getDeclaredMethod("getDisplayContent"); display.setAccessible(true);
        Method configuration = loader.loadClass("com.android.server.wm.ConfigurationContainer").getDeclaredMethod("getConfiguration"); configuration.setAccessible(true);
        Field wc = field(Configuration.class, "windowConfiguration");
        Method bounds = wc.getType().getDeclaredMethod("getBounds"); bounds.setAccessible(true);
        preferences = module.getRemotePreferences(DockConfig.GROUP);
        listener = (prefs, key) -> {
            if (key == null || "enabled".equals(key) || BottomGuardRules.KEY.equals(key)) read();
        };
        preferences.registerOnSharedPreferenceChangeListener(listener); read();
        module.hook(prepare).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            if (!enabled) return chain.proceed();
            Object current = chain.getThisObject(), w = win.get(current);
            WindowManager.LayoutParams p = (WindowManager.LayoutParams) attrs.get(w);
            if (p.type != WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY || !"AntiMistakeTouchView".contentEquals(p.getTitle())) return chain.proceed();
            Object d = display.invoke(w); if (d == null) return chain.proceed();
            Rect frame = (Rect) bounds.invoke(wc.get(configuration.invoke(d)));
            if (!BottomGuardRules.target((String) owner.invoke(w), p.getTitle(), p.type, p.flags, p.gravity,
                    frame.width(), frame.height(), (boolean) locked.invoke(policy.get(current)))) return chain.proceed();
            // Both ROMs copy mAlpha to mShownAlpha and emit setAlpha here. Restore the native
            // input even on failure; attrs, visibility and input regions remain native.
            float original = alpha.getFloat(current);
            alpha.setFloat(current, 0f);
            try { return chain.proceed(); }
            finally { alpha.setFloat(current, original); }
        });
        NativeModule.info("Bottom guard rendering policy v127 installed; exact inert launcher overlay only");
    }
    private void read() {
        enabled = preferences.getBoolean("enabled", true) && preferences.getBoolean(BottomGuardRules.KEY, false);
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
}
