// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import com.flipduo.module.CameraColors
import kotlinx.coroutines.launch
import kotlin.math.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog

internal enum class CameraColorTarget(val id: String, val title: String, val defaultColor: Int) {
    TIMER("timer", "计时与秒针圆环", CameraColors.TIMER),
    PROGRESS("progress", "活动进度圆环", CameraColors.PROGRESS),
    CHARGE("charge", "充电圆环", CameraColors.CHARGE),
    TIMER_BREATH("timer_breath", "计时呼吸灯", CameraColors.TIMER),
    NOTIFICATION("notification", "通知呼吸灯", CameraColors.NOTIFICATION);

    fun color(c: Controls) = when (this) {
        TIMER -> c.cameraTimerColor
        PROGRESS -> c.cameraProgressColor
        CHARGE -> c.cameraChargeColor
        TIMER_BREATH -> c.cameraTimerBreathColor
        NOTIFICATION -> c.cameraNotificationColor
    }
    fun withColor(c: Controls, value: Int) = when (this) {
        TIMER -> c.copy(cameraTimerColor = value)
        PROGRESS -> c.copy(cameraProgressColor = value)
        CHARGE -> c.copy(cameraChargeColor = value)
        TIMER_BREATH -> c.copy(cameraTimerBreathColor = value)
        NOTIFICATION -> c.copy(cameraNotificationColor = value)
    }
}

/** HyperCeiler-style grid/slider draft, HSL channels and shared alpha. Only confirmation publishes. */
@Composable
internal fun CameraColorDialog(store: ControlsStore, c: Controls, target: CameraColorTarget, show: Boolean,
    dismiss: () -> Unit, dismissed: () -> Unit) {
    val original = remember(target) { target.color(c) }
    val initialHsl = remember(original) { FloatArray(3).also { ColorUtils.colorToHSL(original, it) } }
    var selected by rememberSaveable(target) { mutableIntStateOf(original) }
    var hue by rememberSaveable(target) { mutableFloatStateOf(initialHsl[0]) }
    var saturation by rememberSaveable(target) { mutableFloatStateOf(initialHsl[1]) }
    var lightness by rememberSaveable(target) { mutableFloatStateOf(initialHsl[2]) }
    var alpha by rememberSaveable(target) { mutableIntStateOf(AndroidColor.alpha(original)) }
    var colorInput by rememberSaveable(target) { mutableStateOf(CameraColors.rgb(original)) }
    val keyboard = LocalSoftwareKeyboardController.current
    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    fun useColor(color: Int) {
        val hsl = FloatArray(3).also { ColorUtils.colorToHSL(color, it) }
        hue = hsl[0]; saturation = hsl[1]; lightness = hsl[2]; alpha = AndroidColor.alpha(color)
        selected = color; colorInput = CameraColors.rgb(color)
    }
    fun pick(h: Float = hue, s: Float = saturation, l: Float = lightness) {
        // Do not decode the quantized RGB back into HSL during a drag: hue survives black/white endpoints.
        hue = h; saturation = s; lightness = l
        selected = ColorUtils.setAlphaComponent(ColorUtils.HSLToColor(floatArrayOf(h, s, l)), alpha)
        colorInput = CameraColors.rgb(selected)
    }
    val parsed = CameraColors.parseInput(colorInput, alpha)
    val panelHeight = 232.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    OverlayDialog(show = show, title = target.title, onDismissRequest = dismiss, onDismissFinished = dismissed) {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ColorSwatch(original); Text("原色", color = uiColors().secondary, fontSize = 12.sp)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ColorSwatch(selected); Text("新颜色", color = uiColors().secondary, fontSize = 12.sp)
                    }
                    ManagerActionButton(text = "恢复默认", compact = true, modifier = Modifier.weight(1f).heightIn(min = 48.dp), onClick = { useColor(target.defaultColor) })
                }
                Spacer(Modifier.height(12.dp))
                TextField(value = colorInput, onValueChange = { text ->
                    CameraColors.parseInput(text, alpha)?.let(::useColor)
                    colorInput = text // Keep incomplete edits and the cursor's text instead of rewriting during input.
                }, label = "输入 RGB / HEX 颜色", singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth())
                Text(if (parsed == null) "RGB 每项需为 0–255，例如 255,188,85；或输入 #FFBC55。" else "RGB：255,188,85；HEX：#FFBC55。8 位 HEX 可指定透明度。",
                    color = uiColors().secondary, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(16.dp))
                TabRowWithContour(tabs = listOf("网格", "滑块"), selectedTabIndex = pager.currentPage,
                    modifier = Modifier.clip(CircleShape), height = 58.dp, cornerRadius = 24.dp, minWidth = 48.dp, itemSpacing = 0.dp,
                    colors = TabRowDefaults.tabRowColors(backgroundColor = uiColors().secondary.copy(alpha = .08f),
                        selectedBackgroundColor = uiColors().surface, contentColor = uiColors().secondary,
                        selectedContentColor = uiColors().text),
                    onTabSelected = { page -> scope.launch {
                        if (c.animations && android.animation.ValueAnimator.areAnimatorsEnabled()) pager.animateScrollToPage(page)
                        else pager.scrollToPage(page)
                    } })
                Spacer(Modifier.height(12.dp))
                HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().height(panelHeight), verticalAlignment = Alignment.Top) { page ->
                    if (page == 0) ColorGrid(selected, Modifier.fillMaxWidth().height(panelHeight)) { color ->
                        useColor(ColorUtils.setAlphaComponent(color, alpha))
                    } else Column {
                        val hslColors = remember(hue, saturation, lightness) {
                            listOf((0..6).map { Color(ColorUtils.HSLToColor(floatArrayOf(it * 60f, 1f, .5f))) },
                                listOf(Color(ColorUtils.HSLToColor(floatArrayOf(hue, 0f, lightness))), Color(ColorUtils.HSLToColor(floatArrayOf(hue, 1f, lightness)))),
                                listOf(Color.Black, Color(ColorUtils.HSLToColor(floatArrayOf(hue, saturation, .5f))), Color.White))
                        }
                        GradientColorSlider("色相", hue / 360f, "${hue.roundToInt()}°", hslColors[0]) { pick(h = it * 360f) }
                        GradientColorSlider("饱和度", saturation, "${(saturation * 100).roundToInt()}%", hslColors[1]) { pick(s = it) }
                        GradientColorSlider("明度", lightness, "${(lightness * 100).roundToInt()}%", hslColors[2]) { pick(l = it) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                val opaque = Color(selected or 0xff000000.toInt())
                GradientColorSlider("不透明度", alpha / 255f, "${(alpha * 100f / 255).roundToInt()}%",
                    listOf(opaque.copy(alpha = 0f), opaque), checkerboard = true) { value ->
                    alpha = (value * 255).roundToInt(); selected = ColorUtils.setAlphaComponent(selected, alpha)
                    colorInput = CameraColors.rgb(selected)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ManagerActionButton(text = "取消", modifier = Modifier.weight(1f).heightIn(min = 48.dp), onClick = dismiss)
                ManagerActionButton(text = "确定", primary = true, enabled = parsed != null && show, modifier = Modifier.weight(1f).heightIn(min = 48.dp), onClick = {
                    parsed?.let { color -> store.update { target.withColor(it, color) }; dismiss() }
                })
            }
        }
    }
}

