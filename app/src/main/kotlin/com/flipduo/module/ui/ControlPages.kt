// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.Canvas
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.flipduo.module.BuildConfig
import com.flipduo.module.DockPreview
import com.flipduo.module.CoverGeometry
import com.flipduo.module.DockLayout
import com.flipduo.module.DockConfig
import com.flipduo.module.CoverBackground
import com.flipduo.module.NavigationLongPress
import androidx.compose.ui.viewinterop.AndroidView
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Back
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.*

@Composable
internal fun HomePage(store: ControlsStore, connection: Connection, c: Controls, manager: () -> Unit, details: () -> Unit) {
    val epoch by store.runtimeEpoch.collectAsState()
    val targets by store.targets.collectAsState()
    val save by store.saveStatus.collectAsState()
    // Config receipt, module build and process liveness stay separate; see RuntimeStatus.
    val summary = remember(epoch, targets, c.revision, c.enabled, connection, save.localFailed) {
        val (home, system) = runtimeHosts(store, c, targets)
        com.flipduo.module.RuntimeStatus.summarize(c.enabled, connection.connected, connection.message, save.localFailed, home, system)
    }
    val status = summary.title
    val dark = isSystemInDarkTheme()
    val success = summary.success
    val ink = if (success) { if (dark) Color(0xff42be65) else Color(0xff198038) }
        else if (dark) Color(0xfff1c21b) else Color(0xff8e6a00)
    val surface = if (success) { if (dark) Color(0xff0e3820) else Color(0xffdefbe6) }
        else if (dark) Color(0xff3b2f05) else Color(0xfffcf4d6)
    PageFrame("FlipDuo") {
        item { Card(Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors(surface, uiColors().text), insideMargin = PaddingValues(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(MiuixIcons.Info, null, tint = ink, modifier = Modifier.size(20.dp))
                Text("外屏控制区", color = ink, fontSize = 14.sp)
            }
            Spacer(Modifier.height(8.dp)); Text(status, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(summary.detail, color = ink, fontSize = 14.sp, lineHeight = 20.sp)
        } }
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            SwitchPreference(title = "启用控制区", summary = connection.framework, checked = c.enabled, onCheckedChange = { store.update { old -> old.copy(enabled = it) } })
        } }
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            ArrowPreference(title = "运行状态", summary = "宿主版本、回执与功能", onClick = details)
            ArrowPreference(title = "LSPosed 管理", summary = "作用域与模块状态", onClick = manager)
        } }
        item { Preview(store, c) }
    }
}

