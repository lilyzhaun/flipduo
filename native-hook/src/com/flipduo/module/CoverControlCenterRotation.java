// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.app.KeyguardManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.WeakHashMap;
import org.xmlpull.v1.XmlPullParserException;

/** A settled rotation can arrive after the Xiaomi plugin's last layout callback. Replay its native
 * layout once in the confirmed geometry. This also works when FlipDuo never owned the panel. */
final class CoverControlCenterRotation implements View.OnAttachStateChangeListener,
        ViewTreeObserver.OnGlobalLayoutListener, DisplayManager.DisplayListener, Runnable {
    private static final WeakHashMap<Object, WeakReference<CoverControlCenterRotation>> controllers = new WeakHashMap<>();
    private final Object owner, compact, normal;
    private final View root, panel;
    private final Method resources, style, mode, router, routingState;
    private final Field current, main;
    private final int idle;
    private final DisplayMetrics metrics = new DisplayMetrics();
    private final DisplayManager displays;
    private ViewTreeObserver observer;
    private boolean queued;
    private record Geometry(int display, int rotation, int w, int h, int insetLeft, int insetRight, boolean cameraRight, int direction, boolean landscapeCompat) {}
    private Geometry applied;
    private FrameLayout.LayoutParams settledBox;

    private CoverControlCenterRotation(Object owner) throws ReflectiveOperationException {
        this.owner = owner;
        Class<?> type = owner.getClass();
        root = (View) type.getMethod("getView").invoke(owner);
        panel = (View) field(type, "mainPanelContainer").get(owner);
        resources = type.getMethod("updateResources");
        style = type.getMethod("getStyle"); compact = style.getReturnType().getField("COMPACT").get(null);
        mode = type.getMethod("getMode"); normal = mode.getReturnType().getField("NORMAL").get(null);
        router = root.getClass().getDeclaredMethod("getSecondaryPanelRouter"); router.setAccessible(true);
        Class<?> routes = router.getReturnType();
        routingState = routes.getMethod("getRoutingState"); idle = field(routes, "ROUTING_STATE_IDLE").getInt(null);
        current = field(routes, "current"); main = field(routes, "mainPanelAnimController");
        displays = root.getContext().getSystemService(DisplayManager.class);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static boolean enabled() { DockConfig c = NativeModule.config(); return c.enabled && c.fixControlCenterRotation; }
    static boolean active(Object owner) {
        WeakReference<CoverControlCenterRotation> ref = controllers.get(owner);
        return enabled() && ref != null && ref.get() != null;
    }
    static void register(Object owner) { controllers.putIfAbsent(owner, null); sync(owner); }
    static void refresh() { for (Object owner : new ArrayList<>(controllers.keySet())) sync(owner); }
    private static void sync(Object owner) {
        WeakReference<CoverControlCenterRotation> ref = controllers.get(owner);
        CoverControlCenterRotation s = ref == null ? null : ref.get();
        if (!enabled()) {
            if (s != null) { s.root.removeOnAttachStateChangeListener(s); s.onViewDetachedFromWindow(s.root); controllers.put(owner, null); }
        } else if (s != null) s.onGlobalLayout();
        else try {
            s = new CoverControlCenterRotation(owner); controllers.put(owner, new WeakReference<>(s));
            s.root.addOnAttachStateChangeListener(s);
            if (s.root.isAttachedToWindow()) s.onViewAttachedToWindow(s.root);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            controllers.remove(owner); NativeLog.warning("Cover control center rotation repair unavailable on this plugin", e);
        }
    }

    public void onViewAttachedToWindow(View v) {
        observer = root.getViewTreeObserver(); observer.addOnGlobalLayoutListener(this);
        displays.registerDisplayListener(this, root.getHandler()); onGlobalLayout();
    }
    public void onViewDetachedFromWindow(View v) {
        if (observer != null) {
            if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
            displays.unregisterDisplayListener(this); observer = null;
        }
        root.removeCallbacks(this); queued = false; applied = null; settledBox = null;
    }
    public void onDisplayAdded(int id) { }
    public void onDisplayRemoved(int id) { if (applied != null && applied.display == id) applied = null; }
    public void onDisplayChanged(int id) { Display d = root.getDisplay(); if (d != null && d.getDisplayId() == id) onGlobalLayout(); }

    static boolean layoutMatches(View root, boolean wide) {
        return root.getWidth() > 0 && root.getHeight() > 0 && (wide ? root.getWidth() > root.getHeight() : root.getWidth() < root.getHeight())
                && root.getResources().getConfiguration().orientation == (wide ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT);
    }
    private Geometry geometry() {
        Display d = root.getDisplay();
        if (observer == null || !enabled() || d == null || d.getState() != Display.STATE_ON || !panel.isShown()
                || root.getWindowVisibility() != View.VISIBLE || panel.getParent() != root) return null;
        int rotation = d.getRotation(); boolean wide = CoverRotation.landscape(rotation);
        if (wide || !CutoutAppConfig.isCoverDisplay(root.getWidth(), root.getHeight()) || !layoutMatches(root, false)) return null;
        WindowInsets insets = root.getRootWindowInsets(); DisplayCutout cutout = insets == null ? null : insets.getDisplayCutout();
        DisplayCutout physical = d.getCutout(); // The plugin chooses its gravity from Display, but sets padding from dispatched WindowInsets.
        return new Geometry(d.getDisplayId(), rotation, root.getWidth(), root.getHeight(), cutout == null ? 0 : cutout.getSafeInsetLeft(),
                cutout == null ? 0 : cutout.getSafeInsetRight(), physical != null && physical.getSafeInsetRight() > 0,
                root.getLayoutDirection(), NativeModule.config().landscapeCompat);
    }
    private boolean needsReflow(Geometry now) {
        return !now.equals(applied) || (settledBox != null && panel.getLayoutParams() instanceof FrameLayout.LayoutParams p
                && !samePlacement(settledBox, p)); // A late native callback can overwrite a placement even without another rotation.
    }
    public void onGlobalLayout() {
        Geometry now = geometry();
        if (now == null) { applied = null; return; }
        if (needsReflow(now) && !queued) queued = root.post(this);
    }
    public void run() {
        queued = false;
        Geometry now = geometry();
        if (now == null || !needsReflow(now)) return;
        Display display = root.getDisplay(); display.getRealMetrics(metrics);
        if (now.w != metrics.widthPixels || now.h != metrics.heightPixels || root.getContext().getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
        try {
            if (style.invoke(owner) != compact || mode.invoke(owner) != normal) return;
            Object routes = router.invoke(root);
            if (routes == null || (int) routingState.invoke(routes) != idle) return;
            Object mainPanel = main.get(routes);
            if (mainPanel == null || current.get(routes) != mainPanel) return;
            applied = now; settledBox = null; // Their own layout pass is not another rotation.
            ControlCenterLandscape.restore(panel);
            restoreXmlPlacement(panel);
            resources.invoke(owner); // Native split choice, panel width, cutout side, gravity and list margins (including RTL).
            if (panel.getLayoutParams() instanceof FrameLayout.LayoutParams p) settledBox = new FrameLayout.LayoutParams(p);
            root.requestApplyInsets(); // Redispatch actual subtree insets; do not synthesize padding or scroll the list.
        } catch (ReflectiveOperationException | XmlPullParserException | IOException | RuntimeException | LinkageError e) {
            applied = now; settledBox = null; NativeLog.warning("Cannot reflow cover control center for the settled rotation", e);
        }
    }

    /** updatePanelStyle rewrites gravity, not the container's margins/width/height. Read these from
     * *this plugin's* layout, including when we first see an already damaged portrait with no saved baseline. */
    private static void restoreXmlPlacement(View v) throws IOException, XmlPullParserException {
        if (!(v.getLayoutParams() instanceof FrameLayout.LayoutParams p) || v.getId() == View.NO_ID) return;
        Resources res = v.getResources();
        int layout = res.getIdentifier("control_center", "layout", res.getResourcePackageName(v.getId()));
        if (layout == 0) return; // Unknown template: the native reflow still runs.
        try (XmlResourceParser xml = res.getLayout(layout)) {
            for (int tag = xml.next(); tag != XmlResourceParser.END_DOCUMENT; tag = xml.next()) {
                if (tag != XmlResourceParser.START_TAG || xml.getAttributeResourceValue("http://schemas.android.com/apk/res/android", "id", View.NO_ID) != v.getId()) continue;
                FrameLayout.LayoutParams stock = new FrameLayout.LayoutParams(v.getContext(), xml);
                stock.resolveLayoutDirection(v.getLayoutDirection()); stock.gravity = p.gravity;
                if (!samePlacement(stock, p)) v.setLayoutParams(stock);
                return;
            }
        }
    }
    private static boolean samePlacement(FrameLayout.LayoutParams a, FrameLayout.LayoutParams b) {
        return a.width == b.width && a.height == b.height && a.gravity == b.gravity && a.leftMargin == b.leftMargin && a.topMargin == b.topMargin
                && a.rightMargin == b.rightMargin && a.bottomMargin == b.bottomMargin && a.isMarginRelative() == b.isMarginRelative()
                && a.getMarginStart() == b.getMarginStart() && a.getMarginEnd() == b.getMarginEnd();
    }
}
