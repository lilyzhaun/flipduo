// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module.ui

import android.content.Context
import android.os.SystemClock
import android.text.format.DateUtils
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flipduo.module.BuildConfig
import com.flipduo.module.HostCapabilities
import com.flipduo.module.RuntimeStatus
import top.yukonga.miuix.kmp.basic.*

/** Both host verdicts from the stored receipts and the last framework query; no polling. */
internal fun runtimeHosts(store: ControlsStore, c: Controls, targets: RuntimeStatus.Targets): Pair<RuntimeStatus.Host, RuntimeStatus.Host> {
    val wall = System.currentTimeMillis(); val elapsed = SystemClock.elapsedRealtime()
    fun host(id: String) = RuntimeStatus.evaluate(id, store.receipt(id), targets, BuildConfig.VERSION_CODE,
        BuildConfig.VERSION_NAME, c.revision, wall, elapsed)
    return host(RuntimeStatus.FLIPHOME) to host(RuntimeStatus.SYSTEMUI)
}

@Composable
internal fun RuntimeStatusPage(store: ControlsStore, c: Controls, back: () -> Unit) {
    val context = LocalContext.current
    val epoch by store.runtimeEpoch.collectAsState()
    val connection by store.connection.collectAsState()
    val targets by store.targets.collectAsState()
    val policy by store.systemPolicy.collectAsState()
    val save by store.saveStatus.collectAsState()
    val hosts = remember(epoch, targets, c.revision) { runtimeHosts(store, c, targets).toList() }
    val colors = uiColors()
    PageFrame("运行状态", onBack = back) {
        item { SmallTitle("本机") }
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            BasicComponent(title = "模块版本", summary = RuntimeStatus.version(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong()))
            BasicComponent(title = "框架", summary = connection.framework)
            BasicComponent(title = "设置", summary = saveLine(c.revision, save))
        } }
        for (result in hosts) {
            val receipt = store.receipt(result.host)
            item { SmallTitle(RuntimeStatus.hostTitle(result.host)) }
            item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                BasicComponent(title = result.verdict.title, summary = result.detail)
                BasicComponent(title = "最近回执", summary = receiptLine(context, receipt))
                BasicComponent(title = "框架运行目标", summary = result.framework)
                for (id in HostCapabilities.ids(result.host)) {
                    BasicComponent(title = HostCapabilities.title(id), summary = capability(receipt, id))
                }
            } }
        }
        item { SmallTitle("系统截图") }
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            BasicComponent(title = "框架运行目标", summary = RuntimeStatus.framework("screenshot", targets, BuildConfig.VERSION_CODE.toLong()))
            BasicComponent(title = "带壳截图", summary = "未确认：截图进程不发送功能回执")
        } }
        item { SmallTitle("系统框架") }
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            BasicComponent(title = "应用全屏策略", summary = policy.summary)
        } }
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
            TextButton(text = "刷新", onClick = store::refreshRuntime, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
            Spacer(Modifier.height(8.dp))
            Text("刷新只重新查询，并重试未完成的同步；不修改设置，也不重新加载宿主。功能状态来自最近回执，“已就绪”不代表当前正在接管；可选功能不可用不影响导航。",
                color = colors.secondary, fontSize = 12.sp, lineHeight = 18.sp)
        } }
    }
}

/** Local commit and framework delivery are separate stages; nothing reads as saved before commit() returns. */
internal fun saveLine(revision: Int, s: SettingsPipeline.Status): String = when {
    s.localFailed -> "第 $revision 代未能保存到本机，修改尚未持久化；点下方刷新重试"
    s.localSaved != revision -> "第 $revision 代正在保存到本机"
    !s.connected -> "第 $revision 代已保存在本机，连接框架后同步"
    s.remoteFailed -> "第 $revision 代已保存在本机，交给框架失败；点下方刷新重试"
    s.delivered != revision -> "第 $revision 代已保存在本机，正在交给框架"
    else -> "第 $revision 代已保存在本机并交给框架"
}

private fun receiptLine(context: Context, r: RuntimeStatus.Receipt): String {
    if (!r.present) return "尚无回执"
    val parts = mutableListOf("第 ${r.revision} 代配置")
    if (!r.enabled) parts += "控制区关闭"
    parts += if (r.legacy()) "旧格式，未含模块版本" else "模块 ${RuntimeStatus.version(r.moduleName, r.module.toLong())}"
    if (r.pid > 0) parts += "PID ${r.pid}"
    if (r.receivedWall > 0) parts += DateUtils.formatDateTime(context, r.receivedWall,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
    return parts.joinToString(" · ")
}

private fun capability(r: RuntimeStatus.Receipt, id: String): String = when {
    !r.present -> "尚无回执"
    r.caps == null -> "旧版回执未提供"
    else -> HostCapabilities.describe(HostCapabilities.decode(r.caps)[id])
}
