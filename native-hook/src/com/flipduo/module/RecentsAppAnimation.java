package com.flipduo.module;

import android.app.ActivityManager;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;

/** Animates only the ordinary app viewport. The existing control window stays fixed; no task-side surface is created. */
final class RecentsAppAnimation {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable sceneChanged;
    private final Field runningInfo, tracker, targetTask, targetBounds, clipProgress, windowCrop;
    private final Field paramSurface, alpha, matrix, layer, shown, homeSurface, splitSurface;
    private final Field radiusTL, radiusTR, radiusBR, radiusBL;
    private final Method reboundProgress, runningIndex;
    private final Constructor<?> surfaceParams;
    private volatile Source prepared, source;
    private volatile boolean restoring;
    private boolean returning, framePending, frameReady;
    private Object gesture;
    private int taskId, generation;

    RecentsAppAnimation(NativeModule module, ClassLoader loader, Runnable sceneChanged) throws ReflectiveOperationException {
        this.sceneChanged = sceneChanged;
        Class<?> mode = loader.loadClass("com.miui.fliphome.gesture.GestureModeApp");
        Class<?> base = loader.loadClass("com.miui.fliphome.gesture.GestureMode");
        Class<?> helper = loader.loadClass("com.miui.fliphome.gesture.util.ClipAnimationHelper");
        Class<?> target = loader.loadClass("com.android.systemui.shared.recents.system.RemoteAnimationTargetCompat");
        Class<?> transform = loader.loadClass("com.miui.fliphome.gesture.util.ClipAnimationHelper$TransformParams");
        Class<?> params = loader.loadClass("com.android.systemui.shared.recents.system.SyncRtSurfaceTransactionApplierCompat$SurfaceParams");
        Class<?> surface = loader.loadClass("com.android.systemui.shared.recents.system.SurfaceControlCompat");
        runningInfo = field(base, "mRunningTaskInfo"); tracker = field(base, "mTracker");
        targetTask = field(target, "taskId"); targetBounds = field(target, "sourceContainerBounds");
        clipProgress = field(transform, "clipProgress"); windowCrop = field(params, "windowCrop");
        paramSurface = field(params, "surface"); alpha = field(params, "alpha"); matrix = field(params, "matrix");
        layer = field(params, "layer"); shown = field(params, "isShow");
        homeSurface = field(params, "homeSurface"); splitSurface = field(params, "otherSurfaceInSplit");
        radiusTL = field(params, "radiusTL"); radiusTR = field(params, "radiusTR");
        radiusBR = field(params, "radiusBR"); radiusBL = field(params, "radiusBL");
        surfaceParams = params.getDeclaredConstructor(surface, float.class, android.graphics.Matrix.class, Rect.class,
                int.class, boolean.class, float.class, float.class, float.class, float.class);
        surfaceParams.setAccessible(true);
        reboundProgress = method(tracker.getType(), "getCurTaskFullscreenProgress");
        runningIndex = method(tracker.getType(), "getRunningTaskIndex");
        Method start = method(mode, "resetLauncherForStartGesture");
        Method calculate = java.util.Arrays.stream(helper.getDeclaredMethods()).filter(m -> m.getName().equals("calculateSurfaceParams"))
                .findFirst().orElseThrow(() -> new NoSuchMethodException("calculateSurfaceParams"));
        calculate.setAccessible(true);
        Method appReturn = method(mode, "performAppToApp", int.class);
        Method finish = method(mode, "finish", boolean.class, boolean.class);
        Method cancel = method(mode, "onGestureAnimationCanceled");

        hook(module, start).intercept(chain -> {
            begin(chain.getThisObject()); return chain.proceed();
        });
        hook(module, calculate).intercept(chain -> {
            Object result = chain.proceed();
            return crop(chain.getArg(0), chain.getArg(4), result);
        });
        hook(module, appReturn).intercept(chain -> {
            Object owner = chain.getThisObject();
            synchronized (this) {
                if (gesture == owner) returning = (int) chain.getArg(0) == (int) runningIndex.invoke(tracker.get(owner));
            }
            return chain.proceed();
        });
        hook(module, finish).intercept(chain -> {
            Object result = chain.proceed(); finish(chain.getThisObject(), !(boolean) chain.getArg(0)); return result;
        });
        hook(module, cancel).intercept(chain -> {
            Object result = chain.proceed(); cancel(chain.getThisObject()); return result;
        });
        // A different final task is not a rebound to the source, regardless of controller toHome flags.
        try {
            hook(module, method(mode, "performAppToAppAnimationEnd", int.class)).intercept(chain -> {
                Object owner = chain.getThisObject();
                boolean sameTask = (int) chain.getArg(0) == (int) runningIndex.invoke(tracker.get(owner));
                Object result = chain.proceed();
                if (!sameTask) finish(owner, false);
                return result;
            });
        } catch (ReflectiveOperationException error) {
            NativeLog.warning("Recents final task callback unavailable; controller completion retained", error);
        }
        // Completed recents cards are drawn entirely by the OEM. Snapshot task dimensions do not reliably
        // describe the bitmap's already-cropped content, so never add an onDraw clip or rewrite the snapshot.
    }

