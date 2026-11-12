// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import java.text.Collator

private data class LongPressApp(val pkg: String, val label: String, val info: ApplicationInfo)

@Composable
internal fun LongPressAppsPage(store: ControlsStore, controls: Controls, key: NavigationKey, back: () -> Unit) {
    val pm = LocalContext.current.packageManager
    var query by rememberSaveable(key.id) { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<LongPressApp>>(emptyList()) }
    val selected = controls.longPressApp(key)
    LaunchedEffect(refresh) {
        loading = true; failed = false
        try {
            apps = withContext(Dispatchers.IO) {
                val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val collator = Collator.getInstance()
                pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
                    .filter { it.activityInfo.exported && it.activityInfo.enabled && it.activityInfo.applicationInfo.enabled }
                    .map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }
                    .map { LongPressApp(it.packageName, it.loadLabel(pm).toString(), it) }
                    .sortedWith { a, b -> collator.compare(a.label, b.label).takeIf { it != 0 } ?: a.pkg.compareTo(b.pkg) }
            }
        } catch (_: SecurityException) { failed = true }
        finally { loading = false }
    }
    val visible = remember(apps, query) {
        val text = query.trim()
        apps.filter { it.label.contains(text, true) || it.pkg.contains(text, true) }
    }
    PageFrame("${key.title}打开的应用", onBack = back, actions = { ManagerActionButton(text = "刷新", compact = true, onClick = { refresh++ }) }) {
        item { TextField(value = query, onValueChange = { query = it }, label = "搜索应用名称或包名", useLabelAsPlaceholder = true,
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("long_press_app_search")) }
        item { Text("选择后保存为该键的长按动作。返回不改变原设置；只列出有公开桌面入口的应用。",
            color = uiColors().secondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp)) }
        when {
            loading -> item { Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                InfiniteProgressIndicator(Modifier.size(24.dp))
            } }
            failed -> item { Text("无法读取应用列表，请点击刷新重试。", modifier = Modifier.padding(16.dp)) }
            visible.isEmpty() -> item { Text(if (query.isBlank()) "没有可选择的应用" else "没有匹配的应用",
                color = uiColors().secondary, modifier = Modifier.padding(16.dp)) }
            else -> items(visible, key = { it.pkg }) { app ->
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                    ArrowPreference(title = app.label, summary = (if (app.pkg == selected) "已选择 · " else "") + app.pkg,
                        startAction = { LongPressAppIcon(app, pm) }, onClick = {
                            store.update { it.withLongPressApp(key, app.pkg) }; back()
                        })
                }
            }
        }
    }
}

@Composable
private fun LongPressAppIcon(app: LongPressApp, pm: PackageManager) {
    val image by produceState<ImageBitmap?>(null, app.pkg) {
        value = withContext(Dispatchers.IO) { app.info.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }
    }
    Box(Modifier.padding(end = 12.dp).size(36.dp), contentAlignment = Alignment.Center) {
        image?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}
