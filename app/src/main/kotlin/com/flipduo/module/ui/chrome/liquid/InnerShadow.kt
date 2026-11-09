// SPDX-License-Identifier: GPL-3.0-only
// Adapted for FlipDuo from PlayBilling; original upstream notices retained.
// Closely adapted from KernelSU liquid/InnerShadow.kt @ befbcd388bb6c10befd3645311ad08e556d362ac
// and InstallerX-Revived liquid/InnerShadow.kt @ 11f15e4740c21bbed3e3156ceeab643683ed1fcb.
// From SoftVoWiFi cea7db5a; package changed for PlayBilling.
package com.flipduo.module.ui.chrome.liquid

import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.*
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

@Immutable
internal data class InnerShadow(val radius: Dp = 24.dp, val offset: DpOffset = DpOffset(0.dp, radius),
    val color: Color = Color.Black.copy(alpha = 0.15f), val alpha: Float = 1f, val blendMode: BlendMode = BlendMode.SrcOver)

internal fun Modifier.innerShadow(shape: Shape, shadow: () -> InnerShadow?): Modifier = this then InnerShadowElement(shape, shadow)

private class InnerShadowElement(private val shape: Shape, private val shadow: () -> InnerShadow?) : ModifierNodeElement<InnerShadowNode>() {
    override fun create() = InnerShadowNode(shape, shadow)
    override fun update(node: InnerShadowNode) { node.shape = shape; node.shadow = shadow; node.invalidateDraw() }
    override fun InspectorInfo.inspectableProperties() { name = "innerShadow" }
    override fun equals(other: Any?) = other is InnerShadowElement && shape == other.shape && shadow == other.shadow
    override fun hashCode() = 31 * shape.hashCode() + shadow.hashCode()
}

private class InnerShadowNode(var shape: Shape, var shadow: () -> InnerShadow?) : Modifier.Node(), DrawModifierNode {
    override val shouldAutoInvalidate = false
    private var shadowLayer: GraphicsLayer? = null
    private val paint = Paint()
    private val clipPath = Path()
    private var previousRadius = Float.NaN
    override fun ContentDrawScope.draw() {
        drawContent()
        val shadow = shadow() ?: return
        val layer = shadowLayer ?: return
        val outline = shape.createOutline(size, layoutDirection, this)
        clipPath.reset()
        when (outline) {
            is Outline.Rectangle -> clipPath.addRect(outline.rect)
            is Outline.Rounded -> clipPath.addRoundRect(outline.roundRect)
            is Outline.Generic -> clipPath.addPath(outline.path)
        }
        paint.color = shadow.color
        layer.alpha = shadow.alpha
        layer.blendMode = shadow.blendMode
        val radius = shadow.radius.toPx()
        // Resolve density before recording: LayoutNodeDrawScope.record temporarily
        // uses the parent DrawScope as density, so reading toPx inside can recurse.
        val offsetX = shadow.offset.x.toPx()
        val offsetY = shadow.offset.y.toPx()
        if (previousRadius != radius) {
            layer.renderEffect = if (radius > 0f) BlurEffect(radius, radius, TileMode.Decal) else null
            previousRadius = radius
        }
        layer.record {
            drawContext.canvas.save()
            drawContext.canvas.clipPath(clipPath)
            drawContext.canvas.drawOutline(outline, paint)
            drawContext.canvas.translate(offsetX, offsetY)
            drawContext.canvas.drawOutline(outline, MaskPaint)
            drawContext.canvas.restore()
        }
        drawContext.canvas.save()
        drawContext.canvas.clipPath(clipPath)
        drawLayer(layer)
        drawContext.canvas.restore()
    }
    override fun onAttach() { shadowLayer = requireGraphicsContext().createGraphicsLayer().apply { compositingStrategy = CompositingStrategy.Offscreen } }
    override fun onDetach() { shadowLayer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }; shadowLayer = null }
}

private val MaskPaint = Paint().apply { blendMode = BlendMode.Clear }
