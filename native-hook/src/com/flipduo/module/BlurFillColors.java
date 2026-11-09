package com.flipduo.module;

/** SurfaceControl nativeSetColorTransform consumes a COLUMN-major mat3, unlike Android ColorMatrix. */
final class BlurFillColors {
    static float[] colorMatrix() {
        // Blur softens detail; do not additionally wash the app's colors into gray.
        return new float[]{1,0,0, 0,1,0, 0,0,1};
    }
}
