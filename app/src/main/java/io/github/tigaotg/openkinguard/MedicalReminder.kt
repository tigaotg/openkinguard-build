package io.github.tigaotg.openkinguard

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class MedicineOccurrence(val medicine: MedicalEntry, val planned: LocalDateTime,
    val status: String = "待确认") {
    val key: String get() = planned.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
}

/** Pure schedule calculation, independent of time zone and notification permission. */
internal object MedicalSchedule {
    fun slots(medicine: MedicalEntry): List<LocalTime> = medicine.value("times")
        .split(',', '，', ';', '；', '\n').mapNotNull { candidate ->
            runCatching { LocalTime.parse(candidate.trim()) }.getOrNull()
        }.distinct().sorted()

    fun taking(medicine: MedicalEntry): Boolean =
        medicine.value("active").trim().lowercase() !in listOf("否", "false", "0", "已停止")

    fun active(medicine: MedicalEntry): Boolean = taking(medicine) &&
        medicine.value("paused").trim().lowercase() !in listOf("是", "true", "1", "暂停")

    fun allowedOn(medicine: MedicalEntry, date: LocalDate): Boolean {
        if (!active(medicine)) return false
        val days = medicine.value("weekdays").split(',', '，', '、', ' ', ';', '；')
            .mapNotNull { it.toIntOrNull() }.filter { it in 1..7 }
        if (days.isNotEmpty() && date.dayOfWeek.value !in days) return false
        val start = runCatching { LocalDate.parse(medicine.value("start")) }.getOrNull()
        val end = runCatching { LocalDate.parse(medicine.value("end")) }.getOrNull()
        return (start == null || !date.isBefore(start)) && (end == null || !date.isAfter(end))
    }

    fun next(medicine: MedicalEntry, time: LocalTime, now: LocalDateTime): LocalDateTime? {
        val start = runCatching { LocalDate.parse(medicine.value("start")) }.getOrNull()
        val firstDay = if (start != null && start.isAfter(now.toLocalDate())) start
            else now.toLocalDate()
        for (offset in 0..8) {
            val date = firstDay.plusDays(offset.toLong())
            val proposed = date.atTime(time)
            if (allowedOn(medicine, date) && proposed.isAfter(now)) return proposed
        }
        return null
    }

    fun today(state: MedicalState, date: LocalDate = LocalDate.now()): List<MedicineOccurrence> {
        val confirmed = state.records(MedicalKind.DOSE)
        return state.records(MedicalKind.MEDICINE).flatMap { medicine ->
            if (!allowedOn(medicine, date)) emptyList() else slots(medicine).map { time ->
                val planned = date.atTime(time)
                val key = planned.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                val done = confirmed.lastOrNull { it.value("medicineId") == medicine.id &&
                    it.value("planned") == key }
                MedicineOccurrence(medicine, planned, done?.value("status")?.ifBlank { "待确认" }
                    ?: "待确认")
            }
        }.sortedBy { it.planned }
    }
}

/** On-device alarms only. This receiver never sends a network message to family or relays. */
internal object MedicalReminders {
    const val ACTION_HEALTH = "io.github.tigaotg.openkinguard.OPEN_HEALTH"
    private const val ACTION_REMIND = "io.github.tigaotg.openkinguard.MEDICINE_REMIND"
    private const val ACTION_RESTORE = "io.github.tigaotg.openkinguard.MEDICINE_RESTORE"
    private const val CHANNEL = "okg_medication_local_v1"

    fun scheduleAll(context: Context) {
        val state = MedicalVault(context).snapshot()
        val now = LocalDateTime.now()
        state.records(MedicalKind.MEDICINE).forEach { medicine ->
            MedicalSchedule.slots(medicine).forEach { time ->
                schedule(context, medicine, time, now)
            }
        }
    }

    fun schedule(context: Context, medicine: MedicalEntry, time: LocalTime,
        now: LocalDateTime = LocalDateTime.now()) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val requestCode = (medicine.id + "|" + time).hashCode()
        val pending = pending(context, medicine.id, time.toString(), requestCode)
        alarm.cancel(pending)
        val date = MedicalSchedule.next(medicine, time, now) ?: return
        setAlarm(context, pending, date.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
    }

    fun snooze(context: Context, medicine: MedicalEntry, minutes: Long = 10) {
        val pending = pending(context, medicine.id, "snooze",
            (medicine.id + "|snooze").hashCode())
        setAlarm(context, pending, System.currentTimeMillis() + minutes * 60_000L)
    }

    private fun pending(context: Context, id: String, time: String, code: Int): PendingIntent {
        val intent = Intent(context, MedicalReminderReceiver::class.java)
            .setAction(ACTION_REMIND).putExtra("medicine_id", id).putExtra("time", time)
        return PendingIntent.getBroadcast(context, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun setAlarm(context: Context, pending: PendingIntent, epoch: Long) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= 31 && !alarm.canScheduleExactAlarms())
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epoch, pending)
        else alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epoch, pending)
    }

    fun show(context: Context, medicine: MedicalEntry) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "本机服药提醒",
            NotificationManager.IMPORTANCE_HIGH))
        val intent = Intent(context, MainActivity::class.java).setAction(ACTION_HEALTH)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val action = PendingIntent.getActivity(context, 7910, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val message = "${medicine.value("name")} · ${medicine.value("dose")}${medicine.value("unit")}".take(180)
        val note = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_openkinguard)
            .setContentTitle("服药提醒 · 请自行确认")
            .setContentText(message).setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(action).setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        manager.notify(medicine.id.hashCode(), note)
    }
}

class MedicalReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED, "android.intent.action.MY_PACKAGE_REPLACED" ->
                runCatching { MedicalReminders.scheduleAll(context) }
            "io.github.tigaotg.openkinguard.MEDICINE_REMIND" -> {
                val id = intent.getStringExtra("medicine_id") ?: return
                val medicine = MedicalVault(context).snapshot().records(MedicalKind.MEDICINE)
                    .firstOrNull { it.id == id } ?: return
                val time = intent.getStringExtra("time").orEmpty()
                if (!MedicalSchedule.active(medicine)) return
                if (time != "snooze" && !MedicalSchedule.allowedOn(medicine, LocalDate.now())) return
                if (time != "snooze" && MedicalSchedule.slots(medicine).none {
                        it.toString() == time }) return
                MedicalReminders.show(context, medicine)
                if (time != "snooze") {
                    val parsed = runCatching { LocalTime.parse(time) }.getOrNull() ?: return
                    MedicalReminders.schedule(context, medicine, parsed)
                }
            }
        }
    }
}
