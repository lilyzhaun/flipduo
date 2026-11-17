// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flipduo.module.ClockLines
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField

@Composable
internal fun ClockLineEditor(top: String, bottom: String, save: (String, String) -> Unit) {
    var first by rememberSaveable(top, bottom) { mutableStateOf(top) }
    var second by rememberSaveable(top, bottom) { mutableStateOf(bottom) }
    val now = remember { System.currentTimeMillis() }
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("双行格式", color = uiColors().text)
            Text("HH:mm 是 24 小时时间，a h:mm 是 12 小时时间，ss 是秒，M月d日 EEEE 是日期和星期。两行可对调。",
                color = uiColors().secondary, fontSize = 13.sp)
            TextField(value = first, onValueChange = { first = it }, label = "上行格式", singleLine = true, modifier = Modifier.fillMaxWidth())
            TextField(value = second, onValueChange = { second = it }, label = "下行格式", singleLine = true, modifier = Modifier.fillMaxWidth())
            val firstOk = remember(first) { ClockLines.valid(first) }; val secondOk = remember(second) { ClockLines.valid(second) }
            Text(if (firstOk && secondOk) "预览\n${ClockLines.preview(first, now)}\n${ClockLines.preview(second, now)}"
                else "${if (!firstOk) "上行" else "下行"}格式无效：请使用单行日期格式，最多 40 个字符。", color = uiColors().text)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ManagerActionButton(text = "恢复默认", onClick = { first = ClockLines.TOP; second = ClockLines.BOTTOM; save(first, second) })
                ManagerActionButton(text = "保存格式", primary = true, enabled = firstOk && secondOk && (first != top || second != bottom), onClick = { save(first, second) })
            }
        }
    }
}
