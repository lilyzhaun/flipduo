// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure classification of host receipts and framework running targets for the manager UI.
 * "Configuration received", "this module build loaded" and "process still alive" are kept
 * separate; missing evidence becomes waiting / unknown / history, never success or failure.
 */
public final class RuntimeStatus {
    public static final String FLIPHOME = "fliphome", SYSTEMUI = "systemui";
    /** Receipts older than the current boot are detected by elapsed/wall drift beyond this bound. */
    static final long BOOT_TOLERANCE_MS = 10 * 60 * 1000L;

    private RuntimeStatus() {}

    public static String process(String host) {
        switch (host) {
            case FLIPHOME: return "com.miui.fliphome";
            case SYSTEMUI: return "com.android.systemui";
            default: return "com.miui.screenshot";
        }
    }

    public static String hostTitle(String host) {
        switch (host) {
            case FLIPHOME: return "外屏桌面";
            case SYSTEMUI: return "系统界面";
            default: return "系统截图";
        }
    }

    /** Last stored receipt. module/pid/received* are -1 for old-format receipts (hosts without the B01 fields). */
    public static final class Receipt {
        public final boolean present, enabled;
        public final int revision, module, pid;
        public final String moduleName, caps;
        public final long processStart, receivedWall, receivedElapsed;

        public Receipt(boolean present, int revision, boolean enabled, int module, String moduleName, int pid,
                long processStart, long receivedWall, long receivedElapsed, String caps) {
            this.present = present; this.revision = revision; this.enabled = enabled; this.module = module;
            this.moduleName = moduleName == null ? "" : moduleName; this.pid = pid; this.processStart = processStart;
            this.receivedWall = receivedWall; this.receivedElapsed = receivedElapsed; this.caps = caps;
        }

        public static Receipt none() { return new Receipt(false, -1, false, -1, "", -1, -1, -1, -1, null); }
        public boolean legacy() { return present && module <= 0; }
    }

    public enum TargetState { UP_TO_DATE, STALE, RELOADING, FAILED, UNKNOWN;
        public static TargetState of(String name) {
            for (TargetState s : values()) if (s.name().equals(name)) return s;
            return UNKNOWN;
        }
    }

    public static final class Target {
        public final String process; public final int pid; public final TargetState state; public final long loadedVersion;
        public Target(String process, int pid, TargetState state, long loadedVersion) {
            this.process = process; this.pid = pid; this.state = state; this.loadedVersion = loadedVersion;
        }
    }

    /** Framework running-target query result; kept apart from the receipt so neither stands in for the other. */
    public static final class Targets {
        public static final int DISCONNECTED = 0, UNSUPPORTED = 1, FAILED = 2, AVAILABLE = 3;
        public final int kind, api; public final List<Target> list;
        private Targets(int kind, int api, List<Target> list) { this.kind = kind; this.api = api; this.list = list; }
        public static Targets disconnected() { return new Targets(DISCONNECTED, 0, Collections.emptyList()); }
        public static Targets unsupported(int api) { return new Targets(UNSUPPORTED, api, Collections.emptyList()); }
        public static Targets failed(int api) { return new Targets(FAILED, api, Collections.emptyList()); }
        public static Targets available(int api, List<Target> list) {
            return new Targets(AVAILABLE, api, Collections.unmodifiableList(new ArrayList<>(list)));
        }
        public Target find(String process) {
            for (Target t : list) if (process.equals(t.process)) return t;
            return null;
        }
    }

    public enum Liveness { ALIVE, ENDED, UNCONFIRMED }

    public enum Verdict {
        WAITING("等待宿主回执"),
        HISTORY("历史回执"),
        LEGACY("旧版回执"),
        VERSION_MISMATCH("模块版本不一致"),
        PENDING("等待应用当前配置"),
        RECEIVED_UNCONFIRMED("已接收当前配置"),
        CURRENT("当前版本已生效");
        public final String title;
        Verdict(String title) { this.title = title; }
    }

    public static final class Host {
        public final String host; public final Verdict verdict; public final Liveness liveness;
        public final String detail, framework; public final boolean configMatches;
        Host(String host, Verdict verdict, Liveness liveness, String detail, String framework, boolean configMatches) {
            this.host = host; this.verdict = verdict; this.liveness = liveness; this.detail = detail;
            this.framework = framework; this.configMatches = configMatches;
        }
    }

    public static final class Summary {
        public final String title, detail; public final boolean success;
        Summary(String title, String detail, boolean success) { this.title = title; this.detail = detail; this.success = success; }
    }

    static boolean sameBoot(Receipt r, long nowWall, long nowElapsed) {
        if (r.receivedElapsed < 0 || r.receivedWall < 0) return true; // legacy: boot unknown, not proof of a new boot
        if (r.receivedElapsed > nowElapsed) return false;
        long then = r.receivedWall - r.receivedElapsed, now = nowWall - nowElapsed;
        return Math.abs(now - then) <= BOOT_TOLERANCE_MS;
    }

    static Liveness liveness(String host, Receipt r, Targets t, long nowWall, long nowElapsed) {
        if (!r.present) return Liveness.UNCONFIRMED;
        if (!sameBoot(r, nowWall, nowElapsed)) return Liveness.ENDED;
        if (t.kind != Targets.AVAILABLE || r.pid <= 0) return Liveness.UNCONFIRMED;
        String process = process(host);
        for (Target target : t.list) if (process.equals(target.process) && target.pid == r.pid) return Liveness.ALIVE;
        return Liveness.ENDED;
    }

