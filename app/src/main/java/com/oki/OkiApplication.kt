package com.oki

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.oki.core.ai.*
import com.oki.core.notifications.*
import com.oki.core.security.*
import com.oki.core.storage.*
import com.oki.feature.assistant.*
import com.oki.feature.doctors.*
import com.oki.feature.tasks.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AppContainer(context: Context) {
    val database =
        Room.databaseBuilder(context, OkiDatabase::class.java, "oki.db")
            .addMigrations(OkiDatabase.MIGRATION_1_2)
            .build()
    val settings = SettingsRepository(context)
    val credentials = SecureCredentialStore(context)
    private val embeddedCredentials = EmbeddedCredentialBootstrap(context, credentials)
    val publisher = NotificationPublisher(context)
    val reminders = AndroidReminderScheduler(context, publisher)
    val dailyReset = DailyDoctorResetScheduler(context)
    val tasks = TaskRepository(database.tasks(), reminders)
    val doctors = DoctorRepository(database)
    val sounds = SoundStore(context)
    private val http = AiHttp()
    val groq = AiRouter(GroqChatClient(credentials, http))
    val gemini = GeminiVisionClient(credentials, http)
    val assistant =
        AssistantRepository(groq, LocalAssistantToolExecutor(tasks, doctors)) {
            settings.settings.first().reasoningEffort
        }
    private val recoveryMutex = Mutex()

    suspend fun recover() =
        recoveryMutex.withLock {
            // A damaged optional bootstrap must never stop offline recovery.
            try {
                embeddedCredentials.applyIfAvailable()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                /* Settings provides manual key recovery. */
            }
            doctors.ensureToday()
            tasks.restore()
            dailyReset.scheduleNext()
        }

    suspend fun deleteAll() =
        recoveryMutex.withLock {
            tasks.clear()
            doctors.clear()
            credentials.clear()
            settings.clear()
            publisher.clear()
            sounds.clear()
        }
}

class OkiApplication : Application() {
    val scope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.IO +
                CoroutineExceptionHandler { _, _
                    -> /* No secrets or user data logged. Recovery retries on next foreground/boot. */
                }
        )
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        scope.launch { container.recover() }
    }
}
