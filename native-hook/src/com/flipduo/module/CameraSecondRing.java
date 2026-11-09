// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

/** Wall-clock anchored, stateless sweep: no accumulated frame delta or lifecycle catch-up animation. */
final class CameraSecondRing {
    static final long RESET_MS = 240;
    static float head(long wallTime) { return CameraStatusState.seconds(wallTime) * 360; }
    static float tailStart(long wallTime) {
        return Math.min(1f, Math.floorMod(wallTime, 60000L) / (float) RESET_MS) * 360;
    }
    static float tailSweep(long wallTime) { return 360 - tailStart(wallTime); }

    /** Live frames may miss wall-clock :00.000–:00.240. Start the same short wipe when rollover is observed. */
    static final class ObservedTail {
        private long previousWall = Long.MIN_VALUE, previousFrame, started = -1;
        private float start = 360;
        void reset() { previousWall = Long.MIN_VALUE; started = -1; start = 360; }
        void frame(long wallTime, long elapsed, boolean enabled) {
            if (!enabled) { reset(); return; }
            if (previousWall != Long.MIN_VALUE) {
                long frameGap = elapsed - previousFrame;
                boolean continuous = frameGap >= 0 && frameGap <= 1500
                        && Math.abs((wallTime - previousWall) - frameGap) <= 100;
                if (!continuous) started = -1; // No stale wipe after sleep, time correction or a long frame gap.
                else if (Math.floorDiv(wallTime, 60000L) == Math.floorDiv(previousWall, 60000L) + 1) started = elapsed;
            }
            previousWall = wallTime; previousFrame = elapsed;
            start = started < 0 ? 360 : Math.min(1f, Math.max(0, elapsed - started) / (float) RESET_MS) * 360;
        }
        float start() { return start; }
        float sweep() { return 360 - start; }
    }
}
