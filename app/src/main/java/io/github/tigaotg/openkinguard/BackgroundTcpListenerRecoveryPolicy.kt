package io.github.tigaotg.openkinguard

/** Only a fully listening or transiently binding socket should be left untouched. */
internal object BackgroundTcpListenerRecoveryPolicy {
    fun shouldAttempt(state: BackgroundTcpListenerState): Boolean = when (state) {
        BackgroundTcpListenerState.LISTENING,
        BackgroundTcpListenerState.BINDING -> false
        BackgroundTcpListenerState.NOT_STARTED,
        BackgroundTcpListenerState.FAILED,
        BackgroundTcpListenerState.CLOSED_UNEXPECTEDLY -> true
    }
}
