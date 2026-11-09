// SPDX-License-Identifier: GPL-3.0-only
// Adapted for FlipDuo from PlayBilling; original upstream notices retained.
// Closely adapted from KernelSU FloatingBottomBar.kt @ befbcd388bb6c10befd3645311ad08e556d362ac.
// From SoftVoWiFi cea7db5a; theme references changed, corrected barBackdrop retained.
package com.flipduo.module.ui.chrome

import com.flipduo.module.ui.uiColors
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.flipduo.module.ui.chrome.liquid.*
import top.yukonga.miuix.kmp.blur.*

@Composable
internal fun SlidingBarLayers(
    modifier: Modifier, items: List<SlidingBarItem>, selectedIndex: Int, onSelected: (Int) -> Unit,
    liquidEnabled: Boolean, backdrop: LayerBackdrop?, tabWidthPx: Float, panelOffset: Float,
    drag: DampedDragAnimation, position: () -> Float, ltr: Boolean, onMeasured: (Int) -> Unit,
) {
    if (liquidEnabled && backdrop != null) {
        val barBackdrop = rememberLayerBackdrop()
        val combinedBackdrop = rememberCombinedBackdrop(backdrop, barBackdrop)
        val highlight = rememberInteractiveHighlight(rememberCoroutineScope()) { size, _ ->
            androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        }
        BarFrame(modifier, items, selectedIndex, onSelected, panelOffset, onMeasured, backdrop, barBackdrop) {
            LiquidIndicator(tabWidthPx, panelOffset, drag, ltr, combinedBackdrop, highlight, position)
        }
    } else {
        BarFrame(modifier, items, selectedIndex, onSelected, panelOffset, onMeasured, null, null) {
            OpaqueIndicator(tabWidthPx, panelOffset, drag, ltr, position)
        }
    }
}

@Composable
private fun BarFrame(
    modifier: Modifier, items: List<SlidingBarItem>, selectedIndex: Int, onSelected: (Int) -> Unit,
    panelOffset: Float, onMeasured: (Int) -> Unit, backdrop: Backdrop?, capture: LayerBackdrop?,
    overlay: @Composable () -> Unit,
) {
    val colors = uiColors()
    val density = LocalDensity.current
    val layout = floatingRowLayoutContract()
    val isInDark = isSystemInDarkTheme()
    val pillShape = CircleShape
    val base = (capture?.let { Modifier.layerBackdrop(it) } ?: Modifier)
        .graphicsLayer { translationX = panelOffset }
        .dropShadow(shape = pillShape, shadow = Shadow(radius = 10.dp, color = Color.Black, alpha = if (isInDark) 0.2f else 0.1f))
        .then(if (backdrop == null) Modifier.background(colors.surface, pillShape) else Modifier.drawBackdrop(
            backdrop = backdrop, shape = { pillShape }, effects = {
                vibrancy()
                blur(with(density) { 4.dp.toPx() }, with(density) { 4.dp.toPx() })
                lens(with(density) { 24.dp.toPx() }, with(density) { 24.dp.toPx() })
            }, highlight = { PillSpecular }, onDrawSurface = { drawRect(colors.surface.copy(alpha = 0.40f)) },
        ))
    Box(modifier.width(IntrinsicSize.Min), contentAlignment = Alignment.CenterStart) {
        Row(modifier = base.onSizeChanged { onMeasured(it.width) }.height(layout.outerHeightDp.dp)
            .padding(layout.innerPaddingDp.dp).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            TabContent(items, selectedIndex, onSelected, selectable = true)
        }
        overlay()
    }
}
