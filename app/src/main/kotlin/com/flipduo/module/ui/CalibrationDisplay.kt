package com.flipduo.module.ui

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flipduo.module.CutoutAppConfig

/** Calibration guidance uses physical display mode, not the app's compat-scaled or cropped window. */
internal class CalibrationDisplayObserver(
    private val view: View,
    private val changed: (Boolean?) -> Unit,
    private val readMode: () -> Display.Mode? = { view.display?.mode },
) : View.OnAttachStateChangeListener, DisplayManager.DisplayListener, DefaultLifecycleObserver {
    private val manager = view.context.getSystemService(DisplayManager::class.java)
    private val refreshAfterAttach = Runnable { refresh() }
    fun refresh() {
        changed(readMode()?.let { CutoutAppConfig.isCoverDisplay(it.physicalWidth, it.physicalHeight) })
    }
    fun start() {
        view.addOnAttachStateChangeListener(this)
        manager.registerDisplayListener(this, Handler(Looper.getMainLooper()))
        refresh()
    }
    fun stop() {
        view.removeCallbacks(refreshAfterAttach)
        view.removeOnAttachStateChangeListener(this)
        manager.unregisterDisplayListener(this)
    }
    override fun onViewAttachedToWindow(v: View) {
        refresh()
        view.post(refreshAfterAttach) // Initial composition can run before this View has a display.
    }
    override fun onViewDetachedFromWindow(v: View) { view.removeCallbacks(refreshAfterAttach); changed(null) }
    override fun onDisplayAdded(displayId: Int) = refresh()
    override fun onDisplayRemoved(displayId: Int) = refresh()
    override fun onDisplayChanged(displayId: Int) = refresh() // Logical display 0 can switch physical panels.
    override fun onResume(owner: LifecycleOwner) = refresh()
}

@Composable
internal fun calibrationOnCoverDisplay(): Boolean? {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val configuration = LocalConfiguration.current
    val onCover = remember(view) { mutableStateOf<Boolean?>(null) }
    DisposableEffect(view, lifecycle, configuration) {
        val observer = CalibrationDisplayObserver(view, { onCover.value = it })
        observer.start(); lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); observer.stop() }
    }
    return onCover.value
}
