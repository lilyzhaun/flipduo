// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

/**
 * Minute-boundary clock driver for the dock header. Pure Java; the view supplies scheduling and drawing.
 * Each callback is a single one-shot posted for the next wall-clock minute, recomputed from the current
 * time every time, so delays never accumulate drift. Stopped means no pending callback at all.
 */
final class MinuteClock {
    interface Host {
        long now();
        void post(Runnable task, long delayMs);
        void cancel(Runnable task);
        /** Redraw the clock and refresh its accessibility text from the current wall clock. */
        void tick();
    }

    /** Land just after the boundary so a slightly early callback cannot keep showing the previous minute. */
    static final long MARGIN_MS = 50;
    private final Host host;
    private final Runnable task = this::fire;
    private boolean running;

    MinuteClock(Host host) { this.host = host; }

    static long delayToNextMinute(long now) { return 60_000L - Math.floorMod(now, 60_000L) + MARGIN_MS; }

    boolean running() { return running; }

    /** Start with an immediate fresh reading, or stop and drop the pending callback. Idempotent. */
    void update(boolean wanted) {
        if (wanted == running) return;
        running = wanted;
        host.cancel(task);
        if (wanted) { host.tick(); schedule(); }
    }

    /** Time set or time-zone change: redraw now and re-anchor to the new wall clock. */
    void timeChanged() {
        if (!running) return;
        host.cancel(task); host.tick(); schedule();
    }

    private void fire() {
        if (!running) return;
        host.tick(); schedule();
    }

    private void schedule() { host.post(task, delayToNextMinute(host.now())); }
}