@Composable
private fun Preview(store: ControlsStore, c: Controls, editable: Boolean = false, cameraPreview: Boolean = false,
    viewportHeight: Dp? = null, compact: Boolean = false, showGuidance: Boolean = true,
    toggleSize: (() -> Unit)? = null) {
    val colors = uiColors()
    val epoch by store.runtimeEpoch.collectAsState()
    var rotated by rememberSaveable { mutableStateOf(false) }
    val appHaptics = LocalAppearance.current.haptics
    val onPositions by rememberUpdatedState<(String) -> Unit>({ value -> store.update { it.copy(positions = value) } })
    val ratio = DockPreview.MODEL_WIDTH.toFloat() / DockPreview.MODEL_HEIGHT
    val preview: @Composable (Dp) -> Unit = { height ->
        val size = Modifier.height(height).aspectRatio(ratio, matchHeightConstraintsFirst = true)
        AndroidView(factory = { DockPreview(it) }, modifier = size.testTag("cover-preview"),
                update = { view -> epoch; view.setEditorHaptics(appHaptics); view.setCameraPreview(cameraPreview)
                    view.update(c.nativeConfig(), store.runtime, rotated, editable) { onPositions(it) } })
    }
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(if (compact) 12.dp else 16.dp)) {
      BoxWithConstraints(Modifier.fillMaxWidth()) {
        val height = minOf(viewportHeight ?: 200.dp, maxWidth / ratio)
        val compactHeight = minOf(height, maxWidth * .38f / ratio)
        if (compact) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            preview(compactHeight)
            Column(Modifier.weight(1f)) {
                Text("外屏预览", color = colors.text, fontWeight = FontWeight.SemiBold)
                Text(if (rotated) "镜头左上" else "镜头右下", color = colors.secondary, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PreviewAction("旋转") { rotated = !rotated }
                    if (toggleSize != null) PreviewAction("放大", toggleSize)
                }
            }
        } else Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (editable) "拖动布局" else "外屏预览", color = colors.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                PreviewAction("旋转") { rotated = !rotated }
                if (toggleSize != null) PreviewAction("收起", toggleSize)
            }
            Text(if (rotated) "镜头左上" else "镜头右下", color = colors.secondary, fontSize = 13.sp)
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) { preview(height) }
        }
      }
        if (cameraPreview && showGuidance) {
            Spacer(Modifier.height(12.dp))
            Text("预览镜头跟随圆环位置；请以外屏实物对齐为准。", color = colors.secondary, fontSize = 13.sp)
        }
        if (editable && showGuidance) {
            Spacer(Modifier.height(12.dp))
            Text("双按钮组整体拖动，等高控件可交换；松手保存。", color = colors.secondary, fontSize = 13.sp)
            ManagerActionButton(text = "重置控件位置", onClick = { store.update { it.copy(positions = DockLayout().encode()) } }, modifier = Modifier.padding(top = 8.dp))
        }
        if (showGuidance && c.background == CoverBackground.BLUR_FILL) {
            Spacer(Modifier.height(8.dp))
            Text("预览不模拟模糊填充，请在外屏应用中观察。", color = colors.secondary, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun PreviewAction(label: String, onClick: () -> Unit) {
    ManagerActionButton(text = label, onClick = onClick, compact = true)
}

internal enum class LayoutPanel(val title: String, val summary: String) {
    MENU("布局设置", ""),
    CONTROLS("控件布局", "边缘内缩、控件与图标大小"),
    STATUS("时钟显示", "控制区时钟"),
    TRIO("三合一", "电量、信号与网络样式"),
    CAMERA("镜头圆环", "圆环、镜间通知与呼吸灯"),
    CORNERS("应用侧圆角", "圆角开关与上下对称"),
    PRESETS("外观方案", "保存与应用颜色和样式组合"),
}

@Composable
internal fun LayoutPage(store: ControlsStore, c: Controls) {
    val context = LocalContext.current
    val geometry = remember(context) { CoverGeometry.resolve(context) }
    var panel by rememberSaveable { mutableStateOf(LayoutPanel.MENU) }
    var colorTarget by rememberSaveable { mutableStateOf<CameraColorTarget?>(null) }
    var showColorPicker by rememberSaveable { mutableStateOf(false) }
    var fineOpen by rememberSaveable { mutableStateOf("") } // B05: collapsed fine groups, per page
    var previewExpanded by rememberSaveable { mutableStateOf(false) }
    val presets = remember(context) { AppearancePresetSlots(context.getSharedPreferences("appearance_presets", android.content.Context.MODE_PRIVATE)) }
    var presetEpoch by remember { mutableIntStateOf(0) }
    var presetSlot by rememberSaveable { mutableIntStateOf(0) }
    var renamePreset by rememberSaveable { mutableStateOf(false) }
    var presetName by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    fun editPreset(slot: Int, rename: Boolean) {
        val name = presets.read(slot).name
        presetName = TextFieldValue(name, TextRange(0, name.length)); renamePreset = rename; presetSlot = slot
    }
    fun savePreset() {
        if (presetSlot == 0) return
        val saved = if (renamePreset) presets.rename(presetSlot, presetName.text)
            else presets.save(presetSlot, presetName.text, store.state.value)
        if (saved) { presetEpoch++; presetSlot = 0 }
    }
    val scrollStates = LayoutPanel.entries.associateWith { key(it) { rememberLazyListState() } }
    val active = LocalRouteActive.current
    val onCover = calibrationOnCoverDisplay()
    val motion = managerMotionEnabled()
    BackHandler(active && panel != LayoutPanel.MENU && !showColorPicker && presetSlot == 0) { panel = LayoutPanel.MENU }
    PageFrame("外屏布局", scrollState = scrollStates.getValue(panel), pinnedContent = { available ->
        Column(Modifier.fillMaxWidth().testTag("layout-pinned-region")) {
            // On cover, the real layout is already visible beside this app. Give its preview's space
            // to the settings list. Inner-screen preview stays pinned across all secondary panels.
            // Unknown/unattached display waits for a physical-mode result instead of flashing a preview.
            if (onCover == false) {
                val height = if (previewExpanded) (available * .34f).coerceIn(128.dp, 240.dp)
                    else (available * .18f).coerceIn(72.dp, 128.dp)
                Preview(store, c, editable = previewExpanded && !c.eventIsland, cameraPreview = true,
                    viewportHeight = height, compact = !previewExpanded, showGuidance = false,
                    toggleSize = { previewExpanded = !previewExpanded })
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (panel != LayoutPanel.MENU) IconButton(onClick = { panel = LayoutPanel.MENU }, modifier = Modifier.size(48.dp)) {
                    Icon(MiuixIcons.Back, "返回布局分类")
                } else Spacer(Modifier.width(8.dp))
                Text(panel.title, color = uiColors().text, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).semantics { heading() })
            }
            if (panel == LayoutPanel.CAMERA && onCover == false) Text("圆环对齐请在外屏实物上确认，内屏预览仅供参考。",
                color = if (isSystemInDarkTheme()) Color(0xffffb4ab) else Color(0xffb3261e),
                fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp))
        }
    }, pinnedBody = { padding, feedback ->
        val transition = updateTransition(panel, label = "layout-panel")
        transition.AnimatedContent(modifier = Modifier.fillMaxSize()
            .managerTransitionInput(transition.currentState != transition.targetState), transitionSpec = {
            val duration = if (motion) ManagerMotion.PAGE_MS else 0
            val curve = ManagerMotion.easing
            val direction = if (targetState == LayoutPanel.MENU) -1 else 1
            (slideInHorizontally(tween(duration, easing = curve)) { direction * it / 12 } + fadeIn(tween(duration))) togetherWith
                (slideOutHorizontally(tween(duration, easing = curve)) { -direction * it / 12 } + fadeOut(tween(duration))) using
                SizeTransform(clip = true, sizeAnimationSpec = { _, _ -> tween(0) })
        }) { visiblePanel ->
            LazyColumn(state = scrollStates.getValue(visiblePanel), overscrollEffect = null,
                modifier = Modifier.fillMaxSize().then(feedback), contentPadding = padding,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                layoutPanelItems(visiblePanel, store, c, geometry,
                    chooseColor = { target -> colorTarget = target; showColorPicker = true }, select = { panel = it },
                    presets = { PresetSlots(store, presets, presetEpoch, ::editPreset) { slot -> presets.clear(slot); presetEpoch++ } },
                    fineOpen = { SettingsLayers.isOpen(fineOpen, it.name) }, toggleFine = { fineOpen = SettingsLayers.toggle(fineOpen, it.name) })
            }
        }
    }) { }
    colorTarget?.let { target -> key(target) {
        CameraColorDialog(store, c, target, showColorPicker, dismiss = { showColorPicker = false }, dismissed = { colorTarget = null })
    } }
    // Keep the editor outside the lazy item: opening the IME can move that item offscreen on cover.
    PresetNameDialog(show = presetSlot != 0, renameOnly = renamePreset, value = presetName,
        onValueChange = { presetName = it }, dismiss = { presetSlot = 0 }, save = ::savePreset)
}

