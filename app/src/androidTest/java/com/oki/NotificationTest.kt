package com.oki

import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oki.core.ai.aiJson
import com.oki.core.notifications.*
import com.oki.core.storage.*
import com.oki.feature.tasks.TaskNotificationKind
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
class NotificationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command.replace("com.oki", context.packageName))
            .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    }

    @Test
    fun scheduledRemindersUseTheSameSoundPolicyAsSettingsTest(): Unit = runBlocking {
        shell("pm grant com.oki android.permission.POST_NOTIFICATIONS")
        shell("appops set com.oki SCHEDULE_EXACT_ALARM allow")
        val c = (context.applicationContext as OkiApplication).container
        val previous = c.settings.settings.first()
        val manager = context.getSystemService(NotificationManager::class.java)
        val createdIds = mutableListOf<String>()
        val previewIds = mutableListOf<String>()
        try {
            listOf(SoundMode.SYSTEM to false, SoundMode.SYSTEM to true, SoundMode.SILENT to true)
                .forEach { (mode, bypass) ->
                    val taskId = "scheduled-sound-policy-$mode-$bypass".also(createdIds::add)
                    val previewId = "preview-sound-policy-$mode-$bypass".also(previewIds::add)
                    c.settings.setSound(mode)
                    c.settings.setBypassDnd(bypass)
                    val settings = c.settings.settings.first()
                    val task =
                        Task(
                            id = taskId,
                            title = "Scheduled sound check",
                            dueAt = System.currentTimeMillis() + 2500,
                        )
                    c.publisher.publish(task.copy(id = previewId), settings)
                    c.tasks.save(task)
                    val deadline = System.currentTimeMillis() + 12000
                    while (
                        manager.activeNotifications.none {
                            it.tag == ChannelIdentity.notificationTag(taskId)
                        } && System.currentTimeMillis() < deadline
                    ) kotlinx.coroutines.delay(50)
                    val delivered =
                        manager.activeNotifications.single {
                            it.tag == ChannelIdentity.notificationTag(taskId)
                        }
                    val preview =
                        manager.activeNotifications.single {
                            it.tag == ChannelIdentity.notificationTag(previewId)
                        }
                    assertEquals(preview.notification.channelId, delivered.notification.channelId)
                    val channel = manager.getNotificationChannel(delivered.notification.channelId)
                    assertEquals(
                        if (bypass && mode != SoundMode.SILENT)
                            android.media.AudioAttributes.USAGE_ALARM
                        else android.media.AudioAttributes.USAGE_NOTIFICATION,
                        channel.audioAttributes.usage,
                    )
                    if (mode == SoundMode.SILENT) {
                        assertNull(channel.sound)
                        assertFalse(channel.shouldVibrate())
                    } else assertNotNull(channel.sound)
                    c.tasks.delete(taskId)
                    c.publisher.dismiss(previewId)
                }
        } finally {
            createdIds.forEach { c.tasks.delete(it) }
            previewIds.forEach(c.publisher::dismiss)
            c.settings.setSound(previous.soundMode, previous.customSoundUri)
            c.settings.setBypassDnd(previous.bypassDnd)
        }
    }

    @Test
    fun inAppAlarmRingsWithStopAndSnoozeButEndStaysANotification(): Unit = runBlocking {
        shell("pm grant com.oki android.permission.POST_NOTIFICATIONS")
        val publisher = NotificationPublisher(context)
        val task =
            aiJson.decodeFromString<Task>(
                """{"id":"native-alarm-test","title":"Read","dueAt":1,"alertMode":"ALARM"}"""
            )
        val manager = context.getSystemService(NotificationManager::class.java)
        val c = (context.applicationContext as OkiApplication).container
        c.database.tasks().put(task)
        val settings = Settings(soundMode = SoundMode.SILENT, alarmSound = AlarmSound.ALARM)
        try {
            publisher.publish(task, settings)
            publisher.publish(task, settings, TaskNotificationKind.END)
            val deadline = System.currentTimeMillis() + 5000
            while (
                manager.activeNotifications.count { it.tag.startsWith("task:${task.id}") } < 2 &&
                    System.currentTimeMillis() < deadline
            ) Thread.sleep(50)
            val start =
                manager.activeNotifications
                    .single { it.tag == ChannelIdentity.notificationTag(task.id) }
                    .notification
            assertTrue(start.flags and android.app.Notification.FLAG_INSISTENT != 0)
            assertEquals(300000L, start.timeoutAfter)
            assertEquals(
                listOf("Mark done", "Snooze 5 min", "Stop"),
                start.actions.map { it.title.toString() },
            )
            val channel = manager.getNotificationChannel(start.channelId)
            assertEquals(android.media.AudioAttributes.USAGE_ALARM, channel.audioAttributes.usage)
            assertEquals(android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI, channel.sound)
            assertFalse(channel.canBypassDnd())
            val end =
                manager.activeNotifications
                    .single {
                        it.tag == ChannelIdentity.notificationTag(task.id, TaskNotificationKind.END)
                    }
                    .notification
            assertEquals(0, end.flags and android.app.Notification.FLAG_INSISTENT)
            assertNull(end.fullScreenIntent)
            assertNull(manager.getNotificationChannel(end.channelId).sound)
            start.actions.single { it.title.toString() == "Stop" }.actionIntent.send()
            val stopDeadline = System.currentTimeMillis() + 3000
            while (
                publisher.isAlarmActive(task.id) && System.currentTimeMillis() < stopDeadline
            ) kotlinx.coroutines.delay(50)
            assertFalse(publisher.isAlarmActive(task.id))
            assertTrue(
                manager.activeNotifications.any {
                    it.tag == ChannelIdentity.notificationTag(task.id, TaskNotificationKind.END)
                }
            )
        } finally {
            c.tasks.delete(task.id)
        }
    }

    @Test
    fun inAppAlarmSchedulesThroughAlarmClockAndSnoozesWithoutChangingStart(): Unit = runBlocking {
        shell("pm grant com.oki android.permission.POST_NOTIFICATIONS")
        shell("appops set com.oki SCHEDULE_EXACT_ALARM allow")
        val c = (context.applicationContext as OkiApplication).container
        val due = System.currentTimeMillis() + 2500
        val task =
            Task(
                id = "native-alarm-clock",
                title = "Start reading",
                dueAt = due,
                alertMode = TaskAlertMode.ALARM,
            )
        val manager = context.getSystemService(android.app.AlarmManager::class.java)
        try {
            c.tasks.save(task)
            assertEquals(due, manager.nextAlarmClock.triggerTime)
            val deadline = System.currentTimeMillis() + 12000
            while (
                !c.publisher.isAlarmActive(task.id) && System.currentTimeMillis() < deadline
            ) kotlinx.coroutines.delay(50)
            assertTrue(c.publisher.isAlarmActive(task.id))
            val notification =
                context
                    .getSystemService(NotificationManager::class.java)
                    .activeNotifications
                    .single { it.tag == ChannelIdentity.notificationTag(task.id) }
                    .notification
            notification.actions[1].actionIntent.send()
            val snoozeDeadline = System.currentTimeMillis() + 3000
            while (
                c.publisher.isAlarmActive(task.id) && System.currentTimeMillis() < snoozeDeadline
            ) kotlinx.coroutines.delay(50)
            assertFalse(c.publisher.isAlarmActive(task.id))
            val updated = c.tasks.get(task.id)!!
            assertEquals(TaskAlertMode.ALARM, updated.alertMode)
            assertEquals(due, updated.dueAt)
            assertEquals(updated.scheduledReminderAt, manager.nextAlarmClock.triggerTime)
            assertTrue(updated.scheduledReminderAt!! > System.currentTimeMillis() + 290000)
        } finally {
            c.tasks.delete(task.id)
        }
    }

    @Test
    fun notificationRemindersScheduleThroughAlarmClockSoOemsCannotBatchThem(): Unit = runBlocking {
        // Regression: ColorOS batched setExactAndAllowWhileIdle by up to +2m20s. Notification
        // mode reminders must use setAlarmClock, which the system reports as nextAlarmClock.
        shell("pm grant com.oki android.permission.POST_NOTIFICATIONS")
        shell("appops set com.oki SCHEDULE_EXACT_ALARM allow")
        val c = (context.applicationContext as OkiApplication).container
        val manager = context.getSystemService(android.app.AlarmManager::class.java)
        val previous = manager.nextAlarmClock?.triggerTime
        val now = System.currentTimeMillis()
        // nextAlarmClock is the soonest across all apps, so stay ahead of any existing one.
        val due =
            (now + 180_000).let { preferred ->
                if (previous != null && previous <= preferred) (now + previous) / 2 else preferred
            }
        assertTrue("Another alarm clock is due too soon to test against", due > now + 10_000)
        val task = Task(id = "notification-alarm-clock", title = "On-time reminder", dueAt = due)
        assertEquals(TaskAlertMode.NOTIFICATION, task.alertMode)
        try {
            c.tasks.save(task)
            val next = manager.nextAlarmClock
            assertNotNull(next)
            assertEquals(due, next.triggerTime)
            assertEquals(context.packageName, next.showIntent.creatorPackage)
        } finally {
            c.tasks.delete(task.id)
        }
        val deadline = System.currentTimeMillis() + 3000
        while (
            manager.nextAlarmClock?.triggerTime != previous && System.currentTimeMillis() < deadline
        ) kotlinx.coroutines.delay(50)
        assertEquals(previous, manager.nextAlarmClock?.triggerTime)
    }

    @Test
    fun sameTimeNotificationsCoexistAndRespectChannels() {
        shell("pm grant com.oki android.permission.POST_NOTIFICATIONS")
        val publisher = NotificationPublisher(context)
        val due = System.currentTimeMillis() + 300000
        val first = Task(id = "same-time-Aa", title = "First reminder", dueAt = due)
        val second = first.copy(id = "same-time-BB", title = "Second reminder")
        val silent = Settings(soundMode = SoundMode.SILENT)
        publisher.publish(first, silent)
        publisher.publish(second, silent)
        val manager = context.getSystemService(NotificationManager::class.java)
        val deadline = System.currentTimeMillis() + 5000
        while (
            manager.activeNotifications.count { it.tag.startsWith("task:same-time") } < 2 &&
                System.currentTimeMillis() < deadline
        ) Thread.sleep(50)
        assertEquals(2, manager.activeNotifications.count { it.tag.startsWith("task:same-time") })
        val channel = manager.getNotificationChannel(ChannelIdentity.forSettings(silent))
        assertNull(channel.sound)
        assertFalse(channel.canBypassDnd())
        publisher.dismiss(first.id)
        publisher.dismiss(second.id)
    }

    @Test
    fun aDeniedNotificationsDoNotCrash() {
        val publisher = NotificationPublisher(context)
        assertFalse(publisher.canNotify())
        publisher.publish(Task(id = "denied", title = "No crash", dueAt = 1), Settings())
        shell("pm grant com.oki android.permission.POST_NOTIFICATIONS")
    }

    @Test
    fun aDeniedExactAccessUsesGracefulSchedulingFallback() {
        val scheduler = AndroidReminderScheduler(context, NotificationPublisher(context))
        org.junit.Assume.assumeFalse(
            "Run test-emulator.sh to start with exact access denied",
            scheduler.hasExactAccess(),
        )
        val task =
            Task(
                id = "inexact-test",
                title = "Fallback",
                dueAt = System.currentTimeMillis() + 600000,
                scheduledReminderAt = System.currentTimeMillis() + 300000,
            )
        scheduler.schedule(task)
        scheduler.cancel(task.id)
    }

    @Test
    fun customAudioDurationAndChannelVersioning(): Unit = runBlocking {
        val folder = File(context.filesDir, "sounds").apply { mkdirs() }
        fun wav(seconds: Int): Uri {
            val samples = 8000 * seconds
            val b = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray())
                .putInt(36 + samples * 2)
                .put("WAVEfmt ".toByteArray())
                .putInt(16)
                .putShort(1)
                .putShort(1)
                .putInt(8000)
                .putInt(16000)
                .putShort(2)
                .putShort(16)
                .put("data".toByteArray())
                .putInt(samples * 2)
            val file = File(folder, "test-$seconds.wav").apply { writeBytes(b.array()) }
            return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }
        val sound = SoundStore(context)
        val uri = sound.import(wav(5))
        assertTrue(uri.startsWith("content://"))
        assertTrue(runCatching { sound.import(wav(6)) }.isFailure)
        val publisher = NotificationPublisher(context)
        val settings = Settings(soundMode = SoundMode.CUSTOM, customSoundUri = uri)
        val id = publisher.channel(settings)
        val channel =
            context.getSystemService(NotificationManager::class.java).getNotificationChannel(id)
        assertEquals(Uri.parse(uri), channel.sound)
        assertFalse(channel.canBypassDnd())
        folder.listFiles()?.filter { it.name.startsWith("test-") }?.forEach { it.delete() }
    }

    @Test
    fun alarmsDeliverBothWithoutAnActivity(): Unit = runBlocking {
        shell("pm grant com.oki android.permission.POST_NOTIFICATIONS")
        shell("appops set com.oki SCHEDULE_EXACT_ALARM allow")
        val c = (context.applicationContext as OkiApplication).container
        val due = System.currentTimeMillis() + 2500
        val a =
            Task(id = "alarm-receiver-a", title = "Alarm A", dueAt = due, reminderOffsetMinutes = 0)
        val b = a.copy(id = "alarm-receiver-b", title = "Alarm B")
        c.tasks.save(a)
        c.tasks.save(b)
        val manager = context.getSystemService(NotificationManager::class.java)
        val deadline = System.currentTimeMillis() + 12000
        while (
            manager.activeNotifications.count { it.tag.startsWith("task:alarm-receiver-") } < 2 &&
                System.currentTimeMillis() < deadline
        ) kotlinx.coroutines.delay(100)
        assertEquals(
            2,
            manager.activeNotifications.count { it.tag.startsWith("task:alarm-receiver-") },
        )
        c.tasks.delete(a.id)
        c.tasks.delete(b.id)
    }

    @Test
    fun startAndEndNotificationsDeliverSeparatelyAndUseNormalSoundChannels(): Unit = runBlocking {
        shell("pm grant com.oki android.permission.POST_NOTIFICATIONS")
        shell("appops set com.oki SCHEDULE_EXACT_ALARM allow")
        val c = (context.applicationContext as OkiApplication).container
        val now = System.currentTimeMillis()
        val task =
            Task(
                id = "start-end-delivery",
                title = "Focused reading",
                dueAt = now + 2000,
                scheduledReminderAt = now + 2000,
                scheduledEndReminderAt = now + 3500,
            )
        c.database.tasks().put(task)
        // Recovery follows the same path used after reboot or an app update.
        c.tasks.restore()
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            val deadline = System.currentTimeMillis() + 12000
            while (
                manager.activeNotifications.count { it.tag.startsWith("task:${task.id}") } < 2 &&
                    System.currentTimeMillis() < deadline
            ) kotlinx.coroutines.delay(100)
            val notifications =
                manager.activeNotifications.filter { it.tag.startsWith("task:${task.id}") }
            assertEquals(2, notifications.size)
            val end =
                notifications.single {
                    it.tag == ChannelIdentity.notificationTag(task.id, TaskNotificationKind.END)
                }
            assertEquals(
                "Task time is over: ${task.title}",
                end.notification.extras.getCharSequence("android.title").toString(),
            )
            assertTrue(
                end.notification.extras
                    .getCharSequence("android.text")
                    .toString()
                    .contains(task.title)
            )
            assertNull(end.notification.fullScreenIntent)
            val channel = manager.getNotificationChannel(end.notification.channelId)
            assertEquals(
                android.media.AudioAttributes.USAGE_NOTIFICATION,
                channel.audioAttributes.usage,
            )
            assertFalse(channel.canBypassDnd())
            assertNull(c.tasks.get(task.id)!!.scheduledReminderAt)
            assertNull(c.tasks.get(task.id)!!.scheduledEndReminderAt)
            c.tasks.complete(task.id, true)
            val dismissDeadline = System.currentTimeMillis() + 3000
            while (
                manager.activeNotifications.any { it.tag.startsWith("task:${task.id}") } &&
                    System.currentTimeMillis() < dismissDeadline
            ) kotlinx.coroutines.delay(50)
            assertTrue(manager.activeNotifications.none { it.tag.startsWith("task:${task.id}") })
        } finally {
            c.tasks.delete(task.id)
        }
    }
}
