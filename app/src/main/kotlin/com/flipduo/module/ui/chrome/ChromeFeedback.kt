// SPDX-License-Identifier: GPL-3.0-only
// Adapted for FlipDuo from PlayBilling; original upstream notices retained.
// Public API adaptation of SoftVoWiFi cea7db5a keyboard focus and bottom tint mechanisms.
package com.flipduo.module.ui.chrome

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import com.flipduo.module.ui.uiColors
import kotlinx.coroutines.launch

@Composable
internal fun rememberBottomTint(target: Color): ColorProducer {
    val color = animateColorAsState(target, tween(150, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)), label = "bottom-tint")
    return remember(color) { ColorProducer { color.value } }
}

@Composable
internal fun rememberManagerKeyboardFocusIndication(enabled: Boolean): IndicationNodeFactory {
    val gate = rememberUpdatedState(enabled)
    val ink = rememberUpdatedState(uiColors().accent)
    val input = rememberUpdatedState(LocalInputModeManager.current)
    return remember { KeyboardFocusIndication(gate, ink, input) }
}

private class KeyboardFocusIndication(
    val gate: State<Boolean>, val ink: State<Color>, val input: State<InputModeManager>,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = FocusNode(interactionSource, gate, ink, input)
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 0
}

private class FocusNode(val source: InteractionSource, val gate: State<Boolean>, val ink: State<Color>, val input: State<InputModeManager>) : Modifier.Node(), DrawModifierNode {
    private var focused by mutableStateOf(false)
    override fun onAttach() {
        coroutineScope.launch { source.interactions.collect {
            when (it) { is FocusInteraction.Focus -> focused = true; is FocusInteraction.Unfocus -> focused = false }
        } }
    }
    override fun onDetach() { focused = false }
    override fun ContentDrawScope.draw() {
        drawContent()
        if (gate.value && focused && input.value.inputMode == InputMode.Keyboard) {
            drawRect(ink.value, style = Stroke(4.dp.toPx()))
        }
    }
}
