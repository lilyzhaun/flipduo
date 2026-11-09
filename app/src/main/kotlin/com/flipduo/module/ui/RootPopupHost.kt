// SPDX-License-Identifier: GPL-3.0-only
// Adapted for FlipDuo from PlayBilling; original upstream notices retained.
package com.flipduo.module.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.Scaffold

/** One ancestor owns miuix root dialogs; pages retain their own scroll and inset ownership. */
@Composable
internal fun RootPopupHost(content: @Composable () -> Unit) {
    Scaffold(modifier = Modifier.fillMaxSize(), containerColor = uiColors().canvas,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)) { content() }
}
