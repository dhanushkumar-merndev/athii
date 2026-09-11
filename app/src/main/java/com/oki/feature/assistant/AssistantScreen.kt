package com.oki.feature.assistant

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.oki.AppContainer
import com.oki.core.ai.*
import com.oki.core.ui.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val user: Boolean,
    val taskDraft: TaskDraft? = null,
    val doctorDraft: DoctorDraft? = null,
    val draftSaved: Boolean = false,
)

data class ChatConversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val messages: List<ChatMessage> = emptyList(),
)

class AssistantViewModel(private val c: AppContainer) : androidx.lifecycle.ViewModel() {
    private val conversationStore = MutableStateFlow<List<ChatConversation>>(emptyList())
    private val activeConversationId = MutableStateFlow<String?>(null)
    val conversations = conversationStore.asStateFlow()
    val activeId = activeConversationId.asStateFlow()
    val messages =
        combine(conversationStore, activeConversationId) { conversations, activeId ->
                conversations.firstOrNull { it.id == activeId }?.messages.orEmpty()
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val historyVisible = MutableStateFlow(false)
    private var job: Job? = null

    fun ask(question: String) {
        if (question.isBlank() || busy.value) return
        busy.value = true
        error.value = null
        job =
            viewModelScope.launch {
                try {
                    val id = activeConversationId.value ?: createConversation(question)
                    append(id, ChatMessage(text = question, user = true))
                    val answer = c.assistant.ask(question, messagesFor(id))
                    append(
                        id,
                        ChatMessage(
                            text = answer.text,
                            user = false,
                            taskDraft = answer.taskDraft,
                            doctorDraft = answer.doctorDraft,
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error.value = friendlyError(e)
                } finally {
                    busy.value = false
                }
            }
    }

    fun cancel() {
        job?.cancel()
        busy.value = false
    }

    fun markDraftSaved(messageId: String) {
        conversationStore.update { conversations ->
            conversations.map { conversation ->
                conversation.copy(
                    messages =
                        conversation.messages.map { message ->
                            if (message.id == messageId) message.copy(draftSaved = true)
                            else message
                        }
                )
            }
        }
    }

    fun showHistory() {
        historyVisible.value = true
    }

    fun dismissHistory() {
        historyVisible.value = false
    }

    fun newChat() {
        cancel()
        activeConversationId.value = null
        error.value = null
        dismissHistory()
    }

    fun selectConversation(id: String) {
        if (busy.value) return
        activeConversationId.value = id
        error.value = null
        dismissHistory()
    }

    fun clear() {
        cancel()
        conversationStore.value = emptyList()
        activeConversationId.value = null
        error.value = null
    }

    fun restoreHistory(conversations: List<ChatConversation>, activeId: String? = null) {
        conversationStore.value = conversations
        activeConversationId.value = activeId ?: conversations.firstOrNull()?.id
    }

    private fun createConversation(firstQuestion: String): String {
        val conversation =
            ChatConversation(
                title =
                    firstQuestion.trim().replace(Regex("\\s+"), " ").take(44).ifBlank { "New chat" }
            )
        conversationStore.update { listOf(conversation) + it }
        activeConversationId.value = conversation.id
        return conversation.id
    }

    private fun append(conversationId: String, message: ChatMessage) {
        conversationStore.update { conversations ->
            conversations.map { conversation ->
                if (conversation.id == conversationId)
                    conversation.copy(messages = conversation.messages + message)
                else conversation
            }
        }
    }

    private fun messagesFor(conversationId: String): List<ChatMessage> =
        conversationStore.value.firstOrNull { it.id == conversationId }?.messages.orEmpty()
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(
    vm: AssistantViewModel,
    reviewTask: (String, String) -> Unit,
    reviewDoctor: (String, String) -> Unit,
    settings: () -> Unit,
) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val activeId by vm.activeId.collectAsStateWithLifecycle()
    val historyVisible by vm.historyVisible.collectAsStateWithLifecycle()
    var question by rememberSaveable { mutableStateOf("") }
    val scroll = rememberLazyListState()
    LaunchedEffect(messages.size, busy) {
        if (scroll.layoutInfo.totalItemsCount > 0)
            scroll.animateScrollToItem(scroll.layoutInfo.totalItemsCount - 1)
    }
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 22.dp)) {
        LazyColumn(
            Modifier.weight(1f),
            state = scroll,
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(vertical = 18.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    Spacer(Modifier.height(25.dp))
                    EmptyState(
                        Icons.Outlined.AutoAwesome,
                        "What’s on your mind?",
                        "Find a doctor. Plan your day. Ask Athii.",
                    )
                }
                items(
                    listOf(
                        "Which doctors are present today?",
                        "What tasks do I have tomorrow?",
                        "When is my next reminder?",
                    )
                ) { prompt ->
                    OutlinedCard(
                        onClick = { vm.ask(prompt) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text(
                            prompt,
                            Modifier.padding(18.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                item {
                    Text(
                        "Questions and matching local records are sent to Groq. Each chat keeps its own context while Athii is open.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items(messages, key = { it.id }) { message ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.user) Arrangement.End else Arrangement.Start,
                ) {
                    Surface(
                        color =
                            if (message.user) MaterialTheme.colorScheme.surfaceContainerHigh
                            else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.widthIn(max = 340.dp),
                    ) {
                        Column(
                            Modifier.padding(if (message.user) 18.dp else 4.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (!message.user)
                                Text(
                                    "Athii",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            FormattedAssistantText(message.text)
                            message.taskDraft?.let { draft ->
                                if (message.draftSaved) DraftSavedLabel("Task created")
                                else
                                    FilledTonalButton(
                                        onClick = {
                                            reviewTask(message.id, aiJson.encodeToString(draft))
                                        }
                                    ) {
                                        Text("Review task")
                                    }
                            }
                            message.doctorDraft?.let { draft ->
                                if (message.draftSaved) DraftSavedLabel("Doctor added")
                                else
                                    FilledTonalButton(
                                        onClick = {
                                            reviewDoctor(message.id, aiJson.encodeToString(draft))
                                        }
                                    ) {
                                        Text("Review doctor")
                                    }
                            }
                        }
                    }
                }
            }
            if (busy)
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Loader2Circle(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            "Checking your local information…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = vm::cancel) { Text("Stop") }
                }
            item {
                ErrorBanner(error)
                if (error != null) TextButton(onClick = settings) { Text("AI settings") }
            }
        }
        OutlinedTextField(
            question,
            { question = it.take(4000) },
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            placeholder = { Text("Ask anything about your day") },
            shape = RoundedCornerShape(28.dp),
            maxLines = 5,
            trailingIcon = {
                FilledIconButton(
                    onClick = {
                        if (busy) vm.cancel()
                        else {
                            vm.ask(question)
                            question = ""
                        }
                    },
                    enabled = busy || question.isNotBlank(),
                    shape = CircleShape,
                    modifier = Modifier.padding(end = 6.dp).size(44.dp),
                    colors =
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                ) {
                    Icon(
                        if (busy) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward,
                        if (busy) "Stop response" else "Send message",
                        Modifier.size(23.dp),
                    )
                }
            },
        )
        Text(
            "Athii can make mistakes. Check important details.",
            Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (historyVisible)
        ModalBottomSheet(
            onDismissRequest = vm::dismissHistory,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 22.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Chat history", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = vm::newChat) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("New chat")
                    }
                }
                if (conversations.isEmpty())
                    Text(
                        "Your conversations will appear here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                else
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(conversations, key = { it.id }) { conversation ->
                            Surface(
                                color =
                                    if (conversation.id == activeId)
                                        MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainer,
                                shape = RoundedCornerShape(16.dp),
                                modifier =
                                    Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable {
                                        vm.selectConversation(conversation.id)
                                    },
                            ) {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                    Text(
                                        conversation.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        "${conversation.messages.size} messages",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
            }
        }
}

@Composable
private fun DraftSavedLabel(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.Outlined.CheckCircle,
            null,
            Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.secondary,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun FormattedAssistantText(text: String) {
    val formatted = remember(text) { text.toAssistantAnnotatedString() }
    Text(formatted, style = MaterialTheme.typography.bodyLarge)
}

private fun String.toAssistantAnnotatedString(): AnnotatedString = buildAnnotatedString {
    lines().forEachIndexed { index, rawLine ->
        val line = rawLine.trimStart()
        when {
            line.startsWith("### ") ->
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    appendInlineMarkdown(line.removePrefix("### "))
                }
            line.startsWith("## ") ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    appendInlineMarkdown(line.removePrefix("## "))
                }
            line.startsWith("# ") ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    appendInlineMarkdown(line.removePrefix("# "))
                }
            line.startsWith("- ") || line.startsWith("* ") -> {
                append("• ")
                appendInlineMarkdown(line.drop(2))
            }
            else -> appendInlineMarkdown(rawLine)
        }
        if (index < lines().lastIndex) append('\n')
    }
}

private fun AnnotatedString.Builder.appendInlineMarkdown(value: String) {
    var index = 0
    while (index < value.length) {
        when {
            value.startsWith("**", index) -> {
                val end = value.indexOf("**", index + 2)
                if (end > index + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(value.substring(index + 2, end))
                    }
                    index = end + 2
                } else {
                    append(value[index++])
                }
            }
            value[index] == '`' -> {
                val end = value.indexOf('`', index + 1)
                if (end > index + 1) {
                    withStyle(
                        SpanStyle(fontFamily = FontFamily.Monospace, color = Color(0xFF9EDBB1))
                    ) {
                        append(value.substring(index + 1, end))
                    }
                    index = end + 1
                } else {
                    append(value[index++])
                }
            }
            else -> append(value[index++])
        }
    }
}
