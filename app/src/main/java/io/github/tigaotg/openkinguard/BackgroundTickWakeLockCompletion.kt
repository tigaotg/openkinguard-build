package io.github.tigaotg.openkinguard

import java.util.concurrent.atomic.AtomicBoolean

/**
 * A bounded heartbeat WakeLock must cover both synchronous tick work and the
 * asynchronous peer probe it starts. Either may finish first; release is claimed once.
 */
internal class BackgroundTickWakeLockCompletion {
    private val tickFinished = AtomicBoolean(false)
    private val probeFinished = AtomicBoolean(false)
    private val releaseClaimed = AtomicBoolean(false)

    fun markTickFinished(): Boolean {
        tickFinished.set(true)
        return canRelease()
    }

    fun markProbeFinished(): Boolean {
        probeFinished.set(true)
        return canRelease()
    }

    private fun canRelease(): Boolean =
        tickFinished.get() && probeFinished.get() &&
            releaseClaimed.compareAndSet(false, true)
}
