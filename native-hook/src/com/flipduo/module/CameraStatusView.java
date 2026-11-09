// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.app.KeyguardManager;
import android.content.*;
import android.graphics.*;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.view.*;

/** Read-only pixels in the EXISTING watch window. Never contributes to its touchable region. */
final class CameraStatusView extends View {
    static final int TIMER_COLOR = CameraColors.TIMER, CHARGE_COLOR = CameraColors.CHARGE, PULSE_COLOR = CameraColors.NOTIFICATION;
    static final int PROGRESS_COLOR = CameraColors.PROGRESS;
    static final float RADIUS = 166, ICON_SIZE = 60;
    private NativeCameraGeometry geometry;
    private CameraStatusState state = new CameraStatusState();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private boolean bottom, running, listening;
    private int rotation;
    private Runnable rotationChanged;
    private int cameraInset;
    private boolean animations = true, featureEnabled = true;
    private boolean secondRing;
    private final CameraSecondRing.ObservedTail clockTail = new CameraSecondRing.ObservedTail();
    private boolean mainRing = true, lowerRing = true;
    private boolean notifications = true, breathingLight = true;
    private boolean debugRings;
    private int timerColor = TIMER_COLOR, progressColor = PROGRESS_COLOR, chargeColor = CHARGE_COLOR;
    private int timerBreathColor = TIMER_COLOR, notificationColor = PULSE_COLOR;
    private int mainStroke = DockConfig.CAMERA_STROKE_DEFAULT, lowerStroke = DockConfig.CAMERA_STROKE_DEFAULT;
    private int overflowInk = Color.WHITE;
    private PorterDuffColorFilter iconTone = new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
    private int battery = -1;
    private boolean charging;
    /** F03 target percent on the lower ring, or -1 when the option is off. */
    private int chargeTarget = -1;
    /** F04 visual focus: quiet ordinary new-notification pulses; presentation only. */
    private boolean focusQuiet;
    /** F03 step 2: one light confirmation per plug-in session; arrivalAt is the start of the visible breath. */
    private final ChargeArrival arrival = new ChargeArrival();
    private boolean arrivalEnabled;
    private long arrivalAt;
    private long activeSince, lastPulse, pulseStart;
    private final Runnable frame = () -> { invalidate(); schedule(); };
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            if (CameraStatusState.STATE.equals(i.getAction())) {
                if (!EventWire.from(this, c, "com.android.systemui")
                        || !android.os.Process.myUserHandle().equals(i.getParcelableExtra("user", UserHandle.class))) return;
                apply(CameraStatusState.decode(i));
            } else if (Intent.ACTION_BATTERY_CHANGED.equals(i.getAction())) {
                int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                battery = level < 0 ? -1 : Math.max(0, Math.min(100, Math.round(level * 100f / Math.max(1, scale))));
                int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
                boolean plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
                // Visible means the tick itself is on screen now; otherwise the crossing is consumed silently.
                boolean visible = running && ChargeTarget.visible(lowerRing, chargeTarget >= 0, false, charging, battery);
                if (arrival.sample(plugged, battery, arrivalEnabled ? chargeTarget : -1, visible)) arrivalAt = SystemClock.elapsedRealtime();
            }
            if (Intent.ACTION_TIME_CHANGED.equals(i.getAction())) clockTail.reset();
            refresh();
        }
    };
    private final DisplayManager.DisplayListener displays = new DisplayManager.DisplayListener() {
        public void onDisplayAdded(int id) { refresh(); }
        public void onDisplayRemoved(int id) { refresh(); }
        public void onDisplayChanged(int id) { refresh(); }
    };
    CameraStatusView(Context context) {
        this(context, NativeCameraGeometry.forDevice(Build.DEVICE));
    }
    private final NativeCameraGeometry baseGeometry;
    CameraStatusView(Context context, NativeCameraGeometry base) {
        super(context); setClickable(false); setLongClickable(false); setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        baseGeometry = base; geometry = base;
        NativeLog.info("v6.76 camera indicators: read-only cover display; measured=" + (base == NativeCameraGeometry.BIXI));
    }
    void foreground(int ink) {
        if (overflowInk == ink) return;
        overflowInk = ink; iconTone = new PorterDuffColorFilter(ink, PorterDuff.Mode.SRC_IN); invalidate();
    }
    void rotationChanges(Runnable changed) { rotationChanged = changed; }
    void configure(boolean bottom, DockConfig config) {
        configure(bottom ? 2 : 0, config);
    }
    void configure(int rotation, DockConfig config) {
        if (secondRing != config.cameraSecondRing || mainRing != config.cameraMainRing
                || debugRings != config.cameraDebugRings || animations != config.animations) clockTail.reset();
        animations = config.animations; featureEnabled = config.enabled;
        cameraInset = NativeCameraGeometry.windowInset(config, baseGeometry);
        secondRing = config.cameraSecondRing;
        mainRing = config.cameraMainRing; lowerRing = config.cameraLowerRing;
        if (notifications != config.cameraNotifications || breathingLight != config.cameraBreathing || focusQuiet != config.visualFocus) {
            pulseStart = 0; lastPulse = state.pulseAt; // No replay of alerts arriving while their display was disabled or quietened.
        }
        notifications = config.cameraNotifications; breathingLight = config.cameraBreathing; focusQuiet = config.visualFocus;
        debugRings = config.cameraDebugRings; mainStroke = config.cameraMainStroke; lowerStroke = config.cameraLowerStroke;
        geometry = baseGeometry.adjusted(config);
        timerColor = config.cameraTimerColor; progressColor = config.cameraProgressColor; chargeColor = config.cameraChargeColor;
        chargeTarget = config.cameraChargeTarget ? ChargeTarget.clamp(config.cameraChargeTargetPercent) : -1;
        arrivalEnabled = chargeTarget >= 0 && config.cameraChargeArrival;
        if (!arrivalEnabled) arrivalAt = 0;
        timerBreathColor = config.cameraTimerBreathColor; notificationColor = config.cameraNotificationColor;
        alignRotation(rotation);
        alignLayout(); // Insets can change even when orientation does not.
        refresh();
    }
    private boolean alignRotation(int actual) {
        if (rotation == actual) return false;
        rotation = actual; bottom = actual == 2;
        clockTail.reset(); pulseStart = 0; activeSince = SystemClock.elapsedRealtime();
        alignLayout();
        return true;
    }
    private void alignLayout() {
        if (!(getLayoutParams() instanceof android.widget.FrameLayout.LayoutParams params)) return;
        int shortSide = DockSpec.WIDTH + cameraInset;
        int width = CoverRotation.landscape(rotation) ? CoverGeometry.HEIGHT : shortSide;
        int height = CoverRotation.landscape(rotation) ? shortSide : CoverGeometry.HEIGHT;
        int gravity = CoverRotation.gravity(rotation);
        if (params.width != width || params.height != height || params.gravity != gravity) {
            params.width = width; params.height = height; params.gravity = gravity;
            setLayoutParams(params);
        }
    }
    void apply(CameraStatusState next) {
        long now = SystemClock.elapsedRealtime();
        boolean timerOwnsDot = state.ticking(now) || next.ticking(now) || state.stopwatchRunning || next.stopwatchRunning;
        state = next;
        if (VisualFocus.dropPulse(notifications, breathingLight, timerOwnsDot, focusQuiet, next.pulseUrgent))
            pulseStart = 0; // Hidden/suppressed/quietened notifications are consumed, not replayed.
        else if (running && next.pulseAt > lastPulse && next.pulseAt >= activeSince && now - next.pulseAt < CameraStatusState.PULSE_MS)
            pulseStart = next.pulseAt; // Latest notification wins, no queue, no old animation-end callback.
        lastPulse = next.pulseAt;
        if (next.count == 0) pulseStart = 0;
        invalidate(); schedule();
    }
    private boolean allowed(Display d) {
        if (!featureEnabled || !isAttachedToWindow() || !isShown() || d == null || d.getState() != Display.STATE_ON
                || getContext().getSystemService(KeyguardManager.class).isKeyguardLocked()) return false;
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics(); d.getRealMetrics(metrics);
        return CutoutAppConfig.isCoverDisplay(metrics.widthPixels, metrics.heightPixels);
    }
    private void refresh() {
        Display display = getDisplay();
        boolean next = allowed(display);
        // This child has its own DisplayListener. Do not rely on the OEM/parent callback order,
        // especially for 180 degrees, where the dimensions do not change at all.
        if (next && alignRotation(display.getRotation()) && rotationChanged != null) rotationChanged.run();
        if (next != running) {
            clockTail.reset();
            running = next; pulseStart = 0; activeSince = SystemClock.elapsedRealtime();
            if (next) getContext().sendBroadcast(new Intent(CameraStatusState.REQUEST).setPackage("com.android.systemui"), StatusWire.PERMISSION);
        }
        invalidate(); schedule();
    }
    private void schedule() {
        removeCallbacks(frame);
        if (!running) return;
        long now = SystemClock.elapsedRealtime();
        long delay = nextDelay(now, System.currentTimeMillis(), animations && android.animation.ValueAnimator.areAnimatorsEnabled());
        if (delay == 0) postOnAnimation(frame);
        else if (delay > 0) postDelayed(frame, delay);
    }
    long nextDelay(long now, long wallTime, boolean animate) {
        boolean clock = mainRing && secondRing && !debugRings && !state.activityOwnsRing(now);
        boolean visibleTimer = (mainRing && !debugRings || breathingLight) && state.ticking(now);
        boolean visibleStopwatch = (mainRing && !debugRings || breathingLight) && state.stopwatchRunning;
        boolean arriving = arrivalAt > 0 && now >= arrivalAt && now - arrivalAt < ChargeArrival.SHOW_MS;
        if (animate && (visibleTimer || visibleStopwatch || (notifications && breathingLight && state.count > 0 && pulseStart > 0 && now >= pulseStart
                && now - pulseStart < CameraStatusState.PULSE_MS) || clock || arriving)) return 0;
        if (arriving) return ChargeArrival.SHOW_MS - (now - arrivalAt); // reduced motion: static emphasis, one redraw at the end
        if (visibleTimer) return Math.min(1000, state.deadline - now);
        if (visibleStopwatch) return 1000 - Math.floorMod(state.stopwatchTime(now), 1000L);
        if (clock) return 1000 - Math.floorMod(wallTime, 1000L);
        return -1;
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow(); activeSince = SystemClock.elapsedRealtime();
        getContext().registerReceiver(receiver, new IntentFilter(CameraStatusState.STATE), StatusWire.PERMISSION, null, Context.RECEIVER_EXPORTED);
        IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        f.addAction(Intent.ACTION_SCREEN_OFF); f.addAction(Intent.ACTION_SCREEN_ON); f.addAction(Intent.ACTION_USER_PRESENT);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        getContext().registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        getContext().getSystemService(DisplayManager.class).registerDisplayListener(displays, null);
        listening = true; refresh();
    }
    @Override protected void onDetachedFromWindow() {
        clockTail.reset();
        running = false; pulseStart = 0; arrivalAt = 0; removeCallbacks(frame);
        if (listening) {
            getContext().unregisterReceiver(receiver);
            getContext().getSystemService(DisplayManager.class).unregisterDisplayListener(displays);
            listening = false;
        }
        super.onDetachedFromWindow();
    }
    @Override public void onVisibilityAggregated(boolean visible) { super.onVisibilityAggregated(visible); refresh(); }
    @Override protected void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config); refresh();
    }
    @Override protected void onDraw(Canvas canvas) {
        if (!running) return;
        long now = SystemClock.elapsedRealtime(), wallTime = System.currentTimeMillis();
        boolean animate = animations && android.animation.ValueAnimator.areAnimatorsEnabled();
        clockTail.frame(wallTime, now, animate && mainRing && secondRing && !debugRings && !state.activityOwnsRing(now));
        renderFrame(canvas, state, rotation, battery, charging, now, pulseStart, animate, wallTime, true);
    }
    // The same painter is used by isolated two-direction pixel tests and generated synthetic previews.
    void render(Canvas canvas, CameraStatusState s, boolean rotated, int battery, boolean charging, long now, long pulse, boolean animate) {
        render(canvas, s, rotated, battery, charging, now, pulse, animate, System.currentTimeMillis());
    }
    void render(Canvas canvas, CameraStatusState s, boolean rotated, int battery, boolean charging, long now, long pulse, boolean animate, long wallTime) {
        render(canvas, s, rotated ? 2 : 0, battery, charging, now, pulse, animate, wallTime);
    }
    void render(Canvas canvas, CameraStatusState s, int rotation, int battery, boolean charging, long now, long pulse, boolean animate, long wallTime) {
        renderFrame(canvas, s, rotation, battery, charging, now, pulse, animate, wallTime, false);
    }
    private void renderFrame(Canvas canvas, CameraStatusState s, int rotation, int battery, boolean charging,
            long now, long pulse, boolean animate, long wallTime, boolean live) {
        if (geometry == null) return;
        canvas.save();
        // Extend only the existing read-only child towards the app. Screen-edge clipping remains physical.
        if (rotation == 0) canvas.translate(cameraInset, 0);
        else if (rotation == 3) canvas.translate(0, cameraInset);
        paint.setAlpha(255); // Hidden icons no longer reset this paint between ring and breathing frames.
        float x = CoverRotation.cameraX(rotation, geometry.x, geometry.mainY);
        float lowerX = CoverRotation.cameraX(rotation, geometry.lowerX, geometry.lowerY);
        float mainY = CoverRotation.cameraY(rotation, geometry.x, geometry.mainY);
        float lowerY = CoverRotation.cameraY(rotation, geometry.lowerX, geometry.lowerY);
        // Centers follow physical lenses; arc direction follows the upright logical display.
        // Rotating this angle backwards would pin the start to the old physical "top".
        float origin = -90;
        float progress = s.ringProgress(now);
        if (mainRing && (debugRings || s.activityOwnsRing(now))) {
            boolean countUp = s.progress(now) == 0 && s.stopwatch;
            int color = debugRings || s.progress(now) > 0 || s.stopwatch ? timerColor : progressColor;
            ring(canvas, x, mainY, geometry.mainRadius, mainStroke, debugRings ? 1 : progress, color, origin);
            if (!debugRings && countUp && s.stopwatchRunning && animate) arc(canvas, origin,
                    CameraSecondRing.tailStart(s.stopwatchTime(now)), CameraSecondRing.tailSweep(s.stopwatchTime(now)), timerColor);
        }
        else if (mainRing && secondRing) {
            // Head keeps wall-clock time while the previous minute's tail wipes from start to end.
            ring(canvas, x, mainY, geometry.mainRadius, mainStroke, CameraStatusState.seconds(wallTime), timerColor, origin);
            if (animate) arc(canvas, origin, live ? clockTail.start() : CameraSecondRing.tailStart(wallTime),
                    live ? clockTail.sweep() : CameraSecondRing.tailSweep(wallTime), timerColor);
        }
        if (lowerRing && (debugRings || charging && battery >= 0)) ring(canvas, lowerX, lowerY, geometry.lowerRadius, lowerStroke,
                debugRings ? 1 : battery / 100f, chargeColor, origin);
        if (ChargeTarget.visible(lowerRing, chargeTarget >= 0, debugRings, charging, battery)) {
            // Display-only target mark across the ring band, in the foreground ink so it stays visible over the arc.
            float[] tick = ChargeTarget.tick(lowerX, lowerY, geometry.lowerRadius, lowerStroke, chargeTarget, origin);
            long sinceArrival = live && arrivalAt > 0 ? now - arrivalAt : -1;
            float boost = sinceArrival >= 0 && sinceArrival < ChargeArrival.SHOW_MS ? (animate ? CameraStatusState.breath(sinceArrival) : 1f) : 0f;
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(ChargeTarget.WIDTH * (1 + 1.5f * boost)); paint.setStrokeCap(Paint.Cap.BUTT);
            paint.setColor(overflowInk); canvas.drawLine(tick[0], tick[1], tick[2], tick[3], paint);
            paint.setStyle(Paint.Style.FILL);
        }
        x = CoverRotation.cameraX(rotation, geometry.gapX(), geometry.gapY());
        float y = CoverRotation.cameraY(rotation, geometry.gapX(), geometry.gapY());
        boolean horizontal = CoverRotation.landscape(rotation);
        float dx = horizontal ? 0 : 140, dy = horizontal ? (rotation == 1 ? -140 : 140) : 0;
        if (notifications && s.count > 0 && s.left != null) image(canvas, s.left, x - dx, y - dy, s.leftMonochrome);
        if (notifications && s.count > 1 && s.right != null) image(canvas, s.right, x + dx, y + dy, s.rightMonochrome);
        boolean breathing = notifications && breathingLight && animate && pulse > 0 && now >= pulse && now - pulse < CameraStatusState.PULSE_MS && s.count > 0;
        paint.setStyle(Paint.Style.FILL);
        if (breathingLight && (s.ticking(now) || s.stopwatchRunning)) {
            paint.setColor(timerBreathColor); paint.setAlpha(CameraColors.pulseAlpha(timerBreathColor, animate ? CameraStatusState.breath(now) : 1));
            canvas.drawCircle(x, y, 6, paint); paint.setAlpha(255);
        } else if (breathing) {
            paint.setColor(notificationColor); paint.setAlpha(CameraColors.pulseAlpha(notificationColor, CameraStatusState.pulse(now - pulse)));
            canvas.drawCircle(x, y, 6, paint); paint.setAlpha(255);
        } else if (notifications && s.count > 2) {
            paint.setColor(overflowInk);
            for (int i = -1; i <= 1; i++) canvas.drawCircle(x + (horizontal ? 0 : 9 * i), y + (horizontal ? 9 * i : 0), 2.7f, paint);
        }
        canvas.restore();
    }
    private void image(Canvas canvas, Bitmap bitmap, float x, float y, boolean monochrome) {
        paint.setColor(Color.WHITE); paint.setAlpha(255);
        rect.set(x - ICON_SIZE / 2, y - ICON_SIZE / 2, x + ICON_SIZE / 2, y + ICON_SIZE / 2);
        paint.setColorFilter(monochrome ? iconTone : null);
        canvas.drawBitmap(bitmap, null, rect, paint); // Only a declared neutral glyph follows the foreground.
        paint.setColorFilter(null); // Never leak the left glyph's tint into the right icon, dots or next frame.
    }
    private void ring(Canvas canvas, float x, float y, float radius, int stroke, float fraction, int color, float origin) {
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(stroke); paint.setStrokeCap(Paint.Cap.ROUND);
        // Thicken inward: keep the original 6px stroke's outer edge and all existing safe bounds.
        radius += (DockConfig.CAMERA_STROKE_DEFAULT - stroke) / 2f;
        rect.set(x - radius, y - radius, x + radius, y + radius);
        arc(canvas, origin, 0, 360 * fraction, color);
    }
    private void arc(Canvas canvas, float origin, float start, float sweep, int color) {
        if (sweep > 0) {
            paint.setStyle(Paint.Style.STROKE); paint.setColor(color);
            canvas.drawArc(rect, origin + start, sweep, false, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }
}
