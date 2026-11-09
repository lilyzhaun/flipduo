// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.app.Notification;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Notification fingerprints stay in SystemUI. Initial snapshots and ranking-only rebuilds never alert. */
final class CameraNotifications {
    private Map<String, String> previous = new HashMap<>();
    private boolean initialized;
    boolean update(Map<String, String> current, Set<String> onlyOnce) { return !changed(current, onlyOnce).isEmpty(); }
    /** Keys whose notification is new or changed and may alert; same rule as before, now naming which ones. */
    Set<String> changed(Map<String, String> current, Set<String> onlyOnce) {
        Set<String> fresh = new java.util.HashSet<>();
        if (initialized) for (Map.Entry<String, String> entry : current.entrySet()) {
            String old = previous.get(entry.getKey());
            if (!entry.getValue().equals(old) && (old == null || !onlyOnce.contains(entry.getKey()))) fresh.add(entry.getKey());
        }
        previous = new HashMap<>(current); initialized = true;
        return fresh;
    }
    void reset() { previous.clear(); initialized = false; }
    static boolean eligible(StatusBarNotification s, int user) {
        Notification n = s.getNotification();
        return s.getUserId() == user && !s.isOngoing() && (n.flags & Notification.FLAG_GROUP_SUMMARY) == 0
                && !"com.android.systemui".equals(s.getPackageName()) && !"com.miui.fliphome".equals(s.getPackageName());
    }
    static String fingerprint(Notification n) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Bundle e = n.extras;
            for (String key : new String[]{Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT,
                    Notification.EXTRA_SUB_TEXT, Notification.EXTRA_TEXT_LINES}) {
                Object value = e.get(key);
                if (value instanceof CharSequence[] lines) for (CharSequence line : lines) append(digest, line);
                else if (value instanceof CharSequence text) append(digest, text);
                digest.update((byte) 0);
            }
            Parcelable[] messages = e.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (messages != null) for (int i = Math.max(0, messages.length - 25); i < messages.length; i++) {
                if (messages[i] instanceof Bundle b) {
                    append(digest, b.getCharSequence("text"));
                    append(digest, Long.toString(b.getLong("time")));
                }
            }
            return Base64.getEncoder().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void append(MessageDigest digest, CharSequence text) {
        if (text != null) digest.update(text.subSequence(0, Math.min(8192, text.length())).toString().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0xff);
    }
    static long timerTotal(Notification n) {
        CameraTimer timer = CameraTimer.read(n);
        return timer == null ? 0 : timer.total;
    }
}
