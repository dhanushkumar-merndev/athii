package com.oki.feature.assistant

import android.content.Context
import com.oki.core.ai.DoctorDraft
import com.oki.core.ai.ModelIdentity
import com.oki.core.ai.TaskDraft
import com.oki.core.ai.aiJson
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class ChatDraft(
    val id: String = UUID.randomUUID().toString(),
    val task: TaskDraft? = null,
    val doctor: DoctorDraft? = null,
    val saved: Boolean = false,
    val reminderEnabled: Boolean = true,
) {
    val title: String
        get() = task?.title ?: doctor?.doctorName ?: "Details to review"
}

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val user: Boolean,
    val drafts: List<ChatDraft> = emptyList(),
    // Keep older single-draft histories readable.
    val taskDraft: TaskDraft? = null,
    val doctorDraft: DoctorDraft? = null,
    val draftSaved: Boolean = false,
    val models: List<ModelIdentity> = emptyList(),
) {
    val reviewDrafts: List<ChatDraft>
        get() =
            drafts.ifEmpty {
                buildList {
                    taskDraft?.let { add(ChatDraft(id, task = it, saved = draftSaved)) }
                    doctorDraft?.let {
                        add(
                            ChatDraft(
                                if (taskDraft == null) id else "$id:doctor",
                                doctor = it,
                                saved = draftSaved,
                            )
                        )
                    }
                }
            }
}

@Serializable
data class ChatConversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val messages: List<ChatMessage> = emptyList(),
    val summary: String = "",
    val summarizedThrough: String? = null,
    // Null for legacy chats: their original activity time was never recorded.
    val updatedAt: Long? = null,
)

/**
 * Turns not yet covered by the stored summary, and always the most recent turns verbatim: the
 * summary compresses older turns, but a reply to a clarifying question must keep the original
 * request (item counts, dates, alert modes).
 */
internal fun summaryContext(conversation: ChatConversation): List<ChatMessage> {
    val covered = conversation.messages.indexOfFirst { it.id == conversation.summarizedThrough }
    return if (covered < 0 || conversation.summary.isBlank()) conversation.messages
    else
        conversation.messages.drop(
            minOf(
                (covered - 1).coerceAtLeast(0),
                (conversation.messages.size - RECENT_VERBATIM_MESSAGES).coerceAtLeast(0),
            )
        )
}

internal const val RECENT_VERBATIM_MESSAGES = 8

/**
 * Local history has one writer. Replacing an entire snapshot keeps each draft's saved state
 * together.
 */
class ChatHistoryRepository(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "chat-history.json"))

    private val mutex = Mutex()

    suspend fun read(): List<ChatConversation> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!file.exists()) emptyList()
                else aiJson.decodeFromString<List<ChatConversation>>(file.readText())
            }
        }

    suspend fun write(conversations: List<ChatConversation>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                file.parentFile?.mkdirs()
                val pending = File(file.parentFile, "${file.name}.pending")
                try {
                    FileOutputStream(pending).use { stream ->
                        stream.write(
                            aiJson.encodeToString(conversations).toByteArray(Charsets.UTF_8)
                        )
                        stream.fd.sync()
                    }
                    if (!pending.renameTo(file)) throw IOException("Could not save chat history.")
                } finally {
                    pending.delete()
                }
            }
        }

    suspend fun clear() = write(emptyList())
}
