package com.flipduo.module;

import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.IBinder;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.WindowManager;

/** Fixed-extent native blur. App-edge material is hidden with the restoration plane, not cropped. */
final class BlurFillMaterial {
    private final View view;
    private final SurfaceControlViewHost host;
    private SurfaceControlViewHost.SurfacePackage surfacePackage;
    private BlurFillWindowBlur blur;
    private final Rect bounds;
    private boolean ready, closed;
    private Exception failure;

    BlurFillMaterial(View owner, SurfaceControl target, Rect bounds, int layer, SurfaceControl samplingPlane, Runnable changed)
            throws ReflectiveOperationException {
        this.bounds = new Rect(0, 0, bounds.width(), bounds.height());
        view = new View(owner.getContext());
        // No input transfer token: this hierarchy only supplies material, never controls or input.
        host = new SurfaceControlViewHost(owner.getContext(), owner.getDisplay(), (IBinder) null);
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View ignored) {
                if (closed) return;
                try {
                    blur = new BlurFillWindowBlur(view);
                    blur.background(null);
                    // Prepare the real blur frame while its parent is still hidden. Readiness
                    // must cover the blur itself, not only a transparent placeholder buffer.
                    blur.show(BlurFillMaterial.this.bounds, 1, true);
                    view.getViewTreeObserver().registerFrameCommitCallback(() -> view.post(() -> {
                        if (!closed) { ready = true; changed.run(); }
                    }));
                    view.invalidate();
                } catch (ReflectiveOperationException | RuntimeException error) {
                    failure = error; view.post(changed);
                }
            }
            @Override public void onViewDetachedFromWindow(View ignored) { ready = false; }
        });
        try {
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(bounds.width(), bounds.height(),
                    WindowManager.LayoutParams.TYPE_APPLICATION,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            params.setTitle(layer < 0 ? "FlipDuo corner material" : "FlipDuo side material");
            // This overload exists on both verified ROMs; the SDK37 replacement LayoutParams
            // subclass does not exist on SDK36. Use the common signature rather than an SDK guess.
            SurfaceControlViewHost.class.getMethod("setView", View.class, WindowManager.LayoutParams.class)
                    .invoke(host, view, params);
            surfacePackage = host.getSurfacePackage();
            if (surfacePackage == null) throw new ReflectiveOperationException("Missing windowless blur surface");
            try (SurfaceControl.Transaction tx = new SurfaceControl.Transaction()) {
                tx.reparent(surfacePackage.getSurfaceControl(), target)
                        .setLayer(surfacePackage.getSurfaceControl(), layer)
                        .setPosition(surfacePackage.getSurfaceControl(), bounds.left, bounds.top)
                        .setVisibility(surfacePackage.getSurfaceControl(), true);
                // Keep visibility owned by the app-edge gate, but sample alongside the side
                // material BELOW the controls. Sampling above that window changes the backdrop.
                if (samplingPlane != null) SurfaceControl.Transaction.class
                        .getMethod("setRelativeLayer", SurfaceControl.class, SurfaceControl.class, int.class)
                        .invoke(tx, surfacePackage.getSurfaceControl(), samplingPlane, 2);
                tx.apply();
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            close(); throw error;
        }
    }

    boolean ready() throws ReflectiveOperationException {
        if (failure != null) throw new ReflectiveOperationException("Windowless blur initialization failed", failure);
        return ready && !closed;
    }
    void alpha(SurfaceControl.Transaction tx, float opacity) { tx.setAlpha(surfacePackage.getSurfaceControl(), opacity); }
    void onDraw(SurfaceControl.Transaction tx) { BlurFillSurface.onDraw(view, tx); }
    void close() {
        if (closed) return;
        closed = true; ready = false;
        if (blur != null) { blur.close(); blur = null; }
        host.release();
        if (surfacePackage != null) { surfacePackage.release(); surfacePackage = null; }
    }
}
