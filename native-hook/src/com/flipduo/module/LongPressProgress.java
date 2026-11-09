// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

/** Visual clock only. Android's View remains the sole owner of long-click timing and dispatch. */
final class LongPressProgress {
    static final float LIMIT = .96f, STATIC = .08f;
    private boolean active;
    private long revealAt;
    private int duration;

    void start(long now, int tapTimeout, int longPressTimeout) {
        active = true;
        revealAt = now + tapTimeout;
        duration = Math.max(1, longPressTimeout - tapTimeout);
    }
    boolean active() { return active; }
    float fraction(long now, boolean animate) {
        if (!active || now < revealAt) return 0;
        // Never claim completion before the platform actually invokes its long-click listener.
        return animate ? Math.min(LIMIT, (now - revealAt) / (float) duration) : STATIC;
    }
    boolean needsFrame(long now) { return active && fraction(now, true) < LIMIT; }
    void clear() { active = false; }
}
