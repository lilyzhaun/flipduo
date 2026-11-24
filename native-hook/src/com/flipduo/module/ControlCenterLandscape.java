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
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Keep the native compact list, but do not let its landscape two-pane policy push it off the cover. */
final class ControlCenterLandscape {
    private static final Set<ClassLoader> installed = Collections.newSetFromMap(new WeakHashMap<>());
    private static final WeakHashMap<View, FrameLayout.LayoutParams> originals = new WeakHashMap<>();
    private static final WeakHashMap<View, WeakReference<CoverTopControlIcons>> headers = new WeakHashMap<>();
    private static final WeakHashMap<Object, PanelBinding> panels = new WeakHashMap<>();
    private static final WeakHashMap<FrameLayout.LayoutParams, FrameLayout.LayoutParams> headerParams = new WeakHashMap<>();
    private static int panelWidth;

    static void install(NativeModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> container = loader.loadClass("com.miui.systemui.controlcenter.container.ControlCenterContainerController");
        Class<?> content = loader.loadClass("com.android.systemui.plugins.miui.controlcenter.ControlCenterContent");
        Class<?> header = loader.loadClass("com.android.systemui.controlcenter.shade.ControlCenterHeaderController");
        Field getHeader = field(loader.loadClass("com.android.systemui.util.ViewController"), "mView");
        Method dimens = headerUpdateMethod(header); // Resolve both contracts before installing any hook.
        try { CoverTopControlIcons.install(module, loader); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError error) { NativeLog.warning("Cover control-center icon solver unavailable; native header retained", error); }
        try { installHeaderPlacement(module, loader); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError error) { NativeLog.warning("Cover control-center header handoff unavailable", error); }
        module.hook(container.getDeclaredMethod("onContentAttached", content))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object plugin = chain.getArg(0);
            try { installPlugin(module, plugin.getClass().getClassLoader()); }
            catch (ReflectiveOperationException error) { NativeLog.warning("Cover control-center plugin contract unavailable; native layout retained", error); }
            try { CoverLandscapeShade.installPlugin(module, plugin.getClass().getClassLoader()); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError error) { NativeLog.warning("Cover landscape control-center configuration guard unavailable", error); }
            try { CoverControlCenterLongPress.install(module, plugin.getClass().getClassLoader()); }
            catch (ReflectiveOperationException error) { NativeLog.warning("Cover control-center long-press contract unavailable; native listener retained", error); }
            try { CoverControlCenterDetail.install(module, plugin.getClass().getClassLoader()); }
            catch (ReflectiveOperationException error) { NativeLog.warning("Cover control-center detail contract unavailable; native detail layout retained", error); }
            try { CoverControlCenterReturn.install(module, plugin.getClass().getClassLoader()); }
            catch (ReflectiveOperationException error) { NativeLog.warning("Cover control-center return contract unavailable; native transition retained", error); }
            Object result = chain.proceed();
            try {
                Object panel = plugin.getClass().getMethod("getMainPanelController").invoke(plugin);
                CoverControlCenterRotation.register(panel);
                panel.getClass().getMethod("updateResources").invoke(panel);
            } catch (ReflectiveOperationException error) { NativeLog.warning("Cover control-center initial reflow unavailable", error); }
            return result;
        });
        module.hook(dimens).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            View view = (View) getHeader.get(chain.getThisObject());
            if (!headerLandscape(view)) restore(view); // updateDimens owns padding, not the compact header's position.
            Object result = chain.proceed();
            registerHeader(view);
            fitHeader(view); return result;
        });
    }

    static Method headerUpdateMethod(Class<?> header) throws NoSuchMethodException {
        try { return header.getDeclaredMethod("updateDimens"); }
        catch (NoSuchMethodException oldContract) { return header.getDeclaredMethod("updateDimens$1"); }
    }

    static void installHeaderPlacement(XposedInterface module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> type = loader.loadClass("com.android.systemui.controlcenter.shade.CombinedHeaderController");
        Field normal = field(type, "controlCenterHeaderView");
        module.hook(type.getDeclaredMethod("updateControlCenterHeaderLayout")).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(c -> {
            View view = (View)normal.get(c.getThisObject()); if (view == null) return c.proceed();
            var before = view.getLayoutParams();
            Object result = c.proceed();
            // Both host variants publish a NEW LP after the plugin has copied its list-local header slot.
            // Commit the host-local copy now, before the next traversal; never feed its top back to the list.
            if (view.getLayoutParams() != before && view.getLayoutParams() instanceof FrameLayout.LayoutParams p) {
                FrameLayout.LayoutParams stock = headerParams.get(p);
                if (stock != null && headerLandscape(view)) {
                    originals.put(view, stock);
                    if (p.topMargin > 0) {
                        int top = CoverTopControlIcons.localHeaderTop(view, p.topMargin);
                        apply(view, new Rect(p.leftMargin, top, p.leftMargin + p.width, top + iconHeaderHeight(view)), true);
                    }
                } else originals.remove(view); // The host has already supplied the latest native baseline.
            }
            return result;
        });
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
        HeaderBinding headerBinding = HeaderBinding.resolve(type);
        module.hook(separated).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            View root = (View) getView.invoke(chain.getThisObject());
            if (!landscape(root)) return chain.proceed();
            single.invoke(chain.getThisObject(), Boolean.FALSE); return null;
        });
        module.hook(resources).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            if (!panels.containsKey(chain.getThisObject())) {
                View root = (View) getView.invoke(chain.getThisObject());
                panels.put(chain.getThisObject(), new PanelBinding(chain.getThisObject(), root, resources));
            }
            View panel = (View) container.get(chain.getThisObject()); restore(panel);
            Object result = chain.proceed();
            fitPanel(chain.getThisObject(), getView, container, right, width);
            if (headerBinding != null) headerBinding.sync(chain.getThisObject(), (View)getView.invoke(chain.getThisObject()), width.getInt(chain.getThisObject()));
            return result;
        });
        module.hook(style).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object owner = chain.getThisObject();
            if (!landscape((View) getView.invoke(owner)))
                restore((View) container.get(owner)); // Return our override *before* the plugin selects the new camera-side anchor.
            Object result = chain.proceed();
            fitPanel(owner, getView, container, right, width); return result;
        });
        module.hook(header).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) chain.getArg(0);
            FrameLayout.LayoutParams saved = headerParams.remove(p);
            if (saved != null) copyPlacement(p, saved);
            Object result = chain.proceed();
            View root = (View) getView.invoke(chain.getThisObject());
            fitPluginHeader(root, p, width.getInt(chain.getThisObject()));
            return result;
        });
        module.hook(border).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            View root = (View) getView.invoke(chain.getThisObject());
            if (!landscape(root)) return result;
            Rect area = panelArea(root.getDisplay().getRotation(), width.getInt(chain.getThisObject()));
            return new Pair<Float, Float>((float) area.left, (float) area.right);
        });
        installed.add(loader);
        NativeLog.info("v6.75 control-center plugin: single panel with one compact status-row reservation installed");
    }

    private static void fitPanel(Object owner, Method getView, Field container, Field right, Field width) throws ReflectiveOperationException {
        View root = (View) getView.invoke(owner), panel = (View) container.get(owner);
        if (!landscape(root)) { restore(panel); return; }
        panelWidth = width.getInt(owner);
        Rect area = panelArea(root.getDisplay().getRotation(), panelWidth);
        View list = (View) right.get(owner);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) list.getLayoutParams();
        // Native compact mode normally chooses a left/right camera margin. Horizontal cameras are above/below.
        if (lp.leftMargin != 0 || lp.rightMargin != 0 || lp.getMarginStart() != 0 || lp.getMarginEnd() != 0) {
            lp.leftMargin = lp.rightMargin = 0; lp.setMarginStart(0); lp.setMarginEnd(0); list.setLayoutParams(lp);
        }
        apply(panel, area, true);
        CoverTopControlIcons.panelPlaced(panel);
    }
    private static void fitHeader(View view) {
        placeHeader(view);
        var ref = headers.get(view); var rows = ref == null ? null : ref.get(); if (rows != null) rows.sync();
    }
    private static CoverTopControlIcons registerHeader(View view) {
        var ref = headers.get(view); var state = ref == null ? null : ref.get();
        if (state == null) {
            state = new CoverTopControlIcons(view); headers.put(view, new WeakReference<>(state));
            view.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> placeHeader(v));
        }
        return state;
    }
    static CoverTopControlIcons measuredHeader(View view) {
        var ref = headers.get(view); var state = ref == null ? null : ref.get(); if (state != null) return state;
        int id = view.getId();
        return id != View.NO_ID && id != 0 && id == view.getResources().getIdentifier("normal_control_center_header_view", "id", "com.android.systemui") ? registerHeader(view) : null;
    }
    static void placeHeader(View view) {
        if (!headerLandscape(view)) restore(view);
        else if (panelWidth > 0) {
            Rect area = panelArea(view.getDisplay().getRotation(), panelWidth);
            area.top = CoverTopControlIcons.localHeaderTop(view, area.top);
            area.bottom = area.top + iconHeaderHeight(view);
            apply(view, area, true);
        }
    }
    private static boolean headerLandscape(View v) {
        return eligible(v) && v.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }
    static void fitPluginHeader(View root, FrameLayout.LayoutParams p, int width) {
        // This callback also seeds the RecyclerView's cached spacer, often before the root's first measure.
        if (!headerLandscape(root)) return;
        headerParams.put(p, new FrameLayout.LayoutParams(p));
        locate(p, panelArea(root.getDisplay().getRotation(), width), false);
        p.height = iconHeaderHeight(root);
    }
    static int iconRowHeight(View view) {
        // A 396px measured status-row wrapper can already contain the camera strip. It is not an icon metric.
        var res = view.getResources(); int id = res.getIdentifier("status_bar_icon_height", "dimen", "com.android.systemui");
        return Math.max(Math.round(20 * res.getDisplayMetrics().density), id == 0 ? 0 : res.getDimensionPixelSize(id));
    }
    static int iconHeaderHeight(View view) {
        return compactHeaderHeight(view.getResources().getDisplayMetrics().density, iconRowHeight(view));
    }
    static final class HeaderBinding {
        final Method header, cached, refresh;
        final Set<Object> compact = Collections.newSetFromMap(new WeakHashMap<>());
        private HeaderBinding(Class<?> panel) throws ReflectiveOperationException {
            header = panel.getMethod("getHeaderController2"); Class<?> type = header.getReturnType();
            cached = type.getMethod("getLayoutParams"); refresh = type.getDeclaredMethod("updateResources"); refresh.setAccessible(true);
        }
        static HeaderBinding resolve(Class<?> panel) {
            try { return new HeaderBinding(panel); }
            catch (ReflectiveOperationException e) { NativeLog.warning("Cover control-center header cache unavailable", e); return null; }
        }
        void sync(Object owner, View root, int width) throws ReflectiveOperationException {
            if (headerLandscape(root)) {
                compact.add(owner); Object h = header.invoke(owner);
                FrameLayout.LayoutParams p = (FrameLayout.LayoutParams)cached.invoke(h);
                Rect area = panelArea(root.getDisplay().getRotation(), width);
                if (p == null || p.height != iconHeaderHeight(root) || p.width != area.width() || p.leftMargin != area.left || p.topMargin != area.top)
                    refresh.invoke(h); // Updates the host header and both attached RecyclerView spaces through the same native callback.
            } else if (compact.remove(owner)) refresh.invoke(header.invoke(owner));
        }
    }
    static int compactHeaderHeight(float density, int measuredRow) {
        // Keep the real 20dp status icons (or a taller measured row), with 4dp breathing room on either side.
        return Math.max(Math.round(20 * density), measuredRow) + 2 * Math.round(4 * density);
    }
    /** Only reflow after a real resize into landscape. Opening an already sized shade or receiving
     * another inset is not a rotation, and must not restart the plugin's resource callbacks. */
    static final class PanelBinding implements View.OnAttachStateChangeListener, View.OnLayoutChangeListener, Runnable {
        private final java.lang.ref.WeakReference<Object> owner;
        private final java.lang.ref.WeakReference<View> root;
        private final Method resources;
        private boolean listening, queued;
        PanelBinding(Object owner, View root, Method resources) {
            this.owner = new java.lang.ref.WeakReference<>(owner); this.root = new java.lang.ref.WeakReference<>(root); this.resources = resources;
            root.addOnAttachStateChangeListener(this); sync();
        }
        void sync() {
            View v = root.get(); if (v == null) return;
            boolean on = v.isAttachedToWindow() && NativeModule.config().enabled && NativeModule.config().landscapeCompat;
            if (on && !listening) { v.addOnLayoutChangeListener(this); listening = true; }
            else if (!on) stop(v);
        }
        private void stop(View v) {
            if (listening) { v.removeOnLayoutChangeListener(this); listening = false; }
            if (queued) { v.removeCallbacks(this); queued = false; }
        }
        public void onViewAttachedToWindow(View v) { sync(); reflow(); }
        public void onViewDetachedFromWindow(View v) { stop(v); }
        public void onLayoutChange(View v,int l,int t,int r,int b,int ol,int ot,int or,int ob) {
            if (!queued && (r-l != or-ol || b-t != ob-ot) && landscape(v)) queued = v.post(this);
        }
        public void run() { queued = false; View v = root.get(); if (listening && v != null && landscape(v) && CoverLandscapeShade.eligible(v)) reflow(); }
        void reflow() {
            Object target = owner.get(); if (target == null) return;
            try { resources.invoke(target); }
            catch (ReflectiveOperationException error) { NativeLog.warning("Cover control-center layout reflow failed", error); }
        }
    }
    static void refresh() {
        CoverControlCenterRotation.refresh();
        CoverControlCenterDetail.refresh();
        CoverLandscapeDismiss.refresh();
        for (var binding : new java.util.ArrayList<>(panels.values())) { binding.sync(); binding.reflow(); }
        for (View header : new java.util.ArrayList<>(headers.keySet())) fitHeader(header);
    }
    private static boolean landscape(View root) {
        return eligible(root) && CoverControlCenterRotation.layoutMatches(root, true);
    }
    static boolean eligible(View view) {
        if (view == null || !NativeModule.config().enabled || !NativeModule.config().landscapeCompat) return false;
        Display display = view.getDisplay();
        if (display == null || !CoverRotation.landscape(display.getRotation())) return false;
        DisplayMetrics metrics = new DisplayMetrics(); display.getRealMetrics(metrics);
        return metrics.widthPixels > metrics.heightPixels && CutoutAppConfig.isCoverDisplay(metrics.widthPixels, metrics.heightPixels);
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
        // The camera-safe rectangle is in physical display coordinates, even when the plugin is RTL.
        p.setMarginStart(Integer.MIN_VALUE); p.setMarginEnd(Integer.MIN_VALUE);
    }
    private static void copyPlacement(FrameLayout.LayoutParams target, FrameLayout.LayoutParams source) {
        target.width = source.width; target.height = source.height; target.gravity = source.gravity;
        target.leftMargin = source.leftMargin; target.topMargin = source.topMargin;
        target.rightMargin = source.rightMargin; target.bottomMargin = source.bottomMargin;
        target.setMarginStart(source.isMarginRelative() ? source.getMarginStart() : Integer.MIN_VALUE);
        target.setMarginEnd(source.isMarginRelative() ? source.getMarginEnd() : Integer.MIN_VALUE);
    }
    private static void apply(View view, Rect area, boolean fullHeight) {
        if (!(view.getLayoutParams() instanceof FrameLayout.LayoutParams current)) return;
        if (!originals.containsKey(view)) originals.put(view, new FrameLayout.LayoutParams(current));
        if (current.width == area.width() && (!fullHeight || current.height == area.height())
                && current.gravity == (Gravity.TOP | Gravity.LEFT) && current.leftMargin == area.left
                && current.topMargin == area.top && current.rightMargin == 0 && current.bottomMargin == 0) return;
        FrameLayout.LayoutParams next = new FrameLayout.LayoutParams(current); locate(next, area, fullHeight); view.setLayoutParams(next);
    }
    static void restore(View view) {
        FrameLayout.LayoutParams saved = originals.remove(view);
        if (saved != null) view.setLayoutParams(saved);
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
}
