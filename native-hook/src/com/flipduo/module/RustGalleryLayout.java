// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Build;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Activity-local inputs to the verified Rust Gallery's ordinary/cover UI decision. */
public final class RustGalleryLayout {
    public static final String KEY = "rust_gallery_regular_layout", PACKAGE = "com.miui.gallery";
    public static final int POLICY_VERSION = 176;
    private static final String HOME = PACKAGE + ".MainActivity";
    private static final long GALLERY_VERSION = 5040312L;
    private static final int INHERIT = -2, INNER = 0, COVER = 1;
    // The Dart Flip branch tests trunc(max(widthPx, heightPx) / displayDensity) <= 640.
    private static final int TINY_MAX_DP = 640;

    private static boolean verifiedFirmware() {
        return "bixi".equals(Build.DEVICE) && "OS4.0.4.0.XOHCNXM".equals(Build.VERSION.INCREMENTAL);
    }
    public static boolean supports(ApplicationInfo app, long version) {
        return verifiedFirmware() && version == GALLERY_VERSION && app != null && PACKAGE.equals(app.packageName)
                && (app.flags & ApplicationInfo.FLAG_HAS_CODE) == 0 && (app.splitSourceDirs == null || app.splitSourceDirs.length == 0);
    }

    private final Field info, version, type, window;
    private final Method getMaxBounds, setMaxBounds;
    private RustGalleryLayout(Class<?> activity) throws ReflectiveOperationException {
        info = activity.getDeclaredField("info"); version = ApplicationInfo.class.getField("longVersionCode");
        type = Configuration.class.getField("screenType");
        if (info.getType() != ActivityInfo.class || version.getType() != long.class || type.getType() != int.class)
            throw new NoSuchFieldException("Unexpected Gallery configuration contract");
        info.setAccessible(true);
        window = Configuration.class.getField("windowConfiguration");
        getMaxBounds = window.getType().getMethod("getMaxBounds");
        setMaxBounds = window.getType().getMethod("setMaxBounds", Rect.class);
    }
    static RustGalleryLayout resolve(Class<?> activity) throws ReflectiveOperationException {
        return verifiedFirmware() ? new RustGalleryLayout(activity) : null;
    }

    /** Caller checked opt-in, record package, display bounds and window mode. Returns the effective max for diagnostics. */
    Rect apply(Object record, Configuration resolved, Configuration parent, Configuration display) throws ReflectiveOperationException {
        if (resolved == parent || resolved == display || type.getInt(display) != COVER) return null;
        int effective = type.getInt(resolved);
        if (effective == INHERIT) effective = type.getInt(parent);
        if (effective != COVER && effective != INNER) return null;
        ActivityInfo owner = (ActivityInfo) info.get(record);
        if (owner == null || !HOME.equals(owner.name)) return null;
        ApplicationInfo app = owner.applicationInfo;
        if (app == null || !supports(app, version.getLong(app))) return null;

        Object localWindow = window.get(resolved);
        Rect max = (Rect) getMaxBounds.invoke(localWindow);
        if (max.isEmpty()) max = (Rect) getMaxBounds.invoke(window.get(parent));
        // The native display and the scaled activity can report different densities. Satisfy the predicate for both.
        int dpi = Math.max(display.densityDpi, resolved.densityDpi > 0 ? resolved.densityDpi : parent.densityDpi);
        if (max.isEmpty() || dpi <= 0) return null;
        int longEdge = Math.max(max.width(), max.height());
        if (longEdge * 160L < (TINY_MAX_DP + 1L) * dpi) {
            // One extra dp of margin for the native float density. Keep the origin and the entire short axis intact.
            int targetPx = (int) Math.ceil((TINY_MAX_DP + 2.0) * dpi / 160.0);
            max = new Rect(max);
            if (max.width() > max.height()) max.right = max.left + targetPx;
            else max.bottom = max.top + targetPx;
            setMaxBounds.invoke(localWindow, max);
        } else if (effective == INNER) return null;
        if (effective == COVER) type.setInt(resolved, INNER);
        return max;
    }
}
