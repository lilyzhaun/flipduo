package com.flipduo.module;

import android.graphics.Rect;
import android.view.SurfaceControl;
import android.view.View;
import android.view.AttachedSurfaceControl;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.Executor;

/** Task mirror below the existing window; app-only restoration above it. No captured pixels or task mutations. */
final class BlurFillSurface {
    private final Constructor<?> builder;
    private final Method name, parent, containerLayer, build, mirrorSurface, cornerRadius, colorTransform;
    private Scene active;

    private final class Scene {
        final ArrayList<SurfaceControl> layers = new ArrayList<>();
        SurfaceControl backdrop, app, plane;
        SurfaceControl layer(String label, SurfaceControl target) throws ReflectiveOperationException {
            Object b = builder.newInstance(); name.invoke(b, label); parent.invoke(b, target);
            containerLayer.invoke(b);
            SurfaceControl result = (SurfaceControl) build.invoke(b); layers.add(result); return result;
        }
        SurfaceControl mirror(SurfaceControl task) throws ReflectiveOperationException {
            SurfaceControl result = (SurfaceControl) mirrorSurface.invoke(null, task); layers.add(result); return result;
        }
        void detach(SurfaceControl.Transaction tx) {
            if (plane != null && plane.isValid()) tx.setVisibility(plane, false);
            if (backdrop != null && backdrop.isValid()) tx.setVisibility(backdrop, false).reparent(backdrop, null);
            if (app != null && app.isValid()) tx.setVisibility(app, false).reparent(app, null);
        }
        void release() { for (int i = layers.size() - 1; i >= 0; i--) layers.get(i).release(); }
    }

    BlurFillSurface() throws ReflectiveOperationException {
        Class<?> builderType = Class.forName("android.view.SurfaceControl$Builder");
        builder = builderType.getConstructor();
        name = builderType.getMethod("setName", String.class);
        parent = builderType.getMethod("setParent", SurfaceControl.class);
        containerLayer = builderType.getMethod("setContainerLayer");
        build = builderType.getMethod("build");
        mirrorSurface = SurfaceControl.class.getMethod("mirrorSurface", SurfaceControl.class);
        Class<?> tx = SurfaceControl.Transaction.class;
        cornerRadius = tx.getMethod("setCornerRadius", SurfaceControl.class, float.class);
        colorTransform = tx.getMethod("setColorTransform", SurfaceControl.class, float[].class, float[].class);
    }

    static SurfaceControl windowSurface(View view) throws ReflectiveOperationException {
        Method root = View.class.getDeclaredMethod("getViewRootImpl"); root.setAccessible(true);
        Object impl = root.invoke(view);
        return impl == null ? null : (SurfaceControl) Class.forName("android.view.ViewRootImpl").getMethod("getSurfaceControl").invoke(impl);
    }
    static boolean same(SurfaceControl a, SurfaceControl b) throws ReflectiveOperationException {
        return a != null && b != null && a.isValid() && b.isValid()
                && (Boolean) SurfaceControl.class.getMethod("isSameSurface", SurfaceControl.class).invoke(a, b);
    }
    static final class Targets {
        final SurfaceControl background, foreground;
        Targets(SurfaceControl background, SurfaceControl foreground) { this.background = background; this.foreground = foreground; }
    }
    static Targets destinations(View view, SurfaceControl window, Rect rect) throws ReflectiveOperationException {
        SurfaceControl windowContainer = null;
        SurfaceControl background = null, foreground = null;
        try {
            Method getRoot = View.class.getDeclaredMethod("getViewRootImpl"); getRoot.setAccessible(true);
            Object root = getRoot.invoke(view);
            windowContainer = (SurfaceControl) Class.forName("android.view.ViewRootImpl")
                    .getMethod("getParentSurfaceControl").invoke(root);
            if (windowContainer == null || !windowContainer.isValid())
                throw new ReflectiveOperationException("Native window container is unavailable");
            // The sampled background stays an absolute sibling below the window (relative-z
            // would join its sampling-excluded subtree). Restoration belongs above that buffer
            // and is anchored to it; never assume a fixed OEM content-layer z value.
            background = container("FlipDuo blur-fill host", windowContainer, rect, -1);
            foreground = container("FlipDuo app-restore host", windowContainer, rect, 1);
            try (SurfaceControl.Transaction tx = new SurfaceControl.Transaction()) {
                SurfaceControl.Transaction.class.getMethod("setRelativeLayer", SurfaceControl.class, SurfaceControl.class, int.class)
                        .invoke(tx, foreground, window, 1);
                tx.apply();
            }
            return new Targets(background, foreground);
        } catch (ReflectiveOperationException | RuntimeException error) {
            remove(foreground); remove(background); throw error;
        } finally { if (windowContainer != null) windowContainer.release(); }
    }
    private static SurfaceControl container(String label, SurfaceControl parent, Rect rect, int layer) throws ReflectiveOperationException {
        Class<?> type = Class.forName("android.view.SurfaceControl$Builder");
        Object b = type.getConstructor().newInstance();
        type.getMethod("setName", String.class).invoke(b, label);
        type.getMethod("setParent", SurfaceControl.class).invoke(b, parent);
        type.getMethod("setContainerLayer").invoke(b);
        SurfaceControl target = (SurfaceControl) type.getMethod("build").invoke(b);
        try (SurfaceControl.Transaction tx = new SurfaceControl.Transaction()) {
            tx.setLayer(target, layer).setPosition(target, rect.left, rect.top)
                    .setCrop(target, new Rect(0, 0, rect.width(), rect.height())).setVisibility(target, false);
            tx.apply();
        } catch (RuntimeException error) { remove(target); throw error; }
        return target;
    }
    static void onDraw(View view, SurfaceControl.Transaction tx) {
        AttachedSurfaceControl root = view.getRootSurfaceControl();
        if (view.isAttachedToWindow() && view.isShown() && view.isHardwareAccelerated()
                && root != null && root.applyTransactionOnDraw(tx)) view.invalidate();
        else tx.apply();
    }
    static void clip(SurfaceControl.Transaction tx, SurfaceControl target, SurfaceControl plane, Rect clip)
            throws ReflectiveOperationException {
        tx.setCrop(target, clip).setCrop(plane, clip);
    }
    static void remove(SurfaceControl surface) {
        if (surface == null) return;
        try {
            if (surface.isValid()) try (SurfaceControl.Transaction tx = new SurfaceControl.Transaction()) {
                tx.setVisibility(surface, false).reparent(surface, null).apply();
            }
        } finally { surface.release(); }
    }