/** B05 local appearance presets: three slots in app-only storage; applying goes through the normal save path. */
@Composable
private fun PresetSlots(store: ControlsStore, presets: AppearancePresetSlots, epoch: Int,
    edit: (Int, Boolean) -> Unit, clear: (Int) -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (slot in 1..3) {
            val entry = remember(epoch, slot) { presets.read(slot) }
            val saved = entry.encoded
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, color = uiColors().text, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (saved == null) "尚未保存外观" else "保存于 " + android.text.format.DateUtils.formatDateTime(context, entry.savedAt,
                            android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME),
                            color = uiColors().secondary, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    ManagerActionButton(text = if (saved == null) "保存当前" else "重命名", compact = true,
                        modifier = Modifier.testTag("preset-edit-$slot"), onClick = { edit(slot, saved != null) })
                }
                if (saved != null) FlowRow(Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ManagerActionButton(text = "应用", primary = true,
                        modifier = Modifier.testTag("preset-apply-$slot"), onClick = { store.update { AppearancePreset.apply(it, saved) } })
                    ManagerActionButton(text = "保存当前", onClick = { edit(slot, false) })
                    ManagerActionButton(text = "清除", onClick = { clear(slot) })
                }
            }
        }
    }
}

/** B05 layering: a collapsed group of fine adjustments; the summary says when it holds non-default values. */
@Composable
private fun FineGroupRow(title: String, custom: Boolean, open: Boolean, toggle: () -> Unit) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        ArrowPreference(title = title, summary = SettingsLayers.summary(custom, open), onClick = toggle)
    }
}

