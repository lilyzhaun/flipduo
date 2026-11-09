package com.flipduo.module;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.DisplayMetrics;
import android.util.SparseArray;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.WindowManager;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.util.List;

/** SystemUI's existing Shell task leash supplies a live compositor mirror, never a screenshot/readback. */
final class BlurFillSource {
    // User explicitly requested the live-mirror trial first, accepting that system blur-disable
    // handling is deferred. This is an experiment, not a guarantee against a transient clear frame.
    static boolean renderingAllowed() { return true; }
    private static BlurFillSource instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final IBinder life = new Binder();
    private final BlurFillSurface surfaces;
    private final Field taskMap, organizerLock, taskDisplay, taskUser, configuration, windowConfiguration;
    private final Method appearedLeash, taskMode, taskType, bounds;
    private volatile Object organizer;
    private Context context;
    private volatile Request request;
    private volatile BlurFillIdle idle;
    private int checkedTask = -1;
    private int activeTask = -1;
    private SurfaceControl activeLeash;
    private Rect activeSource;
    private boolean ready, reported, started, sceneCommitted, expanded;
    private final Runnable reconcile = this::reconcileOnMain;

    private static final class Request {
        IBinder owner;
        IBinder.DeathRecipient death;
        int sequence, display, rotation, firstRadius, lastRadius;
        UserHandle user;
        SurfaceControl target, plane;
        Rect destination, viewport, side;
        boolean retained;
    }

    private BlurFillSource(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> shell = loader.loadClass("com.android.wm.shell.ShellTaskOrganizer");
        taskMap = shell.getDeclaredField("mTasks"); taskMap.setAccessible(true);
        organizerLock = shell.getDeclaredField("mLock"); organizerLock.setAccessible(true);
        appearedLeash = Class.forName("android.window.TaskAppearedInfo").getMethod("getLeash");
        taskDisplay = android.app.TaskInfo.class.getDeclaredField("displayId");
        taskUser = android.app.TaskInfo.class.getDeclaredField("userId");
        taskMode = android.app.TaskInfo.class.getDeclaredMethod("getWindowingMode");
        taskType = android.app.TaskInfo.class.getDeclaredMethod("getActivityType");
        configuration = android.app.TaskInfo.class.getDeclaredField("configuration");
        windowConfiguration = Configuration.class.getDeclaredField("windowConfiguration");
        bounds = windowConfiguration.getType().getMethod("getBounds");
        surfaces = new BlurFillSurface();
    }