    void show(SurfaceControl target, SurfaceControl plane, SurfaceControl task, Rect source, Rect destination, Rect side,
            Rect appDestination, int firstRadius, int lastRadius, boolean horizontal,
            Executor executor, Runnable committed) throws ReflectiveOperationException {
        Scene next = new Scene(), previous = active;
        try {
            next.plane = plane;
            next.backdrop = next.layer("FlipDuo blur-fill", target);
            next.app = next.layer("FlipDuo app viewport", plane);
            SurfaceControl mirror = next.mirror(task);
            float sx = destination.width() / (float) source.width(), sy = destination.height() / (float) source.height();
            try (SurfaceControl.Transaction tx = new SurfaceControl.Transaction()) {
                // A new task always starts side-only. A committed mirror is not proof that the
                // real task's transition has ended; the source expands only at the Shell idle boundary.
                clip(tx, target, plane, side);
                tx.setLayer(next.backdrop, 0).setCrop(next.backdrop, destination);
                tx.setLayer(next.app, 0).setCrop(next.app, destination);
                tx.reparent(mirror, next.backdrop).setLayer(mirror, 0).setCrop(mirror, source)
                        .setScale(mirror, sx, sy).setPosition(mirror, -source.left * sx, -source.top * sy);
                colorTransform.invoke(tx, mirror, BlurFillColors.colorMatrix(), new float[]{0,0,0});
                int parts = firstRadius == lastRadius ? 1 : 2;
                for (int i = 0; i < parts; i++) {
                    Rect clip = parts == 1 ? destination : BlurFillGeometry.half(destination, appDestination, horizontal, i == 1);
                    if (clip.isEmpty()) continue;
                    SurfaceControl half = next.layer("FlipDuo app clip " + i, next.app);
                    SurfaceControl rounded = next.layer("FlipDuo app rounded " + i, half);
                    SurfaceControl clear = next.mirror(task);
                    tx.setLayer(half, i).setCrop(half, clip);
                    tx.setPosition(rounded, appDestination.left, appDestination.top)
                            .setCrop(rounded, new Rect(0, 0, source.width(), source.height()));
                    cornerRadius.invoke(tx, rounded, (float) (i == 0 ? firstRadius : lastRadius));
                    tx.reparent(clear, rounded).setLayer(clear, 0).setCrop(clear, source)
                            .setPosition(clear, -source.left, -source.top);
                }
                if (previous != null) previous.detach(tx);
                tx.setVisibility(plane, true); // Re-enable the foreground after retiring the old scene.
                for (SurfaceControl layer : next.layers) tx.setVisibility(layer, true);
                tx.addTransactionCommittedListener(executor, committed::run).apply();
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            try (SurfaceControl.Transaction tx = new SurfaceControl.Transaction()) { next.detach(tx); tx.apply(); }
            finally { next.release(); }
            throw error;
        }
        active = next;
        if (previous != null) previous.release();
    }
    void clear() {
        Scene previous = active; active = null;
        if (previous == null) return;
        try {
            try (SurfaceControl.Transaction tx = new SurfaceControl.Transaction()) { previous.detach(tx); tx.apply(); }
        } finally { previous.release(); }
    }
}
