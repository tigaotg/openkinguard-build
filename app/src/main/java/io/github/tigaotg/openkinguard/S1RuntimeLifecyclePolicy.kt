package io.github.tigaotg.openkinguard

/**
 * Foreground owner state for the same in-memory LAN/P2P identity.
 * The fullscreen controller is foreground UI, not background monitoring.
 */
internal object S1RuntimeLifecyclePolicy {
    fun shouldStop(uiVisible: Boolean, controllerVisible: Boolean,
                   backgroundListening: Boolean, projectionForeground: Boolean): Boolean =
        !uiVisible && !controllerVisible && !backgroundListening && !projectionForeground
}
