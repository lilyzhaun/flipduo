// SPDX-License-Identifier: AGPL-3.0-only
// Window/configuration strategy adapted from parallelcc/MixFlipMod SystemHook.kt.
// Author: parallelcc. https://github.com/parallelcc/MixFlipMod
// True-fullscreen property and Insets port: commit 6b02e8cec6497efd2a07b9ba1307fb9c2f979867.
package com.flipduo.module;

import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.view.DisplayCutout;
import android.view.WindowInsets;
import android.view.WindowManager;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Per-app cover modes: preserve the original cutout, or opt into MixFlipMod true fullscreen. */
final class AppCutoutHook {
    private static final String FULL_SCREEN = "miui.supportFlipFullScreen";
    private static final String WATCH_OVERLAY = "miui.supportFlipWatchOverlayGroupView";
    private volatile CutoutAppConfig.Snapshot policy = CutoutAppConfig.Snapshot.EMPTY;
    private Method removeSourceAt;
    private DisplayCutout noCutout;
    private RustGalleryLayout galleryLayout;
    private final Set<String> reportedPackages = ConcurrentHashMap.newKeySet();
    private final Set<String> reportedConfigurations = ConcurrentHashMap.newKeySet();
    private SharedPreferences preferences;
    private SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;
    private final NativeModule module;
    private final Method getBounds, setAppBounds, getWindowingMode, getDisplayContent, getConfiguration, getResolved;
    private final Field windowConfiguration;

    private AppCutoutHook(NativeModule module, ClassLoader loader) throws ReflectiveOperationException {
        this.module = module;
        windowConfiguration = field(Configuration.class, "windowConfiguration");
        Class<?> wc = loader.loadClass("android.app.WindowConfiguration");
        getBounds = method(wc, "getBounds");
        setAppBounds = method(wc, "setAppBounds", Rect.class);
        getWindowingMode = method(wc, "getWindowingMode");
        getDisplayContent = method(loader.loadClass("com.android.server.wm.WindowContainer"), "getDisplayContent");
        Class<?> container = loader.loadClass("com.android.server.wm.ConfigurationContainer");
        getConfiguration = method(container, "getConfiguration");
        getResolved = method(container, "getResolvedOverrideConfiguration");
    }

    static AppCutoutHook install(NativeModule module, ClassLoader loader) throws ReflectiveOperationException {
        AppCutoutHook policy = new AppCutoutHook(module, loader);
        policy.bind(loader);
        return policy;
    }

