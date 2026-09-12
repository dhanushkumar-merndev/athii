package com.oki.core.notifications

import android.app.*
import android.content.*
import android.net.Uri
import android.os.Build
import com.oki.MainActivity
import com.oki.core.storage.*
import com.oki.feature.tasks.ReminderScheduler
import com.oki.feature.tasks.TaskNotificationKind
import java.time.*

class AndroidReminderScheduler(
    private val context: Context,
    private val publisher: NotificationPublisher,
) : ReminderScheduler {
    private val alarm = context.getSystemService(AlarmManager::class.java)

    fun hasExactAccess() = Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()

    override fun validate(task: Task) {
        if (task.reminderEnabled && !task.isCompleted && task.alertMode == TaskAlertMode.ALARM) {
            require(publisher.canNotify()) {
                "Allow notifications in Settings before saving an alarm."
            }
            require(hasExactAccess()) { "Allow on-time alerts in Settings before saving an alarm." }
        }
    }

    private fun intent(
        id: String,
        at: Long = 0,
        kind: TaskNotificationKind = TaskNotificationKind.START,
    ) =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReminderReceiver::class.java)
                .setAction("remind")
                // Keep the previous start identity so app updates replace existing schedules.
                .setData(
                    Uri.parse(
                        "oki://reminder/$id" + if (kind == TaskNotificationKind.END) "/end" else ""
                    )
                )
                .putExtra("task_id", id)
                .putExtra("notification_kind", kind.name)
                .putExtra("expected_at", at),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    override fun schedule(task: Task) {
        if (!task.reminderEnabled || task.isCompleted) return
        val now = System.currentTimeMillis()
        listOf(
                TaskNotificationKind.START to task.scheduledReminderAt,
                TaskNotificationKind.END to task.scheduledEndReminderAt,
            )
            .forEach { (kind, at) ->
                if (at != null && at > now) {
                    if (
                        kind == TaskNotificationKind.START && task.alertMode == TaskAlertMode.ALARM
                    ) {
                        if (hasExactAccess() && publisher.canNotify()) {
                            val show =
                                PendingIntent.getActivity(
                                    context,
                                    0,
                                    Intent(context, MainActivity::class.java)
                                        .setData(Uri.parse("oki://task/${task.id}"))
                                        .putExtra("task_id", task.id),
                                    PendingIntent.FLAG_UPDATE_CURRENT or
                                        PendingIntent.FLAG_IMMUTABLE,
                                )
                            try {
                                alarm.setAlarmClock(
                                    AlarmManager.AlarmClockInfo(at, show),
                                    intent(task.id, at, kind),
                                )
                            } catch (_: SecurityException) {
                                // Permission may be revoked after validation. Never turn an alarm
                                // into an inexact reminder.
                            } catch (_: IllegalStateException) {
                                // Per-app alarm ceiling reached; see scheduleAlarm.
                            }
                        }
                    } else scheduleAlarm(alarm, at, intent(task.id, at, kind), hasExactAccess())
                }
            }
    }

    override fun cancel(id: String) {
        alarm.cancel(intent(id))
        alarm.cancel(intent(id, kind = TaskNotificationKind.END))
    }

    override fun dismiss(id: String) = publisher.dismiss(id)
}

internal fun scheduleAlarm(alarm: AlarmManager, at: Long, pending: PendingIntent, exact: Boolean) {
    try {
        if (exact) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    } catch (_: SecurityException) {
        // Exact access can be revoked between the check and the call; never drop the reminder.
        try {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } catch (_: IllegalStateException) {
            /* At the per-app alarm ceiling. See below. */
        }
    } catch (_: IllegalStateException) {
        // Android caps an app at 500 concurrent alarms and throws once that is reached. Losing one
        // reminder is recoverable; letting this escape would kill the app on every launch, because
        // recovery reschedules every task at startup and would hit the same ceiling again.
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
