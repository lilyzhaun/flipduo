// SPDX-License-Identifier: AGPL-3.0-only
// Adapted from parallelcc/MixFlipMod, FlipHomeHook.kt, commit 6b02e8cec6497efd2a07b9ba1307fb9c2f979867.
// https://github.com/parallelcc/MixFlipMod — original author: parallelcc.
package com.flipduo.module;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewParent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** MixFlipMod task-menu port. FlipDuo adds its existing preference/listener restoration. */
final class CoverRecents {
    private static CoverRecentsStyle layouts;
    private static Cards cards;
    static void install(NativeModule module, ClassLoader loader) {
        try { layouts = new CoverRecentsStyle(module, loader); }
        catch (ReflectiveOperationException | RuntimeException error) { NativeModule.warning("Recents layout unavailable; native layout retained", error); }
        try { cards = new Cards(module, loader); }
        catch (ReflectiveOperationException | RuntimeException error) { NativeModule.warning("Recents card actions unavailable; native listeners retained", error); }
    }
    static void refresh() { if (cards != null) cards.refresh(); if (layouts != null) layouts.refresh(); }

    private static final class Cards {
        private final Class<?> stackClass, containerClass, horizontalClass;
        private final Method getTask, component, lock, header, showLock, getStyle, getViews, setMenu, isMenu, toHome, listenerInfo;
        private final Field locked, longListener;
        private final Field[] keys;
        private final Map<View, OriginalListener> originals = new WeakHashMap<>();
        private RecentsTaskMenu menu;
        private View anchor;

