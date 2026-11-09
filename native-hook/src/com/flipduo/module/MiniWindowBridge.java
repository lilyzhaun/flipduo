package com.flipduo.module;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * SystemUI side of the cover mini window. HyperOS itself turns the task into its native mini freeform window and keeps
 * running it; we only aim the shell's enter animation at FlipHome's widget slot and map a single tap to the native
 * double-tap maximize. Tasks, processes and app data are never removed or edited.
 */
final class MiniWindowBridge {
    static final String STATE = "com.flipduo.module.MINI_WINDOW_STATE";
    private static final String SHELL = "com.android.wm.shell.multitasking.";
    private static final String REASON = "flipduo_cover_slot";
    private static final int LEFT_CORNER = 1; // Native corner math then anchors x at movable.left without rounding.
    private static final ThreadLocal<Slot> ENTERING = new ThreadLocal<>();
    private static MiniWindowBridge instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ClassLoader loader;
    private Method taskId, taskInfo, isMini, cornerRadius, doubleTap, targetBounds, targetScale, activityType, windowingMode;
    private Method taskLeash, sameSurface, validSurface, crop, surfaceCorner, surfaceStroke, surfaceShadow, shadow, strokeRefresh;
    private Method destinationStroke, strokeAlpha, strokeThickness, applyTransaction, closeTransaction;
    private Constructor<?> transaction;
    private Field taskDisplay, organizer, strokeController;
    private volatile Slot pending, active;
    private Context context;
    private final Runnable timeout = () -> {
        Slot slot = pending;
        if (slot != null) { pending = null; NativeLog.info("Native shell did not enter a mini window; task=" + slot.task); }
    };

    private static final class Slot {
        final int task, display;
        final Rect rect;
        final int[] size;
        float scale;
        float radius;
        Object info, leash, decoration;
        volatile Object[] shadowParameters;
        boolean aimedScale, aimedPosition;
        Slot(int task, int display, Rect rect, int[] size) {
            this.task = task; this.display = display; this.rect = rect; this.size = size; scale = MiniSlot.scale(size[0], rect.width());
        }
        int[] bounds() { return new int[]{rect.left, rect.top, rect.right, rect.bottom}; }
    }

    private MiniWindowBridge(ClassLoader loader) { this.loader = loader; }

    static boolean occupiesSlot(int display) {
        Slot slot = instance == null ? null : instance.active;
        return slot != null && slot.display == display;
    }

