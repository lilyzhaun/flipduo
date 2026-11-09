// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Per-process capability ladder carried by the settings receipt.
 * Only fixed identifiers and levels cross the process boundary: never notification text,
 * package names, captured pixels or user data. Unknown identifiers are ignored on decode.
 */
public final class HostCapabilities {
    /** Interface missing or installation failed; native behaviour is retained. */
    public static final int UNAVAILABLE = 0;
    /** Hooks resolved against this ROM, runtime start not (yet) completed. */
    public static final int SUPPORTED = 1;
    /** Initialised; says nothing about whether it currently owns anything. */
    public static final int READY = 2;
    /** Was taking over at the moment the receipt was sent. */
    public static final int ACTIVE = 3;
    public static final int MAX_ENCODED = 512;

    /** Fixed ids in display order; titles are user-facing. */
    private static final String[][] KNOWN = {
        {"fliphome", "controls", "外屏控制区"},
        {"fliphome", "widgets", "原生小组件入口"},
        {"fliphome", "recents_corners", "最近任务圆角交接"},
        {"systemui", "status_bar", "外屏状态栏策略"},
        {"systemui", "status_source", "电量与信号状态源"},
        {"systemui", "tools", "系统工具"},
        {"systemui", "event_bridge", "活动接管"},
        {"systemui", "mini_window", "原生小窗"},
        {"systemui", "camera_status", "镜头状态"},
        {"systemui", "blur_source", "模糊填充来源"},
        {"systemui", "camera_shortcut", "相机按钮策略"},
        {"systemui", "control_center", "控制中心横屏"},
    };

    private static final Map<String, Integer> LEVELS = new TreeMap<>();

    private HostCapabilities() {}

    static synchronized void installed(String id, boolean ok) { LEVELS.put(id, ok ? SUPPORTED : UNAVAILABLE); }

    /** Runtime start finished. A failed install is never promoted by a later start. */
    static synchronized void started(String id) {
        Integer level = LEVELS.get(id);
        if (level == null || level == SUPPORTED) LEVELS.put(id, READY);
    }

    /** Start threw or did not run; an already-ready capability is not downgraded. */
    static synchronized void startFailed(String id) {
        if (!LEVELS.containsKey(id)) LEVELS.put(id, UNAVAILABLE);
    }

    /** Ownership at report time. Ignored until the capability is ready. */
    static synchronized void active(String id, boolean value) {
        Integer level = LEVELS.get(id);
        if (level != null && level >= READY) LEVELS.put(id, value ? ACTIVE : READY);
    }

    static synchronized String encode() {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Integer> e : LEVELS.entrySet()) {
            if (!known(e.getKey())) continue;
            if (out.length() > 0) out.append(',');
            out.append(e.getKey()).append(':').append(e.getValue());
        }
        return out.toString();
    }

    static synchronized void resetForTest() { LEVELS.clear(); }

    /** Strict decode: rejects oversized input; skips malformed, duplicate-free unknown or out-of-range entries. */
    public static Map<String, Integer> decode(String value) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (value == null || value.isEmpty() || value.length() > MAX_ENCODED) return out;
        for (String part : value.split(",", 64)) {
            int colon = part.indexOf(':');
            if (colon <= 0 || colon != part.lastIndexOf(':') || colon == part.length() - 1) continue;
            String id = part.substring(0, colon);
            String digits = part.substring(colon + 1);
            if (!known(id) || digits.length() != 1) continue;
            int level = digits.charAt(0) - '0';
            if (level < UNAVAILABLE || level > ACTIVE) continue;
            out.put(id, level);
        }
        return out;
    }

    /** True for an encoded string the provider may store verbatim. */
    public static boolean storable(String value) {
        if (value == null || value.length() > MAX_ENCODED) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == ':' || c == ',')) return false;
        }
        return true;
    }

    public static boolean known(String id) {
        for (String[] row : KNOWN) if (row[1].equals(id)) return true;
        return false;
    }

    /** Ids this host is expected to report, in display order. */
    public static String[] ids(String host) {
        int n = 0;
        for (String[] row : KNOWN) if (row[0].equals(host)) n++;
        String[] out = new String[n];
        int i = 0;
        for (String[] row : KNOWN) if (row[0].equals(host)) out[i++] = row[1];
        return out;
    }

    public static String title(String id) {
        for (String[] row : KNOWN) if (row[1].equals(id)) return row[2];
        return id;
    }

    /** Absent entry: null level. Never claims a failure merely because nothing is happening now. */
    public static String describe(Integer level) {
        if (level == null) return "未回报";
        switch (level) {
            case UNAVAILABLE: return "接口不可用，保留原生行为";
            case SUPPORTED: return "接口支持，尚未初始化";
            case READY: return "已就绪";
            case ACTIVE: return "回报时正在接管";
            default: return "未回报";
        }
    }
}