        Cards(NativeModule module, ClassLoader loader) throws ReflectiveOperationException {
            Class<?> taskView = loader.loadClass("com.miui.fliphome.recents.views.TaskView");
            stackClass = loader.loadClass("com.miui.fliphome.recents.views.TaskStackView");
            containerClass = loader.loadClass("com.miui.fliphome.recents.views.RecentsContainer");
            horizontalClass = loader.loadClass("com.miui.fliphome.recents.TaskStackViewLayoutStyleHorizontal");
            Class<?> task = loader.loadClass("com.android.systemui.shared.recents.model.Task");
            getTask = taskView.getDeclaredMethod("getTask"); locked = field(task, "isLocked");
            keys = new Field[]{field(task,"key"),field(task,"cti1Key"),field(task,"cti2Key")};
            component = keys[0].getType().getMethod("getComponent");
            lock = loader.loadClass("com.miui.fliphome.RecentsAndFSGestureUtils")
                    .getDeclaredMethod("lockOrUnlockApp", task, boolean.class, Runnable.class);
            header = taskView.getDeclaredMethod("getHeaderView");
            showLock = header.getReturnType().getDeclaredMethod("showOrHideLockImageView", boolean.class);
            getStyle = stackClass.getMethod("getTaskStackViewLayoutStyle"); getViews = stackClass.getMethod("getTaskViews");
            setMenu = stackClass.getMethod("setIsShowingMenu", boolean.class); isMenu = stackClass.getMethod("isShowingMenu");
            toHome = containerClass.getMethod("dismissRecentsToHome", boolean.class);
            listenerInfo = View.class.getDeclaredMethod("getListenerInfo"); listenerInfo.setAccessible(true);
            longListener = field(listenerInfo.getReturnType(), "mOnLongClickListener");
            header.setAccessible(true); showLock.setAccessible(true); lock.setAccessible(true);
            module.hook(taskView.getDeclaredMethod("onFinishInflate")).intercept(chain -> {
                Object result = chain.proceed(); View view = (View) chain.getThisObject();
                originals.putIfAbsent(view, new OriginalListener((View.OnLongClickListener) longListener.get(listenerInfo.invoke(view)), view.isLongClickable()));
                apply(view); return result;
            });
            module.hook(taskView.getDeclaredMethod("onDetachedFromWindow")).intercept(chain -> {
                Object result = chain.proceed();
                if (anchor == chain.getThisObject()) dismiss(); return result;
            });
            module.hook(stackClass.getDeclaredMethod("setVisibility", int.class)).intercept(chain -> {
                Object result = chain.proceed();
                if ((int) chain.getArg(0) != View.VISIBLE) dismiss(); return result;
            });
            module.hook(containerClass.getDeclaredMethod("onBackPressed")).intercept(chain -> {
                if (menu != null && menu.isShowing()) { dismiss(); return null; } return chain.proceed();
            });
        }
        private boolean enabled() { DockConfig c = NativeModule.config(); return c.enabled && c.recentsCardLongPress; }
        private void apply(View view) {
            OriginalListener old = originals.get(view);
            view.setOnLongClickListener(enabled() ? this::longClick : old.listener);
            view.setLongClickable(enabled() || old.longClickable);
        }
        void refresh() {
            if (menu != null) menu.closeImmediately();
            for (View view : new ArrayList<>(originals.keySet())) apply(view);
        }
        private boolean longClick(View view) {
            if (!enabled() || !view.isAttachedToWindow() || !view.isShown()) return false;
            try {
                Object item = getTask.invoke(view); View parent = ancestor(view, stackClass);
                if (item == null || parent == null) return false;
                String packageName = packageName(item); if (packageName == null) return false;
                if (horizontalClass.isInstance(getStyle.invoke(parent))) {
                    haptic(view, HapticFeedbackConstants.LONG_PRESS); details(view, packageName);
                } else show(view, parent, item, packageName);
                return true;
            } catch (ReflectiveOperationException | RuntimeException error) {
                if (menu != null) menu.closeImmediately();
                NativeModule.warning("Could not open native task actions", error); return false;
            }
        }
        private void show(View view, View parent, Object item, String packageName) throws ReflectiveOperationException {
            if (menu != null) menu.closeImmediately();
            if ((boolean) isMenu.invoke(parent)) return;
            boolean wasLocked = locked.getBoolean(item);
            List<CardState> states = enterRecentsMenuMode(view, parent);
            try {
                menu = new RecentsTaskMenu(view, parent, wasLocked,
                        () -> act(view, item, packageName, true, !wasLocked),
                        () -> act(view, item, packageName, false, false),
                        () -> {
                            restoreRecentsMenuMode(parent, states);
                            menu = null; anchor = null;
                        });
            } catch (RuntimeException error) { restoreRecentsMenuMode(parent, states); throw error; }
            anchor = view; menu.show();
            haptic(view, HapticFeedbackConstants.LONG_PRESS);
        }
        private List<CardState> enterRecentsMenuMode(View view, View parent) throws ReflectiveOperationException {
            List<CardState> states = new ArrayList<>();
            for (Object card : (Iterable<?>) getViews.invoke(parent)) {
                View v = (View) card;
                states.add(new CardState(v, v.getVisibility(), v.getAlpha(), v.getTranslationZ(), v.getImportantForAccessibility()));
            }
            setMenu.invoke(parent, true);
            for (CardState state : states) {
                state.view.animate().cancel();
                if (state.view == view) state.view.setTranslationZ(10f);
                else { state.view.setAlpha(0); state.view.setVisibility(View.INVISIBLE); state.view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS); }
            }
            return states;
        }
        private void act(View view, Object item, String packageName, boolean locking, boolean toLock) {
            try {
                if (!enabled() || !view.isAttachedToWindow() || getTask.invoke(view) != item) return;
                if (locking) {
                    lock.invoke(null, item, toLock, (Runnable) () -> {
                        try {
                            if (getTask.invoke(view) == item) {
                                showLock.invoke(header.invoke(view), toLock);
                                haptic(view, HapticFeedbackConstants.VIRTUAL_KEY);
                            }
                        }
                        catch (ReflectiveOperationException error) { NativeModule.warning("Could not update native task lock icon", error); }
                    });
                } else {
                    details(view, packageName); View container = ancestor(view, containerClass);
                    if (container != null) toHome.invoke(container, true);
                }
            } catch (ReflectiveOperationException | RuntimeException error) { NativeModule.warning("Native task action did not complete", error); }
        }
        private String packageName(Object item) throws ReflectiveOperationException {
            for (Field key : keys) {
                Object taskKey = key.get(item); if (taskKey == null) continue;
                ComponentName target = (ComponentName) component.invoke(taskKey); if (target == null) continue;
                return target.getPackageName();
            }
            return null;
        }
        private void details(View view, String packageName) {
            view.getContext().startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        private void dismiss() { if (menu != null) menu.dismiss(); }
        private void restoreRecentsMenuMode(View parent, List<CardState> states) {
            try { setMenu.invoke(parent, false); }
            catch (ReflectiveOperationException error) { NativeModule.warning("Could not leave native task menu mode", error); }
            for (CardState state : states) {
                state.view.animate().cancel(); state.view.setVisibility(state.visibility); state.view.setAlpha(state.alpha);
                state.view.setTranslationZ(state.z); state.view.setImportantForAccessibility(state.accessibility);
            }
        }
    }
    private record OriginalListener(View.OnLongClickListener listener, boolean longClickable) { }
    private record CardState(View view, int visibility, float alpha, float z, int accessibility) { }
    private static void haptic(View view, int type) { if (NativeModule.config().haptics) view.performHapticFeedback(type); }
    private static View ancestor(View view, Class<?> type) {
        for (ViewParent p = view.getParent(); p != null; p = p.getParent()) if (type.isInstance(p)) return (View) p;
        return null;
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
}
