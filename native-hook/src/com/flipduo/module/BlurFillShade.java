package com.flipduo.module;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** The native notification blur is independent of Shell task transitions. */
final class BlurFillShade {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable changed;
    private WeakReference<View> panel = new WeakReference<>(null);
    private float height, blur;
    private boolean blocked, pending;
    private int epoch;

    BlurFillShade(Runnable changed) { this.changed = changed; }

    static BlurFillShade install(NativeModule module, ClassLoader loader, Runnable changed)
            throws ReflectiveOperationException {
        // Exact definitions and setter -> StateFlow dispatch verified in ruyi OS3 and bixi OS4.
        Class<?> injector = loader.loadClass("com.android.systemui.shade.NotificationPanelViewControllerInjector");
        Field panel = injector.getDeclaredField("panelView"); panel.setAccessible(true);
        Method height = injector.getDeclaredMethod("setExpandedHeightInternal$1", float.class);
        Method controller = injector.getDeclaredMethod("getPanelViewController");
        Field actualHeight = controller.getReturnType().getDeclaredField("mExpandedHeight"); actualHeight.setAccessible(true);
        Class<?> animator = loader.loadClass("com.android.systemui.shade.NotificationPanelBlurAnimator");
        Method value = loader.loadClass("com.miui.systemui.util.FloatFlowProperty")
                .getDeclaredMethod("setValue", Object.class, float.class);
        BlurFillShade state = new BlurFillShade(changed);
        module.hook(height).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            View view = (View) panel.get(chain.getThisObject());
            float next = (Float) chain.getArg(0);
            // Positive height is known before native blur consumers can draw the opening frame.
            if (next > 0) state.height(view, next);
            Object result = chain.proceed();
            // The OEM can replace the requested height with zero/max height. Read its result,
            // including ruyi's inlined batchApplyWindowLayoutParams path, instead of retaining p1.
            state.height(view, actualHeight.getFloat(controller.invoke(chain.getThisObject())));
            return result;
        });
        module.hook(value).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            if (!animator.isInstance(chain.getThisObject())) return chain.proceed();
            float next = (Float) chain.getArg(1);
            if (next > 0) state.ratio(next);
            Object result = chain.proceed();
            // Observe actual animated values, not updateBlurRatio(0, true)'s target value.
            if (next <= 0) state.ratio(next);
            return result;
        });
        return state;
    }

    void height(View view, float value) {
        onMain(() -> { panel = new WeakReference<>(view); height = value; update(); });
    }
    void ratio(float value) {
        onMain(() -> { blur = value; update(); });
    }
    private void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else main.post(action);
    }
    boolean blocks(int display) {
        View view = panel.get();
        return blocked && view != null && view.getDisplay() != null
                && view.getDisplay().getDisplayId() == display;
    }
    private void update() {
        if (height > 0 || blur > 0) {
            epoch++; pending = false;
            if (!blocked) { blocked = true; changed.run(); }
            return;
        }
        if (!blocked || pending) return;
        pending = true;
        int expected = ++epoch;
        // StateFlow consumers run after the setter. Register only after their zero-blur update,
        // then wait for that window's rendered frame rather than a timer or Shell idle alone.
        main.post(() -> {
            if (epoch != expected) return;
            View view = panel.get();
            if (view == null || !view.isAttachedToWindow() || !view.isShown() || !view.isHardwareAccelerated()) {
                finish(expected); return;
            }
            view.getViewTreeObserver().registerFrameCommitCallback(() -> main.post(() -> finish(expected)));
            view.invalidate();
        });
    }
    private void finish(int expected) {
        if (epoch != expected) return;
        pending = blocked = false;
        changed.run();
    }
}
