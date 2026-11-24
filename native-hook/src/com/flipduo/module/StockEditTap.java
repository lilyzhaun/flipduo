// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.view.HapticFeedbackConstants;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Use the edit row's existing MIUIX feedback instance; never invoke the native Edit action. */
final class StockEditTap {
    private final Field adapter, feedback;
    private final Method tap;
    private final int normal;

    private StockEditTap(Class<?> holder, ClassLoader loader) throws ReflectiveOperationException {
        adapter = holder.getDeclaredField("this$0"); adapter.setAccessible(true);
        feedback = adapter.getType().getDeclaredField("mHapticCompat"); feedback.setAccessible(true);
        tap = feedback.getType().getMethod("performEmulationHaptic", int.class, double.class);
        normal = loader.loadClass("miuix.view.HapticFeedbackConstants").getField("MIUI_TAP_NORMAL").getInt(null);
    }

    static StockEditTap resolve(Class<?> holder, ClassLoader loader) {
        try { return new StockEditTap(holder, loader); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) { return null; }
    }

    static void play(StockEditTap edit, Object holder, View button, boolean enabled) {
        if (!enabled || !button.isHapticFeedbackEnabled()) return;
        if (edit != null && holder != null) {
            try {
                Object host = edit.adapter.get(holder);
                Object feedback = host == null ? null : edit.feedback.get(host);
                if (feedback != null && Boolean.TRUE.equals(edit.tap.invoke(feedback, edit.normal, .625d))) return;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { /* use View feedback */ }
        }
        button.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }
}
