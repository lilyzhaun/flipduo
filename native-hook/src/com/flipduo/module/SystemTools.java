package com.flipduo.module;

import android.content.*;
import android.os.*;
import android.util.Log;
import java.lang.reflect.*;

/** Fixed native operations only. Both request and result are restricted to privileged peers. */
final class SystemTools {
    static final String REQUEST = "com.flipduo.module.SYSTEM_TOOL";
    static final String RESULT = "com.flipduo.module.SYSTEM_TOOL_RESULT";
    private static boolean registered;
    static void start(Context context) {
        if (registered) return;
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                String action = intent.getStringExtra("action");
                if ("mini_window".equals(action) || "mini_exit".equals(action)) {
                    MiniWindowBridge.request(context, intent); return; // Asynchronous; the slot state is broadcast separately.
                }
                boolean success = false;
                try {
                    if ("lock".equals(action)) {
                        PowerManager.class.getDeclaredMethod("goToSleep", long.class).invoke(context.getSystemService(PowerManager.class), SystemClock.uptimeMillis());
                    } else if ("screenshot".equals(action)) {
                        // The native SystemUI action retains normal secure-window and screenshot-editor policy.
                        context.sendBroadcast(new Intent("SYSTEM_ACTION_TAKE_SCREENSHOT").setPackage("com.android.systemui"));
                    } else if ("volume".equals(action)) {
                        // Show the native panel without increasing/decreasing volume or controlling media playback.
                        context.getSystemService(android.media.AudioManager.class).adjustVolume(
                                android.media.AudioManager.ADJUST_SAME, android.media.AudioManager.FLAG_SHOW_UI);
                    } else if ("torch".equals(action)) {
                        // Verified on this firmware: the native controller toggles its live state and retains thermal/camera restrictions.
                        context.sendBroadcast(new Intent("miui.intent.action.TOGGLE_TORCH").setPackage("com.android.systemui")
                                .putExtra("miui.intent.extra.IS_TOGGLE", true));
                    } else if ("power_menu".equals(action)) {
                        context.sendBroadcast(new Intent("SYSTEM_ACTION_POWER_DIALOG").setPackage("com.android.systemui"));
                    } else if ("previous_app".equals(action)) {
                        if (!PreviousApp.open(context, intent.getIntExtra("displayId", -1)))
                            throw new IllegalStateException("No previous ordinary application on the active unlocked cover");
                    } else if ("back".equals(action) || "home".equals(action) || "recents".equals(action)) {
                        int display = intent.getIntExtra("displayId", -1);
                        if ("recents".equals(action)) NativeNavigation.recents(context, display);
                        else NativeNavigation.key(context, display, "back".equals(action) ? android.view.KeyEvent.KEYCODE_BACK : android.view.KeyEvent.KEYCODE_HOME);
                    } else if ("notifications".equals(action) || "quick_settings".equals(action)) {
                        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "statusbar");
                        Object service = Class.forName("com.android.internal.statusbar.IStatusBarService$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
                        Class<?> type = Class.forName("com.android.internal.statusbar.IStatusBarService");
                        if ("notifications".equals(action)) type.getMethod("expandNotificationsPanel").invoke(service);
                        else type.getMethod("expandSettingsPanel", String.class).invoke(service, (Object) null);
                    } else throw new IllegalArgumentException("Unknown tool");
                    success = true; NativeModule.info("System tool executed: " + action);
                } catch (Exception error) { NativeModule.warning("System tool failed: " + action, error); }
                context.sendBroadcast(new Intent(RESULT).setPackage("com.miui.fliphome").putExtra("id", intent.getLongExtra("id", 0)).putExtra("success", success), StatusWire.PERMISSION);
            }
        }, new IntentFilter(REQUEST), StatusWire.PERMISSION, new Handler(Looper.getMainLooper()), Context.RECEIVER_EXPORTED);
        registered = true;
        NativeModule.info("v6.50 native tools registered: screenshot, volume, torch, power menu, previous app and navigation");
    }
}