    static void install(NativeModule module, ClassLoader loader) throws ReflectiveOperationException {
        MiniWindowBridge b = new MiniWindowBridge(loader);
        Class<?> animation = loader.loadClass(SHELL + "miuifreeform.MiuiFreeformModeAnimation");
        Class<?> info = loader.loadClass(SHELL + "common.taskmanager.MiuiFreeformModeTaskInfo");
        Class<?> target = loader.loadClass(SHELL + "common.animation.MultiTaskingAnimTarget");
        Class<?> miniState = loader.loadClass(SHELL + "miuifreeform.MiuiFreeformModeMiniStateHandler");
        b.taskId = info.getMethod("getTaskId"); b.taskInfo = info.getMethod("getTaskInfo");
        b.taskLeash = info.getMethod("getLeash");
        b.isMini = info.getMethod("isMiniState"); b.cornerRadius = info.getMethod("getCornerRadius");
        b.organizer = animation.getDeclaredField("mRootTaskDisplayAreaOrganizer"); b.organizer.setAccessible(true);
        b.strokeController = animation.getDeclaredField("mMultiTaskingCornerTipAndStrokeController"); b.strokeController.setAccessible(true);
        Class<?> surface = Class.forName("android.view.SurfaceControl");
        Class<?> tx = Class.forName("android.view.SurfaceControl$Transaction");
        b.transaction = tx.getConstructor(); b.applyTransaction = tx.getMethod("apply"); b.closeTransaction = tx.getMethod("close");
        b.sameSurface = surface.getMethod("isSameSurface", surface); b.validSurface = surface.getMethod("isValid");
        b.crop = tx.getMethod("setCrop", surface, Rect.class);
        b.surfaceCorner = tx.getMethod("setCornerRadius", surface, float.class);
        b.surfaceStroke = tx.getMethod("setSurfaceStroke", surface, float[].class, float.class, float.class);
        b.surfaceShadow = tx.getMethod("setMiShadow", surface, int.class, float.class, float.class, float.class, float.class);
        Class<?> strokes = loader.loadClass(SHELL + "common.corner.MultiTaskingCornerTipAndStrokeController");
        Class<?> strokeInfo = loader.loadClass(SHELL + "common.corner.MultiTaskingCornerTipAndStrokeAnimInfo");
        Class<?> strokeState = loader.loadClass(SHELL + "common.corner.MultiTaskingStrokeState");
        b.destinationStroke = strokeInfo.getMethod("getDestStrokeState");
        b.strokeAlpha = strokeState.getMethod("setAlpha", float.class); b.strokeThickness = strokeState.getMethod("setThickness", float.class);
        b.strokeRefresh = strokes.getMethod("changeStrokeWithoutAnim", info, String.class);
        hook(module, strokes.getDeclaredMethod("getDesStrokeAnimInfo", info, strokeInfo, int.class, boolean.class)).intercept(chain -> {
            Object result = chain.proceed();
            if (b.slotForTask(chain.getArg(0)) != null) {
                Object state = b.destinationStroke.invoke(chain.getArg(1));
                b.strokeAlpha.invoke(state, 0f); b.strokeThickness.invoke(state, 0f);
            }
            return result;
        });
        b.shadow = loader.loadClass(SHELL + "common.MultiTaskingShadowHelper").getMethod("setMiShadow", surface, tx,
                float.class, float.class, float.class, float.class, float.class);
        hook(module, b.shadow).intercept(chain -> {
            Slot slot = b.active != null ? b.active : b.pending;
            if (slot == null || slot.leash == null || !(boolean) b.sameSurface.invoke(slot.leash, chain.getArg(0))) return chain.proceed();
            Object[] args = chain.getArgs().toArray(); slot.shadowParameters = args.clone();
            args[2] = 0f; // The native helper encodes this alpha in the shadow color; radius/offset are not alpha.
            return chain.proceed(args);
        });
        b.doubleTap = miniState.getMethod("doubleTap", info);
        b.targetBounds = target.getMethod("getTaskBounds"); b.targetScale = target.getMethod("getScaleX");
        // Hidden TaskInfo accessors; SystemUI is a platform app.
        b.taskDisplay = android.app.TaskInfo.class.getDeclaredField("displayId");
        b.activityType = android.app.TaskInfo.class.getDeclaredMethod("getActivityType");
        b.windowingMode = android.app.TaskInfo.class.getDeclaredMethod("getWindowingMode");
        Method enter = null;
        for (Method m : animation.getDeclaredMethods()) if (m.getName().equals("startEnterMiniFreeformAnim")) enter = m;
        if (enter == null) throw new NoSuchMethodException("startEnterMiniFreeformAnim");
        int infoArg = Arrays.asList(enter.getParameterTypes()).indexOf(info);
        hook(module, enter).intercept(chain -> {
            Slot slot = b.pending;
            if (slot == null || slot.task != b.task(chain.getArg(infoArg))) return chain.proceed();
            slot.info = chain.getArg(infoArg); slot.leash = b.taskLeash.invoke(slot.info);
            slot.decoration = b.strokeController.get(chain.getThisObject());
            ENTERING.set(slot);
            try { return chain.proceed(); }
            finally { ENTERING.remove(); b.entered(slot, chain.getThisObject(), chain.getArg(infoArg)); }
        });
        Method enterBounds = animation.getDeclaredMethod("startEnterMiniFreeformBoundsAnimation", android.os.IBinder.class,
                Class.forName("android.window.TransitionInfo$Change"), tx, tx,
                loader.loadClass("com.android.wm.shell.transition.Transitions$TransitionFinishCallback"), info, boolean.class);
        hook(module, enterBounds).intercept(chain -> {
            Object result = chain.proceed();
            Slot slot = ENTERING.get();
            if (slot != null && slot.aimedScale && slot.aimedPosition) {
                // WCT resized the app buffer, but does not resize the parent's already-latched crop/stroke bounds.
                // Patch BOTH native transactions, not a delayed follow-up or the transparent FlipHome window.
                b.frame(slot, chain.getArg(2)); b.frame(slot, chain.getArg(3));
                NativeLog.info("Mini frame transactions task=" + slot.task + " localCrop=" + slot.size[0] + "x" + slot.size[1]
                        + " scale=" + slot.scale + " stroke=0 shadow=0");
            }
            return result;
        });
        // Inside that one native enter animation only: the slot replaces the corner, the mini scale and the size.
        hook(module, Class.forName("android.util.MiuiMultiWindowUtils")
                .getMethod("getMiniFreeformScale", Context.class, boolean.class, Rect.class, String.class)).intercept(chain -> {
            Slot slot = ENTERING.get();
            if (slot == null) return chain.proceed();
            Rect bounds = (Rect) chain.getArg(2); // The shell later applies this same Rect with WindowContainerTransaction.setBounds.
            int[] size = MiniSlot.freeformSize(Math.max(slot.size[0], bounds.width()), slot.rect.width(), slot.rect.height());
            slot.size[0] = size[0]; slot.size[1] = size[1]; slot.scale = MiniSlot.scale(size[0], slot.rect.width());
            bounds.set(bounds.left, bounds.top, bounds.left + slot.size[0], bounds.top + slot.size[1]);
            slot.aimedScale = true;
            return slot.scale;
        });
        hook(module, loader.loadClass(SHELL + "common.MultiTaskingDisplayInfo")
                .getMethod("getMovableBounds", Context.class, int.class, int.class, int.class)).intercept(chain -> {
            Slot slot = ENTERING.get();
            if (slot == null) return chain.proceed();
            slot.aimedPosition = true;
            return new Rect(slot.rect);
        });
        hook(module, loader.loadClass(SHELL + "miuifreeform.MiuiFreeformModeAvoidAlgorithm")
                .getMethod("adjustBoundsForSidebarIfNeed", Rect.class, Rect.class))
                .intercept(chain -> ENTERING.get() != null ? chain.getArg(0) : chain.proceed());
        hook(module, miniState.getMethod("singleTap", info)).intercept(chain -> {
            Slot slot = b.active;
            if (slot == null || slot.task != b.task(chain.getArg(0))) return chain.proceed();
            // Natively a single tap restores the floating window; from the slot it uses the double-tap maximize instead.
            return b.doubleTap.invoke(chain.getThisObject(), chain.getArg(0));
        });
        hook(module, animation.getMethod("startMaximizeShellTransition", info, boolean.class)).intercept(chain -> {
            b.release(chain.getArg(0), "maximize");
            return chain.proceed();
        });
        hook(module, animation.getMethod("startGestureAnimation", int.class, info, target)).intercept(chain -> {
            Slot slot = b.active;
            if (slot != null && slot.task == b.task(chain.getArg(1))) {
                Rect to = (Rect) b.targetBounds.invoke(chain.getArg(2));
                float scale = (float) b.targetScale.invoke(chain.getArg(2));
                // Native drag, pin, avoid or tap-restore away from the slot: the mini window is no longer ours to frame.
                if (!MiniSlot.fills(slot.bounds(), to.left, to.top, to.width() * scale, to.height() * scale))
                    b.release(chain.getArg(1), "gesture type=" + chain.getArg(0));
            }
            return chain.proceed();
        });
        hook(module, loader.loadClass(SHELL + "miuifreeform.MiuiFreeformModeController").getMethod("onTaskVanished", int.class)).intercept(chain -> {
            Slot slot = b.active;
            if (slot != null && slot.task == (int) chain.getArg(0)) b.clear(slot, "vanished");
            return chain.proceed();
        });
        instance = b;
        NativeLog.info("v6.32 native mini window hooks installed: task crop, decoration and logical canvas aligned");
    }

