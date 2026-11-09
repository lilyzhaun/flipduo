package com.flipduo.module;

import android.app.KeyguardManager;
import android.content.*;
import android.graphics.*;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.util.*;
import android.view.*;

/** Disabled experimental edge sampling. It does not provide frame-synchronous immersion. */
final class ContentColorSource {
    static final String ACTION = "com.flipduo.module.CONTENT_COLORS";
    static final String CONTROL = "com.flipduo.module.COLOR_CAPTURE_CONTROL";
    private static final boolean AUTO_COLORS = false; // Rejected on-device: lag, stretched lines and flicker. Do not auto-enable.
    private static ContentColorSource instance;
    private final Context context;
    private final HandlerThread thread = new HandlerThread("FlipDuoColors", android.os.Process.THREAD_PRIORITY_BACKGROUND);
    private Handler worker;
    private CaptureBackend backend;
    private int failures, samples;
    private volatile boolean enabled = AUTO_COLORS;

    static void start(Context context) {
        if (instance != null) return;
        ContentColorSource source = new ContentColorSource(context);
        source.thread.start(); source.worker = new Handler(source.thread.getLooper());
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                if (intent.getBooleanExtra("probe", false)) {
                    source.worker.post(() -> {
                        NativeModule.info("Native colour API probe begin (8x40, no file)");
                        try {
                            Bitmap bitmap = new CaptureBackend().region(0, new Rect(6, 6, 14, 46));
                            try { NativeModule.info("Native colour API probe succeeded: " + bitmap.getWidth() + "x" + bitmap.getHeight()); }
                            finally { bitmap.recycle(); }
                        } catch (Exception error) { NativeModule.warning("Native colour API probe failed", error); }
                    });
                } else {
                    boolean enable = intent.getBooleanExtra("enable", false);
                    source.worker.post(() -> {
                        source.enabled = enable; source.failures = 0;
                        source.worker.removeCallbacks(source.sample);
                        if (source.enabled) source.worker.post(source.sample);
                        NativeModule.info("Content edge sampling enabled=" + source.enabled);
                    });
                }
            }
        }, new IntentFilter(CONTROL), StatusWire.PERMISSION, new Handler(Looper.getMainLooper()), Context.RECEIVER_EXPORTED);
        instance = source;
        NativeModule.info("Experimental edge sampling default=" + source.enabled + "; frame-synchronous immersion is not implemented");
        if (source.enabled) source.worker.post(source.sample);
    }
    private ContentColorSource(Context context) { this.context = context; }

    private final Runnable sample = new Runnable() {
        @Override public void run() {
            try {
                if (!enabled) return;
                Display display = context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
                DisplayMetrics dm = new DisplayMetrics(); display.getRealMetrics(dm);
                if (!context.getSystemService(PowerManager.class).isInteractive() || context.getSystemService(KeyguardManager.class).isKeyguardLocked()
                        || dm.widthPixels != 1208 || dm.heightPixels != 1392) { failures = 0; return; }
                if (backend == null) backend = new CaptureBackend();
                int rotation = display.getRotation();
                if (rotation != 0 && rotation != 2) return;
                // Sample inside the main app, away from this module's native column and its seam.
                Rect edge = rotation == 2 ? new Rect(404, 0, 412, 1392) : new Rect(796, 0, 804, 1392);
                Bitmap strip = backend.region(display.getDisplayId(), edge);
                int[] colors;
                try { colors = ColorProfile.from(strip); } finally { strip.recycle(); }
                context.sendBroadcast(new Intent(ACTION).setPackage("com.miui.fliphome").putExtra("colors", colors)
                        .putExtra("rotation", rotation).putExtra("time", SystemClock.elapsedRealtime()), StatusWire.PERMISSION);
                failures = 0; samples++;
                if (samples == 1 || samples % 30 == 0) NativeModule.info("Content edge colours delivered, samples=" + samples + " middle=" + Integer.toHexString(colors[36]));
            } catch (SecurityException protectedContent) {
                failures = 0; // Protected content is intentionally skipped; the next ordinary app must be sampled again.
            } catch (Exception error) {
                failures = Math.min(4, failures + 1); NativeModule.warning("Edge colour capture unavailable; retrying with backoff", error);
            } finally { if (enabled) worker.postDelayed(this, 1000L + failures * 1000L); }
        }
    };
}
