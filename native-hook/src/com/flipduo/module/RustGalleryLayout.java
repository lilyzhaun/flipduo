// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Activity-local inputs to the verified Rust Gallery's ordinary/cover UI decision. */
public final class RustGalleryLayout {
    public static final String KEY = "rust_gallery_regular_layout", PACKAGE = "com.miui.gallery";
    public static final int POLICY_VERSION = 178;
    private static final String HOME = PACKAGE + ".MainActivity";
    private static final int INHERIT = -2, INNER = 0, COVER = 1;
    // The Dart Flip branch tests trunc(max(widthPx, heightPx) / displayDensity) <= 640.
    private static final int TINY_MAX_DP = 640;

    /** Package metadata, not the release number or presence of language/resource splits, selects the route. */
    public static boolean isNativeGallery(ApplicationInfo app) {
        return app != null && PACKAGE.equals(app.packageName) && (app.flags & ApplicationInfo.FLAG_HAS_CODE) == 0;
    }

    private final Field info, type, window;
    private final Method getMaxBounds, setMaxBounds;
    RustGalleryLayout(Class<?> activity) throws ReflectiveOperationException {
        info = activity.getDeclaredField("info");
        type = Configuration.class.getField("screenType");
        if (!ActivityInfo.class.isAssignableFrom(info.getType()) || type.getType() != int.class)
            throw new NoSuchFieldException("Unexpected Gallery configuration contract");
        info.setAccessible(true);
        window = Configuration.class.getField("windowConfiguration");
        getMaxBounds = window.getType().getMethod("getMaxBounds");
        setMaxBounds = window.getType().getMethod("setMaxBounds", Rect.class);
        if (!Rect.class.isAssignableFrom(getMaxBounds.getReturnType())) throw new NoSuchMethodException("getMaxBounds must return Rect");
    }

    /** Caller checked opt-in, record package, display bounds and window mode. Returns the effective max for diagnostics. */
    Rect apply(Object record, Configuration resolved, Configuration parent, Configuration display) throws ReflectiveOperationException {
        if (resolved == parent || resolved == display || type.getInt(display) != COVER) return null;
        int effective = type.getInt(resolved);
        if (effective == INHERIT) effective = type.getInt(parent);
        if (effective != COVER && effective != INNER) return null;
        ActivityInfo owner = (ActivityInfo) info.get(record);
        if (owner == null || !HOME.equals(owner.name)) return null;
        if (!isNativeGallery(owner.applicationInfo)) return null;

        Object localWindow = window.get(resolved);
        Rect max = (Rect) getMaxBounds.invoke(localWindow);
        if (max == null || max.isEmpty()) max = (Rect) getMaxBounds.invoke(window.get(parent));
        // The native display and the scaled activity can report different densities. Satisfy the predicate for both.
        int dpi = Math.max(display.densityDpi, resolved.densityDpi > 0 ? resolved.densityDpi : parent.densityDpi);
        if (max == null || max.isEmpty() || dpi <= 0) return null;
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
