package com.flipduo.module;

import android.graphics.drawable.Drawable;
import android.app.Application;
import android.content.*;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.*;
import android.widget.FrameLayout;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Injects children into Xiaomi's existing widget window. Never creates a window. */
public final class NativeModule extends XposedModule {
    private static final String TAG = "FlipDuoNative";
    private static final String VIEW_TAG = "flipduo.native.controls";
    private static volatile DockConfig currentConfig = new DockConfig();
    static DockConfig config() { return currentConfig; }
    /** SystemUI Application after startup; only used for the fallback receipt. */
    private static volatile Context systemUiApp;
    private SharedPreferences preferences;
    private SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;
    private Runnable applyPreferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    static void info(String message) {
        NativeLog.info(message);
    }
    static void warning(String message, Throwable error) {
        NativeLog.warning(message, error);
    }
    private final List<WeakReference<NativeDock>> docks = new CopyOnWriteArrayList<>();
    private final Map<ViewGroup, WindowSession> sessions = new WeakHashMap<>();
    private boolean diagnosticsRegistered;
    private Method trustNativeOverlay;
    private Field overlayPrivateFlags;
    private Object windowManager;
    private AppCutoutHook appCutoutHook;
    private InnerRecentsSnapshotHook innerRecentsSnapshotHook;
    private CoverAppCompatHook coverAppCompatHook;
    private CoverBottomGuardPolicy bottomGuardPolicy;
    private RecentsAppAnimation cornerHandoff;
    private StockWidgetStyle widgetStyle;
    /** Set only while the OEM recomputes widget material for a scene with nothing behind the slot (WidgetMaterialPolicy). */
    private static final ThreadLocal<Boolean> WIDGET_NO_BLUR = ThreadLocal.withInitial(() -> false);
    /** BlurColorUtil.setViewBlurMode(View,int); null when this FlipHome lacks the no-blur fallback hooks. */
    private Method widgetViewBlurMode;
    private Field nativeAppColor;
    private Method nativeColorDark;
    private boolean toneRefreshQueued;
    private final Runnable refreshTones = () -> {
        toneRefreshQueued = false;
        for (Map.Entry<ViewGroup, WindowSession> entry : new ArrayList<>(sessions.entrySet())) {
            WindowSession session = entry.getValue(); NativeDock dock = session.dock.get();
            if (!session.toneReady || !session.control.active() || dock == null || !dock.enabled || dock.getVisibility() != View.VISIBLE) continue;
            try { applyDockTone(session, dock); }
            catch (ReflectiveOperationException error) { warning("Cannot follow native side color", error); }
        }
    };
    private final Runnable refreshScenes = () -> {
        for (ViewGroup root : new ArrayList<>(sessions.keySet())) {
            try { configureNativeColumn(root); }
            catch (Exception error) { warning("Cannot follow native cover scene", error); }
        }
    };

