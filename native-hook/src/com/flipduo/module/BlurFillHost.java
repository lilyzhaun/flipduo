package com.flipduo.module;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.SurfaceControl;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.function.Consumer;

/** FlipHome owns the destination only. Source task handles and pixel buffers never cross this protocol. */
final class BlurFillHost {
    static final String REQUEST = "com.flipduo.module.BLUR_FILL_REQUEST";
    static final String STATE = "com.flipduo.module.BLUR_FILL_STATE";
    static final int PIPELINE = 7;
    private final WeakReference<ViewGroup> root;
    private final Context context;
    private final WindowManager windowManager;
    private final Runnable changed;
    private final Consumer<Boolean> blurChanged = this::onBlurChanged;
    private void onBlurChanged(Boolean ignored) {
        if (!listening) return;
        // Read the shared current state: a queued callback's boolean may already be stale.
        if (!windowManager.isCrossWindowBlurEnabled()) pauseForBlur();
        changed.run();
    }
    private final IBinder owner = new Binder();
    private final IBinder.DeathRecipient sourceDied = this::onSourceDied;
    private void onSourceDied() {
        ViewGroup view = root.get();
        if (view != null) view.post(() -> {
            ready = sent = requestPending = geometryPending = corners = false;
            sourceLife = null; sequence++; controlSequence = 0; lastStatus = -1; present(); changed.run();
        });
    }
    private IBinder sourceLife;
    private boolean listening, wanted, sent, ready, geometryPending, requestPending, liveSource = true;
    private boolean corners;
    private float opacity = 1;
    private int sequence, display, rotation, firstRadius, lastRadius;
    private int controlSequence, lastStatus = -1;
    private Rect viewport, body;
    private SurfaceControl window, target, plane;
    private BlurFillWindowBlur windowBlur;
    private final ArrayList<Retired> retired = new ArrayList<>();
    private final class Retired {
        final SurfaceControl target, plane;
        final int sequence;
        final boolean sent;
        boolean complete;
        Retired(SurfaceControl target, SurfaceControl plane, int sequence, boolean sent) {
            this.target = target; this.plane = plane; this.sequence = sequence; this.sent = sent;
        }
        void finish() {
            if (complete) return;
            complete = true; retired.remove(this);
            if (plane != null) plane.release();
            target.release();
            if (sent) sendRelease(sequence);
        }
        void removeNow() {
            if (complete) return;
            try (SurfaceControl.Transaction tx = new SurfaceControl.Transaction()) {
                if (target.isValid()) tx.setVisibility(target, false).reparent(target, null);
                if (plane != null && plane.isValid()) tx.setVisibility(plane, false).reparent(plane, null);
                tx.apply();
            } finally { finish(); }
        }
    }
    private final int[] location = new int[2];
    private int screenX = Integer.MIN_VALUE, screenY = Integer.MIN_VALUE;
    private WeakReference<ViewTreeObserver> observer = new WeakReference<>(null);
    private final ViewTreeObserver.OnPreDrawListener positionChanged = this::onPreDraw;
    private boolean onPreDraw() {
        ViewGroup view = root.get();
        if (wanted && view != null) {
            view.getLocationOnScreen(location);
            if (screenX != location[0] || screenY != location[1]) {
                screenX = location[0]; screenY = location[1]; changed.run();
            }
        }
        return true;
    }
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            ViewGroup view = root.get(); if (view == null) { stop(); return; }
            if (intent.getIntExtra("pipeline", -1) != PIPELINE) return; // Do not expose an old full-rectangle implementation.
            if (intent.getBooleanExtra("available", false)) {
                sent = ready = corners = requestPending = false;
                sequence++; controlSequence = 0; lastStatus = -1; present(); changed.run(); return;
            }
            Bundle extras = intent.getExtras();
            if (!wanted || extras == null || !owner.equals(extras.getBinder("owner")) || intent.getIntExtra("sequence", -1) != sequence) return;
            if (intent.getBooleanExtra("terminated", false)) {
                releaseTarget(); changed.run(); return;
            }
            int status = intent.getIntExtra("status_sequence", -1);
            if (intent.getIntExtra("control_sequence", -1) != controlSequence || status < 0 || status < lastStatus) return;
            lastStatus = status;
            boolean next = intent.getBooleanExtra("ready", false);
            if (next && !windowManager.isCrossWindowBlurEnabled()) {
                pauseForBlur(); changed.run(); return;
            }
            IBinder life = extras.getBinder("life");
            if (sourceLife != life) {
                if (sourceLife != null) sourceLife.unlinkToDeath(sourceDied, 0);
                sourceLife = life;
                if (life != null) try { life.linkToDeath(sourceDied, 0); }
                catch (RemoteException error) { sourceLife = null; next = false; sent = false; }
            }
            boolean nextCorners = next && liveSource && intent.getBooleanExtra("corners", false);
            if (ready != next || corners != nextCorners) { ready = next; corners = nextCorners; present(); changed.run(); }
        }
    };

    BlurFillHost(ViewGroup view, Runnable changed) {
        root = new WeakReference<>(view); context = view.getContext().getApplicationContext(); this.changed = changed;
        windowManager = context.getSystemService(WindowManager.class);
        view.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> { if (wanted && (l != ol || t != ot || r != or || b != ob)) changed.run(); });
    }
    boolean sync(boolean active, boolean liveSource, int actualRotation, int firstRadius, int lastRadius) {
        ViewGroup view = root.get();
        if (!BlurFillSource.renderingAllowed() || !active || view == null || !view.isAttachedToWindow()
                || !view.isHardwareAccelerated() || view.getDisplay() == null) {
            stop(); return false;
        }
        boolean sourceChanged = this.liveSource != liveSource;
        if (sourceChanged) { controlSequence++; corners = false; }
        this.liveSource = liveSource; wanted = true;
        if (!listening) {
            context.registerReceiver(receiver, new IntentFilter(STATE), StatusWire.PERMISSION, null, Context.RECEIVER_EXPORTED);
            listening = true;
            windowManager.addCrossWindowBlurEnabledListener(context.getMainExecutor(), blurChanged);
        }
        if (!windowManager.isCrossWindowBlurEnabled()) { pauseForBlur(); return false; }
        ViewTreeObserver tree = view.getViewTreeObserver();
        if (tree != observer.get()) {
            ViewTreeObserver old = observer.get(); if (old != null && old.isAlive()) old.removeOnPreDrawListener(positionChanged);
            tree.addOnPreDrawListener(positionChanged); observer = new WeakReference<>(tree);
        }
        if (view.getWidth() == 0 || view.getHeight() == 0) { releaseTarget(); return false; }
        try {
            SurfaceControl surface = BlurFillSurface.windowSurface(view);
            if (surface == null || !surface.isValid()) { releaseTarget(); return false; }
            int id = view.getDisplay().getDisplayId();
            view.getLocationOnScreen(location);
            Rect rect = BlurFillGeometry.destination(actualRotation, location[0], location[1], view.getWidth(), view.getHeight());
            if (rect.isEmpty()) { releaseTarget(); return false; }
            Rect nextViewport = new Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
            if (target == null || !target.isValid() || id != display || actualRotation != rotation
                    || !nextViewport.equals(viewport) || firstRadius != this.firstRadius || lastRadius != this.lastRadius
                    || !BlurFillSurface.same(surface, window)) {
                releaseTarget(); window = surface;
                Rect frame = new Rect(0, 0, nextViewport.width(), nextViewport.height());
                BlurFillSurface.Targets layers = BlurFillSurface.destinations(view, surface, frame);
                target = layers.background; plane = layers.foreground;
                windowBlur = new BlurFillWindowBlur(view);
                windowBlur.background(view.getBackground());
                display = id; rotation = actualRotation; viewport = nextViewport; body = rect;
                this.firstRadius = firstRadius; this.lastRadius = lastRadius;
            }
            if (!sent && !requestPending) requestOnDraw(view);
            else if (sent && sourceChanged) {
                Intent control = new Intent(REQUEST).setPackage("com.android.systemui").putExtra("sequence", sequence)
                        .putExtra("control", true).putExtra("retain", !liveSource).putExtra("control_sequence", controlSequence);
                Bundle binders = new Bundle(); binders.putBinder("owner", owner); control.putExtras(binders);
                if (!liveSource) context.sendBroadcast(control, StatusWire.PERMISSION);
                else try (SurfaceControl.Transaction tx = new SurfaceControl.Transaction()) {
                    int expected = sequence, expectedControl = controlSequence;
                    // Drain the gesture's local restrict-on-draw before allowing SystemUI to expand.
                    // Otherwise a delayed old crop could land AFTER the source's settled restore.
                    tx.addTransactionCommittedListener(context.getMainExecutor(), () -> {
                        if (wanted && sent && liveSource && sequence == expected && controlSequence == expectedControl)
                            context.sendBroadcast(control, StatusWire.PERMISSION);
                    });
                    BlurFillSurface.onDraw(view, tx);
                }
            }
            if (sourceChanged) present(!liveSource);
            return ready;
        } catch (ReflectiveOperationException | RuntimeException error) {
            stop(); NativeModule.warning("Blur-fill destination unavailable; no replacement fill", error); return false;
        }
    }
    /** Submission readiness is not pixel verification; used only to choose the controls' tint. */
    boolean visible() {
        ViewGroup view = root.get();
        return wanted && ready && opacity > 0 && view != null && view.isAttachedToWindow() && view.isShown()
                && target != null && target.isValid() && windowBlur != null && windowManager.isCrossWindowBlurEnabled();
    }
    void alpha(float opacity) {
        if (this.opacity == opacity) return;
        boolean wasVisible = visible();
        this.opacity = opacity;
        present();
        if (wasVisible != visible()) changed.run();
    }
    private void present() { present(false); }
    void background(Drawable background) {
        ViewGroup view = root.get(); if (view == null) return;
        if (windowBlur != null) windowBlur.background(background);
        else if (view.getBackground() != background) view.setBackground(background);
    }
    private void present(boolean restrict) {
        ViewGroup view = root.get();
        if (!wanted || target == null || !target.isValid() || view == null) return;
        if (!windowManager.isCrossWindowBlurEnabled()) { pauseForBlur(); changed.run(); return; }
        if (restrict) corners = false;
        Rect materialBounds = ready && corners && liveSource ? new Rect(0, 0, viewport.width(), viewport.height()) : body;
        windowBlur.show(materialBounds, opacity, ready && opacity > 0 && view.isShown());
        try (SurfaceControl.Transaction tx = new SurfaceControl.Transaction()) {
            // Locally restrict at gesture onset. Only SystemUI may expand again, after Shell idle
            // AND the current mirror's commit; a host ACK/alpha update must never reopen the clear strip.
            if (restrict) BlurFillSurface.clip(tx, target, plane, body);
            tx.setAlpha(target, ready ? opacity : 0).setAlpha(plane, ready ? 1 : 0);
            BlurFillSurface.onDraw(view, tx);
        } catch (ReflectiveOperationException | RuntimeException error) {
            stop(); NativeModule.warning("Blur-fill region handoff unavailable; no replacement fill", error);
        }
    }
    private void requestOnDraw(ViewGroup view) throws ReflectiveOperationException {
        SurfaceControl expectedTarget = target; int expectedSequence = sequence;
        requestPending = true;
        windowBlur.show(body, opacity, false);
        try (SurfaceControl.Transaction tx = new SurfaceControl.Transaction()) {
            BlurFillSurface.clip(tx, target, plane, body);
            tx.setAlpha(plane, 0).setVisibility(plane, true);
            tx.setAlpha(expectedTarget, 0).setVisibility(expectedTarget, true).addTransactionCommittedListener(context.getMainExecutor(), () -> {
                if (!wanted || target != expectedTarget || sequence != expectedSequence) return;
                if (!windowManager.isCrossWindowBlurEnabled()) { pauseForBlur(); changed.run(); return; }
                requestPending = false;
                Intent request = new Intent(REQUEST).setPackage("com.android.systemui").putExtra("sequence", sequence)
                        .putExtra("display", display).putExtra("rotation", rotation)
                        .putExtra("destination", new Rect(0, 0, viewport.width(), viewport.height())).putExtra("target", target)
                        .putExtra("plane", plane).putExtra("viewport", viewport)
                        .putExtra("first_radius", firstRadius).putExtra("last_radius", lastRadius).putExtra("pipeline", PIPELINE)
                        .putExtra("user", android.os.Process.myUserHandle()).putExtra("retain", !liveSource)
                        .putExtra("control_sequence", controlSequence);
                Bundle binders = new Bundle(); binders.putBinder("owner", owner); request.putExtras(binders);
                context.sendBroadcast(request, StatusWire.PERMISSION); sent = true;
            });
            BlurFillSurface.onDraw(view, tx);
        }
    }
    void geometrySubmitted() {
        ViewGroup view = root.get();
        if (!wanted || geometryPending || view == null || !view.isAttachedToWindow()) return;
        geometryPending = true;
        int expectedSequence = sequence;
        Runnable complete = () -> { if (sequence == expectedSequence) { geometryPending = false; if (wanted) changed.run(); } };
        if (view.isHardwareAccelerated()) {
            view.getViewTreeObserver().registerFrameCommitCallback(() -> view.post(complete)); view.invalidate();
        } else view.post(complete);
    }
    private void pauseForBlur() {
        releaseTarget(true);
        for (Retired previous : new ArrayList<>(retired)) previous.removeNow();
    }
    private void releaseTarget() { releaseTarget(false); }
    private void releaseTarget(boolean immediate) {
        Retired previous = target == null ? null : new Retired(target, plane, sequence, sent);
        if (windowBlur != null) { windowBlur.close(); windowBlur = null; }
        sent = ready = requestPending = geometryPending = corners = false; target = plane = window = null;
        sequence++; controlSequence = 0; lastStatus = -1;
        if (previous == null) return;
        retired.add(previous);
        ViewGroup view = root.get();
        if (immediate || view == null || !previous.target.isValid()) { previous.removeNow(); return; }
        try (SurfaceControl.Transaction tx = new SurfaceControl.Transaction()) {
            // Hide the entire local subtree with the replacement window buffer. Only then let the remote
            // source clear its mirror, so IPC timing cannot expose the task's shrinking recents transform.
            tx.setVisibility(previous.target, false).reparent(previous.target, null)
                    .setVisibility(previous.plane, false).reparent(previous.plane, null)
                    .addTransactionCommittedListener(context.getMainExecutor(), previous::finish);
            BlurFillSurface.onDraw(view, tx);
        }
    }
    private void sendRelease(int oldSequence) {
        Intent request = new Intent(REQUEST).setPackage("com.android.systemui").putExtra("release", true).putExtra("sequence", oldSequence);
        Bundle binders = new Bundle(); binders.putBinder("owner", owner); request.putExtras(binders);
        context.sendBroadcast(request, StatusWire.PERMISSION);
    }
    void stop() {
        releaseTarget();
        ViewGroup view = root.get();
        if (view == null || !view.isAttachedToWindow() || !view.isShown())
            for (Retired previous : new ArrayList<>(retired)) previous.removeNow();
        if (listening) {
            windowManager.removeCrossWindowBlurEnabledListener(blurChanged);
            context.unregisterReceiver(receiver);
        }
        if (sourceLife != null) sourceLife.unlinkToDeath(sourceDied, 0);
        ViewTreeObserver tree = observer.get(); if (tree != null && tree.isAlive()) tree.removeOnPreDrawListener(positionChanged);
        observer.clear(); geometryPending = false;
        listening = wanted = sent = ready = false; sourceLife = null; target = null;
    }
}
