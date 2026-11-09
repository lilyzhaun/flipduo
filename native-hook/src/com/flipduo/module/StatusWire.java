package com.flipduo.module;

import android.content.Intent;

final class StatusWire {
    static final String ACTION = "com.flipduo.module.NATIVE_STATUS";
    static final String REQUEST = "com.flipduo.module.REQUEST_STATUS";
    static final String TINT = "com.flipduo.module.NATIVE_TINT";
    static final String PERMISSION = "android.permission.INJECT_EVENTS";
    static Intent encode(TrioState s) {
        return new Intent(ACTION).setPackage("com.miui.fliphome")
                .putExtra("battery", s.battery).putExtra("charging", s.charging).putExtra("saver", s.saver)
                .putExtra("wk", s.wifiKnown).putExtra("we", s.wifiEnabled).putExtra("wc", s.wifiConnected).putExtra("wl", s.wifiLevel)
                .putExtra("mk", s.mobileKnown).putExtra("sim", s.simPresent).putExtra("service", s.inService)
                .putExtra("air", s.airplane).putExtra("ml", s.mobileLevel).putExtra("rat", s.technology)
                .putExtra("tint_known", s.statusTintKnown).putExtra("status_tint", s.statusTint).putExtra("tint_display", s.statusTintDisplay)
                .putExtra("tint_at", s.statusTintAt);
    }
    static Intent tint(int color, int display, long at) {
        return new Intent(TINT).setPackage("com.miui.fliphome").addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra("tint_known", display >= 0).putExtra("status_tint", color)
                .putExtra("tint_display", display).putExtra("tint_at", at);
    }
    static boolean applyTint(TrioState state, Intent intent) {
        long at = intent.getLongExtra("tint_at", 0);
        if (at < state.statusTintAt) return false; // A queued full radio snapshot must not overwrite a newer color frame.
        state.statusTintKnown = intent.getBooleanExtra("tint_known", false);
        state.statusTint = intent.getIntExtra("status_tint", 0);
        state.statusTintDisplay = intent.getIntExtra("tint_display", -1);
        state.statusTintAt = at;
        return true;
    }
    static TrioState decode(Intent i) {
        TrioState s = new TrioState();
        s.battery = i.getIntExtra("battery", -1); s.charging = i.getBooleanExtra("charging", false); s.saver = i.getBooleanExtra("saver", false);
        s.wifiKnown = i.getBooleanExtra("wk", false); s.wifiEnabled = i.getBooleanExtra("we", false);
        s.wifiConnected = i.getBooleanExtra("wc", false); s.wifiLevel = i.getIntExtra("wl", -1);
        s.mobileKnown = i.getBooleanExtra("mk", false); s.simPresent = i.getBooleanExtra("sim", false);
        s.inService = i.getBooleanExtra("service", false); s.airplane = i.getBooleanExtra("air", false);
        s.mobileLevel = i.getIntExtra("ml", -1); s.technology = i.getStringExtra("rat");
        if (s.technology == null || !s.technology.matches("[2345]G")) s.technology = "";
        s.statusTintKnown = i.getBooleanExtra("tint_known", false);
        s.statusTint = i.getIntExtra("status_tint", 0);
        s.statusTintDisplay = i.getIntExtra("tint_display", -1);
        s.statusTintAt = i.getLongExtra("tint_at", 0);
        return s;
    }
    static TrioState decode(Intent intent, TrioState previous) {
        TrioState next = decode(intent);
        if (next.statusTintAt < previous.statusTintAt) {
            next.statusTint = previous.statusTint; next.statusTintDisplay = previous.statusTintDisplay;
            next.statusTintKnown = previous.statusTintKnown; next.statusTintAt = previous.statusTintAt;
        }
        return next;
    }
}
