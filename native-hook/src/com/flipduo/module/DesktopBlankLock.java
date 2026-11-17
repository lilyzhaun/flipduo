// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.app.KeyguardManager;
import android.content.Intent;
import android.view.*;
import android.view.accessibility.AccessibilityManager;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.WeakHashMap;

/** Observes only events delivered to the stock app recycler. Never consumes or synthesizes input. */
final class DesktopBlankLock {
    private final WeakHashMap<View, BlankTapPair> pairs = new WeakHashMap<>();
    private final Method childAt, scrollState, menuShowing, holder;
    private final Class<?> editor, spacer;
    private final Field fragment;

    DesktopBlankLock(NativeModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> list = loader.loadClass("com.miui.fliphome.recyclerview.HomeRecyclerView");
        editor = loader.loadClass("com.miui.fliphome.adapter.FlipLauncherAdapter$EditViewHolder");
        spacer = loader.loadClass("com.miui.fliphome.adapter.FlipLauncherAdapter$HeaderViewHolder");
        fragment = list.getDeclaredField("mFlipLauncherFragment"); fragment.setAccessible(true);
        menuShowing = fragment.getType().getMethod("isShortcutMenuShow");
        childAt = list.getMethod("findChildViewUnder", float.class, float.class);
        holder = list.getMethod("getChildViewHolder", View.class);
        scrollState = list.getMethod("getScrollState");
        Method dispatch = list.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class);
        Method focus = list.getDeclaredMethod("dispatchWindowFocusChanged", boolean.class);
        module.hook(focus).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            pairs.remove(chain.getThisObject()); return chain.proceed();
        });
        module.hook(dispatch).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            ViewGroup view = (ViewGroup) chain.getThisObject(); MotionEvent event = (MotionEvent) chain.getArg(0);
            DockConfig c = NativeModule.config(); boolean lock = false;
            if (c.enabled && c.desktopDoubleTapLock) lock = observe(view, event); else pairs.remove(view);
            Object result = chain.proceed();
            if (lock && view.hasWindowFocus() && view.isShown()) {
                Display display = view.getDisplay();
                if (display != null && display.getState() == Display.STATE_ON && CutoutAppConfig.isCoverDisplay(display.getMode().getPhysicalWidth(), display.getMode().getPhysicalHeight())
                        && !view.getContext().getSystemService(KeyguardManager.class).isKeyguardLocked()
                        && !view.getContext().getSystemService(AccessibilityManager.class).isTouchExplorationEnabled())
                    view.getContext().sendBroadcast(new Intent(SystemTools.REQUEST).setPackage("com.android.systemui")
                            .putExtra("action", "lock").putExtra("displayId", display.getDisplayId()), StatusWire.PERMISSION);
            }
            return result;
        });
    }
    private boolean observe(ViewGroup list, MotionEvent e) throws ReflectiveOperationException {
        int a = e.getActionMasked(); BlankTapPair pair = pairs.get(list);
        if (a != MotionEvent.ACTION_DOWN && pair == null) return false;
        if (!e.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) || e.getPointerCount() != 1 || !list.hasWindowFocus()
                || (a != MotionEvent.ACTION_DOWN && a != MotionEvent.ACTION_UP && a != MotionEvent.ACTION_MOVE)) {
            pairs.remove(list); return false;
        }
        float x = e.getX(), y = e.getY();
        if (a == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < e.getHistorySize(); i++) pair.move(e.getHistoricalX(i), e.getHistoricalY(i));
            pair.move(x, y); return false;
        }
        Object owner = fragment.get(list);
        if (owner == null || (boolean)menuShowing.invoke(owner) || (int)scrollState.invoke(list) != 0 || !blank(list, x, y)) {
            pairs.remove(list); return false;
        }
        if (a == MotionEvent.ACTION_UP) return pair.up(e.getEventTime(), x, y);
        if (pair == null) {
            ViewConfiguration vc = ViewConfiguration.get(list.getContext());
            pair = new BlankTapPair(vc.getScaledTouchSlop(), vc.getScaledDoubleTapSlop(), ViewConfiguration.getDoubleTapTimeout(), ViewConfiguration.getLongPressTimeout());
            pairs.put(list, pair);
        }
        pair.down(e.getEventTime(), x, y); return false;
    }
    private boolean blank(ViewGroup list, float x, float y) throws ReflectiveOperationException {
        if (x < 0 || y < 0 || x >= list.getWidth() || y >= list.getHeight() || list.getChildCount() == 0) return false;
        View child = (View)childAt.invoke(list, x, y);
        if (child == null) return true;
        Object item = holder.invoke(list, child);
        if (!editor.isInstance(item) && !spacer.isInstance(item)) return false;
        // Only bare padding in known spacer/footer layouts. Any painted descendant owns its whole box.
        if (child.isClickable() || child.isLongClickable()) return false;
        if (child instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            View v = group.getChildAt(i); float dx = x-child.getX()-v.getX(), dy = y-child.getY()-v.getY();
            if (v.getVisibility() == View.VISIBLE && dx >= 0 && dx < v.getWidth() && dy >= 0 && dy < v.getHeight()) return false;
        }
        return true;
    }
}
