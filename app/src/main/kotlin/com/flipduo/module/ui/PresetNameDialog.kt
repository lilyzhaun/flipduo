// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
internal fun PresetNameDialog(show: Boolean, renameOnly: Boolean, value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit, dismiss: () -> Unit, save: () -> Unit) {
    val valid = AppearancePresetSlots.normalizedName(value.text) != null
    OverlayDialog(show = show, title = if (renameOnly) "重命名方案" else "保存外观方案", onDismissRequest = dismiss) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(show) { if (show) focus.requestFocus() }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            TextField(value = value, onValueChange = onValueChange, label = "方案名称", singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("preset-name-input"),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid && show) save() }))
            Text(when {
                value.text.isBlank() -> "请输入方案名称"
                !valid -> "使用单行名称，最多${AppearancePresetSlots.NAME_LIMIT}个字符"
                renameOnly -> "仅修改名称，已保存的外观保持不变"
                else -> "保存当前外观，之后可按名称应用"
            }, color = uiColors().secondary, fontSize = 13.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ManagerActionButton(text = "取消", modifier = Modifier.weight(1f), onClick = dismiss)
                ManagerActionButton(text = "保存", primary = true, enabled = valid && show,
                    modifier = Modifier.weight(1f).testTag("preset-name-save"), onClick = save)
            }
        }
    }
}
