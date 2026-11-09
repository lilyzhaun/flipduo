// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

/**
 * F03 step 2: at most one light confirmation per plug-in session when the battery first reaches the target.
 * A session starts when the charger is plugged in and ends when it is unplugged. Nothing is armed when the
 * first sample (plug-in, host start or remount) is already at/above the target, or when the target is moved
 * below the current level, so nothing is ever reported late. A crossing that happens while the cover ring is
 * not visible (locked, inner screen, screen off) is consumed silently and never replayed. Pure Java.
 */
final class ChargeArrival {
    /** One breath of the existing breathing cycle. */
    static final long SHOW_MS = 1600;

    private boolean seen, plugged, armed, done;
    private int target = -1;

    /** @param target the configured target, or -1 when the confirmation (or target tick) is off. */
    boolean sample(boolean plugged, int level, int target, boolean visible) {
        boolean below = level >= 0 && target >= 0 && level < target;
        if (!plugged) { armed = false; done = false; }
        else if (!seen || !this.plugged) { done = false; armed = below; } // first sample or a new plug-in session
        else if (target != this.target) armed = !done && below;            // re-targeting never reports late
        seen = true; this.plugged = plugged; this.target = target;
        if (!armed || level < 0 || target < 0 || level < target) return false;
        armed = false; done = true;
        return visible;
    }
}
