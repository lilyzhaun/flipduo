// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import static io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE;

/** Opt the unlocked landscape cover into normal shade interaction only at the OEM rejection sites. */
final class CoverLandscapeShade {
    private static final String PANEL = "com.android.systemui.shade.NotificationPanelViewController";
    private static final Set<ClassLoader> plugins = Collections.newSetFromMap(new WeakHashMap<>());

    static boolean eligible(View v) {
        return v != null && v.isAttachedToWindow() && ControlCenterLandscape.eligible(v) && v.getDisplay().getState() == Display.STATE_ON
                && !v.getContext().getSystemService(KeyguardManager.class).isKeyguardLocked();
    }

    /** A nested callback on another view must not inherit an outer view's exemption. */
    static final class Scope {
        private final ThreadLocal<Object> target = new ThreadLocal<>();
        boolean matches(Object candidate) { return candidate != null && target.get() == candidate; }
        void consume() { target.remove(); }
        boolean take(Object candidate) { if (!matches(candidate)) return false; consume(); return true; }
        Object during(Object next, XposedInterface.Chain chain) throws Throwable {
            Object old = target.get();
            if (next == null && old == null) return chain.proceed();
            target.set(next);
            try { return chain.proceed(); }
            finally { if (old == null) target.remove(); else target.set(old); }
        }
    }

    static void installNotifications(XposedInterface module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> panel = loader.loadClass(PANEL), touch = loader.loadClass(PANEL + "$TouchHandler");
        Class<?> listener = null;
        for (String suffix : new String[]{"17", "7"}) { // Both names exist on some ROMs; only one implements this interface.
            try {
                Class<?> c = loader.loadClass(PANEL + "$$ExternalSyntheticLambda" + suffix);
                if (View.OnApplyWindowInsetsListener.class.isAssignableFrom(c) && c.getDeclaredField("f$0").getType() == panel) { listener = c; break; }
            } catch (ClassNotFoundException | NoSuchFieldException ignored) { }
        }
        if (listener == null) throw new NoSuchMethodException("Notification panel inset listener");
        Field view = field(panel, "mView"), owner = field(touch, "this$0"), insetOwner = field(listener, "f$0");
        Method insets = listener.getDeclaredMethod("onApplyWindowInsets", View.class, WindowInsets.class);
        Method onTouch = touch.getDeclaredMethod("onTouch", View.class, MotionEvent.class);
        Method event = touch.getDeclaredMethod("onTouchEvent", MotionEvent.class);
        Method tiny = loader.loadClass("com.miui.utils.configs.MiuiConfigs").getDeclaredMethod("isTinyScreenLandscape", Context.class);
        Class<?> headsUp = loader.loadClass("com.android.systemui.statusbar.notification.headsup.HeadsUpTouchHelper");
        Method interceptHeadsUp = headsUp.getDeclaredMethod("onInterceptTouchEvent", MotionEvent.class);

        Scope first = new Scope(), drag = new Scope();
        module.hook(tiny).setExceptionMode(PROTECTIVE).intercept(c -> first.take(c.getArg(0)) || drag.matches(c.getArg(0)) ? false : c.proceed());
        module.hook(insets).setExceptionMode(PROTECTIVE).intercept(c -> {
            View v = (View) view.get(insetOwner.get(c.getThisObject()));
            return first.during(eligible(v) ? v.getContext() : null, c);
        });
        // The complete stock onTouch wrapper is one downward rejection followed by onTouchEvent.
        // Do not synthesize an event or return true on behalf of the native gesture recognizer.
        module.hook(onTouch).setExceptionMode(PROTECTIVE).intercept(c -> {
            View v = (View)view.get(owner.get(c.getThisObject()));
            return first.during(eligible(v) ? v.getContext() : null, c);
        });
        module.hook(event).setExceptionMode(PROTECTIVE).intercept(c -> {
            View v = (View) view.get(owner.get(c.getThisObject()));
            // Its two checks govern an intercepted drag and the corresponding finish on UP/CANCEL.
            return drag.during(eligible(v) ? v.getContext() : null, c);
        });
        // Heads-up has its own tiny-landscape behavior, even when called by the shade touch handler.
        module.hook(interceptHeadsUp).setExceptionMode(PROTECTIVE).intercept(c -> drag.during(null, c));
        for (Method caller : new Method[]{insets, onTouch, event, interceptHeadsUp}) module.deoptimize(caller);
    }

    static void installPlugin(XposedInterface module, ClassLoader loader) throws ReflectiveOperationException {
        if (plugins.contains(loader)) return;
        String base = "miui.systemui.controlcenter.windowview.";
        Class<?> window = loader.loadClass(base + "ControlCenterWindowViewController"), expand = loader.loadClass(base + "ControlCenterExpandController");
        Method config = window.getDeclaredMethod("onConfigurationChanged", Configuration.class), getView = window.getMethod("getView");
        Method resourcePhase = window.getDeclaredMethod("updateResources");
        Method getExpand = window.getMethod("getExpandController");
        Method hide = expand.getDeclaredMethod("hidePanel$default", expand, boolean.class, boolean.class, int.class, Object.class);
        Field previous = field(window, "configuration");
        Scope scope = new Scope();
        module.hook(config).setExceptionMode(PROTECTIVE).intercept(c -> {
            Object w = c.getThisObject(); Configuration next = (Configuration)c.getArg(0), last = (Configuration)previous.get(w);
            boolean same = next != null && last != null && next.orientation == Configuration.ORIENTATION_LANDSCAPE && next.orientation == last.orientation;
            return scope.during(same && eligible((View)getView.invoke(w)) ? getExpand.invoke(w) : null, c);
        });
        module.hook(hide).setExceptionMode(PROTECTIVE).intercept(c -> {
            // All three plugins make this exact default call at the very start of the configuration callback.
            // Consume it once: child resource callbacks and explicit closes subsequently run as usual.
            if (scope.matches(c.getArg(0)) && !(boolean)c.getArg(1) && !(boolean)c.getArg(2) && (int)c.getArg(3) == 2 && c.getArg(4) == null) {
                scope.consume(); return null;
            }
            return c.proceed();
        });
        // If the vendor's tiny-screen predicate did not match, this is the next operation. No descendant
        // of the subsequent resource dispatch should ever inherit a yet-unused hide exemption.
        module.hook(resourcePhase).setExceptionMode(PROTECTIVE).intercept(c -> { scope.consume(); return c.proceed(); });
        module.deoptimize(config); plugins.add(loader);
    }
    static Field field(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private CoverLandscapeShade() { }
}
