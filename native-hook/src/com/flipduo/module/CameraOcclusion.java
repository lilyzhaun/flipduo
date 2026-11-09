package com.flipduo.module;

/** MIX Flip 2 optics model shared with the existing photo-calibrated device artwork.
 * A DisplayCutout bounding rectangle is an app avoidance envelope, NOT an opaque physical rectangle.
 * Only bixi uses this profile. The receiver stays conservatively inside the lower camera module;
 * final optical alignment remains subject to on-device visual verification.
 */
final class CameraOcclusion {
    static final float X = 1008, MAIN_Y = 842, LOWER_Y = 1194, RADIUS = 154;
    static final float SAFE_RADIUS = RADIUS - 24;
    // An optical artwork/occlusion profile, NEVER a gate for rings, notifications or framed captures.
    static boolean hasOpticalProfile(String device) { return "bixi".equals(device); }

    static boolean contains(float x, float y, boolean rotated) {
        if (rotated) { x = 1208 - x; y = 1392 - y; }
        float dx = x - X, a = y - MAIN_Y, b = y - LOWER_Y;
        return dx * dx + Math.min(a * a, b * b) <= SAFE_RADIUS * SAFE_RADIUS;
    }
    static boolean receiverContains(float x, float y, boolean rotated) {
        if (rotated) { x = 1208 - x; y = 1392 - y; }
        float dx = x - X, dy = y - LOWER_Y;
        return dx * dx + dy * dy <= SAFE_RADIUS * SAFE_RADIUS;
    }
}
