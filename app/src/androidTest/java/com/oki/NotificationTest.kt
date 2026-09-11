package com.oki

import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oki.core.notifications.*
import com.oki.core.storage.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
class NotificationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
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
            return FileProvider.getUriForFile(context, "com.oki.files", file)
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
}
