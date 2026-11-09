// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics

/** Manager motion follows Android's animation scale, independently of the cover-controls switch. */
internal object ManagerMotion {
    const val PAGE_MS = 240
    const val DISCLOSURE_MS = 180
    val easing = CubicBezierEasing(.2f, .8f, .2f, 1f)
}

@Composable
internal fun managerMotionEnabled(): Boolean =
    (rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) > 0f

@Composable
internal fun Modifier.managerDisclosure(): Modifier = animateContentSize(
    animationSpec = tween(if (managerMotionEnabled()) ManagerMotion.DISCLOSURE_MS else 0, easing = ManagerMotion.easing))

/** Outgoing layout-panel controls must not save changes while the next panel is sliding in. */
internal fun Modifier.managerTransitionInput(blocked: Boolean): Modifier = if (!blocked) this else
    clearAndSetSemantics {}.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
