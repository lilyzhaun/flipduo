// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.content.Context;
import android.view.View;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/** Native factory/gesture contracts checked in both ROMs; behavior reference: MixFlipMod.
 * Apply saved changes at a closed-recents boundary, never rewrite global settings or restart hosts. */
final class CoverRecentsStyle {
    private final Method instance, gesture, getStyle, setStyle, styleValue, updateStack;
    private final Field context;
    private final Class<?> horizontal;
    private final Map<View, Boolean> stacks = new WeakHashMap<>();
    private int systemValue;
    private boolean applying;

    CoverRecentsStyle(NativeModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> base = loader.loadClass("com.miui.fliphome.gesture.BaseGestureImpl");
        Class<?> app = loader.loadClass("com.miui.fliphome.FlipApplication");
        Class<?> style = loader.loadClass("com.miui.fliphome.recents.TaskStackViewLayoutStyle");
        Class<?> stack = loader.loadClass("com.miui.fliphome.recents.views.TaskStackView");
        horizontal = loader.loadClass("com.miui.fliphome.recents.TaskStackViewLayoutStyleHorizontal");
        instance = app.getMethod("getInstance"); gesture = app.getMethod("getBaseGestureImpl");
        getStyle = base.getMethod("getTaskStackViewLayoutStyle"); styleValue = style.getMethod("getStyleValue");
        setStyle = base.getDeclaredMethod("setTaskStackViewLayoutStyle", int.class, Context.class); setStyle.setAccessible(true);
        context = base.getDeclaredField("mContext"); context.setAccessible(true);
        updateStack = stack.getDeclaredMethod("updateLayoutStyle"); updateStack.setAccessible(true);
        module.hook(setStyle).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            if (!applying) systemValue = (int) chain.getArg(0);
            int desired = selected(systemValue);
            return chain.proceed(new Object[]{desired, chain.getArg(1)});
        });
        module.hook(style.getDeclaredMethod("create", int.class, Context.class))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    int desired = selected((int) chain.getArg(0));
                    return desired == (int) chain.getArg(0) ? chain.proceed() : chain.proceed(new Object[]{desired, chain.getArg(1)});
                });
        module.hook(stack.getDeclaredConstructor(Context.class)).intercept(chain -> {
            Object result = chain.proceed(); stacks.put((View) chain.getThisObject(), true); return result;
        });
        module.hook(stack.getDeclaredMethod("setVisibility", int.class)).intercept(chain -> {
            View view = (View) chain.getThisObject();
            // Update before making a hidden stack visible, so its first frame uses matching scroller/algorithm.
            if ((int) chain.getArg(0) == View.VISIBLE && !view.isShown()) refresh();
            Object result = chain.proceed();
            if ((int) chain.getArg(0) != View.VISIBLE) refresh();
            return result;
        });
        try {
            Class<?> algorithm = loader.loadClass("com.miui.fliphome.recents.views.TaskStackViewsAlgorithmHorizontal");
            Class<?> swipe = loader.loadClass("com.miui.fliphome.recents.views.VerticalSwipe");
            Field scale = swipe.getDeclaredField("mCurScale"); scale.setAccessible(true);
            module.hook(algorithm.getDeclaredMethod("getTargetTaskViewIndex", boolean.class, int.class, int.class))
                    .intercept(chain -> {
                        if (activeHorizontal()) {
                            int fixed = RecentsStyle.targetIndex((boolean) chain.getArg(0), (int) chain.getArg(1), (int) chain.getArg(2));
                            if (fixed != (int) chain.getArg(1)) return fixed;
                        }
                        return chain.proceed();
                    });
            module.hook(swipe.getDeclaredMethod("getCurTransY")).intercept(chain -> {
                float value = (float) chain.proceed();
                return activeHorizontal() ? RecentsStyle.dismissY(value, scale.getFloat(chain.getThisObject())) : value;
            });
        } catch (ReflectiveOperationException error) { NativeModule.warning("Horizontal recents gesture corrections unavailable", error); }
    }
    private int selected(int nativeValue) {
        DockConfig config = NativeModule.config();
        return RecentsStyle.effectiveValue(config.enabled, config.recentsStyle, nativeValue);
    }
    private Object currentGesture() throws ReflectiveOperationException {
        Object app = instance.invoke(null); return app == null ? null : gesture.invoke(app);
    }
    boolean activeHorizontal() throws ReflectiveOperationException {
        if (!NativeModule.config().enabled) return false;
        Object current = currentGesture(); return current != null && horizontal.isInstance(getStyle.invoke(current));
    }
    void refresh() {
        if (applying) return;
        for (View stack : new ArrayList<>(stacks.keySet()))
            if (stack.isAttachedToWindow() && stack.isShown()) return; // Complete the current gesture/menu first.
        try {
            Object current = currentGesture(); if (current == null) return;
            Object before = getStyle.invoke(current);
            if (before == null || (int) styleValue.invoke(before) == selected(systemValue)) return;
            applying = true;
            try { setStyle.invoke(current, selected(systemValue), context.get(current)); }
            finally { applying = false; }
            for (View stack : new ArrayList<>(stacks.keySet())) { updateStack.invoke(stack); stack.requestLayout(); }
        } catch (ReflectiveOperationException error) { NativeModule.warning("Could not apply saved recents style at the native layout boundary", error); }
    }
}
