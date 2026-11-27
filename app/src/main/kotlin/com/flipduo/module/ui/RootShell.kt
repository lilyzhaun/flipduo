// SPDX-License-Identifier: GPL-3.0-only
// Adapted from PlayBilling RootShell; original single-pager and material contracts retained.
package com.flipduo.module.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.flipduo.module.ui.chrome.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Settings

internal enum class RootPage { HOME, LAYOUT, SETTINGS }
@Composable
internal fun RootShell(covered: Boolean, content: @Composable (RootPage) -> Unit) {
    val colors = uiColors()
    val spec = glassSpec(LocalAppearance.current, LocalShaderSupported.current)
    // Only the visible liquid bottom bar samples the pager. Page headers own their own backdrop.
    val backdrop = if (!covered && spec.effectiveLiquidGlass) rememberLayerBackdrop { drawRect(colors.canvas); drawContent() } else null
    val pager = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var job by remember { mutableStateOf<Job?>(null) }
    val entrance = remember { Animatable(0f) }
    var selection by remember { mutableIntStateOf(pager.currentPage) }
    val gate by rememberUpdatedState(!covered)
    val haptic = LocalHapticFeedback.current
    val motion = managerMotionEnabled()
    fun select(index: Int) {
        if (!gate) return
        if (selection == index) return
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        selection = index; job?.cancel()
        job = scope.launch {
            val distance = index - pager.currentPage
            if (motion && abs(distance) == 1) {
                entrance.snapTo(0f)
                pager.animateScrollToPage(index, animationSpec = tween(ManagerMotion.PAGE_MS, easing = ManagerMotion.easing))
            } else {
                // A direct tab tap is not a swipe through the intervening editor. Enter just the destination.
                pager.scrollToPage(index)
                entrance.snapTo(if (motion && abs(distance) > 1) distance.compareTo(0).toFloat() else 0f)
                if (entrance.value != 0f) entrance.animateTo(0f, tween(ManagerMotion.DISCLOSURE_MS, easing = ManagerMotion.easing))
            }
        }
    }
    LaunchedEffect(pager) {
        pager.interactionSource.interactions.collect { if (it is DragInteraction.Start) {
            job?.cancel(); entrance.snapTo(0f); selection = pager.currentPage
        } }
    }
    LaunchedEffect(pager) {
        snapshotFlow { if (pager.isScrollInProgress) pager.targetPage else pager.settledPage }.collect { if (job?.isActive != true) selection = it }
    }
    BackHandler(!covered && selection != 0) { select(0) }
    val items = remember { listOf(SlidingBarItem("首页", MiuixIcons.Home), SlidingBarItem("布局", MiuixIcons.File), SlidingBarItem("设置", MiuixIcons.Settings)) }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(colors.canvas).then(backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier)
            .then(if (covered) Modifier.clearAndSetSemantics {} else Modifier)) {
            HorizontalPager(pager, modifier = Modifier.fillMaxSize().graphicsLayer {
                translationX = entrance.value * 28.dp.toPx() * (if (rtl) -1 else 1)
            }, userScrollEnabled = !covered, beyondViewportPageCount = 0) { index ->
                CompositionLocalProvider(LocalRouteActive provides (!covered && index == selection), LocalBottomInset provides (88.dp + bottom)) {
                    content(RootPage.entries[index])
                }
            }
        }
        if (!covered) CompositionLocalProvider(LocalRouteActive provides true) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + 12.dp)) {
                AnimatedContent(spec.floating, transitionSpec = { fadeIn(tween(if (motion) ManagerMotion.DISCLOSURE_MS else 0)) togetherWith
                        fadeOut(tween(if (motion) ManagerMotion.DISCLOSURE_MS else 0)) }, label = "navigation-mode") { floating ->
                    if (floating) SlidingFloatingBottomBar(selection, ::select, spec, backdrop, items, pagerState = pager)
                    else NavigationBar(color = colors.surface, mode = NavigationBarDisplayMode.IconAndText) {
                        items.forEachIndexed { i, item -> NavigationBarItem(selection == i, { select(i) }, item.icon, item.label) }
                    }
                }
            }
        }
        if (covered) Box(Modifier.fillMaxSize().clearAndSetSemantics {}.pointerInput(Unit) {
            awaitEachGesture {
                do {
                    val event = awaitPointerEvent(); event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        })
    }
}