/** B05: one undo level per page for this app session; distinct from each page's "恢复默认". */
@Composable
internal fun UndoGroupCard(store: ControlsStore, group: SettingsGroup) {
    val available by store.undoable.collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ManagerActionButton(text = "撤销本页上次修改", enabled = group in available, onClick = { store.undo(group) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
        if (group in available) Text("仅撤销本页，不影响其他设置", color = uiColors().secondary, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

/** Each layout/settings sub-page undoes the SettingsGroup of the same name; the menu pages have none (checked by SettingsUndoTest). */
internal val LayoutPanel.undoGroup: SettingsGroup? get() = SettingsGroup.entries.firstOrNull { it.name == name }
internal val SettingsPanel.undoGroup: SettingsGroup? get() = SettingsGroup.entries.firstOrNull { it.name == name }

private fun LazyListScope.layoutPanelItems(panel: LayoutPanel, store: ControlsStore, c: Controls, geometry: CoverGeometry,
    chooseColor: (CameraColorTarget) -> Unit, select: (LayoutPanel) -> Unit,
    presets: @Composable () -> Unit,
    fineOpen: (LayoutPanel) -> Boolean = { false }, toggleFine: (LayoutPanel) -> Unit = {}) {
        when (panel) {
        LayoutPanel.MENU -> {
          item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            listOf(LayoutPanel.CONTROLS, LayoutPanel.STATUS, LayoutPanel.TRIO).forEach { destination ->
                ArrowPreference(title = destination.title, summary = destination.summary, onClick = { select(destination) })
            }
          } }
          item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            listOf(LayoutPanel.CAMERA, LayoutPanel.CORNERS, LayoutPanel.PRESETS).forEach { destination ->
                ArrowPreference(title = destination.title, summary = destination.summary, onClick = { select(destination) })
            }
          } }
        }
        LayoutPanel.CONTROLS -> {
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                SettingSlider("边缘内缩", c.offsetY, 0f..14f, "px", { store.update { old -> old.copy(offsetY = it) } })
                SettingSlider("控件外观大小", c.scale, 80f..100f, "%", { store.update { old -> old.copy(scale = it) } })
                SettingSlider("按钮图标大小", c.iconScale, 70f..130f, "%", { store.update { old -> old.copy(iconScale = it) } })
            }
            Spacer(Modifier.height(16.dp))
            Text("镜头右下时向下内缩，左上时向上内缩。缩小外观不会缩小触摸目标。",
                color = uiColors().secondary, fontSize = 12.sp, lineHeight = 18.sp)
        } }
        if (!c.eventIsland) item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
            Text("放大顶部预览后可拖动控件；双按钮组整体移动，等高控件可交换，松手保存。",
                color = uiColors().secondary, fontSize = 13.sp)
            ManagerActionButton(text = "重置控件位置", modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp),
                onClick = { store.update { it.copy(positions = DockLayout().encode()) } })
        } }
        }
        LayoutPanel.CAMERA -> {
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            SwitchPreference(title = "上镜头圆环", summary = "显示计时、秒表、活动进度或时钟秒针；关闭不清除校准参数。",
                checked = c.cameraMainRing, onCheckedChange = { store.update { old -> old.copy(cameraMainRing = it) } })
            SwitchPreference(title = "下镜头圆环", summary = "充电时显示电量圆环；关闭不影响镜间通知图标。",
                checked = c.cameraLowerRing, onCheckedChange = { store.update { old -> old.copy(cameraLowerRing = it) } })
            SwitchPreference(title = "镜间通知", summary = "显示通知图标、多应用三点与新通知提示；关闭不会清除系统通知，也不影响圆环。",
                checked = c.cameraNotifications, onCheckedChange = { store.update { old -> old.copy(cameraNotifications = it) } })
            SwitchPreference(title = "镜间呼吸灯", summary = "新通知呼吸三次，计时中持续呼吸；颜色可分别自定义，关闭后仍可保留图标与三点。",
                checked = c.cameraBreathing, onCheckedChange = { store.update { old -> old.copy(cameraBreathing = it) } })
            SwitchPreference(title = "视觉专注", checked = c.visualFocus,
                summary = "普通新通知不在镜间呼吸；通知图标、通知入口与计时照常，来电、全屏提醒等紧急通知仍会提示，关闭后不补播。不是系统免打扰，不拦截或清除通知。",
                onCheckedChange = { store.update { old -> old.copy(visualFocus = it) } })
            if (c.visualFocus) SwitchPreference(title = "专注时隐藏通知数量", checked = c.visualFocusHideCount,
                summary = "只隐藏事件岛通知入口上的数字，入口仍可点按；关闭视觉专注后自动恢复显示。",
                onCheckedChange = { store.update { old -> old.copy(visualFocusHideCount = it) } })
            SwitchPreference(title = "圆环时钟秒针", summary = "需开启上镜头圆环。每 60 秒匀速一圈；计时器、秒表与明确进度优先。",
                checked = c.cameraSecondRing, onCheckedChange = { store.update { old -> old.copy(cameraSecondRing = it) } })
            SwitchPreference(title = "圆环调试", summary = "仅显示已开启的完整圆环，便于校准；不启动计时器、不模拟充电。",
                checked = c.cameraDebugRings, onCheckedChange = { store.update { old -> old.copy(cameraDebugRings = it) } })
        } }
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            SwitchPreference(title = "充电目标刻度", checked = c.cameraChargeTarget,
                summary = "充电时在下镜头圆环上标出目标电量，只作提示，不限制充电、不预测充满时间。需开启下镜头圆环。",
                onCheckedChange = { store.update { old -> old.copy(cameraChargeTarget = it) } })
        } }
        if (c.cameraChargeTarget) item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
            SettingSlider("目标电量", c.cameraChargeTargetPercent,
                com.flipduo.module.ChargeTarget.MIN.toFloat()..com.flipduo.module.ChargeTarget.MAX.toFloat(), "%",
                { store.update { old -> old.copy(cameraChargeTargetPercent = it) } },
                summary = "刻度横跨圆环，与电量弧形状不同；达到目标前后都能辨认。")
        } }
        if (c.cameraChargeTarget) item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            SwitchPreference(title = "到达目标时提示", checked = c.cameraChargeArrival,
                summary = "充到目标时刻度轻呼吸一次。同一次插电最多一次；插电时已达标、调低目标或宿主重启不补报；锁屏、内屏或外屏熄灭时不提示也不补播；不发声、不振动。",
                onCheckedChange = { store.update { old -> old.copy(cameraChargeArrival = it) } })
        } }
        item { SmallTitle("圆环与呼吸灯颜色") }
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            CameraColorTarget.entries.forEach { target ->
                ArrowPreference(title = target.title, summary = com.flipduo.module.CameraColors.hex(target.color(c)),
                    startAction = { ColorSwatch(target.color(c)) }, onClick = { chooseColor(target) })
            }
        } }
        item { FineGroupRow("位置与大小校准", SettingsLayers.cameraCalibrationCustom(c), fineOpen(LayoutPanel.CAMERA)) { toggleFine(LayoutPanel.CAMERA) } }
        if (fineOpen(LayoutPanel.CAMERA)) {
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
            Text("上镜头 · 计时与进度", color = uiColors().text, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            CameraRingSliders(c.cameraMainX, c.cameraMainY, c.cameraMainRadius, c.cameraMainStroke,
                { value -> store.update { it.copy(cameraMainX = value) } },
                { value -> store.update { it.copy(cameraMainY = value) } },
                { value -> store.update { it.copy(cameraMainRadius = value) } },
                { value -> store.update { it.copy(cameraMainStroke = value) } })
        } }
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
             Text("下镜头 · 充电", color = uiColors().text, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            CameraRingSliders(c.cameraLowerX, c.cameraLowerY, c.cameraLowerRadius, c.cameraLowerStroke,
                { value -> store.update { it.copy(cameraLowerX = value) } },
                { value -> store.update { it.copy(cameraLowerY = value) } },
                { value -> store.update { it.copy(cameraLowerRadius = value) } },
                { value -> store.update { it.copy(cameraLowerStroke = value) } })
        } }
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
            Text("调整前先开启对应圆环。按镜头在右下时调整：横向正值向右，纵向正值向下；倒置自动镜像。粗细向内增厚，外缘不动。关闭不清除校准参数。二代保留系统音乐实测圆心；其他外屏以系统避让区为可微调基准，不代表光学标定。",
                color = uiColors().secondary, fontSize = 12.sp, lineHeight = 18.sp)
            ManagerActionButton(text = "恢复圆环默认位置与大小", modifier = Modifier.padding(top = 12.dp), onClick = {
                store.update { it.copy(cameraMainX = 0, cameraMainY = 0, cameraLowerX = 0, cameraLowerY = 0,
                    cameraMainRadius = DockConfig.CAMERA_RADIUS_DEFAULT, cameraLowerRadius = DockConfig.CAMERA_RADIUS_DEFAULT,
                    cameraMainStroke = DockConfig.CAMERA_STROKE_DEFAULT, cameraLowerStroke = DockConfig.CAMERA_STROKE_DEFAULT) }
            })
        } }
        }
        }
        LayoutPanel.STATUS -> {
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            SwitchPreference(title = "显示时钟", checked = c.showClock, onCheckedChange = { store.update { old -> old.copy(showClock = it) } })
        } }
        }
        LayoutPanel.TRIO -> {
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            SwitchPreference(title = "显示三合一", checked = c.showTrio, onCheckedChange = { store.update { old -> old.copy(showTrio = it) } })
        } }
        item { SmallTitle("整体与轮廓") }
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                SettingSlider("三合一大小", c.statusScale, 80f..120f, "%", { store.update { old -> old.copy(statusScale = it) } })
                SettingSlider("圆环与信号点粗细", c.trioStroke, 60f..140f, "%", { store.update { old -> old.copy(trioStroke = it) } })
            }
        } }
        item { SmallTitle("电量读数") }
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            SwitchPreference(title = "显示电量数字", summary = "关闭后保留电量圆环和充电标记。", checked = c.trioBatteryNumber,
                onCheckedChange = { store.update { old -> old.copy(trioBatteryNumber = it) } })
        } }
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
            Text("100% 为原有样式。松手保存，整体大小保留原来的设置。", color = uiColors().secondary, fontSize = 12.sp, lineHeight = 18.sp)
            ManagerActionButton(text = "恢复三合一默认样式", modifier = Modifier.padding(top = 12.dp).heightIn(min = 48.dp), onClick = {
                store.update { it.copy(statusScale = 100, trioStroke = 100, trioWifiScale = 100, trioWifiStroke = 100,
                    trioNetworkScale = 100, trioNetworkWeight = 100, trioBatteryNumber = true,
                    trioBatteryScale = 100, trioBatteryWeight = 100) }
            })
        } }
        item { FineGroupRow("精细调整", SettingsLayers.trioFineCustom(c), fineOpen(LayoutPanel.TRIO)) { toggleFine(LayoutPanel.TRIO) } }
        if (fineOpen(LayoutPanel.TRIO)) {
        item { SmallTitle("网络显示") }
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                SettingSlider("Wi-Fi 图标大小", c.trioWifiScale, 70f..130f, "%", { store.update { old -> old.copy(trioWifiScale = it) } })
                SettingSlider("Wi-Fi 线条粗细", c.trioWifiStroke, 60f..140f, "%", { store.update { old -> old.copy(trioWifiStroke = it) } })
                SettingSlider("网络制式字号", c.trioNetworkScale, 70f..140f, "%", { store.update { old -> old.copy(trioNetworkScale = it) } },
                    summary = "未连接 Wi-Fi 时显示，如 4G、5G。")
                SettingSlider("网络制式字重", c.trioNetworkWeight, 70f..130f, "%", { store.update { old -> old.copy(trioNetworkWeight = it) } })
            }
        } }
        if (c.trioBatteryNumber) item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                SettingSlider("电量数字字号", c.trioBatteryScale, 70f..130f, "%", { store.update { old -> old.copy(trioBatteryScale = it) } })
                SettingSlider("电量数字字重", c.trioBatteryWeight, 70f..130f, "%", { store.update { old -> old.copy(trioBatteryWeight = it) } })
            }
        } }
        }
        }
        LayoutPanel.CORNERS -> item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            SwitchPreference(title = "应用侧圆角", summary = "纯黑或模糊填充时修饰应用交界；最近任务圆角还需开启“裁切最近任务”。关闭裁切会保留此选择。", checked = c.systemCorners,
                onCheckedChange = { store.update { old -> old.copy(systemCorners = it) } })
            SwitchPreference(title = "上下对称圆角", summary = "开启后上下均为 ${geometry.appTop(false, true, true)}px；关闭时上 ${geometry.top(false)}px、下 ${geometry.bottom(false)}px，倒置时交换。仅在应用侧圆角开启时生效。",
                checked = c.symmetricCorners,
                onCheckedChange = { store.update { old -> old.copy(symmetricCorners = it) } })
        } }
        LayoutPanel.PRESETS -> {
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
            Text("方案只保存在本机，包含圆环与呼吸灯颜色、圆环粗细、三合一样式、时钟显示、控件大小与应用侧圆角；不包含镜头位置校准、外屏背景、应用全屏名单、长按动作和总开关。应用方案后，可在对应页面撤销。",
                color = uiColors().secondary, fontSize = 12.sp, lineHeight = 18.sp)
        } }
        item { presets() }
        }
        }
        panel.undoGroup?.let { group -> item { UndoGroupCard(store, group) } }
}

