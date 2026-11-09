// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.view.ViewGroup;
import android.widget.TextView;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.WeakHashMap;

/** The OEM callback copies the compressed status-clock height and caches text-size changes.
 * Refit the actual shade view after that callback, including its cached/early-return path. */
final class CoverNotificationClock {
    private static final WeakHashMap<Object, Boolean> owners = new WeakHashMap<>();
    private static final WeakHashMap<TextView, SavedLayout> layouts = new WeakHashMap<>();
    private static Method update, expansion, lazyGet;
    private static Field headerController, bigTime, callback, progress, copiedHeight;

    static void install(NativeModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> type = loader.loadClass("com.android.systemui.controlcenter.shade.NotificationHeaderExpandController");
        Class<?> combined = loader.loadClass("com.android.systemui.controlcenter.shade.CombinedHeaderController");
        Class<?> header = loader.loadClass("com.android.systemui.qs.MiuiNotificationHeaderView");
        update = type.getDeclaredMethod("updateTranslationY"); update.setAccessible(true);
        headerController = field(type, "headerController"); bigTime = field(combined, "notificationBigTime");
        callback = field(type, "notificationCallback"); progress = field(type, "progress");
        copiedHeight = field(type, "statusBarBigTimeHeight");
        expansion = callback.getType().getDeclaredMethod("onExpansionChanged", float.class); expansion.setAccessible(true);
        Field callbackOwner = field(callback.getType(), "this$0");
        lazyGet = loader.loadClass("dagger.Lazy").getMethod("get");
        Field viewClock = field(header, "mBigTime");
        module.hook(update).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed(); Object owner = chain.getThisObject(); owners.put(owner, true);
            // The original has not bound a clock yet: don't force a lazy header into existence during DI construction.
            if (copiedHeight.getInt(owner) <= 0) return result;
            fitClock(clock(owner));
            return result;
        });
        module.hook(expansion).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed(); Object owner = callbackOwner.get(chain.getThisObject());
            owners.put(owner, true);
            fitClock(clock(owner));
            return result;
        });
        module.hook(header.getDeclaredMethod("updateFlipResources"))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object result = chain.proceed(); TextView clock = (TextView) viewClock.get(chain.getThisObject());
                    fitClock(clock);
                    return result;
                });
    }
    private static boolean eligible(TextView view) {
        return view != null && NativeModule.config().hideStatus && CoverPanelInsets.coverUnlocked(view);
    }
    static int requiredHeight(TextView clock) {
        var font = clock.getPaint().getFontMetricsInt();
        return Math.max(clock.getLineHeight(), font.bottom - font.top)
                + clock.getCompoundPaddingTop() + clock.getCompoundPaddingBottom();
    }
    private static TextView clock(Object owner) throws ReflectiveOperationException {
        return (TextView) bigTime.get(lazyGet.invoke(headerController.get(owner)));
    }
    private static void fitClock(TextView clock) {
        if (clock == null || !(clock.getLayoutParams() instanceof ViewGroup.MarginLayoutParams params)) return;
        if (!eligible(clock)) { restore(clock, params); return; }
        SavedLayout saved = layouts.computeIfAbsent(clock, key -> new SavedLayout(params));
        // An OEM font/resource pass may write new native values. Preserve those for restoration.
        if (params.height != saved.appliedHeight) saved.height = params.height;
        if (params.bottomMargin != saved.appliedGap) saved.gap = params.bottomMargin;
        int height = saved.height > 0 ? Math.max(saved.height, requiredHeight(clock)) : saved.height;
        int gap = Math.max(0, saved.gap);
        saved.appliedHeight = height; saved.appliedGap = gap;
        if (params.height != height || params.bottomMargin != gap) {
            params.height = height; params.bottomMargin = gap; clock.setLayoutParams(params);
        }
    }
    private static void restore(TextView clock, ViewGroup.MarginLayoutParams params) {
        SavedLayout saved = layouts.remove(clock); if (saved == null) return;
        boolean changed = false;
        if (params.height == saved.appliedHeight && params.height != saved.height) { params.height = saved.height; changed = true; }
        if (params.bottomMargin == saved.appliedGap && params.bottomMargin != saved.gap) { params.bottomMargin = saved.gap; changed = true; }
        if (changed) clock.setLayoutParams(params);
    }
    static void refresh() {
        for (TextView clock : new ArrayList<>(layouts.keySet())) fitClock(clock);
        for (Object owner : new ArrayList<>(owners.keySet())) {
            try { update.invoke(owner); expansion.invoke(callback.get(owner), progress.getFloat(owner)); }
            catch (ReflectiveOperationException error) { NativeLog.warning("Notification clock reflow unavailable", error); }
        }
    }
    private static final class SavedLayout {
        int height, gap, appliedHeight, appliedGap;
        SavedLayout(ViewGroup.MarginLayoutParams params) {
            height = appliedHeight = params.height; gap = appliedGap = params.bottomMargin;
        }
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private CoverNotificationClock() { }
}
