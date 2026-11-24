// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.content.res.Configuration;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.WeakHashMap;

/** Supply the normal CC header solver with a one-line vertical slot. The ROM owns every measured
 * size and layout frame; resource callbacks outside the solve continue to see their own inputs. */
final class CoverTopControlIcons implements View.OnAttachStateChangeListener {
    private static final WeakHashMap<View, Anchor> anchors = new WeakHashMap<>();
    private final View header;
    private Row real, alternate;
    private int upperDisplay = -1, upperRotation = -1, measuredHeight, measuredGap;

    static void install(XposedInterface module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> normal = loader.loadClass("com.android.systemui.controlcenter.shade.ControlCenterHeaderView");
        Class<?> solver = loader.loadClass("androidx.constraintlayout.widget.ConstraintLayout");
        Class<?> params = loader.loadClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams");
        if (normal.getSuperclass() != solver) throw new NoSuchMethodException("Unknown control header solver");
        String[] names = {"topToTop", "topToBottom", "bottomToTop", "bottomToBottom", "baselineToBaseline", "baselineToTop", "baselineToBottom"};
        Field[] vertical = new Field[names.length];
        for (int i=0; i<names.length; i++) vertical[i] = params.getField(names[i]);
        // The ROM header inherits this method. Other SystemUI constraint layouts go straight to their original solver.
        module.hook(solver.getDeclaredMethod("onMeasure", int.class, int.class)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            if (!normal.isInstance(chain.getThisObject())) return chain.proceed();
            View view = (View)chain.getThisObject(); CoverTopControlIcons state = ControlCenterLandscape.measuredHeader(view);
            return state == null ? chain.proceed() : state.measure(vertical, chain);
        });
    }

    static void panelPlaced(View panel) {
        if (!(panel.getParent() instanceof View parent)) return;
        var anchor = anchors.computeIfAbsent(panel.getRootView(), unused -> new Anchor());
        if (anchor.parent == null || anchor.parent.get() != parent) anchor.parent = new WeakReference<>(parent);
    }
    private static int layoutY(View v) {
        int y = 0;
        while (v.getParent() instanceof View parent) { y += v.getTop() - parent.getScrollY(); v = parent; }
        return y;
    }
    static int localHeaderTop(View header, int top) {
        if (top <= 0 || !(header.getParent() instanceof View host)) return top;
        // Use the layout origin, not visibility, an animated position, or a child's previous traversal.
        View window = header.getRootView(); Anchor anchor = anchors.get(window);
        int pluginOrigin = anchor == null ? 0 : anchor.origin(window, header.getDisplay());
        return pluginOrigin + top - layoutY(host) + host.getScrollY() - host.getPaddingTop();
    }
    private static final class Anchor {
        WeakReference<View> parent;
        int id = -1, rotation = -1, y;
        int origin(View window, Display d) {
            if (d == null) return 0;
            View v = parent.get();
            if (v != null && v.getRootView() == window && v.isLaidOut()) {
                y = layoutY(v) - v.getScrollY() + v.getPaddingTop(); id = d.getDisplayId(); rotation = d.getRotation();
            }
            return id == d.getDisplayId() && rotation == d.getRotation() ? y : 0;
        }
    }
    CoverTopControlIcons(View header) { this.header = header; header.addOnAttachStateChangeListener(this); }
    private int height() {
        if (!CoverLandscapeShade.eligible(header)) { upperDisplay = upperRotation = -1; return 0; }
        Display d = header.getDisplay(); int id = d.getDisplayId(), rotation = d.getRotation();
        DisplayCutout cutout = d.getCutout();
        if (cutout == null) { WindowInsets w = header.getRootWindowInsets(); cutout = w == null ? null : w.getDisplayCutout(); }
        if (cutout != null && cutout.getSafeInsetBottom() > 0) { upperDisplay = upperRotation = -1; return 0; }
        if (upperDisplay != id || upperRotation != rotation) {
            upperDisplay = upperRotation = -1;
            if (cutout == null || cutout.getSafeInsetTop() <= 0 || header.getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE) return 0;
            upperDisplay = id; upperRotation = rotation;
        }
        // A hidden subtree or temporarily absent insets do not move the icons on the same confirmed cover rotation.
        return ControlCenterLandscape.iconRowHeight(header);
    }
    private int gap(int height) { return height == 0 ? 0 : Math.round(4 * header.getResources().getDisplayMetrics().density); }
    void sync() {
        int h = height();
        if ((h != measuredHeight || gap(h) != measuredGap) && !header.isLayoutRequested()) header.requestLayout();
    }
    public void onViewAttachedToWindow(View v) { sync(); }
    public void onViewDetachedFromWindow(View v) {
        upperDisplay = upperRotation = -1;
        if (measuredHeight != 0) { mark(real); mark(alternate); header.requestLayout(); }
        real = alternate = null; measuredHeight = measuredGap = 0;
    }
    private Object measure(Field[] vertical, XposedInterface.Chain chain) throws Throwable {
        int h = height(), gap = gap(h);
        if (h == 0 && measuredHeight == 0) return chain.proceed();
        Row r = real, a = alternate;
        if (h > 0) {
            real = resolve(r, "normal_control_center_status_bar", "status_bar_area");
            alternate = resolve(a, "normal_fake_control_center_status_bar", "status_icons_container");
        }
        if (h != measuredHeight || gap != measuredGap || r != real || a != alternate) {
            // All three ROM solvers rebuild their graph when a direct child isLayoutRequested().
            // forceLayout marks that input for THIS measure without scheduling another traversal.
            mark(real); mark(alternate);
        }
        measuredHeight = -1; // An interrupted native solve must not be cached as a successful geometry change.
        Pass pass = chain::proceed;
        if (h > 0 && alternate != null) {
            Pass original = pass; pass = () -> alternate.during(vertical, h, gap, original);
        }
        Object result = h == 0 || real == null ? pass.run() : real.during(vertical, h, gap, pass);
        measuredHeight = h; measuredGap = gap;
        return result;
    }
    private Row resolve(Row row, String outer, String inner) {
        if (row != null && row.group.getParent() == header && row.area.getParent() == row.group) return row;
        var res = header.getResources(); int groupId = res.getIdentifier(outer, "id", "com.android.systemui"), areaId = res.getIdentifier(inner, "id", "com.android.systemui");
        View group = groupId == 0 ? null : header.findViewById(groupId), area = group == null || areaId == 0 ? null : group.findViewById(areaId);
        return area != null && group.getParent() == header && area.getParent() == group ? new Row(group, area) : null;
    }
    private static void mark(Row row) { if (row != null) { row.area.forceLayout(); row.group.forceLayout(); } }
    private interface Pass { Object run() throws Throwable; }
    private record Row(View group, View area) {
        Object during(Field[] vertical, int h, int gap, Pass pass) throws Throwable {
            if (!(group.getLayoutParams() instanceof ViewGroup.MarginLayoutParams p) || !vertical[0].getDeclaringClass().isInstance(p)) return pass.run();
            var child = area.getLayoutParams(); if (child == null) return pass.run();
            int stockHeight = p.height, top = p.topMargin, bottom = p.bottomMargin, inner = child.height;
            int[] stock = new int[vertical.length];
            for (int i=0; i<stock.length; i++) stock[i] = vertical[i].getInt(p);
            try {
                // Pin just the vertical axis inside the header. Horizontal constraints, RTL, visibility, animations and click targets remain native.
                for (int i=0; i<stock.length; i++) vertical[i].setInt(p, i == 0 ? 0 : -1);
                p.topMargin = gap; p.bottomMargin = 0; p.height = child.height = h;
                return pass.run();
            } finally {
                p.topMargin = top; p.bottomMargin = bottom; p.height = stockHeight; child.height = inner;
                for (int i=0; i<stock.length; i++) vertical[i].setInt(p, stock[i]);
            }
        }
    }
}
