// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.*
import androidx.navigation3.scene.*
import androidx.navigation3.ui.*
import androidx.navigationevent.compose.*
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.*

private data object RootOverlay
private data object AboutRoute
private data object AppearanceRoute
private data object LicenseRoute
private data object FullscreenAppsRoute
private data object RuntimeStatusRoute
private data class LongPressAppsRoute(val key: NavigationKey)
private data class SettingsRoute(val panel: SettingsPanel)

@Composable
fun FlipDuoUi() {
    val context = LocalContext.current
    // Process-wide store: settings saves outlive this composition and activity recreation.
    val controls = remember { ControlsStore.shared(context) }
    val state by controls.state.collectAsState()
    val save by controls.saveStatus.collectAsState()
    // Rare local commit failure: say so once per failed snapshot; the edit stays visible and retryable.
    LaunchedEffect(save.localFailed, save.latest) {
        if (save.localFailed) Toast.makeText(context, "设置未能保存到本机，可在“运行状态”页重试", Toast.LENGTH_LONG).show()
    }
    val connection by controls.connection.collectAsState()
    val appearanceStore = remember { AppearanceStore(context) }
    val appearance by appearanceStore.state.collectAsState()
    val dark = isSystemInDarkTheme()
    val theme = remember(dark) { ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) }
    val shader = remember { runCatching { isRuntimeShaderSupported() }.getOrDefault(false) }
    val platformHaptic = LocalHapticFeedback.current
    val hapticEnabled by rememberUpdatedState(appearance.haptics)
    val haptic = remember(platformHaptic) { object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            if (hapticEnabled) platformHaptic.performHapticFeedback(hapticFeedbackType)
        }
    } }
    val settingsScroll = SettingsPanel.entries.associateWith { key(it) { rememberLazyListState() } }
    var pages by rememberSaveable { mutableStateOf(emptyList<String>()) }
    fun navigate(route: String) {
        pages = managerPagesAfterPush(pages, route)
    }
    fun back() { pages = pages.dropLast(1) }
    fun manager() {
        val intent = context.packageManager.getLaunchIntentForPackage("org.lsposed.manager")
        if (intent != null) context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        else Toast.makeText(context, "请从系统中打开 LSPosed 管理器", Toast.LENGTH_SHORT).show()
    }
    MiuixTheme(controller = theme) {
        CompositionLocalProvider(LocalAppearance provides appearance, LocalShaderSupported provides shader, LocalHapticFeedback provides haptic) {
            RootPopupHost {
                Box(Modifier.fillMaxSize()) {
                    RootShell(covered = pages.isNotEmpty()) { root -> when (root) {
                        RootPage.HOME -> HomePage(controls, connection, state, ::manager) { navigate("runtime_status") }
                        RootPage.LAYOUT -> LayoutPage(controls, state)
                         RootPage.SETTINGS -> SettingsPage(controls, state, { navigate("appearance") }, { navigate("about") }, { navigate("fullscreen_apps") },
                            { key -> navigate("long_app_${key.id}") }, ::manager, { panel -> navigate(panel.route) },
                            scrollState = settingsScroll.getValue(SettingsPanel.MENU))
                    } }
                    val keys = listOf<Any>(RootOverlay) + pages.map { route -> when (route) {
                        "about" -> AboutRoute
                        "license" -> LicenseRoute
                        "fullscreen_apps" -> FullscreenAppsRoute
                        "runtime_status" -> RuntimeStatusRoute
                        else -> when {
                            route.startsWith("long_app_") -> LongPressAppsRoute(NavigationKey.entries.first { "long_app_${it.id}" == route })
                             route.startsWith("settings_") -> SettingsRoute(SettingsPanel.entries.first { it.route == route })
                            else -> AppearanceRoute
                        }
                    } }
                    val entries = entryProvider<Any> {
                        entry<RootOverlay> { Box(Modifier.fillMaxSize()) }
                        entry<AboutRoute> { AboutPage({ navigate("license") }) { if (pages.lastOrNull() == "about") back() } }
                        entry<LicenseRoute> { LicensePage { if (pages.lastOrNull() == "license") back() } }
                        entry<AppearanceRoute> { AppearancePage(appearanceStore) { back() } }
                        entry<FullscreenAppsRoute> { FullscreenAppsPage(controls, state) { back() } }
                        entry<RuntimeStatusRoute> { RuntimeStatusPage(controls, state) { if (pages.lastOrNull() == "runtime_status") back() } }
                         entry<LongPressAppsRoute> { route -> LongPressAppsPage(controls, state, route.key) { back() } }
                        entry<SettingsRoute> { route ->
                            SettingsPage(controls, state, { navigate("appearance") }, { navigate("about") }, { navigate("fullscreen_apps") },
                                { key -> navigate("long_app_${key.id}") }, ::manager, { panel -> navigate(panel.route) },
                                panel = route.panel, back = ::back, scrollState = settingsScroll.getValue(route.panel))
                        }
                    }
                    val decorated = rememberDecoratedNavEntries(backStack = keys,
                        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()), entryProvider = entries)
                    val scenes = rememberSceneState(decorated, sceneStrategies = listOf(SinglePaneSceneStrategy()), onBack = ::back)
                    val gesture = rememberNavigationEventState(scenes)
                    val resting = rememberNavigationEventState(scenes)
                    NavigationBackHandler(scenes, gesture, ::back)
                    key(appearance.predictiveBack) {
                        CompositionLocalProvider(LocalBottomInset provides WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(), LocalRouteActive provides pages.isNotEmpty()) {
                            NavDisplay(sceneState = scenes, navigationEventState = if (appearance.predictiveBack) gesture else resting,
                                transitionEffects = NavDisplayTransitionEffects(enableCornerClip = false, dimAmount = 0f, blockInputDuringTransition = true))
                        }
                    }
                }
            }
        }
    }
}
