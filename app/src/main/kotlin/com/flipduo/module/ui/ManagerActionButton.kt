// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Miuix owns shape rendering, interaction and semantics; secondary actions have no persistent grey fill. */
@Composable
internal fun ManagerActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    compact: Boolean = false,
) {
    val scheme = MiuixTheme.colorScheme
    TextButton(text = text, onClick = onClick, modifier = modifier, enabled = enabled,
        cornerRadius = 28.dp, minWidth = 48.dp, minHeight = 48.dp,
        colors = if (primary) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(
            color = Color.Transparent, disabledColor = Color.Transparent,
            textColor = scheme.primary, disabledTextColor = scheme.disabledOnSecondaryVariant),
        insideMargin = PaddingValues(horizontal = if (primary) 20.dp else 12.dp, vertical = 10.dp),
        textStyle = if (compact) MiuixTheme.textStyles.button.copy(fontSize = 14.sp) else null)
}