    void prepare(ViewGroup root, int rotation, CoverGeometry geometry, boolean rounded, boolean symmetric, boolean ordinary) {
        prepared = new Source(root, new Viewport(rotation, geometry, rounded, symmetric), ordinary);
    }
    private void forget(ViewGroup root) { if (prepared != null && prepared.root == root) prepared = null; }
    boolean inFlight(ViewGroup root) { Source current = source; return current != null && current.root == root; }
    boolean retainsColumn(ViewGroup root) { Source current = source; return current != null && current.root == root && current.ordinary; }
    boolean holdsTone(ViewGroup root) { return inFlight(root); }
    boolean restoring(ViewGroup root) { return restoring && inFlight(root); }

    private synchronized void begin(Object owner) throws ReflectiveOperationException {
        // Track blur handoff even with crop off; crop() still leaves all native task parameters untouched.
        if (!NativeModule.config().enabled || !NativeModule.config().recentsCrop
                && NativeModule.config().background != CoverBackground.BLUR_FILL) return;
        Source next = prepared;
        ActivityManager.RunningTaskInfo info = (ActivityManager.RunningTaskInfo) runningInfo.get(owner);
        if (next == null || info == null || !NativeModule.config().enabled) return;
        source = next; gesture = owner; taskId = info.taskId; generation++;
        returning = false; restoring = false; framePending = false; frameReady = false;
        if (Looper.myLooper() == Looper.getMainLooper()) sceneChanged.run(); else main.post(sceneChanged);
        NativeLog.info("App-only gesture task=" + taskId + " ordinary=" + next.ordinary + " viewport=" + next.viewport.bounds);
    }

    private synchronized Object crop(Object target, Object transform, Object original) throws ReflectiveOperationException {
        if (!NativeModule.config().recentsCrop) return original;
        Source current = source;
        if (current == null || !current.ordinary || original == null || targetTask.getInt(target) != taskId) return original;
        Rect bounds = (Rect) targetBounds.get(target);
        Rect crop = new Rect((Rect) windowCrop.get(original));
        Viewport viewport = current.viewport;
        if (viewport.initialCrop == null) {
            viewport.initialCrop = new Rect(crop);
            viewport.includesSide = (bounds.isEmpty() || includesSide(CoverRotation.landscape(viewport.rotation) ? bounds.height() : bounds.width(),
                    CoverRotation.landscape(viewport.rotation) ? bounds.width() : bounds.height()))
                    && includesSide(CoverRotation.landscape(viewport.rotation) ? crop.height() : crop.width(),
                            CoverRotation.landscape(viewport.rotation) ? crop.width() : crop.height());
            NativeLog.info("App-only source geometry bounds=" + bounds + " nativeCrop=" + crop + " includesSide=" + viewport.includesSide);
        }
        if (viewport.includesSide) {
            Rect app = cropViewport(viewport.initialCrop, viewport.rotation);
            if (!crop.intersect(app)) return original;
        }
        float rebound = returning ? (float) reboundProgress.invoke(tracker.get(gesture)) : 0;
        float progress = cornerProgress(returning, clipProgress.getFloat(transform), rebound);
        float sourceScale = viewport.initialCrop.height() / (float) CoverRotation.height(viewport.rotation);
        Object result = surfaceParams.newInstance(paramSurface.get(original), alpha.getFloat(original), matrix.get(original), crop,
                layer.getInt(original), shown.getBoolean(original),
                radius(viewport.radii[0] * sourceScale, radiusTL.getFloat(original), progress),
                radius(viewport.radii[1] * sourceScale, radiusTR.getFloat(original), progress),
                radius(viewport.radii[2] * sourceScale, radiusBR.getFloat(original), progress),
                radius(viewport.radii[3] * sourceScale, radiusBL.getFloat(original), progress));
        homeSurface.set(result, homeSurface.get(original)); splitSurface.set(result, splitSurface.get(original));
        return result;
    }

