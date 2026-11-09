// SPDX-License-Identifier: GPL-3.0-only
// Adapted for FlipDuo from PlayBilling; original upstream notices retained.
// Geometry/formulas adapted from SoftVoWiFi cea7db5a, SlidingBottomBarContract/KsuParityContracts.
package com.flipduo.module.ui.chrome

import androidx.compose.animation.core.EaseOut
import kotlin.math.abs
import kotlin.math.sign

internal data class FloatingGeometry(
    val outerHeightDp: Int = 64,
    val indicatorHeightDp: Int = 56,
    val tabMinimumWidthDp: Int = 76,
    val tabMinimumHeightDp: Int = 48,
    val innerPaddingDp: Int = 4,
    val labelSizeSp: Int = 11,
    val labelLineHeightSp: Int = 14,
    val rubberBandDp: Int = 4,
    val pressedScale: Float = 78f / 56f,
)

internal fun floatingRowLayoutContract() = FloatingGeometry()
internal fun floatingBubbleBarContract() = FloatingGeometry()
internal fun bubbleTranslationPx(position: Float, tabWidthPx: Float, rtl: Boolean) = position * tabWidthPx * if (rtl) -1f else 1f

internal fun absolutePointerBubblePosition(
    pointerInBarPx: Float, grabOffsetPx: Float, barWidthPx: Float, tabWidthPx: Float,
    innerPaddingPx: Float, panelOffsetPx: Float, tabCount: Int, rtl: Boolean,
): Float {
    if (tabWidthPx <= 0f) return 0f
    val origin = pointerInBarPx - grabOffsetPx - panelOffsetPx
    val position = if (rtl) (barWidthPx - innerPaddingPx - tabWidthPx - origin) / tabWidthPx else (origin - innerPaddingPx) / tabWidthPx
    return position.coerceIn(0f, (tabCount - 1).toFloat())
}

internal fun rubberBandPanelOffset(rawOffsetPx: Float, totalWidthPx: Float, rubberBandPx: Float): Float {
    if (totalWidthPx <= 0f) return 0f
    val fraction = (rawOffsetPx / totalWidthPx).coerceIn(-1f, 1f)
    return rubberBandPx * fraction.sign * EaseOut.transform(abs(fraction))
}
