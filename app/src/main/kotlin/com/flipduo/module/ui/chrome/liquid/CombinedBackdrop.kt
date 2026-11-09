// SPDX-License-Identifier: GPL-3.0-only
// Adapted for FlipDuo from PlayBilling; original upstream notices retained.
// Closely adapted from KernelSU liquid/CombinedBackdrop.kt @ befbcd388bb6c10befd3645311ad08e556d362ac
// and InstallerX-Revived liquid/CombinedBackdrop.kt @ 11f15e4740c21bbed3e3156ceeab643683ed1fcb.
// From SoftVoWiFi cea7db5a; package changed for PlayBilling.
package com.flipduo.module.ui.chrome.liquid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import top.yukonga.miuix.kmp.blur.Backdrop

@Stable
internal class CombinedBackdrop(private val first: Backdrop, private val second: Backdrop) : Backdrop {
    override val isCoordinatesDependent: Boolean = first.isCoordinatesDependent || second.isCoordinatesDependent
    override val offsetResidualX: Float get() = first.offsetResidualX
    override val offsetResidualY: Float get() = first.offsetResidualY
    override fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?, layerBlock: (GraphicsLayerScope.() -> Unit)?, downscaleFactor: Int) {
        with(first) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
        with(second) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
    }
}

@Composable
internal fun rememberCombinedBackdrop(first: Backdrop, second: Backdrop): Backdrop = remember(first, second) { CombinedBackdrop(first, second) }
