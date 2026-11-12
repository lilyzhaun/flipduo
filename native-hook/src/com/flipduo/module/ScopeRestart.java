// SPDX-License-Identifier: AGPL-3.0-only
// Quick-restart flow adapted from HyperCeiler, Copyright (C) 2023-2026 HyperCeiler Contributions.
// https://github.com/ReChronoRain/HyperCeiler/tree/0830c5875570e66ae576b9276b0e57033cb6601a
// FlipDuo uses exact per-user targets and bounded execution rather than upstream's pgrep/SIGTERM helper.
package com.flipduo.module;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** App-only, explicit user action. Sending a signal does not prove that a host reloaded the module. */
public final class ScopeRestart {
    public enum Scope {
        SCREENSHOT("截图", "com.miui.screenshot"),
        SOGOU("搜狗输入法小米版", "com.sohu.inputmethod.sogou.xiaomi"),
        FLIPHOME("外屏桌面", "com.miui.fliphome"),
        SYSTEMUI("系统界面", "com.android.systemui");
        public final String title, packageName;
        Scope(String title, String packageName) { this.title = title; this.packageName = packageName; }
    }

    public record Result(Map<Scope, String> items, String error) {}

    public static Result run(Set<Scope> selected, int user, File cache, long deadlineSeconds)
            throws IOException, InterruptedException {
        if (selected.isEmpty()) return new Result(Map.of(), null);
        File output = File.createTempFile("scope-restart-", ".txt", cache);
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", script(selected, user, deadlineSeconds))
                    .redirectErrorStream(true).redirectOutput(output).start();
            process.getOutputStream().close();
            boolean completed = process.waitFor(30, TimeUnit.SECONDS);
            if (!completed) process.destroyForcibly();
            // A regular file avoids blocking on a pipe retained by a root child after timeout.
            return result(selected, Files.readString(output.toPath()), completed ? process.exitValue() : 12);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            output.delete();
        }
    }

    public static String script(Set<Scope> selected, int user, long deadlineSeconds) {
        if (user < 0 || deadlineSeconds <= 0) throw new IllegalArgumentException("Invalid execution identity/deadline");
        String setup = """
                [ "$(id -u)" = 0 ] || exit 10
                echo ROOT_OK
                processes=$(ps -A -o PID,UID,NAME) || exit 11
                restart_scope() {
                    sent=0
                    failed=0
                    expired=0
                    while read -r pid uid name; do
                        case "$name" in "$1"|"$1":*) ;; *) continue ;; esac
                        case "$pid:$uid" in *[!0-9:]*|:*|*:) continue ;; esac
                        [ "$pid" -gt 100 ] || continue
                        [ "$((uid / 100000))" -eq USER_ID ] || continue
                        actual=$(tr '\\000' '\\n' 2>/dev/null < "/proc/$pid/cmdline") || { failed=$((failed + 1)); continue; }
                        actual=$(printf '%s\\n' "$actual" | head -n 1)
                        [ "$actual" = "$name" ] || continue
                        actual_uid=$(stat -c %u "/proc/$pid" 2>/dev/null) || { failed=$((failed + 1)); continue; }
                        [ "$actual_uid" = "$uid" ] || continue
                        # Late root approval must not execute an expired request.
                        read -r uptime unused < /proc/uptime || { expired=1; break; }
                        [ "${uptime%%.*}" -lt DEADLINE ] || { expired=1; break; }
                        if kill -9 "$pid" 2>/dev/null; then sent=$((sent + 1)); else failed=$((failed + 1)); fi
                    done <<PROCESSES
                $processes
                PROCESSES
                    [ "$expired" = 0 ] || exit 12
                    printf 'RESULT|%s|%s|%s\\n' "$2" "$sent" "$failed"
                }
                """.replace("USER_ID", Integer.toString(user)).replace("DEADLINE", Long.toString(deadlineSeconds));
        StringBuilder commands = new StringBuilder(setup);
        for (Scope scope : Scope.values()) if (selected.contains(scope))
            commands.append("restart_scope '").append(scope.packageName).append("' '").append(scope.name()).append("'\n");
        return commands.append("exit 0\n").toString();
    }

    public static Result result(Set<Scope> selected, String output, int exit) {
        Map<Scope, String> items = new EnumMap<>(Scope.class);
        for (String line : output.split("\\R")) {
            String[] parts = line.split("\\|");
            if (parts.length != 4 || !parts[0].equals("RESULT")) continue;
            for (Scope scope : selected) if (scope.name().equals(parts[1])) {
                try {
                    int sent = Integer.parseInt(parts[2]), failed = Integer.parseInt(parts[3]);
                    if (sent < 0 || failed < 0) continue;
                    items.put(scope, failed > 0 ? (sent > 0 ? "部分请求失败，请核对运行状态" : "进程查询或结束失败，请检查 Root 权限")
                            : sent > 0 ? "已发送重载请求" : "未运行，无需结束进程");
                } catch (NumberFormatException invalidResult) { /* Missing results are reported below. */ }
            }
        }
        String error = exit == 12 ? "操作超时，请核对运行状态后再决定是否重试。"
                : !output.lines().anyMatch("ROOT_OK"::equals) ? "未取得 Root 权限，请在 Root 管理器中授权 FlipDuo 后重试。"
                : exit != 0 || items.size() != selected.size() ? "未能完成进程查询或结束请求，请核对运行状态。" : null;
        for (Scope scope : selected) items.putIfAbsent(scope, "执行结果未确认");
        return new Result(Map.copyOf(items), error);
    }
}
