// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** User-entered, single-line date patterns. Keep settings preview and both dock orientations in sync. */
public final class ClockLines {
    public static final String TOP = "HH:mm", BOTTOM = "EEE";
    private ClockLines() {}

    public static boolean valid(String pattern) {
        if (pattern == null || pattern.trim().isEmpty() || pattern.length() > 40) return false;
        boolean quoted = false;
        for (char c : pattern.toCharArray()) {
            if (Character.isISOControl(c) || c == '\u2028' || c == '\u2029') return false;
            if (c == '\'') quoted = !quoted;
            else if (!quoted && ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))
                    && "yMdEHhkKams".indexOf(c) < 0) return false;
        }
        if (quoted) return false;
        try { return !preview(pattern, 0).trim().isEmpty(); }
        catch (IllegalArgumentException invalidPattern) { return false; }
    }
    public static String read(String pattern, String fallback) { return valid(pattern) ? pattern : fallback; }
    public static String preview(String pattern, long millis) {
        return new SimpleDateFormat(pattern, Locale.CHINA).format(new Date(millis));
    }
    static boolean seconds(String pattern) {
        boolean quoted = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\'') quoted = !quoted;
            else if (!quoted && c == 's') return true;
        }
        return false;
    }
}
