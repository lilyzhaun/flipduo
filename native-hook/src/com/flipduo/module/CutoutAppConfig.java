package com.flipduo.module;

import android.content.SharedPreferences;
import java.util.Collections;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

/** Shared by the settings app and system_server; an explicitly empty selection overrides the old trial. */
public final class CutoutAppConfig {
    public static final String KEY = "cutout_apps";
    public static final String LEGACY_KEY = "telegram_cutout_trial";
    public static final String LEGACY_TARGET = "org.telegram.messenger.web";
    public static final String SCALES_KEY = "cutout_app_scales";
    public static final String TRUE_FULLSCREEN_KEY = "cutout_true_fullscreen_apps";
    public static final int DEFAULT_SCALE = 80, MIN_SCALE = 70, MAX_SCALE = 110;
    public static final long SCALING_POLICY_VERSION = 29;
    public static final long FULLSCREEN_POLICY_VERSION = 135;

    private CutoutAppConfig() {}

    public static Set<String> read(SharedPreferences prefs) {
        if (prefs.contains(KEY)) return Set.copyOf(prefs.getStringSet(KEY, Collections.emptySet()));
        return prefs.getBoolean(LEGACY_KEY, false) ? Set.of(LEGACY_TARGET) : Collections.emptySet();
    }
    public static Map<String, Integer> readScales(SharedPreferences prefs) {
        return decodeScales(prefs.getString(SCALES_KEY, ""));
    }
    /** Missing key keeps every existing selection in the original cutout-preserving mode. */
    public static Set<String> readTrueFullscreen(SharedPreferences prefs) {
        return Set.copyOf(prefs.getStringSet(TRUE_FULLSCREEN_KEY, Collections.emptySet()));
    }
    public static int defaultScale(boolean trueFullscreen) { return trueFullscreen ? 100 : DEFAULT_SCALE; }
    public static Map<String, Integer> decodeScales(String encoded) {
        Map<String, Integer> result = new HashMap<>();
        if (encoded == null || encoded.isEmpty()) return Map.of();
        for (String entry : encoded.split(";")) {
            int at = entry.indexOf('=');
            if (at <= 0) continue;
            try { result.put(entry.substring(0, at), clampScale(Integer.parseInt(entry.substring(at + 1)))); }
            catch (NumberFormatException ignored) { /* Invalid external preference entry: use the default for that app. */ }
        }
        return Map.copyOf(result);
    }
    public static int clampScale(int value) { return Math.max(MIN_SCALE, Math.min(MAX_SCALE, value)); }
    public static int densityFor(int originalDpi, int percent) { return Math.max(1, Math.round(originalDpi * clampScale(percent) / 100f)); }
    public static boolean isCoverDisplay(int width, int height) {
        return (width == 1208 && height == 1392) || (width == 1392 && height == 1208);
    }
    public static int dpFor(int pixels, int densityDpi) { return Math.max(1, (int) (pixels * 160f / densityDpi)); }
}
