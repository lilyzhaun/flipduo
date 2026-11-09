// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import java.util.Locale;

/** ARGB values are user choices; transparent and black are valid, not automatic-color sentinels. */
public final class CameraColors {
    public static final int TIMER = 0xffffbc55, PROGRESS = 0xff86baff, CHARGE = 0xff87dfb4;
    public static final int NOTIFICATION = 0xff428fff;

    private CameraColors() {}

    public static String hex(int color) { return String.format(Locale.ROOT, "#%08X", color); }
    public static String rgb(int color) {
        return String.format(Locale.ROOT, "%d, %d, %d", (color >>> 16) & 255, (color >>> 8) & 255, color & 255);
    }

    public static Integer parse(String input) {
        if (input == null) return null;
        String value = input.trim().replace('，', ',');
        if (value.regionMatches(true, 0, "rgb(", 0, 4)) {
            if (!value.endsWith(")")) return null;
            value = value.substring(4, value.length() - 1).trim();
            if (!value.contains(",")) return null;
        }
        if (value.contains(",")) {
            String[] channels = value.split(",", -1);
            if (channels.length != 3) return null;
            int color = 0xff000000;
            for (int i = 0; i < channels.length; i++) {
                String channel = channels[i].trim();
                if (!channel.matches("[0-9]{1,3}")) return null;
                int component = Integer.parseInt(channel);
                if (component > 255) return null;
                color |= component << (16 - 8 * i);
            }
            return color;
        }
        if (value.startsWith("#")) value = value.substring(1);
        if (!value.matches("[0-9a-fA-F]{6}|[0-9a-fA-F]{8}")) return null;
        long color = Long.parseLong(value, 16);
        return (int) (value.length() == 6 ? color | 0xff000000L : color);
    }

    /** RGB edits retain the separate opacity slider; only eight hex digits explicitly replace alpha. */
    public static Integer parseInput(String input, int alpha) {
        Integer color = parse(input);
        if (color == null) return null;
        String value = input.trim();
        if (value.startsWith("#")) value = value.substring(1);
        return value.matches("[0-9a-fA-F]{8}") ? color : (color & 0x00ffffff) | (alpha << 24);
    }

    static int pulseAlpha(int color, float fraction) {
        return Math.round((color >>> 24) * Math.max(0, Math.min(1, fraction)));
    }
}
