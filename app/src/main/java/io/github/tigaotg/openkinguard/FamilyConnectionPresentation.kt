package io.github.tigaotg.openkinguard

/** User-facing connection state. Never infer reachability from a saved address alone. */
internal data class FamilyNodeBanner(
    val title: String,
    val instruction: String,
    val attempting: Boolean = false,
)

internal object FamilyConnectionPresentation {
    fun banner(saved: Boolean, enabled: Boolean, listening: Boolean,
               connected: Boolean, revoked: Boolean): FamilyNodeBanner = when {
        revoked -> FamilyNodeBanner("家庭节点已取消本机授权",
            "请联系路由器管理员，在已取消设备中重新授权。")
        connected -> FamilyNodeBanner("已连接家庭节点",
            "连接正常。给家人发送消息后，还需等待对方的送达确认。")
        !saved -> FamilyNodeBanner("尚未添加家庭节点",
            "扫描路由器上的家庭节点二维码即可添加。")
        !enabled -> FamilyNodeBanner("家庭节点尚未启用",
            "节点地址已保存，可在高级设置中启用。")
        !listening -> FamilyNodeBanner("家庭节点正在等待应用监听",
            "请开启本机监听；如果希望离开应用后仍尝试连接，可开启后台监听。")
        else -> FamilyNodeBanner("正在尝试连接家庭节点…",
            "系统会自动重试。请检查移动网络，以及路由器是否在线。", attempting = true)
    }
}

/** Clear next steps for nontechnical people; the signed reason stays in diagnostics. */
internal object LocationActionPresentation {
    fun message(code: String): String = when (code) {
        "REMOTE_DENIED" -> "对方还没有允许你查看位置。请让对方在 OpenKinGuard 中开启对你的定位授权。"
        "REMOTE_OS_PERMISSION" -> "对方手机尚未授予 OpenKinGuard 定位权限。请让对方在手机设置中允许定位。"
        "REMOTE_BACKGROUND" -> "请让对方打开 OpenKinGuard 并保持在屏幕前台，再试一次。"
        "REMOTE_NO_FIX" -> "对方暂时无法获取位置。请让对方打开手机定位开关，必要时到室外重试。"
        "NETWORK_TIMEOUT", "NETWORK_UNREACHABLE", "CONNECT_FAILED", "CONNECTION_INTERRUPTED" ->
            "暂时联系不上对方。请检查双方移动网络、家庭节点和应用监听是否正常，再重试。"
        "LOCAL_PERMISSION" -> "请检查本机 OpenKinGuard 的系统权限，再重新尝试。"
        else -> "暂时无法获取对方位置。请检查对方是否在线、定位授权和网络连接。"
    }
}