@Composable
private fun CameraRingSliders(x: Int, y: Int, radius: Int, stroke: Int, setX: (Int) -> Unit, setY: (Int) -> Unit, setRadius: (Int) -> Unit, setStroke: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SettingSlider("横向偏移", x, DockConfig.CAMERA_X_MIN.toFloat()..DockConfig.CAMERA_X_MAX.toFloat(), "px", setX, fine = true)
        SettingSlider("纵向偏移", y, DockConfig.CAMERA_Y_MIN.toFloat()..DockConfig.CAMERA_Y_MAX.toFloat(), "px", setY, fine = true)
        SettingSlider("圆环半径", radius, DockConfig.CAMERA_RADIUS_MIN.toFloat()..DockConfig.CAMERA_RADIUS_MAX.toFloat(), "px", setRadius,
            summary = if (radius > 170) "请结合镜头位置微调；超出屏幕边缘的部分会被裁切。" else null, fine = true)
        SettingSlider("圆环粗细", stroke, DockConfig.CAMERA_STROKE_MIN.toFloat()..DockConfig.CAMERA_STROKE_MAX.toFloat(), "px", setStroke, fine = true)
    }
}

@Composable
private fun SettingSlider(title: String, value: Int, range: ClosedFloatingPointRange<Float>, unit: String,
    apply: (Int) -> Unit, summary: String? = null, fine: Boolean = false) {
    var draft by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val colors = uiColors()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, modifier = Modifier.weight(1f), fontSize = 16.sp, color = colors.text)
            Text("${draft.roundToInt()}$unit", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = colors.accent, style = TextStyle(fontFeatureSettings = "tnum"))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (fine) 8.dp else 0.dp)) {
            if (fine) FineStepButton(title, increase = false, enabled = draft > range.start) {
                draft = (draft.roundToInt() - 1).toFloat().coerceIn(range); apply(draft.roundToInt())
            }
            Slider(value = draft, onValueChange = { draft = it }, valueRange = range,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = title },
                onValueChangeFinished = { apply(draft.roundToInt()) })
            if (fine) FineStepButton(title, increase = true, enabled = draft < range.endInclusive) {
                draft = (draft.roundToInt() + 1).toFloat().coerceIn(range); apply(draft.roundToInt())
            }
        }
        if (summary != null) {
            Spacer(Modifier.height(8.dp))
            Text(summary, color = colors.secondary, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun FineStepButton(title: String, increase: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val ink = uiColors().text.copy(alpha = if (enabled) 1f else .38f)
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp).semantics {
        contentDescription = "$title，${if (increase) "增加" else "减少"} 1 像素"
    }) {
        Canvas(Modifier.size(18.dp)) {
            drawLine(ink, Offset(0f, center.y), Offset(size.width, center.y), strokeWidth = 2.dp.toPx())
            if (increase) drawLine(ink, Offset(center.x, 0f), Offset(center.x, size.height), strokeWidth = 2.dp.toPx())
        }
    }
}