    static void install(NativeModule module, ClassLoader loader) throws ReflectiveOperationException {
        BlurFillSource source = new BlurFillSource(loader);
        Class<?> shell = source.taskMap.getDeclaringClass();
        for (Method method : shell.getDeclaredMethods()) {
            if (!method.getName().equals("onTaskAppeared") && !method.getName().equals("onTaskInfoChanged")
                    && !method.getName().equals("onTaskVanished")) continue;
            module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed(); source.organizer = chain.getThisObject(); source.schedule(); return result;
            });
        }
        // Optional: absent Shell transition APIs must not disable the live side fill or other hooks.
        try { source.installTransitions(module, loader); }
        catch (ReflectiveOperationException | RuntimeException error) {
            source.idle = null;
            NativeModule.warning("Blur-fill transition boundary unavailable; static extension stays hidden", error);
        }
        instance = source;
        NativeModule.info("v6.95 fixed live blur-fill task hooks installed; clear extension waits for Shell idle and mirror commit");
    }
    private void installTransitions(NativeModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> type = loader.loadClass("com.android.wm.shell.transition.Transitions");
        Method ready = type.getDeclaredMethod("onTransitionReady", IBinder.class,
                Class.forName("android.window.TransitionInfo"), SurfaceControl.Transaction.class, SurfaceControl.Transaction.class);
        idle = new BlurFillIdle(type, command -> main.post(command), this::schedule);
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            module.hook(constructor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed(), owner = chain.getThisObject();
                main.post(() -> {
                    try { if (idle != null) idle.connect(owner); }
                    catch (IllegalAccessException | RuntimeException error) {
                        idle = null; NativeModule.warning("Could not attach blur-fill Shell idle boundary", error);
                    }
                });
                return result;
            });
        }
        module.hook(ready).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            BlurFillIdle gate = idle;
            if (gate != null) gate.invalidate(); // Invalidate before a queued idle/commit callback can reveal a strip.
            Request current = request;
            // Add restriction to the REAL transition start transaction, before an incoming task can
            // move. No mirror transform, pixel sampling, or additional surface is calculated.
            if (current != null) try {
                BlurFillSurface.clip((SurfaceControl.Transaction) chain.getArg(2), current.target, current.plane, current.side);
            } catch (ReflectiveOperationException | RuntimeException error) {
                NativeModule.warning("Could not restrict blur-fill at transition start", error);
            }
            main.post(() -> {
                if (request == current && current != null) {
                    expanded = false;
                    restrictAndSchedule();
                }
            });
            return chain.proceed();
        });
    }
    private void restrictAndSchedule() {
        try { presentation(false, true); }
        catch (ReflectiveOperationException | RuntimeException error) {
            dropLayers(); report(false); NativeModule.warning("Could not restrict blur-fill extension", error);
        }
        schedule();
    }
    static void refresh() { if (instance != null) instance.schedule(); }
    private void schedule() { main.removeCallbacks(reconcile); main.post(reconcile); }

    static void start(Context context) {
        BlurFillSource source = instance;
        if (source == null || source.started) return;
        source.context = context;
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) { source.receive(intent); }
        }, new IntentFilter(BlurFillHost.REQUEST), StatusWire.PERMISSION, source.main, Context.RECEIVER_EXPORTED);
        IntentFilter events = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        events.addAction(Intent.ACTION_SCREEN_ON); events.addAction(Intent.ACTION_USER_PRESENT); events.addAction("android.intent.action.USER_SWITCHED");
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction()) || "android.intent.action.USER_SWITCHED".equals(intent.getAction())) source.releaseRequest();
                else source.schedule();
            }
        }, events, null, source.main, Context.RECEIVER_NOT_EXPORTED);
        context.getSystemService(WindowManager.class).addCrossWindowBlurEnabledListener(context.getMainExecutor(), enabled -> source.schedule());
        source.started = true;
        context.sendBroadcast(new Intent(BlurFillHost.STATE).setPackage("com.miui.fliphome")
                .putExtra("available", true).putExtra("pipeline", BlurFillHost.PIPELINE), StatusWire.PERMISSION);
    }

    private void receive(Intent intent) {
        Bundle extras = intent.getExtras();
        IBinder owner = extras == null ? null : extras.getBinder("owner");
        if (owner == null) return;
        int sequence = intent.getIntExtra("sequence", -1);
        if (request != null && request.owner.equals(owner) && sequence < request.sequence) return;
        if (intent.getBooleanExtra("release", false)) {
            if (request != null && request.owner.equals(owner)) releaseRequest();
            return;
        }
        if (intent.getBooleanExtra("control", false)) {
            if (request != null && request.owner.equals(owner) && sequence == request.sequence) {
                request.retained = intent.getBooleanExtra("retain", false); schedule();
                if (!request.retained && idle != null) idle.invalidate();
            }
            return;
        }
        SurfaceControl target = intent.getParcelableExtra("target", SurfaceControl.class);
        SurfaceControl plane = intent.getParcelableExtra("plane", SurfaceControl.class);
        Rect destination = intent.getParcelableExtra("destination", Rect.class);
        Rect viewport = intent.getParcelableExtra("viewport", Rect.class);
        int rotation = intent.getIntExtra("rotation", -1), display = intent.getIntExtra("display", -1);
        int firstRadius = intent.getIntExtra("first_radius", -1), lastRadius = intent.getIntExtra("last_radius", -1);
        UserHandle user = intent.getParcelableExtra("user", UserHandle.class);
        if (sequence < 0 || target == null || !target.isValid() || destination == null || destination.isEmpty()
                || plane == null || !plane.isValid() || viewport == null || viewport.isEmpty()
                || intent.getIntExtra("pipeline", -1) != BlurFillHost.PIPELINE
                || user == null || rotation < 0 || rotation > 3 || destination.left != 0 || destination.top != 0
                || viewport.left < 0 || viewport.top < 0 || viewport.right > CoverRotation.width(rotation) || viewport.bottom > CoverRotation.height(rotation)
                || destination.width() != viewport.width() || destination.height() != viewport.height()
                || firstRadius < 0 || lastRadius < 0 || firstRadius > CoverGeometry.COLUMN / 2 || lastRadius > CoverGeometry.COLUMN / 2) {
            if (target != null) target.release(); if (plane != null) plane.release(); return;
        }
        releaseRequest();
        Request next = new Request(); next.owner = owner; next.sequence = sequence; next.display = display;
        next.rotation = rotation; next.user = user; next.target = target; next.destination = destination;
        next.plane = plane; next.viewport = viewport; next.firstRadius = firstRadius; next.lastRadius = lastRadius;
        next.side = BlurFillGeometry.destination(rotation, viewport.left, viewport.top, viewport.width(), viewport.height());
        next.retained = intent.getBooleanExtra("retain", false);
        next.death = () -> main.post(() -> { if (request == next) releaseRequest(); });
        try { owner.linkToDeath(next.death, 0); }
        catch (RemoteException error) { target.release(); plane.release(); return; }
        request = next; schedule();
        checkedTask = -1;
        if (idle != null) idle.invalidate();
    }

    private void reconcileOnMain() {
        Request current = request;
        if (context == null || current == null) return;
        if (!renderingAllowed()) { dropLayers(); report(false); return; }
        DockConfig config = NativeModule.config();
        if (!current.target.isValid() || !current.plane.isValid()) { releaseRequest(); return; }
        // The two hosts receive remote preferences independently. Retain the request while SystemUI
        // catches up; its preference callback resumes it. FlipHome explicitly releases on mode exit.
        if (!config.enabled || config.background != CoverBackground.BLUR_FILL) { dropLayers(); report(false); return; }
        try {
            Display display = context.getSystemService(DisplayManager.class).getDisplay(current.display);
            DisplayMetrics metrics = new DisplayMetrics(); if (display != null) display.getRealMetrics(metrics);
            if (display == null || display.getState() != Display.STATE_ON || display.getRotation() != current.rotation
                    || !CutoutAppConfig.isCoverDisplay(metrics.widthPixels, metrics.heightPixels)
                    || context.getSystemService(KeyguardManager.class).isKeyguardLocked()
                    || !context.getSystemService(WindowManager.class).isCrossWindowBlurEnabled() || organizer == null) {
                dropLayers(); report(false); return;
            }
            // A gesture or the shared column's exit tail owns the existing mirror, not a new top task.
            // Keep the compositor mirror live, without tearing it down or selecting the desktop/card.
            if (current.retained) {
                presentation(false, false);
                Object retained;
                synchronized (organizerLock.get(organizer)) { retained = ((SparseArray<?>) taskMap.get(organizer)).get(activeTask); }
                SurfaceControl leash = retained == null ? null : (SurfaceControl) appearedLeash.invoke(retained);
                if (activeLeash == null || !activeLeash.isValid() || leash == null || !BlurFillSurface.same(leash, activeLeash)) {
                    dropLayers(); report(false);
                }
                return;
            }
            boolean observing = idle != null && idle.connected();
            boolean settled = observing && idle.await();
            if (observing && !settled && activeTask >= 0) {
                presentation(false, false); // Keep the SAME live source and fixed side throughout a transition.
                if (!activeLeash.isValid()) { dropLayers(); report(false); }
                return;
            }
            List<ActivityManager.RunningTaskInfo> tasks = context.getSystemService(ActivityManager.class).getRunningTasks(1);
            ActivityManager.RunningTaskInfo task = tasks.isEmpty() ? null : tasks.get(0);
            if (task == null || !task.isVisible() || taskDisplay.getInt(task) != current.display
                    || !UserHandle.getUserHandleForUid(taskUser.getInt(task) * 100000).equals(current.user)
                    || (int) taskType.invoke(task) != 1 || (int) taskMode.invoke(task) != 1) {
                dropLayers(); report(false); return;
            }
            Object appeared;
            synchronized (organizerLock.get(organizer)) { appeared = ((SparseArray<?>) taskMap.get(organizer)).get(task.taskId); }
            SurfaceControl leash = appeared == null ? null : (SurfaceControl) appearedLeash.invoke(appeared);
            Rect taskBounds = (Rect) bounds.invoke(windowConfiguration.get(configuration.get(task)));
            Rect source = BlurFillGeometry.source(taskBounds, current.rotation);
            if (leash == null || !leash.isValid() || source.isEmpty()) { dropLayers(); report(false); return; }
            if (observing && checkedTask != task.taskId) {
                checkedTask = task.taskId; idle.invalidate(); settled = idle.await();
                if (activeTask >= 0 && !settled) { presentation(false, false); return; }
            }
            if (activeTask == task.taskId && source.equals(activeSource) && BlurFillSurface.same(leash, activeLeash)) {
                presentation(settled && sceneCommitted, false); return;
            }
            Rect app = BlurFillGeometry.appDestination(source, taskBounds, current.viewport);
            surfaces.show(current.target, current.plane, leash, source, current.destination, current.side, app,
                    current.firstRadius, current.lastRadius, CoverRotation.landscape(current.rotation), command -> main.post(command), () -> {
                if (request != current || activeTask != task.taskId || activeLeash != leash || activeSource != source) return;
                sceneCommitted = true;
                try { presentation(!current.retained && idle != null && idle.settled(), false); }
                catch (ReflectiveOperationException | RuntimeException error) {
                    dropLayers(); report(false); NativeModule.warning("Could not restore settled blur-fill corners", error); return;
                }
                report(true);
                NativeModule.info("Blur-fill committed task=" + task.taskId + " sequence=" + current.sequence);
            });
            activeTask = task.taskId; activeLeash = leash; activeSource = source;
            sceneCommitted = expanded = false;
            NativeModule.info("Blur-fill task=" + task.taskId + " source=" + source + " destination=" + current.destination + " GPU-live=true");
        } catch (ReflectiveOperationException | RuntimeException error) {
            dropLayers(); report(false); NativeModule.warning("Live blur-fill unavailable; no replacement fill", error);
        }
    }
    private void presentation(boolean corners, boolean force) throws ReflectiveOperationException {
        Request current = request;
        if (current == null || !force && expanded == corners) return;
        try (SurfaceControl.Transaction tx = new SurfaceControl.Transaction()) {
            BlurFillSurface.clip(tx, current.target, current.plane, corners ? current.destination : current.side);
            tx.apply();
        }
        expanded = corners;
    }
    private void report(boolean value) {
        report(value, false);
    }
    private void report(boolean value, boolean terminated) {
        if (request == null || !terminated && reported && ready == value) return;
        ready = value; reported = true;
        Intent state = new Intent(BlurFillHost.STATE).setPackage("com.miui.fliphome").putExtra("sequence", request.sequence)
                .putExtra("ready", ready).putExtra("terminated", terminated).putExtra("pipeline", BlurFillHost.PIPELINE);
        Bundle binders = new Bundle(); binders.putBinder("owner", request.owner); binders.putBinder("life", life); state.putExtras(binders);
        context.sendBroadcast(state, StatusWire.PERMISSION);
    }
    private void dropLayers() {
        activeTask = -1; activeLeash = null; activeSource = null;
        sceneCommitted = expanded = false;
        try { surfaces.clear(); }
        catch (RuntimeException error) { NativeModule.warning("Could not remove blur-fill mirror", error); }
    }
    private void releaseRequest() {
        dropLayers(); report(false, true);
        if (request != null) { request.owner.unlinkToDeath(request.death, 0); request.target.release(); request.plane.release(); }
        request = null; ready = reported = false;
    }
}
