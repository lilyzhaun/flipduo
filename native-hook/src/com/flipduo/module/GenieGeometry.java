package com.flipduo.module;

/** Shared, gently eased travel into the hidden lens circle. The distant sheet travels further while
 * entry and exit use the same reversible shape. Visual rounding takes priority over exact point-wise
 * affine motion. Sine easing: https://easings.net/#easeInOutSine
 */
final class GenieGeometry {
    private float width, height, lensX, lensY, receiverRadius, progress;
    private boolean reversed;

    void configure(float width, float height, float cameraLeft, float cameraTop,
            float cameraRight, float cameraBottom, boolean reversed) {
        configure(width, height, cameraLeft, cameraTop, cameraRight, cameraBottom, reversed, true);
    }
    void configure(float width, float height, float cameraLeft, float cameraTop,
            float cameraRight, float cameraBottom, boolean reversed, boolean opticalProfile) {
        this.width = width; this.height = height; this.reversed = reversed;
        // Avoidance bounds are NOT opaque. Use the known bixi optical profile and leave a margin
        // inside the lens, so no complete black disk can be exposed outside the hardware rim.
        if (opticalProfile) {
            lensX = width - (CoverGeometry.WIDTH - CameraOcclusion.X);
            lensY = CameraOcclusion.LOWER_Y; receiverRadius = CameraOcclusion.SAFE_RADIUS - 8;
        } else {
            // Uncalibrated covers still get the same reversible deformation, but collapse to a
            // POINT. Never leave a full black disk assuming an avoidance rectangle is opaque.
            float quarterHeight = (cameraBottom - cameraTop) / 4f;
            lensX = reversed ? width - (cameraLeft + cameraRight) / 2f : (cameraLeft + cameraRight) / 2f;
            lensY = reversed ? height - cameraTop - quarterHeight : cameraBottom - quarterHeight;
            receiverRadius = 0;
        }
    }

    void frame(float clock) {
        progress = sine(clock);
    }

    void point(float x, float y, float[] out) {
        if (reversed) { x = width - x; y = height - y; }
        float dx = x - lensX, dy = y - lensY;
        float distance = (float) Math.hypot(dx, dy);
        float radius = receiverRadius;
        // Interior optical pixels can stay put; every actual sheet boundary lies outside this radius.
        float scale = distance > radius ? 1 + progress * (radius / distance - 1) : 1;
        float px = lensX + dx * scale, py = lensY + dy * scale;
        out[0] = reversed ? width - px : px;
        out[1] = reversed ? height - py : py;
    }
    void outlinePoint(float x, float y, float[] out) { point(x, y, out); }

    static float cornerRelease(float clock) { return sine(Math.min(1, clock / .12f)); }
    // A camera-side cap is already near its physical edge. Restoring it only during the last 12%
    // of entry looked like a sudden fill; distribute this geometry over half the shared transition.
    static float cameraCornerRelease(float clock) { return sine(Math.min(1, clock / .5f)); }
    private static float sine(float value) { return (float) ((1 - Math.cos(Math.PI * value)) / 2); }

    static int duration(int configuredMs) {
        // User rejected the slow 480ms / angular prototype. Shorten only this visual transition:
        // default 320ms, bounded 240–400ms; saved settings and native/control timings stay untouched.
        return Math.max(240, Math.min(400, Math.round(configuredMs * 1.6f)));
    }
}
