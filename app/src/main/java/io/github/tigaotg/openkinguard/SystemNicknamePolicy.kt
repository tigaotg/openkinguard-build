package io.github.tigaotg.openkinguard

import android.content.Context
import android.os.Build
import android.provider.Settings

/** The old default is a placeholder, not an explicit user choice. */
internal object SystemNicknamePolicy {
    fun isLegacyCustom(stored: String): Boolean =
        stored.isNotBlank() && stored != "我的设备"

    fun displayName(customized: Boolean, stored: String, systemName: String?,
        manufacturer: String?, model: String?): String {
        if (customized && stored.isNotBlank()) return stored
        val modelName = model.orEmpty().trim()
        val maker = manufacturer.orEmpty().trim()
        val fallback = if (modelName.contains(maker, ignoreCase = true) || maker.isBlank())
            modelName else "$maker $modelName".trim()
        return normalize(systemName).ifBlank { normalize(fallback) }.ifBlank { "安卓手机" }
    }

    private fun normalize(name: String?): String = name.orEmpty().trim().filterNot { ch ->
        Character.isISOControl(ch) || ch in setOf('\u202a', '\u202b', '\u202c',
            '\u202d', '\u202e', '\u2066', '\u2067', '\u2068', '\u2069')
    }.take(18).trim()

    fun fromAndroid(context: Context, customized: Boolean, stored: String): String {
        // Android/OEMs vary in where they expose the name set in system settings.
        val systemName = sequenceOf(
            { Settings.Global.getString(context.contentResolver, "device_name") },
            { Settings.Secure.getString(context.contentResolver, "device_name") },
            { Settings.System.getString(context.contentResolver, "device_name") },
        ).mapNotNull { provider -> runCatching { provider() }.getOrNull() }
            .firstOrNull { it.isNotBlank() }
        return displayName(customized, stored, systemName, Build.MANUFACTURER, Build.MODEL)
    }
}