    /** Protected SystemTools request; state is reported separately with {@link #STATE}. */
    static void request(Context context, Intent intent) {
        try {
            if (instance == null) throw new IllegalStateException("Native mini window shell is unavailable");
            instance.handle(context, intent);
        } catch (Exception error) { NativeLog.warning("Mini window request was not executed", error); }
    }

    private void handle(Context app, Intent intent) throws ReflectiveOperationException {
        context = app;
        if ("mini_exit".equals(intent.getStringExtra("action"))) { exit(intent.getIntExtra("task", -1)); return; }
        if (pending != null || active != null) { NativeLog.info("Mini window request ignored: slot already in use"); return; }
        int displayId = intent.getIntExtra("displayId", -1);
        Rect rect = intent.getParcelableExtra("slot", Rect.class);
        Display display = app.getSystemService(DisplayManager.class).getDisplay(displayId);
        DisplayMetrics metrics = new DisplayMetrics();
        if (display != null) display.getRealMetrics(metrics);
        if (rect == null || rect.isEmpty() || display == null || display.getState() != Display.STATE_ON
                || app.getSystemService(KeyguardManager.class).isKeyguardLocked()
                || !CutoutAppConfig.isCoverDisplay(metrics.widthPixels, metrics.heightPixels)) {
            NativeLog.info("Mini window request ignored: cover is not unlocked and interactive"); return;
        }
        List<ActivityManager.RunningTaskInfo> tasks = app.getSystemService(ActivityManager.class).getRunningTasks(1);
        ActivityManager.RunningTaskInfo task = tasks.isEmpty() ? null : tasks.get(0);
        // Only an ordinary full-screen app: never home/recents/assistant, split, picture-in-picture or freeform.
        if (task == null || !task.isVisible() || (int) taskDisplay.get(task) != displayId
                || (int) activityType.invoke(task) != 1 || (int) windowingMode.invoke(task) != 1) {
            NativeLog.info("Mini window request ignored: top task is not an ordinary full-screen app " + (task == null ? "none" : task.topActivity));
            return;
        }
        Slot slot = new Slot(task.taskId, displayId, rect, MiniSlot.freeformSize(metrics.widthPixels, rect.width(), rect.height()));
        pending = slot;
        try {
            Object atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            atm.getClass().getMethod("launchMiniFreeFormWindowVersion2", int.class, int.class, String.class, Rect.class)
                    .invoke(atm, slot.task, LEFT_CORNER, REASON, new Rect());
        } catch (ReflectiveOperationException | RuntimeException error) { pending = null; throw error; }
        main.postDelayed(timeout, 2000);
        NativeLog.info("Native mini window requested task=" + slot.task + " slot=" + rect.toShortString()
                + " freeform=" + slot.size[0] + "x" + slot.size[1] + " scale=" + slot.scale);
    }

