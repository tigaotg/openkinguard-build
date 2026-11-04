package io.github.tigaotg.openkinguard.security.protocol

import org.junit.Assert.*
import org.junit.Test

class ExerciseMaintenanceTest {
    @Test fun `old unattended exercise request becomes historical`() {
        val now = 1_790_000_000_000L
        assertFalse(ExerciseMaintenance.staleUnclaimed(now - 1_000L, now))
        assertFalse(ExerciseMaintenance.staleUnclaimed(0L, now))
        assertFalse(ExerciseMaintenance.staleUnclaimed(now -
            ExerciseMaintenance.UNCLAIMED_EXPIRY_MS, now))
        assertTrue(ExerciseMaintenance.staleUnclaimed(now -
            ExerciseMaintenance.UNCLAIMED_EXPIRY_MS - 1L, now))
    }

    @Test fun `closing retry never gets permanently blocked`() {
        val now = 1_790_000_000_000L
        assertTrue(ExerciseMaintenance.shouldRetryClose(0L, 0, now))
        assertFalse(ExerciseMaintenance.shouldRetryClose(now - 1_000L, 1, now))
        assertTrue(ExerciseMaintenance.shouldRetryClose(now - 10_000L, 1, now))
        assertFalse(ExerciseMaintenance.shouldRetryClose(now - 60_000L, 50, now))
        assertTrue(ExerciseMaintenance.shouldRetryClose(now - 300_000L, 50, now))
        assertTrue(ExerciseMaintenance.shouldRetryClose(now + 5_000L, 6, now))
    }
}
