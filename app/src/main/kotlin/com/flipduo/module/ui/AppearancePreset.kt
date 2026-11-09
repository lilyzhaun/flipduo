// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module.ui

import com.flipduo.module.DockConfig

/**
 * B05 local appearance presets. Whitelist only: colours, ring strokes, trio style, clock, control size and
 * app-side corners. Never camera position/radius calibration (hardware, per device), app lists, long-press
 * actions, the master switch, the (experimental) cover background, feedback or revision. Pure Kotlin.
 *
 * Encoding "v1;name=b1;name=i42;..." is plain data: unknown names and malformed values are ignored and never
 * change the current value; numbers are clamped to the same ranges the settings reader uses.
 */
internal object AppearancePreset {
    private val INTS = mapOf<String, Pair<(Controls) -> Int, IntRange?>>(
        "cameraTimerColor" to ({ c: Controls -> c.cameraTimerColor } to null),
        "cameraProgressColor" to ({ c: Controls -> c.cameraProgressColor } to null),
        "cameraChargeColor" to ({ c: Controls -> c.cameraChargeColor } to null),
        "cameraTimerBreathColor" to ({ c: Controls -> c.cameraTimerBreathColor } to null),
        "cameraNotificationColor" to ({ c: Controls -> c.cameraNotificationColor } to null),
        "cameraMainStroke" to ({ c: Controls -> c.cameraMainStroke } to DockConfig.CAMERA_STROKE_MIN..DockConfig.CAMERA_STROKE_MAX),
        "cameraLowerStroke" to ({ c: Controls -> c.cameraLowerStroke } to DockConfig.CAMERA_STROKE_MIN..DockConfig.CAMERA_STROKE_MAX),
        "statusScale" to ({ c: Controls -> c.statusScale } to 80..120),
        "trioStroke" to ({ c: Controls -> c.trioStroke } to 60..140),
        "trioWifiScale" to ({ c: Controls -> c.trioWifiScale } to 70..130),
        "trioWifiStroke" to ({ c: Controls -> c.trioWifiStroke } to 60..140),
        "trioNetworkScale" to ({ c: Controls -> c.trioNetworkScale } to 70..140),
        "trioNetworkWeight" to ({ c: Controls -> c.trioNetworkWeight } to 70..130),
        "trioBatteryScale" to ({ c: Controls -> c.trioBatteryScale } to 70..130),
        "trioBatteryWeight" to ({ c: Controls -> c.trioBatteryWeight } to 70..130),
        "scale" to ({ c: Controls -> c.scale } to 80..100),
        "iconScale" to ({ c: Controls -> c.iconScale } to 70..130),
    )
    private val BOOLS = mapOf<String, (Controls) -> Boolean>(
        "showTrio" to { it.showTrio }, "trioBatteryNumber" to { it.trioBatteryNumber }, "showClock" to { it.showClock },
        "systemCorners" to { it.systemCorners }, "symmetricCorners" to { it.symmetricCorners },
    )

    /** Field names a preset can carry (for tests and the page's explanation). */
    val fields: Set<String> get() = INTS.keys + BOOLS.keys

    fun capture(c: Controls): String = buildString {
        append("v1")
        for ((name, read) in INTS) append(';').append(name).append("=i").append(read.first(c))
        for ((name, read) in BOOLS) append(';').append(name).append("=b").append(if (read(c)) 1 else 0)
    }

    /** Values parsed from [encoded]; anything unknown, oversized or malformed is dropped. */
    fun parse(encoded: String?): Map<String, Any> {
        if (encoded == null || encoded.length > 4096 || !encoded.startsWith("v1")) return emptyMap()
        val out = LinkedHashMap<String, Any>()
        for (part in encoded.split(';').drop(1)) {
            val eq = part.indexOf('='); if (eq <= 0 || eq + 2 > part.length) continue
            val name = part.substring(0, eq); val kind = part[eq + 1]; val raw = part.substring(eq + 2)
            when {
                kind == 'i' && name in INTS -> raw.toIntOrNull()?.let { v -> out[name] = INTS.getValue(name).second?.let { v.coerceIn(it) } ?: v }
                kind == 'b' && name in BOOLS && (raw == "0" || raw == "1") -> out[name] = raw == "1"
            }
        }
        return out
    }

    /** [current] with only the whitelisted values present in [encoded]; everything else, incl. revision, unchanged. */
    fun apply(current: Controls, encoded: String?): Controls {
        val v = parse(encoded)
        fun i(name: String, now: Int) = v[name] as? Int ?: now
        fun b(name: String, now: Boolean) = v[name] as? Boolean ?: now
        return current.copy(
            cameraTimerColor = i("cameraTimerColor", current.cameraTimerColor),
            cameraProgressColor = i("cameraProgressColor", current.cameraProgressColor),
            cameraChargeColor = i("cameraChargeColor", current.cameraChargeColor),
            cameraTimerBreathColor = i("cameraTimerBreathColor", current.cameraTimerBreathColor),
            cameraNotificationColor = i("cameraNotificationColor", current.cameraNotificationColor),
            cameraMainStroke = i("cameraMainStroke", current.cameraMainStroke),
            cameraLowerStroke = i("cameraLowerStroke", current.cameraLowerStroke),
            statusScale = i("statusScale", current.statusScale), trioStroke = i("trioStroke", current.trioStroke),
            trioWifiScale = i("trioWifiScale", current.trioWifiScale), trioWifiStroke = i("trioWifiStroke", current.trioWifiStroke),
            trioNetworkScale = i("trioNetworkScale", current.trioNetworkScale), trioNetworkWeight = i("trioNetworkWeight", current.trioNetworkWeight),
            trioBatteryScale = i("trioBatteryScale", current.trioBatteryScale), trioBatteryWeight = i("trioBatteryWeight", current.trioBatteryWeight),
            scale = i("scale", current.scale), iconScale = i("iconScale", current.iconScale),
            showTrio = b("showTrio", current.showTrio), trioBatteryNumber = b("trioBatteryNumber", current.trioBatteryNumber),
            showClock = b("showClock", current.showClock),
            systemCorners = b("systemCorners", current.systemCorners), symmetricCorners = b("symmetricCorners", current.symmetricCorners),
        )
    }
}
