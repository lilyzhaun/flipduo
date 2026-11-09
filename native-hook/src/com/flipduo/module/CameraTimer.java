// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.app.Notification;
import android.os.Bundle;
import org.json.*;
import java.util.Iterator;

/** Matches NotificationTimeKeeper/TimerSystemCurrentUtils: -1/-3 run, -2/-4 pause. All values are milliseconds. */
final class CameraTimer {
    final int type;
    final long total, when, systemCurrent;
    CameraTimer(int type, long total, long when, long systemCurrent) {
        this.type = type; this.total = total; this.when = when; this.systemCurrent = systemCurrent;
    }
    boolean paused() { return type == -2 || type == -4; }
    long pausedRemaining() { return Math.max(0, when - systemCurrent); }
    long deadline(long now, long wall, long chronometerBase) {
        if (paused()) return 0;
        return when > 0 ? now + Math.max(0, when - wall) : chronometerBase;
    }
    private static boolean supported(int type, long total) {
        return type >= -4 && type <= -1 && total > 0 && total <= 7 * 86400000L;
    }
    static CameraTimer read(Notification notification) {
        return read(notification, false);
    }
    static CameraTimer countUp(Notification notification) { return read(notification, true); }
    private static boolean supported(int type, long total, boolean countUp) {
        return countUp ? type >= 1 && type <= 4 : supported(type, total);
    }
    private static CameraTimer read(Notification notification, boolean countUp) {
        if (notification == null) return null;
        Bundle e = notification.extras;
        int type = e.getInt("timerType", 0); long total = e.getLong("timerTotal", 0);
        if (supported(type, total, countUp)) return new CameraTimer(type, total, e.getLong("timerWhen", 0), e.getLong("timerSystemCurrent", 0));
        String json = e.getString("miui.focus.param");
        if (json == null || json.length() > 65536) return null;
        try { return find(new JSONObject(json), 0, new int[]{64}, countUp); }
        catch (JSONException invalid) { return null; }
    }
    private static CameraTimer find(Object node, int depth, int[] budget, boolean countUp) throws JSONException {
        if (depth > 8 || --budget[0] < 0) return null;
        if (node instanceof JSONObject object) {
            int type = object.optInt("timerType", 0); long total = object.optLong("timerTotal", 0);
            if (supported(type, total, countUp)) return new CameraTimer(type, total, object.optLong("timerWhen", 0), object.optLong("timerSystemCurrent", 0));
            Iterator<String> keys = object.keys();
            while (keys.hasNext() && budget[0] > 0) {
                Object value = object.opt(keys.next());
                if (value instanceof JSONObject || value instanceof JSONArray) {
                    CameraTimer found = find(value, depth + 1, budget, countUp); if (found != null) return found;
                }
            }
        } else if (node instanceof JSONArray array) for (int i = 0; i < array.length() && budget[0] > 0; i++) {
            CameraTimer found = find(array.opt(i), depth + 1, budget, countUp); if (found != null) return found;
        }
        return null;
    }
}
