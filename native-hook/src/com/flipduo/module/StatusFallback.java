// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

/**
 * Degraded status reading while no SystemUI status has arrived, kept apart from the clock.
 * Runs only while wanted (visible, screen on, still no SystemUI state): a first read at once, then
 * {@link #FAST_READS} reads {@link #FAST_MS} apart, then one per {@link #SLOW_MS}. Each new start
 * (attach, screen on, shown again) restarts the fast phase; stopping drops the pending read.
 */
final class StatusFallback {
    interface Host {
        void post(Runnable task, long delayMs);
        void cancel(Runnable task);
        void read();
    }

    static final long FAST_MS = 2500, SLOW_MS = 60_000;
    static final int FAST_READS = 12;
    private final Host host;
    private final Runnable task = this::fire;
    private boolean running;
    private int reads;

    StatusFallback(Host host) { this.host = host; }

    boolean running() { return running; }

    void update(boolean wanted) {
        if (wanted == running) return;
        running = wanted;
        host.cancel(task);
        if (wanted) { reads = 0; fire(); }
    }

    private void fire() {
        if (!running) return;
        host.read();
        reads++;
        host.post(task, reads < FAST_READS ? FAST_MS : SLOW_MS);
    }
}
