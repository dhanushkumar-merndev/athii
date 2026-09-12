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
    val database by lazy {
        Room.databaseBuilder(context, OkiDatabase::class.java, "oki.db")
            .addMigrations(
                OkiDatabase.MIGRATION_1_2,
                OkiDatabase.MIGRATION_2_3,
                OkiDatabase.MIGRATION_3_4,
                OkiDatabase.MIGRATION_4_5,
            )
            .build()
    }
    val settings by lazy { SettingsRepository(context) }
    val credentials by lazy { SecureCredentialStore(context) }
    private val embeddedCredentials by lazy { EmbeddedCredentialBootstrap(context, credentials) }
    val publisher by lazy { NotificationPublisher(context) }
    val reminders by lazy { AndroidReminderScheduler(context, publisher) }
    val dailyReset by lazy { DailyDoctorResetScheduler(context) }
    val tasks by lazy { TaskRepository(database.tasks(), reminders) }
    val doctors by lazy { DoctorRepository(database) }
    val sounds by lazy { SoundStore(context) }
    val aiUsage by lazy { AiUsageStore(java.io.File(context.filesDir, "ai-usage.json")) }
    private val http by lazy { AiHttp(aiUsage) }
    val groq by lazy {
        AiRouter(
            GroqChatClient(credentials, http),
            GeminiChatClient(credentials, http),
            credentials::isConfigured,
            aiUsage,
        )
    }
    val gemini by lazy { GeminiVisionClient(credentials, http, usage = aiUsage) }
    val chatHistory by lazy { ChatHistoryRepository(context) }
    val assistant by lazy {
        AssistantRepository(groq, LocalAssistantToolExecutor(tasks, doctors)) {
            settings.settings.first().reasoningEffort
        }
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
            purgeExpiredCompletedTasks()
            tasks.restore()
            dailyReset.scheduleNext()
        }

    /** Applies the chosen retention for completed tasks. Safe to call often; it is one delete. */
    suspend fun purgeExpiredCompletedTasks() {
        tasks.purgeCompleted(settings.settings.first().autoDeleteCompleted.days)
    }

    suspend fun deleteAll() =
        recoveryMutex.withLock {
            tasks.clear()
            doctors.clear()
            chatHistory.clear()
            aiUsage.clear()
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
