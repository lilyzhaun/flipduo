// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Process
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.flipduo.module.CoverInputMethodConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import java.text.Collator

private data class CoverIme(val id: String, val label: String)

@Composable
internal fun CoverInputMethodPage(store: ControlsStore, controls: Controls, back: () -> Unit, scrollState: LazyListState?) {
    val context = LocalContext.current
    val user = remember { Process.myUid() / 100000 }
    val selected = controls.coverInputMethod.takeIf {
        controls.coverInputMethodUser == user && CoverInputMethodConfig.isSelectable(it)
    }.orEmpty()
    var methods by remember { mutableStateOf<List<CoverIme>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val connection by store.connection.collectAsState()
    val delivery by store.deliveredRevision.collectAsState()
    val failed by store.syncFailed.collectAsState()
    val policy by store.systemPolicy.collectAsState()
    LifecycleResumeEffect(Unit) {
        refresh++; store.refreshSystemPolicy()
        onPauseOrDispose { }
    }
    LaunchedEffect(refresh) {
        loading = true; error = null
        try {
            methods = withContext(Dispatchers.IO) {
                val collator = Collator.getInstance()
                context.getSystemService(InputMethodManager::class.java).enabledInputMethodList
                    .filter { CoverInputMethodConfig.isSelectable(it.id) }
                    .map { CoverIme(it.id, it.loadLabel(context.packageManager).toString()) }
                    .sortedWith { a, b -> collator.compare(a.label, b.label).takeIf { it != 0 } ?: a.id.compareTo(b.id) }
            }
        } catch (_: SecurityException) { error = "无法读取已启用的输入法，请点击刷新重试。" }
        finally { loading = false }
    }
    fun choose(id: String) {
        if (id.isNotEmpty() && !CoverInputMethodConfig.isSelectable(id)) return
        store.update { it.copy(coverInputMethod = id, coverInputMethodUser = if (id.isEmpty()) -1 else user) }
    }
    PageFrame("外屏输入法", onBack = back, scrollState = scrollState, actions = {
        ManagerActionButton(text = "刷新", compact = true, onClick = { refresh++; store.refreshSystemPolicy() })
    }) {
        item { Text("选择外屏使用的键盘，展开后由系统恢复内屏输入法。更改后重新展开再合上手机。键盘尺寸和外屏适配取决于输入法自身。",
            color = uiColors().secondary, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(horizontal = 8.dp)) }
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            ArrowPreference(title = "跟随系统", summary = if (selected.isEmpty()) "已选择 · 使用系统原生外屏输入法" else "恢复系统原生外屏输入法",
                onClick = { choose("") })
        } }
        when {
            loading -> item { Text("正在读取已启用的输入法…", modifier = Modifier.padding(16.dp), color = uiColors().secondary) }
            error != null -> item { Text(error!!, modifier = Modifier.padding(16.dp), color = uiColors().secondary) }
            else -> {
                if (selected.isNotEmpty() && methods.none { it.id == selected }) item {
                    Text("已保存的输入法当前不可用，将使用系统原生流程。重新启用后可继续使用，也可选择其他键盘。\n$selected",
                        color = uiColors().secondary, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(8.dp))
                }
                if (methods.isEmpty()) item { Text("暂无已启用的输入法，请先在系统设置中启用。", modifier = Modifier.padding(16.dp)) }
                items(methods, key = { it.id }) { method ->
                    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                        ArrowPreference(title = method.label, summary = (if (method.id == selected) "已选择 · " else "") + method.id,
                            onClick = { choose(method.id) })
                    }
                }
            }
        }
        item { ManagerActionButton(text = "管理系统输入法", onClick = {
            try { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
            catch (_: ActivityNotFoundException) { error = "当前系统没有提供输入法管理入口。" }
        }) }
        item { Text(when {
            !controls.enabled -> "模块总开关已关闭，当前跟随系统。"
            !connection.connected || failed -> "选择已保存在本机，连接 LSPosed 后同步。"
            delivery != controls.revision -> "正在同步选择…"
            !policy.checked -> "正在核对系统框架版本…"
            (policy.loadedVersion ?: 0L) < CoverInputMethodConfig.POLICY_VERSION -> "外屏输入法策略需系统框架加载当前版本：安装更新后请手动重启手机。"
            else -> "系统框架版本支持此功能；实际输入效果以外屏体验为准。"
        }, modifier = Modifier.padding(horizontal = 8.dp), color = uiColors().secondary, fontSize = 13.sp, lineHeight = 19.sp) }
        item { UndoGroupCard(store, SettingsGroup.INPUT_METHOD) }
    }
}
