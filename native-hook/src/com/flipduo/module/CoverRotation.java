package com.flipduo.module;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.Gravity;

/** Natural panel -> logical display. Does not rotate an application's surface or any input event. */
final class CoverRotation {
    static boolean landscape(int rotation) { return rotation == 1 || rotation == 3; }
    static int width(int rotation) { return landscape(rotation) ? CoverGeometry.HEIGHT : CoverGeometry.WIDTH; }
    static int height(int rotation) { return landscape(rotation) ? CoverGeometry.WIDTH : CoverGeometry.HEIGHT; }
    static int gravity(int rotation) {
        return switch (rotation) {
            case 1 -> Gravity.TOP | Gravity.LEFT;
            case 2 -> Gravity.BOTTOM | Gravity.LEFT;
            case 3 -> Gravity.BOTTOM | Gravity.RIGHT;
            default -> Gravity.TOP | Gravity.RIGHT;
        };
    }
    static float x(int rotation, float x, float y) {
        return switch (rotation) { case 1 -> y; case 2 -> CoverGeometry.WIDTH - x; case 3 -> CoverGeometry.HEIGHT - y; default -> x; };
    }
    static float y(int rotation, float x, float y) {
        return switch (rotation) { case 1 -> CoverGeometry.WIDTH - x; case 2 -> CoverGeometry.HEIGHT - y; case 3 -> x; default -> y; };
    }
    static Rect map(Rect natural, int rotation) {
        int x1 = Math.round(x(rotation, natural.left, natural.top)), y1 = Math.round(y(rotation, natural.left, natural.top));
        int x2 = Math.round(x(rotation, natural.right, natural.bottom)), y2 = Math.round(y(rotation, natural.right, natural.bottom));
        return new Rect(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2));
    }
    static Rect natural(Rect logical, int rotation) {
        return switch (rotation) {
            case 1 -> new Rect(CoverGeometry.WIDTH - logical.bottom, logical.left, CoverGeometry.WIDTH - logical.top, logical.right);
            case 2 -> new Rect(CoverGeometry.WIDTH - logical.right, CoverGeometry.HEIGHT - logical.bottom,
                    CoverGeometry.WIDTH - logical.left, CoverGeometry.HEIGHT - logical.top);
            case 3 -> new Rect(logical.top, CoverGeometry.HEIGHT - logical.right, logical.bottom, CoverGeometry.HEIGHT - logical.left);
            default -> new Rect(logical);
        };
    }
    static Rect column(int rotation, int extension) {
        return map(new Rect(CoverGeometry.WIDTH - DockSpec.WIDTH - DockSpec.SEAM_BLEED - extension, 0,
                CoverGeometry.WIDTH, CoverGeometry.HEIGHT), rotation);
    }
    static Rect controls(int rotation) {
        return map(new Rect(CoverGeometry.WIDTH - DockSpec.WIDTH, 0, CoverGeometry.WIDTH, DockSpec.HEIGHT), rotation);
    }
    // Coordinates within the read-only camera child (no seam extension), not within the display.
    static float cameraX(int rotation, float x, float y) {
        return CoverRotation.x(rotation, x, y) - (rotation == 0 ? CoverGeometry.WIDTH - DockSpec.WIDTH : 0);
    }
    static float cameraY(int rotation, float x, float y) {
        return CoverRotation.y(rotation, x, y) - (rotation == 3 ? CoverGeometry.WIDTH - DockSpec.WIDTH : 0);
    }
    /** Rotate only the background painter's local coordinates; controls keep real, upright View bounds. */
    static void columnCanvas(Canvas canvas, Rect bounds, int rotation) {
        canvas.translate(bounds.left, bounds.top);
        if (rotation == 1) { canvas.translate(0, bounds.height()); canvas.rotate(-90); }
        else if (rotation == 3) { canvas.translate(bounds.width(), 0); canvas.rotate(90); }
    }
    static float recentsX(int rotation) {
        return switch (rotation) { case 1 -> CoverGeometry.HEIGHT / 2f; case 2 -> 804; case 3 -> CoverGeometry.HEIGHT - DockSpec.HEIGHT / 2f; default -> 404; };
    }
    static float recentsY(int rotation) { return height(rotation) - 4; }
    private CoverRotation() { }
}
