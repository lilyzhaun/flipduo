// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.content.SharedPreferences;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.view.SurfaceControl;
import android.view.WindowManager;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** Native launcher overlay and optional OEM app-bottom exemption in the existing system scope.
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
        hookCompatBottomExemption(module, loader);
        NativeModule.info("Bottom guard rendering policy v130 installed; exact inert launcher overlay");
    }

    private void hookCompatBottomExemption(NativeModule module, ClassLoader loader) {
        try {
            Class<?> service = loader.loadClass("com.android.server.wm.MiuiSizeCompatService");
            Method skip = service.getDeclaredMethod("getSkipBottomInsetsActivities", String.class);
            Field context = field(service, "mContext");
            Method packageInfo = PackageManager.class.getDeclaredMethod("getPackageInfoAsUser", String.class, int.class, int.class);
            skip.setAccessible(true); packageInfo.setAccessible(true);
            module.hook(skip).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object original = chain.proceed();
                if (!enabled) return original;
                String pkg = (String) chain.getArg(0);
                if (pkg == null || pkg.isEmpty()) return original;
                PackageManager pm = ((Context) context.get(chain.getThisObject())).getPackageManager();
                // Android UIDs encode the profile in PER_USER_RANGE (100000); do not query
                // user 0 for a cloned/work-profile caller. This is the OEM's app query, not a scan.
                PackageInfo info;
                try {
                    info = (PackageInfo) packageInfo.invoke(pm, pkg,
                            PackageManager.GET_ACTIVITIES | PackageManager.MATCH_DISABLED_COMPONENTS, Binder.getCallingUid() / 100000);
                } catch (ReflectiveOperationException error) {
                    NativeModule.warning("App bottom exemption package lookup unavailable; OEM result retained", error);
                    return original;
                }
                return bottomExemptions(original, info, pkg);
            });
            // bixi OS4 BoundsCompat caches this per process. Both enabling and disabling
            // require restarting affected apps. The native client retains its display,
            // rotation and size-compat checks, and only omits the synthetic 21px bottom.
            NativeModule.info("Native app bottom exemption available; restart affected apps after toggling");
        } catch (ReflectiveOperationException error) {
            // ruyi OS3 has the 21px branch but no service exemption API. Do not substitute
            // the broader no-insets list: that also removes the original top inset.
            NativeModule.info("Native app bottom exemption unavailable; launcher overlay policy remains active");
        }
    }

    @SuppressWarnings("unchecked")
    static Object bottomExemptions(Object original, PackageInfo info, String pkg) {
        if (!(original instanceof Map<?, ?>) || info == null || info.activities == null) return original;
        Map<String, String> result = new HashMap<>((Map<String, String>) original);
        for (ActivityInfo activity : info.activities)
            if (pkg.equals(activity.packageName) && activity.name != null) result.putIfAbsent(activity.name, "");
        return result;
    }
    private void read() {
        enabled = preferences.getBoolean("enabled", true) && preferences.getBoolean(BottomGuardRules.KEY, false);
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
}