    static Rect appViewport(int rotation) {
        int side = DockSpec.WIDTH + DockSpec.SEAM_BLEED;
        return CoverRotation.map(new Rect(0, 0, CoverGeometry.WIDTH - side, CoverGeometry.HEIGHT), rotation);
    }
    static boolean includesSide(int width, int height) {
        // Compare aspect ratios, not physical pixels against compatibility-buffer pixels. Allow only seam rounding.
        return height > 0 && (long) width * 1392 > (long) (1208 - DockSpec.WIDTH) * height;
    }
    static Rect cropViewport(Rect source, int rotation) {
        Rect app = appViewport(rotation);
        float width = CoverRotation.width(rotation), height = CoverRotation.height(rotation);
        return new Rect(source.left + Math.round(source.width() * app.left / width), source.top + Math.round(source.height() * app.top / height),
                source.left + Math.round(source.width() * app.right / width), source.top + Math.round(source.height() * app.bottom / height));
    }
    static float cornerProgress(boolean returning, float clip, float rebound) {
        return Math.max(0, Math.min(1, returning ? rebound : clip));
    }
    private static float radius(float source, float nativeRadius, float progress) {
        return source + (nativeRadius - source) * progress;
    }

    private synchronized void finish(Object owner, boolean toSource) {
        if (gesture != owner) return;
        // The controller's final destination wins over an earlier app-to-app hover/return candidate.
        // Otherwise finish(toHome=true) can wait forever for an app frame while the desktop is already showing.
        if (source.ordinary && toSource) {
            restoring = true; main.post(sceneChanged);
        } else {
            source = null; gesture = null; restoring = false; main.post(sceneChanged);
        }
    }
    private synchronized void cancel(Object owner) { finish(owner, returning); }
    synchronized void sourceFrame(ViewGroup root, boolean nativeAppReady) {
        if (!restoring(root)) return;
        // This fence is the WATCH WINDOW's committed frame, not proof the destination is ordinary.
        // A genuine fullscreen destination never sets nativeAppReady. Waiting for it forever kept
        // restoring/retainsColumn latched, forcing both black and blur fill over that destination.
        if (frameReady) {
            source = null; gesture = null; restoring = false;
            main.post(sceneChanged);
            NativeLog.info("App-only rebound fence released; native destination owns column, ordinary=" + nativeAppReady);
        } else if (!frameReady && !framePending && root.isAttachedToWindow() && root.isHardwareAccelerated()) {
            int expected = generation;
            framePending = true;
            root.getViewTreeObserver().registerFrameCommitCallback(() -> main.post(() -> {
                synchronized (RecentsAppAnimation.this) {
                    if (generation != expected || source == null) return;
                    frameReady = true;
                }
                sceneChanged.run();
            }));
            root.invalidate();
        }
    }
    synchronized void detach(ViewGroup root) {
        forget(root);
        if (inFlight(root)) { source = null; gesture = null; restoring = false; }
    }

    static final class Viewport {
        final Rect bounds; final int topRadius, bottomRadius;
        final int[] radii;
        final int rotation;
        Rect initialCrop; boolean includesSide;
        Viewport(int rotation, CoverGeometry geometry, boolean rounded, boolean symmetric) {
            this.rotation = rotation;
            bounds = appViewport(rotation);
            topRadius = geometry.appTop(rotation == 2, rounded, symmetric);
            bottomRadius = geometry.appBottom(rotation == 2, rounded, symmetric);
            int top = geometry.appTop(false, rounded, symmetric), bottom = geometry.appBottom(false, rounded, symmetric);
            radii = switch (rotation) {
                case 1 -> new int[]{top, bottom, bottom, top};
                case 2 -> new int[]{bottom, bottom, top, top};
                case 3 -> new int[]{bottom, top, top, bottom};
                default -> new int[]{top, top, bottom, bottom};
            };
        }
    }
    private record Source(ViewGroup root, Viewport viewport, boolean ordinary) { }
    private static XposedInterface.HookBuilder hook(NativeModule module, Executable method) {
        return module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE);
    }
    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Method method(Class<?> type, String name, Class<?>... args) throws ReflectiveOperationException {
        Method method = type.getDeclaredMethod(name, args); method.setAccessible(true); return method;
    }
}
