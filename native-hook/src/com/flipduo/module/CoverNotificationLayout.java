// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.view.View;
import android.view.WindowInsets;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.WeakHashMap;

/** The real shared notification-container inset listener owns the 398px camera padding.
 * NotificationPanelView is a sibling, so changing that subtree cannot expand these rows. */
final class CoverNotificationLayout {
    private static final WeakHashMap<View, Boolean> containers = new WeakHashMap<>();
    private static final WeakHashMap<View, String> reported = new WeakHashMap<>();
    private static final ThreadLocal<WindowInsets> notificationRead = new ThreadLocal<>();

    static void install(NativeModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> listener = loader.loadClass("com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainerInjector$insetListener$1");
        Class<?> container = loader.loadClass("com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer");
        Method applyInsets = listener.getDeclaredMethod("onApplyWindowInsets", View.class, WindowInsets.class);
        // Both sampled ROMs can ignore mDisplayCutout and re-read the physical display here.
        // Only this listener's private copy is exempt, only during its synchronous invocation.
        module.hook(WindowInsets.class.getDeclaredMethod("getDisplayCutout"))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain ->
                        chain.getThisObject() == notificationRead.get() ? null : chain.proceed());
        // The OEM getter may already be inlined into this listener's compiled implementation.
        if (!module.deoptimize(applyInsets)) NativeLog.info("Notification inset listener deoptimization unavailable");
        module.hook(applyInsets)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    View view = (View) chain.getArg(0);
                    if (!container.isInstance(view)) return chain.proceed();
                    containers.put(view, true);
                    boolean wide = NativeModule.config().notificationFullscreen && CoverPanelInsets.coverUnlocked(view);
                    WindowInsets original = (WindowInsets) chain.getArg(1);
                    // Let the OEM listener rebuild padding and its own ConstraintSet from this copy.
                    Object result;
                    if (wide) {
                        WindowInsets copy = CoverPanelInsets.notificationInsets(original);
                        WindowInsets previous = notificationRead.get();
                        notificationRead.set(copy);
                        try { result = chain.proceed(new Object[]{view, copy}); }
                        finally {
                            if (previous == null) notificationRead.remove(); else notificationRead.set(previous);
                        }
                    } else result = chain.proceed();
                    String state = "wide=" + wide + " padding=" + view.getPaddingLeft() + "," + view.getPaddingRight()
                            + " container=" + view.getWidth();
                    if (!state.equals(reported.put(view, state))) NativeLog.info("Notification container " + state);
                    return result;
                });
    }
    static void refresh() {
        for (View view : new ArrayList<>(containers.keySet())) view.requestApplyInsets();
    }
    private CoverNotificationLayout() { }
}