@Composable
private fun GradientColorSlider(title: String, value: Float, label: String, colors: List<Color>, checkerboard: Boolean = false, change: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), color = uiColors().text, fontSize = 14.sp)
        Text(label, color = uiColors().accent, fontSize = 14.sp)
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(28.dp).clip(CircleShape).then(if (checkerboard) Modifier.drawCheckerboard() else Modifier)) {
            drawRect(Brush.horizontalGradient(if (rtl) colors.reversed() else colors, startX = 14.dp.toPx(), endX = size.width - 14.dp.toPx()))
        }
        // Keep Miuix's native press/drag spring, keyboard, TalkBack progress and 48dp touch surface.
        Slider(value = value, onValueChange = change, colors = SliderDefaults.sliderColors(
            foregroundColor = Color.Transparent, backgroundColor = Color.Transparent, thumbColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = title })
    }
}

@Composable
internal fun ColorSwatch(argb: Int, modifier: Modifier = Modifier.size(32.dp)) {
    val border = uiColors().secondary.copy(alpha = .35f)
    Canvas(modifier.clip(CircleShape).drawCheckerboard().border(1.dp, border, CircleShape)) { drawRect(Color(argb)) }
}

@Composable
private fun ColorGrid(selected: Int, modifier: Modifier, choose: (Int) -> Unit) {
    val columns = 12; val rows = 8
    val palette = remember { (0 until rows).map { row -> (0 until columns).map { column ->
        ColorUtils.HSLToColor(if (row == 0) floatArrayOf(0f, 0f, 1f - column / (columns - 1f))
            else floatArrayOf(column * 360f / columns, .85f, 1f - row / rows.toFloat()))
    } } }
    val onPick by rememberUpdatedState(choose)
    val picked = remember(selected) {
        palette.flatten().indices.minBy { index ->
            val color = palette[index / columns][index % columns]
            (AndroidColor.red(color) - AndroidColor.red(selected)).toDouble().pow(2) +
                (AndroidColor.green(color) - AndroidColor.green(selected)).toDouble().pow(2) +
                (AndroidColor.blue(color) - AndroidColor.blue(selected)).toDouble().pow(2)
        }
    }
    Canvas(modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
        .semantics { contentDescription = "颜色网格，当前${CameraColors.hex(selected)}；精细或无障碍调色请切换滑块" }
        .pointerInput(Unit) {
            detectTapGestures { point -> onPick(palette[(point.y / size.height * rows).toInt().coerceIn(0, rows - 1)][(point.x / size.width * columns).toInt().coerceIn(0, columns - 1)]) }
        }.pointerInput(Unit) {
            fun select(point: Offset) { onPick(palette[(point.y / size.height * rows).toInt().coerceIn(0, rows - 1)][(point.x / size.width * columns).toInt().coerceIn(0, columns - 1)]) }
            detectDragGestures(onDragStart = ::select, onDrag = { change, _ -> change.consume(); select(change.position) })
        }) {
        val cell = Size(size.width / columns, size.height / rows)
        for (row in 0 until rows) for (column in 0 until columns) drawRect(Color(palette[row][column]), Offset(column * cell.width, row * cell.height), cell)
        val cellCenter = Offset((picked % columns + .5f) * cell.width, (picked / columns + .5f) * cell.height)
        val radius = min(cell.width, cell.height) * .32f
        drawCircle(Color.Black.copy(alpha = .5f), radius + 1.dp.toPx(), cellCenter, style = Stroke(2.dp.toPx()))
        drawCircle(Color.White, radius, cellCenter, style = Stroke(2.dp.toPx()))
    }
}
