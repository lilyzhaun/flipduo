package com.flipduo.module;

import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import java.lang.reflect.Method;

/** One real ViewRoot blur drawable, painted BEFORE the window's existing background and controls. */
final class BlurFillWindowBlur {
    private static Method viewRoot, create, radius, corner;
    private final View view;
    private final Drawable blur;
    private final Rect clip = new Rect();
    private Drawable base;
    private LayerDrawable layers;

    BlurFillWindowBlur(View view) throws ReflectiveOperationException {
        if (viewRoot == null) {
            viewRoot = View.class.getDeclaredMethod("getViewRootImpl"); viewRoot.setAccessible(true);
            create = Class.forName("android.view.ViewRootImpl").getMethod("createBackgroundBlurDrawable");
            Class<?> type = Class.forName("com.android.internal.graphics.drawable.BackgroundBlurDrawable");
            radius = type.getMethod("setBlurRadius", int.class);
            corner = type.getMethod("setCornerRadius", float.class);
        }
        this.view = view;
        blur = (Drawable) create.invoke(viewRoot.invoke(view));
        blur.setVisible(false, false);
        radius.invoke(blur, 110); corner.invoke(blur, 0f);
    }
    void background(Drawable background) {
        if (layers == null || base != background) {
            // Detach first: View.setBackground would otherwise clear the reused child's callback
            // AFTER LayerDrawable had adopted it, breaking the existing column animation.
            if (view.getBackground() == background) view.setBackground(null);
            base = background;
            layers = new LayerDrawable(base == null ? new Drawable[]{blur} : new Drawable[]{blur, base}) {
                @Override protected void onBoundsChange(Rect bounds) {
                    super.onBoundsChange(bounds);
                    blur.setBounds(clip); // LayerDrawable must not expand a gesture's side-only region.
                }
            };
        }
        if (view.getBackground() != layers) {
            // View propagates its visibility to a new background, including LayerDrawable children.
            // Installing a wrapper must not make an uncommitted or paused blur visible.
            boolean visible = blur.isVisible();
            view.setBackground(layers);
            blur.setVisible(visible, false);
        }
    }
    void show(Rect bounds, float alpha, boolean visible) {
        if (!clip.equals(bounds)) { clip.set(bounds); blur.setBounds(clip); }
        blur.setAlpha(Math.round(alpha * 255));
        if (blur.setVisible(visible, false) && visible)
            blur.setBounds(clip.left, clip.top, clip.right, clip.bottom);
        view.invalidate();
    }
    void close() {
        blur.setVisible(false, false);
        if (layers != null && view.getBackground() == layers) {
            view.setBackground(null);
            view.setBackground(base);
        }
        layers = null; base = null;
        view.invalidate();
    }
}