@Composable
internal fun SettingsPage(store: ControlsStore, c: Controls, appearance: () -> Unit, about: () -> Unit, fullscreenApps: () -> Unit,
    chooseLongPressApp: (NavigationKey) -> Unit, manager: () -> Unit, selectPanel: (SettingsPanel) -> Unit,
    panel: SettingsPanel = SettingsPanel.MENU, back: () -> Unit = {}, scrollState: LazyListState? = null) {
    if (panel == SettingsPanel.INPUT_METHOD) {
        CoverInputMethodPage(store, c, back, scrollState)
        return
    }
    val screenshotScope by store.screenshotScope.collectAsState()
    val systemScope by store.systemScope.collectAsState()
    val connection by store.connection.collectAsState()
    val deliveredRevision by store.deliveredRevision.collectAsState()
    val syncFailed by store.syncFailed.collectAsState()
    val policy by store.systemPolicy.collectAsState()
    // Each category is a real saveable NavEntry, using the same push/pop animation as About.
    PageFrame(panel.title, onBack = if (panel == SettingsPanel.MENU) null else back, scrollState = scrollState) {
        when (panel) {
        SettingsPanel.INPUT_METHOD -> Unit // Dedicated picker above shares this category's navigation entry.
        SettingsPanel.MENU -> {
        item { SmallTitle("外屏功能") }
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            ArrowPreference(title = "应用全屏", summary = "已启用 ${c.fullscreenApps.size} 个应用 · 模式与内容大小", onClick = fullscreenApps)
            SettingsPanel.entries.filter { it != SettingsPanel.MENU }.forEach { destination ->
                ArrowPreference(title = destination.title, summary = destination.summary, onClick = { selectPanel(destination) })
            }
        } }
        item { SmallTitle("管理应用") }
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            ArrowPreference(title = "应用外观", summary = "主题、底栏与应用内交互", onClick = appearance)
            ArrowPreference(title = "LSPosed 管理", summary = "查看模块启用与作用域", onClick = manager)
            ArrowPreference(title = "关于 FlipDuo", summary = BuildConfig.VERSION_NAME, onClick = about)
        } }
        }
        SettingsPanel.BEHAVIOR -> {
        item { SmallTitle("背景") }
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            val backgrounds = CoverBackground.entries
            OverlayDropdownPreference(title = "外屏背景", items = backgrounds.map { it.title }, selectedIndex = backgrounds.indexOf(c.background),
                onSelectedIndexChange = { index -> store.update { old -> old.copy(background = backgrounds[index]) } })
            Text(when (c.background) {
                CoverBackground.BLACK -> "使用纯黑侧区，不改变导航、圆环和通知。最近任务由下方开关独立控制。"
                CoverBackground.SYSTEM -> "保留系统原本的背景，不改变导航、圆环和通知。"
                CoverBackground.BLUR_FILL -> "试验：侧区与应用圆角外侧使用同一实时模糊背景，小组件展开时仍保留。无可用来源或系统报告模糊不可用时暂停填充，恢复后重新就绪。首次需加载新版系统界面与外屏桌面。"
             }, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = uiColors().secondary, fontSize = 12.sp, lineHeight = 18.sp)
        } }
        item { SmallTitle("最近任务") }
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            SwitchPreference(title = "裁切最近任务", checked = c.recentsCrop,
                summary = "开启后仅应用区参与任务动画，圆角仍由“布局 → 应用侧圆角”控制。关闭使用系统完整画面，不额外设置任务圆角。",
                onCheckedChange = { store.update { old -> old.copy(recentsCrop = it) } })
            val recentsStyles = com.flipduo.module.RecentsStyle.entries
            OverlayDropdownPreference(title = "最近任务样式", items = recentsStyles.map { it.title },
                selectedIndex = recentsStyles.indexOf(c.recentsStyle),
                onSelectedIndexChange = { index -> store.update { it.copy(recentsStyle = recentsStyles[index]) } })
            SwitchPreference(title = "最近任务卡片长按功能", checked = c.recentsCardLongPress,
                summary = "纵向显示任务锁定和应用信息菜单，横向打开应用信息。与导航键长按动作无关。",
                onCheckedChange = { value -> store.update { it.copy(recentsCardLongPress = value) } })
            Text("更改后退出并重新进入最近任务生效；跟随系统会恢复原生样式，不修改系统默认设置。", modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                color = uiColors().secondary, fontSize = 12.sp, lineHeight = 18.sp)
        } }
        item { SmallTitle("控制区") }
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            SwitchPreference(title = "纵向事件岛", summary = "外侧导航三键，内侧显示活动与通知；点击展开原生操作", checked = c.eventIsland,
                onCheckedChange = { store.update { old -> old.copy(eventIsland = it) } })
            SwitchPreference(title = "空闲锁屏按钮", summary = "事件岛下方显示一键锁屏；有活动或面板接管时自动隐藏。不影响经典布局的锁屏工具。",
                checked = c.islandLock, onCheckedChange = { store.update { old -> old.copy(islandLock = it) } })
            SwitchPreference(title = "外屏横屏适配", summary = "横屏时重排导航与事件岛，圆环仍贴合镜头；关闭后横屏使用系统原布局。不强制旋转应用或改变视频比例。",
                 checked = c.landscapeCompat, onCheckedChange = { store.update { old -> old.copy(landscapeCompat = it) } })
        } }
        item { SmallTitle("系统入口") }
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            SwitchPreference(title = "通知全宽", checked = c.notificationFullscreen,
                summary = "扩展外屏通知区域，控制中心保持原布局。镜头位置的内容可能被硬件遮住；关闭恢复原生布局。首次需系统界面加载新版。",
                onCheckedChange = { value -> store.update { it.copy(notificationFullscreen = value) } })
            SwitchPreference(title = "隐藏外屏底部遮罩", checked = c.hideCoverBottomGuard,
                summary = "隐藏底部防误触层，保留原生输入区域；有原生豁免接口的系统还会移除应用底部兼容留白。切换后需重新启动受影响应用；一代OS3暂仅支持前者。",
                onCheckedChange = { value -> store.update { it.copy(hideCoverBottomGuard = value) } })
            if (c.hideCoverBottomGuard && (policy.loadedVersion ?: 0L) < com.flipduo.module.BottomGuardRules.POLICY_VERSION)
                Text("这项需要系统框架加载新版；仅重载外屏桌面或系统界面不会生效。",
                    color = uiColors().secondary, fontSize = 13.sp, lineHeight = 19.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            SwitchPreference(title = "隐藏外屏系统状态栏", summary = "仅隐藏已解锁外屏的显示，保留顶部占位与下拉手势", checked = c.hideStatus, onCheckedChange = { store.update { old -> old.copy(hideStatus = it) } })
            SwitchPreference(title = "隐藏外屏相机按钮", summary = "隐藏外屏锁屏页的相机快捷按钮；不影响内屏入口或相机应用。",
                checked = c.hideCoverCamera, onCheckedChange = { store.update { old -> old.copy(hideCoverCamera = it) } })
        } }
        if (c.hideStatus) item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
            SettingSlider("状态栏占位高度", c.statusBarHeight, 48f..114f, "px",
                { store.update { old -> old.copy(statusBarHeight = it) } }, summary = "降低顶部留白，保留下拉手势。")
        } }
        }
        SettingsPanel.NAVIGATION -> {
        item { NavigationLongPressPreferences(c, chooseLongPressApp) { change -> store.update(change) } }
        }
        SettingsPanel.FEEDBACK -> {
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            SwitchPreference(title = "控件触感", summary = "遵守系统触感设置", checked = c.haptics, onCheckedChange = { store.update { old -> old.copy(haptics = it) } })
            SwitchPreference(title = "展开与收起动画", summary = "从当前进度反向接管，遵守系统动画开关", checked = c.animations, onCheckedChange = { store.update { old -> old.copy(animations = it) } })
        } }
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
            SettingSlider("工具过渡时长", c.animationMs, 120f..280f, "ms", { store.update { old -> old.copy(animationMs = it) } })
        } }
        }
        SettingsPanel.SCREENSHOTS -> {
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            SwitchPreference(title = "带壳截图", summary = "加入机身、镜头与闪光灯，PNG 保留机身四角外的透明区域", checked = c.framedScreenshots,
                onCheckedChange = { store.update { old -> old.copy(framedScreenshots = it) } })
            ArrowPreference(title = "截图作用域", summary = screenshotScope, onClick = store::requestScreenshotScope)
        } }
        }
        SettingsPanel.COMPATIBILITY -> {
        item { SmallTitle("外屏应用") }
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            SwitchPreference(title = "解除外屏应用限制", checked = c.unrestrictCoverApps,
                summary = "解除系统对外屏启动资格的限制；应用全屏、缩放和折叠接续仍由各自设置控制。首次需系统框架加载新版。",
                onCheckedChange = { value -> store.update { it.copy(unrestrictCoverApps = value) } })
            Text(when {
                !connection.connected || syncFailed -> "设置仅保存在本机，连接 LSPosed 后同步。"
                deliveredRevision != c.revision -> "正在同步设置…"
                (policy.loadedVersion ?: 0L) < com.flipduo.module.CoverAppCompatRules.POLICY_VERSION -> "本项首次加载需要用户手动重启手机；本应用不会自动重启。"
                else -> "已加载版本支持此功能；更改后重新打开对应应用。"
            }, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = uiColors().secondary, fontSize = 12.sp, lineHeight = 18.sp)
        } }
        item { SmallTitle("内屏兼容") }
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            val status = when {
                !connection.connected || syncFailed -> "设置仅保存在本机，连接 LSPosed 后同步"
                deliveredRevision != c.revision -> "正在同步设置"
                !policy.checked -> "正在核对系统策略"
                (policy.loadedVersion ?: 0L) < com.flipduo.module.InnerRecentsConfig.TOGGLE_POLICY_VERSION ->
                    "本次升级需重启一次，加载后即可随时切换"
                !policy.scalingReady -> policy.summary
                else -> "更改后重新进入最近任务生效，无需重启"
            }
            SwitchPreference(title = "内屏最近任务兼容", summary = "等比显示外屏旧预览，可能留白；关闭使用系统原生预览。\n$status",
                checked = c.innerRecentsCompat,
                onCheckedChange = { store.update { old -> old.copy(innerRecentsCompat = it) } })
        } }
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            ArrowPreference(title = "系统框架作用域", summary = systemScope, onClick = store::requestSystemScope)
        } }
        item { SmallTitle("输入法") }
        item { Text("搜狗浅色输入法白弧修复需勾选系统搜狗作用域；保留原生键盘布局，其他皮肤保持原样。",
            modifier = Modifier.padding(horizontal = 8.dp), color = uiColors().secondary, fontSize = 13.sp, lineHeight = 19.sp) }
        }
        }
        panel.undoGroup?.let { group -> item { UndoGroupCard(store, group) } }
    }
}

