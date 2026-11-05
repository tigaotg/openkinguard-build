package io.github.tigaotg.openkinguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class MedicalScheduleTest {
    private val medicine = MedicalEntry("med-1", mapOf(
        "name" to "样例药", "times" to "08:00，20:00", "weekdays" to "1,3,5",
        "start" to "2026-11-02", "end" to "2026-11-30", "active" to "是"))

    @Test fun differentDaysAndStartEnd() {
        assertEquals(2, MedicalSchedule.slots(medicine).size)
        assertTrue(MedicalSchedule.allowedOn(medicine, LocalDate.parse("2026-11-06")))
        assertFalse(MedicalSchedule.allowedOn(medicine, LocalDate.parse("2026-11-07")))
        assertFalse(MedicalSchedule.allowedOn(medicine, LocalDate.parse("2026-12-02")))
    }

    @Test fun nextScheduledDayDoesNotRepeatPastAlarm() {
        val next = MedicalSchedule.next(medicine, LocalTime.of(8, 0),
            LocalDateTime.parse("2026-11-06T09:00"))
        assertEquals(LocalDateTime.parse("2026-11-09T08:00"), next)
    }

    @Test fun pausedMedicineNeverScheduled() {
        val stopped = medicine.copy(values = medicine.values + ("paused" to "是"))
        assertFalse(MedicalSchedule.active(stopped))
        assertEquals(null, MedicalSchedule.next(stopped, LocalTime.NOON,
            LocalDateTime.parse("2026-11-05T09:00")))
    }

    @Test fun futureStartDateManyMonthsAwayStillSchedules() {
        val future = medicine.copy(values = medicine.values +
            mapOf("start" to "2027-03-01", "end" to "2027-12-31"))
        assertEquals(LocalDateTime.parse("2027-03-01T08:00"),
            MedicalSchedule.next(future, LocalTime.of(8, 0),
                LocalDateTime.parse("2026-11-06T11:00")))
    }

    @Test fun confirmationRemainsDistinctFromUnconfirmed() {
        val state = MedicalVault.emptyState().copy(entries = mapOf(
            MedicalKind.MEDICINE to listOf(medicine),
            MedicalKind.DOSE to listOf(MedicalEntry("dose1", mapOf(
                "medicineId" to medicine.id, "planned" to "2026-11-06T08:00:00",
                "status" to "已服药")))))
        val today = MedicalSchedule.today(state, LocalDate.parse("2026-11-06"))
        assertEquals(2, today.size)
        assertEquals("已服药", today.first().status)
        assertEquals("待确认", today.last().status)
    }
}
