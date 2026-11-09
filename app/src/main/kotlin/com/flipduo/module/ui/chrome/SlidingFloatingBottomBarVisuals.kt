// SPDX-License-Identifier: GPL-3.0-only
// Adapted for FlipDuo from PlayBilling; original upstream notices retained.
// Closely adapted from KernelSU FloatingBottomBar.kt @ befbcd388bb6c10befd3645311ad08e556d362ac.
// From SoftVoWiFi cea7db5a; theme changed, SMS badges and business destinations removed.
package com.flipduo.module.ui.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flipduo.module.ui.LocalRouteActive
import com.flipduo.module.ui.uiColors
import com.flipduo.module.ui.chrome.liquid.*
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.blur.*
import top.yukonga.miuix.kmp.blur.highlight.*

@Composable
internal fun LiquidIndicator(
    tabWidthPx: Float, panelOffset: Float, drag: DampedDragAnimation, ltr: Boolean,
    backdrop: Backdrop, highlight: InteractiveHighlight, position: () -> Float = { drag.value },
) {
    if (tabWidthPx <= 0f) return
    val density = LocalDensity.current
    val colors = uiColors()
    val accent = MiuixTheme.colorScheme.primary
    val layout = floatingRowLayoutContract()
    Box(Modifier.padding(horizontal = layout.innerPaddingDp.dp)
        .graphicsLayer { translationX = bubbleTranslationPx(position(), tabWidthPx, !ltr) + panelOffset }
        .then(highlight.gestureModifier).then(drag.modifier)
        .drawBackdrop(backdrop = backdrop, shape = { androidx.compose.foundation.shape.CircleShape }, effects = {
            val progress = drag.pressProgress
            lens(refractionHeight = with(density) { 10.dp.toPx() } * progress,
                refractionAmount = with(density) { 14.dp.toPx() } * progress, depthEffect = true, chromaticAberration = 0.5f)
        }, highlight = { PillSpecular.copy(alpha = drag.pressProgress) }, layerBlock = {
            scaleX = drag.scaleX
            scaleY = drag.scaleY
            val velocity = drag.velocity / 10f
            scaleX /= 1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f)
            scaleY *= 1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f)
        }, onDrawSurface = {
            val progress = drag.pressProgress
            drawRect(colors.text.copy(alpha = 0.10f * (1f - progress)))
            drawRect(accent.copy(alpha = 0.10f * progress))
        })
        .innerShadow(androidx.compose.foundation.shape.CircleShape) {
            InnerShadow(8.dp * drag.pressProgress, color = Color.Black.copy(alpha = 0.15f), alpha = drag.pressProgress)
        }.then(highlight.modifier).height(layout.indicatorHeightDp.dp).width(with(density) { tabWidthPx.toDp() }))
}

@Composable
internal fun OpaqueIndicator(tabWidthPx: Float, panelOffset: Float, drag: DampedDragAnimation, ltr: Boolean, position: () -> Float = { drag.value }) {
    if (tabWidthPx <= 0f) return
    val density = LocalDensity.current
    val accent = MiuixTheme.colorScheme.primary
    val layout = floatingRowLayoutContract()
    Box(Modifier.padding(horizontal = layout.innerPaddingDp.dp).graphicsLayer {
        translationX = bubbleTranslationPx(position(), tabWidthPx, !ltr) + panelOffset
        scaleX = drag.scaleX
        scaleY = drag.scaleY
    }.then(drag.modifier).clip(androidx.compose.foundation.shape.CircleShape)
        .background(accent.copy(alpha = 0.15f), androidx.compose.foundation.shape.CircleShape)
        .height(layout.indicatorHeightDp.dp).width(with(density) { tabWidthPx.toDp() }))
}

@Composable
internal fun RowScope.TabContent(items: List<SlidingBarItem>, selectedIndex: Int, onSelected: (Int) -> Unit, selectable: Boolean) {
    val colors = uiColors()
    val layout = floatingRowLayoutContract()
    val focusIndication = rememberManagerKeyboardFocusIndication(LocalRouteActive.current)
    items.forEachIndexed { index, item ->
        val tint = rememberBottomTint(if (index == selectedIndex) MiuixTheme.colorScheme.primary else colors.text)
        val tab = Modifier.defaultMinSize(layout.tabMinimumWidthDp.dp, layout.tabMinimumHeightDp.dp).fillMaxHeight().weight(1f)
        Column(modifier = if (selectable) tab.selectable(selected = index == selectedIndex, interactionSource = null,
            indication = focusIndication, role = Role.Tab, onClick = { onSelected(index) }) else tab,
            verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painter = rememberVectorPainter(item.icon), contentDescription = null, tint = tint)
            Text(item.label, color = tint, fontSize = layout.labelSizeSp.sp, lineHeight = layout.labelLineHeightSp.sp, fontWeight = FontWeight.Normal)
        }
    }
}

internal val PillSpecular = Highlight(width = 1.dp, alpha = 0.75f, style = BloomStroke(
    color = Color.White.copy(alpha = 0.12f), innerBlurRadius = 2.dp,
    primaryLight = LightSource(LightPosition(0.5f, -0.3f, -0.05f), Color.White, 1f),
    secondaryLight = LightSource(LightPosition(0.5f, 0.8f, -0.5f), Color.White, 0.4f), dualPeak = true,
))
