package io.github.tigaotg.openkinguard

/** Fixed, reviewed HTTPS entrypoints. Authentication is always owned by the system browser. */
internal data class OfficialDeviceFindPortal(
    val name: String,
    val url: String,
    val applicability: String,
)

internal object OfficialDeviceFindPortals {
    val entries = listOf(
        OfficialDeviceFindPortal("华为 · 云空间", "https://cloud.huawei.com/",
            "需提前开启华为查找设备；旧荣耀机型可能仍使用华为账号"),
        OfficialDeviceFindPortal("荣耀 · 查找设备", "https://cloud.honor.com/",
            "适用于支持荣耀账号查找设备的机型"),
        OfficialDeviceFindPortal("小米 / Redmi · 云服务", "https://i.mi.com/",
            "需已登录小米账号并开启查找设备"),
        OfficialDeviceFindPortal("OPPO / 一加 / realme · 欢太", "https://cloud.oppo.com/findindex.html",
            "适用于支持欢太账号查找的机型；部分地区入口不同"),
        OfficialDeviceFindPortal("vivo / iQOO · 云服务", "https://yun.vivo.com.cn/",
            "需使用丢失设备对应的 vivo 账号"),
        OfficialDeviceFindPortal("三星 · Samsung Find", "https://samsungfind.samsung.com/",
            "需三星账号及设备支持查找服务"),
        OfficialDeviceFindPortal("Google / Pixel / 摩托罗拉 · 查找中心",
            "https://android.com/find", "需设备支持并已开启 Google 查找中心；纯 AOSP 不一定可用"),
        OfficialDeviceFindPortal("魅族 · Flyme 查找手机", "https://finder.flyme.cn/",
            "适用于支持 Flyme 查找手机的机型"),
        OfficialDeviceFindPortal("联想 · 查找设备（部分平板）", "https://z.zui.com/",
            "官方说明目前仅特定拯救者 Y700 机型支持；其他平板不保证可用"),
        OfficialDeviceFindPortal("苹果 · iCloud 查找", "https://www.icloud.com/find",
            "方便家人查找 iPhone / iPad；需先启用苹果查找"),
    )

    init {
        require(entries.map { it.url }.distinct().size == entries.size)
        require(entries.all { it.url.startsWith("https://") && it.name.isNotBlank() })
    }
}
