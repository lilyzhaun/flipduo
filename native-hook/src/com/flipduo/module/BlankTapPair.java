// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

/** Two completed taps; never confirms on the second DOWN (it may still become a drag). */
final class BlankTapPair {
    private final int moveSlop, pairSlop, timeout, hold;
    private boolean tracking, second;
    private long firstUp = -1, down;
    private float x, y;
    BlankTapPair(int moveSlop, int pairSlop, int timeout, int hold) {
        this.moveSlop = moveSlop; this.pairSlop = pairSlop; this.timeout = timeout; this.hold = hold;
    }
    void cancel() { tracking = second = false; firstUp = -1; }
    void down(long now, float px, float py) {
        second = !tracking && firstUp >= 0 && now > firstUp && now - firstUp <= timeout && near(px, py, pairSlop);
        tracking = true; firstUp = -1; down = now; x = px; y = py;
    }
    private boolean near(float px, float py, int slop) { float dx = x-px, dy = y-py; return dx*dx + dy*dy <= slop*slop; }
    void move(float px, float py) { if (tracking && !near(px, py, moveSlop)) cancel(); }
    boolean up(long now, float px, float py) {
        move(px, py);
        if (!tracking || now - down >= hold) { cancel(); return false; }
        boolean match = second; cancel(); if (!match) firstUp = now; return match;
    }
}
