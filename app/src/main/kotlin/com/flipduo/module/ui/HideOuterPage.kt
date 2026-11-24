// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.flipduo.module.FlipDuoApplication
import com.flipduo.module.HideOuterApp
import com.flipduo.module.RustGalleryLayout
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

private data class OuterRow(val app: HideOuterApp, val label: String, val icon: ImageBitmap?, val unavailable: String?,
    val rustGallery: Boolean = false) {
    val scopeName get() = if (rustGallery) "system" else app.pkg
}

@Composable
internal fun HideOuterPage(store: ControlsStore, controls: Controls, back: () -> Unit, scrollState: LazyListState?) {
    val pm = LocalContext.current.packageManager
    val service by FlipDuoApplication.service.collectAsState()
    val policy by store.systemPolicy.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    var epoch by remember { mutableIntStateOf(0) }
    var grantsEpoch by remember { mutableIntStateOf(0) }
    var message by remember(service) { mutableStateOf("") }
    LifecycleResumeEffect(Unit) { epoch++; onPauseOrDispose { } }
    LaunchedEffect(service, epoch, controls.rustGalleryLayout) { if (controls.rustGalleryLayout) store.refreshSystemPolicy() }
    val rows by produceState<List<OuterRow>>(emptyList(), pm, epoch) {
        value = withContext(Dispatchers.IO) {
            HideOuterApp.entries.map { app ->
                try {
                    val info = pm.getApplicationInfo(app.pkg, PackageManager.ApplicationInfoFlags.of(0))
                    val rustGallery = RustGalleryLayout.isNativeGallery(info)
                    OuterRow(app, info.loadLabel(pm).toString(), info.loadIcon(pm).toBitmap(96, 96).asImageBitmap(), when {
                        !info.enabled -> "应用已停用"
                        !rustGallery && !HideOuterApp.hasAppCode(info) -> "此版本使用原生运行时，不支持此选项"
                        else -> null
                    }, rustGallery)
                } catch (_: PackageManager.NameNotFoundException) { OuterRow(app, app.title, null, "未安装") }
                catch (_: SecurityException) { OuterRow(app, app.title, null, "无法读取应用信息") }
            }
        }
    }
    val grants by produceState<Set<String>?>(null, service, epoch, grantsEpoch) {
        val current = service
        value = if (current == null) null else try { withContext(Dispatchers.IO) { current.scope.toSet() } }
        catch (_: XposedService.ServiceException) { null }
    }
    val selected = controls.hideOuterApps
    fun checked(row: OuterRow) = if (row.rustGallery) controls.rustGalleryLayout else row.app.pkg in selected ||
        (row.app == HideOuterApp.GALLERY && row.unavailable != null && controls.rustGalleryLayout)
    fun granted(name: String) = name in grants.orEmpty() || (name == "system" && "android" in grants.orEmpty())
    val missing = rows.filter { it.unavailable == null && checked(it) }.map { it.scopeName }.distinct().filterNot(::granted)
    PageFrame("隐藏外屏状态", onBack = back, scrollState = scrollState) {
        item { Text("让所选应用在外屏使用普通布局。勾选后授权对应的 LSPosed 作用域；更改后请完全退出并重新打开该应用。",
            color = uiColors().secondary, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(horizontal = 8.dp)) }
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            for (row in rows) key(row.app) {
                val pkg = row.app.pkg; val checked = checked(row)
                val note = row.unavailable ?: when {
                    row.rustGallery && checked && grants != null && !granted("system") -> "实验 · 待授权系统框架"
                    row.rustGallery && checked && (!policy.scalingReady || (policy.loadedVersion ?: 0) < RustGalleryLayout.POLICY_VERSION) ->
                        "实验 · 需系统框架加载新版模块；升级后手动重启手机一次"
                    row.rustGallery -> "实验 · 尝试在外屏显示相册普通首页；不支持时保留原布局"
                    checked && grants != null && !granted(pkg) -> "待授权"
                    else -> null
                }
                SwitchPreference(title = row.label, summary = if (note == null) pkg else "$pkg\n$note",
                    startAction = { Box(Modifier.padding(end = 12.dp).size(36.dp)) {
                        row.icon?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
                    } }, checked = checked, enabled = row.unavailable == null || checked,
                    onCheckedChange = { on -> message = ""; store.update { c -> when {
                        row.app == HideOuterApp.GALLERY && row.unavailable != null && !on -> c.copy(rustGalleryLayout = false, hideOuterApps = c.hideOuterApps - pkg)
                        row.rustGallery -> c.copy(rustGalleryLayout = on)
                        else -> c.copy(hideOuterApps = if (on) c.hideOuterApps + pkg else c.hideOuterApps - pkg)
                    } } })
            }
        } }
        if (!controls.enabled || service == null || grants == null || message.isNotBlank()) item {
            Text(when {
                !controls.enabled -> "模块总开关已关闭，应用使用原有布局。"
                service == null -> "选择可保存在本机，连接 LSPosed 后即可授权。"
                message.isNotBlank() -> message
                else -> "暂时无法读取作用域，可在 LSPosed 中勾选所选应用。"
            }, color = uiColors().secondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
        }
        if (service != null && missing.isNotEmpty()) item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            ArrowPreference(title = "授权所选应用", summary = if ("system" in missing) "包含 Rust 相册所需的系统框架作用域" else "向 LSPosed 请求 ${missing.size} 个应用的作用域", onClick = {
                val current = service ?: return@ArrowPreference
                try {
                    current.requestScope(missing, object : XposedService.OnScopeEventListener {
                        override fun onScopeRequestApproved(packages: List<String>) { coroutineScope.launch { grantsEpoch++; message = if ("system" in missing) "系统框架需加载新版模块，之后重新打开相册。" else "请重新启动已授权的应用。" } }
                        override fun onScopeRequestFailed(reason: String) { coroutineScope.launch { grantsEpoch++; message = "未获得授权，可重试或在 LSPosed 中勾选。" } }
                    })
                } catch (_: XposedService.ServiceException) { message = "作用域请求未发送，请重新连接 LSPosed。" }
            })
        } }
        item { UndoGroupCard(store, SettingsGroup.HIDE_OUTER) }
    }
}
