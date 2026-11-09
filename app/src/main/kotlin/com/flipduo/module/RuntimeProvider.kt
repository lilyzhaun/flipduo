package com.flipduo.module

import android.content.*
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock

class RuntimeProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val c = requireNotNull(context)
        val uid = Binder.getCallingUid()
        if (method !in setOf("report", "status") || extras == null) return null
        val host = extras.getString("host")
        val pkg = when (host) { "fliphome" -> "com.miui.fliphome"; "systemui" -> "com.android.systemui"; else -> throw SecurityException("Unknown host") }
        val info = c.packageManager.getApplicationInfo(pkg, 0)
        if (uid != info.uid || info.flags and ApplicationInfo.FLAG_SYSTEM == 0) throw SecurityException("Untrusted caller")
        if (method == "status") {
            // SystemUI originates state; FlipHome may relay the same INJECT_EVENTS-protected status wire.
            val state = extras.getBundle("state") ?: return null
            val editor = c.getSharedPreferences("runtime", Context.MODE_PRIVATE).edit()
            for (key in listOf("battery", "wl", "ml")) editor.putInt("status_$key", state.getInt(key, -1))
            for (key in listOf("charging", "saver", "wk", "we", "wc", "mk", "sim", "service", "air")) editor.putBoolean("status_$key", state.getBoolean(key))
            editor.putString("status_rat", state.getString("rat", "")).apply()
            return Bundle().apply { putBoolean("accepted", true) }
        }
        val editor = c.getSharedPreferences("runtime", Context.MODE_PRIVATE).edit()
            .putInt("${host}_revision", extras.getInt("revision", -1))
            .putLong("${host}_time", System.currentTimeMillis())
            .putLong("${host}_elapsed", SystemClock.elapsedRealtime())
            .putBoolean("${host}_enabled", extras.getBoolean("enabled"))
        // New fields are bounded and fixed-format; an old-format receipt clears them so they cannot be mixed.
        val module = extras.getInt("module", -1)
        val name = extras.getString("module_name").orEmpty()
        val pid = extras.getInt("pid", -1)
        val caps = extras.getString("caps")
        if (module > 0 && pid > 0 && name.length <= 32 && HostCapabilities.storable(caps)) {
            editor.putInt("${host}_module", module).putString("${host}_module_name", name).putInt("${host}_pid", pid)
                .putLong("${host}_process_start", extras.getLong("process_start", -1L)).putString("${host}_caps", caps)
        } else {
            for (key in listOf("module", "module_name", "pid", "process_start", "caps")) editor.remove("${host}_$key")
        }
        editor.apply()
        c.contentResolver.notifyChange(Uri.parse("content://com.flipduo.module.runtime"), null)
        return Bundle().apply { putBoolean("accepted", true) }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
