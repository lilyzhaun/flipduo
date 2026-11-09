package com.flipduo.module;

import android.app.KeyguardManager;
import android.content.*;
import android.graphics.Canvas;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.util.DisplayMetrics;
import android.view.*;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

/** Suppresses only status-bar drawing. Native visibility, input, window flags and inset sources stay intact. */
final class CoverStatusPolicy {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ArrayList<Entry> ENTRIES = new ArrayList<>();
    private static final Set<Method> DRAW_HOOKS = new HashSet<>();
    private static Context context;
    private static final Runnable REFRESH = CoverStatusPolicy::refreshOnMain;
    private static final WeakHashMap<Object, Boolean> HEIGHT_CONTROLLERS = new WeakHashMap<>();
    private static Method refreshHeight;
    private static final ThreadLocal<Boolean> WINDOW_HEIGHT = ThreadLocal.withInitial(() -> false);

    static void install(NativeModule module, ClassLoader loader) throws Exception {
        // Observe the color already resolved by SystemUI, including its native light/dark recognition.
        for (String name : new String[]{"com.android.systemui.statusbar.policy.Clock", "com.android.systemui.statusbar.views.MiuiClock"}) {
            Class<?> clock = loader.loadClass(name);
            for (Method method : clock.getDeclaredMethods()) {
                if (!method.getName().equals("onDarkChanged") && !method.getName().equals("onLightDarkTintChanged")
                        && !method.getName().equals("onAttachedToWindow")) continue;
                module.hook(method).intercept(chain -> {
                    Object result = chain.proceed();
                    readClockTint((TextView) chain.getThisObject());
                    return result;
                });
            }
        }
        Class<?> bars = Class.forName("com.android.internal.policy.SystemBarUtils");
        for (Method method : bars.getDeclaredMethods()) {
            if (!method.getName().equals("getStatusBarHeight") && !method.getName().equals("getStatusBarHeightForRotation")) continue;
            if (method.getParameterTypes().length == 0 || method.getParameterTypes()[0] != Context.class) continue;
            module.hook(method).intercept(chain -> {
                int nativeHeight = (Integer) chain.proceed();
                Context c = (Context) chain.getArg(0);
                // NotificationHeaderExpandController also queries this helper. Only the status
                // window may consume the compressed height; shade/header/control-center keep native geometry.
                return WINDOW_HEIGHT.get() && shouldHide(c.getDisplay())
                        ? Math.min(nativeHeight, NativeModule.config().statusBarHeight) : nativeHeight;
            });
        }
        Class<?> controller = loader.loadClass("com.android.systemui.statusbar.window.StatusBarWindowControllerImpl");
        refreshHeight = controller.getDeclaredMethod("refreshStatusBarHeight");
        for (var constructor : controller.getDeclaredConstructors()) hookHeightScope(module, constructor);
        for (Method method : controller.getDeclaredMethods()) {
            if (!method.getName().equals("apply") && !method.getName().equals("getBarLayoutParamsForRotation")
                    && !method.equals(refreshHeight)) continue;
            hookHeightScope(module, method);
        }
        Class<?> wm = Class.forName("android.view.WindowManagerGlobal");
        for (Method method : wm.getDeclaredMethods()) {
            if (!method.getName().equals("addView") && !method.getName().equals("updateViewLayout")) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length < 2 || types[0] != View.class) continue;
            module.hook(method).intercept(chain -> {
                View root = (View)chain.getArg(0);
                Object value = chain.getArg(1);
                if (value instanceof WindowManager.LayoutParams) {
                    WindowManager.LayoutParams params = (WindowManager.LayoutParams)value;
                    if ("StatusBar".contentEquals(params.getTitle())) {
                        Entry e = find(root);
                        if (e == null) {
                            // Hook both render paths: skip-draw ViewGroups can bypass View.draw() on hardware canvases.
                            hookDrawing(module, root.getClass(), "draw");
                            hookDrawing(module, root.getClass(), "dispatchDraw");
                            e = new Entry(root); ENTRIES.add(e);
                            root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                                public void onViewAttachedToWindow(View v) { refresh(); }
                                public void onViewDetachedFromWindow(View v) { refresh(); }
                            });
                        }
                        apply(e);
                    }
                }
                Object result = chain.proceed();
                if (method.getName().equals("addView") && value instanceof WindowManager.LayoutParams
                        && "StatusBar".contentEquals(((WindowManager.LayoutParams)value).getTitle())) refresh();
                return result;
            });
        }
    }

    private static void hookHeightScope(NativeModule module, Executable method) {
        module.hook(method).intercept(chain -> {
            boolean previous = WINDOW_HEIGHT.get();
            WINDOW_HEIGHT.set(true);
            Object result;
            try { result = chain.proceed(); }
            finally { WINDOW_HEIGHT.set(previous); }
            HEIGHT_CONTROLLERS.put(chain.getThisObject(), Boolean.TRUE);
            return result;
        });
    }

    private static void hookDrawing(NativeModule module, Class<?> type, String name) throws NoSuchMethodException {
        Method method = null;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { method = current.getDeclaredMethod(name, Canvas.class); break; }
            catch (NoSuchMethodException ignored) { }
        }
        if (method == null) throw new NoSuchMethodException(type.getName() + "." + name);
        if (DRAW_HOOKS.contains(method)) return;
        module.hook(method).intercept(chain -> {
            Entry entry = find((View) chain.getThisObject());
            if (entry != null && Boolean.TRUE.equals(entry.hidden)) return null;
            return chain.proceed();
        });
        DRAW_HOOKS.add(method);
    }

    static void start(Context app) {
        context = app;
        app.getSystemService(DisplayManager.class).registerDisplayListener(new DisplayManager.DisplayListener() {
            public void onDisplayAdded(int id) { refresh(); }
            public void onDisplayRemoved(int id) { refresh(); }
            public void onDisplayChanged(int id) { refresh(); }
        }, MAIN);
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON); filter.addAction(Intent.ACTION_SCREEN_OFF); filter.addAction(Intent.ACTION_USER_PRESENT);
        app.registerReceiver(new BroadcastReceiver() { public void onReceive(Context c, Intent i) { refresh(); } }, filter, Context.RECEIVER_NOT_EXPORTED);
        refresh();
    }
    private static Entry find(View v) { for (Entry e : ENTRIES) if (e.root.get() == v) return e; return null; }
    private static boolean shouldHide(Display display) {
        DockConfig config = NativeModule.config();
        if (!config.enabled || !config.hideStatus || context == null) return false;
        if (context.getSystemService(KeyguardManager.class).isKeyguardLocked()) return false;
        return isCover(display);
    }
    private static boolean isCover(Display display) {
        if (display == null || display.getState() != Display.STATE_ON) return false;
        DisplayMetrics dm = new DisplayMetrics(); display.getRealMetrics(dm);
        return CutoutAppConfig.isCoverDisplay(dm.widthPixels, dm.heightPixels)
                && (!CoverRotation.landscape(display.getRotation()) || NativeModule.config().landscapeCompat);
    }
    private static boolean readClockTint(TextView clock) {
        if (find(clock.getRootView()) == null || !isCover(clock.getDisplay())) return false;
        SystemStatusSource.statusTint(clock.getCurrentTextColor(), clock.getDisplay().getDisplayId());
        return true;
    }
    private static boolean findClockTint(View root) {
        if (root instanceof TextView && (root.getClass().getName().equals("com.android.systemui.statusbar.policy.Clock")
                || root.getClass().getName().equals("com.android.systemui.statusbar.views.MiuiClock")))
            return readClockTint((TextView) root);
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++)
            if (findClockTint(group.getChildAt(i))) return true;
        return false;
    }
    private static void apply(Entry e) {
        View v = e.root.get(); if (v == null) return;
        boolean hide = shouldHide(v.getDisplay());
        if (e.hidden == null || e.hidden != hide) {
            e.hidden = hide;
            // Re-record the root display list, including when switching back to the native lock-screen drawing.
            v.invalidate();
            NativeModule.info("Cover statusbar drawingHidden=" + hide + " nativeInputAndInsets=true"
                    + " display=" + (v.getDisplay() == null ? -1 : v.getDisplay().getDisplayId()));
        }
    }
    static void refresh() {
        MAIN.removeCallbacks(REFRESH); MAIN.post(REFRESH);
    }
    /** Whether this policy started and therefore sends the SystemUI receipt on refresh. Main thread. */
    static boolean reporting() { return context != null; }
    private static void refreshOnMain() {
        CoverNotificationLayout.refresh();
        for (Object controller : new ArrayList<>(HEIGHT_CONTROLLERS.keySet())) {
            try { refreshHeight.invoke(controller); }
            catch (ReflectiveOperationException error) { NativeModule.warning("Cannot refresh native status-bar height", error); }
        }
        CoverNotificationClock.refresh();
        boolean tintFound = false;
        for (int i = ENTRIES.size() - 1; i >= 0; i--) {
            Entry e = ENTRIES.get(i); View v = e.root.get();
            if (v == null) { ENTRIES.remove(i); continue; }
            apply(e);
            if (isCover(v.getDisplay())) tintFound |= findClockTint(v);
        }
        if (!tintFound) SystemStatusSource.statusTint(0, -1);
        if (context != null) RuntimeReport.applied(context, "systemui", NativeModule.config());
    }
    private static final class Entry {
        final WeakReference<View> root;
        Boolean hidden;
        Entry(View v) { root = new WeakReference<>(v); }
    }
}
