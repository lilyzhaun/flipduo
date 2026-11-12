package com.flipduo.module.ui

import android.content.Context
import android.content.SharedPreferences
import com.flipduo.module.DockConfig
import com.flipduo.module.CameraColors
import com.flipduo.module.CoverBackground
import com.flipduo.module.CoverInputMethodConfig
import com.flipduo.module.FlipDuoApplication
import com.flipduo.module.CutoutAppConfig
import com.flipduo.module.InnerRecentsConfig
import com.flipduo.module.NavigationLongPress
import com.flipduo.module.RecentsStyle
import com.flipduo.module.RuntimeStatus
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.HookedTarget
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal data class Controls(
    val enabled: Boolean = true, val hideStatus: Boolean = true, val haptics: Boolean = true,
    val animations: Boolean = true, val showClock: Boolean = true, val showTrio: Boolean = true,
    val offsetY: Int = 12, val scale: Int = 96, val iconScale: Int = 100, val statusScale: Int = 100,
    val animationMs: Int = 200, val revision: Int = 0,
    val order: String = "BACK,HOME,RECENTS,NOTIFICATIONS,CONTROL_CENTER,MORE",
    val systemCorners: Boolean = true,
    val positions: String = com.flipduo.module.DockLayout().encode(),
    val statusBarHeight: Int = 64, val framedScreenshots: Boolean = true,
    val fullscreenApps: Set<String> = emptySet(),
    val fullscreenScales: Map<String, Int> = emptyMap(),
    val eventIsland: Boolean = true,
    val innerRecentsCompat: Boolean = true,
    val symmetricCorners: Boolean = false,
    val backLongPress: NavigationLongPress = NavigationLongPress.NONE,
    val homeLongPress: NavigationLongPress = NavigationLongPress.NONE,
    val recentsLongPress: NavigationLongPress = NavigationLongPress.MINI_WINDOW,
    val backLongPressApp: String = "",
    val homeLongPressApp: String = "",
    val recentsLongPressApp: String = "",
    val cameraSecondRing: Boolean = false,
    val cameraMainX: Int = 0, val cameraMainY: Int = 0,
    val cameraLowerX: Int = 0, val cameraLowerY: Int = 0,
    val cameraMainRadius: Int = DockConfig.CAMERA_RADIUS_DEFAULT,
    val cameraLowerRadius: Int = DockConfig.CAMERA_RADIUS_DEFAULT,
    val cameraDebugRings: Boolean = false,
    val cameraMainStroke: Int = DockConfig.CAMERA_STROKE_DEFAULT,
    val cameraLowerStroke: Int = DockConfig.CAMERA_STROKE_DEFAULT,
    val cameraMainRing: Boolean = true,
    val cameraLowerRing: Boolean = true,
    val cameraNotifications: Boolean = true,
    val cameraBreathing: Boolean = true,
    val islandLock: Boolean = true,
    val landscapeCompat: Boolean = true,
    val background: CoverBackground = CoverBackground.BLACK,
    val recentsCrop: Boolean = true,
    val cameraTimerColor: Int = CameraColors.TIMER,
    val cameraProgressColor: Int = CameraColors.PROGRESS,
    val cameraChargeColor: Int = CameraColors.CHARGE,
    val cameraTimerBreathColor: Int = CameraColors.TIMER,
    val cameraNotificationColor: Int = CameraColors.NOTIFICATION,
    val trioStroke: Int = 100,
    val trioWifiScale: Int = 100, val trioWifiStroke: Int = 100,
    val trioNetworkScale: Int = 100, val trioNetworkWeight: Int = 100,
    val trioBatteryNumber: Boolean = true,
    val trioBatteryScale: Int = 100, val trioBatteryWeight: Int = 100,
    val hideCoverCamera: Boolean = false,
    val hideFullscreenButtons: Boolean = false,
    val unrestrictCoverApps: Boolean = false,
    val recentsStyle: RecentsStyle = RecentsStyle.SYSTEM,
    val recentsCardLongPress: Boolean = false,
    val cameraChargeTarget: Boolean = false,
    val cameraChargeTargetPercent: Int = com.flipduo.module.ChargeTarget.DEFAULT,
    val visualFocus: Boolean = false,
    val visualFocusHideCount: Boolean = false,
    val cameraChargeArrival: Boolean = false,
    val trueFullscreenApps: Set<String> = emptySet(),
    val notificationFullscreen: Boolean = false,
    val hideCoverBottomGuard: Boolean = false,
    val coverInputMethod: String = "",
    val coverInputMethodUser: Int = -1,
)
internal enum class NavigationKey(val id: String, val title: String) {
    BACK("back", "返回键"), HOME("home", "桌面键"), RECENTS("recents", "最近任务键")
}
internal fun Controls.longPress(key: NavigationKey) = when (key) {
    NavigationKey.BACK -> backLongPress
    NavigationKey.HOME -> homeLongPress
    NavigationKey.RECENTS -> recentsLongPress
}
internal fun Controls.longPressApp(key: NavigationKey) = when (key) {
    NavigationKey.BACK -> backLongPressApp
    NavigationKey.HOME -> homeLongPressApp
    NavigationKey.RECENTS -> recentsLongPressApp
}
internal fun Controls.withLongPress(key: NavigationKey, action: NavigationLongPress) = when (key) {
    NavigationKey.BACK -> copy(backLongPress = action)
    NavigationKey.HOME -> copy(homeLongPress = action)
    NavigationKey.RECENTS -> copy(recentsLongPress = action)
}
internal fun Controls.withLongPressApp(key: NavigationKey, target: String): Controls {
    val pkg = NavigationLongPress.appPackage(target)
    require(pkg.isNotEmpty())
    return when (key) {
        NavigationKey.BACK -> copy(backLongPress = NavigationLongPress.LAUNCH_APP, backLongPressApp = pkg)
        NavigationKey.HOME -> copy(homeLongPress = NavigationLongPress.LAUNCH_APP, homeLongPressApp = pkg)
        NavigationKey.RECENTS -> copy(recentsLongPress = NavigationLongPress.LAUNCH_APP, recentsLongPressApp = pkg)
    }
}
internal data class Connection(val connected: Boolean = false, val framework: String = "等待 LSPosed", val message: String = "设置保存在本机；连接框架后同步")
internal data class SystemPolicyStatus(val checked: Boolean = false, val scalingReady: Boolean = false,
    val loadedVersion: Long? = null, val summary: String = "正在核对系统策略")

