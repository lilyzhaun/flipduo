// SPDX-License-Identifier: GPL-3.0-only
// Adapted for FlipDuo from PlayBilling; original upstream notices retained.
// Page ownership/layout follows SoftVoWiFi cea7db5a AppPageScaffold; no business copied.
package com.flipduo.module.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.testTag
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

internal val LocalAppearance = staticCompositionLocalOf { Appearance() }
internal val LocalShaderSupported = staticCompositionLocalOf { false }
internal val LocalReselectEpoch = staticCompositionLocalOf { 0 }

@Composable
internal fun PageFrame(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    itemSpacing: Dp = 12.dp,
    scrollState: LazyListState? = null,
    pinnedContent: (@Composable (Dp) -> Unit)? = null,
    pinnedBody: (@Composable (PaddingValues, Modifier) -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val colors = uiColors()
    val appearance = LocalAppearance.current
    val effectiveBlur = appearance.blur && LocalShaderSupported.current
    val backdrop = if (effectiveBlur) rememberLayerBackdrop {
        drawRect(colors.canvas.copy(alpha = 1f))
        drawContent()
    } else null
    val defaultScroll = rememberLazyListState()
    val scroll = scrollState ?: defaultScroll
    val behavior = MiuixScrollBehavior(rememberTopAppBarState())
    val active = LocalRouteActive.current
    val motion = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    val reselect = LocalReselectEpoch.current
    LaunchedEffect(reselect) {
        if (reselect > 0) {
            if (scroll.firstVisibleItemIndex > 40) scroll.scrollToItem(30)
            if (motion > 0f) scroll.animateScrollToItem(0) else scroll.scrollToItem(0)
            behavior.state.heightOffset = 0f
            behavior.state.contentOffset = 0f
        }
    }
    val headerBackdrop = backdrop.takeIf { onBack != null || behavior.state.overlappedFraction > 0f }
    val header = if (headerBackdrop != null) Modifier.drawBackdrop(headerBackdrop, shape = { RectangleShape }, effects = { blur(25f) },
        onDrawSurface = { drawRect(colors.canvas.copy(alpha = if (onBack != null) 0.92f else 0.72f + 0.20f * behavior.state.overlappedFraction.coerceIn(0f, 1f))) })
        else Modifier.background(colors.canvas)
    val bottom = maxOf(LocalBottomInset.current, WindowInsets.ime.asPaddingValues().calculateBottomPadding())
    val feedback = (if (appearance.haptics && active) Modifier.scrollEndHaptic() else Modifier)
        .overScrollVertical(isEnabled = { active && motion > 0f })
    Scaffold(containerColor = colors.canvas, contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal), topBar = {
        if (onBack == null && pinnedContent == null) TopAppBar(title = title, scrollBehavior = behavior, color = androidx.compose.ui.graphics.Color.Transparent,
            modifier = header, actions = actions)
        else SmallTopAppBar(title = title, color = androidx.compose.ui.graphics.Color.Transparent, modifier = header,
            navigationIcon = { if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(MiuixIcons.Back, "返回") } }, actions = actions)
    }) { padding ->
        Box(Modifier.fillMaxSize().background(colors.canvas).then(backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier)) {
            if (pinnedContent != null) BoxWithConstraints(Modifier.fillMaxHeight().widthIn(max = 720.dp).fillMaxWidth().align(Alignment.TopCenter)) {
                val top = padding.calculateTopPadding() + 12.dp
                val available = (maxHeight - top - bottom - padding.calculateBottomPadding()).coerceAtLeast(0.dp)
                Column(Modifier.fillMaxSize().padding(top = top)) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { pinnedContent(available) }
                    val bodyPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp,
                        bottom = bottom + padding.calculateBottomPadding() + 12.dp)
                    if (pinnedBody != null) Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                        pinnedBody(bodyPadding, feedback)
                    } else LazyColumn(state = scroll, overscrollEffect = null,
                        modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds().then(feedback),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp,
                            bottom = bottom + padding.calculateBottomPadding() + 12.dp),
                        verticalArrangement = Arrangement.spacedBy(itemSpacing), content = content)
                }
            } else LazyColumn(state = scroll, overscrollEffect = null,
                modifier = Modifier.fillMaxHeight().widthIn(max = 720.dp).fillMaxWidth().align(Alignment.TopCenter)
                    .then(feedback).then(if (onBack == null) Modifier.nestedScroll(behavior.nestedScrollConnection) else Modifier),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp,
                    bottom = bottom + padding.calculateBottomPadding() + 12.dp),
                verticalArrangement = Arrangement.spacedBy(itemSpacing), content = content)
        }
    }
}
