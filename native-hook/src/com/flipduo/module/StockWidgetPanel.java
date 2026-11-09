package com.flipduo.module;

import android.graphics.Rect;
import android.graphics.Region;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewTreeObserver;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

/** Temporarily presents the existing native pager. Never reparents, recreates, rescales or edits a widget. */
final class StockWidgetPanel {
    private final View pager;
    private final Runnable dismiss;
    private final Runnable prepareMaterial;
    private final IslandSwipe swipe;
    private FrameLayout.LayoutParams originalLayout;
    private View.AccessibilityDelegate originalDelegate;
    private float originalAlpha;
    private boolean expanded;
    private Rect originalClip;
    private ViewTreeObserver clipObserver;
    private boolean materialPending;
    private final ViewTreeObserver.OnPreDrawListener clipFrame = this::prepareVisibleFrame;

    StockWidgetPanel(View pager, Runnable dismiss) {
        this(pager, dismiss, () -> { });
    }
    StockWidgetPanel(View pager, Runnable dismiss, Runnable prepareMaterial) {
        this.pager = pager; this.dismiss = dismiss; this.prepareMaterial = prepareMaterial;
        swipe = new IslandSwipe(dismiss);
    }
    private boolean prepareVisibleFrame() {
        clipToOriginalSlot();
        if (materialPending && expanded && pager.isShown() && pager.getWidth() > 0 && pager.getHeight() > 0) {
            materialPending = false; // Consume before calling OEM code; its scene refresh must not rearm this frame.
            prepareMaterial.run();
        }
        return true;
    }
    boolean expanded() { return expanded; }
    void materialChanged() {
        if (!expanded) return;
        materialPending = true; pager.invalidate();
    }
    boolean touch(MotionEvent event, boolean animations, int duration) {
        boolean handled = swipe.touch(pager, event, animations, duration);
        if (expanded) clipToOriginalSlot();
        return handled;
    }
    boolean cancelChild() { return swipe.cancelChild(); }

    void open(boolean bottom, int nativeHeight, int paddingTop, int paddingBottom) {
        open(bottom ? 2 : 0, nativeHeight, paddingTop, paddingBottom);
    }
    void open(int rotation, int nativeHeight, int paddingTop, int paddingBottom) {
        if (expanded) return;
        originalLayout = new FrameLayout.LayoutParams((FrameLayout.LayoutParams) pager.getLayoutParams());
        if (originalLayout.width <= 0 || nativeHeight <= paddingTop + paddingBottom || nativeHeight > DockSpec.HEIGHT)
            throw new IllegalStateException("Native widget pager has unsupported resource geometry");
        originalAlpha = pager.getAlpha(); originalDelegate = pager.getAccessibilityDelegate();
        originalClip = pager.getClipBounds();
        boolean bottom = rotation == 2;
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(originalLayout.width, nativeHeight-paddingTop-paddingBottom,
                (bottom ? Gravity.LEFT | Gravity.BOTTOM : Gravity.RIGHT | Gravity.TOP));
        p.leftMargin = p.rightMargin = (DockSpec.WIDTH-p.width)/2;
        p.topMargin = paddingTop; p.bottomMargin = DockSpec.HEIGHT-nativeHeight+paddingBottom;
        if (CoverRotation.landscape(rotation)) {
            int shortEdge = p.width; p.width = p.height; p.height = shortEdge;
            p.gravity = CoverRotation.gravity(rotation);
            p.leftMargin = rotation == 1 ? paddingTop : DockSpec.HEIGHT-nativeHeight+paddingBottom;
            p.rightMargin = rotation == 3 ? paddingTop : DockSpec.HEIGHT-nativeHeight+paddingBottom;
            p.topMargin = p.bottomMargin = (DockSpec.WIDTH - shortEdge) / 2;
        }
        expanded = true;
        materialPending = true;
        pager.setLayoutParams(p);
        pager.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                if (originalDelegate != null) originalDelegate.onInitializeAccessibilityNodeInfo(host, info);
                else super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_COLLAPSE, "收起小组件并返回导航"));
            }
            @Override public boolean performAccessibilityAction(View host, int action, Bundle args) {
                if (action == AccessibilityNodeInfo.ACTION_COLLAPSE) { dismiss.run(); return true; }
                return originalDelegate != null ? originalDelegate.performAccessibilityAction(host, action, args)
                        : super.performAccessibilityAction(host, action, args);
            }
        });
        present();
    }

    void present() {
        if (!expanded) return;
        ViewTreeObserver observer = pager.getViewTreeObserver();
        if (observer != clipObserver) {
            unbindClipObserver(); clipObserver = observer; clipObserver.addOnPreDrawListener(clipFrame);
            materialPending = true;
        }
        if (pager.getAlpha() != 1) pager.setAlpha(1);
        if (pager.getVisibility() != View.VISIBLE) pager.setVisibility(View.VISIBLE);
        pager.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void pause() {
        if (!expanded) return;
        // A temporary window detach interrupts the gesture, not the logical panel or its current native page.
        // Cancel the old animator so its completion callback cannot dismiss the restored panel later.
        swipe.reset(); materialPending = true; unbindClipObserver(); pager.setClipBounds(originalClip);
    }

    private void unbindClipObserver() {
        if (clipObserver != null && clipObserver.isAlive()) clipObserver.removeOnPreDrawListener(clipFrame);
        clipObserver = null;
    }

    void close() {
        if (!expanded) return;
        expanded = false;
        materialPending = false;
        pager.setVisibility(View.GONE);
        swipe.reset();
        unbindClipObserver(); pager.setClipBounds(originalClip);
        pager.setAlpha(originalAlpha); pager.setLayoutParams(originalLayout);
        pager.setAccessibilityDelegate(originalDelegate);
        pager.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    void addTouchableRegion(Region region) {
        if (!expanded || pager.getVisibility() != View.VISIBLE) return;
        Rect rect = new Rect(); pager.getHitRect(rect);
        if (rect.intersect(pager.getLeft(), pager.getTop(), pager.getRight(), pager.getBottom())) region.op(rect, Region.Op.UNION);
    }

    private void clipToOriginalSlot() {
        // The background window spans the camera column too. Only the pager must slide out of its original slot,
        // including when the controls are at the bottom; never animate widget content over the lenses.
        Rect clip = new Rect(0, Math.min(pager.getHeight(), Math.max(0, (int) Math.ceil(-pager.getTranslationY()))),
                pager.getWidth(), pager.getHeight());
        if (originalClip != null && !clip.intersect(originalClip)) clip.setEmpty();
        pager.setClipBounds(clip);
    }
}
