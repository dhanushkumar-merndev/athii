package com.oki.core.notifications

import android.app.*
import android.content.*
import android.net.Uri
import android.os.Build
import com.oki.core.storage.*
import com.oki.feature.tasks.ReminderScheduler
import java.time.*

class AndroidReminderScheduler(
    private val context: Context,
    private val publisher: NotificationPublisher,
) : ReminderScheduler {
    private val alarm = context.getSystemService(AlarmManager::class.java)

    fun hasExactAccess() = Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()

    private fun intent(id: String, at: Long = 0) =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReminderReceiver::class.java)
                .setAction("remind")
                .setData(Uri.parse("oki://reminder/$id"))
                .putExtra("task_id", id)
                .putExtra("expected_at", at),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    override fun schedule(task: Task) {
        val at = task.scheduledReminderAt ?: return
        if (!task.reminderEnabled || task.isCompleted || at <= System.currentTimeMillis()) return
        scheduleAlarm(alarm, at, intent(task.id, at), hasExactAccess())
    }

    override fun cancel(id: String) {
        alarm.cancel(intent(id))
    }

    override fun dismiss(id: String) = publisher.dismiss(id)
}

internal fun scheduleAlarm(alarm: AlarmManager, at: Long, pending: PendingIntent, exact: Boolean) {
    try {
        if (exact) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    } catch (_: SecurityException) {
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }
}

class DailyDoctorResetScheduler(private val context: Context) {
    fun scheduleNext() {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, DailyResetReceiver::class.java).setAction("midnight"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val at = TimeRules.nextMidnight(Instant.now(), ZoneId.systemDefault()).toEpochMilli()
        scheduleAlarm(
            alarm,
            at,
            pending,
            Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms(),
        )
    }
}
