// SPDX-License-Identifier: GPL-3.0-only
// Adapted for FlipDuo from PlayBilling; original upstream notices retained.
package com.flipduo.module.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flipduo.module.ui.about.MiuixCloudPainter
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlinx.coroutines.isActive

internal fun aboutCloudPlaying(active: Boolean, resumed: Boolean, visible: Boolean, supported: Boolean, scale: Float) =
    active && resumed && visible && supported && scale > 0f

/** Only the background draw phase changes per frame; the list, controls and text never recompose per frame. */
@Composable
internal fun AboutBackground(visible: Boolean, capture: LayerBackdrop? = null, alpha: () -> Float = { 1f }) {
    val dark = isSystemInDarkTheme()
    val colors = uiColors()
    val supported = LocalShaderSupported.current
    val painter = remember(supported, dark) {
        if (!supported) null else try { MiuixCloudPainter(dark) } catch (_: IllegalArgumentException) { null }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val scale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    val playing = aboutCloudPlaying(LocalRouteActive.current, resumed, visible, painter != null, scale)
    var time by remember { mutableFloatStateOf(0f) }
    val colorStage = remember { Animatable(0f) }
    LaunchedEffect(playing, dark, scale) {
        if (!playing) return@LaunchedEffect
        var target = floor(colorStage.value) + 1f
        while (isActive) {
            delay(((if (dark) 8_000L else 5_000L) * 0.5f * scale).toLong().coerceAtLeast(1))
            colorStage.animateTo(target, spring(dampingRatio = 0.9f, stiffness = 35f))
            target++
        }
    }
    LaunchedEffect(playing, scale) {
        if (!playing) return@LaunchedEffect
        val offset = time
        val origin = withFrameNanos { it }
        var previous = origin
        while (isActive) {
            val frame = withFrameNanos { it }
            if (frame - previous >= 16_666_667L) {
                time = offset + (frame - origin) / 1_000_000_000f / scale
                previous = frame
            }
        }
    }
    Canvas(Modifier.fillMaxSize().then(capture?.let { Modifier.layerBackdrop(it) } ?: Modifier)
        .testTag(if (playing) "about_cloud_playing" else "about_cloud_static")) {
        drawRect(colors.canvas)
        if (painter != null && size.width > 0 && size.height > 0) {
            painter.update(size.width, size.height, if (scale > 0f) time else 0f, colorStage.value)
            drawRect(painter.brush, alpha = alpha().coerceIn(0f, 1f))
        }
    }
}
