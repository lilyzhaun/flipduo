package com.flipduo.module;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/** A rounded glass tint; reuses the live fill when present, otherwise owns one window blur region. */
final class MaterialPanel extends View {
    private static final int BLUR_RADIUS = 100;
    private static Method viewRoot, createBlur, radius, corner;
    private static boolean unavailable;
    private DockAppearance material;
    private Drawable blurDrawable;
    private boolean wantsBlur, listening, blurEnabled, suppressed, sharedFill, sharedFillVisible;
    private Drawable tintDrawable;
    private boolean translucentTint;
    private WindowManager windowManager;
    private final Consumer<Boolean> blurListener = enabled -> {
        blurEnabled = enabled;
        updateMaterial();
    };

    MaterialPanel(Context context) {
        super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setClickable(false); setLongClickable(false); setFocusable(false);
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), Math.min(view.getWidth(), view.getHeight()) / 2f);
            }
        });
    }
    void appearance(DockAppearance appearance, boolean useBlur) {
        if (material == appearance && wantsBlur == useBlur && tintDrawable != null) { invalidate(); return; }
        if (material != appearance) tintDrawable = null;
        material = appearance; wantsBlur = useBlur;
        if (isAttachedToWindow()) updateListener();
        updateMaterial();
    }
    void sharedFill(boolean active, boolean visible) {
        visible &= active;
        if (sharedFill == active && sharedFillVisible == visible) return;
        sharedFill = active; sharedFillVisible = visible;
        if (isAttachedToWindow()) updateListener();
        updateMaterial();
    }
    void suppress(boolean value) {
        if (suppressed == value) return;
        suppressed = value;
        if (isAttachedToWindow()) updateListener();
        updateMaterial();
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateListener(); updateMaterial();
    }
    @Override protected void onDetachedFromWindow() {
        if (listening) windowManager.removeCrossWindowBlurEnabledListener(blurListener);
        listening = false;
        releaseBlur();
        super.onDetachedFromWindow();
    }
    private void updateListener() {
        if (wantsBlur && !sharedFill && !suppressed && !unavailable && !listening) {
            windowManager = getContext().getSystemService(WindowManager.class);
            blurEnabled = windowManager.isCrossWindowBlurEnabled();
            listening = true;
            windowManager.addCrossWindowBlurEnabledListener(getContext().getMainExecutor(), blurListener);
        } else if ((!wantsBlur || sharedFill || suppressed || unavailable) && listening) {
            windowManager.removeCrossWindowBlurEnabledListener(blurListener);
            listening = false;
        }
    }
    private void updateMaterial() {
        // BackgroundBlurDrawable is submitted to the compositor independently of ancestor View.alpha.
        // Remove both layers while the native pager/card owns the slot; layout/theme callbacks must not recreate them.
        if (suppressed) { releaseBlur(); setBackground(null); return; }
        if (material == null) return;
        // The full live backdrop has already been blurred. Sampling it again for every
        // control creates overlapping regions (and clears more of the same window buffer).
        // While the source is pending, keep the existing denser, readable material instead.
        if (sharedFill) {
            releaseBlur(); showTint(sharedFillVisible && isAttachedToWindow()); return;
        }
        if (!wantsBlur || !blurEnabled || unavailable || !isAttachedToWindow()) {
            releaseBlur(); showTint(false); return;
        }
        try {
            if (viewRoot == null) {
                viewRoot = View.class.getDeclaredMethod("getViewRootImpl"); viewRoot.setAccessible(true);
                createBlur = Class.forName("android.view.ViewRootImpl").getMethod("createBackgroundBlurDrawable");
                Class<?> type = Class.forName("com.android.internal.graphics.drawable.BackgroundBlurDrawable");
                radius = type.getMethod("setBlurRadius", int.class);
                corner = type.getMethod("setCornerRadius", float.class);
            }
            if (blurDrawable == null) {
                blurDrawable = (Drawable) createBlur.invoke(viewRoot.invoke(this));
                radius.invoke(blurDrawable, BLUR_RADIUS);
            }
            corner.invoke(blurDrawable, Math.min(getWidth(), getHeight()) / 2f);
            Drawable tint = tint(true);
            Drawable background = getBackground();
            if (!(background instanceof LayerDrawable layers) || layers.getNumberOfLayers() != 2
                    || layers.getDrawable(0) != blurDrawable || layers.getDrawable(1) != tint) {
                // Detach before adopting reused children, preserving their invalidation callbacks.
                setBackground(null);
                setBackground(new LayerDrawable(new Drawable[]{blurDrawable, tint}));
            }
            syncBlurVisibility();
        } catch (ReflectiveOperationException error) {
            unavailable = true; releaseBlur(); updateListener();
            setBackground(null); showTint(false);
            NativeLog.warning("Compositor group blur unavailable; denser glass tint retained", error);
        }
    }
    private Drawable tint(boolean translucent) {
        if (tintDrawable == null || translucentTint != translucent) {
            tintDrawable = material.surface(translucent); translucentTint = translucent;
        }
        return tintDrawable;
    }
    private void showTint(boolean translucent) {
        Drawable tint = tint(translucent);
        if (getBackground() != tint) setBackground(tint);
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w,h,oldw,oldh); invalidateOutline();
        if (blurDrawable != null) updateMaterial();
    }
    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility); syncBlurVisibility();
    }
    @Override public void setAlpha(float alpha) {
        super.setAlpha(alpha); syncBlurVisibility();
    }
    private void syncBlurVisibility() {
        if (blurDrawable == null) return;
        // The compositor region has its own alpha; View.alpha alone only fades the tint buffer.
        blurDrawable.setAlpha(Math.round(getAlpha() * 255));
        boolean visible = !sharedFill && !suppressed && isShown() && wantsBlur && blurEnabled && getAlpha() > 0;
        if (blurDrawable.setVisible(visible, false) && visible) {
            // BackgroundBlurDrawable builds an empty path when laid out while hidden (the tools panel).
            android.graphics.Rect bounds = blurDrawable.getBounds();
            blurDrawable.setBounds(bounds.left, bounds.top, bounds.right, bounds.bottom);
        }
    }
    private void releaseBlur() {
        if (blurDrawable != null) blurDrawable.setVisible(false, false);
        blurDrawable = null;
    }
    String describeBlur() {
        return "requested=" + wantsBlur + " enabled=" + blurEnabled + " suppressed=" + suppressed
                + " sharedFill=" + sharedFill + " sharedVisible=" + sharedFillVisible + " drawable=" + blurDrawable;
    }
}
