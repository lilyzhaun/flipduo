package com.flipduo.module;

import android.content.Context;
import android.content.Intent;
import android.app.ActivityOptions;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.util.*;
import android.view.*;
import android.widget.Toast;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import android.view.animation.PathInterpolator;

final class NativeNavigation {
    private static final ExecutorService ACTIONS = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean RECENTS_RUNNING = new AtomicBoolean();
    static void recents(Context context, int displayId) {
        if (!RECENTS_RUNNING.compareAndSet(false, true)) return;
        Context app = context.getApplicationContext();
        ACTIONS.execute(() -> {
            try {
                checkCover(app, displayId);
                Display display = app.getSystemService(DisplayManager.class).getDisplay(displayId);
                int rotation = display.getRotation();
                float x = CoverRotation.recentsX(rotation), startY = CoverRotation.recentsY(rotation);
                Class<?> input = Class.forName("android.hardware.input.InputManagerGlobal");
                Object manager = input.getMethod("getInstance").invoke(null);
                Method inject = input.getMethod("injectInputEvent", InputEvent.class, int.class);
                Method target = InputEvent.class.getDeclaredMethod("setDisplayId", int.class);
                MAIN.post(() -> Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
                    final PathInterpolator ease = new PathInterpolator(.32f, .72f, 0, 1);
                    long start, down;
                     float y = startY;
                    boolean touching;
                    @Override public void doFrame(long frameTimeNanos) {
                        try {
                             checkCover(app, displayId);
                             if (display.getRotation() != rotation) throw new IllegalStateException("Display rotated during recents gesture");
                            if (start == 0) {
                                start = frameTimeNanos; down = SystemClock.uptimeMillis();
                                motion(manager, inject, target, displayId, down, MotionEvent.ACTION_DOWN, x, y);
                                touching = true;
                            }
                            float progress = Math.min(1, (frameTimeNanos - start) / 280_000_000f);
                             y = startY - 510 * ease.getInterpolation(progress);
                            motion(manager, inject, target, displayId, down, MotionEvent.ACTION_MOVE, x, y);
                            if (progress < 1) Choreographer.getInstance().postFrameCallback(this);
                            else {
                                motion(manager, inject, target, displayId, down, MotionEvent.ACTION_UP, x, y);
                                touching = false; RECENTS_RUNNING.set(false);
                                NativeLog.info("Continuous vsync recents gesture delivered: display=" + displayId + " durationMs=" + (SystemClock.uptimeMillis() - down));
                            }
                        } catch (Exception error) {
                            if (touching) try { motion(manager, inject, target, displayId, down, MotionEvent.ACTION_CANCEL, x, y); }
                            catch (Exception cancelError) { error.addSuppressed(cancelError); }
                            RECENTS_RUNNING.set(false); failed(app, error);
                        }
                    }
                }));
            } catch (Exception error) { RECENTS_RUNNING.set(false); failed(app, error); }
        });
    }

    private static void motion(Object manager, Method inject, Method target, int display, long down, int action, float x, float y) throws Exception {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            target.invoke(event, display);
            if (!Boolean.TRUE.equals(inject.invoke(manager, event, 0))) throw new IllegalStateException("Gesture rejected");
        } finally { event.recycle(); }
    }

    private static void checkCover(Context app, int displayId) {
        Display display = app.getSystemService(DisplayManager.class).getDisplay(displayId);
        if (display == null) throw new IllegalStateException("Cover display is unavailable");
        DisplayMetrics dm = new DisplayMetrics(); display.getRealMetrics(dm);
        if (display.getState() != Display.STATE_ON || !CutoutAppConfig.isCoverDisplay(dm.widthPixels, dm.heightPixels)
                || CoverRotation.landscape(display.getRotation()) && !NativeModule.config().landscapeCompat)
            throw new IllegalStateException("Cover is no longer active");
    }

    private static void failed(Context context, Exception error) {
        NativeLog.warning("Navigation was not executed", error);
        MAIN.post(() -> android.widget.Toast.makeText(context, "操作未执行，请查看模块日志", android.widget.Toast.LENGTH_SHORT).show());
    }
    static void key(Context context, int displayId, int code) {
        Context app = context.getApplicationContext();
        ACTIONS.execute(() -> {
            try {
                checkCover(app, displayId);
                // InputManager.getInstance() was removed on this actual SDK-37 device.
                Class<?> input = Class.forName("android.hardware.input.InputManagerGlobal");
                Object manager = input.getMethod("getInstance").invoke(null);
                Method inject = input.getMethod("injectInputEvent", InputEvent.class, int.class);
                Method setDisplay = InputEvent.class.getDeclaredMethod("setDisplayId", int.class);
                long downTime = SystemClock.uptimeMillis();
                boolean down = send(manager, inject, setDisplay, displayId, code, KeyEvent.ACTION_DOWN, downTime);
                boolean up = down && send(manager, inject, setDisplay, displayId, code, KeyEvent.ACTION_UP, downTime);
                if (!down || !up) throw new IllegalStateException("Input dispatcher rejected key " + code);
                NativeLog.info("Navigation executed: key=" + code + " display=" + displayId + " DOWN/UP accepted");
            } catch (Exception error) {
                failed(app, error);
            }
        });
    }
    private static boolean send(Object manager, Method inject, Method target, int display, int code, int action, long down) throws Exception {
        KeyEvent key = new KeyEvent(down, SystemClock.uptimeMillis(), action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY, InputDevice.SOURCE_KEYBOARD);
        target.invoke(key, display);
        return Boolean.TRUE.equals(inject.invoke(manager, key, 1)); // WAIT_FOR_RESULT, off the UI thread.
    }
}
