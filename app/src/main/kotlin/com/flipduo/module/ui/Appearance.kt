// SPDX-License-Identifier: GPL-3.0-only
// Adapted for FlipDuo from PlayBilling; original upstream notices retained.
package com.flipduo.module.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.yukonga.miuix.kmp.theme.MiuixTheme

data class Appearance(
    val floating: Boolean = true,
    val blur: Boolean = true,
    val liquid: Boolean = true,
    val predictiveBack: Boolean = false,
    val haptics: Boolean = true,
)

class AppearanceStore(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val defaults = Appearance()
    private val mutable = MutableStateFlow(Appearance(
        prefs.getBoolean("floating_nav", defaults.floating), prefs.getBoolean("blur_enabled", defaults.blur),
        prefs.getBoolean("liquid_glass_enabled", defaults.liquid), prefs.getBoolean("predictive_back_enabled", defaults.predictiveBack),
        prefs.getBoolean("haptics_enabled", defaults.haptics),
    ))
    val state = mutable.asStateFlow()

    fun set(key: String, enabled: Boolean) {
        val old = mutable.value
        mutable.value = when (key) {
            "floating_nav" -> old.copy(floating = enabled)
            "blur_enabled" -> old.copy(blur = enabled)
            "liquid_glass_enabled" -> old.copy(liquid = enabled)
            "predictive_back_enabled" -> old.copy(predictiveBack = enabled)
            "haptics_enabled" -> old.copy(haptics = enabled)
            else -> error("Unknown appearance preference")
        }
        prefs.edit().putBoolean(key, enabled).apply()
    }
}

internal val LocalRouteActive = staticCompositionLocalOf { true }
internal val LocalBottomInset = staticCompositionLocalOf { androidx.compose.ui.unit.Dp(0f) }

internal data class UiColors(val canvas: Color, val surface: Color, val text: Color, val secondary: Color, val accent: Color)

@Composable
internal fun uiColors() = UiColors(
    MiuixTheme.colorScheme.surface, MiuixTheme.colorScheme.surfaceContainer,
    MiuixTheme.colorScheme.onSurface, MiuixTheme.colorScheme.onSurfaceVariantSummary,
    MiuixTheme.colorScheme.primary,
)

internal data class GlassSpec(val floating: Boolean, val effectiveBlur: Boolean, val effectiveLiquidGlass: Boolean)
internal fun glassSpec(appearance: Appearance, supported: Boolean) = GlassSpec(
    appearance.floating, appearance.blur && supported, appearance.floating && appearance.liquid && supported,
)
