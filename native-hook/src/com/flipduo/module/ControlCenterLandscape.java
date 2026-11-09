package com.flipduo.module;

import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Keep the native compact list, but do not let its landscape two-pane policy push it off the cover. */
final class ControlCenterLandscape {
    private static final Set<ClassLoader> installed = Collections.newSetFromMap(new WeakHashMap<>());
    private static final WeakHashMap<View, FrameLayout.LayoutParams> originals = new WeakHashMap<>();
    private static final Set<View> headers = Collections.newSetFromMap(new WeakHashMap<>());
    private static final WeakHashMap<View, java.lang.ref.WeakReference<View>> statusRows = new WeakHashMap<>();
    private static final WeakHashMap<Object, Method> panels = new WeakHashMap<>();
    private static final WeakHashMap<FrameLayout.LayoutParams, FrameLayout.LayoutParams> headerParams = new WeakHashMap<>();
    private static int panelWidth;
    private static int statusRowHeight;

    static void install(NativeModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> container = loader.loadClass("com.miui.systemui.controlcenter.container.ControlCenterContainerController");
        Class<?> content = loader.loadClass("com.android.systemui.plugins.miui.controlcenter.ControlCenterContent");
        Class<?> header = loader.loadClass("com.android.systemui.controlcenter.shade.ControlCenterHeaderController");
        Field getHeader = field(loader.loadClass("com.android.systemui.util.ViewController"), "mView");
        Field getStatusRow = field(header, "controlCenterStatusBar");
        Method dimens = headerUpdateMethod(header); // Resolve both contracts before installing any hook.
        module.hook(container.getDeclaredMethod("onContentAttached", content))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object plugin = chain.getArg(0);
            try { installPlugin(module, plugin.getClass().getClassLoader()); }
            catch (ReflectiveOperationException error) { NativeLog.warning("Cover control-center plugin contract unavailable; native layout retained", error); }
            Object result = chain.proceed();
            try {
                Object panel = plugin.getClass().getMethod("getMainPanelController").invoke(plugin);
                panel.getClass().getMethod("updateResources").invoke(panel);
            } catch (ReflectiveOperationException error) { NativeLog.warning("Cover control-center initial reflow unavailable", error); }
            return result;
        });
        module.hook(dimens).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            View view = (View) getHeader.get(chain.getThisObject());
            statusRows.put(view, new java.lang.ref.WeakReference<>((View) getStatusRow.get(chain.getThisObject())));
            restore(view);
            Object result = chain.proceed();
            if (headers.add(view)) view.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> fitHeader(v));
            fitHeader(view); return result;
        });
    }

    static Method headerUpdateMethod(Class<?> header) throws NoSuchMethodException {
        try { return header.getDeclaredMethod("updateDimens"); }
        catch (NoSuchMethodException oldContract) { return header.getDeclaredMethod("updateDimens$1"); }
    }

    private static void installPlugin(NativeModule module, ClassLoader loader) throws ReflectiveOperationException {
        if (installed.contains(loader)) return;
        Class<?> type = loader.loadClass("miui.systemui.controlcenter.panel.main.MainPanelController");
        Method getView = type.getMethod("getView");
        Method single = type.getDeclaredMethod("setUseSeparatedPanels", Boolean.class); single.setAccessible(true);
        Field container = field(type, "mainPanelContainer"), right = field(type, "rightMainPanel"), width = field(type, "panelWidth");
        Method separated = type.getDeclaredMethod("updateUseSeparatedPanels");
        Method resources = type.getDeclaredMethod("updateResources");
        Method style = type.getDeclaredMethod("updatePanelStyle");
        Method border = type.getDeclaredMethod("getPanelBorder", boolean.class);
        Method header = type.getDeclaredMethod("overrideHeaderLayoutParams", FrameLayout.LayoutParams.class);
        module.hook(separated).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            View root = (View) getView.invoke(chain.getThisObject());
            if (!eligible(root)) return chain.proceed();
            single.invoke(chain.getThisObject(), Boolean.FALSE); return null;
        });
        module.hook(resources).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            if (panels.put(chain.getThisObject(), resources) == null) {
                View root = (View) getView.invoke(chain.getThisObject());
                java.lang.ref.WeakReference<Object> owner = new java.lang.ref.WeakReference<>(chain.getThisObject());
                root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    public void onViewAttachedToWindow(View view) {
                        Object target = owner.get();
                        if (target != null) try { resources.invoke(target); }
                        catch (ReflectiveOperationException error) { NativeLog.warning("Cover control-center attach reflow failed", error); }
                    }
                    public void onViewDetachedFromWindow(View view) { }
                });
            }
            View panel = (View) container.get(chain.getThisObject()); restore(panel);
            Object result = chain.proceed();
            fitPanel(chain.getThisObject(), getView, container, right, width);
            return result;
        });
        module.hook(style).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            fitPanel(chain.getThisObject(), getView, container, right, width); return result;
        });
        module.hook(header).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) chain.getArg(0);
            FrameLayout.LayoutParams saved = headerParams.remove(p);
            if (saved != null) copyPlacement(p, saved);
            Object result = chain.proceed();
            View root = (View) getView.invoke(chain.getThisObject());
            if (eligible(root)) {
                Rect area = panelArea(root.getDisplay().getRotation(), width.getInt(chain.getThisObject()));
                headerParams.put(p, new FrameLayout.LayoutParams(p));
                locate(p, area, false);
                // The native callback copies this height into HeaderSpaceController. Repositioning
                // the list alone leaves a second 245px status/header reservation above its first tile.
                p.height = compactHeaderHeight(root.getResources().getDisplayMetrics().density, statusRowHeight);
            }
            return result;
        });
        module.hook(border).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            View root = (View) getView.invoke(chain.getThisObject());
            if (!eligible(root)) return result;
            Rect area = panelArea(root.getDisplay().getRotation(), width.getInt(chain.getThisObject()));
            return new Pair<Float, Float>((float) area.left, (float) area.right);
        });
        installed.add(loader);
        NativeLog.info("v6.75 control-center plugin: single panel with one compact status-row reservation installed");
    }

    private static void fitPanel(Object owner, Method getView, Field container, Field right, Field width) throws ReflectiveOperationException {
        View root = (View) getView.invoke(owner), panel = (View) container.get(owner);
        if (!eligible(root)) { restore(panel); return; }
        panelWidth = width.getInt(owner);
        Rect area = panelArea(root.getDisplay().getRotation(), panelWidth);
        View list = (View) right.get(owner);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) list.getLayoutParams();
        // Native compact mode normally chooses a left/right camera margin. Horizontal cameras are above/below.
        if (lp.leftMargin != 0 || lp.rightMargin != 0 || lp.getMarginStart() != 0 || lp.getMarginEnd() != 0) {
            lp.leftMargin = lp.rightMargin = 0; lp.setMarginStart(0); lp.setMarginEnd(0); list.setLayoutParams(lp);
        }
        apply(panel, area, true);
    }
    private static void fitHeader(View view) {
        if (!eligible(view)) { restore(view); return; }
        java.lang.ref.WeakReference<View> reference = statusRows.get(view);
        View row = reference == null ? null : reference.get();
        if (row != null) statusRowHeight = row.getMeasuredHeight();
        if (panelWidth > 0) {
            Rect area = panelArea(view.getDisplay().getRotation(), panelWidth);
            area.bottom = area.top + compactHeaderHeight(view.getResources().getDisplayMetrics().density, statusRowHeight);
            apply(view, area, true);
        }
    }
    static int compactHeaderHeight(float density, int measuredRow) {
        // Keep the real 20dp status icons (or a taller measured row), with 4dp breathing room on either side.
        return Math.max(Math.round(20 * density), measuredRow) + 2 * Math.round(4 * density);
    }
    static void refresh() {
        for (var entry : new java.util.ArrayList<>(panels.entrySet())) {
            try { entry.getValue().invoke(entry.getKey()); }
            catch (ReflectiveOperationException error) { NativeLog.warning("Cover control-center preference reflow failed", error); }
        }
        for (View header : new java.util.ArrayList<>(headers)) fitHeader(header);
    }
    static boolean eligible(View view) {
        if (view == null || !NativeModule.config().enabled || !NativeModule.config().landscapeCompat) return false;
        Display display = view.getDisplay();
        if (display == null || !CoverRotation.landscape(display.getRotation())) return false;
        DisplayMetrics metrics = new DisplayMetrics(); display.getRealMetrics(metrics);
        return CutoutAppConfig.isCoverDisplay(metrics.widthPixels, metrics.heightPixels);
    }
    static Rect panelArea(int rotation, int nativeWidth) {
        int width = Math.min(Math.max(1, nativeWidth), CoverRotation.width(rotation));
        int left = (CoverRotation.width(rotation) - width) / 2;
        int top = rotation == 1 ? DockSpec.WIDTH + DockSpec.SEAM_BLEED : 0;
        int bottom = rotation == 3 ? CoverGeometry.WIDTH - DockSpec.WIDTH - DockSpec.SEAM_BLEED : CoverRotation.height(rotation);
        return new Rect(left, top, left + width, bottom);
    }
    static void locate(FrameLayout.LayoutParams p, Rect area, boolean fullHeight) {
        p.width = area.width(); if (fullHeight) p.height = area.height();
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.leftMargin = area.left; p.topMargin = area.top; p.rightMargin = p.bottomMargin = 0;
        p.setMarginStart(area.left); p.setMarginEnd(0);
    }
    private static void copyPlacement(FrameLayout.LayoutParams target, FrameLayout.LayoutParams source) {
        target.width = source.width; target.height = source.height; target.gravity = source.gravity;
        target.leftMargin = source.leftMargin; target.topMargin = source.topMargin;
        target.rightMargin = source.rightMargin; target.bottomMargin = source.bottomMargin;
        target.setMarginStart(source.getMarginStart()); target.setMarginEnd(source.getMarginEnd());
    }
    private static void apply(View view, Rect area, boolean fullHeight) {
        if (!(view.getLayoutParams() instanceof FrameLayout.LayoutParams current)) return;
        if (!originals.containsKey(view)) originals.put(view, new FrameLayout.LayoutParams(current));
        if (current.width == area.width() && (!fullHeight || current.height == area.height())
                && current.gravity == (Gravity.TOP | Gravity.LEFT) && current.leftMargin == area.left
                && current.topMargin == area.top && current.rightMargin == 0 && current.bottomMargin == 0) return;
        FrameLayout.LayoutParams next = new FrameLayout.LayoutParams(current); locate(next, area, fullHeight); view.setLayoutParams(next);
    }
    private static void restore(View view) {
        FrameLayout.LayoutParams saved = originals.remove(view);
        if (saved != null) view.setLayoutParams(saved);
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
}
