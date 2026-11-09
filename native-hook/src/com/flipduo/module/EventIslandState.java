package com.flipduo.module;

import android.content.Intent;
import android.graphics.drawable.Icon;

final class EventIslandState {
    long generation;
    boolean available, focus, expanded, urgent;
    int notifications, events;
    /** F01: determinate progress 0..100 of the focused activity, -1 when absent (also for older SystemUI). */
    int progress = -1;
    String app = "", clock = "";
    Icon icon;

    static EventIslandState decode(Intent intent) {
        EventIslandState s = new EventIslandState();
        s.generation = intent.getLongExtra("generation", 0);
        s.available = intent.getBooleanExtra("available", false);
        s.focus = intent.getBooleanExtra("focus", false);
        s.expanded = intent.getBooleanExtra("expanded", false);
        s.urgent = intent.getBooleanExtra("urgent", false);
        s.notifications = Math.max(0, intent.getIntExtra("notifications", 0));
        s.events = Math.max(0, intent.getIntExtra("events", 0));
        s.app = intent.getStringExtra("app"); if (s.app == null) s.app = "";
        s.clock = intent.getStringExtra("clock"); if (s.clock == null) s.clock = "";
        int progress = intent.getIntExtra("progress", -1);
        s.progress = s.focus && progress >= 0 && progress <= 100 ? progress : -1;
        s.icon = intent.getParcelableExtra("icon", Icon.class);
        return s;
    }
}
