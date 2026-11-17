// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/** Adjusts the existing grid; the host owns its list, touch helper, windows and data. */
final class NativeDesktopColumnsHook {
    private static NativeDesktopColumnsHook instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WeakHashMap<Object, State> states = new WeakHashMap<>();
    private final WeakHashMap<ImageView, int[]> iconSizes = new WeakHashMap<>();
    private final Field fragmentAdapter, fragmentRecycler, callbackAdapter, lookupAdapter, itemView;
    private final Field items, rows, appHeight, rowSpace, editHeight;
    private final Method getManager, getAdapter, setSpanCount, getLookup, invalidateIndex, invalidateGroup;
    private final Method notifyData, computing, firstVisible, findView, decoratedTop, scrollTo, getIcon;
    private final Class<?> appItem;

    NativeDesktopColumnsHook(NativeModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> fragment = loader.loadClass("com.miui.FlipLauncherFragment");
        Class<?> adapter = loader.loadClass("com.miui.fliphome.adapter.FlipLauncherAdapter");
        Class<?> rv = loader.loadClass("androidx.recyclerview.widget.RecyclerView");
        Class<?> grid = loader.loadClass("androidx.recyclerview.widget.GridLayoutManager");
        Class<?> lookup = loader.loadClass("com.miui.FlipLauncherFragment$2");
        Class<?> holder = loader.loadClass("androidx.recyclerview.widget.RecyclerView$ViewHolder");
        Class<?> editor = loader.loadClass("com.miui.fliphome.adapter.FlipLauncherAdapter$EditViewHolder");
        Class<?> callback = loader.loadClass("com.miui.fliphome.adapter.FlipLauncherItemTouchCallback");
        appItem = loader.loadClass("com.miui.fliphome.view.LauncherAppItem");
        fragmentAdapter = field(fragment, "mAdapter"); fragmentRecycler = field(fragment, "mRecycleView");
        callbackAdapter = field(callback, "mAdapter");
        lookupAdapter = field(lookup, "val$adapter"); itemView = holder.getField("itemView");
        items = field(adapter, "mAppItems"); rows = field(adapter, "mLauncherRowNum");
        appHeight = field(adapter, "mLauncherAppHeight"); rowSpace = field(adapter, "mLauncherRowSpace");
        editHeight = field(adapter, "mLauncherEditHeight");
        getManager = rv.getMethod("getLayoutManager"); getAdapter = rv.getMethod("getAdapter");
        computing = rv.getMethod("isComputingLayout");
        setSpanCount = grid.getMethod("setSpanCount", int.class);
        getLookup = grid.getMethod("getSpanSizeLookup");
        invalidateIndex = getLookup.getReturnType().getMethod("invalidateSpanIndexCache");
        invalidateGroup = getLookup.getReturnType().getMethod("invalidateSpanGroupIndexCache");
        firstVisible = grid.getMethod("findFirstVisibleItemPosition"); findView = grid.getMethod("findViewByPosition", int.class);
        decoratedTop = grid.getMethod("getDecoratedTop", View.class);
        scrollTo = grid.getMethod("scrollToPositionWithOffset", int.class, int.class);
        notifyData = adapter.getMethod("notifyDataSetChanged"); getIcon = appItem.getMethod("getAppIcon");
        Method created = fragment.getDeclaredMethod("onViewCreated", View.class, Bundle.class);
        Method resumed = fragment.getDeclaredMethod("onResume");
        Method destroyed = fragment.getMethod("onDestroyView");
        Method span = lookup.getDeclaredMethod("getSpanSize", int.class);
        Method margin = adapter.getDeclaredMethod("modifyEditViewMarginDynamically", editor);
        Method bind = adapter.getDeclaredMethod("onBindViewHolder", holder, int.class);
        Method clear = callback.getDeclaredMethod("clearView", rv, holder);
        Method selection = callback.getDeclaredMethod("onSelectedChanged", holder, int.class);
        // Resolve all required members before installing hooks. No SDK/version guesses.
        module.hook(created).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed(), owner = chain.getThisObject();
            Object key = fragmentAdapter.get(owner);
            State previous = states.remove(key); if (previous != null) main.removeCallbacks(previous);
            State state = new State((ViewGroup) fragmentRecycler.get(owner));
            states.put(key, state); state.apply(); return result;
        });
        module.hook(resumed).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed(); request(states.get(fragmentAdapter.get(chain.getThisObject()))); return result;
        });
        module.hook(destroyed).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            if (!fragment.isInstance(chain.getThisObject())) return chain.proceed();
            State state = states.remove(fragmentAdapter.get(chain.getThisObject()));
            if (state != null) main.removeCallbacks(state);
            return chain.proceed();
        });
        module.hook(span).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            int nativeSpan = (int) chain.proceed();
            State state = states.get(lookupAdapter.get(chain.getThisObject()));
            return state != null && state.columns == 4 && nativeSpan == 3 ? 4 : nativeSpan;
        });
        module.hook(margin).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed(), owner = chain.getThisObject(), edit = chain.getArg(0);
            State state = states.get(owner);
            if (state != null && state.columns == 4 && edit != null) {
                View view = (View) itemView.get(edit);
                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
                params.topMargin = NativeDesktopColumns.editTopMargin(((List<?>) items.get(owner)).size(),
                        rows.getInt(owner), appHeight.getInt(owner), rowSpace.getInt(owner), editHeight.getInt(owner));
            }
            return result;
        });
        module.hook(bind).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed(); State state = states.get(chain.getThisObject());
            View view = (View) itemView.get(chain.getArg(0));
            if (state != null && appItem.isInstance(view)) resizeIcon((ImageView) getIcon.invoke(view), state.columns);
            return result;
        });
        // Observe native dragging/recovery only to defer a pending column change.
        module.hook(selection).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed(); State state = states.get(callbackAdapter.get(chain.getThisObject()));
            if (state != null && (int) chain.getArg(1) == 2 && chain.getArg(0) != null) state.moving.put(chain.getArg(0), true);
            return result;
        });
        module.hook(clear).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed(); State state = states.get(getAdapter.invoke(chain.getArg(0)));
            if (state != null) { state.moving.remove(chain.getArg(1)); request(state); }
            return result;
        });
        instance = this;
        NativeModule.info("Native desktop columns installed; stock vertical launcher retained");
    }

    static void refreshAll() {
        NativeDesktopColumnsHook hook = instance;
        if (hook != null) hook.main.post(() -> {
            for (State state : new ArrayList<>(hook.states.values())) hook.request(state);
        });
    }

    private void request(State state) {
        if (state != null) { main.removeCallbacks(state); main.post(state); }
    }

    private void resizeIcon(ImageView image, int columns) {
        ViewGroup.LayoutParams params = image.getLayoutParams();
        int[] original = iconSizes.get(image);
        if (columns == 4) {
            if (original == null) { original = new int[]{params.width, params.height}; iconSizes.put(image, original); }
            if (original[0] > 0 && original[1] > 0) {
                params.width = Math.round(original[0] * .75f); params.height = Math.round(original[1] * .75f);
                image.setLayoutParams(params);
            }
        } else if (original != null) {
            params.width = original[0]; params.height = original[1]; image.setLayoutParams(params); iconSizes.remove(image);
        }
    }

    private final class State implements Runnable {
        final WeakReference<ViewGroup> recycler;
        final WeakHashMap<Object, Boolean> moving = new WeakHashMap<>();
        int columns = 3;
        State(ViewGroup recycler) { this.recycler = new WeakReference<>(recycler); }
        @Override public void run() {
            try { apply(); }
            catch (ReflectiveOperationException | RuntimeException error) { NativeModule.warning("Cannot update native desktop columns", error); }
        }
        void apply() throws ReflectiveOperationException {
            ViewGroup view = recycler.get(); if (view == null) return;
            DockConfig config = NativeModule.config();
            int wanted = config.enabled ? NativeDesktopColumns.normalize(config.desktopColumns) : 3;
            if (wanted == columns) return;
            // The next resume/clearView applies deferred changes; no polling or gesture replacement.
            if ((boolean) computing.invoke(view) || !moving.isEmpty()) return;
            Object manager = getManager.invoke(view), adapter = getAdapter.invoke(view);
            int first = (int) firstVisible.invoke(manager);
            View anchor = first >= 0 ? (View) findView.invoke(manager, first) : null;
            int offset = anchor == null ? 0 : (int) decoratedTop.invoke(manager, anchor) - view.getPaddingTop();
            columns = wanted;
            setSpanCount.invoke(manager, wanted);
            Object lookup = getLookup.invoke(manager); invalidateIndex.invoke(lookup); invalidateGroup.invoke(lookup);
            notifyData.invoke(adapter);
            if (anchor != null) scrollTo.invoke(manager, first, offset);
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
}