    private void bind(ClassLoader loader) throws ReflectiveOperationException {
        // Resolve the device's complete API surface before registering any hooks.
        Class<?> ipm = loader.loadClass("com.android.server.pm.IPackageManagerBase");
        Method activityInfo = method(ipm, "getActivityInfo", ComponentName.class, long.class, int.class);
        Method applicationInfo = method(ipm, "getApplicationInfo", String.class, long.class, int.class);
        Method property = method(ipm, "getPropertyAsUser", String.class, String.class, String.class, int.class);
        Constructor<?> propertyConstructor = loader.loadClass("android.content.pm.PackageManager$Property")
                .getDeclaredConstructor(String.class, boolean.class, String.class, String.class);
        propertyConstructor.setAccessible(true);

        Class<?> activity = loader.loadClass("com.android.server.wm.ActivityRecord");
        try { galleryLayout = RustGalleryLayout.resolve(activity); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError error) { NativeModule.warning("Rust Gallery layout option unavailable", error); }
        Class<?> controller = loader.loadClass("com.android.server.wm.BoundsCompatController");
        Field owner = field(controller, "mOwner"), packageName = field(activity, "packageName");
        Method fixedAspect = method(controller, "canUseFixedAspectRatio", Configuration.class);
        Method cutoutMode = method(loader.loadClass("android.view.WindowLayoutStubImpl"),
                "getLayoutInDisplayCutoutMode", WindowManager.LayoutParams.class);
        Method activityResolve = method(activity, "resolveOverrideConfiguration", Configuration.class);
        Method activityForToken = method(activity, "forTokenLocked", IBinder.class);
        Class<?> process = loader.loadClass("com.android.server.wm.WindowProcessController");
        Field packages = field(process, "mPkgList");
        Field configActivity = field(process, "mConfigActivityRecord");
        Method processResolve = method(process, "resolveOverrideConfiguration", Configuration.class);

        Class<?> window = loader.loadClass("com.android.server.wm.WindowState");
        Class<?> state = loader.loadClass("android.view.InsetsState"), source = loader.loadClass("android.view.InsetsSource");
        Method fillInsets = method(window, "fillInsetsState", state, boolean.class);
        Method owningPackage = method(window, "getOwningPackage"), compatInsets = method(window, "getCompatInsetsState");
        Field attrs = field(window, "mAttrs"), scale = field(window, "mGlobalScale");
        Method cutout = method(state, "getDisplayCutout"), setCutout = method(state, "setDisplayCutout", DisplayCutout.class);
        Method sourceSize = method(state, "sourceSize"), sourceAt = method(state, "sourceAt", int.class);
        Method sourceType = method(source, "getType"), addSource = method(state, "addSource", source);
        Method displayFrame = method(state, "getDisplayFrame");
        Constructor<?> copySource = source.getDeclaredConstructor(source);
        copySource.setAccessible(true);
        // Optional capability: failure must not disable the existing cutout-preserving mode.
        try {
            Method remove = method(state, "removeSourceAt", int.class);
            DisplayCutout empty = (DisplayCutout) field(DisplayCutout.class, "NO_CUTOUT").get(null);
            removeSourceAt = remove; noCutout = empty;
        } catch (ReflectiveOperationException error) {
            NativeModule.warning("True fullscreen cutout removal unavailable; original mode retained", error);
        }

        hook(activityInfo, chain -> {
            Object result = chain.proceed();
            // Cover capability is a declaration, not the display state at the moment PackageManager was queried.
            if (!(result instanceof ActivityInfo info) || !policy.selected(info.packageName)) return result;
            ActivityInfo copy = new ActivityInfo(info);
            copy.metaData = fullscreenMetadata(info.metaData);
            return copy;
        });
        hook(applicationInfo, chain -> {
            Object result = chain.proceed();
            if (!(result instanceof ApplicationInfo info) || !policy.selected(info.packageName)) return result;
            ApplicationInfo copy = new ApplicationInfo(info);
            copy.metaData = fullscreenMetadata(info.metaData);
            return copy;
        });
        hook(fixedAspect, chain -> {
            Object result = chain.proceed();
            CutoutAppConfig.Snapshot snapshot = policy;
            if (snapshot.apps().isEmpty()) return result;
            Object record = owner.get(chain.getThisObject());
            return record != null && snapshot.selected(packageName.get(record)) && cover(displayConfiguration(record)) ? false : result;
        });
        hook(property, chain -> {
            CutoutAppConfig.Snapshot snapshot = policy;
            if (WATCH_OVERLAY.equals(chain.getArg(0)) && snapshot.selected(chain.getArg(1))) {
                return propertyConstructor.newInstance(WATCH_OVERLAY, !snapshot.trueFullscreen(chain.getArg(1), noCutout != null), chain.getArg(1), chain.getArg(2));
            }
            return chain.proceed();
        });
        hook(cutoutMode, chain -> {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) chain.getArg(0);
            if (lp != null && policy.selected(lp.packageName) && lp.token != null) {
                Object record = activityForToken.invoke(null, lp.token);
                if (record != null && cover(displayConfiguration(record))) return WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }
            return chain.proceed();
        });
        hook(activityResolve, chain -> {
            Object result = chain.proceed();
            Object record = chain.getThisObject();
            String pkg = (String) packageName.get(record);
            CutoutAppConfig.Snapshot snapshot = policy;
            boolean forced = snapshot.selected(pkg);
            Integer systemPercent = pkg == null ? null : snapshot.systemScales().get(pkg);
            boolean gallery = snapshot.rustGalleryLayout() && galleryLayout != null && RustGalleryLayout.PACKAGE.equals(pkg);
            if (forced || systemPercent != null || gallery) {
                Configuration base = displayConfiguration(record);
                Configuration parent = (Configuration) chain.getArg(0);
                Configuration resolved = (Configuration) getResolved.invoke(record);
                if (cover(base) && fullscreen(resolved, parent)) {
                    if (forced) fullAppBounds(resolved, pkg, base, snapshot.scaleFor(pkg, noCutout != null));
                    else if (systemPercent != null) scaleSystemContent(resolved, parent, systemPercent);
                    if (gallery) {
                        Rect max = galleryLayout.apply(record, resolved, parent, base);
                        if (max != null && reportedConfigurations.add("gallery:regularLayout"))
                            NativeModule.info("Rust Gallery MainActivity override prepared: screenType=0 maxBounds=" + max);
                    }
                }
            }
            // On the inner display the freshly resolved native configuration is left untouched. No cached cover DPI
            // survives here: ConfigurationContainer rebuilt this override before comparison and IPC dispatch.
            return result;
        });
        hook(processResolve, chain -> {
            Object result = chain.proceed();
            List<?> processPackages = (List<?>) packages.get(chain.getThisObject());
            Object record = configActivity.get(chain.getThisObject());
            CutoutAppConfig.Snapshot snapshot = policy;
            // Follow-system uses the normal Activity -> merged override -> process listener. Do not scale it twice.
            if (record != null && !processPackages.isEmpty() && processPackages.stream().allMatch(snapshot::selected)) {
                String pkg = (String) processPackages.get(0);
                boolean supportsTrueFullscreen = noCutout != null;
                int percent = snapshot.scaleFor(pkg, supportsTrueFullscreen);
                if (processPackages.stream().allMatch(p -> snapshot.scaleFor(p, supportsTrueFullscreen) == percent)) {
                    Configuration base = displayConfiguration(record);
                    Configuration activityConfig = (Configuration) getConfiguration.invoke(record);
                    if (cover(base) && fullscreen(activityConfig, activityConfig))
                        fullAppBounds((Configuration) getResolved.invoke(chain.getThisObject()), pkg, base, percent);
                }
            }
            return result;
        });
        hook(fillInsets, chain -> {
            Object result = chain.proceed();
            CutoutAppConfig.Snapshot snapshot = policy;
            if (snapshot.apps().isEmpty()) return result;
            String pkg = (String) owningPackage.invoke(chain.getThisObject());
            // Use the target display, not the density/compat-scaled frame being sent to this client.
            if (!snapshot.selected(pkg) || !cover(displayConfiguration(chain.getThisObject()))) return result;
            Object w = chain.getThisObject(), out = chain.getArg(0);
            Configuration config = (Configuration) getConfiguration.invoke(w);
            if (!fullscreen(config, config)) return result;
            if (snapshot.trueFullscreen(pkg, noCutout != null)) {
                // Upstream: only this IPC output loses camera avoidance. Never mutate the shared source.
                setCutout.invoke(out, noCutout);
                for (int i = (int) sourceSize.invoke(out) - 1; i >= 0; i--) {
                    Object s = sourceAt.invoke(out, i);
                    if ((int) sourceType.invoke(s) == WindowInsets.Type.displayCutout()) removeSourceAt.invoke(out, i);
                }
                if (reportedPackages.add(pkg)) NativeModule.info("AppCutout true-fullscreen package=" + pkg
                        + " frame=" + displayFrame.invoke(out) + " cameraInsets=removed");
                return result;
            }
            // The same per-window, rotation/scale-adjusted source used by stock fillInsetsState.
            // Run after lower-priority hooks; preserve the cutout even if one stripped the IPC output.
            Object original = compatInsets.invoke(w);
            DisplayCutout geometry = (DisplayCutout) cutout.invoke(original);
            setCutout.invoke(out, geometry);
            int count = (int) sourceSize.invoke(original), cameraSources = 0;
            for (int i = 0; i < count; i++) {
                Object s = sourceAt.invoke(original, i);
                if ((int) sourceType.invoke(s) == WindowInsets.Type.displayCutout()) {
                    addSource.invoke(out, copySource.newInstance(s));
                    cameraSources++;
                }
            }
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) attrs.get(w);
            if (lp.type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION && reportedPackages.add(pkg)) {
                NativeModule.info("AppCutout delivered package=" + pkg + " frame=" + displayFrame.invoke(out) + " scale=" + scale.get(w)
                        + " cutout=" + geometry + " cutoutSources=" + cameraSources);
            }
            return result;
        });
        preferences = module.getRemotePreferences(DockConfig.GROUP);
        preferenceListener = (prefs, key) -> {
            if (key == null || CutoutAppConfig.KEY.equals(key) || CutoutAppConfig.LEGACY_KEY.equals(key)
                    || CutoutAppConfig.SCALES_KEY.equals(key) || CutoutAppConfig.TRUE_FULLSCREEN_KEY.equals(key)
                    || CutoutAppConfig.SYSTEM_SCALES_KEY.equals(key) || RustGalleryLayout.KEY.equals(key) || "enabled".equals(key)) readPreference();
        };
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
        readPreference();
        NativeModule.info("AppCutout system hooks installed; Rust Gallery layout policy " + RustGalleryLayout.POLICY_VERSION
                + " available=" + (galleryLayout != null));
    }

    private void readPreference() {
        CutoutAppConfig.Snapshot snapshot = CutoutAppConfig.snapshot(preferences);
        policy = snapshot;
        reportedPackages.clear();
        reportedConfigurations.clear();
        NativeModule.info("AppCutout selected=" + snapshot.apps() + " trueFullscreen=" + snapshot.trueFullscreenApps()
                + " scales=" + snapshot.scales() + "; reopen changed apps to refresh their windows");
    }

    private Configuration displayConfiguration(Object container) throws ReflectiveOperationException {
        Object display = getDisplayContent.invoke(container);
        return display == null ? null : (Configuration) getConfiguration.invoke(display);
    }
    private boolean cover(Configuration config) throws ReflectiveOperationException {
        if (config == null) return false;
        Rect bounds = (Rect) getBounds.invoke(windowConfiguration.get(config));
        return CutoutAppConfig.isCoverDisplay(bounds.width(), bounds.height());
    }
    private boolean fullscreen(Configuration config, Configuration parent) throws ReflectiveOperationException {
        int mode = (int) getWindowingMode.invoke(windowConfiguration.get(config));
        if (mode == 0) mode = (int) getWindowingMode.invoke(windowConfiguration.get(parent));
        return mode == 0 || mode == 1;
    }

    private static Bundle fullscreenMetadata(Bundle original) {
        Bundle copy = original == null ? new Bundle() : new Bundle(original);
        copy.putInt(FULL_SCREEN, 0);
        return copy;
    }

    /** The OEM has just resolved this activity. Inherit undefined fields from its task, not the whole display. */
    private static void scaleSystemContent(Configuration resolved, Configuration parent, int percent) {
        int dpi = resolved.densityDpi > 0 ? resolved.densityDpi : parent.densityDpi;
        if (dpi <= 0) return;
        int scaledDpi = CutoutAppConfig.densityFor(dpi, percent);
        if (scaledDpi == dpi) return;
        resolved.screenWidthDp = scaledDp(resolved.screenWidthDp, parent.screenWidthDp, dpi, scaledDpi);
        resolved.screenHeightDp = scaledDp(resolved.screenHeightDp, parent.screenHeightDp, dpi, scaledDpi);
        resolved.smallestScreenWidthDp = scaledDp(resolved.smallestScreenWidthDp, parent.smallestScreenWidthDp, dpi, scaledDpi);
        resolved.densityDpi = scaledDpi;
    }

    private static int scaledDp(int overrideDp, int inheritedDp, int dpi, int scaledDpi) {
        int dp = overrideDp > 0 ? overrideDp : inheritedDp;
        return dp > 0 ? Math.max(1, Math.round(dp * (float) dpi / scaledDpi)) : overrideDp;
    }

    private void fullAppBounds(Configuration config, String pkg, Configuration base, int percent) throws ReflectiveOperationException {
        Object wc = windowConfiguration.get(config);
        Rect bounds = (Rect) getBounds.invoke(wc);
        if (bounds.isEmpty()) bounds = (Rect) getBounds.invoke(windowConfiguration.get(base));
        if (!bounds.isEmpty()) setAppBounds.invoke(wc, new Rect(bounds));
        // Always derive from the target display, never from a previously scaled activity/process override.
        int originalDpi = base.densityDpi;
        if (originalDpi <= 0) return;
        config.densityDpi = CutoutAppConfig.densityFor(originalDpi, percent);
        if (!bounds.isEmpty()) {
            config.screenWidthDp = CutoutAppConfig.dpFor(bounds.width(), config.densityDpi);
            config.screenHeightDp = CutoutAppConfig.dpFor(bounds.height(), config.densityDpi);
            config.smallestScreenWidthDp = Math.min(config.screenWidthDp, config.screenHeightDp);
        }
        if (reportedConfigurations.add(pkg)) NativeModule.info("AppCutout density package=" + pkg + " scale=" + percent
                + "% dpi=" + originalDpi + "->" + config.densityDpi + " dp=" + config.screenWidthDp + "x" + config.screenHeightDp);
    }

    private void hook(Executable executable, XposedInterface.Hooker hooker) {
        module.hook(executable).setPriority(10000).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(hooker);
    }

    private static Method method(Class<?> type, String name, Class<?>... params) throws ReflectiveOperationException {
        Method method = type.getDeclaredMethod(name, params);
        method.setAccessible(true);
        return method;
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
