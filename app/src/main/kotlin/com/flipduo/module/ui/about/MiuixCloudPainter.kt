// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
// Adapted from miuix c36fab72391801d1e3ea5a00f966bf16bac28d4c example/component/effect/
// BgEffectPainter.kt and BgEffectConfig.kt. OS3-only; no demo controls or OS2 branch.
package com.flipduo.module.ui.about

import top.yukonga.miuix.kmp.blur.RuntimeShader
import top.yukonga.miuix.kmp.blur.asBrush
import kotlin.math.cos
import kotlin.math.sin

internal class MiuixCloudPainter(private val dark: Boolean) {
    private val shader = RuntimeShader(MIUIX_CLOUD_SHADER).apply {
        setFloatUniform("uTranslateY", 0f)
        setFloatUniform("uNoiseScale", 1.5f)
        setFloatUniform("uPointRadiusMulti", 1f)
        setFloatUniform("uAlphaMulti", 1f)
        setFloatUniform("uPoints", POINTS)
        setFloatUniform("uLightOffset", if (dark) 0f else 0.1f)
        setFloatUniform("uSaturateOffset", if (dark) 0.17f else 0.2f)
    }
    val brush = shader.asBrush()
    private val colors = FloatArray(16)
    private val points = FloatArray(8)
    private val palette = if (dark) DARK else LIGHT

    fun update(width: Float, height: Float, time: Float, stage: Float) {
        shader.setFloatUniform("uResolution", width, height)
        // Same portrait/landscape bound calculation as upstream's full-size background.
        if (width <= height) shader.setFloatUniform("uBound", 0f, 0.5f, 1f, 0.5f)
        else {
            val aspect = width / height
            shader.setFloatUniform("uBound", 0f, 0.75f - aspect / 2f, 1f, aspect)
        }
        shader.setFloatUniform("uAnimTime", time)
        fun color(index: Int) = palette[when (index.mod(4)) { 1 -> 0; 3 -> 2; else -> 1 }]
        val start = color(stage.toInt())
        val end = color(stage.toInt() + 1)
        val fraction = stage - stage.toInt()
        for (i in colors.indices) colors[i] = start[i] + (end[i] - start[i]) * fraction
        shader.setFloatUniform("uColors", colors)
        val offset = if (dark) 0.4f else 0.2f
        repeat(4) { i ->
            val x = POINTS[i * 3]; val y = POINTS[i * 3 + 1]
            val animX = x + sin(time + y) * offset
            points[i * 2] = animX
            points[i * 2 + 1] = y + cos(time + animX) * offset
        }
        shader.setFloatUniform("uPointsAnim", points)
    }

    private companion object {
        val POINTS = floatArrayOf(0.8f, 0.2f, 1f, 0.8f, 0.9f, 1f, 0.2f, 0.9f, 1f, 0.2f, 0.2f, 1f)
        val LIGHT = arrayOf(
            floatArrayOf(1f, 0.9f, 0.94f, 1f, 1f, 0.84f, 0.89f, 1f, 0.97f, 0.73f, 0.82f, 1f, 0.64f, 0.65f, 0.98f, 1f),
            floatArrayOf(0.58f, 0.74f, 1f, 1f, 1f, 0.9f, 0.93f, 1f, 0.74f, 0.76f, 1f, 1f, 0.97f, 0.77f, 0.84f, 1f),
            floatArrayOf(0.98f, 0.86f, 0.9f, 1f, 0.6f, 0.73f, 0.98f, 1f, 0.92f, 0.93f, 1f, 1f, 0.56f, 0.69f, 1f, 1f),
        )
        val DARK = arrayOf(
            floatArrayOf(0.2f, 0.06f, 0.88f, 0.4f, 0.3f, 0.14f, 0.55f, 0.5f, 0f, 0.64f, 0.96f, 0.5f, 0.11f, 0.16f, 0.83f, 0.4f),
            floatArrayOf(0.07f, 0.15f, 0.79f, 0.5f, 0.62f, 0.21f, 0.67f, 0.5f, 0.06f, 0.25f, 0.84f, 0.5f, 0f, 0.2f, 0.78f, 0.5f),
            floatArrayOf(0.58f, 0.3f, 0.74f, 0.4f, 0.27f, 0.18f, 0.6f, 0.5f, 0.66f, 0.26f, 0.62f, 0.5f, 0.12f, 0.16f, 0.7f, 0.6f),
        )
    }
}
