// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

/**
 * F03: geometry of the user's charge-target tick on the lower camera ring. Display only: it never
 * limits charging or predicts time. The tick is a radial bar across the ring band, so it is told
 * apart from the charge arc by shape, not only by colour, and it never reaches closer to the lens
 * than the ring's own inner edge nor beyond its existing outer edge.
 */
public final class ChargeTarget {
    public static final int MIN = 50, MAX = 100, DEFAULT = 80;
    static final float WIDTH = 3f;

    private ChargeTarget() {}

    public static int clamp(int percent) { return Math.max(MIN, Math.min(MAX, percent)); }

    /** Shown exactly when the charge ring itself is drawn and a target is configured. */
    static boolean visible(boolean lowerRing, boolean enabled, boolean debugRings, boolean charging, int battery) {
        return lowerRing && enabled && (debugRings || charging && battery >= 0);
    }

    /**
     * {inner x, inner y, outer x, outer y} for a ring centred at (cx, cy). Same convention as the arc:
     * the original 6px stroke's outer edge stays fixed and thicker strokes grow inward; angles run
     * clockwise from {@code origin} degrees (screen top = -90).
     */
    static float[] tick(float cx, float cy, float radius, int stroke, int percent, float origin) {
        float outer = radius + DockConfig.CAMERA_STROKE_DEFAULT / 2f, inner = outer - stroke;
        double angle = Math.toRadians(origin + 360.0 * clamp(percent) / 100.0);
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        return new float[]{cx + inner * c, cy + inner * s, cx + outer * c, cy + outer * s};
    }
}