/**
 * [writer] runs every settings commit (production: a one-thread IO view, see [shared]). Tests that check
 * persisted values may pass Dispatchers.Unconfined; the UI must never write on the main thread.
 */
internal class ControlsStore(context: Context, private val scope: CoroutineScope, writer: CoroutineDispatcher) {
    companion object {
        @Volatile private var instance: ControlsStore? = null
        /** One store per process: activity recreation (fold/unfold) neither loses a pending save nor reads it back stale. */
        fun shared(context: Context): ControlsStore = instance ?: synchronized(this) {
            instance ?: ControlsStore(context.applicationContext, CoroutineScope(SupervisorJob() + Dispatchers.Main),
                Dispatchers.IO.limitedParallelism(1)).also { instance = it }
        }
    }
    private val local = context.getSharedPreferences("controls_local", Context.MODE_PRIVATE)
    val runtime: SharedPreferences = context.getSharedPreferences("runtime", Context.MODE_PRIVATE)
    val state = MutableStateFlow(read(local))
    val connection = MutableStateFlow(Connection())
    val deliveredRevision = MutableStateFlow(-1)
    val syncFailed = MutableStateFlow(false)
    val screenshotScope = MutableStateFlow("授权系统截图，用于外屏带壳截图")
    val systemScope = MutableStateFlow("授权系统框架，用于应用全屏")
    val systemPolicy = MutableStateFlow(SystemPolicyStatus())
    var runtimeEpoch = MutableStateFlow(0)
    /** Framework running targets, queried on bind, on a new host receipt and on manual refresh only. */
    val targets = MutableStateFlow(RuntimeStatus.Targets.disconnected())
    private val runtimeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        runtimeEpoch.value++
        // Battery/signal status writes share this file; one key per new host receipt justifies a framework query.
        if (key == "fliphome_time" || key == "systemui_time") refreshTargets()
    }
    private var targetQuery: Job? = null
    /** Set by the first real edit; a framework snapshot is adopted only before any edit and without local data. */
    private var edited = false
    private val pipeline = SettingsPipeline(state.value, local.contains("revision"), { it.revision }, { write(local, it) }, writer, ::onSave)
    /** Local commit and framework delivery, tracked separately; the UI value itself is [state]. */
    val saveStatus = MutableStateFlow(pipeline.status)
    private val undoPoints = SettingsUndo()
    /** Pages with an undo point in this app session (B05); empty after a restart. */
    val undoable = MutableStateFlow<Set<SettingsGroup>>(emptySet())
    init {
        runtime.registerOnSharedPreferenceChangeListener(runtimeListener)
        scope.launch { FlipDuoApplication.service.collect { service ->
            if (service == null) {
                pipeline.detach(); connection.value = Connection()
                targets.value = RuntimeStatus.Targets.disconnected()
                systemPolicy.value = SystemPolicyStatus(true, summary = "连接 LSPosed 后确认生效状态")
                return@collect
            }
            try {
                val prefs = withContext(Dispatchers.IO) { service.getRemotePreferences(DockConfig.GROUP) }
                screenshotScope.value = if ("com.miui.screenshot" in service.scope) "截图作用域已授权" else "点击授权系统截图作用域"
                systemScope.value = if ("system" in service.scope || "android" in service.scope) "系统框架已授权；首次加载需重启手机" else "点击授权系统框架作用域"
                if (!edited && !local.contains("revision")) {
                    val adopted = withContext(Dispatchers.IO) { read(prefs) }
                    if (!edited) { state.value = adopted; pipeline.submit(adopted) }
                }
                connection.value = Connection(true, "${service.frameworkName} · API ${service.apiVersion}", "正在同步设置…")
                pipeline.attach { value -> write(prefs, value) }
                refreshSystemPolicy()
                refreshTargets()
            } catch (_: Exception) { pipeline.detach(); connection.value = Connection(false, "框架连接异常", "本机设置已保留，可重新打开应用重试") }
        } }
    }
    /** Main thread. The value shows at once; local commit and framework delivery follow on the serial writer. */
    fun update(change: (Controls) -> Controls) {
        val current = state.value
        val changed = change(current)
        if (changed == current) return // unchanged: no write, no new revision, nothing published
        val next = changed.copy(revision = current.revision + 1)
        undoPoints.committed(current, next); undoable.value = undoPoints.available()
        edited = true
        state.value = next
        pipeline.submit(next)
    }
    /** Restores only [group]'s values from before its last saved change, as a normal new revision. Not itself undoable. */
    fun undo(group: SettingsGroup) {
        val current = state.value
        val restored = undoPoints.take(group, current)
        undoable.value = undoPoints.available()
        if (restored == null || restored == current) return
        val next = restored.copy(revision = current.revision + 1)
        edited = true
        state.value = next
        pipeline.submit(next)
    }
    private fun onSave(s: SettingsPipeline.Status) {
        saveStatus.value = s
        deliveredRevision.value = if (s.connected) s.delivered else -1
        syncFailed.value = s.remoteFailed
        connection.update { c -> if (!c.connected) c else c.copy(message = when {
            s.localFailed -> "本机保存失败，设置尚未持久化；可在“运行状态”页重试"
            s.remoteFailed -> "同步失败；设置仍保存在本机"
            s.fullyDelivered -> "设置已交给框架，等待宿主回执"
            else -> "正在同步设置…"
        }) }
    }
    fun reset() = update { Controls(revision = it.revision) }
    /** Same snapshot through the same path; never creates a revision. */
    fun retrySync() = pipeline.retry()
    /** Re-run existing queries; retry only an unsaved or undelivered snapshot. Never bumps revision or reloads a host. */
    fun refreshRuntime() {
        val s = pipeline.status
        if (s.localFailed || s.remoteFailed || s.localPending || !s.fullyDelivered) retrySync()
        refreshSystemPolicy(); refreshTargets()
    }
    fun refreshTargets() {
        targetQuery?.cancel()
        targetQuery = scope.launch {
            val service = FlipDuoApplication.service.value
            if (service == null) { targets.value = RuntimeStatus.Targets.disconnected(); return@launch }
            val api = service.apiVersion
            val result = try {
                withContext(Dispatchers.IO) {
                    if (api < XposedService.API_102) RuntimeStatus.Targets.unsupported(api)
                    else RuntimeStatus.Targets.available(api, service.runningTargets.map {
                        RuntimeStatus.Target(it.processName, it.pid, RuntimeStatus.TargetState.of(it.state.name), it.loadedVersionCode)
                    })
                }
            } catch (_: XposedService.ServiceException) { RuntimeStatus.Targets.failed(api) }
            catch (_: UnsupportedOperationException) { RuntimeStatus.Targets.unsupported(api) }
            if (FlipDuoApplication.service.value === service) targets.value = result
        }
    }
    fun receipt(host: String): RuntimeStatus.Receipt {
        val r = runtime
        if (!r.contains("${host}_revision")) return RuntimeStatus.Receipt.none()
        return RuntimeStatus.Receipt(true, r.getInt("${host}_revision", -1), r.getBoolean("${host}_enabled", false),
            r.getInt("${host}_module", -1), r.getString("${host}_module_name", ""), r.getInt("${host}_pid", -1),
            r.getLong("${host}_process_start", -1L), r.getLong("${host}_time", -1L), r.getLong("${host}_elapsed", -1L),
            r.getString("${host}_caps", null))
    }
    fun refreshSystemPolicy() {
        scope.launch {
            val service = FlipDuoApplication.service.value
            if (service == null) { systemPolicy.value = SystemPolicyStatus(true, summary = "连接 LSPosed 后确认生效状态"); return@launch }
            systemPolicy.value = try {
                withContext(Dispatchers.IO) {
                    if (service.apiVersion < 102) SystemPolicyStatus(true, summary = "当前框架无法查询已加载的策略版本")
                    else {
                        val target = service.runningTargets.firstOrNull { it.processName in setOf("system_server", "system", "android") }
                        when {
                            target == null -> SystemPolicyStatus(true, summary = "系统策略尚未加载；授权系统框架后重启生效")
                            target.state == HookedTarget.State.STALE -> SystemPolicyStatus(true,
                                loadedVersion = target.loadedVersionCode,
                                summary = "本次更新尚未加载。请重启手机，再应用新的选择与大小设置。")
                            target.state == HookedTarget.State.RELOADING -> SystemPolicyStatus(true,
                                loadedVersion = target.loadedVersionCode, summary = "正在加载更新，完成后点击刷新确认。")
                            target.state == HookedTarget.State.FAILED -> SystemPolicyStatus(true,
                                loadedVersion = target.loadedVersionCode, summary = "更新加载未完成，请重启手机后重试。")
                            target.loadedVersionCode < CutoutAppConfig.FULLSCREEN_POLICY_VERSION -> SystemPolicyStatus(true,
                                loadedVersion = target.loadedVersionCode, summary = "设置已保存。新的全屏模式需系统框架加载新版；重启手机后再重新打开应用。")
                            else -> SystemPolicyStatus(true, true, target.loadedVersionCode,
                                "设置自动保存，重新打开对应应用后生效")
                        }
                    }
                }
            } catch (_: XposedService.ServiceException) {
                SystemPolicyStatus(true, summary = "暂时无法读取系统策略，点击刷新重试")
            } catch (_: UnsupportedOperationException) {
                SystemPolicyStatus(true, summary = "当前框架无法查询已加载的策略版本")
            }
        }
    }
    fun requestScreenshotScope() {
        val service = FlipDuoApplication.service.value
        if (service == null) { screenshotScope.value = "请先连接 LSPosed"; return }
        if ("com.miui.screenshot" in service.scope) { screenshotScope.value = "截图作用域已授权"; return }
        service.requestScope(listOf("com.miui.screenshot"), object : XposedService.OnScopeEventListener {
            override fun onScopeRequestApproved(packages: List<String>) {
                screenshotScope.value = "截图作用域已授权；重新启动截图进程后生效"
            }
            override fun onScopeRequestFailed(message: String) { screenshotScope.value = "授权未完成：$message" }
        })
    }
    fun close() { runtime.unregisterOnSharedPreferenceChangeListener(runtimeListener) }
    fun requestSystemScope() {
        val service = FlipDuoApplication.service.value
        if (service == null) { systemScope.value = "请先连接 LSPosed"; return }
        if ("system" in service.scope || "android" in service.scope) { systemScope.value = "系统框架已授权"; return }
        service.requestScope(listOf("system"), object : XposedService.OnScopeEventListener {
            override fun onScopeRequestApproved(packages: List<String>) {
                systemScope.value = "系统框架已授权；首次加载需重启手机"
            }
            override fun onScopeRequestFailed(message: String) { systemScope.value = "授权未完成：$message" }
        })
    }
    private fun write(p: SharedPreferences, c: Controls) {
        val okay = p.edit().putBoolean("enabled", c.enabled).putBoolean("hide_status", c.hideStatus)
            .putBoolean("haptics", c.haptics).putBoolean("animations", c.animations)
            .putBoolean("show_clock", c.showClock).putBoolean("show_trio", c.showTrio)
            .putBoolean("hide_cover_camera", c.hideCoverCamera)
            .putBoolean("notification_fullscreen", c.notificationFullscreen)
            .putBoolean(com.flipduo.module.BottomGuardRules.KEY, c.hideCoverBottomGuard)
            .putString(CoverInputMethodConfig.KEY, c.coverInputMethod)
            .putInt(CoverInputMethodConfig.USER_KEY, c.coverInputMethodUser)
            .putBoolean("hide_fullscreen_buttons", c.hideFullscreenButtons)
            .putBoolean("unrestrict_cover_apps", c.unrestrictCoverApps)
            .putString("recents_style", c.recentsStyle.name)
            .putBoolean("recents_card_long_press", c.recentsCardLongPress)
            .putBoolean("camera_charge_target", c.cameraChargeTarget)
            .putInt("camera_charge_target_percent", com.flipduo.module.ChargeTarget.clamp(c.cameraChargeTargetPercent))
            .putBoolean("visual_focus", c.visualFocus)
            .putBoolean("visual_focus_hide_count", c.visualFocusHideCount)
            .putBoolean("camera_charge_arrival", c.cameraChargeArrival)
            .putInt("offset_y", c.offsetY).putInt("scale", c.scale).putInt("icon_scale", c.iconScale)
            .putInt("status_scale", c.statusScale).putInt("animation_ms", c.animationMs)
            .putInt("trio_stroke", c.trioStroke)
            .putInt("trio_wifi_scale", c.trioWifiScale).putInt("trio_wifi_stroke", c.trioWifiStroke)
            .putInt("trio_network_scale", c.trioNetworkScale).putInt("trio_network_weight", c.trioNetworkWeight)
            .putBoolean("trio_battery_number", c.trioBatteryNumber)
            .putInt("trio_battery_scale", c.trioBatteryScale).putInt("trio_battery_weight", c.trioBatteryWeight)
            .putBoolean("system_corners", c.systemCorners).putString("positions", c.positions)
            .putBoolean("symmetric_corners", c.symmetricCorners)
            .putInt("status_bar_height", c.statusBarHeight).putBoolean("framed_screenshots", c.framedScreenshots)
            .putStringSet(CutoutAppConfig.KEY, c.fullscreenApps)
            .putStringSet(CutoutAppConfig.TRUE_FULLSCREEN_KEY, c.trueFullscreenApps)
            .putBoolean(CutoutAppConfig.LEGACY_KEY, CutoutAppConfig.LEGACY_TARGET in c.fullscreenApps)
            .putString(CutoutAppConfig.SCALES_KEY, c.fullscreenScales.toSortedMap().entries.joinToString(";") { "${it.key}=${CutoutAppConfig.clampScale(it.value)}" })
            .putBoolean("event_island", c.eventIsland)
            .putBoolean("island_lock", c.islandLock)
            .putBoolean("landscape_compat", c.landscapeCompat)
            .putString(CoverBackground.KEY, c.background.key)
            .putBoolean("black_background", c.background == CoverBackground.BLACK)
            .putBoolean("recents_crop", c.recentsCrop)
            .putBoolean("camera_second_ring", c.cameraSecondRing)
            .putBoolean("camera_main_ring", c.cameraMainRing).putBoolean("camera_lower_ring", c.cameraLowerRing)
            .putBoolean("camera_notifications", c.cameraNotifications).putBoolean("camera_breathing", c.cameraBreathing)
            .putBoolean("camera_debug_rings", c.cameraDebugRings)
            .putInt("camera_main_stroke", c.cameraMainStroke).putInt("camera_lower_stroke", c.cameraLowerStroke)
            .putInt("camera_main_x", c.cameraMainX).putInt("camera_main_y", c.cameraMainY)
            .putInt("camera_lower_x", c.cameraLowerX).putInt("camera_lower_y", c.cameraLowerY)
            .putInt("camera_main_radius", c.cameraMainRadius).putInt("camera_lower_radius", c.cameraLowerRadius)
            .putInt("camera_timer_color", c.cameraTimerColor).putInt("camera_progress_color", c.cameraProgressColor)
            .putInt("camera_charge_color", c.cameraChargeColor).putInt("camera_timer_breath_color", c.cameraTimerBreathColor)
            .putInt("camera_notification_color", c.cameraNotificationColor)
            .putString("long_press_back", c.backLongPress.name)
            .putString("long_press_home", c.homeLongPress.name)
            .putString("long_press_recents", c.recentsLongPress.name)
            .putString("long_press_back_app", NavigationLongPress.appPackage(c.backLongPressApp))
            .putString("long_press_home_app", NavigationLongPress.appPackage(c.homeLongPressApp))
            .putString("long_press_recents_app", NavigationLongPress.appPackage(c.recentsLongPressApp))
            .putBoolean(InnerRecentsConfig.KEY, c.innerRecentsCompat)
            .putString("order", c.order).putInt("revision", c.revision).commit()
        check(okay)
    }
    private fun read(p: SharedPreferences) = Controls(
        p.getBoolean("enabled", true), p.getBoolean("hide_status", true), p.getBoolean("haptics", true),
        p.getBoolean("animations", true), p.getBoolean("show_clock", true), p.getBoolean("show_trio", true),
        p.getInt("offset_y", 12).coerceIn(0, 14), p.getInt("scale", 96).coerceIn(80, 100),
        p.getInt("icon_scale", 100).coerceIn(70, 130), p.getInt("status_scale", 100).coerceIn(80, 120),
        p.getInt("animation_ms", 200).coerceIn(120, 280),
        p.getInt("revision", 0), p.getString("order", null) ?: "BACK,HOME,RECENTS,NOTIFICATIONS,CONTROL_CENTER,MORE",
        if (p.contains("system_corners")) p.getBoolean("system_corners", true) else p.getInt("corner_radius", 32) != 0,
        com.flipduo.module.DockLayout.decode(p.getString("positions", "")).encode(),
        p.getInt("status_bar_height", 64).coerceIn(48, 114), p.getBoolean("framed_screenshots", true),
        CutoutAppConfig.read(p),
        CutoutAppConfig.readScales(p),
        p.getBoolean("event_island", true),
        InnerRecentsConfig.read(p),
        p.getBoolean("symmetric_corners", false),
        NavigationLongPress.parse(p.getString("long_press_back", null), NavigationLongPress.NONE),
        NavigationLongPress.parse(p.getString("long_press_home", null), NavigationLongPress.NONE),
        NavigationLongPress.parse(p.getString("long_press_recents", null), NavigationLongPress.MINI_WINDOW),
        NavigationLongPress.appPackage(p.getString("long_press_back_app", "")),
        NavigationLongPress.appPackage(p.getString("long_press_home_app", "")),
        NavigationLongPress.appPackage(p.getString("long_press_recents_app", "")),
        p.getBoolean("camera_second_ring", false),
        p.getInt("camera_main_x", 0).coerceIn(DockConfig.CAMERA_X_MIN, DockConfig.CAMERA_X_MAX),
        p.getInt("camera_main_y", 0).coerceIn(DockConfig.CAMERA_Y_MIN, DockConfig.CAMERA_Y_MAX),
        p.getInt("camera_lower_x", 0).coerceIn(DockConfig.CAMERA_X_MIN, DockConfig.CAMERA_X_MAX),
        p.getInt("camera_lower_y", 0).coerceIn(DockConfig.CAMERA_Y_MIN, DockConfig.CAMERA_Y_MAX),
        p.getInt("camera_main_radius", DockConfig.CAMERA_RADIUS_DEFAULT).coerceIn(DockConfig.CAMERA_RADIUS_MIN, DockConfig.CAMERA_RADIUS_MAX),
        p.getInt("camera_lower_radius", DockConfig.CAMERA_RADIUS_DEFAULT).coerceIn(DockConfig.CAMERA_RADIUS_MIN, DockConfig.CAMERA_RADIUS_MAX),
        p.getBoolean("camera_debug_rings", false),
        p.getInt("camera_main_stroke", DockConfig.CAMERA_STROKE_DEFAULT).coerceIn(DockConfig.CAMERA_STROKE_MIN, DockConfig.CAMERA_STROKE_MAX),
        p.getInt("camera_lower_stroke", DockConfig.CAMERA_STROKE_DEFAULT).coerceIn(DockConfig.CAMERA_STROKE_MIN, DockConfig.CAMERA_STROKE_MAX),
        p.getBoolean("camera_main_ring", true),
        p.getBoolean("camera_lower_ring", true),
        p.getBoolean("camera_notifications", true),
        p.getBoolean("camera_breathing", true),
        p.getBoolean("island_lock", true),
        p.getBoolean("landscape_compat", true),
        CoverBackground.read(p),
        p.getBoolean("recents_crop", CoverBackground.read(p) == CoverBackground.BLACK),
        p.getInt("camera_timer_color", CameraColors.TIMER),
        p.getInt("camera_progress_color", CameraColors.PROGRESS),
        p.getInt("camera_charge_color", CameraColors.CHARGE),
        p.getInt("camera_timer_breath_color", CameraColors.TIMER),
        p.getInt("camera_notification_color", CameraColors.NOTIFICATION),
        p.getInt("trio_stroke", 100).coerceIn(60, 140),
        p.getInt("trio_wifi_scale", 100).coerceIn(70, 130),
        p.getInt("trio_wifi_stroke", 100).coerceIn(60, 140),
        p.getInt("trio_network_scale", 100).coerceIn(70, 140),
        p.getInt("trio_network_weight", 100).coerceIn(70, 130),
        p.getBoolean("trio_battery_number", true),
        p.getInt("trio_battery_scale", 100).coerceIn(70, 130),
        p.getInt("trio_battery_weight", 100).coerceIn(70, 130),
        p.getBoolean("hide_cover_camera", false),
        p.getBoolean("hide_fullscreen_buttons", false),
        p.getBoolean("unrestrict_cover_apps", false),
        RecentsStyle.read(p.getString("recents_style", null)),
        p.getBoolean("recents_card_long_press", false),
        p.getBoolean("camera_charge_target", false),
        com.flipduo.module.ChargeTarget.clamp(p.getInt("camera_charge_target_percent", com.flipduo.module.ChargeTarget.DEFAULT)),
        p.getBoolean("visual_focus", false),
        p.getBoolean("visual_focus_hide_count", false),
        p.getBoolean("camera_charge_arrival", false),
        CutoutAppConfig.readTrueFullscreen(p),
        p.getBoolean("notification_fullscreen", false),
        p.getBoolean(com.flipduo.module.BottomGuardRules.KEY, false),
        p.getString(CoverInputMethodConfig.KEY, "") ?: "",
        p.getInt(CoverInputMethodConfig.USER_KEY, -1),
    )
}

