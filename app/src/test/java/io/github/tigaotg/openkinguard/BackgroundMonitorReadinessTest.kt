package io.github.tigaotg.openkinguard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundMonitorReadinessTest {
    private fun readiness(
        batteryExempt: Boolean = true,
        backgroundLocationGranted: Boolean = true,
        locationRelevant: Boolean = false,
        medicineAlarmRelevant: Boolean = false,
        exactAlarmAllowed: Boolean = true,
    ) = BackgroundReadiness(
        consent = BackgroundConsent.ENABLED,
        phase = BackgroundPhase.RUNNING,
        notificationsAllowed = true,
        batteryExempt = batteryExempt,
        backgroundRestricted = false,
        locationRelevant = locationRelevant,
        locationGranted = true,
        backgroundLocationGranted = backgroundLocationGranted,
        bluetoothRelevant = false,
        bluetoothGranted = true,
        medicineAlarmRelevant = medicineAlarmRelevant,
        exactAlarmAllowed = exactAlarmAllowed,
        serviceDetail = "test",
    )

    @Test fun batteryOptimizationIsAVisibleReadinessProblem() {
        val state = readiness(batteryExempt = false)
        assertTrue(state.needsAttention)
        assertTrue(state.advice().any { it.contains("电池优化") })
    }

    @Test fun missingBackgroundLocationIsVisibleOnlyWhenLocationSharingIsRelevant() {
        assertFalse(readiness(backgroundLocationGranted = false).needsAttention)
        val state = readiness(backgroundLocationGranted = false, locationRelevant = true)
        assertTrue(state.needsAttention)
        assertTrue(state.advice().any { it.contains("后台定位") })
    }

    @Test fun exactAlarmPermissionIsVisibleWhenMedicationRemindersAreActive() {
        assertFalse(readiness(exactAlarmAllowed = false).needsAttention)
        val state = readiness(medicineAlarmRelevant = true, exactAlarmAllowed = false)
        assertTrue(state.needsAttention)
        assertTrue(state.advice().any { it.contains("精确闹钟") })
    }
}
