package com.flipduo.module;

import android.content.res.Configuration;
import java.lang.reflect.Field;

/** Requests the OEM's own material refresh. Never replaces blur with an opaque module palette. */
final class StockWidgetStyle {
    private final Field update;

    StockWidgetStyle(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> helper = loader.loadClass("com.miui.fliphome.widget.WidgetBgHelper");
        update = field(helper, "mUpdateBgRunnable");
    }

    static boolean night(Configuration configuration) {
        return (configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    void refresh(Object helper) throws ReflectiveOperationException { ((Runnable) update.get(helper)).run(); }

    /** The OEM owns both page occlusion and its HWUI material. Only refresh once layout is
     * ready; do not replace card backgrounds, gate pass blur, or change ViewBlur modes. */
    void refreshVisible(Object helper) throws ReflectiveOperationException { refresh(helper); }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field result = type.getDeclaredField(name); result.setAccessible(true); return result;
    }
}
