// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.app.Notification;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.*;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.StatusBarNotification;
import android.widget.ImageView;
import java.lang.reflect.*;
import java.util.*;

/** Reuses the native collection and IconPack, never installs a notification listener service or launches an app. */
final class CameraStatusSource {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Context context;
    private static Object collection;
    private static final CameraStatusState state = new CameraStatusState();
    private static final CameraNotifications changes = new CameraNotifications();
    private static int lastUser = -1;
    private static boolean dirty = true;
    private static int lastTimerMode;
    private static String focusKey = "";
    private static List<Entry> progressEntries = List.of();
    private static List<Entry> visibleIcons = List.of();
    private static ImageView leftView, rightView;
    private static final Runnable publish = CameraStatusSource::publish;
    static void install(NativeModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> view = loader.loadClass("com.android.systemui.statusbar.StatusBarIconView");
        Method drawable = view.getDeclaredMethod("updateDrawable", boolean.class);
        module.hook(drawable).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            if (Boolean.TRUE.equals(result)) iconUpdated(chain.getThisObject());
            return result;
        });
        Class<?> manager = loader.loadClass("com.android.systemui.statusbar.notification.icon.IconManager");
        Class<?> entry = loader.loadClass("com.android.systemui.statusbar.notification.collection.NotificationEntry");
        int bindings = 0;
        for (Method method : bindingMethods(manager, entry)) {
            module.hook(method).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed(); iconUpdated(chain.getArg(0)); return result;
            });
            bindings++;
        }
        NativeLog.info("v6.72 camera icon lifecycle installed: drawable=1 bindings=" + bindings);
    }
    static List<Method> bindingMethods(Class<?> manager, Class<?> entry) {
        List<Method> result = new ArrayList<>();
        for (Method method : manager.getDeclaredMethods())
            if ((method.getName().equals("createIcons") || method.getName().equals("updateIcons"))
                    && method.getParameterCount() > 0 && method.getParameterTypes()[0] == entry) result.add(method);
        return result;
    }
    static void iconUpdated(Object owner) {
        Runnable update = () -> {
            boolean selected = owner != null && (owner == leftView || owner == rightView);
            if (!selected) for (Entry entry : visibleIcons) if (entry.source == owner) { selected = true; break; }
            if (selected) { dirty = true; changed(); }
        };
        if (Looper.myLooper() == MAIN.getLooper()) update.run(); else MAIN.post(update);
    }
    static void collection(Object value) { collection = value; dirty = true; changed(); }
    static void changed() { MAIN.removeCallbacks(publish); MAIN.post(publish); }
    static void timer(Notification notification, long deadline) {
        timer(notification, deadline, "");
    }
    static void timer(Notification notification, long deadline, String key) {
        boolean focusChanged = !focusKey.equals(key); focusKey = key;
        CameraTimer up = notification != null && !Notification.CATEGORY_CALL.equals(notification.category) ? CameraTimer.countUp(notification) : null;
        boolean stopwatch = up != null && up.when > 0;
        boolean upRunning = stopwatch && (up.type == 1 || up.type == 3);
        long now = SystemClock.elapsedRealtime();
        long elapsed = !stopwatch ? 0 : Math.max(0, (upRunning ? System.currentTimeMillis() : up.systemCurrent) - up.when);
        if (stopwatch && !upRunning && up.systemCurrent <= 0) stopwatch = false;
        boolean upChanged = state.stopwatch != stopwatch || state.stopwatchRunning != upRunning
                || stopwatch && Math.abs(state.stopwatchTime(now) - elapsed) > 100;
        state.stopwatch = stopwatch; state.stopwatchRunning = stopwatch && upRunning;
        state.stopwatchElapsed = elapsed; state.stopwatchSample = now;
        CameraTimer timer = CameraTimer.read(notification);
        long total = timer == null ? 0 : timer.total;
        boolean paused = timer != null && timer.paused();
        long remaining = paused && timer.when > 0 && timer.systemCurrent > 0 ? timer.pausedRemaining() : 0;
        long nextDeadline = timer == null ? 0 : timer.deadline(SystemClock.elapsedRealtime(), System.currentTimeMillis(), deadline);
        int mode = timer == null ? 0 : timer.type;
        if (mode != lastTimerMode) {
            lastTimerMode = mode;
            NativeLog.info("v6.52 camera timer mode=" + mode + " paused=" + paused + " total=" + total + " frozenRemaining=" + remaining);
        }
        if (!focusChanged && !upChanged && state.deadline == nextDeadline && state.total == total && state.paused == paused && state.pausedRemaining == remaining) return;
        state.deadline = nextDeadline; state.total = total; state.paused = paused; state.pausedRemaining = remaining; changed();
    }
    static void start(Context app) {
        if (context != null) return;
        context = app;
        app.registerReceiver(new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) { dirty = true; changed(); }
        }, new IntentFilter(CameraStatusState.REQUEST), StatusWire.PERMISSION, MAIN, Context.RECEIVER_EXPORTED);
        app.registerReceiver(new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) { changed(); }
        }, new IntentFilter("android.intent.action.USER_SWITCHED"), null, MAIN, Context.RECEIVER_NOT_EXPORTED);
        changed(); NativeLog.info("v6.72 camera status source ready; source Icon snapshots follow native binding completion");
    }
    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    record Entry(Object source, StatusBarNotification sbn, Ranking ranking) { }
    static List<Entry> appIcons(List<Entry> ranked) {
        Map<String, Entry> apps = new LinkedHashMap<>();
        for (Entry entry : ranked)
            apps.putIfAbsent(entry.sbn.getUserId() + ":" + entry.sbn.getPackageName(), entry);
        return new ArrayList<>(apps.values());
    }
    private static void publish() {
        if (context == null) return;
        try {
            int user = (int) Class.forName("android.app.ActivityManager").getDeclaredMethod("getCurrentUser").invoke(null);
            if (user != lastUser) {
                changes.reset(); state.pulseAt = 0; dirty = true;
                visibleIcons = List.of(); leftView = rightView = null;
                if (lastUser >= 0) {
                    focusKey = ""; progressEntries = List.of(); state.stopwatch = state.stopwatchRunning = false;
                    state.activityProgress = -1; state.deadline = state.total = state.pausedRemaining = 0; state.paused = false;
                }
                lastUser = user;
            }
            if (collection != null && dirty) {
                List<Entry> entries = new ArrayList<>();
                List<Entry> all = new ArrayList<>();
                for (Object entry : (Collection<?>) field(collection, "mReadOnlyNotificationSet")) {
                    StatusBarNotification sbn = (StatusBarNotification) field(entry, "mSbn");
                    Ranking rank = (Ranking) field(entry, "mRanking");
                    if (sbn.getUserId() != user || rank.isSuspended() || rank.getImportance() == 0
                            || (sbn.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
                    all.add(new Entry(entry, sbn, rank));
                    if (!CameraNotifications.eligible(sbn, user)) continue;
                    entries.add(new Entry(entry, sbn, rank));
                }
                entries.sort(Comparator.comparingInt((Entry e) -> e.ranking.getRank()).thenComparing(e -> e.sbn.getKey()));
                all.sort(Comparator.comparingInt((Entry e) -> e.ranking.getRank()).thenComparing(e -> e.sbn.getKey()));
                progressEntries = all;
                Map<String, String> fingerprints = new HashMap<>(); Set<String> onlyOnce = new HashSet<>();
                for (Entry entry : entries) {
                    fingerprints.put(entry.sbn.getKey(), CameraNotifications.fingerprint(entry.sbn.getNotification()));
                    if ((entry.sbn.getNotification().flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0) onlyOnce.add(entry.sbn.getKey());
                }
                Set<String> fresh = changes.changed(fingerprints, onlyOnce);
                if (!fresh.isEmpty()) {
                    state.pulseAt = SystemClock.elapsedRealtime();
                    // F04: only a boolean leaves SystemUI; same urgency rule as the event island.
                    boolean urgent = false;
                    for (Entry entry : entries) if (fresh.contains(entry.sbn.getKey())) {
                        Notification n = entry.sbn.getNotification();
                        urgent |= FocusEventBridge.isUrgent(n, CameraTimer.read(n) != null);
                    }
                    state.pulseUrgent = urgent;
                }
                // Display groups by app; alert detection above remains per notification/message version.
                List<Entry> icons = appIcons(entries);
                visibleIcons = icons.subList(0, Math.min(2, icons.size()));
                leftView = icons.isEmpty() ? null : iconView(icons.get(0));
                rightView = icons.size() < 2 ? null : iconView(icons.get(1));
                state.count = icons.size();
                state.left = icons.isEmpty() ? null : icon(icons.get(0));
                state.right = icons.size() < 2 ? null : icon(icons.get(1));
                state.leftMonochrome = CameraStatusState.monochrome(state.left);
                state.rightMonochrome = CameraStatusState.monochrome(state.right);
                if (state.count == 0) { state.pulseAt = 0; state.pulseUrgent = false; }
                dirty = false;
            }
            state.activityProgress = selectProgress(progressEntries, focusKey);
            UserHandle handle = UserHandle.getUserHandleForUid(user * 100000);
            Context userContext = (Context) Context.class.getDeclaredMethod("createContextAsUser", UserHandle.class, int.class).invoke(context, handle, 0);
            EventWire.send(userContext, state.encode().putExtra("user", handle));
        } catch (ReflectiveOperationException | RuntimeException error) {
            NativeLog.warning("Camera status source unavailable; existing event island retained", error);
        }
    }
    static float selectProgress(List<Entry> ranked, String focused) {
        float first = -1;
        for (Entry entry : ranked) {
            float value = CameraProgress.read(entry.sbn.getNotification());
            if (value < 0) continue;
            if (entry.sbn.getKey().equals(focused)) return value;
            if (first < 0) first = value;
        }
        return first;
    }
    private static Bitmap icon(Entry entry) throws ReflectiveOperationException {
        ImageView view = iconView(entry);
        Icon source = sourceIcon(view);
        // The current Icon data can already be colored while a legacy ImageView still holds an old white glyph.
        Drawable drawable = source == null ? null : source.loadDrawable(context);
        String origin = drawable != null ? "source-icon:" + source.getType() : "view-drawable";
        if (drawable == null && view != null) drawable = view.getDrawable();
        if (drawable == null && entry.sbn != null) {
            Icon small = entry.sbn.getNotification().getSmallIcon();
            if (small == null) return null;
            drawable = small.loadDrawable(context); origin = "small-icon:" + small.getType();
        }
        if (drawable == null) return null;
        Drawable.ConstantState saved = drawable.getConstantState();
        if (saved == null) return null; // Never mutate the native drawable's bounds/tint.
        Drawable copy = saved.newDrawable(context.getResources()).mutate();
        copy.setTintList(null); copy.clearColorFilter();
        Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        copy.setBounds(0, 0, 64, 64); copy.draw(new Canvas(bitmap));
        if (entry.sbn != null) NativeLog.info("v6.72 camera icon package=" + entry.sbn.getPackageName()
                + " origin=" + origin + " drawable=" + drawable.getClass().getSimpleName());
        return bitmap;
    }
    private static ImageView iconView(Entry entry) throws ReflectiveOperationException {
        Object pack = field(entry.source, "mIcons");
        return pack == null ? null : (ImageView) field(pack, "mStatusBarIcon");
    }
    private static Icon sourceIcon(ImageView view) throws ReflectiveOperationException {
        if (view == null) return null;
        Method source;
        try { source = view.getClass().getMethod("getSourceIcon"); }
        catch (NoSuchMethodException legacy) { return null; }
        return (Icon) source.invoke(view);
    }
}
