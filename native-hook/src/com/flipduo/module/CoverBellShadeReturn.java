// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Finish a missed native notification-window close after a FlipDuo bell request. SystemUI main thread only. */
final class CoverBellShadeReturn {
    private static CoverBellShadeReturn installed;
    private int requestDisplay = -1, display = -1, generation;
    private long requestUntil;
    private WeakReference<Object> active = new WeakReference<>(null);
    private boolean opened, closing;
    private final Field view, state, height, injector, helper, visible, appearance, listener, owner, expandedWindow;
    private final Field windowController, windowState, panelVisible;
    private final Field[] otherOwners;
    private final Method isExpanded, makeInvisible;

    private CoverBellShadeReturn(Class<?> panel, Class<?> window) throws ReflectiveOperationException {
        view = field(panel, "mView"); state = field(panel, "mCurrentPanelState"); height = field(panel, "mExpandedHeight");
        injector = field(panel, "mNotifInjector"); helper = field(injector.getType(), "expandHelper");
        visible = field(helper.getType(), "visible"); appearance = field(helper.getType(), "appearance");
        isExpanded = panel.getDeclaredMethod("isExpanded");
        listener = field(panel, "mOpenCloseListener"); owner = field(listener.getType(), "this$0");
        expandedWindow = field(owner.getType(), "mExpandedVisible");
        makeInvisible = owner.getType().getDeclaredMethod("makeExpandedInvisible");
        windowController = field(panel, "mNotificationShadeWindowController"); windowState = field(window, "mCurrentState");
        Class<?> ws = windowState.getType(); panelVisible = field(ws, "panelVisible");
        otherOwners = new Field[]{field(ws, "headsUpNotificationShowing"), field(ws, "remoteInputActive"), field(ws, "qsExpanded"),
                field(ws, "shadeOrQsExpanded"), field(ws, "controlCenterExpanded"), field(ws, "bouncerShowing"), field(ws, "keyguardShowing"), field(ws, "dozing"),
                field(ws, "isSwitchingUsers"), field(ws, "launchingActivityFromNotification"), field(ws, "forcePluginOpen")};
    }
    static void install(NativeModule module, ClassLoader loader) throws ReflectiveOperationException {
        if (installed != null || !"ruyi".equals(Build.DEVICE) || !Build.VERSION.INCREMENTAL.startsWith("OS3.")) return;
        Class<?> panel = loader.loadClass("com.android.systemui.shade.NotificationPanelViewController");
        var s = new CoverBellShadeReturn(panel, loader.loadClass("com.android.systemui.shade.NotificationShadeWindowControllerImpl"));
        Method enter = panel.getDeclaredMethod("expandToNotifications"), phase = panel.getDeclaredMethod("onPanelStateChanged$2", int.class);
        Method visibility = s.helper.getType().getDeclaredMethod("setVisible$2", boolean.class);
        module.hook(enter).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            s.enter(chain.getThisObject()); return chain.proceed();
        });
        module.hook(phase).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed(), p = chain.getThisObject();
            if (s.active.get() == p) s.changed(p, s.state.getInt(p) != 0);
            return result;
        });
        module.hook(visibility).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object p = s.active.get(), h = chain.getThisObject();
            if (p == null || h != s.expandHelper(p)) return chain.proceed();
            boolean was = s.visible.getBoolean(h);
            Object result = chain.proceed();
            boolean now = s.visible.getBoolean(h);
            if (was != now && p == s.active.get()) s.changed(p, now);
            return result;
        });
        installed = s;
    }

    static void requested(int displayId) {
        if (installed == null) return;
        installed.clear(); installed.requestDisplay = displayId;
        // Only correlate the queued statusbar command; expiry never changes a window.
        installed.requestUntil = SystemClock.uptimeMillis() + 2200;
    }
    private void clear() { generation++; active.clear(); opened = closing = false; display = requestDisplay = -1; }
    private Object expandHelper(Object p) throws IllegalAccessException { return helper.get(injector.get(p)); }
    private static boolean eligible(View v, int id) {
        return v != null && v.isAttachedToWindow() && v.getDisplay() != null && v.getDisplay().getDisplayId() == id
                && CoverPanelInsets.coverUnlocked(v);
    }
    private void enter(Object p) throws IllegalAccessException {
        int target = requestDisplay; clear();
        if (target < 0 || SystemClock.uptimeMillis() > requestUntil || !eligible((View) view.get(p), target)) return;
        active = new WeakReference<>(p); display = target;
        opened = state.getInt(p) != 0 || visible.getBoolean(expandHelper(p));
        NativeModule.info("Cover bell: entered ruyi notification panel");
    }
    private void changed(Object p, boolean open) throws IllegalAccessException {
        if (open) { if (closing) clear(); else opened = true; return; }
        if (!opened) return;
        closing = true;
        int ticket = ++generation;
        View v = (View) view.get(p);
        // Stock CLOSED posts maybeHide, which in turn posts hide. Let both run first.
        v.post(() -> { if (generation == ticket) v.post(() -> finish(p, ticket)); });
    }
    private void finish(Object p, int ticket) {
        if (ticket != generation || p != active.get()) return;
        try {
            if (!eligible((View) view.get(p), display)) { clear(); return; }
            Object h = expandHelper(p);
            if (state.getInt(p) != 0 || height.getFloat(p) != 0f || (boolean) isExpanded.invoke(p)
                    || visible.getBoolean(h) || appearance.getBoolean(h)) return; // Wait for the other native close edge.
            clear(); // One close per bell; the native operation itself can deliver callbacks.
            Object link = listener.get(p);
            if (link == null) return;
            Object shade = owner.get(link), ws = windowState.get(windowController.get(p));
            boolean expanded = expandedWindow.getBoolean(shade), held = panelVisible.getBoolean(ws);
            if (!expanded || !held) {
                NativeModule.info("Cover bell: native close expandedWindow=" + expanded + " panelVisible=" + held);
                return;
            }
            for (Field f : otherOwners) if (f.getBoolean(ws)) return;
            makeInvisible.invoke(shade);
            NativeModule.info("Cover bell: finished a closed notification shade window on ruyi");
        } catch (ReflectiveOperationException | RuntimeException error) {
            clear(); NativeModule.warning("Cover bell return unavailable; retaining native shade", error);
        }
    }
    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name); f.setAccessible(true); return f;
    }
}
