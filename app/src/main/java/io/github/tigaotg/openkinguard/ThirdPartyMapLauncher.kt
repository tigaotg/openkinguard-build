package io.github.tigaotg.openkinguard

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.tigaotg.openkinguard.security.protocol.LocationMapPolicy

internal data class MapViewerChoice(val id: String, val title: String)

/** The receiver stores WGS84 GPS coordinates, not GCJ-02 or BD-09 coordinates. */
internal object MapHandoffPolicy {
    fun googleUrl(latE7: Int, lonE7: Int): String =
        "https://www.google.com/maps/search/?api=1&query=" +
            LocationMapPolicy.coordinates(latE7, lonE7).replace(",", "%2C")

    fun amapWebUrl(latE7: Int, lonE7: Int): String {
        val (lat, lon) = LocationMapPolicy.coordinates(latE7, lonE7).split(',')
        return "https://uri.amap.com/marker?position=${lon}%2C${lat}" +
            "&name=%E8%AE%BE%E5%A4%87%E4%BD%8D%E7%BD%AE&coordinate=wgs84&callnative=1"
    }

    /** Amap accepts an unshifted GPS fix when dev=1; do not label it GCJ-02. */
    fun amapNativeParameters(latE7: Int, lonE7: Int): Map<String, String> {
        val (lat, lon) = LocationMapPolicy.coordinates(latE7, lonE7).split(',')
        return linkedMapOf("sourceApplication" to "OpenKinGuard", "poiname" to "设备位置",
            "lat" to lat, "lon" to lon, "dev" to "1")
    }

    /** Baidu requires coord_type=wgs84 to interpret GPS rather than BD-09. */
    fun baiduNativeParameters(latE7: Int, lonE7: Int): Map<String, String> = linkedMapOf(
        "location" to LocationMapPolicy.coordinates(latE7, lonE7),
        "title" to "设备位置", "coord_type" to "wgs84",
        "src" to "andr.tigaotg.openkinguard")
}

/** Every map handoff requires an explicit tap; no coordinate is uploaded by this app. */
internal object ThirdPartyMapLauncher {
    // Legacy settings remain readable; Google is now a separate explicit choice.
    const val ASK = "ask"
    const val DOMESTIC_AUTO = "domestic_auto"

    @Suppress("DEPRECATION")
    private fun installed(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    fun availableDomestic(context: Context): List<MapViewerChoice> = buildList {
        add(MapViewerChoice(DOMESTIC_AUTO, "自动（优先高德，其次百度）"))
        if (installed(context, "com.autonavi.minimap")) add(MapViewerChoice("amap", "高德地图"))
        if (installed(context, "com.baidu.BaiduMap")) add(MapViewerChoice("baidu", "百度地图"))
    }

    fun openGoogle(context: Context, fix: ReceivedLocationFix): String {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(
            MapHandoffPolicy.googleUrl(fix.latitudeE7, fix.longitudeE7)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val googleInstalled = installed(context, "com.google.android.apps.maps")
        try {
            context.startActivity(if (googleInstalled)
                Intent(intent).setPackage("com.google.android.apps.maps") else intent)
        } catch (unavailable: android.content.ActivityNotFoundException) {
            if (!googleInstalled) throw unavailable
            context.startActivity(intent) // App cannot accept the URL: allow browser fallback.
        }
        return "已打开 Google 地图链接（WGS84 坐标）；地图或浏览器可能联网处理位置。"
    }

    fun openDomestic(context: Context, fix: ReceivedLocationFix, preferred: String): String {
        val hasAmap = installed(context, "com.autonavi.minimap")
        val hasBaidu = installed(context, "com.baidu.BaiduMap")
        val target = when {
            preferred == "baidu" && hasBaidu -> "baidu"
            preferred == "amap" && hasAmap -> "amap"
            hasAmap -> "amap"
            hasBaidu -> "baidu"
            else -> "amap_web"
        }
        val intent = when (target) {
            "amap" -> Intent(Intent.ACTION_VIEW,
                Uri.Builder().scheme("androidamap").authority("viewMap")
                    .apply { MapHandoffPolicy.amapNativeParameters(
                        fix.latitudeE7, fix.longitudeE7).forEach { (key, value) ->
                        appendQueryParameter(key, value) } }
                    .build()).setPackage("com.autonavi.minimap")
            "baidu" -> Intent(Intent.ACTION_VIEW,
                Uri.Builder().scheme("baidumap").authority("map").appendPath("marker")
                    .apply { MapHandoffPolicy.baiduNativeParameters(
                        fix.latitudeE7, fix.longitudeE7).forEach { (key, value) ->
                        appendQueryParameter(key, value) } }
                    .build()).setPackage("com.baidu.BaiduMap")
            else -> Intent(Intent.ACTION_VIEW, Uri.parse(
                MapHandoffPolicy.amapWebUrl(fix.latitudeE7, fix.longitudeE7)))
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (unavailable: android.content.ActivityNotFoundException) {
            // A vendor may expose its package but not the documented deep link.
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                MapHandoffPolicy.amapWebUrl(fix.latitudeE7, fix.longitudeE7)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return "已打开国内地图（按 WGS84 原始坐标交接，由地图处理坐标转换）；" +
            "地图可能联网处理位置。"
    }
}
