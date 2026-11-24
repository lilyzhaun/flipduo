// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.WeakHashMap;

/** Only FlipHome's verified native edit row. No hooks into com.miui.home, its databases, or search services. */
final class StockAppSearchHook {
    private static StockAppSearchHook instance;
    private final WeakHashMap<TextView, StockSearchFooter> footers = new WeakHashMap<>();
    private final WeakHashMap<Activity, WeakReference<StockAppSearchPanel>> panels = new WeakHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final StockEditTap editTap;

    StockAppSearchHook(NativeModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> holder = loader.loadClass("com.miui.fliphome.adapter.FlipLauncherAdapter$EditViewHolder");
        Class<?> shortcut = loader.loadClass("com.miui.fliphome.shortcut.ShortcutInfo");
        Class<?> launcher = loader.loadClass("com.miui.fliphome.FlipLauncher");
        Field edit = holder.getDeclaredField("mEditTv"); edit.setAccessible(true);
        Method bind = holder.getDeclaredMethod("bindItem", shortcut, boolean.class);
        Method back = launcher.getDeclaredMethod("onBackPressed"), pause = launcher.getDeclaredMethod("onPause");
        Method configuration = launcher.getDeclaredMethod("onConfigurationChanged", Configuration.class);
        editTap = StockEditTap.resolve(holder, loader);
        module.hook(bind).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed(); Object boundHolder = chain.getThisObject(); TextView text = (TextView) edit.get(boundHolder);
            if (text != null && (text.getParent() instanceof FrameLayout || footers.containsKey(text))) {
                StockSearchFooter footer = footers.computeIfAbsent(text, v -> {
                    WeakReference<Object> owner = new WeakReference<>(boundHolder);
                    return new StockSearchFooter(v, source -> open(source, owner.get()));
                });
                footer.apply(NativeModule.config().desktopSearchEnabled());
            }
            return result;
        });
        module.hook(back).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            return close((Activity) chain.getThisObject()) ? null : chain.proceed();
        });
        module.hook(pause).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            close((Activity) chain.getThisObject()); return chain.proceed();
        });
        module.hook(configuration).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            close((Activity) chain.getThisObject()); return chain.proceed();
        });
        instance = this; NativeLog.info("Stock FlipHome local app search installed; native edit action retained");
    }

    private void open(View source, Object holder) {
        Activity activity = activity(source.getContext());
        if (activity == null || !source.isShown() || !StockAppSearchPanel.eligible(activity)) return;
        WeakReference<StockAppSearchPanel> previous = panels.get(activity);
        if (previous != null && previous.get() != null) return;
        if (!(activity.findViewById(android.R.id.content) instanceof FrameLayout root)) return;
        StockEditTap.play(editTap, holder, source, NativeModule.config().haptics);
        StockAppSearchPanel panel = new StockAppSearchPanel(activity, () -> panels.remove(activity));
        panels.put(activity, new WeakReference<>(panel)); panel.show(root);
    }

    private boolean close(Activity activity) {
        WeakReference<StockAppSearchPanel> reference = panels.remove(activity);
        StockAppSearchPanel panel = reference == null ? null : reference.get();
        if (panel == null) return false; panel.close(); return true;
    }

    static void refreshAll() {
        StockAppSearchHook hook = instance; if (hook == null) return;
        hook.main.post(() -> {
            boolean enabled = NativeModule.config().desktopSearchEnabled();
            if (!enabled) for (Activity activity : new ArrayList<>(hook.panels.keySet())) hook.close(activity);
            for (StockSearchFooter footer : new ArrayList<>(hook.footers.values())) footer.apply(enabled);
        });
    }

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper wrapper) {
            if (context instanceof Activity activity) return activity;
            Context base = wrapper.getBaseContext(); if (base == context) return null; context = base;
        }
        return null;
    }
}
