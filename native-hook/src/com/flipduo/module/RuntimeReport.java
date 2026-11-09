package com.flipduo.module;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import java.util.concurrent.*;

/** Small on-change acknowledgements only; never transfers captured content or user app metadata. */
final class RuntimeReport {
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    static void state(Context context, TrioState state) {
        Bundle values = StatusWire.encode(state).getExtras();
        WORK.execute(() -> {
            try {
                Bundle b = new Bundle(); b.putString("host", "com.android.systemui".equals(context.getPackageName()) ? "systemui" : "fliphome"); b.putBundle("state", values);
                context.getContentResolver().call(Uri.parse("content://com.flipduo.module.runtime"), "status", null, b);
            } catch (Exception error) { NativeLog.warning("Preview status unavailable", error); }
        });
    }
    static void applied(Context context, String host, DockConfig config) {
        Context app = context.getApplicationContext();
        String caps = HostCapabilities.encode();
        WORK.execute(() -> {
            try {
                Bundle b = new Bundle(); b.putString("host", host); b.putInt("revision", config.revision);
                b.putBoolean("enabled", config.enabled); b.putLong("time", System.currentTimeMillis());
                // Compile-time constants of this module build, not the host Context's package version.
                b.putInt("module", BuildConfig.VERSION_CODE); b.putString("module_name", BuildConfig.VERSION_NAME);
                b.putInt("pid", android.os.Process.myPid()); b.putLong("process_start", android.os.Process.getStartElapsedRealtime());
                b.putString("caps", caps);
                app.getContentResolver().call(Uri.parse("content://com.flipduo.module.runtime"), "report", null, b);
            } catch (Exception error) { NativeLog.warning("Settings acknowledgement unavailable", error); }
        });
    }
}
