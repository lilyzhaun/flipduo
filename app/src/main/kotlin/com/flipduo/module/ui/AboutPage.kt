// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
// Adapted from PlayBilling / InstallerX-Revived 35d7c7a06e86b280789a171d8b213becac1a0fd7 MiuixAboutPage.kt.
// Retains its fixed identity, measured scroll stages, cloud capture and frosted rows; FlipDuo content only.
package com.flipduo.module.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flipduo.module.BuildConfig
import com.flipduo.module.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
internal fun AboutPage(license: () -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val colors = uiColors()
    val dark = isSystemInDarkTheme()
    val appearance = LocalAppearance.current
    val active = LocalRouteActive.current
    val useBlur = appearance.blur && LocalShaderSupported.current
    val cloud = if (useBlur) rememberLayerBackdrop() else null
    val page = if (useBlur) rememberLayerBackdrop { drawRect(colors.canvas); drawContent() } else null
    val scroll = rememberLazyListState()
    val behavior = MiuixScrollBehavior(rememberTopAppBarState())
    var logoHeight by remember { mutableIntStateOf(0) }
    var spacerHeight by remember { mutableIntStateOf(0) }
    val progress = remember { derivedStateOf {
        if (spacerHeight <= 0) 0f else if (scroll.firstVisibleItemIndex > 0) 1f
        else (scroll.firstVisibleItemScrollOffset.toFloat() / spacerHeight).coerceIn(0f, 1f)
    } }
    val collapsed by remember { derivedStateOf { progress.value >= 1f } }
    val motion = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    val header = if (page != null && collapsed) Modifier.drawBackdrop(page, shape = { RectangleShape },
        effects = { blur(25f) }, onDrawSurface = { drawRect(colors.canvas.copy(alpha = .92f)) })
    else Modifier.background(if (collapsed) colors.canvas else Color.Transparent)
    val cardBlend = remember(dark) { if (dark) listOf(BlendColorEntry(Color(0x757A7A7A), BlurBlendMode.Luminosity))
        else listOf(BlendColorEntry(Color(0x340034F9), BlurBlendMode.Overlay), BlendColorEntry(Color(0xB3FFFFFF), BlurBlendMode.HardLight)) }
    val titleBlend = remember(dark) { if (dark) listOf(
        BlendColorEntry(Color(0xe6a1a1a1), BlurBlendMode.ColorDodge), BlendColorEntry(Color(0x4de6e6e6), BlurBlendMode.LinearLight),
        BlendColorEntry(Color(0xff1af500), BlurBlendMode.Lab)) else listOf(
        BlendColorEntry(Color(0xcc4a4a4a), BlurBlendMode.ColorBurn), BlendColorEntry(Color(0xff4f4f4f), BlurBlendMode.LinearLight),
        BlendColorEntry(Color(0xff1af200), BlurBlendMode.Lab)) }
    val bottom = maxOf(LocalBottomInset.current, WindowInsets.ime.asPaddingValues().calculateBottomPadding())
    Scaffold(containerColor = colors.canvas, contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal), topBar = {
        SmallTopAppBar(title = "关于", scrollBehavior = behavior, modifier = header, color = Color.Transparent,
            titleColor = colors.text.copy(alpha = progress.value), navigationIcon = {
                IconButton(onClick = back, modifier = Modifier.size(48.dp)) { Icon(MiuixIcons.Back, "返回") }
            })
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(start = padding.calculateStartPadding(LocalLayoutDirection.current),
            end = padding.calculateEndPadding(LocalLayoutDirection.current)).then(page?.let { Modifier.layerBackdrop(it) } ?: Modifier)) {
            val compact = maxHeight < 520.dp
            val heroTop = if (compact) 24.dp else 92.dp
            val minimumContentHeight = (maxHeight - padding.calculateTopPadding() - padding.calculateBottomPadding()).coerceAtLeast(0.dp)
            AboutBackground(!collapsed, cloud, alpha = { 1f - progress.value })
            Column(Modifier.fillMaxWidth().padding(top = padding.calculateTopPadding() + heroTop).onSizeChanged { logoHeight = it.height },
                horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(R.drawable.launcher_art), null, Modifier.size(if (compact) 72.dp else 88.dp).graphicsLayer {
                    val p = ((progress.value - .35f) / .15f).coerceIn(0f, 1f); alpha = 1f - p; scaleX = 1f - p * .05f; scaleY = scaleX
                }.clip(RoundedCornerShape(22.dp)))
                Text("FlipDuo", fontWeight = FontWeight.Bold, fontSize = 35.sp, lineHeight = 44.sp,
                    modifier = Modifier.padding(top = 12.dp, bottom = 5.dp).graphicsLayer {
                        val p = ((progress.value - .20f) / .15f).coerceIn(0f, 1f); alpha = 1f - p; scaleX = 1f - p * .05f; scaleY = scaleX
                    }.then(if (cloud == null) Modifier else Modifier.textureBlur(cloud, shape = RoundedCornerShape(16.dp), blurRadius = 150f,
                        colors = BlurColors(blendColors = titleBlend), contentBlendMode = BlendMode.DstIn)))
                Text("版本 ${BuildConfig.VERSION_NAME}", color = colors.secondary, fontSize = 14.sp, lineHeight = 20.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().graphicsLayer {
                        val p = ((progress.value - .05f) / .15f).coerceIn(0f, 1f); alpha = 1f - p; scaleX = 1f - p * .05f; scaleY = scaleX
                    })
            }
            val logoDp = with(LocalDensity.current) { logoHeight.toDp() }
            LazyColumn(state = scroll, overscrollEffect = null,
                modifier = Modifier.fillMaxSize().then(if (appearance.haptics && active) Modifier.scrollEndHaptic() else Modifier)
                    .overScrollVertical(isEnabled = { active && motion > 0f }).nestedScroll(behavior.nestedScrollConnection),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = bottom + padding.calculateBottomPadding())) {
                item(key = "identity_space") { Spacer(Modifier.fillMaxWidth().height(logoDp + if (compact) 64.dp else 218.dp).onSizeChanged { spacerHeight = it.height }) }
                item(key = "about_content") {
                    Column(Modifier.fillMaxWidth().heightIn(min = minimumContentHeight).padding(12.dp)) {
                        Card(Modifier.fillMaxWidth().then(if (cloud == null) Modifier else Modifier.textureBlur(cloud,
                            shape = RoundedCornerShape(16.dp), blurRadius = 60f, colors = BlurColors(blendColors = cardBlend))),
                            insideMargin = PaddingValues(0.dp), colors = CardDefaults.defaultColors(if (cloud == null) colors.surface else Color.Transparent, Color.Transparent)) {
                             BasicComponent(title = "适配设备", summary = "Xiaomi MIX Flip / MIX Flip 2")
                             ArrowPreference(title = "获取更新", summary = "密码:26wa",
                                 onClick = ({ context.startActivity(updateDownloadIntent()) }).takeIf { active })
                             ArrowPreference(title = "作者酷安", summary = "查看作者主页",
                                 onClick = ({ context.startActivity(authorCoolapkIntent()) }).takeIf { active })
                             ArrowPreference(title = "MixFlipMod", summary = "parallelcc · 外屏解锁与最近任务功能来源",
                                 onClick = ({ context.startActivity(mixFlipModIntent()) }).takeIf { active })
                            ArrowPreference(title = "开源许可", summary = "项目许可与第三方致谢", onClick = license.takeIf { active })
                        }
                    }
                }
            }
        }
    }
}