    @Override public void onSystemServerStarting(SystemServerStartingParam param) {
        NativeLog.writer = (message, error) -> log(error == null ? Log.INFO : Log.WARN, TAG, message, error);
        try { appCutoutHook = AppCutoutHook.install(this, param.getClassLoader()); }
        catch (Throwable error) { warning("AppCutout unavailable; app window policy remains inactive", error); }
        try { innerRecentsSnapshotHook = new InnerRecentsSnapshotHook(this, param.getClassLoader()); }
        catch (ReflectiveOperationException error) { warning("Inner recents snapshot compatibility unavailable; original snapshots retained", error); }
        try { coverAppCompatHook = new CoverAppCompatHook(this, param.getClassLoader()); }
        catch (ReflectiveOperationException | RuntimeException error) { warning("Cover app eligibility extension unavailable; original eligibility retained", error); }
        try { bottomGuardPolicy = new CoverBottomGuardPolicy(this, param.getClassLoader()); }
        catch (ReflectiveOperationException | RuntimeException error) { warning("Bottom guard rendering policy unavailable; native overlay retained", error); }
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        NativeLog.writer = (message, error) -> log(error == null ? Log.INFO : Log.WARN, TAG, message, error);
        if (param.isFirstPackage() && ("com.miui.fliphome".equals(param.getPackageName()) || "com.android.systemui".equals(param.getPackageName())
                || "com.miui.screenshot".equals(param.getPackageName()) || SogouImeCutout.PACKAGE.equals(param.getPackageName()))) {
            try {
                preferences = getRemotePreferences(DockConfig.GROUP); currentConfig = DockConfig.read(preferences);
                applyPreferences = () -> {
                    currentConfig = DockConfig.read(preferences);
                    if ("com.miui.fliphome".equals(param.getPackageName())) CoverRecents.refresh();
                    if (SogouImeCutout.PACKAGE.equals(param.getPackageName())) SogouImeCutout.refresh();
                    else if ("com.android.systemui".equals(param.getPackageName())) { CoverStatusPolicy.refresh(); CoverCameraShortcutPolicy.refresh(); BlurFillSource.refresh(); FocusEventBridge.refresh(); ControlCenterLandscape.refresh(); reportSystemUiFallback(); }
                    else for (ViewGroup root : new ArrayList<>(sessions.keySet())) {
                        try {
                            WindowSession s = session(root); s.control.setEnabled(currentConfig.enabled);
                            if (currentConfig.enabled) { mount(root); configureNativeColumn(root); }
                            else restoreRoot(root, s);
                            reportFlipHome(root);
                        } catch (Exception error) { warning("Layout preferences could not be applied", error); }
                    }
                };
                preferenceListener = (prefs, key) -> { main.removeCallbacks(applyPreferences); main.post(applyPreferences); };
                preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
            } catch (Exception error) { warning("Remote preferences unavailable; defaults kept", error); }
        }
        if (param.isFirstPackage() && SogouImeCutout.PACKAGE.equals(param.getPackageName())) {
            try { SogouImeCutout.install(this, param.getClassLoader()); }
            catch (ReflectiveOperationException | RuntimeException error) { warning("Sogou cutout extension unavailable; original keyboard retained", error); }
            return;
        }
        if (param.isFirstPackage() && "com.miui.screenshot".equals(param.getPackageName())) {
            try { ScreenshotFrameHook.install(this, param.getClassLoader()); }
            catch (ReflectiveOperationException error) { warning("Screenshot hook unavailable; native screenshots retained", error); }
            return;
        }
        if (param.isFirstPackage() && "com.android.systemui".equals(param.getPackageName())) {
            // Receipt capability levels only record what happened; they never change install order or fallbacks.
            try { BlurFillSource.install(this, param.getClassLoader()); HostCapabilities.installed("blur_source", true); }
            catch (ReflectiveOperationException error) { HostCapabilities.installed("blur_source", false); warning("Live blur-fill unavailable; native controls retained", error); }
            try { CoverCameraShortcutPolicy.install(this, param.getClassLoader()); HostCapabilities.installed("camera_shortcut", true); HostCapabilities.started("camera_shortcut"); }
            catch (ReflectiveOperationException error) { HostCapabilities.installed("camera_shortcut", false); warning("Cover camera shortcut policy unavailable; native shortcut retained", error); }
            try { ControlCenterLandscape.install(this, param.getClassLoader()); HostCapabilities.installed("control_center", true); HostCapabilities.started("control_center"); }
            catch (ReflectiveOperationException error) { HostCapabilities.installed("control_center", false); warning("Cover control-center adaptation unavailable; native panel retained", error); }
            try { CameraStatusSource.install(this, param.getClassLoader()); HostCapabilities.installed("camera_status", true); }
            catch (ReflectiveOperationException error) { HostCapabilities.installed("camera_status", false); warning("Camera icon binding callbacks unavailable; collection snapshots retained", error); }
            try { FocusEventBridge.install(this, param.getClassLoader()); HostCapabilities.installed("event_bridge", true); }
            catch (ReflectiveOperationException error) { HostCapabilities.installed("event_bridge", false); warning("Native focus integration unavailable; original reminders retained", error); }
            try { MiniWindowBridge.install(this, param.getClassLoader()); HostCapabilities.installed("mini_window", true); HostCapabilities.started("mini_window"); }
            catch (ReflectiveOperationException error) { HostCapabilities.installed("mini_window", false); warning("Native mini window slot unavailable; long-pressed recents is ignored", error); }
            try { CoverStatusPolicy.install(this, param.getClassLoader()); HostCapabilities.installed("status_bar", true); }
            catch (Throwable error) { HostCapabilities.installed("status_bar", false); warning("Cover statusbar policy unavailable; status source and tools kept", error); }
            try { CoverNotificationLayout.install(this, param.getClassLoader()); }
            catch (ReflectiveOperationException | RuntimeException error) { warning("Cover notification layout unavailable; native shade retained", error); }
            try { CoverNotificationClock.install(this, param.getClassLoader()); }
            catch (ReflectiveOperationException | RuntimeException error) { warning("Cover notification clock reflow unavailable; native clock retained", error); }
            try {
                // HyperOS 4 does not expose the AOSP SystemUIApplication class name.
                hook(Application.class.getDeclaredMethod("attach", Context.class)).intercept(chain -> {
                    Object result = chain.proceed();
                    Application application = (Application) chain.getThisObject();
                    if (!"com.android.systemui".equals(application.getPackageName())) return result;
                    // Run after handleBindApplication finishes onCreate, independent of the OEM Application class or Instrumentation.
                    new Handler(Looper.getMainLooper()).post(() -> {
                        try { BlurFillSource.start(application); HostCapabilities.started("blur_source"); }
                        catch (RuntimeException error) { HostCapabilities.startFailed("blur_source"); warning("Live blur-fill startup failed; other sources retained", error); }
                        try { SystemStatusSource.start(application); HostCapabilities.started("status_source"); }
                        catch (Throwable error) { HostCapabilities.startFailed("status_source"); log(Log.ERROR, TAG, "SystemUI status source failed", error); }
                         try {
                             SystemTools.start(application); HostCapabilities.started("tools");
                             CoverStatusPolicy.start(application); HostCapabilities.started("status_bar");
                             FocusEventBridge.start(application); HostCapabilities.started("event_bridge");
                         } catch (Throwable error) {
                             // Same grouped start as before; the receipt now shows which part did not initialise.
                             for (String id : new String[]{"tools", "status_bar", "event_bridge"}) HostCapabilities.startFailed(id);
                             log(Log.ERROR, TAG, "SystemUI tool startup failed", error);
                         }
                         try { CameraStatusSource.start(application); HostCapabilities.started("camera_status"); }
                         catch (RuntimeException error) { HostCapabilities.startFailed("camera_status"); warning("Camera status startup failed; existing controls retained", error); }
                         systemUiApp = application;
                         // CoverStatusPolicy normally sends the receipt; without it, still report so optional failures are visible.
                         reportSystemUiFallback();
                    });
                    return result;
                });
                log(Log.INFO, TAG, "Universal SystemUI application entry hooked");
            } catch (Throwable error) { log(Log.ERROR, TAG, "Cannot install SystemUI state hook", error); }
            return;
        }
        if (!param.isFirstPackage() || !"com.miui.fliphome".equals(param.getPackageName())) return;
        CoverRecents.install(this, param.getClassLoader());
        try {
            ClassLoader cl = param.getClassLoader();
            // FlipHome already holds INTERNAL_SYSTEM_WINDOW. The transparent rounding bleed must not make
            // InputDispatcher reject application touches outside our precise native touchableRegion.
            trustNativeOverlay = WindowManager.LayoutParams.class.getDeclaredMethod("setTrustedOverlay");
            overlayPrivateFlags = WindowManager.LayoutParams.class.getDeclaredField("privateFlags");
            trustNativeOverlay.setAccessible(true); overlayPrivateFlags.setAccessible(true);
            windowManager = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
            Class<?> group = cl.loadClass("com.miui.fliphome.widget.ui.WatchOverlayGroupView");
            Class<?> owner = cl.loadClass("com.miui.fliphome.widget.WatchOverlayWindow");
            Class<?> background = cl.loadClass("com.miui.fliphome.widget.WidgetBgHelper");
            nativeAppColor = background.getDeclaredField("appColor"); nativeAppColor.setAccessible(true);
            nativeColorDark = cl.loadClass("com.miui.fliphome.utils.BlurColorUtil").getDeclaredMethod("isColorDark", int.class);
            nativeColorDark.setAccessible(true);
            Class<?> widgetManager = cl.loadClass("com.miui.fliphome.widget.ui.FlipWidgetManager");
            // Resolve the verified lifecycle contracts before installing any FlipHome hooks.
            // HyperOS 3 has no updateWindowSize; its absence must not abort rotation/power hooks halfway.
            List<Method> geometryLifecycle = NativeHostGeometry.lifecycleMethods(group);
            Method orientationUpdate = group.getDeclaredMethod("updateLayoutByOrientation", int.class);
            Method rotationHide = group.getDeclaredMethod("isHide");
            Method hostVisibility = group.getDeclaredMethod("setVisibility", int.class);
            Method enterPower = owner.getDeclaredMethod("enterPowerMode");
            Method exitPower = owner.getDeclaredMethod("exitPowerMode");
            try { widgetStyle = new StockWidgetStyle(cl); HostCapabilities.installed("widgets", true); HostCapabilities.started("widgets"); }
            catch (ReflectiveOperationException error) { HostCapabilities.installed("widgets", false); warning("Native widget palette unavailable; widget entry disabled, controls retained", error); }
            try { cornerHandoff = new RecentsAppAnimation(this, cl, refreshScenes); HostCapabilities.installed("recents_corners", true); HostCapabilities.started("recents_corners"); }
            catch (ReflectiveOperationException error) { HostCapabilities.installed("recents_corners", false); warning("Task corner handoff unavailable; native transition retained", error); }
            // refreshViews explicitly makes the stock pager visible again when widget data changes.
            // Retain the data/model, but keep its presentation suppressed while our controls own this root.
            hook(widgetManager.getDeclaredMethod("refreshViews", List.class)).intercept(chain -> {
                Object result = chain.proceed();
                View pager = (View) field(chain.getThisObject(), "mPagerView");
                if (pager != null && pager.getParent() instanceof ViewGroup root && ownsRoot(root)) {
                    WindowSession session = sessions.get(root);
                    NativeDock dock = session.dock.get();
                    // A null refresh means temporarily hidden; the native method retains mChildViews.
                    // Only the actual retained widget list decides whether content is gone.
                    boolean available = widgetStyle != null && widgetCount(pager) > 0;
                    if (dock != null) dock.widgetsAvailable(available);
                    if (widgetsExpanded(session)) {
                        if (!available)
                            closeWidgets(root);
                        else session.widgets.present();
                    } else hideStockPager(pager);
                }
                return result;
            });
            hook(owner.getDeclaredMethod("updateWidgetVisible", boolean.class)).intercept(chain -> {
                ViewGroup root = (ViewGroup) field(chain.getThisObject(), "mWatchOverlayGroupView");
                if (!ownsRoot(root)) return chain.proceed();
                WindowSession session = sessions.get(root);
                if (widgetsExpanded(session)) {
                    if (widgetDisplayExited(root, session)) closeWidgets(root);
                    else session.widgets.present(); // OEM visibility/alpha is presentation only, not a collapse command.
                    return null;
                }
                hideStockPager((View) field(root, "mPagerView"));
                return null; // Do not let the OEM pager's AUTO_ALPHA animation reopen a hidden widget.
            });
            var dispatchChildren = getInvoker(ViewGroup.class.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class));
            hook(group.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class)).intercept(chain -> {
                ViewGroup root = (ViewGroup) chain.getThisObject();
                if (!ownsRoot(root)) return chain.proceed();
                WindowSession session = sessions.get(root);
                if (widgetsExpanded(session)) {
                    MotionEvent event = (MotionEvent) chain.getArg(0);
                    if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) return true;
                    if (session.widgets.touch(event, currentConfig.animations, currentConfig.animationMs)) {
                        if (session.widgets.cancelChild()) {
                            int action = event.getAction(); event.setAction(MotionEvent.ACTION_CANCEL);
                            try { dispatchChildren.invokeSpecial(root, event); } finally { event.setAction(action); }
                        }
                        return true;
                    }
                }
                // Stock dispatch redirects touches to FlipLauncher whenever pager.alpha != 1.
                // A deliberately hidden pager must not redirect touches away from the injected controls.
                return dispatchChildren.invokeSpecial(chain.getThisObject(), chain.getArg(0));
            });
            // These are Xiaomi's actual desktop and WMS full-screen signals, not package allowlists.
            background.getDeclaredField("isDesk");
            background.getDeclaredField("isFullScreen");
            hook(background.getDeclaredMethod("onAppColorChanged", int.class)).intercept(chain -> {
                Object result = chain.proceed();
                // Observe the already-delivered OEM strip color, not its 300ms-delayed widget refresh.
                // Paint only: do not touch geometry, native timing, scene ownership or seam backgrounds.
                if (!toneRefreshQueued) { toneRefreshQueued = true; main.post(refreshTones); }
                return result;
            });
            for (String name : new String[]{"onDeskChanged", "onFullScreenChanged"}) {
                hook(background.getDeclaredMethod(name, boolean.class)).intercept(chain -> {
                    Object result = chain.proceed();
                    nativeSceneChanged(chain.getThisObject(), "onDeskChanged".equals(name) && !(Boolean) chain.getArg(0));
                    return result;
                });
            }
            try {
                // Optional: the OEM's own no-blur widget material for scenes with no blur source. Missing
                // methods keep the OEM blur path and never stop the other FlipHome hooks.
                Class<?> blurUtil = cl.loadClass("com.miui.fliphome.utils.BlurColorUtil");
                Method supportBlur = blurUtil.getDeclaredMethod("isSupportBlur", Context.class);
                Method viewBlur = blurUtil.getDeclaredMethod("setViewBlurMode", View.class, int.class);
                viewBlur.setAccessible(true);
                hook(supportBlur).intercept(chain -> WIDGET_NO_BLUR.get() ? Boolean.FALSE : chain.proceed());
                widgetViewBlurMode = viewBlur;
            } catch (ReflectiveOperationException error) { warning("Widget no-blur material unavailable; OEM blur kept", error); }
            for (String name : new String[]{"updateBackground", "updateBackgroundToN8"}) {
                hook(background.getDeclaredMethod(name, View.class, List.class, List.class, int.class)).intercept(chain -> {
                    // The stock pager owns its material, blend colors and theme; we only choose which of the
                    // OEM's own branches applies. Our controls yield their separate materials while it is expanded.
                    boolean noBlur = widgetViewBlurMode != null && WidgetMaterialPolicy.noBlurSource(currentConfig.enabled,
                            currentConfig.background, Boolean.TRUE.equals(field(chain.getThisObject(), "isDesk")));
                    Object result;
                    if (!noBlur) result = chain.proceed();
                    else {
                        WIDGET_NO_BLUR.set(true);
                        try { result = chain.proceed(); } finally { WIDGET_NO_BLUR.set(false); }
                        // The OEM solid branch keeps a card's earlier ViewBlur=1; the 80%/50% unfocused colours would
                        // still show the empty sample through, so cards leave the blur mode the OEM no longer uses.
                        List<?> cards = (List<?>) chain.getArg(1);
                        if (cards != null) for (Object card : cards) {
                            try { widgetViewBlurMode.invoke(null, card, 0); }
                            catch (ReflectiveOperationException error) { warning("Could not clear widget card blur mode", error); break; }
                        }
                    }
                    main.removeCallbacks(refreshScenes); main.post(refreshScenes);
                    return result;
                });
            }
            hook(owner.getDeclaredConstructor(Context.class)).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object window = chain.getThisObject();
                    ViewGroup root = (ViewGroup) field(window, "mWatchOverlayGroupView");
                    WindowSession session = session(root);
                    session.backgroundHelper = new WeakReference<>(field(window, "mBackgroundStatus"));
                    session.windowOwner = new WeakReference<>(window);
                    // Keep Xiaomi's constructor-time touch/status-bar conflict regions at their native size.
                    mount(root); configureNativeColumn(root);
                } catch (Exception error) { warning("Cannot bind native cover scene; stock window kept", error); }
                return result;
            });
            for (Method method : geometryLifecycle) {
                hook(method).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        ViewGroup root = (ViewGroup) chain.getThisObject();
                        WindowSession session = sessions.get(root);
                        if (session != null && "updateWindowSize".equals(method.getName())) session.windowCommit.invalidate();
                        mount(root);
                        configureNativeColumn(root);
                    }
                    catch (Throwable error) { log(Log.ERROR, TAG, "Native container injection failed", error); }
                    return result;
                });
            }
            hook(rotationHide).intercept(chain -> {
                boolean nativeHide = (Boolean) chain.proceed();
                ViewGroup root = (ViewGroup) chain.getThisObject();
                return NativeHostVisibility.hide(nativeHide, landscapeHostAllowed(root, rotation(root)));
            });
            hook(hostVisibility).intercept(chain -> {
                ViewGroup root = (ViewGroup) chain.getThisObject();
                WindowSession session = sessions.get(root);
                if (session != null) session.hostVisibility.request((int) chain.getArg(0));
                int before = root.getVisibility();
                Object result = chain.proceed(); // Explicit native GONE/INVISIBLE remains authoritative.
                if (session != null && session.blurFill != null && root.getVisibility() != View.VISIBLE) session.blurFill.stop();
                if (before != root.getVisibility() && currentConfig.background == CoverBackground.BLUR_FILL) {
                    main.removeCallbacks(refreshScenes); main.post(refreshScenes);
                }
                return result;
            });
            hook(orientationUpdate).intercept(chain -> {
                ViewGroup root = (ViewGroup) chain.getThisObject();
                WindowSession session = session(root);
                session.hostVisibility.beginOrientation((int) field(root, "mRotation"), (int) chain.getArg(0));
                Object result;
                try { result = chain.proceed(); }
                finally { session.hostVisibility.endOrientation(); }
                try { configureNativeColumn(root); }
                catch (Throwable error) { log(Log.ERROR, TAG, "Native rotation update failed", error); }
                return result;
            });
            hook(enterPower).intercept(chain -> {
                ViewGroup root = (ViewGroup) field(chain.getThisObject(), "mWatchOverlayGroupView");
                WindowSession session = session(root);
                session.control.enterPowerMode();
                try { restoreRoot(root, session); }
                catch (Exception error) { log(Log.ERROR, TAG, "Cannot restore stock window before power mode", error); }
                return chain.proceed();
            });
            hook(exitPower).intercept(chain -> {
                Object result = chain.proceed();
                ViewGroup root = (ViewGroup) field(chain.getThisObject(), "mWatchOverlayGroupView");
                WindowSession session = session(root);
                session.control.exitPowerMode();
                try { mount(root); configureNativeColumn(root); }
                catch (Exception error) { log(Log.ERROR, TAG, "Cannot reattach native controls after power mode", error); }
                return result;
            });
            log(Log.INFO, TAG, "v6.84 native hooks installed: immediate native-strip tone; frame color stream; original window lifecycle retained");
            info("v6.95 fixed live blur-fill hooks installed; no offscreen output, clear extension gated by Shell idle");
            info("v6.97 camera rotation hooks installed; event-aligned camera child and screen-top ring origin");
            info("Native host sizing=" + (NativeHostGeometry.resizeMethod(group) == null ? "init-only" : "resource-update"));
            HostCapabilities.installed("controls", true); HostCapabilities.started("controls");
        } catch (Throwable error) {
            HostCapabilities.installed("controls", false);
            log(Log.ERROR, TAG, "Unsupported FlipHome version; stock UI kept", error);
        }
    }

    private void mount(ViewGroup root) throws Exception {
        // updateWindowSize can run before onFinishInflate has assigned the pager field.
        View pager = (View) field(root, "mPagerView");
        if (pager == null) return;
        registerDiagnostics(root.getContext().getApplicationContext());
        WindowSession session = session(root);
        if (!session.control.active() || session.rebuilding || session.backgroundHelper.get() == null) return;
        if (!widgetsExpanded(session)) hideStockPager(pager); else session.widgets.present();
        NativeDock existing = root.findViewWithTag(VIEW_TAG);
        if (existing != null) {
            session.dock = new WeakReference<>(existing);
            registerInputRegion(root, session);
            return;
        }
        NativeDock dock = new NativeDock(root.getContext());
        session.dock = new WeakReference<>(dock);
        dock.setTag(VIEW_TAG);
        root.setPadding(0, 0, 0, 0);
        root.addView(dock, new FrameLayout.LayoutParams(DockSpec.WIDTH, DockSpec.HEIGHT, Gravity.TOP | Gravity.RIGHT));
        registerInputRegion(root, session);
        docks.add(new WeakReference<>(dock));
        dock.applyAppearance(false);
        dock.widgetActions(() -> openWidgets(root), () -> closeWidgets(root));
        dock.miniWindowAction(() -> requestMiniWindow(root));
        dock.backgroundChanges(() -> { main.removeCallbacks(refreshScenes); main.post(refreshScenes); });
        dock.widgetsAvailable(widgetStyle != null && widgetCount(pager) > 0);
        dock.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l == or - ol && b - t == ob - ot) return;
            int[] at = new int[2]; root.getLocationOnScreen(at);
            log(Log.INFO, TAG, "native-frame=" + root.getWidth() + "x" + root.getHeight()
                    + " controls=" + dock.getWidth() + "x" + dock.getHeight() + " at=" + at[0] + "," + at[1]);
        });
        log(Log.INFO, TAG, "Replaced native pager contents; original window/token/rotation retained");
        reportFlipHome(root);
    }

    private static void reportSystemUiFallback() {
        Context app = systemUiApp;
        if (app != null && !CoverStatusPolicy.reporting()) RuntimeReport.applied(app, "systemui", currentConfig);
    }

    /** FlipHome receipt with the controls ownership at this moment; still a one-off report, not a poll. */
    private void reportFlipHome(ViewGroup root) {
        boolean owned = false;
        for (ViewGroup candidate : new ArrayList<>(sessions.keySet())) if (ownsRoot(candidate)) { owned = true; break; }
        HostCapabilities.active("controls", owned);
        RuntimeReport.applied(root.getContext(), "fliphome", currentConfig);
    }

    private boolean ownsRoot(ViewGroup root) {
        WindowSession session = sessions.get(root);
        NativeDock dock = session == null ? null : session.dock.get();
        return session != null && session.control.active() && dock != null && dock.enabled && dock.getParent() == root;
    }

    private boolean landscapeHostAllowed(ViewGroup root, int rotation) {
        Display display = root.getDisplay();
        return CoverRotation.landscape(rotation) && currentConfig.landscapeCompat && ownsRoot(root)
                && display != null && !coverExited(root.getContext(), display.getDisplayId());
    }
    private void reconcileHostVisibility(ViewGroup root, WindowSession session, int rotation) {
        if (root.getDisplay() == null) return; // Keep the stock gate until an attached display can be qualified.
        int before = root.getVisibility();
        session.hostVisibility.reconcile(root, rotation, landscapeHostAllowed(root, rotation));
        if (before != root.getVisibility()) info("v6.73 native host visibility=" + root.getVisibility() + " rotation=" + rotation);
    }

    private static void hideStockPager(View pager) {
        if (pager == null) return;
        pager.setVisibility(View.GONE);
        pager.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    private static boolean widgetsExpanded(WindowSession session) {
        return session != null && session.widgets != null && session.widgets.expanded();
    }

    private static int widgetCount(View pager) throws ReflectiveOperationException {
        // getAllItems() is the 1/3 reusable visual containers, not the configured widget count.
        List<?> items = (List<?>) field(pager, "mChildViews");
        return items == null ? 0 : items.size();
    }

    private static boolean widgetDisplayExited(ViewGroup root, WindowSession session) {
        return coverExited(root.getContext(), session.widgetDisplayId);
    }

    private static int rotation(View root) throws ReflectiveOperationException {
        Display display = root.getDisplay();
        return CoverGeometry.rotation(display == null ? -1 : display.getRotation(), (int) field(root, "mRotation"));
    }

    private static boolean coverExited(Context context, int displayId) {
        if (!context.getSystemService(PowerManager.class).isInteractive()
                || context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked()) return true;
        Display display = context.getSystemService(DisplayManager.class).getDisplay(displayId);
        if (display == null || display.getState() == Display.STATE_OFF) return true;
        DisplayMetrics metrics = new DisplayMetrics(); display.getRealMetrics(metrics);
        return !CutoutAppConfig.isCoverDisplay(metrics.widthPixels, metrics.heightPixels);
    }

    private void openWidgets(ViewGroup root) {
        WindowSession session = sessions.get(root);
        NativeDock dock = session == null ? null : session.dock.get();
        if (widgetStyle == null || !ownsRoot(root) || dock == null || !dock.canOpenWidgets() || !root.isShown()
                || root.getContext().getSystemService(android.app.KeyguardManager.class).isKeyguardLocked()
                || cornerHandoff != null && cornerHandoff.inFlight(root)) return;
        try {
            View pager = (View) field(root, "mPagerView");
            int count = widgetCount(pager);
            if (count == 0) {
                dock.widgetsAvailable(false);
                return;
            }
            if (session.widgets == null) session.widgets = new StockWidgetPanel(pager, () -> closeWidgets(root), () -> {
                if (!widgetsExpanded(session) || !ownsRoot(root)) return;
                try { widgetStyle.refreshVisible(session.backgroundHelper.get()); }
                catch (ReflectiveOperationException | RuntimeException error) {
                    warning("Could not prepare first visible native widget material; original panel retained", error);
                }
            });
            session.widgetDisplayId = root.getDisplay().getDisplayId();
            session.widgetRotation = rotation(root);
            session.widgets.open(session.widgetRotation, session.original.height, session.padding[1], session.padding[3]);
            dock.widgetsExpanded(true);
            configureNativeColumn(root);
            info("Native widget panel opened items=" + count + " night=" + StockWidgetStyle.night(root.getResources().getConfiguration()));
        } catch (Exception error) {
            closeWidgets(root);
            session.widgets = null;
            warning("Could not present native widgets", error);
            dock.widgetsAvailable(false);
        }
    }

    private void closeWidgets(ViewGroup root) {
        WindowSession session = sessions.get(root);
        if (!widgetsExpanded(session)) return;
        session.widgets.close();
        session.widgets = null;
        NativeDock dock = session.dock.get();
        try { widgetStyle.refresh(session.backgroundHelper.get()); }
        catch (ReflectiveOperationException error) { warning("Could not restore native widget palette", error); }
        if (dock != null) dock.widgetsExpanded(false);
        if (root.isAttachedToWindow() && !session.rebuilding) {
            try { configureNativeColumn(root); }
            catch (Exception error) { warning("Could not restore widget panel input bounds", error); }
        }
        info("Native widget panel closed; navigation restored");
    }

    /** Asks SystemUI to turn the cover's ordinary foreground app into HyperOS's own mini window in the widget slot. */
    private boolean requestMiniWindow(ViewGroup root) {
        WindowSession session = sessions.get(root);
        NativeDock dock = session == null ? null : session.dock.get();
        try {
            Object helper = session == null ? null : session.backgroundHelper.get();
            int rotation = rotation(root);
            // Desktop, native full-screen pages, the lock screen, the inner display and running task animations are ignored.
            if (!ownsRoot(root) || dock == null || session.mini != null || !root.isShown() || root.getDisplay() == null
                    || helper == null || (Boolean) field(helper, "isDesk") || (Boolean) field(helper, "isFullScreen")
                    || rotation != 0 && rotation != 2 || cornerHandoff != null && cornerHandoff.inFlight(root)
                    || coverExited(root.getContext(), root.getDisplay().getDisplayId())) {
                info("Mini window long-press ignored: not an ordinary unlocked cover app"); return false;
            }
            int width = ((FrameLayout.LayoutParams) ((View) field(root, "mPagerView")).getLayoutParams()).width;
            if (width <= 0 || session.original.height <= session.padding[1] + session.padding[3] || session.original.height > DockSpec.HEIGHT) {
                info("Mini window long-press ignored: native widget slot geometry unavailable"); return false;
            }
            int[] slot = MiniSlot.dockRect(rotation == 2, width, session.original.height, session.padding[1], session.padding[3]);
            int[] at = new int[2]; dock.getLocationOnScreen(at);
            android.graphics.Rect screen = new android.graphics.Rect(slot[0] + at[0], slot[1] + at[1], slot[2] + at[0], slot[3] + at[1]);
            int display = root.getDisplay().getDisplayId();
            session.miniRequest = new MiniHole(slot, rotation, display);
            root.getContext().sendBroadcast(new Intent(SystemTools.REQUEST).setPackage("com.android.systemui").putExtra("action", "mini_window")
                    .putExtra("displayId", display).putExtra("slot", screen), StatusWire.PERMISSION);
            info("Mini window requested for widget slot " + screen.toShortString() + " rotation=" + rotation);
            return true;
        } catch (ReflectiveOperationException error) { warning("Could not request mini window", error); return false; }
    }

    private void miniState(ViewGroup root, int task, boolean occupied, float radius) {
        WindowSession session = sessions.get(root);
        if (session == null) return;
        if (!occupied) {
            if (session.mini != null && session.mini.task == task) releaseMini(root, false); // Native exit already ran.
            return;
        }
        MiniHole request = session.miniRequest;
        session.miniRequest = null;
        if (request == null) return;
        NativeDock dock = session.dock.get();
        int rotation;
        try { rotation = rotation(root); } catch (ReflectiveOperationException error) { rotation = -1; }
        if (dock == null || !ownsRoot(root) || rotation != request.rotation || coverExited(root.getContext(), request.display)) {
            exitMini(root.getContext(), task); return; // The slot can no longer frame it; never leave it under the black column.
        }
        session.mini = request.occupy(task, radius);
        dock.miniSlotted(true);
        try { configureNativeColumn(root); } catch (Exception error) { warning("Could not open the mini window slot", error); }
        info("Native mini window occupies the widget slot task=" + task + " radius=" + radius);
    }

    private void releaseMini(ViewGroup root, boolean exitNative) {
        WindowSession session = sessions.get(root);
        if (session == null) return;
        session.miniRequest = null;
        MiniHole mini = session.mini;
        if (mini == null) return;
        session.mini = null;
        if (exitNative) exitMini(root.getContext(), mini.task);
        NativeDock dock = session.dock.get();
        if (dock != null) dock.miniSlotted(false);
        try {
            if (!exitNative) synchronizeNativeWindowScene(session.backgroundHelper.get());
            if (root.isAttachedToWindow() && !session.rebuilding) configureNativeColumn(root);
        } catch (Exception error) { warning("Could not restore controls after the mini window", error); }
        info("Mini window slot released; navigation restored");
    }

    private void nativeSceneChanged(Object helper, boolean leftDesktop) {
        Runnable update = () -> {
            try { if (leftDesktop) synchronizeNativeWindowScene(helper); }
            catch (ReflectiveOperationException error) { warning("Could not synchronize native app scene from WMS", error); }
            refreshScenes.run();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) update.run(); else main.post(update);
    }

    private void synchronizeNativeWindowScene(Object helper) throws ReflectiveOperationException {
        if (helper == null || (Boolean) field(helper, "isDesk")) return;
        for (WindowSession session : sessions.values()) {
            if (session.backgroundHelper.get() != helper) continue;
            if (session.mini != null || session.miniRequest != null) return;
            Object owner = session.windowOwner.get();
            if (owner == null) return;
            // HyperOS's ActivityObserver writes true when FlipLauncher resumes behind a mini window.
            // Returning to the SAME app need not change WMS's cached false, so no callback resets
            // that launcher-local value. Reuse the existing Binder key: registration returns WMS's
            // current state and replaces that same map entry; it does not add another listener.
            Field listener = owner.getClass().getDeclaredField("mWindowStateListener"); listener.setAccessible(true);
            Method register = windowManager.getClass().getMethod("registerFlipWindowStateListener", listener.getType());
            register.setAccessible(true);
            boolean fullScreen = (Boolean) register.invoke(windowManager, listener.get(owner));
            if (fullScreen != (Boolean) field(helper, "isFullScreen")) {
                helper.getClass().getDeclaredMethod("onFullScreenChanged", boolean.class).invoke(helper, fullScreen);
                info("Native app scene synchronized from WMS fullScreen=" + fullScreen);
            }
            return;
        }
    }

    private static void exitMini(Context context, int task) {
        context.sendBroadcast(new Intent(SystemTools.REQUEST).setPackage("com.android.systemui")
                .putExtra("action", "mini_exit").putExtra("task", task), StatusWire.PERMISSION);
    }

    private void registerDiagnostics(Context app) {
        if (diagnosticsRegistered) return;
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                for (WeakReference<NativeDock> reference : docks) {
                    NativeDock dock = reference.get(); if (dock != null && dock.enabled) dock.dumpButtonBounds();
                }
                for (ViewGroup root : new ArrayList<>(sessions.keySet())) {
                    WindowSession session = sessions.get(root);
                    try {
                        Display display = root.getDisplay();
                        int[] at = new int[2]; root.getLocationOnScreen(at);
                        WindowManager.LayoutParams params = (WindowManager.LayoutParams) field(root, "mLayoutParams");
                        NativeDock dock = session.dock.get();
                        info("Native rotation diagnostics display=" + (display == null ? -1 : display.getDisplayId())
                                + " actual=" + (display == null ? -1 : display.getRotation()) + " cached=" + field(root, "mRotation")
                                + " applied=" + session.rotation + " column=" + session.columnRotation
                                + " root=" + root.getWidth() + "x" + root.getHeight() + " at=" + at[0] + "," + at[1]
                                + " params=" + params.width + "x" + params.height + " gravity=" + params.gravity
                                + " extended=" + session.extended + " background=" + (root.getBackground() == null ? "none" : root.getBackground().getClass().getSimpleName())
                                + " handoff=" + (cornerHandoff != null && cornerHandoff.inFlight(root))
                                + " restoring=" + (cornerHandoff != null && cornerHandoff.restoring(root))
                                + " columnAmount=" + session.columnFade.opacity()
                                + " dock=" + (dock == null ? "none" : dock.getLeft() + "," + dock.getTop() + "," + dock.getRight() + "," + dock.getBottom())
                                + " scene=" + session.scene);
                    } catch (ReflectiveOperationException error) { warning("Cannot read native rotation diagnostics", error); }
                    if (widgetsExpanded(session)) {
                        try {
                            View pager = (View) field(root, "mPagerView");
                            Object state = field(session.backgroundHelper.get(), "mBackgroundState");
                            info("Native widget diagnostics current=" + pager.getClass().getMethod("getCurrentItem").invoke(pager)
                                    + " items=" + widgetCount(pager)
                                    + " color=#" + Integer.toHexString((int) field(state, "firstColor"))
                                    + " textMode=" + field(state, "mamlTextColor"));
                        } catch (ReflectiveOperationException error) { warning("Cannot read native widget diagnostic metadata", error); }
                    }
                    for (int i = 0; i < root.getChildCount(); i++) {
                        View child = root.getChildAt(i);
                        info("Native child class=" + child.getClass().getName() + " visibility=" + child.getVisibility()
                                + " alpha=" + child.getAlpha() + " bounds=" + child.getLeft() + "," + child.getTop()
                                + "," + child.getRight() + "," + child.getBottom());
                    }
                    info("Camera host diagnostics eligible=" + cameraStatusEligible(root)
                            + " mounted=" + (session.cameraView != null && session.cameraView.getParent() == root));
                }
            }
        }, new IntentFilter("com.flipduo.module.DUMP_NATIVE_CONTROLS"), StatusWire.PERMISSION,
                new Handler(Looper.getMainLooper()), Context.RECEIVER_EXPORTED);
        diagnosticsRegistered = true;
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                for (ViewGroup root : new ArrayList<>(sessions.keySet()))
                    miniState(root, intent.getIntExtra("task", -1), intent.getBooleanExtra("occupied", false), intent.getFloatExtra("radius", 0));
            }
        }, new IntentFilter(MiniWindowBridge.STATE), StatusWire.PERMISSION, main, Context.RECEIVER_EXPORTED);
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                coverLifecycleChanged(intent.getAction());
            }
        }, coverLifecycleFilter(), null, main, Context.RECEIVER_NOT_EXPORTED);
        app.getSystemService(DisplayManager.class).registerDisplayListener(new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int id) { }
            @Override public void onDisplayChanged(int id) { displayChanged(id, false); }
            @Override public void onDisplayRemoved(int id) { displayChanged(id, true); }
            private void displayChanged(int id, boolean removed) {
                for (ViewGroup root : new ArrayList<>(sessions.keySet())) {
                    WindowSession session = sessions.get(root);
                    if (widgetsExpanded(session) && session.widgetDisplayId == id && (removed || widgetDisplayExited(root, session)))
                        closeWidgets(root);
                    if (session.mini != null && session.mini.display == id && (removed || coverExited(root.getContext(), id)))
                        releaseMini(root, true);
                    // A 180-degree rotation keeps the same display size. The OEM window moves through
                    // paramsForRotation even when its cached mRotation callback is late or absent.
                    if (!removed && root.isAttachedToWindow() && root.getDisplay() != null && root.getDisplay().getDisplayId() == id) {
                        try {
                            boolean mounted = session.cameraView != null && session.cameraView.getParent() == root;
                            if (needsCoverLayout(rotation(root), session.rotation, cameraStatusEligible(root), mounted))
                                configureNativeColumn(root);
                        } catch (Exception error) { warning("Cannot align controls with display rotation", error); }
                    }
                }
            }
        }, main);
    }

    static IntentFilter coverLifecycleFilter() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction("android.intent.action.USER_SWITCHED");
        return filter;
    }

    private void coverLifecycleChanged(String action) {
        boolean leaving = Intent.ACTION_SCREEN_OFF.equals(action) || "android.intent.action.USER_SWITCHED".equals(action);
        if (!leaving && !Intent.ACTION_SCREEN_ON.equals(action) && !Intent.ACTION_USER_PRESENT.equals(action)) return;
        if (leaving) for (ViewGroup root : new ArrayList<>(sessions.keySet())) {
            if (sessions.get(root).blurFill != null) sessions.get(root).blurFill.stop();
            if ("android.intent.action.USER_SWITCHED".equals(action)) sessions.get(root).blurUserBlocked = true;
            sessions.get(root).columnFade.snap(); closeWidgets(root); releaseMini(root, true);
        }
        if (Intent.ACTION_USER_PRESENT.equals(action)) for (WindowSession session : sessions.values()) session.blurUserBlocked = false;
        // SCREEN_ON can arrive while keyguard is still locked. USER_PRESENT must reconcile again,
        // even if neither the scene, display dimensions nor rotation changed while sleeping.
        refreshScenes.run();
    }

    static boolean needsCoverLayout(int actualRotation, int appliedRotation, boolean cameraEligible, boolean cameraMounted) {
        return actualRotation != appliedRotation || cameraEligible != cameraMounted;
    }

    private static boolean cameraStatusEligible(ViewGroup root) {
        Display display = root.getDisplay();
        return currentConfig.enabled
                && (display == null || !CoverRotation.landscape(display.getRotation()) || currentConfig.landscapeCompat)
                && display != null && !coverExited(root.getContext(), display.getDisplayId());
    }

    private WindowSession session(ViewGroup root) throws Exception {
        WindowSession existing = sessions.get(root);
        if (existing != null) return existing;
        WindowSession session = new WindowSession();
        session.columnFade = new ColumnTransition(refreshScenes);
        session.columnFade.frameListener(() -> {
            if (session.blurFill != null) session.blurFill.alpha(session.columnFade.opacity());
        });
        session.geometry = CoverGeometry.resolve(root.getContext());
        info("Cover geometry source=" + session.geometry.source + " installRotation=" + session.geometry.installRotation
                + " logicalTop=" + session.geometry.top(false) + " logicalBottom=" + session.geometry.bottom(false));
        session.control.setEnabled(currentConfig.enabled);
        session.padding = new int[]{root.getPaddingLeft(), root.getPaddingTop(), root.getPaddingRight(), root.getPaddingBottom()};
        session.background = root.getBackground();
        View pager = (View) field(root, "mPagerView");
        session.pagerVisibility = pager == null ? View.VISIBLE : pager.getVisibility();
        session.pagerAccessibility = pager == null ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO : pager.getImportantForAccessibility();
        session.original.copyFrom(NativeHostGeometry.snapshot((WindowManager.LayoutParams) field(root, "mLayoutParams")));
        sessions.put(root, session);
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                session.windowCommit.invalidate();
                // Leave the initial addView/attach traversal before submitting the full native extent.
                // The OEM getter returns its own mutable mLayoutParams, not proof of a WMS update.
                view.post(() -> {
                    if (!view.isAttachedToWindow()) return;
                    try {
                        ViewGroup group = (ViewGroup) view;
                        mount(group); configureNativeColumn(group); registerInputRegion(group, session);
                    } catch (Exception error) { log(Log.ERROR, TAG, "Native window reattach failed", error); }
                });
            }
            @Override public void onViewDetachedFromWindow(View view) {
                if (session.blurFill != null) session.blurFill.stop();
                session.windowCommit.invalidate();
                if (widgetsExpanded(session)) {
                    if (widgetDisplayExited((ViewGroup) view, session)) closeWidgets((ViewGroup) view);
                    else session.widgets.pause();
                }
                if (session.mini != null && coverExited(view.getContext(), session.mini.display)) releaseMini((ViewGroup) view, true);
                unregisterInputRegion(session);
                session.columnFade.snap();
                if (cornerHandoff != null) cornerHandoff.detach((ViewGroup) view);
            }
        });
        return session;
    }

    private void restoreRoot(ViewGroup root, WindowSession session) throws Exception {
        if (session.blurFill != null) session.blurFill.stop();
        session.columnFade.reset();
        closeWidgets(root);
        releaseMini(root, true);
        if (cornerHandoff != null) cornerHandoff.detach(root);
        unregisterInputRegion(session);
        NativeDock dock = session.dock.get();
        if (dock != null) { dock.cameraStatus(null); dock.holdNativeTone(false); }
        if (dock != null) {
            dock.enabled = false;
            if (dock.getParent() == root) root.removeView(dock);
        }
        session.dock.clear();
        if (session.cameraView != null) { root.removeView(session.cameraView); session.cameraView = null; }
        View pager = (View) field(root, "mPagerView");
        if (pager != null) {
            pager.setVisibility(session.pagerVisibility);
            pager.setImportantForAccessibility(session.pagerAccessibility);
        }
        root.setPadding(session.padding[0], session.padding[1], session.padding[2], session.padding[3]);
        root.setBackground(session.background);
        rebuildNativeGeometry(root, session);
        reconcileHostVisibility(root, session, rotation(root));
        root.requestLayout();
        if (root.isAttachedToWindow()) root.getContext().getSystemService(WindowManager.class)
                .updateViewLayout(root, (WindowManager.LayoutParams) field(root, "mLayoutParams"));
    }

    private void rebuildNativeGeometry(ViewGroup root, WindowSession session) throws Exception {
        session.windowCommit.invalidate();
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) field(root, "mLayoutParams");
        session.rebuilding = true;
        try {
            NativeHostGeometry.rebuild(root, params, session.original);
            session.extended = false;
        } finally { session.rebuilding = false; }
    }

    private void configureNativeColumn(ViewGroup root) throws Exception {
        configureNativeColumn(root, rotation(root));
    }

    private boolean configureNativeColumn(ViewGroup root, int rotation) throws Exception {
        NativeDock dock = root.findViewWithTag(VIEW_TAG);
        WindowSession session = sessions.get(root);
        if (session == null || session.rebuilding || !session.control.active() || dock == null || !dock.enabled) return false;
        if (rotation < 0 || rotation > 3) return false;
        boolean rotationChanged = session.rotation != rotation;
        boolean black = currentConfig.background == CoverBackground.BLACK;
        boolean blurFill = currentConfig.background == CoverBackground.BLUR_FILL;
        boolean fill = black || blurFill;
        if (!currentConfig.recentsCrop && !blurFill && cornerHandoff != null) cornerHandoff.detach(root);
        if (rotationChanged) {
            if (cornerHandoff != null) cornerHandoff.detach(root); // Old app viewport/tone ownership cannot survive a rotation.
            session.columnFade.snap();
            session.rotation = rotation;
            info("Native rotation aligned actual=" + rotation + " cached=" + field(root, "mRotation"));
        }
        if (CoverRotation.landscape(rotation) && !currentConfig.landscapeCompat) {
            return restoreLandscapeNative(root, session, dock, rotationChanged);
        }
        reconcileHostVisibility(root, session, rotation);
        dock.setVisibility(View.VISIBLE);
        dock.applyRotation(rotation);
        if (!widgetsExpanded(session)) hideStockPager((View) field(root, "mPagerView"));
        if (cornerHandoff != null && cornerHandoff.holdsTone(root)) dock.holdNativeTone(true);
        Object helper = session.backgroundHelper.get();
        // If the native scene source is gone, keep the underlying full-screen page unobstructed.
        boolean desk = helper != null && (Boolean) field(helper, "isDesk");
        boolean fullScreen = helper != null && (Boolean) field(helper, "isFullScreen");
        dock.fullscreenScene(fullScreen && !desk);
        boolean nativeAppReady = helper != null && !desk && !fullScreen;
        if (blurFill && session.blurFill == null) session.blurFill = new BlurFillHost(root,
                () -> { main.removeCallbacks(refreshScenes); main.post(refreshScenes); });
        boolean unlocked = !session.blurUserBlocked && root.getDisplay() != null
                && !coverExited(root.getContext(), root.getDisplay().getDisplayId());
        boolean liveSource = BlurFillGeometry.eligible(currentConfig.enabled, blurFill, unlocked,
                nativeAppReady, root.isShown(), dock.coverFillOccluded(), cornerHandoff != null && cornerHandoff.inFlight(root));
        if (cornerHandoff != null && cornerHandoff.restoring(root)) { desk = false; fullScreen = false; }
        boolean preserveNative = !fill || helper == null || desk || fullScreen;
        if (fill && currentConfig.recentsCrop && cornerHandoff != null && cornerHandoff.retainsColumn(root)) {
            // Both fills keep the fixed control column; only the app viewport participates in recents.
            preserveNative = false;
        }
        boolean blurArea = blurFill && !preserveNative && !dock.coverFillOccluded();
        boolean changed = rotationChanged;
        Display display = root.getDisplay();
        // Display.getCutout is already in logical display coordinates. Never rotate it a second time.
        android.graphics.Rect camera = null;
        if (display != null
                && display.getRotation() == rotation && display.getCutout() != null) {
            for (android.graphics.Rect logical : display.getCutout().getBoundingRects()) {
                // A horizontal painter uses natural coordinates internally. Do not transform the already-logical cutout twice.
                android.graphics.Rect rect = CoverRotation.landscape(rotation) ? CoverRotation.natural(logical, rotation) : logical;
                if (rect.isEmpty() || rect.width() > CoverGeometry.COLUMN + DockSpec.SEAM_BLEED
                        || rect.height() <= DockSpec.HEIGHT || rect.bottom > CoverGeometry.HEIGHT) continue;
                if (((rotation == 0 || CoverRotation.landscape(rotation)) && rect.right == CoverGeometry.WIDTH && rect.top >= DockSpec.HEIGHT)
                        || (rotation == 2 && rect.left == 0 && rect.bottom <= CoverGeometry.HEIGHT - DockSpec.HEIGHT)) {
                    camera = rect; break;
                }
            }
        }
        session.columnFade.camera(camera, CameraOcclusion.hasOpticalProfile(Build.DEVICE));
        boolean animateColumn = currentConfig.animations && android.animation.ValueAnimator.areAnimatorsEnabled()
                && !rotationChanged && helper != null && root.isAttachedToWindow() && root.isShown()
                && display != null && !coverExited(root.getContext(), display.getDisplayId());
        // An explicit OFF must also cancel any in-flight black contour, not leave its exit tail.
        session.columnFade.update(!preserveNative, animateColumn && fill, currentConfig.animationMs);
        // Entry needs the full extent from its zero-ink first frame; exit retains it until swallowed.
        // Native scene/tone/input and gesture
        // completion already use the real destination; no app frame or delayed cleanup is awaited here.
        boolean columnVisible = session.columnFade.visible();
        // Mirror readiness never owns scene/window lifetime. Keep an existing source through gestures and the exit tail.
        boolean mirrorAllowed = currentConfig.enabled && blurFill && unlocked && columnVisible
                && root.isShown() && !dock.coverFillOccluded();
        int firstRadius = session.geometry.appTop(rotation >= 2, currentConfig.systemCorners, currentConfig.symmetricCorners);
        int lastRadius = session.geometry.appBottom(rotation >= 2, currentConfig.systemCorners, currentConfig.symmetricCorners);
        if (session.blurFill != null) session.blurFill.sync(mirrorAllowed, liveSource, rotation, firstRadius, lastRadius);
        session.columnFade.liveFill(blurFill); // Mode, not readiness: missing frames must not reveal the old black painter.
        if (session.blurFill != null) session.blurFill.alpha(session.columnFade.opacity());
        boolean cameraExtent = cameraStatusEligible(root);
        if (!columnVisible && !cameraExtent && session.extended) { rebuildNativeGeometry(root, session); changed = true; }
        // Xiaomi updateWindowSize restores resource padding on every call, not just inflate.
        if (root.getPaddingLeft() != 0 || root.getPaddingTop() != 0 || root.getPaddingRight() != 0 || root.getPaddingBottom() != 0)
            root.setPadding(0, 0, 0, 0);
        registerInputRegion(root, session);
        WindowManager.LayoutParams nativeParams = (WindowManager.LayoutParams) field(root, "mLayoutParams");
        if (widgetsExpanded(session) && session.widgetBackground != currentConfig.background) {
            session.widgetBackground = currentConfig.background; session.widgets.materialChanged();
        }
        if (widgetsExpanded(session) && (!dock.canOpenWidgets() || session.widgetRotation != rotation || widgetDisplayExited(root, session))) {
            session.widgets.close(); session.widgets = null; dock.widgetsExpanded(false);
            widgetStyle.refresh(session.backgroundHelper.get());
        }
        if (session.mini != null && (session.mini.rotation != rotation || coverExited(root.getContext(), session.mini.display))) {
            exitMini(root.getContext(), session.mini.task); session.mini = null; dock.miniSlotted(false);
            info("Mini window slot released by rotation or cover exit");
        }
        // This is the existing, trusted Xiaomi watch window, NOT a new application overlay.
        // The entry/exit contour uses this same window extent; only a finished exit restores native geometry.
        int extension = Math.max(session.geometry.appExtension(fill && currentConfig.systemCorners, currentConfig.symmetricCorners),
                cameraExtent ? Math.max(0, NativeCameraGeometry.windowInset(currentConfig) - DockSpec.SEAM_BLEED) : 0);
        if (columnVisible || cameraExtent) {
            changed |= configureParams(nativeParams, rotation, extension);
            changed |= configureRotationParams(nativeParams, extension, currentConfig.landscapeCompat);
            session.extended = true;
        } else {
            // OEM resource height is 657px here, while our shared control canvas is 664px.
            // Fit the controls without extending any black background into the full-screen page.
            changed |= fitNativeSlot(nativeParams, rotation, session.original.width);
            WindowManager.LayoutParams[] values = independentRotationParams(nativeParams);
            if (values != null) for (int i = 0; i < Math.min(4, values.length); i++)
                if (values[i] != null && (currentConfig.landscapeCompat || i == 0 || i == 2))
                    changed |= fitNativeSlot(values[i], i, session.original.width);
        }
        // Expanding a SystemUI component must not replace the independent black column or its app-edge corners.
        Drawable background = session.background;
        if (columnVisible || cameraExtent) {
            if (session.column == null || session.columnRotation != rotation || session.columnCorners != currentConfig.systemCorners
                    || session.columnSymmetric != currentConfig.symmetricCorners) {
                session.column = new DockAppearance().column(rotation == 2, session.geometry, currentConfig.systemCorners, currentConfig.symmetricCorners);
                session.columnRotation = rotation; session.columnCorners = currentConfig.systemCorners;
                session.columnSymmetric = currentConfig.symmetricCorners;
            }
            session.columnFade.bind(session.column, session.background,
                    session.original.width > 0 ? session.original.width : DockSpec.WIDTH + DockSpec.SEAM_BLEED,
                    Math.max(DockSpec.HEIGHT, session.original.height), rotation);
            background = session.columnFade;
        }
        // In blur mode this drawable is clock-only and paints nothing; the compositor owns the whole material.
        // The native mini window is an app task below this overlay: only its slot is left transparent.
        if (session.mini != null) background = session.mini.background(background, dock);
        if (root.getBackground() != background) root.setBackground(background);
        configureCameraStatus(root, session, rotation, cameraExtent);
        int privateFlags = overlayPrivateFlags.getInt(nativeParams);
        trustNativeOverlay.invoke(nativeParams);
        changed |= privateFlags != overlayPrivateFlags.getInt(nativeParams);
        Field inputRotationsField = WindowManager.LayoutParams.class.getDeclaredField("paramsForRotation");
        inputRotationsField.setAccessible(true);
        WindowManager.LayoutParams[] inputRotations = (WindowManager.LayoutParams[]) inputRotationsField.get(nativeParams);
        if (inputRotations != null) for (WindowManager.LayoutParams value : inputRotations) {
            if (value == null) continue;
            trustNativeOverlay.invoke(value);
        }
        String scene = "desk=" + desk + " fullScreen=" + fullScreen + " nativeBackground=" + preserveNative
                + " background=" + currentConfig.background.key + " recentsCrop=" + currentConfig.recentsCrop + " rotation=" + rotation;
        if (!scene.equals(session.scene)) {
            session.scene = scene;
            info("Cover scene " + scene + " window=" + nativeParams.width + "x" + nativeParams.height);
        }
        FrameLayout.LayoutParams child = (FrameLayout.LayoutParams) dock.getLayoutParams();
        int gravity = CoverRotation.gravity(rotation);
        boolean horizontal = CoverRotation.landscape(rotation);
        int dockWidth = horizontal ? DockSpec.HEIGHT : DockSpec.WIDTH, dockHeight = horizontal ? DockSpec.WIDTH : DockSpec.HEIGHT;
        if (child.width != dockWidth || child.height != dockHeight || child.gravity != gravity) {
            child.width = dockWidth; child.height = dockHeight; child.gravity = gravity; dock.setLayoutParams(child);
        }
        dock.applyRotation(rotation);
        dock.applyConfig(currentConfig);
        // Each group registers its own compositor region; OEM pager-material flags do not gate our child views.
        // A dark status-bar header does not describe a bright native side/letterbox strip.
        // Use the native strip's tone for ordinary apps; true desktop/fullscreen keeps the clock chain.
        session.toneReady = true; session.toneNative = blurArea || preserveNative; session.toneOrdinary = nativeAppReady; session.toneDesktop = desk;
        applyDockTone(session, dock);
        // Resolve the destination while still held; release once, with no intermediate outgoing palette.
        dock.holdNativeTone(cornerHandoff != null && cornerHandoff.holdsTone(root));
        boolean submitted = session.windowCommit.submit(nativeParams, root.isAttachedToWindow(),
                () -> root.getContext().getSystemService(WindowManager.class).updateViewLayout(root, nativeParams));
        if (submitted) info("Native window geometry submitted " + nativeParams.width + "x" + nativeParams.height
                + " gravity=" + nativeParams.gravity + " rotation=" + rotation);
        if (submitted && session.blurFill != null) session.blurFill.geometrySubmitted();
        changed |= submitted;
        if (cornerHandoff != null) cornerHandoff.sourceFrame(root, nativeAppReady);
        if (cornerHandoff != null && !cornerHandoff.inFlight(root)) {
            // A retained transition column is presentation, not proof that the next foreground app is ordinary.
            cornerHandoff.prepare(root, rotation, session.geometry, currentConfig.recentsCrop && currentConfig.systemCorners,
                    currentConfig.symmetricCorners, nativeAppReady && (currentConfig.recentsCrop || blurFill));
        }
        return changed;
    }

    private void applyDockTone(WindowSession session, NativeDock dock) throws ReflectiveOperationException {
        Object helper = session.backgroundHelper.get();
        Object state = helper == null ? null : field(helper, "mBackgroundState");
        boolean dark = state == null || (Integer) field(state, "mamlTextColor") == 0;
        if (session.toneOrdinary && helper != null && nativeAppColor != null)
            dark = (Boolean) nativeColorDark.invoke(null, nativeAppColor.getInt(helper));
        dock.applyNativeTone(session.toneNative, !session.toneNative || dark, session.toneNative,
                session.toneDesktop, !session.toneOrdinary);
    }

    private void configureCameraStatus(ViewGroup root, WindowSession session, int rotation, boolean cameraExtent) {
        NativeDock dock = session.dock.get();
        if (cameraExtent) {
            if (session.cameraView == null) {
                session.cameraView = new CameraStatusView(root.getContext());
                session.cameraView.rotationChanges(() -> {
                    main.removeCallbacks(refreshScenes); main.post(refreshScenes);
                });
            }
            if (session.cameraView.getParent() == null) {
                root.addView(session.cameraView, new FrameLayout.LayoutParams(DockSpec.WIDTH, 1392, CoverRotation.gravity(rotation)));
            }
            session.cameraView.configure(rotation, currentConfig);
            dock.cameraStatus(session.cameraView);
        } else if (session.cameraView != null) {
            dock.cameraStatus(null);
            root.removeView(session.cameraView); session.cameraView = null;
        }
    }


    private void registerInputRegion(ViewGroup root, WindowSession session) throws Exception {
        if (!root.isAttachedToWindow() || !session.control.active() || session.dock.get() == null) return;
        ViewTreeObserver observer = root.getViewTreeObserver();
        if (session.observer.get() == observer) return;
        unregisterInputRegion(session);
        Class<?> listener = Class.forName("android.view.ViewTreeObserver$OnComputeInternalInsetsListener");
        if (session.inputProxy == null) session.inputProxy = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener}, (object, method, args) -> {
            if (method.getName().equals("onComputeInternalInsets")) {
                NativeDock dock = session.dock.get();
                Object info = args[0];
                boolean active = session.control.active() && dock != null && dock.enabled && dock.getVisibility() == View.VISIBLE;
                info.getClass().getMethod("setTouchableInsets", int.class).invoke(info, active ? 3 : 0);
                if (active) {
                    android.graphics.Region region = (android.graphics.Region) info.getClass().getField("touchableRegion").get(info);
                    dock.fillTouchableRegion(region);
                    if (session.widgets != null) session.widgets.addTouchableRegion(region);
                }
                return null;
            }
            if (method.getName().equals("hashCode")) return System.identityHashCode(object);
            if (method.getName().equals("equals")) return object == args[0];
            if (method.getName().equals("toString")) return "FlipDuoNativeInputRegion";
            return null;
        });
        observer.getClass().getMethod("addOnComputeInternalInsetsListener", listener).invoke(observer, session.inputProxy);
        session.observer = new WeakReference<>(observer);
    }

    private void unregisterInputRegion(WindowSession session) {
        ViewTreeObserver observer = session.observer.get();
        session.observer.clear();
        if (observer == null || !observer.isAlive() || session.inputProxy == null) return;
        try {
            Class<?> listener = Class.forName("android.view.ViewTreeObserver$OnComputeInternalInsetsListener");
            observer.getClass().getMethod("removeOnComputeInternalInsetsListener", listener).invoke(observer, session.inputProxy);
        } catch (ReflectiveOperationException error) { log(Log.WARN, TAG, "Cannot remove old native input-region observer", error); }
    }

    static boolean configureParams(WindowManager.LayoutParams params, int rotation, int extension) {
        android.graphics.Rect area = CoverRotation.column(rotation, extension);
        int width = area.width(), height = area.height();
        int gravity = CoverRotation.gravity(rotation);
        int flags = params.flags | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        boolean changed = params.width != width || params.height != height || params.x != 0 || params.y != 0
                || params.gravity != gravity || params.flags != flags
                || params.layoutInDisplayCutoutMode != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                || params.getFitInsetsTypes() != 0;
        // Only the static background bleeds over the measured rounding seam. Controls and input stay 398px wide.
        params.width = width; params.height = height; params.x = 0; params.y = 0;
        params.gravity = gravity; params.flags = flags;
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setFitInsetsTypes(0);
        return changed;
    }

    private static boolean fitNativeSlot(WindowManager.LayoutParams params, int rotation, int nativeWidth) {
        if (nativeWidth <= 0) return false;
        int width = CoverRotation.landscape(rotation) ? DockSpec.HEIGHT : nativeWidth;
        int height = CoverRotation.landscape(rotation) ? nativeWidth : DockSpec.HEIGHT;
        int gravity = CoverRotation.gravity(rotation);
        boolean changed = params.width != width || params.height != height || params.gravity != gravity;
        params.width = width; params.height = height; params.gravity = gravity;
        return changed;
    }

    static boolean configureRotationParams(WindowManager.LayoutParams params, int extension, boolean landscape) throws ReflectiveOperationException {
        WindowManager.LayoutParams[] values = independentRotationParams(params);
        if (values == null) return false;
        boolean changed = false;
        for (int i = 0; i < Math.min(4, values.length); i++) {
            WindowManager.LayoutParams value = values[i];
            if (value == null) continue;
            if (landscape || i == 0 || i == 2) changed |= configureParams(value, i, extension);
        }
        return changed;
    }
    private static WindowManager.LayoutParams[] independentRotationParams(WindowManager.LayoutParams params) throws ReflectiveOperationException {
        Field rotations = WindowManager.LayoutParams.class.getDeclaredField("paramsForRotation"); rotations.setAccessible(true);
        WindowManager.LayoutParams[] values = (WindowManager.LayoutParams[]) rotations.get(params);
        if (values == null) return null;
        Set<WindowManager.LayoutParams> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < Math.min(4, values.length); i++) {
            WindowManager.LayoutParams value = values[i];
            if (value == null) continue;
            // OEM commonly aliases 0/1 and 2/3. A quarter turn needs independent dimensions, not last-writer-wins.
            if (!seen.add(value) || value == params) {
                WindowManager.LayoutParams copy = new WindowManager.LayoutParams(); copy.copyFrom(value); rotations.set(copy, null);
                values[i] = copy;
            }
        }
        return values;
    }

    private boolean restoreLandscapeNative(ViewGroup root, WindowSession session, NativeDock dock, boolean rotationChanged) throws Exception {
        if (session.blurFill != null) session.blurFill.stop();
        session.columnFade.reset();
        if (cornerHandoff != null) cornerHandoff.detach(root);
        boolean rebuild = session.extended || rotationChanged || dock.getVisibility() != View.GONE;
        if (widgetsExpanded(session)) { session.widgets.close(); session.widgets = null; dock.widgetsExpanded(false); }
        if (session.mini != null) releaseMini(root, true);
        dock.suspendEventHost(); dock.setVisibility(View.GONE); dock.cameraStatus(null); dock.holdNativeTone(false);
        if (session.cameraView != null) { root.removeView(session.cameraView); session.cameraView = null; }
        if (rebuild) rebuildNativeGeometry(root, session);
        root.setPadding(session.padding[0], session.padding[1], session.padding[2], session.padding[3]);
        root.setBackground(session.background);
        View pager = (View) field(root, "mPagerView");
        if (pager != null) { pager.setVisibility(session.pagerVisibility); pager.setImportantForAccessibility(session.pagerAccessibility); }
        reconcileHostVisibility(root, session, session.rotation);
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) field(root, "mLayoutParams");
        return session.windowCommit.submit(params, root.isAttachedToWindow(),
                () -> root.getContext().getSystemService(WindowManager.class).updateViewLayout(root, params)) || rotationChanged;
    }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }

    private static final class WindowSession {
        CoverGeometry geometry;
        final NativeHostVisibility hostVisibility = new NativeHostVisibility();
        final ControlState control = new ControlState();
        final NativeWindowCommit windowCommit = new NativeWindowCommit();
        final WindowManager.LayoutParams original = new WindowManager.LayoutParams();
        int[] padding;
        Drawable background;
        Drawable column;
        boolean toneReady, toneNative, toneOrdinary, toneDesktop;
        BlurFillHost blurFill;
        boolean blurUserBlocked;
        ColumnTransition columnFade;
        CameraStatusView cameraView;
        StockWidgetPanel widgets;
        MiniHole mini, miniRequest;
        int widgetRotation, widgetDisplayId;
        CoverBackground widgetBackground;
        int columnRotation;
        int rotation = -1;
        boolean columnCorners, columnSymmetric, extended, rebuilding;
        String scene;
        WeakReference<Object> backgroundHelper = new WeakReference<>(null);
        WeakReference<Object> windowOwner = new WeakReference<>(null);
        int pagerVisibility, pagerAccessibility;
        WeakReference<NativeDock> dock = new WeakReference<>(null);
        WeakReference<ViewTreeObserver> observer = new WeakReference<>(null);
        Object inputProxy;
    }
}