    private void entered(Slot slot, Object animation, Object info) {
        main.removeCallbacks(timeout);
        if (pending != slot) return;
        pending = null;
        if (!slot.aimedScale || !slot.aimedPosition) {
            restoreDecoration(slot);
            NativeLog.info("Native mini window kept its own position (enter path not aimable); task=" + slot.task); return;
        }
        try {
            slot.radius = (float) cornerRadius.invoke(info);
            // system_server computed its own mini scale for its default bounds; sync it the way the shell's
            // freeform-to-mini path does, so input and IME regions match the animated surface.
            Object change = Class.forName("miui.app.MiuiFreeFormManager$MiuiFreeFormInfoChange").getConstructor().newInstance();
            change.getClass().getMethod("setMiuiFreeformMode", int.class).invoke(change, 1);
            change.getClass().getMethod("setMiuiFreeformScale", float.class).invoke(change, slot.scale);
            Object token = ActivityManager.RunningTaskInfo.class.getField("token").get(taskInfo.invoke(info));
            Object wct = Class.forName("android.window.WindowContainerTransaction").getConstructor().newInstance();
            wct.getClass().getMethod("setMiuiFreeformInfoChange", token.getClass(), change.getClass()).invoke(wct, token, change);
            Object rootOrganizer = organizer.get(animation);
            rootOrganizer.getClass().getMethod("applyTransaction", wct.getClass()).invoke(rootOrganizer, wct);
        } catch (ReflectiveOperationException | RuntimeException error) { NativeLog.warning("Mini window scale was not synced to system_server", error); }
        active = slot;
        try { strokeRefresh.invoke(slot.decoration, slot.info, REASON); }
        catch (ReflectiveOperationException error) { NativeLog.warning("Could not compact mini window decoration", error); }
        FocusEventBridge.refresh();
        send(slot, true);
        NativeLog.info("Mini window entered cover slot task=" + slot.task + " radius=" + slot.radius);
    }

