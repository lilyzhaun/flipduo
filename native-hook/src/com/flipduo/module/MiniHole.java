package com.flipduo.module;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * FlipHome's record of a native mini window in the widget slot. The app task lies below this existing overlay window,
 * so only the slot is left transparent; the black column, corners and every other pixel are drawn as before.
 */
final class MiniHole {
    final int[] slot; // Control-canvas coordinates, see MiniSlot.dockRect().
    final int rotation, display, task;
    final float radius;
    private Drawable base, holed;

    MiniHole(int[] slot, int rotation, int display) { this(slot, rotation, display, -1, 0); }
    private MiniHole(int[] slot, int rotation, int display, int task, float radius) {
        this.slot = slot; this.rotation = rotation; this.display = display; this.task = task; this.radius = radius;
    }
    MiniHole occupy(int task, float radius) { return new MiniHole(slot, rotation, display, task, Math.max(0, radius)); }

    /** The root background with the slot cut out; stable identity per base drawable. */
    Drawable background(Drawable under, View dock) {
        if (under == null) return null;
        if (holed == null || base != under) { base = under; holed = new Hole(under, dock); }
        return holed;
    }

    private final class Hole extends Drawable implements Drawable.Callback {
        private final Drawable under;
        private final View dock;
        private final Path path = new Path();
        Hole(Drawable under, View dock) { this.under = under; this.dock = dock; }
        @Override public void draw(Canvas canvas) {
            under.setCallback(this); // A background swap clears the old root callback; reconnect after it.
            // The dock is a child of the root this background belongs to, laid out before drawing.
            float sx = dock.getWidth() / (float) DockSpec.WIDTH, sy = dock.getHeight() / (float) DockSpec.HEIGHT;
            path.rewind();
            path.addRoundRect(dock.getLeft() + slot[0] * sx, dock.getTop() + slot[1] * sy,
                    dock.getLeft() + slot[2] * sx, dock.getTop() + slot[3] * sy, radius, radius, Path.Direction.CW);
            canvas.save(); canvas.clipOutPath(path); under.draw(canvas); canvas.restore();
        }
        @Override protected void onBoundsChange(Rect bounds) { under.setCallback(this); under.setBounds(bounds); }
        @Override public void invalidateDrawable(Drawable who) { invalidateSelf(); }
        @Override public void scheduleDrawable(Drawable who, Runnable what, long when) { scheduleSelf(what, when); }
        @Override public void unscheduleDrawable(Drawable who, Runnable what) { unscheduleSelf(what); }
        @Override public void setAlpha(int alpha) { under.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { under.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
