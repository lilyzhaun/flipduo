// SPDX-License-Identifier: AGPL-3.0-only
// Quick-restart interaction adapted from HyperCeiler, Copyright (C) 2023-2026 HyperCeiler Contributions.
// https://github.com/ReChronoRain/HyperCeiler/tree/0830c5875570e66ae576b9276b0e57033cb6601a
package com.flipduo.module.ui

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flipduo.module.ScopeRestart
import com.flipduo.module.ScopeRestart.Scope
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class RestartState(val running: Boolean = false, val result: ScopeRestart.Result? = null)

/** One in-flight user action per app process, including fold/unfold activity recreation. */
private object ScopeRestartAction {
    val state = MutableStateFlow(RestartState())
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized fun start(context: Context, selected: Set<Scope>) {
        if (state.value.running || selected.isEmpty()) return
        val cache = context.cacheDir
        val user = Process.myUid() / 100000
        val deadline = SystemClock.elapsedRealtime() / 1000 + 30
        state.value = RestartState(running = true)
        worker.launch {
            val result = try {
                ScopeRestart.run(selected, user, cache, deadline)
            } catch (error: IOException) {
                Log.w("FlipDuo", "Could not start root scope restart", error)
                ScopeRestart.Result(emptyMap(), "无法执行 Root 命令，请检查 Root 管理器及 FlipDuo 授权。")
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                ScopeRestart.Result(emptyMap(), "操作已中断，请核对运行状态。")
            }
            state.value = RestartState(result = result)
        }
    }
}

@Composable
internal fun ScopeRestartDialog(show: Boolean, dismiss: () -> Unit, refreshTargets: () -> Unit) {
    val context = LocalContext.current
    val state by ScopeRestartAction.state.collectAsState()
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val scopes = remember { Scope.values().toList() }
    val all = (1 shl scopes.size) - 1
    LaunchedEffect(state.result) { if (state.result != null) refreshTargets() }
    OverlayDialog(show = show, title = "快速重启", onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Text("重启选中的作用域，需要 Root 权限。", color = uiColors().secondary,
                    style = MiuixTheme.textStyles.body2)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("已选择 ${Integer.bitCount(selected)} 项", color = uiColors().secondary,
                        style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
                    TextButton(text = if (selected == all) "全不选" else "全选", enabled = !state.running,
                        minHeight = 48.dp,
                        colors = ButtonDefaults.textButtonColors(color = Color.Transparent,
                            disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.primary),
                        onClick = { selected = if (selected == all) 0 else all })
                }
                scopes.forEach { scope ->
                    RestartScopeRow(scope, checked = selected and (1 shl scope.ordinal) != 0,
                        enabled = !state.running, onCheckedChange = { checked ->
                            selected = if (checked) selected or (1 shl scope.ordinal)
                                else selected and (1 shl scope.ordinal).inv()
                        })
                }
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    if (state.running) Text("正在等待 Root 授权并发送请求…", color = uiColors().secondary,
                        fontSize = 13.sp, lineHeight = 19.sp)
                    state.result?.let { result ->
                        scopes.forEach { scope -> result.items()[scope]?.let { text ->
                            Text("${scope.title}：$text", fontSize = 13.sp, lineHeight = 19.sp)
                        } }
                        result.error()?.let { Text(it, color = uiColors().secondary, fontSize = 13.sp, lineHeight = 19.sp) }
                        Text("新版是否加载请查看“运行状态”。", color = uiColors().secondary, fontSize = 13.sp,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            // Miuix 0.9.3's dialog example uses equal-width native TextButtons with its default squircle.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                TextButton(text = if (state.running || state.result != null) "关闭" else "取消",
                    modifier = Modifier.weight(1f), minHeight = 48.dp, onClick = dismiss)
                TextButton(text = "重启选中", enabled = selected != 0 && !state.running,
                    colors = ButtonDefaults.textButtonColorsPrimary(), minHeight = 48.dp,
                    modifier = Modifier.weight(1f).testTag("restart-selected"), onClick = {
                        ScopeRestartAction.start(context, scopes.filter { selected and (1 shl it.ordinal) != 0 }.toSet())
                    })
            }
        }
    }
}

@Composable
private fun RestartScopeRow(scope: Scope, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val haptic = LocalHapticFeedback.current
    val colors = MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .testTag("restart-scope-${scope.name}")
        .toggleable(value = checked, enabled = enabled, role = Role.Checkbox,
            interactionSource = interaction, indication = null, onValueChange = {
                onCheckedChange(it)
                haptic.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
            }).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(scope.title, style = MiuixTheme.textStyles.body1, modifier = Modifier.weight(1f),
            color = if (!enabled) colors.disabledOnSecondaryVariant else if (focused) colors.primary else colors.onSurface)
        Checkbox(state = ToggleableState(checked), onClick = null, enabled = enabled,
            modifier = Modifier.clearAndSetSemantics { })
    }
}
