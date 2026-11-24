// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.WeakHashMap;

/** The native end/center clear button is over the cards on the nearly square landscape cover.
 * Keep the native button, listeners and animation; give it a footer below the scroll viewport. */
final class CoverLandscapeDismiss implements View.OnAttachStateChangeListener, ViewTreeObserver.OnGlobalLayoutListener {
    private static final WeakHashMap<View, WeakReference<CoverLandscapeDismiss>> known = new WeakHashMap<>();
    private static final WeakHashMap<View, WeakReference<CoverLandscapeDismiss>> stacks = new WeakHashMap<>();
    private final View button, scroll;
    private final int[] xy = new int[2];
    private final DisplayMetrics metrics = new DisplayMetrics();
    private final Rect available = new Rect(), pocket = new Rect(), clip = new Rect();
    private int clipBottom = -1;
    private ViewTreeObserver observer;
    private FrameLayout.LayoutParams stock;
    private int nativeMargin, writtenMargin = -1;

    static void install(XposedInterface module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> type = loader.loadClass("com.android.systemui.shade.NotificationDismissViewController");
        var button = CoverLandscapeShade.field(type, "dismissView"); var scroll = CoverLandscapeShade.field(type, "nssl");
        module.hook(type.getDeclaredMethod("updateOrientation")).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(c -> {
            Object controller = c.getThisObject();
            var s = bind((View)button.get(controller), (View)scroll.get(controller));
            s.restoreButton(); // Let the OEM write its native side margins and gravity to the original values.
            Object result = c.proceed(); s.sync(); return result;
        });
        try {
            var shared = loader.loadClass("com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer");
            module.hook(shared.getDeclaredMethod("drawChild", Canvas.class, View.class, long.class))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(c -> {
                var ref = stacks.get((View)c.getArg(1)); var s = ref == null ? null : ref.get();
                if (s == null || s.clipBottom < 0 || s.scroll.getParent() != c.getThisObject()) return c.proceed();
                Canvas canvas = (Canvas)c.getArg(0); int save = s.clipStack(canvas);
                try { return c.proceed(); } finally { if (save >= 0) canvas.restoreToCount(save); }
            });
        } catch (ReflectiveOperationException error) { NativeLog.warning("Cover notification stack drawing boundary unavailable", error); }
    }
    static CoverLandscapeDismiss bind(View button, View scroll) {
        var ref = known.get(button); CoverLandscapeDismiss s = ref == null ? null : ref.get();
        if (s == null) {
            s = new CoverLandscapeDismiss(button, scroll); var state = new WeakReference<>(s);
            known.put(button, state); stacks.put(scroll, state); button.addOnAttachStateChangeListener(s);
        }
        return s;
    }
    private CoverLandscapeDismiss(View button, View scroll) { this.button = button; this.scroll = scroll; }
    static void refresh() { for (var ref : new ArrayList<>(known.values())) { var s = ref.get(); if (s != null) s.sync(); } }
    public void onViewAttachedToWindow(View v) { sync(); }
    public void onViewDetachedFromWindow(View v) { stop(); }
    private void sync() {
        if (!button.isAttachedToWindow() || !ControlCenterLandscape.eligible(button)) { stop(); return; }
        if (observer == null) { observer = button.getViewTreeObserver(); observer.addOnGlobalLayoutListener(this); }
        onGlobalLayout();
    }
    private void stop() {
        if (observer != null && observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
        observer = null; restoreButton(); restoreScroll();
    }
    public void onGlobalLayout() {
        if (!button.isShown() || !CoverLandscapeShade.eligible(button) || !(button.getParent() instanceof View parent)
                || !(scroll.getParent() instanceof View listParent) || button.getRootView() != scroll.getRootView()
                || !(button.getLayoutParams() instanceof FrameLayout.LayoutParams p)
                || !(scroll.getLayoutParams() instanceof ViewGroup.MarginLayoutParams lp)) { restoreButton(); restoreScroll(); return; }
        int height = p.height > 0 ? p.height : button.getHeight();
        if (height <= 0 || parent.getHeight() == 0 || listParent.getHeight() == 0) { restoreButton(); restoreScroll(); return; }
        var display = button.getDisplay(); display.getRealMetrics(metrics);
        if (metrics.widthPixels <= metrics.heightPixels) { restoreButton(); restoreScroll(); return; }
        int gap = Math.round(8 * button.getResources().getDisplayMetrics().density);
        WindowInsets insets = button.getRootWindowInsets();
        DisplayCutout cutout = display.getCutout();
        DisplayCutout windowCutout = insets == null ? null : insets.getDisplayCutout();
        if (cutout == null) cutout = windowCutout;
        Insets nav = insets == null ? Insets.NONE : insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars());
        int windowCameraBottom = insets == null ? 0 : insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout()).bottom;
        int camera = cutout == null ? windowCameraBottom : cutout.getSafeInsetBottom();
        int bottom = Math.max(nav.bottom, Math.max(camera, windowCameraBottom));
        parent.getLocationOnScreen(xy);
        int parentLeft = xy[0] + parent.getPaddingLeft(), buttonParentBottom = xy[1] + parent.getHeight() - parent.getPaddingBottom();
        available.set(Math.max(parentLeft, Math.max(nav.left, cutout == null ? 0 : cutout.getSafeInsetLeft())), Math.max(0, xy[1] + parent.getPaddingTop()),
                Math.min(xy[0] + parent.getWidth() - parent.getPaddingRight(), metrics.widthPixels - Math.max(nav.right, cutout == null ? 0 : cutout.getSafeInsetRight())),
                Math.min(buttonParentBottom, metrics.heightPixels - nav.bottom));
        Rect bound = camera > 0 ? bottomBound(cutout) : null;
        if (bound == null && camera > 0 && windowCutout != cutout) bound = bottomBound(windowCutout);
        boolean beside = bound != null && bottomPocket(available, bound, p.width > 0 ? p.width : button.getWidth(), height, gap, pocket);
        int buttonMargin = Math.max(0, buttonParentBottom - (metrics.heightPixels - bottom)) + gap;
        int left = 0, gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        if (beside) { left = pocket.left - parentLeft; buttonMargin = buttonParentBottom - pocket.bottom; gravity = Gravity.BOTTOM | Gravity.LEFT; }
        // Protect the whole notification row from the lenses. The *button* alone can use the free side of that strip.
        int end = buttonParentBottom - buttonMargin - height - gap;
        if (beside) end = Math.min(end, Math.min(bound.top, metrics.heightPixels - bottom));
        listParent.getLocationOnScreen(xy);
        int footer = Math.max(0, xy[1] + listParent.getHeight() - listParent.getPaddingBottom() - end);
        setClipBottom(camera > 0 ? Math.max(0, end - xy[1] + listParent.getScrollY()) : -1);
        if (stock == null) stock = new FrameLayout.LayoutParams(p);
        if (p.gravity != gravity || p.bottomMargin != buttonMargin || p.topMargin != 0 || p.leftMargin != left || p.rightMargin != 0 || p.isMarginRelative()) {
            var next = new FrameLayout.LayoutParams(p); next.gravity = gravity; next.setMargins(left, 0, 0, buttonMargin);
            next.setMarginStart(Integer.MIN_VALUE); next.setMarginEnd(Integer.MIN_VALUE); button.setLayoutParams(next);
        }
        if (writtenMargin < 0 || lp.bottomMargin != writtenMargin) nativeMargin = lp.bottomMargin;
        writtenMargin = Math.max(nativeMargin, footer);
        if (lp.bottomMargin != writtenMargin) { lp.bottomMargin = writtenMargin; scroll.setLayoutParams(lp); }
    }
    private Rect bottomBound(DisplayCutout cutout) {
        if (cutout == null || cutout.getSafeInsetBottom() <= 0) return null;
        Rect b = cutout.getBoundingRectBottom();
        return !b.isEmpty() && b.left >= 0 && b.right <= metrics.widthPixels && b.top >= 0 && b.bottom == metrics.heightPixels ? b : null;
    }
    /** Physical display coordinates, independent of locale. Never infer a pocket from an inset height alone. */
    static boolean bottomPocket(Rect available, Rect cutout, int width, int height, int gap, Rect result) {
        if (width <= 0 || height <= 0 || cutout.isEmpty() || !Rect.intersects(available, cutout)) return false;
        int minY = Math.max(available.top, cutout.top) + gap;
        int maxY = Math.min(available.bottom, cutout.bottom) - gap - height;
        if (minY > maxY) return false;
        int y = Math.max(minY, Math.min(maxY, cutout.top + (cutout.height() - height) / 2));
        int leftEnd = Math.min(available.right, cutout.left), rightStart = Math.max(available.left, cutout.right);
        int leftSpace = leftEnd - available.left, rightSpace = available.right - rightStart;
        int space = Math.max(leftSpace, rightSpace);
        if (space < width + 2 * gap) return false;
        int x = (rightSpace >= leftSpace ? rightStart : available.left) + (space - width) / 2;
        result.set(x, y, x + width, y + height); return true;
    }
    int clipStack(Canvas canvas) {
        if (clipBottom < 0 || !canvas.getClipBounds(clip) || clip.bottom <= clipBottom) return -1;
        int save = canvas.save(); canvas.clipRect(clip.left, clip.top, clip.right, clipBottom); return save;
    }
    private void setClipBottom(int value) {
        if (clipBottom == value) return;
        clipBottom = value;
        if (scroll.getParent() instanceof View parent) parent.invalidate(); // Re-record the shared container's drawChild clip.
    }
    private void restoreButton() {
        if (stock != null && button.getLayoutParams() instanceof FrameLayout.LayoutParams p) {
            stock.width = p.width; stock.height = p.height; button.setLayoutParams(stock);
        }
        stock = null;
    }
    private void restoreScroll() {
        setClipBottom(-1);
        if (writtenMargin >= 0 && scroll.getLayoutParams() instanceof ViewGroup.MarginLayoutParams p && p.bottomMargin == writtenMargin && nativeMargin != writtenMargin) {
            p.bottomMargin = nativeMargin; scroll.setLayoutParams(p);
        }
        writtenMargin = -1;
    }
}
