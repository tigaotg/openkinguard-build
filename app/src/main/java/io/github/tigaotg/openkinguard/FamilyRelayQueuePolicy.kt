package io.github.tigaotg.openkinguard

/** A relay queue acknowledgement is transport metadata, never a device receipt. */
internal object FamilyRelayQueuePolicy {
    fun queuedDescription(survivesServiceRestart: Boolean): String =
        if (survivesServiceRestart)
            "家庭中继已暂存加密消息（最多5分钟，服务重启可恢复）；尚无对方手机签名回执，" +
                "路由器断电及超时后仍需本机自动补投，绝不可视为已送达"
        else
            "家庭中继仅在运行内存中暂存加密消息（最多5分钟）；尚无对方手机签名回执，" +
                "中继重启或超时后仍需本机自动补投，绝不可视为已送达"
}
