package io.github.tigaotg.openkinguard

import android.content.Context
import android.os.Build

/**
 * Single source of truth for the installed APK version.
 * Never hardcode a release version into the About screen or diagnostics.
 */
internal object AppVersionInfo {
    data class Installed(val name: String, val code: Long)

    @Suppress("DEPRECATION")
    fun installed(context: Context): Installed = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0L))
        } else pm.getPackageInfo(context.packageName, 0)
        val version = info.versionName?.takeIf { it.isNotBlank() } ?: "未知"
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
            else info.versionCode.toLong()
        Installed(version, code)
    }.getOrDefault(Installed("未读取到安装版本", -1L))

    fun name(context: Context): String = installed(context).name
}