    private void release(Object info, String reason) throws ReflectiveOperationException {
        Slot slot = active;
        if (slot != null && slot.task == task(info)) clear(slot, reason);
    }

    private void clear(Slot slot, String reason) {
        synchronized (this) { if (active != slot) return; active = null; }
        restoreDecoration(slot);
        FocusEventBridge.refresh();
        send(slot, false);
        NativeLog.info("Mini window left cover slot task=" + slot.task + " reason=" + reason);
    }

    /** FlipHome can no longer frame the slot (lock, inner display, rotation, disable): use the native maximize. */
    private void exit(int task) throws ReflectiveOperationException {
        Slot slot = active;
        if (slot == null || slot.task != task) return;
        Object shell = loader.loadClass("com.android.wm.shell.dagger.MultiTaskingControllerImpl").getMethod("getInstance").invoke(null);
        Object controller = shell.getClass().getMethod("getMiuiFreeformModeController").invoke(shell);
        Object handler = shell.getClass().getMethod("getMiuiFreeformModeMiniStateHandler").invoke(shell);
        Object tasks = shell.getClass().getMethod("getMultiTaskingTaskRepository").invoke(shell);
        Field executor = controller.getClass().getDeclaredField("mMainExecutor"); executor.setAccessible(true);
        ((Executor) executor.get(controller)).execute(() -> { // Shell state is confined to its own main thread.
            try {
                Object info = tasks.getClass().getMethod("getMiuiFreeformTaskInfo", int.class).invoke(tasks, task);
                if (info != null && (boolean) isMini.invoke(info)) doubleTap.invoke(handler, info);
                else clear(slot, "already left");
            } catch (ReflectiveOperationException error) { NativeLog.warning("Could not return mini window to full screen", error); }
        });
    }

    private void send(Slot slot, boolean occupied) {
        Context app = context;
        if (app == null) return;
        app.sendBroadcast(new Intent(STATE).setPackage("com.miui.fliphome").putExtra("task", slot.task).putExtra("display", slot.display)
                .putExtra("occupied", occupied).putExtra("slot", slot.rect).putExtra("radius", slot.radius), StatusWire.PERMISSION);
    }

    private Slot slotForTask(Object info) throws ReflectiveOperationException {
        Slot slot = active != null ? active : pending;
        return slot != null && slot.task == task(info) ? slot : null;
    }

    private void frame(Slot slot, Object tx) throws ReflectiveOperationException {
        int[] local = MiniSlot.localCrop(slot.size);
        crop.invoke(tx, slot.leash, new Rect(local[0], local[1], local[2], local[3]));
        surfaceCorner.invoke(tx, slot.leash, (float) cornerRadius.invoke(slot.info) / slot.scale);
        surfaceStroke.invoke(tx, slot.leash, new float[]{0f, 0f, 0f}, 0f, 0f);
        surfaceShadow.invoke(tx, slot.leash, 0, 0f, 0f, 0f, 0f);
    }

    private void restoreDecoration(Slot slot) {
        if (slot.leash == null) return;
        try {
            if (!(boolean) validSurface.invoke(slot.leash)) return; // Native task removal may already have released the surface.
            strokeRefresh.invoke(slot.decoration, slot.info, REASON + "_exit");
            Object[] requested = slot.shadowParameters;
            if (requested != null) {
                Object tx = transaction.newInstance();
                try {
                    Object[] args = requested.clone(); args[1] = tx;
                    shadow.invoke(null, args); applyTransaction.invoke(tx);
                } finally { closeTransaction.invoke(tx); }
            }
        } catch (ReflectiveOperationException error) { NativeLog.warning("Could not restore native mini decoration", error); }
    }

    private int task(Object info) throws ReflectiveOperationException { return (int) taskId.invoke(info); }

    private static XposedInterface.HookBuilder hook(NativeModule module, Executable method) {
        return module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE);
    }
}
