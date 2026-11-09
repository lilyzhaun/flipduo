package com.flipduo.module;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.WeakHashMap;

/** Only the OEM's cover-keyguard camera shortcut, never the inner keyguard or camera application. */
final class CoverCameraShortcutPolicy {
    private static CoverCameraShortcutPolicy instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WeakHashMap<Object, IconVisibility> controllers = new WeakHashMap<>();
    private final Field cameraView;
    private final Method updateVisibility, iconView;
    private final Runnable refresh = this::refreshOnMain;

    private CoverCameraShortcutPolicy(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> panel = loader.loadClass("com.android.keyguard.tinyPanel.TinyKeyguardPanelViewControllerImpl");
        Class<?> camera = loader.loadClass("com.android.keyguard.widget.TinyCameraView");
        cameraView = panel.getDeclaredField("mTinyCameraView"); cameraView.setAccessible(true);
        updateVisibility = panel.getDeclaredMethod("updateCameraIconVisibility"); updateVisibility.setAccessible(true);
        iconView = camera.getDeclaredMethod("getCameraIconView"); iconView.setAccessible(true);
    }

    static void install(NativeModule module, ClassLoader loader) throws ReflectiveOperationException {
        CoverCameraShortcutPolicy policy = new CoverCameraShortcutPolicy(loader);
        Class<?> panel = policy.updateVisibility.getDeclaringClass();
        Method canShow = panel.getDeclaredMethod("canShowCameraIcon");
        Method attached = panel.getDeclaredMethod("onViewAttachedToWindow", View.class);
        // Resolve all contracts before hooking. Preserve native setup/unlock restrictions on restore.
        module.hook(canShow).intercept(chain -> {
            boolean nativeAllowed = (Boolean) chain.proceed();
            return nativeAllowed && !NativeModule.config().hidesCoverCameraShortcut();
        });
        module.hook(policy.updateVisibility).intercept(chain -> {
            Object result = chain.proceed();
            policy.applyIconVisibility(chain.getThisObject());
            return result;
        });
        module.hook(attached).intercept(chain -> {
            Object result = chain.proceed();
            policy.updateVisibility.invoke(chain.getThisObject());
            return result;
        });
        instance = policy;
        NativeModule.info("v6.87 cover keyguard camera shortcut policy installed; default native, icon touch follows visibility");
    }

    private void applyIconVisibility(Object controller) throws ReflectiveOperationException {
        IconVisibility saved = controllers.computeIfAbsent(controller, ignored -> new IconVisibility());
        Object camera = cameraView.get(controller);
        if (camera == null) return;
        View icon = (View) iconView.invoke(camera);
        if (icon == null) return;
        // OS3/OS4 TouchHandler checks the child's own visibility, not whether its parent is GONE.
        int current = icon.getVisibility();
        int next = saved.update(current, NativeModule.config().hidesCoverCameraShortcut());
        if (next != current) icon.setVisibility(next);
    }

    static void refresh() {
        if (instance == null) return;
        instance.main.removeCallbacks(instance.refresh); instance.main.post(instance.refresh);
    }
    private void refreshOnMain() {
        for (Object controller : new ArrayList<>(controllers.keySet())) {
            try { updateVisibility.invoke(controller); }
            catch (ReflectiveOperationException error) { NativeModule.warning("Cannot refresh cover camera shortcut visibility", error); }
        }
    }

    static final class IconVisibility {
        private int original = -1;
        int update(int current, boolean hide) {
            if (hide) {
                if (original < 0) original = current;
                return View.INVISIBLE;
            }
            int restore = original; original = -1;
            return restore >= 0 && current == View.INVISIBLE ? restore : current;
        }
    }
}
