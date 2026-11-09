package com.flipduo.module;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.Path;
import android.view.animation.PathInterpolator;
import android.view.animation.LinearInterpolator;

/** Presentation only, inside the existing watch window. Never owns a gesture, task or input region. */
final class ColumnTransition extends Drawable {
    private final Runnable finished;
    private ValueAnimator animator;
    private Drawable column, nativeBackground;
    private int nativeWidth, nativeHeight, alpha = 255;
    private boolean ordinary, bottom;
    private int rotation;
    private boolean opticalProfile = true;
    private float opacity;
    private boolean liveFill;
    private Runnable frameListener;
    private final CameraSuction suction = new CameraSuction();
    private final Path silhouette = new Path();
    private final Rect camera = new Rect();

    ColumnTransition(Runnable finished) { this.finished = finished; }
    void frameListener(Runnable listener) { frameListener = listener; }
    void liveFill(boolean value) {
        if (liveFill != value) { liveFill = value; invalidateSelf(); }
    }
    private void frameChanged() {
        invalidateSelf();
        if (frameListener != null) frameListener.run();
    }

    void camera(Rect area) {
        camera(area, true);
    }
    void camera(Rect area, boolean opticalProfile) {
        this.opticalProfile = opticalProfile;
        if (area == null) camera.setEmpty(); else camera.set(area);
    }

    void bind(Drawable column, Drawable nativeBackground, int width, int height, boolean bottom) {
        bind(column, nativeBackground, width, height, bottom ? 2 : 0);
    }
    void bind(Drawable column, Drawable nativeBackground, int width, int height, int rotation) {
        boolean bottom = rotation == 2;
        boolean changed = this.column != column || this.nativeBackground != nativeBackground
                || nativeWidth != width || nativeHeight != height || this.rotation != rotation;
        this.column = column; this.nativeBackground = nativeBackground;
        nativeWidth = width; nativeHeight = height; this.bottom = bottom;
        this.rotation = rotation;
        if (changed) invalidateSelf();
    }

    void update(boolean ordinary, boolean animate, int durationMs) {
        float target = ordinary ? 1 : 0;
        if (this.ordinary == ordinary && (animator != null || opacity == target)) {
            if (!animate) snap();
            return; // Repeated OEM scene callbacks must not restart the transition.
        }
        boolean reverse = ordinary && opacity > 0 && opacity < 1;
        this.ordinary = ordinary;
        cancelAnimator();
        if (!animate || camera.isEmpty() && ordinary && !reverse || opacity == target) {
            opacity = target; frameChanged(); return;
        }
        ValueAnimator next = ValueAnimator.ofFloat(opacity, target);
        animator = next;
        int duration = camera.isEmpty() ? durationMs : GenieGeometry.duration(durationMs);
        next.setDuration(Math.max(80, Math.round(duration * Math.abs(target - opacity))));
        // Shared presentation clock; geometry handles gentle easing and rounding, symmetrically.
        // Includes a fresh 0 -> 1 entry from the hidden lens.
        // Unknown camera keeps the existing safe fade and direct first entry.
        next.setInterpolator(camera.isEmpty() ? new PathInterpolator(.32f, .72f, 0, 1)
                : new LinearInterpolator());
        next.addUpdateListener(value -> { opacity = (float) value.getAnimatedValue(); frameChanged(); });
        next.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (animator != animation) return;
                animator = null; opacity = target; frameChanged();
                if (!ColumnTransition.this.ordinary) finished.run();
            }
        });
        next.start();
    }

    // The first entry frame has no visible ink yet, but must already have the full window geometry
    // and a bound drawable, otherwise the growth would be cropped to the old 664px native slot.
    boolean visible() { return ordinary || opacity > 0; }
    float opacity() { return opacity; }
    void snap() { cancelAnimator(); opacity = ordinary ? 1 : 0; frameChanged(); }
    void reset() { ordinary = false; snap(); }
    private void cancelAnimator() {
        ValueAnimator previous = animator;
        animator = null; // cancel() also calls onAnimationEnd; it must not shrink a newly entered app.
        if (previous != null) previous.cancel();
    }

    @Override public void draw(Canvas canvas) {
        // Blur is supplied entirely by the compositor, including the app-corner cut-outs.
        // Never draw a black fallback, corner mask, native slot or suction sheet in this mode.
        if (liveFill) return;
        Rect bounds = getBounds();
        boolean horizontal = CoverRotation.landscape(rotation);
        if (nativeBackground != null && opacity < 1) {
            int width = Math.min(horizontal ? nativeHeight : nativeWidth, bounds.width());
            int height = Math.min(horizontal ? nativeWidth : nativeHeight, bounds.height());
            boolean leftEdge = rotation == 1 || rotation == 2, bottomEdge = rotation == 2 || rotation == 3;
            int left = leftEdge ? bounds.left : bounds.right - width;
            int top = bottomEdge ? bounds.bottom - height : bounds.top;
            canvas.save(); canvas.clipRect(left, top, left + width, top + height); canvas.translate(left, top);
            nativeBackground.setBounds(0, 0, width, height);
            drawAlpha(nativeBackground, canvas, alpha);
            canvas.restore();
        }
        if (column != null && opacity > 0) {
            if (horizontal) {
                canvas.save(); CoverRotation.columnCanvas(canvas, bounds, rotation);
                drawColumn(canvas, new Rect(0, 0, bounds.height(), bounds.width())); canvas.restore();
            } else drawColumn(canvas, bounds);
        }
    }
    private void drawColumn(Canvas canvas, Rect bounds) {
        column.setBounds(bounds);
        if (!camera.isEmpty() && column instanceof DockAppearance.Column shape && opacity < 1) {
            // Dynamic controls converge to the original analytic geometry. Settled ink below
            // uses the authoritative exact painter, never the rounded animation approximation.
            int left = bottom ? 0 : CoverGeometry.WIDTH - bounds.width();
            suction.configure(shape, bounds, camera, left, bounds.top, opticalProfile);
            suction.path(1 - opacity, silhouette);
            // Do not clip the rectangular app-avoidance envelope: the display around and between
            // the two physical lenses must move with the same sheet. The optics themselves occlude it.
            suction.draw(canvas, silhouette, alpha);
        } else drawAlpha(column, canvas, Math.round(alpha * (camera.isEmpty() ? opacity : 1)));
    }
    private static void drawAlpha(Drawable drawable, Canvas canvas, int alpha) {
        int previous = drawable.getAlpha();
        drawable.setAlpha(Math.round(previous * alpha / 255f));
        drawable.draw(canvas);
        drawable.setAlpha(previous);
    }
    @Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
    @Override public int getAlpha() { return alpha; }
    @Override public void setColorFilter(ColorFilter filter) { if (column != null) column.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