internal fun authorCoolapkIntent() = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.coolapk.com/u/2257485"))
    .addCategory(Intent.CATEGORY_BROWSABLE)

internal fun updateDownloadIntent() = Intent(Intent.ACTION_VIEW, Uri.parse("https://wwbjk.lanzouq.com/b0po0ummj"))
    .addCategory(Intent.CATEGORY_BROWSABLE)

internal fun mixFlipModIntent() = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/parallelcc/MixFlipMod"))
    .addCategory(Intent.CATEGORY_BROWSABLE)

@Composable
internal fun LicensePage(back: () -> Unit) {
    val context = LocalContext.current
    val entries = remember { listOf(
        LicenseEntry("FlipDuo", "GPL 派生代码；组合的菜单移植许可见 MixFlipMod 条目", "GPL-3.0-only", "LICENSE"),
        LicenseEntry("miuix", "0.9.3 · 界面、导航与模糊组件", "Apache-2.0", "Apache-2.0.txt"),
        LicenseEntry("libxposed", "API 101.0.1 · Service 102.0.0", "Apache-2.0", "Apache-2.0.txt"),
        LicenseEntry("AndroidX 与 Compose", "Activity、Lifecycle、Navigation 与 Compose", "Apache-2.0", "Apache-2.0.txt"),
        LicenseEntry("Kotlin 与 kotlinx.coroutines", "语言与协程支持", "Apache-2.0", "Apache-2.0.txt"),
        LicenseEntry("派生界面代码", "PlayBilling、InstallerX-Revived 与 KernelSU", "GPL-3.0-only", "LICENSE"),
        LicenseEntry("MixFlipMod", "parallelcc · 外屏解锁与最近任务功能来源，卡片长按菜单直接移植", "AGPL-3.0", "MixFlipMod-AGPL-3.0.txt"),
    ) }
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    val entry = selected?.let(entries::get)
    BackHandler(enabled = entry != null) { selected = null }
    val goBack: () -> Unit = { if (entry == null) back() else selected = null }
    val paragraphs by produceState<List<String>?>(null, entry?.file) {
        value = entry?.let { item -> withContext(Dispatchers.IO) {
            context.assets.open(item.file).bufferedReader().use { it.readText() }
                .trim().split(Regex("\\n\\s*\\n")).map { it.trim().replace(Regex("[ \\t]*\\n[ \\t]*"), " ") }
        } }
    }
    PageFrame(entry?.title ?: "开源许可", onBack = goBack) {
        if (entry == null) {
            item { Text("感谢这些开源项目", color = uiColors().secondary, fontSize = 14.sp) }
            item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                entries.forEachIndexed { index, item ->
                    ArrowPreference(title = item.title, summary = "${item.description}\n${item.license}", onClick = { selected = index })
                }
            } }
            item { Text("原作者的版权与来源声明保留在相应源码中。按钮图标为原创绘制；应用图标为原创生成素材。", color = uiColors().secondary, fontSize = 13.sp, lineHeight = 20.sp) }
        } else {
            item { Text(entry.license, fontWeight = FontWeight.Medium, color = uiColors().secondary, fontSize = 14.sp) }
            if (entry.title == "MixFlipMod") item {
                ArrowPreference(title = "项目主页", summary = "github.com/parallelcc/MixFlipMod",
                    onClick = { context.startActivity(mixFlipModIntent()) })
            }
            if (paragraphs == null) item { Text("正在读取许可…", color = uiColors().secondary) }
            else items(paragraphs!!) { paragraph ->
                val heading = paragraph.length < 100 && (paragraph.matches(Regex("^[0-9]+\\..*")) || paragraph.uppercase() == paragraph)
                SelectionContainer { Text(paragraph, fontSize = if (heading) 16.sp else 14.sp, lineHeight = if (heading) 24.sp else 23.sp,
                    fontWeight = if (heading) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth().padding(vertical = if (heading) 8.dp else 4.dp)) }
            }
        }
    }
}

private data class LicenseEntry(val title: String, val description: String, val license: String, val file: String)
