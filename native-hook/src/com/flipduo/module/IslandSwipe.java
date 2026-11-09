package com.flipduo.module;

import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.PathInterpolator;

/** Upward drag dismisses the native card, never the event or its pending actions. */
final class IslandSwipe {
    private final Runnable close;
    private View root;
    private VelocityTracker velocity;
    private int pointer = -1;
    private float x, y, start;
    private boolean dragging, rejected, cancelChild;

    IslandSwipe(Runnable close) { this.close = close; }

    boolean touch(View view, MotionEvent event, boolean animations, int duration) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (root != null) root.animate().cancel();
            recycle(); root = view; pointer = event.getPointerId(0);
            x = event.getRawX(); y = event.getRawY(); start = view.getTranslationY();
            dragging = rejected = cancelChild = false;
            velocity = VelocityTracker.obtain(); velocity.addMovement(event);
            return false;
        }
        if (pointer < 0 || root != view) return false;
        int index = event.findPointerIndex(pointer);
        if (velocity != null) velocity.addMovement(event);
        if (index < 0 || (action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.getActionIndex()) == pointer)) {
            boolean handled = dragging; settle(0, animations, duration, false); return handled;
        }
        float dx = event.getRawX(index) - x, dy = event.getRawY(index) - y;
        int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        if (action == MotionEvent.ACTION_MOVE) {
            if (!dragging && !rejected) {
                if (Math.abs(dx) > slop && Math.abs(dx) >= Math.abs(dy)) rejected = true;
                else if (dy > slop) rejected = true;
                else if (dy < -slop && -dy > Math.abs(dx)) { dragging = true; cancelChild = true; }
            }
            if (dragging) view.setTranslationY(Math.max(-view.getHeight(), Math.min(0, start + dy)));
            return dragging;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            boolean handled = dragging;
            if (dragging) {
                velocity.computeCurrentVelocity(1000);
                boolean dismiss = action == MotionEvent.ACTION_UP && (-view.getTranslationY() >= Math.max(slop * 2, view.getHeight() * .12f)
                        || velocity.getYVelocity(pointer) < -ViewConfiguration.get(view.getContext()).getScaledMinimumFlingVelocity());
                settle(dismiss ? -view.getHeight() : 0, animations, duration, dismiss);
            } else recycle();
            return handled;
        }
        return dragging;
    }

    boolean cancelChild() { boolean value = cancelChild; cancelChild = false; return value; }

    private void settle(float target, boolean animations, int duration, boolean dismiss) {
        recycle(); dragging = false;
        View view = root;
        if (!animations || duration == 0 || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
            view.setTranslationY(target); if (dismiss) finish(view); return;
        }
        view.animate().translationY(target).setDuration(Math.min(250, duration))
                .setInterpolator(new PathInterpolator(.32f, .72f, 0, 1))
                .withEndAction(dismiss ? () -> finish(view) : null).start();
    }

    private void finish(View view) {
        close.run(); // The owner hides the native window synchronously, before this transform is restored.
        if (root == view) reset();
    }

    void reset() {
        recycle(); dragging = rejected = cancelChild = false;
        if (root != null) { root.animate().cancel(); root.setTranslationY(0); root = null; }
    }
    private void recycle() { pointer = -1; if (velocity != null) { velocity.recycle(); velocity = null; } }
}