internal fun Controls.nativeConfig() = DockConfig().also {
    it.enabled = enabled; it.hideStatus = hideStatus; it.haptics = haptics; it.animations = animations
    it.showClock = showClock; it.showTrio = showTrio; it.offsetY = offsetY; it.scale = scale
    it.hideCoverCamera = hideCoverCamera
    it.notificationFullscreen = notificationFullscreen
    it.hideCoverBottomGuard = hideCoverBottomGuard
    it.coverInputMethod = coverInputMethod; it.coverInputMethodUser = coverInputMethodUser
    it.hideFullscreenButtons = hideFullscreenButtons; it.unrestrictCoverApps = unrestrictCoverApps
    it.recentsStyle = recentsStyle; it.recentsCardLongPress = recentsCardLongPress
    it.cameraChargeTarget = cameraChargeTarget; it.cameraChargeTargetPercent = cameraChargeTargetPercent
    it.visualFocus = visualFocus; it.visualFocusHideCount = visualFocusHideCount; it.cameraChargeArrival = cameraChargeArrival
    it.iconScale = iconScale; it.statusScale = statusScale; it.animationMs = animationMs
    it.trioStroke = trioStroke
    it.trioWifiScale = trioWifiScale; it.trioWifiStroke = trioWifiStroke
    it.trioNetworkScale = trioNetworkScale; it.trioNetworkWeight = trioNetworkWeight
    it.trioBatteryNumber = trioBatteryNumber
    it.trioBatteryScale = trioBatteryScale; it.trioBatteryWeight = trioBatteryWeight
    it.systemCorners = systemCorners; it.positions = positions; it.revision = revision; it.order = order
    it.statusBarHeight = statusBarHeight; it.framedScreenshots = framedScreenshots
    it.eventIsland = eventIsland
    it.islandLock = islandLock
    it.landscapeCompat = landscapeCompat
    it.background = background
    it.recentsCrop = recentsCrop
    it.symmetricCorners = symmetricCorners
    it.backLongPress = backLongPress; it.homeLongPress = homeLongPress; it.recentsLongPress = recentsLongPress
    it.backLongPressApp = backLongPressApp; it.homeLongPressApp = homeLongPressApp; it.recentsLongPressApp = recentsLongPressApp
    it.cameraSecondRing = cameraSecondRing
    it.cameraMainRing = cameraMainRing; it.cameraLowerRing = cameraLowerRing
    it.cameraNotifications = cameraNotifications; it.cameraBreathing = cameraBreathing
    it.cameraMainX = cameraMainX; it.cameraMainY = cameraMainY; it.cameraMainRadius = cameraMainRadius
    it.cameraLowerX = cameraLowerX; it.cameraLowerY = cameraLowerY; it.cameraLowerRadius = cameraLowerRadius
    it.cameraDebugRings = cameraDebugRings; it.cameraMainStroke = cameraMainStroke; it.cameraLowerStroke = cameraLowerStroke
    it.cameraTimerColor = cameraTimerColor; it.cameraProgressColor = cameraProgressColor
    it.cameraChargeColor = cameraChargeColor; it.cameraTimerBreathColor = cameraTimerBreathColor
    it.cameraNotificationColor = cameraNotificationColor
}
