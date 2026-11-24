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
    public static final String SYSTEM_SCALES_KEY = "cutout_follow_system_scales";
    public static final String TRUE_FULLSCREEN_KEY = "cutout_true_fullscreen_apps";
    public static final int DEFAULT_SCALE = 80, SYSTEM_SCALE = 100, MIN_SCALE = 70, MAX_SCALE = 110;
    public static final long SCALING_POLICY_VERSION = 29;
    public static final long FULLSCREEN_POLICY_VERSION = 135;
    public static final long SYSTEM_SCALE_POLICY_VERSION = 174;

    /** One published revision for the existing window hooks; each callback keeps its own reference. */
    record Snapshot(Set<String> apps, Map<String, Integer> scales, Set<String> trueFullscreenApps, Map<String, Integer> systemScales,
                    boolean rustGalleryLayout) {
        static final Snapshot EMPTY = new Snapshot(Set.of(), Map.of(), Set.of(), Map.of(), false);
        boolean selected(Object pkg) { return pkg instanceof String && apps.contains(pkg); }
        boolean trueFullscreen(Object pkg, boolean supported) {
            return supported && selected(pkg) && trueFullscreenApps.contains(pkg);
        }
        int scaleFor(Object pkg, boolean trueFullscreenSupported) {
            return scales.getOrDefault(pkg, defaultScale(trueFullscreen(pkg, trueFullscreenSupported)));
        }
    }

    private CutoutAppConfig() {}

    public static Set<String> read(SharedPreferences prefs) {
        if (prefs.contains(KEY)) return Set.copyOf(prefs.getStringSet(KEY, Collections.emptySet()));
        return legacySelection(prefs.getBoolean(LEGACY_KEY, false));
    }
    public static Map<String, Integer> readScales(SharedPreferences prefs) {
        return decodeScales(prefs.getString(SCALES_KEY, ""));
    }
    public static Map<String, Integer> readSystemScales(SharedPreferences prefs) {
        return decodeScales(prefs.getString(SYSTEM_SCALES_KEY, ""), true);
    }
    /** Missing key keeps every existing selection in the original cutout-preserving mode. */
    public static Set<String> readTrueFullscreen(SharedPreferences prefs) {
        return Set.copyOf(prefs.getStringSet(TRUE_FULLSCREEN_KEY, Collections.emptySet()));
    }

    static Snapshot snapshot(SharedPreferences prefs) {
        Map<String, ?> values = prefs.getAll();
        Set<String> apps = values.containsKey(KEY) ? appSet(values.get(KEY)) : legacySelection(Boolean.TRUE.equals(values.get(LEGACY_KEY)));
        return new Snapshot(apps, decodeScales(values.get(SCALES_KEY) instanceof String encoded ? encoded : ""),
                appSet(values.get(TRUE_FULLSCREEN_KEY)),
                decodeScales(values.get(SYSTEM_SCALES_KEY) instanceof String encoded ? encoded : "", true),
                Boolean.TRUE.equals(values.get(RustGalleryLayout.KEY)) && (!values.containsKey("enabled") || Boolean.TRUE.equals(values.get("enabled"))));
    }

    private static Set<String> legacySelection(boolean enabled) { return enabled ? Set.of(LEGACY_TARGET) : Set.of(); }

    @SuppressWarnings("unchecked") // SharedPreferences only stores sets of strings.
    private static Set<String> appSet(Object value) { return value instanceof Set<?> set ? Set.copyOf((Set<String>) set) : Set.of(); }
    public static int defaultScale(boolean trueFullscreen) { return trueFullscreen ? 100 : DEFAULT_SCALE; }
    public static Map<String, Integer> decodeScales(String encoded) {
        return decodeScales(encoded, false);
    }
    private static Map<String, Integer> decodeScales(String encoded, boolean omitSystemDefault) {
        Map<String, Integer> result = new HashMap<>();
        if (encoded == null || encoded.isEmpty()) return Map.of();
        for (String entry : encoded.split(";")) {
            int at = entry.indexOf('=');
            if (at <= 0) continue;
            try {
                String pkg = entry.substring(0, at);
                int percent = clampScale(Integer.parseInt(entry.substring(at + 1)));
                if (omitSystemDefault && percent == SYSTEM_SCALE) result.remove(pkg); else result.put(pkg, percent);
            }
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
