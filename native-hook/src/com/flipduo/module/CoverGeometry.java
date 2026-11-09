package com.flipduo.module;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;

/** Resolves this cover's OEM overlay, not the inner display's framework RoundedCorners. */
public final class CoverGeometry {
    public static final int WIDTH = 1208, HEIGHT = 1392, COLUMN = 398, SAFE_HEIGHT = 664;
    public static final String OVERLAY = "com.miui.systemui.overlay.devices.android";
    public final int naturalTop, naturalBottom;
    public final int installRotation;
    public final String source;

    CoverGeometry(int top, int bottom, int installation, String source) {
        this.naturalTop = top; this.naturalBottom = bottom; this.installRotation = installation; this.source = source;
    }
    public static CoverGeometry resolve(Context context) {
        try {
            Context overlay = context.createPackageContext(OVERLAY, 0);
            Configuration cover = new Configuration(overlay.getResources().getConfiguration());
            cover.screenWidthDp = 372; cover.screenHeightDp = 428; cover.smallestScreenWidthDp = 372;
            cover.densityDpi = 520; cover.orientation = Configuration.ORIENTATION_PORTRAIT;
            Resources res = overlay.createConfigurationContext(cover).getResources();
            int top = res.getDimensionPixelSize(res.getIdentifier("rounded_corner_radius_top", "dimen", OVERLAY));
            int bottom = res.getDimensionPixelSize(res.getIdentifier("rounded_corner_radius_bottom", "dimen", OVERLAY));
            if (top > 0 && bottom > 0 && top < COLUMN / 2 && bottom < COLUMN / 2)
                return new CoverGeometry(top, bottom, "bixi".equals(Build.DEVICE) ? 2 : 0, "系统设备资源");
        } catch (Exception ignored) { /* The public outer-display API reports zero on this firmware. */ }
        // Values verified from this device's actual DevicesAndroidOverlay.apk, never applied to other devices.
        if ("bixi".equals(Build.DEVICE)) return new CoverGeometry(130, 50, 2, "MIX Flip 2 设备配置");
        return new CoverGeometry(0, 0, 0, "未取得系统圆角");
    }
    // Panel installOrientation=2 is independent from logical rotation. Cutout coordinates are already logical.
    private boolean invertCorners(boolean rotated) { return ((installRotation + (rotated ? 2 : 0)) & 3) == 2; }
    public int top(boolean rotated) { return invertCorners(rotated) ? naturalBottom : naturalTop; }
    public int bottom(boolean rotated) { return invertCorners(rotated) ? naturalTop : naturalBottom; }
    public int extension(boolean enabled) { return enabled ? Math.max(naturalTop, naturalBottom) : 0; }
    // Application seam only: physical screen/frame and camera geometry above must stay OEM-shaped.
    public int appTop(boolean rotated, boolean enabled, boolean symmetric) {
        return !enabled ? 0 : symmetric ? Math.min(naturalTop, naturalBottom) : top(rotated);
    }
    public int appBottom(boolean rotated, boolean enabled, boolean symmetric) {
        return !enabled ? 0 : symmetric ? Math.min(naturalTop, naturalBottom) : bottom(rotated);
    }
    public int appExtension(boolean enabled, boolean symmetric) {
        return !enabled ? 0 : symmetric ? Math.min(naturalTop, naturalBottom) : extension(true);
    }
    public int dockX(boolean rotated) { return rotated ? 0 : WIDTH - COLUMN; }
    public int dockY(boolean rotated) { return rotated ? HEIGHT - SAFE_HEIGHT : 0; }
    /** WMS rotates paramsForRotation independently of FlipHome's queued orientation callback. */
    public static int rotation(int displayRotation, int nativeRotation) {
        return displayRotation >= 0 && displayRotation <= 3 ? displayRotation : nativeRotation;
    }
    public float cameraX(boolean rotated, float naturalX) { return rotated ? WIDTH - naturalX : naturalX; }
    public float cameraY(boolean rotated, float naturalY) { return rotated ? HEIGHT - naturalY : naturalY; }
}
