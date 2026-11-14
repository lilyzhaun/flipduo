// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.graphics.Insets;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Size only native QS detail panels, using the current display's camera-safe column. */
final class CoverControlCenterDetail {
    private static final Set<ClassLoader> installed = Collections.newSetFromMap(new WeakHashMap<>());
    private static final WeakHashMap<ViewGroup, State> panels = new WeakHashMap<>();

    static void install(NativeModule module, ClassLoader loader) throws ReflectiveOperationException {
        if (installed.contains(loader)) return;
        Class<?> controller = loader.loadClass("miui.systemui.controlcenter.panel.secondary.detail.DetailPanelController");
        Class<?> delegate = loader.loadClass("miui.systemui.controlcenter.panel.secondary.detail.DetailPanelDelegate");
        Class<?> params = loader.loadClass("miui.systemui.controlcenter.panel.secondary.DetailPanelParams");
        Method getContainer = controller.getMethod("getContainer");
        Class<?> maxHeight = loader.loadClass("miui.systemui.controlcenter.widget.MaxHeightLinearLayout");
        Method getMax = maxHeight.getMethod("getMaxHeight");
        Method setMax = maxHeight.getMethod("setMaxHeight", int.class);
        Field binding = delegate.getDeclaredField("binding"); binding.setAccessible(true);
        Field detailContainer = binding.getType().getField("detailContainer");
        Class<?> lp = loader.loadClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams");
        String[] anchors = {"leftToLeft", "rightToRight", "topToTop", "bottomToBottom",
                "leftToRight", "rightToLeft", "topToBottom", "bottomToTop",
                "startToStart", "startToEnd", "endToStart", "endToEnd"};
        Field[] fields = new Field[anchors.length];
        for (int i = 0; i < anchors.length; i++) fields[i] = lp.getField(anchors[i]);
        Method create = controller.getDeclaredMethod("onCreate");
        Method show = delegate.getDeclaredMethod("prepareShow", params);
        Method config = controller.getDeclaredMethod("onConfigurationChanged", int.class);
        module.hook(create).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            ViewGroup panel = (ViewGroup) getContainer.invoke(chain.getThisObject());
            register(panel, fields, getMax, setMax);
            return result;
        });
        module.hook(show).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            ViewGroup panel = (ViewGroup) detailContainer.get(binding.get(chain.getThisObject()));
            State state = panels.get(panel);
            if (state != null) state.restore(panel);
            Object result = chain.proceed();
            register(panel, fields, getMax, setMax); refresh(panel);
            return result; // Before the native animator prepares its destination measurements.
        });
        module.hook(config).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            ViewGroup panel = (ViewGroup) getContainer.invoke(chain.getThisObject());
            State state = panels.get(panel);
            if (state != null) state.restore(panel);
            Object result = chain.proceed(); refresh(panel); return result;
        });
        installed.add(loader);
        NativeModule.info("Cover QS detail: camera-safe layout and compact native content installed");
    }

    static void refresh() { for (ViewGroup panel : new java.util.ArrayList<>(panels.keySet())) refresh(panel); }
    private static void refresh(ViewGroup panel) { State state = panels.get(panel); if (state != null) state.onGlobalLayout(); }
    private static void register(ViewGroup panel, Field[] fields, Method getMax, Method setMax) {
        if (panels.containsKey(panel)) return;
        State state = new State(panel, fields, getMax, setMax); panels.put(panel, state);
        panel.addOnAttachStateChangeListener(state);
        if (panel.isAttachedToWindow()) state.onViewAttachedToWindow(panel);
    }

    private static final class State implements ViewTreeObserver.OnGlobalLayoutListener, View.OnAttachStateChangeListener {
        final WeakReference<ViewGroup> panel;
        final Field[] anchors;
        final Method getMax, setMax;
        final CoverDetailContent content = new CoverDetailContent();
        int[] original, applied;
        int originalMax, appliedMax;
        ViewTreeObserver observer;

        State(ViewGroup panel, Field[] anchors, Method getMax, Method setMax) {
            this.panel = new WeakReference<>(panel); this.anchors = anchors; this.getMax = getMax; this.setMax = setMax;
        }

        public void onViewAttachedToWindow(View v) {
            if (observer == null) { observer = v.getViewTreeObserver(); observer.addOnGlobalLayoutListener(this); }
            onGlobalLayout();
        }
        public void onViewDetachedFromWindow(View v) {
            if (observer != null && observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
            observer = null;
            try { restore((ViewGroup) v); }
            catch (ReflectiveOperationException error) { NativeModule.warning("Cannot restore QS detail layout", error); }
        }
        public void onGlobalLayout() {
            ViewGroup v = panel.get(); if (v == null) return;
            try {
                Rect safe = area(v);
                if (safe == null) { restore(v); return; }
                if (!(v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams p)) return;
                int[] current = read(p);
                if (original == null) { original = current.clone(); applied = current.clone(); }
                for (int i = 0; i < current.length; i++) if (current[i] != applied[i]) original[i] = current[i];
                int[] next = original.clone();
                View parent = (View) v.getParent();
                next[0] = safe.width(); next[1] = original[1] > 0 ? Math.min(original[1], safe.height()) : original[1];
                next[2] = safe.left - parent.getPaddingLeft(); next[3] = safe.top - parent.getPaddingTop();
                next[4] = parent.getWidth() - parent.getPaddingRight() - safe.right;
                next[5] = parent.getHeight() - parent.getPaddingBottom() - safe.bottom;
                next[6] = next[7] = Integer.MIN_VALUE; // Physical anchors; old RTL margins must not override them.
                for (int i = 0; i < anchors.length; i++) next[8 + i] = i < 4 ? 0 : -1;
                write(v, p, current, next); applied = next;
                int max = (int) getMax.invoke(v);
                if (max != appliedMax) originalMax = max;
                int limited = originalMax > 0 ? Math.min(originalMax, safe.height()) : safe.height();
                if (max != limited) setMax.invoke(v, limited);
                appliedMax = limited;
                content.apply(v);
            } catch (ReflectiveOperationException error) { NativeModule.warning("Cannot adapt QS detail layout", error); }
        }

        void restore(ViewGroup v) throws ReflectiveOperationException {
            content.restore();
            if (original == null) return;
            if (v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams p) {
                int[] current = read(p), next = current.clone();
                for (int i = 0; i < current.length; i++) if (current[i] == applied[i]) next[i] = original[i];
                write(v, p, current, next);
            }
            if ((int) getMax.invoke(v) == appliedMax) setMax.invoke(v, originalMax);
            original = applied = null; appliedMax = 0;
        }

        int[] read(ViewGroup.MarginLayoutParams p) throws IllegalAccessException {
            int[] result = new int[8 + anchors.length];
            result[0] = p.width; result[1] = p.height; result[2] = p.leftMargin; result[3] = p.topMargin;
            result[4] = p.rightMargin; result[5] = p.bottomMargin;
            result[6] = p.isMarginRelative() ? p.getMarginStart() : Integer.MIN_VALUE;
            result[7] = p.isMarginRelative() ? p.getMarginEnd() : Integer.MIN_VALUE;
            for (int i = 0; i < anchors.length; i++) result[8 + i] = anchors[i].getInt(p);
            return result;
        }
        void write(ViewGroup v, ViewGroup.MarginLayoutParams p, int[] old, int[] next) throws IllegalAccessException {
            if (java.util.Arrays.equals(old, next)) return;
            p.width = next[0]; p.height = next[1]; p.setMargins(next[2], next[3], next[4], next[5]);
            p.setMarginStart(next[6]); p.setMarginEnd(next[7]);
            for (int i = 0; i < anchors.length; i++) anchors[i].setInt(p, next[8 + i]);
            v.setLayoutParams(p);
        }
    }

    private static Rect area(ViewGroup panel) {
        if (!NativeModule.config().enabled || !panel.isAttachedToWindow() || !(panel.getParent() instanceof View root)) return null;
        Display display = panel.getDisplay(); if (display == null) return null;
        DisplayMetrics metrics = new DisplayMetrics(); display.getRealMetrics(metrics);
        if (!CutoutAppConfig.isCoverDisplay(metrics.widthPixels, metrics.heightPixels) || root.getWidth() <= 0 || root.getHeight() <= 0) return null;
        DisplayCutout cutout = display.getCutout();
        int left = 0, top = 0, right = 0, bottom = 0;
        if (cutout != null) {
            left = cutout.getSafeInsetLeft(); top = cutout.getSafeInsetTop();
            right = cutout.getSafeInsetRight(); bottom = cutout.getSafeInsetBottom();
        }
        WindowInsets window = root.getRootWindowInsets();
        if (window != null) {
            Insets bars = window.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
            left = Math.max(left, bars.left); top = Math.max(top, bars.top);
            right = Math.max(right, bars.right); bottom = Math.max(bottom, bars.bottom);
        }
        Rect safe = CoverDetailGeometry.area(metrics.widthPixels, metrics.heightPixels, left, top, right, bottom,
                panel.getResources().getDisplayMetrics().density);
        if (safe == null) return null;
        int[] position = new int[2]; root.getLocationOnScreen(position); safe.offset(-position[0], -position[1]);
        return safe.intersect(root.getPaddingLeft(), root.getPaddingTop(), root.getWidth() - root.getPaddingRight(),
                root.getHeight() - root.getPaddingBottom()) ? safe : null;
    }
}
