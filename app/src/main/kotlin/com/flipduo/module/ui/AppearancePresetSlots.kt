// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module.ui

import android.content.SharedPreferences

internal data class AppearancePresetSlot(val name: String, val encoded: String?, val savedAt: Long)

/** Names are local slot metadata, separate from the existing v1 appearance payload and runtime settings. */
internal class AppearancePresetSlots(private val prefs: SharedPreferences) {
    fun read(slot: Int) = AppearancePresetSlot(
        prefs.getString("slot_${slot}_name", null)?.takeIf { it.isNotBlank() } ?: defaultName(slot),
        prefs.getString("slot_$slot", null), prefs.getLong("slot_${slot}_time", 0L),
    )

    fun save(slot: Int, rawName: String, controls: Controls): Boolean {
        val name = normalizedName(rawName) ?: return false
        prefs.edit().putString("slot_$slot", AppearancePreset.capture(controls))
            .putString("slot_${slot}_name", name).putLong("slot_${slot}_time", System.currentTimeMillis()).apply()
        return true
    }

    fun rename(slot: Int, rawName: String): Boolean {
        val name = normalizedName(rawName) ?: return false
        if (prefs.getString("slot_$slot", null) == null) return false
        prefs.edit().putString("slot_${slot}_name", name).apply()
        return true
    }

    fun clear(slot: Int) {
        prefs.edit().remove("slot_$slot").remove("slot_${slot}_time").remove("slot_${slot}_name").apply()
    }

    companion object {
        const val NAME_LIMIT = 24
        fun defaultName(slot: Int) = "方案" + "一二三"[slot - 1]
        fun normalizedName(raw: String): String? = raw.trim().takeIf {
            it.isNotEmpty() && it.codePointCount(0, it.length) <= NAME_LIMIT && '\n' !in it && '\r' !in it
        }
    }
}
