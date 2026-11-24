// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import java.util.function.Predicate;

/** Own only a clearly leftward gesture inside the open panel; all ordinary events follow normal View dispatch. */
final class StockSearchSwipe {
    private final View panel;
    private final Runnable close;
    private final Predicate<MotionEvent> children;
    private final int slop;
    private final float fling;
    private VelocityTracker velocity;
    private float downX, downY, startOffset;
    private boolean dragging, drain, leaving;

    StockSearchSwipe(View panel, Runnable close, Predicate<MotionEvent> children) {
        this.panel = panel; this.close = close; this.children = children;
        ViewConfiguration config = ViewConfiguration.get(panel.getContext());
        slop = config.getScaledTouchSlop();
        fling = Math.max(config.getScaledMinimumFlingVelocity(), 400 * panel.getResources().getDisplayMetrics().density);
    }

    boolean dispatch(MotionEvent event, boolean allowStart) {
        if (leaving) return true;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            releaseTracker(); dragging = drain = false;
            if (allowStart && panel.getWidth() > 0) {
                downX = event.getRawX(); downY = event.getRawY();
                velocity = VelocityTracker.obtain(); track(event);
            }
            return children.test(event);
        }
        if (drain) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) drain = false;
            return true;
        }
        if (velocity == null) return children.test(event);
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() != 1) {
            boolean owned = dragging;
            releaseTracker(); dragging = false;
            if (!owned) return children.test(event);
            drain = action != MotionEvent.ACTION_CANCEL; settle(false); return true;
        }
        track(event);
        float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
        if (!dragging && (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP)
                && Math.max(Math.abs(dx), Math.abs(dy)) > slop) {
            if (-dx <= Math.abs(dy) * 1.35f) {
                releaseTracker(); return children.test(event); // Direction belongs to the child for the rest of this gesture.
            }
            dragging = true; panel.animate().cancel(); startOffset = panel.getTranslationX();
            MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL);
            try { children.test(cancel); } finally { cancel.recycle(); }
        }
        if (dragging) panel.setTranslationX(Math.max(-panel.getWidth(), Math.min(0, startOffset + dx)));
        if (action == MotionEvent.ACTION_UP) {
            boolean owned = dragging;
            if (owned) {
                velocity.computeCurrentVelocity(1000);
                float vx = velocity.getXVelocity(), distance = -panel.getTranslationX();
                boolean far = distance >= Math.max(2 * slop, panel.getWidth() * .25f);
                boolean flick = -dx >= 2 * slop && vx <= -fling;
                releaseTracker(); dragging = false; settle((far && vx < fling) || flick);
            } else releaseTracker();
            return owned || children.test(event);
        }
        return dragging || children.test(event);
    }

    private void track(MotionEvent event) {
        // The panel translates under the finger. Feed screen coordinates to the velocity tracker too.
        MotionEvent screen = MotionEvent.obtain(event);
        screen.offsetLocation(event.getRawX() - event.getX(), event.getRawY() - event.getY());
        try { velocity.addMovement(screen); } finally { screen.recycle(); }
    }

    private void settle(boolean dismiss) {
        leaving = dismiss;
        float end = dismiss ? -panel.getWidth() : 0;
        DockConfig config = NativeModule.config();
        if (!config.animations || !ValueAnimator.areAnimatorsEnabled()) {
            panel.setTranslationX(end); if (dismiss) close.run(); return;
        }
        panel.animate().translationX(end).setDuration(Math.min(180, config.animationMs))
                .setInterpolator(new DecelerateInterpolator()).withEndAction(dismiss ? close : null).start();
    }

    private void releaseTracker() {
        if (velocity != null) { velocity.recycle(); velocity = null; }
    }
    void reset() {
        releaseTracker(); panel.animate().cancel(); dragging = drain = leaving = false;
    }
}
