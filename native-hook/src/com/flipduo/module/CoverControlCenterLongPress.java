// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.util.DisplayMetrics;
import android.view.Display;
import android.view.View;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Restore the existing QSRecord action that the OEM COMPACT tile listener skips. */
final class CoverControlCenterLongPress {
    private static final Set<ClassLoader> installed = Collections.newSetFromMap(new WeakHashMap<>());

    static void install(NativeModule module, ClassLoader loader) throws ReflectiveOperationException {
        if (installed.contains(loader)) return;
        Class<?> tile = loader.loadClass("miui.systemui.controlcenter.qs.tileview.QSTileItemView");
        Class<?> panel = loader.loadClass("miui.systemui.controlcenter.panel.main.MainPanelController");
        Method longClick = tile.getDeclaredMethod("onFinishInflate$lambda$2", tile, View.class);
        longClick.setAccessible(true);
        Method inflate = tile.getDeclaredMethod("onFinishInflate");
        Method getPanel = tile.getMethod("getMainPanelController"), getStyle = panel.getMethod("getStyle");
        Field action = field(tile, "longClickAction"), mode = field(tile, "mode");
        Method invoke = action.getType().getMethod("invoke", Object.class);
        Object normal = mode.getType().getField("NORMAL").get(null);
        Object compact = getStyle.getReturnType().getField("COMPACT").get(null);
        module.hook(inflate).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            View view = (View) chain.getThisObject();
            // Bind after the OEM listener is created. Hooking its tiny synthetic bridge
            // alone can be bypassed by ART inlining; touch recognition stays with View.
            view.setOnLongClickListener(clicked -> {
                try {
                    if (eligible(view) && mode.get(view) == normal
                            && getStyle.invoke(getPanel.invoke(view)) == compact) {
                        Object callback = action.get(view);
                        // QSRecord retains haptics, detail-vs-settings and suppressLayout checks.
                        if (callback != null) return Boolean.TRUE.equals(invoke.invoke(callback, clicked));
                    }
                    return Boolean.TRUE.equals(longClick.invoke(null, view, clicked));
                } catch (ReflectiveOperationException error) {
                    NativeModule.warning("Cover control-center long-press dispatch failed", error);
                    return true; // Do not turn a failed long action into a short-click toggle.
                }
            });
            return result;
        });
        installed.add(loader);
        NativeModule.info("Cover control-center long press: native COMPACT tile action restored");
    }

    private static boolean eligible(View view) {
        if (view == null || !NativeModule.config().enabled) return false;
        Display display = view.getDisplay();
        if (display == null) return false;
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        return CutoutAppConfig.isCoverDisplay(metrics.widthPixels, metrics.heightPixels);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field result = type.getDeclaredField(name); result.setAccessible(true); return result;
    }
}
