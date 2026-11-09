// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.content.Intent;
import android.graphics.Bitmap;

/** Display-only bounded payload: no notification text, keys, intents or app-defined parcelables. */
final class CameraStatusState {
    static final String STATE = "com.flipduo.module.CAMERA_STATUS", REQUEST = "com.flipduo.module.CAMERA_STATUS_REQUEST";
    static final long CYCLE_MS = 1600, PULSE_MS = 3 * CYCLE_MS;
    int count;
    Bitmap left, right;
    boolean leftMonochrome, rightMonochrome;
    long pulseAt, deadline, total;
    /** F04: the latest pulse came from an urgent notification (boolean only, no content). */
    boolean pulseUrgent;
    boolean paused;
    long pausedRemaining;
    boolean stopwatch, stopwatchRunning;
    long stopwatchElapsed, stopwatchSample;
    float activityProgress = -1;
    boolean activityOwnsRing(long now) { return progress(now) > 0 || stopwatch || activityProgress >= 0; }
    long stopwatchTime(long now) {
        return stopwatchElapsed + (stopwatchRunning ? Math.max(0, now - stopwatchSample) : 0);
    }
    float ringProgress(long now) {
        if (progress(now) > 0) return progress(now);
        if (stopwatch) return seconds(stopwatchTime(now));
        return activityProgress;
    }
    float progress(long now) { return paused ? remaining(0, pausedRemaining, total) : remaining(now, deadline, total); }
    boolean ticking(long now) { return !paused && total > 0 && deadline > now; }
    static float remaining(long now, long deadline, long total) {
        return total > 0 && deadline > now ? Math.min(1f, (deadline - now) / (float) total) : 0;
    }
    static float pulse(long age) {
        return age < 0 || age >= PULSE_MS ? 0 : breath(age);
    }
    static float breath(long now) {
        return (float) (.5 - .5 * Math.cos(2 * Math.PI * Math.floorMod(now, CYCLE_MS) / CYCLE_MS));
    }
    static float seconds(long wallTime) { return Math.floorMod(wallTime, 60000L) / 60000f; }
    /** A uniform neutral glyph with transparent surround, not arbitrary grayscale artwork. Snapshot-time only. */
    static boolean monochrome(Bitmap bitmap) {
        if (bitmap == null) return false;
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        boolean visible = false, transparent = false;
        int darkest = 255, lightest = 0;
        for (int color : pixels) {
            if (android.graphics.Color.alpha(color) < 32) { transparent = true; continue; }
            int r = android.graphics.Color.red(color), g = android.graphics.Color.green(color), b = android.graphics.Color.blue(color);
            if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 12) return false;
            visible = true;
            int gray = (r + g + b) / 3;
            darkest = Math.min(darkest, gray); lightest = Math.max(lightest, gray);
            if (lightest - darkest > 12) return false;
        }
        return visible && transparent;
    }
    Intent encode() {
        return new Intent(STATE).setPackage("com.miui.fliphome").putExtra("count", count)
                .putExtra("left", left).putExtra("right", right).putExtra("pulse", pulseAt).putExtra("pulseUrgent", pulseUrgent)
                .putExtra("leftMonochrome", leftMonochrome).putExtra("rightMonochrome", rightMonochrome)
                .putExtra("deadline", deadline).putExtra("total", total)
                .putExtra("paused", paused).putExtra("remaining", pausedRemaining)
                .putExtra("stopwatch", stopwatch).putExtra("stopwatchRunning", stopwatchRunning)
                .putExtra("stopwatchElapsed", stopwatchElapsed).putExtra("stopwatchSample", stopwatchSample)
                .putExtra("activityProgress", activityProgress);
    }
    static CameraStatusState decode(Intent intent) {
        CameraStatusState s = new CameraStatusState();
        s.count = Math.max(0, intent.getIntExtra("count", 0));
        s.left = bounded(intent.getParcelableExtra("left", Bitmap.class));
        s.right = bounded(intent.getParcelableExtra("right", Bitmap.class));
        s.leftMonochrome = s.left != null && intent.getBooleanExtra("leftMonochrome", false);
        s.rightMonochrome = s.right != null && intent.getBooleanExtra("rightMonochrome", false);
        s.pulseAt = Math.max(0, intent.getLongExtra("pulse", 0));
        s.pulseUrgent = s.pulseAt > 0 && intent.getBooleanExtra("pulseUrgent", false);
        s.deadline = Math.max(0, intent.getLongExtra("deadline", 0));
        s.total = Math.max(0, intent.getLongExtra("total", 0));
        s.paused = intent.getBooleanExtra("paused", false);
        s.pausedRemaining = Math.max(0, intent.getLongExtra("remaining", 0));
        s.stopwatch = intent.getBooleanExtra("stopwatch", false);
        s.stopwatchRunning = s.stopwatch && intent.getBooleanExtra("stopwatchRunning", false);
        s.stopwatchElapsed = Math.max(0, Math.min(7 * 86400000L, intent.getLongExtra("stopwatchElapsed", 0)));
        s.stopwatchSample = Math.max(0, intent.getLongExtra("stopwatchSample", 0));
        float progress = intent.getFloatExtra("activityProgress", -1);
        s.activityProgress = Float.isFinite(progress) && progress >= 0 && progress <= 1 ? progress : -1;
        return s;
    }
    private static Bitmap bounded(Bitmap bitmap) {
        return bitmap != null && !bitmap.isRecycled() && bitmap.getWidth() <= 64 && bitmap.getHeight() <= 64 ? bitmap : null;
    }
}
