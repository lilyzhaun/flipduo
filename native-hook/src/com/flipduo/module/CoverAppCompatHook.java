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
        try { installInterceptionCache(module, loader); }
        catch (ReflectiveOperationException error) {
            NativeModule.warning("Cover eligibility cache contract unavailable; property queries remain installed", error);
        }
        NativeModule.info("Cover eligibility v159 installed: integer 5/1 and app/activity presence queries");
    }
    private void installInterceptionCache(NativeModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> task = loader.loadClass("com.android.server.wm.AppCompatTask");
        Method has = task.getDeclaredMethod("hasActivityInterceptionKey", String.class);
        Method put = task.getDeclaredMethod("setActivityInterception", String.class, boolean.class);
        Method read = task.getDeclaredMethod("isActivityInterception", String.class);
        Method notify = loader.loadClass("com.android.server.wm.ContinuityPolicyService")
                .getDeclaredMethod("notifyActivityIntercept", task, ComponentName.class);
        Method start = null;
        for (Method method : loader.loadClass("com.android.server.wm.InterceptActivityController").getDeclaredMethods()) {
            if (!method.getName().equals("startActivityInnerBlockedWithSnapShot")) continue;
            if (start != null || method.getReturnType() != boolean.class)
                throw new NoSuchMethodException("Ambiguous startActivityInnerBlockedWithSnapShot contract");
            start = method;
        }
        if (start == null) throw new NoSuchMethodException("startActivityInnerBlockedWithSnapShot");
        // These tiny cache accessors can be inlined in the verified ROM callers.
        // Resolve/deoptimize all callers before installing either half of the cache policy.
        for (Method caller : new Method[]{start, read, notify})
            if (!module.deoptimize(caller)) throw new ReflectiveOperationException("Cannot deoptimize " + caller.getName());
        // startActivityInnerBlockedWithSnapShot consults this process-wide cache BEFORE
        // the four property queries. Force a fresh decision while enabled, including
        // pre-existing system-app entries, without clearing the OEM's original map.
        module.hook(has).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            if (enabled) return Boolean.FALSE;
            return chain.proceed();
        });
        // Temporary allow results must not survive disabling the feature.
        module.hook(put).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            if (enabled) return null;
            return chain.proceed();
        });
        NativeModule.info("Cover eligibility: interception cache bypass and temporary-write suppression installed");
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
