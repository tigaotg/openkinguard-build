package io.github.tigaotg.openkinguard

/** Recover a broken listener only while the user-granted background service is enabled. */
internal object BackgroundTcpListenerRecoveryPolicy {
    fun shouldAttempt(
        consent: BackgroundConsent,
        state: BackgroundTcpListenerState
    ): Boolean = consent == BackgroundConsent.ENABLED && when (state) {
        BackgroundTcpListenerState.LISTENING,
        BackgroundTcpListenerState.BINDING -> false
        BackgroundTcpListenerState.NOT_STARTED,
        BackgroundTcpListenerState.FAILED,
        BackgroundTcpListenerState.CLOSED_UNEXPECTEDLY -> true
    }
}
