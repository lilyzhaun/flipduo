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

/** Reconcile the separate main-panel material sink when a detail route has actually completed. */
final class CoverControlCenterReturn {
    private static final Set<ClassLoader> installed = Collections.newSetFromMap(new WeakHashMap<>());

    static void install(NativeModule module, ClassLoader loader) throws ReflectiveOperationException {
        if (installed.contains(loader)) return;
        Class<?> router = loader.loadClass("miui.systemui.controlcenter.panel.SecondaryPanelRouter");
        Class<?> detail = loader.loadClass("miui.systemui.controlcenter.panel.secondary.detail.DetailPanelController");
        Class<?> main = loader.loadClass("miui.systemui.controlcenter.panel.main.MainPanelAnimController");
        Method complete = router.getDeclaredMethod("performComplete");
        Method routeState = router.getMethod("getRoutingState");
        int routing = field(router, "ROUTING_STATE_ROUTING").getInt(null);
        int idle = field(router, "ROUTING_STATE_IDLE").getInt(null);
        Field current = field(router, "current"), mainPanel = field(router, "mainPanelAnimController");
        Field container = field(main, "mainPanelContainer"), blur = field(main, "blurUtilsExt");
        Method getView = main.getMethod("getView");
        Method applyAlphaScale = blur.getType().getMethod("applyAlphaScale", View.class, float.class);
        module.hook(complete).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object owner = chain.getThisObject();
            boolean fromDetail = detail.isInstance(current.get(owner)) && (int) routeState.invoke(owner) == routing;
            Object result = chain.proceed();
            // performComplete also runs while either animation is pending, and its
            // completion action can start another route. Inspect the final router state.
            if (!fromDetail || !NativeModule.config().enabled || (int) routeState.invoke(owner) != idle) return result;
            Object animator = mainPanel.get(owner);
            if (current.get(owner) != animator) return result;
            View content = (View) container.get(animator);
            Display display = content.getDisplay();
            if (display == null || !content.isAttachedToWindow()) return result;
            DisplayMetrics metrics = new DisplayMetrics(); display.getRealMetrics(metrics);
            if (!CutoutAppConfig.isCoverDisplay(metrics.widthPixels, metrics.heightPixels)) return result;
            View target = (View) getView.invoke(animator);
            // Same terminal values and native sink as MainPanelAnimController.forceToShow.
            // onShown only releases layout / visibility / accessibility; the material
            // otherwise depends on the hidden header's last onAnimUpdate callback.
            if (target.getAlpha() != 1f) target.setAlpha(1f);
            if (content.getAlpha() != 1f || content.getScaleX() != 1f || content.getScaleY() != 1f)
                applyAlphaScale.invoke(blur.get(animator), content, 1f);
            return result;
        });
        installed.add(loader);
        NativeModule.info("Cover detail return: native main-panel alpha/scale reconciled after completed routing");
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field result = type.getDeclaredField(name); result.setAccessible(true); return result;
    }
}
