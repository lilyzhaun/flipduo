// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.os.Build;

/** Bixi indicator centers from the live system-music View hierarchy, NOT optical calibration.
 * com.miui.player 4.48.0.1, 1208x1392, rotation 0, density 3.25, fontScale 1.
 * Parent offsets (779,595); square Views 429x429 at y=-30/399.
 * Actual translations (13.5,52.5)/(13.5,-15.75); lower View rotates around its own center.
 * Includes the full ancestor/translation chain exactly once, without artwork or photo fitting.
 * See camera-system-geometry-research/miui-player-measured-centers.json and CAMERA_STATUS.md.
 * CoverFrame uses these same adjusted centers. CameraOcclusion and background animation stay unchanged.
 */
final class NativeCameraGeometry {
    static final NativeCameraGeometry BIXI = new NativeCameraGeometry(
            779 + 429 / 2f + 13.5f,              // 1007, including the measured translationX.
            595 - 30 + 429 / 2f + 52.5f,        // 832, upper spectrum center.
             595 + 399 + 429 / 2f - 15.75f);     // 1192.75, lower spectrum center.
    // Ruyi OS3 DevicesAndroidOverlay: natural cover avoidance rect (810,664)-(1208,1392).
    // The centers of its upper/lower half-cells are a USER-ADJUSTABLE reference, not optical centers.
    // Do not borrow BIXI's music measurements or use this envelope as an opaque hardware mask.
    static final NativeCameraGeometry COVER_BASELINE = fromEnvelope(
            CoverGeometry.WIDTH - DockSpec.WIDTH, DockSpec.HEIGHT, CoverGeometry.WIDTH, CoverGeometry.HEIGHT);
    static NativeCameraGeometry forDevice(String device) {
        return "bixi".equals(device) ? BIXI : COVER_BASELINE;
    }
    static NativeCameraGeometry fromEnvelope(float left, float top, float right, float bottom) {
        float quarterHeight = (bottom - top) / 4f;
        return new NativeCameraGeometry((left + right) / 2f, top + quarterHeight, bottom - quarterHeight);
    }
    final float x, mainY, lowerX, lowerY, mainRadius, lowerRadius;
    private NativeCameraGeometry(float x, float mainY, float lowerY) {
        this(x, mainY, x, lowerY, DockConfig.CAMERA_RADIUS_DEFAULT, DockConfig.CAMERA_RADIUS_DEFAULT);
    }
    private NativeCameraGeometry(float x, float mainY, float lowerX, float lowerY, float mainRadius, float lowerRadius) {
        this.x = x; this.mainY = mainY; this.lowerX = lowerX; this.lowerY = lowerY;
        this.mainRadius = mainRadius; this.lowerRadius = lowerRadius;
    }
    private float gapFraction() {
        double distance = Math.hypot(lowerX - x, lowerY - mainY);
        return distance == 0 ? .5f : .5f + (mainRadius - lowerRadius) / (2f * (float) distance);
    }
    float gapX() { return x + (lowerX - x) * gapFraction(); }
    float gapY() { return mainY + (lowerY - mainY) * gapFraction(); }
    NativeCameraGeometry adjusted(DockConfig c) {
        return new NativeCameraGeometry(x + c.cameraMainX, mainY + c.cameraMainY,
                lowerX + c.cameraLowerX, lowerY + c.cameraLowerY, c.cameraMainRadius, c.cameraLowerRadius);
    }
    static int windowInset(DockConfig c) {
        return windowInset(c, forDevice(Build.DEVICE));
    }
    static int windowInset(DockConfig c, NativeCameraGeometry base) {
        NativeCameraGeometry g = base.adjusted(c);
        float left = Math.min(g.x - g.mainRadius, g.lowerX - g.lowerRadius) - DockConfig.CAMERA_STROKE_DEFAULT / 2f;
        return Math.max(0, (int) Math.ceil(CoverGeometry.WIDTH - DockSpec.WIDTH - left));
    }
}