    public static Host evaluate(String host, Receipt r, Targets t, int appModule, String appModuleName,
            int revision, long nowWall, long nowElapsed) {
        Liveness live = liveness(host, r, t, nowWall, nowElapsed);
        String framework = framework(host, t, appModule);
        boolean config = r.present && r.revision == revision;
        Verdict v;
        String detail;
        if (!r.present) {
            v = Verdict.WAITING;
            Target target = t.kind == Targets.AVAILABLE ? t.find(process(host)) : null;
            if (target != null) detail = "框架显示模块已加载，宿主尚未回执；可能尚未挂载或该版本系统不兼容";
            else if (t.kind == Targets.AVAILABLE) detail = "宿主当前未运行本模块";
            else detail = "尚未收到宿主回执";
        } else if (live == Liveness.ENDED) {
            v = Verdict.HISTORY;
            detail = "最近回执来自已结束的进程，不代表当前运行状态";
        } else if (r.legacy()) {
            v = Verdict.LEGACY;
            detail = "回执来自旧版模块，未提供版本；宿主重新加载后更新";
        } else if (r.module != appModule) {
            v = Verdict.VERSION_MISMATCH;
            detail = "宿主运行 " + version(r.moduleName, r.module) + "，本机为 " + version(appModuleName, appModule) + "；重新加载宿主后生效";
        } else if (!config) {
            v = Verdict.PENDING;
            detail = "已收到第 " + r.revision + " 代配置，当前为第 " + revision + " 代";
        } else if (live == Liveness.ALIVE) {
            v = Verdict.CURRENT;
            detail = "当前版本已接收第 " + revision + " 代配置";
        } else {
            v = Verdict.RECEIVED_UNCONFIRMED;
            detail = t.kind == Targets.AVAILABLE ? "无法确认回执进程" : "当前框架无法确认宿主进程仍在运行";
        }
        return new Host(host, v, live, detail, framework, config);
    }

    public static String version(String name, long code) {
        return name == null || name.isEmpty() ? "versionCode " + code : name + "（" + code + "）";
    }

    public static String framework(String host, Targets t, long appModule) {
        switch (t.kind) {
            case Targets.DISCONNECTED: return "框架未连接";
            case Targets.UNSUPPORTED: return "当前框架 API " + t.api + " 不支持查询运行目标";
            case Targets.FAILED: return "暂时无法查询运行目标";
            default: break;
        }
        Target target = t.find(process(host));
        if (target == null) return "未运行或未加载本模块";
        switch (target.state) {
            case UP_TO_DATE:
                return (target.loadedVersion == appModule ? "已加载当前版本" : "已加载 versionCode " + target.loadedVersion)
                        + " · PID " + target.pid;
            case STALE: return "仍为旧版 versionCode " + target.loadedVersion + "，需重新加载 · PID " + target.pid;
            case RELOADING: return "正在重新加载 · PID " + target.pid;
            case FAILED: return "加载失败 · PID " + target.pid;
            default: return "状态未知 · PID " + target.pid;
        }
    }

    /** Home-card summary. Optional capability levels deliberately do not participate. */
    public static Summary summarize(boolean enabled, boolean connected, String connectionMessage, Host home, Host system) {
        return summarize(enabled, connected, connectionMessage, false, home, system);
    }

    /** A failed local commit outranks every receipt: the shown edit is not persisted, so nothing may read as saved. */
    public static Summary summarize(boolean enabled, boolean connected, String connectionMessage, boolean localFailed,
            Host home, Host system) {
        if (localFailed) return new Summary("设置未保存", "本机保存失败，当前修改尚未持久化；可在“运行状态”页重试", false);
        Summary s = summarizeHosts(connected, connectionMessage, home, system);
        return enabled ? s : new Summary("控制区已关闭", s.detail, false);
    }

    private static Summary summarizeHosts(boolean connected, String connectionMessage, Host home, Host system) {
        if (!connected) return new Summary("等待框架连接", connectionMessage, false);
        String outdated = names(home, system, Verdict.LEGACY, Verdict.VERSION_MISMATCH);
        if (outdated != null) return new Summary("宿主仍运行旧版本", outdated + "需重新加载后使用当前版本", false);
        String waiting = names(home, system, Verdict.WAITING, Verdict.HISTORY);
        if (waiting != null) return new Summary("等待宿主回执", waiting + "尚未回执当前运行状态", false);
        if (home.verdict == Verdict.PENDING || system.verdict == Verdict.PENDING)
            return new Summary("等待宿主应用配置", connectionMessage, false);
        if (home.verdict == Verdict.CURRENT && system.verdict == Verdict.CURRENT)
            return new Summary("配置已同步", "外屏桌面与系统界面运行当前版本，已接收当前配置", true);
        return new Summary("配置已同步", "外屏桌面与系统界面已接收当前配置；暂无法确认宿主仍在运行", true);
    }

    private static String names(Host home, Host system, Verdict a, Verdict b) {
        boolean h = home.verdict == a || home.verdict == b, s = system.verdict == a || system.verdict == b;
        if (h && s) return "外屏桌面与系统界面";
        if (h) return hostTitle(home.host);
        if (s) return hostTitle(system.host);
        return null;
    }
}
