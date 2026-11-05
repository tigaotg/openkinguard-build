package io.github.tigaotg.openkinguard

/** Reject callbacks from old, cancelled QR applications before touching Compose state. */
internal object RelayJoinUiPolicy {
    fun shouldApply(activeAttempt: Int, callbackAttempt: Int): Boolean =
        activeAttempt == callbackAttempt

    fun terminal(message: String): Boolean = message.startsWith("加入未完成") ||
        message.startsWith("路由器主人已批准") ||
        message.startsWith("路由器已批准") ||
        message.startsWith("申请等待超时")
}
