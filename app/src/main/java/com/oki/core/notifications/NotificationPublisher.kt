package com.oki.core.notifications

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.oki.MainActivity
import com.oki.R
import com.oki.core.storage.*
import com.oki.feature.tasks.TaskNotificationKind
import java.security.MessageDigest
import java.time.*
import java.time.format.DateTimeFormatter

object ChannelIdentity {
    private fun fingerprint(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(12).joinToString("") {
            "%02x".format(it)
        }

    /**
     * [bypassAllowed] is the *effective* bypass, not the preference: Android drops the flag when
     * the app has no Do Not Disturb access, and a channel's bypass is immutable once created.
     * Keying the id on the effective value means granting access later produces a new channel that
     * actually bypasses, instead of silently reusing a muted one.
     */
    fun forSettings(settings: Settings, bypassAllowed: Boolean = false): String =
        when (settings.soundMode) {
            SoundMode.SYSTEM -> "reminders_default_v1"
            SoundMode.SILENT -> "reminders_silent_v1"
            SoundMode.CUSTOM -> "reminders_custom_" + fingerprint(settings.customSoundUri)
        } + if (bypassAllowed) "_dnd" else ""

    /**
     * A channel's sound is immutable once Android creates it, so the chosen alarm tone has to be
     * part of the channel id or switching tones would silently keep the old one.
     */
    fun forAlarm(soundUri: Uri?, bypassAllowed: Boolean = false): String =
        NotificationPublisher.ALARM_CHANNEL_PREFIX +
            fingerprint(soundUri?.toString().orEmpty()) +
            if (bypassAllowed) "_dnd" else ""

    fun notificationTag(taskId: String, kind: TaskNotificationKind = TaskNotificationKind.START) =
        "task:$taskId" + if (kind == TaskNotificationKind.END) ":end" else ""
}

class NotificationPublisher(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    companion object {
        const val ALARM_CHANNEL_PREFIX = "task_alarms_"
        const val ALARM_TIMEOUT_MS = 300_000L
    }

    fun canShowFullScreenAlarm(): Boolean =
        Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent()

    /**
     * Android keeps timers only while it believes the app matters. Without this exemption an OEM
     * battery manager can freeze Athii once it leaves the screen, and alarms arrive late or not at
     * all.
     */
    fun ignoresBatteryOptimizations(): Boolean =
        context
            .getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    /** The tone an ALARM-mode task rings with. Null only when the device reports no ringtone. */
    fun alarmSoundUri(settings: Settings): Uri? =
        when (settings.alarmSound) {
            AlarmSound.ALARM -> AndroidSettings.System.DEFAULT_ALARM_ALERT_URI
            AlarmSound.CUSTOM ->
                settings.alarmSoundUri.takeIf(String::isNotBlank)?.let(Uri::parse)
                    ?: AndroidSettings.System.DEFAULT_ALARM_ALERT_URI
            AlarmSound.RINGTONE ->
                runCatching {
                        RingtoneManager.getActualDefaultRingtoneUri(
                            context,
                            RingtoneManager.TYPE_RINGTONE,
                        )
                    }
                    .getOrNull() ?: AndroidSettings.System.DEFAULT_ALARM_ALERT_URI
        }

    /** Android ignores a bypass request unless the user has granted Do Not Disturb access. */
    fun canBypassDnd(): Boolean = manager.isNotificationPolicyAccessGranted

    /** The preference only takes effect once the user has granted Do Not Disturb access. */
    private fun effectiveBypass(settings: Settings) = settings.bypassDnd && canBypassDnd()

    fun alarmChannel(settings: Settings): String {
        val uri = alarmSoundUri(settings)
        val bypass = effectiveBypass(settings)
        val id = ChannelIdentity.forAlarm(uri, bypass)
        manager.createNotificationChannel(
            NotificationChannel(id, "Task alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                description =
                    "Alarms you choose for task start times. Uses the device alarm volume."
                setSound(
                    uri,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                setBypassDnd(bypass)
            }
        )
        // A channel per tone would otherwise pile up in Android's notification settings.
        manager.notificationChannels
            .filter { it.id.startsWith(ALARM_CHANNEL_PREFIX) && it.id != id }
            .forEach { manager.deleteNotificationChannel(it.id) }
        return id
    }

    fun isAlarmActive(id: String): Boolean =
        manager.activeNotifications.any {
            it.tag == ChannelIdentity.notificationTag(id) &&
                it.notification.channelId.orEmpty().startsWith(ALARM_CHANNEL_PREFIX)
        }

    fun canNotify(): Boolean =
        manager.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED)

    fun channel(settings: Settings): String {
        val bypass = effectiveBypass(settings)
        val id = ChannelIdentity.forSettings(settings, bypass)
        val uri =
            when (settings.soundMode) {
                SoundMode.SYSTEM -> AndroidSettings.System.DEFAULT_NOTIFICATION_URI
                SoundMode.SILENT -> null
                SoundMode.CUSTOM -> Uri.parse(settings.customSoundUri)
            }
        if (settings.soundMode == SoundMode.CUSTOM) {
            // Notification system needs read access to our private validated audio copy.
            context.grantUriPermission(
                "com.android.systemui",
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            context.grantUriPermission("android", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        manager.createNotificationChannel(
            NotificationChannel(
                    id,
                    when (settings.soundMode) {
                        SoundMode.SYSTEM -> "Reminders · system sound"
                        SoundMode.SILENT -> "Reminders · silent"
                        SoundMode.CUSTOM -> "Reminders · custom sound"
                    },
                    NotificationManager.IMPORTANCE_HIGH,
                )
                .apply {
                    description = "Local task reminders. Sound follows your device settings."
                    setSound(
                        uri,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    enableVibration(settings.soundMode != SoundMode.SILENT)
                    setBypassDnd(bypass)
                }
        )
        return id
    }

    fun publish(
        task: Task,
        settings: Settings,
        kind: TaskNotificationKind = TaskNotificationKind.START,
    ) {
        if (!canNotify()) return
        if (kind == TaskNotificationKind.START && task.alertMode == TaskAlertMode.ALARM) {
            publishAlarm(task, settings)
            return
        }
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .setData(Uri.parse("oki://task/${task.id}"))
                    .putExtra("task_id", task.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val time =
            if (kind == TaskNotificationKind.END) task.scheduledEndReminderAt ?: task.dueAt
            else task.dueAt
        val due =
            Instant.ofEpochMilli(time)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("EEE, d MMM · h:mm a"))
        val summary =
            if (kind == TaskNotificationKind.END) "${task.title} · Ended $due" else "Starts $due"
        val builder =
            NotificationCompat.Builder(context, channel(settings))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(
                    if (kind == TaskNotificationKind.END) "Task time is over: ${task.title}"
                    else task.title
                )
                .setContentText(summary)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(
                            listOf(summary, task.notes.take(240))
                                .filter { it.isNotBlank() }
                                .joinToString("\n")
                        )
                )
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .addAction(0, "Mark done", action(task.id, "done"))
        if (kind == TaskNotificationKind.START)
            builder.addAction(0, "Snooze 5 min", action(task.id, "snooze"))
        try {
            manager.notify(ChannelIdentity.notificationTag(task.id, kind), 0, builder.build())
        } catch (_: SecurityException) {
            /* permission revoked between check and publish */
        }
    }

    private fun publishAlarm(task: Task, settings: Settings) {
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, AlarmActivity::class.java)
                    .setData(Uri.parse("oki://alarm/${task.id}"))
                    .putExtra("task_id", task.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            NotificationCompat.Builder(context, alarmChannel(settings))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(task.title)
                .setContentText("Time to start · Mark done, snooze, or stop")
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentIntent(open)
                .setOngoing(true)
                .setAutoCancel(false)
                .setTimeoutAfter(ALARM_TIMEOUT_MS)
                .addAction(0, "Mark done", action(task.id, "done"))
                .addAction(0, "Snooze 5 min", action(task.id, "snooze"))
                .addAction(0, "Stop", action(task.id, "stop"))
        if (canShowFullScreenAlarm()) builder.setFullScreenIntent(open, true)
        val notification = builder.build().apply { flags = flags or Notification.FLAG_INSISTENT }
        try {
            manager.notify(ChannelIdentity.notificationTag(task.id), 0, notification)
        } catch (_: SecurityException) {
            // Notification access can change while the alarm is being delivered.
        }
    }

    private fun action(id: String, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReminderReceiver::class.java)
                .setAction(action)
                .setData(Uri.parse("oki://$action/$id"))
                .putExtra("task_id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun dismiss(id: String) {
        dismissStart(id)
        manager.cancel(ChannelIdentity.notificationTag(id, TaskNotificationKind.END), 0)
    }

    fun dismissStart(id: String) {
        manager.cancel(ChannelIdentity.notificationTag(id), 0)
    }

    fun clear() {
        manager.cancelAll()
    }
}
