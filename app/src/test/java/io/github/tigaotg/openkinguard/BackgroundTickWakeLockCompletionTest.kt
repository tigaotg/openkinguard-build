package io.github.tigaotg.openkinguard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundTickWakeLockCompletionTest {
    @Test fun wakeLockWaitsForProbeAfterSynchronousTickFinishes() {
        val completion = BackgroundTickWakeLockCompletion()
        assertFalse(completion.markTickFinished())
        assertTrue(completion.markProbeFinished())
    }

    @Test fun wakeLockWaitsForSynchronousTickAfterProbeFinishes() {
        val completion = BackgroundTickWakeLockCompletion()
        assertFalse(completion.markProbeFinished())
        assertTrue(completion.markTickFinished())
    }

    @Test fun wakeLockReleaseCanOnlyBeClaimedOnce() {
        val completion = BackgroundTickWakeLockCompletion()
        assertFalse(completion.markTickFinished())
        assertTrue(completion.markProbeFinished())
        assertFalse(completion.markTickFinished())
        assertFalse(completion.markProbeFinished())
    }
}
