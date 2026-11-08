package io.github.tigaotg.openkinguard

/** Persistent UDP listening requires three independent facts, not a generic online flag. */
internal object S5DirectListenerPolicy {
    fun shouldRestore(
        savedConsent: Boolean,
        publicSignalingEnabled: Boolean,
        localServiceRunning: Boolean
    ): Boolean = savedConsent && publicSignalingEnabled && localServiceRunning
}