@Composable
internal fun NavigationLongPressPreferences(c: Controls, chooseApp: (NavigationKey) -> Unit, update: ((Controls) -> Controls) -> Unit) {
    val context = LocalContext.current
    val actions = remember { NavigationLongPress.values().toList() }
    val labels = remember { actions.map { it.title } }
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        for (key in NavigationKey.entries) {
            OverlayDropdownPreference(title = key.title, items = labels, selectedIndex = actions.indexOf(c.longPress(key)), maxHeight = 400.dp,
                onSelectedIndexChange = { index ->
                    val action = actions[index]
                    if (action == NavigationLongPress.LAUNCH_APP) chooseApp(key)
                    else update { it.withLongPress(key, action) }
                })
            if (c.longPress(key) == NavigationLongPress.LAUNCH_APP) {
                val pkg = c.longPressApp(key)
                val label = remember(pkg, context) {
                    if (pkg.isEmpty()) "请选择应用" else try {
                        context.packageManager.getApplicationInfo(pkg, 0).loadLabel(context.packageManager).toString()
                    } catch (_: android.content.pm.PackageManager.NameNotFoundException) { "应用已卸载，请重新选择" }
                }
                ArrowPreference(title = "${key.title}打开的应用", summary = label, onClick = { chooseApp(key) })
            }
        }
        Text("仅更改长按，短按保持不变。小窗、上一个应用仅适用于已解锁外屏的普通应用；语音助手未设默认时由系统选择。",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            color = uiColors().secondary, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
internal fun AppearancePage(store: AppearanceStore, back: () -> Unit) {
    val state by store.state.collectAsState()
    PageFrame("应用外观", onBack = back) {
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            SwitchPreference(title = "悬浮底栏", checked = state.floating, onCheckedChange = { store.set("floating_nav", it) })
            SwitchPreference(title = "模糊效果", checked = state.blur, onCheckedChange = { store.set("blur_enabled", it) })
            SwitchPreference(title = "液态玻璃", summary = "作用于应用内底栏，不读取其他应用", checked = state.liquid, onCheckedChange = { store.set("liquid_glass_enabled", it) })
        } }
        item { SmallTitle("应用交互") }
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            SwitchPreference(title = "预测性返回", summary = "返回时跟手预览上一页", checked = state.predictiveBack, onCheckedChange = { store.set("predictive_back_enabled", it) })
            SwitchPreference(title = "触感反馈", summary = "应用内操作反馈，遵守系统设置", checked = state.haptics, onCheckedChange = { store.set("haptics_enabled", it) })
        } }
    }
}
