// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module.ui

import com.flipduo.module.DockConfig

/**
 * B05 layering: fine adjustments sit in a collapsed group under the basic items. A group whose values
 * differ from the defaults says so, so a collapsed group never hides that something was changed.
 * Pure Kotlin; display only — nothing here writes a setting.
 */
internal object SettingsLayers {
    fun cameraCalibrationCustom(c: Controls): Boolean =
        c.cameraMainX != 0 || c.cameraMainY != 0 || c.cameraLowerX != 0 || c.cameraLowerY != 0 ||
            c.cameraMainRadius != DockConfig.CAMERA_RADIUS_DEFAULT || c.cameraLowerRadius != DockConfig.CAMERA_RADIUS_DEFAULT ||
            c.cameraMainStroke != DockConfig.CAMERA_STROKE_DEFAULT || c.cameraLowerStroke != DockConfig.CAMERA_STROKE_DEFAULT

    fun trioFineCustom(c: Controls): Boolean =
        c.trioWifiScale != 100 || c.trioWifiStroke != 100 || c.trioNetworkScale != 100 || c.trioNetworkWeight != 100 ||
            c.trioBatteryScale != 100 || c.trioBatteryWeight != 100

    fun summary(custom: Boolean, open: Boolean): String =
        (if (custom) "已自定义" else "保持默认") + " · " + (if (open) "点按收起" else "点按展开")

    /** Saveable open-state encoding: comma-separated panel names. */
    fun isOpen(open: String, panel: String): Boolean = panel in open.split(',')
    fun toggle(open: String, panel: String): String =
        (if (isOpen(open, panel)) open.split(',') - panel else open.split(',') + panel).filter { it.isNotEmpty() }.joinToString(",")
}
