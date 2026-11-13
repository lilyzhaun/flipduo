package com.flipduo.module;

import android.graphics.Rect;

/** Physical logical-display coordinates, not dp or the app's compatibility density. */
final class BlurFillGeometry {
    static Rect strip(int rotation) {
        return CoverRotation.map(new Rect(CoverGeometry.WIDTH - DockSpec.WIDTH - DockSpec.SEAM_BLEED,
                0, CoverGeometry.WIDTH, CoverGeometry.HEIGHT), rotation);
    }
    static Rect source(Rect task, int rotation) {
        Rect app = CoverRotation.map(new Rect(0, 0,
                CoverGeometry.WIDTH - DockSpec.WIDTH - DockSpec.SEAM_BLEED, CoverGeometry.HEIGHT), rotation);
        if (!app.intersect(task)) return new Rect();
        app.offset(-task.left, -task.top);
        return app;
    }
    static Rect destination(int rotation, int x, int y, int width, int height) {
        Rect rect = strip(rotation); rect.offset(-x, -y);
        // A temporarily small native window is not a smaller blur destination. Wait for its layout.
        return rect.left >= 0 && rect.top >= 0 && rect.right <= width && rect.bottom <= height ? rect : new Rect();
    }
    static Rect appDestination(Rect source, Rect task, Rect viewport) {
        Rect result = new Rect(source); result.offset(task.left - viewport.left, task.top - viewport.top); return result;
    }
    /** The native window is the side strip plus one app-edge inset, in actual logical orientation. */
    static Rect extension(int width, int height, Rect side) {
        Rect result = new Rect(0, 0, width, height);
        if (side.left > 0) result.right = side.left;
        else if (side.top > 0) result.bottom = side.top;
        else if (side.right < width) result.left = side.right;
        else if (side.bottom < height) result.top = side.bottom;
        else result.setEmpty();
        return result;
    }
    static Rect half(Rect destination, Rect app, boolean horizontal, boolean second) {
        Rect result = new Rect(destination);
        if (horizontal) {
            int split = Math.max(result.left, Math.min(result.right, app.left + app.width() / 2));
            if (second) result.left = split; else result.right = split;
        } else {
            int split = Math.max(result.top, Math.min(result.bottom, app.top + app.height() / 2));
            if (second) result.top = split; else result.bottom = split;
        }
        return result;
    }
    static boolean eligible(boolean enabled, boolean blur, boolean unlocked, boolean ordinary,
            boolean shown, boolean nativeContent, boolean gesture) {
        return enabled && blur && unlocked && ordinary && shown && !nativeContent && !gesture;
    }
}
