// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.flipduo.module.CutoutAppConfig
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

private data class FullscreenApp(val packageName: String, val label: String, val system: Boolean, val info: ApplicationInfo?)

@Composable
internal fun FullscreenAppsPage(store: ControlsStore, controls: Controls, back: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager
    var query by rememberSaveable { mutableStateOf("") }
    var selectedOnly by rememberSaveable { mutableStateOf(false) }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var expandedPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var commonOptions by rememberSaveable { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf<List<FullscreenApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    val policy by store.systemPolicy.collectAsState()
    val connection by store.connection.collectAsState()
    val deliveredRevision by store.deliveredRevision.collectAsState()
    val syncFailed by store.syncFailed.collectAsState()
    val delivered = connection.connected && deliveredRevision == controls.revision
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { store.refreshSystemPolicy() }
    val selected by rememberUpdatedState(controls.fullscreenApps)
    LaunchedEffect(refresh) {
        loading = true; failed = false
        try {
            val selectedAtLoad = selected
            apps = withContext(Dispatchers.IO) {
                val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val infos = pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
                    .map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }
                    .filter { it.packageName != context.packageName }
                val entries = infos.map { info ->
                    FullscreenApp(info.packageName, info.loadLabel(pm).toString(),
                        info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0, info)
                }.toMutableList()
                // Keep stale selections removable even after an application was uninstalled.
                val known = entries.mapTo(HashSet()) { it.packageName }
                (selectedAtLoad - known).forEach { pkg -> entries.add(FullscreenApp(pkg, pkg, false, null)) }
                val collator = Collator.getInstance()
                entries.sortedWith(compareByDescending<FullscreenApp> { it.packageName in selectedAtLoad }
                    .thenComparator { a, b -> collator.compare(a.label, b.label) }.thenBy { it.packageName })
            }
        } catch (_: SecurityException) { failed = true }
        finally { loading = false }
    }
    // Sorting is stable while toggling; a row never jumps under the next tap.
    val visible = remember(apps, query, showSystem, selectedOnly, controls.fullscreenApps) {
        val text = query.trim()
        apps.filter { app ->
            (showSystem || !app.system || app.packageName in controls.fullscreenApps) &&
                (!selectedOnly || app.packageName in controls.fullscreenApps) &&
                (app.label.contains(text, ignoreCase = true) || app.packageName.contains(text, ignoreCase = true))
        }
    }
    PageFrame("应用全屏", onBack = back, itemSpacing = 12.dp, actions = {
        ManagerActionButton(text = "刷新", compact = true, onClick = { refresh++; store.retrySync(); store.refreshSystemPolicy() })
    }) {
        item(key = "search") {
            TextField(value = query, onValueChange = { query = it }, label = "搜索应用名称或包名",
                useLabelAsPlaceholder = true, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("fullscreen_search"))
        }
        item(key = "filters") {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AppFilter("全部", !selectedOnly) { selectedOnly = false }
                AppFilter("已启用 ${controls.fullscreenApps.size}", selectedOnly) { selectedOnly = true }
                AppFilter("系统应用", showSystem, Role.Checkbox) { showSystem = !showSystem }
            }
        }
        item(key = "status") {
            val message = when {
                !connection.connected -> "设置仅保存在本机，连接 LSPosed 后自动同步。"
                syncFailed -> "设置仅保存在本机，请点击刷新重试同步。"
                !delivered -> "正在同步设置…"
                policy.checked -> policy.summary
                else -> "正在核对系统策略…"
            }
            Text(message, color = uiColors().secondary, fontSize = 13.sp, lineHeight = 19.sp,
                modifier = Modifier.padding(horizontal = 8.dp))
        }
        item(key = "fullscreen_common") {
            Card(Modifier.fillMaxWidth().managerDisclosure(), insideMargin = PaddingValues(0.dp)) {
                ArrowPreference(title = "通用设置", summary = if (commonOptions) "收起按钮设置与撤销" else "全屏按钮显隐 · 撤销修改",
                    onClick = { commonOptions = !commonOptions })
                if (commonOptions) SwitchPreference(title = "全屏时隐藏所有按钮", checked = controls.hideFullscreenButtons,
                    summary = "保留时钟、三合一及镜头环。长按时间或三合一可临时切换按钮显隐；有活动时直接展开卡片，可上滑收起。",
                    onCheckedChange = { value -> store.update { it.copy(hideFullscreenButtons = value) } })
            }
        }
        if (commonOptions) item(key = "undo") { UndoGroupCard(store, SettingsGroup.FULLSCREEN) }
        when {
            loading -> item(key = "loading") {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    InfiniteProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp)); Text("正在读取应用")
                }
            }
            failed -> item(key = "error") {
                Text("无法读取应用列表，请点击右上角刷新。", modifier = Modifier.padding(16.dp))
            }
            visible.isEmpty() -> item(key = "empty") {
                Text(if (query.isNotBlank()) "没有匹配的应用" else if (selectedOnly) "还没有启用的应用" else "没有可显示的应用",
                    color = uiColors().secondary, modifier = Modifier.padding(16.dp))
            }
            else -> items(visible, key = { it.packageName }, contentType = { "app" }) { app ->
                val enabled = app.packageName in controls.fullscreenApps
                val trueFullscreen = app.packageName in controls.trueFullscreenApps
                val mode = if (!enabled) 0 else if (trueFullscreen) 2 else 1
                val modes = listOf("跟随系统", "保留挖孔全屏", "真全屏")
                val defaultScale = CutoutAppConfig.defaultScale(trueFullscreen)
                val percent = controls.fullscreenScales[app.packageName] ?: defaultScale
                val expanded = expandedPackage == app.packageName
                Card(Modifier.fillMaxWidth().managerDisclosure(), insideMargin = PaddingValues(0.dp)) {
                    ArrowPreference(title = app.label,
                        summary = if (app.info == null) "未安装或无桌面入口 · ${modes[mode]}" else
                            if (enabled) "${modes[mode]} · ${percent}%" else modes[mode],
                        startAction = { FullscreenAppIcon(app, pm) },
                        onClick = { expandedPackage = if (expanded) null else app.packageName })
                    if (expanded) {
                        Text(app.packageName, color = uiColors().secondary, fontSize = 12.sp, lineHeight = 18.sp,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
                        OverlayDropdownPreference(title = "显示模式", items = modes, selectedIndex = mode,
                            onSelectedIndexChange = { index -> store.update { current -> current.copy(
                                fullscreenApps = if (index == 0) current.fullscreenApps - app.packageName else current.fullscreenApps + app.packageName,
                                trueFullscreenApps = when (index) {
                                    1 -> current.trueFullscreenApps - app.packageName
                                    2 -> current.trueFullscreenApps + app.packageName
                                    else -> current.trueFullscreenApps
                                }) } })
                        Text(when (mode) {
                            1 -> "扩展应用画布，保留系统挖孔信息，由应用决定如何避让镜头。原有全屏设置沿用此模式。"
                            2 -> "忽略挖孔避让，应用可铺满外屏；镜头位置的内容可能被硬件遮住。"
                            else -> "使用系统原生显示方式，保留已保存的模式与大小参数。"
                        }, color = uiColors().secondary, fontSize = 13.sp, lineHeight = 19.sp,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp))
                    }
                    if (expanded && enabled) {
                        var draft by remember(app.packageName, percent) { mutableFloatStateOf(percent.toFloat()) }
                        fun save(value: Int) = store.update { current -> current.copy(fullscreenScales =
                            current.fullscreenScales + (app.packageName to value)) }
                        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("内容大小", fontSize = 14.sp, color = uiColors().secondary)
                                Text("${draft.roundToInt()}%", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                    color = uiColors().accent)
                            }
                            Spacer(Modifier.height(14.dp))
                            Slider(value = draft, onValueChange = { draft = it }, valueRange = 70f..110f,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                onValueChangeFinished = { save(draft.roundToInt()) })
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("70% — 110%", fontSize = 12.sp, color = uiColors().secondary)
                                ManagerActionButton(text = "恢复 ${defaultScale}%", compact = true, onClick = { save(defaultScale) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppFilter(text: String, selected: Boolean, role: Role = Role.Tab, onClick: () -> Unit) {
    val colors = uiColors()
    Box(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
        .background(if (selected) colors.accent.copy(alpha = .12f) else colors.surface)
        .then(if (role == Role.Checkbox) Modifier.toggleable(selected, role = role) { onClick() }
            else Modifier.selectable(selected, role = role, onClick = onClick))
        .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 14.sp, color = if (selected) colors.accent else colors.secondary,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
    }
}

@Composable
private fun FullscreenAppIcon(app: FullscreenApp, pm: PackageManager) {
    val image by produceState<ImageBitmap?>(null, app.packageName, app.info) {
        value = withContext(Dispatchers.IO) {
            (app.info?.loadIcon(pm) ?: pm.defaultActivityIcon).toBitmap(96, 96).asImageBitmap()
        }
    }
    Box(Modifier.padding(end = 12.dp).size(36.dp), contentAlignment = Alignment.Center) {
        image?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}
