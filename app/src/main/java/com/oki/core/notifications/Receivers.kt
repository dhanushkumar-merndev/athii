package com.oki.core.notifications

import android.content.*
import com.oki.OkiApplication
import com.oki.feature.tasks.TaskNotificationKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("task_id") ?: return
        val pending = goAsync()
        val app = context.applicationContext as OkiApplication
        app.scope.launch {
            try {
                when (intent.action) {
                    "stop" -> app.container.publisher.dismissStart(id)
                    "done" -> app.container.tasks.complete(id, true)
                    "snooze" -> app.container.tasks.snooze(id)
                    "remind" -> {
                        val kind =
                            if (intent.getStringExtra("notification_kind") == "END")
                                TaskNotificationKind.END
                            else TaskNotificationKind.START
                        app.container.tasks.deliver(
                            id,
                            intent.getLongExtra("expected_at", -1),
                            kind,
                        ) {
                            app.container.publisher.publish(
                                it,
                                app.container.settings.settings.first(),
                                kind,
                            )
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

class RecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action !in
                setOf(
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_MY_PACKAGE_REPLACED,
                    Intent.ACTION_TIMEZONE_CHANGED,
                    Intent.ACTION_TIME_CHANGED,
                    "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
                )
        )
            return
        val pending = goAsync()
        val app = context.applicationContext as OkiApplication
        app.scope.launch {
            try {
                app.container.recover()
            } finally {
                pending.finish()
            }
        }
    }
}

class DailyResetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as OkiApplication
        app.scope.launch {
            try {
                app.container.doctors.ensureToday()
            } finally {
                app.container.dailyReset.scheduleNext()
                pending.finish()
            }
        }
    }
}
