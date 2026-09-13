package com.oki.feature.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.oki.AppContainer
import com.oki.core.ai.*
import com.oki.core.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString

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
    val historyReady = MutableStateFlow(false)
    val usage = c.aiUsage.usage
    val activeModel = MutableStateFlow<String?>(null)
    val compacting = MutableStateFlow(false)
    val savingDrafts = MutableStateFlow<Set<String>>(emptySet())
    val draftErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    private val draftSaver = ChatDraftSaver(c.tasks, c.doctors)
    private var summaryJob: Job? = null
    private var job: Job? = null
    private var requestVersion = 0L
    private var locallyChanged = false

    init {
        viewModelScope.launch {
            var historyReadFailed = false
            try {
                val saved = c.chatHistory.read()
                if (!locallyChanged) conversationStore.value = saved
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error.value =
                    "Could not open saved chats. Your tasks and doctors are still available."
                historyReadFailed = true
            }
            historyReady.value = true
            conversationStore.collect { snapshot ->
                // Preserve an unreadable file until the user starts or changes a conversation,
                // then keep persisting new chats instead of disabling history for this session.
                if (historyReadFailed && !locallyChanged) return@collect
                try {
                    c.chatHistory.write(snapshot)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    error.value = "Could not save chat history on this device."
                }
            }
        }
    }

    fun ask(question: String) {
        if (question.isBlank() || busy.value || !historyReady.value) return
        busy.value = true
        error.value = null
        val version = ++requestVersion
        summaryJob?.cancel()
        compacting.value = false
        locallyChanged = true
        job =
            viewModelScope.launch {
                try {
                    val id = activeConversationId.value ?: createConversation(question)
                    val previous = conversationStore.value.first { it.id == id }
                    append(id, ChatMessage(text = question, user = true))
                    val context = summaryContext(previous)
                    val answer =
                        c.assistant.ask(question, context, previous.summary) { model ->
                            if (version == requestVersion) activeModel.value = model.label
                        }
                    currentCoroutineContext().ensureActive()
                    val reply =
                        ChatMessage(
                            text = answer.text,
                            user = false,
                            drafts = answer.reviewDrafts,
                            models = answer.models,
                        )
                    append(id, reply)
                    summaryJob =
                        viewModelScope.launch {
                            compacting.value = true
                            try {
                                val memory =
                                    c.assistant.summarize(
                                        previous.summary,
                                        question,
                                        answer,
                                        context,
                                    )
                                if (!memory.isNullOrBlank())
                                    conversationStore.update { chats ->
                                        chats.map {
                                            if (it.id == id)
                                                it.copy(
                                                    summary = memory,
                                                    summarizedThrough = reply.id,
                                                )
                                            else it
                                        }
                                    }
                            } finally {
                                compacting.value = false
                            }
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (version == requestVersion) error.value = friendlyError(e)
                } finally {
                    if (version == requestVersion) {
                        busy.value = false
                        activeModel.value = null
                    }
                }
            }
    }

    fun cancel() {
        requestVersion++
        job?.cancel()
        job = null
        summaryJob?.cancel()
        compacting.value = false
        activeModel.value = null
        busy.value = false
    }

    fun markDraftSaved(actionId: String) {
        locallyChanged = true
        conversationStore.update { conversations ->
            conversations.map { conversation ->
                conversation.copy(
                    messages =
                        conversation.messages.map { message ->
                            message.copy(
                                drafts =
                                    message.reviewDrafts.map { draft ->
                                        if (draft.id == actionId) draft.copy(saved = true)
                                        else draft
                                    },
                                draftSaved = message.draftSaved || message.id == actionId,
                            )
                        }
                )
            }
        }
    }

    fun changeDraft(updated: ChatDraft) {
        locallyChanged = true
        draftErrors.update { it - updated.id }
        conversationStore.update { chats ->
            chats.map { chat ->
                chat.copy(
                    messages =
                        chat.messages.map { message ->
                            message.copy(
                                drafts =
                                    message.reviewDrafts.map { draft ->
                                        if (
                                            draft.id == updated.id &&
                                                !draft.saved &&
                                                draft.id !in savingDrafts.value
                                        )
                                            updated.copy(saved = false)
                                        else draft
                                    }
                            )
                        }
                )
            }
        }
    }

    fun saveDraft(id: String) {
        if (id in savingDrafts.value) return
        val draft =
            conversationStore.value
                .flatMap { it.messages }
                .flatMap { it.reviewDrafts }
                .firstOrNull { it.id == id } ?: return
        if (draft.saved) return
        savingDrafts.update { it + id }
        draftErrors.update { it - id }
        viewModelScope.launch {
            try {
                draftSaver.save(draft)
                markDraftSaved(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                draftErrors.update {
                    it + (id to (e.message?.take(220) ?: "Could not save this item."))
                }
            } finally {
                savingDrafts.update { it - id }
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
        if (conversationStore.value.none { it.id == id }) return
        cancel()
        activeConversationId.value = id
        error.value = null
        dismissHistory()
    }

    fun clear() {
        cancel()
        locallyChanged = true
        conversationStore.value = emptyList()
        activeConversationId.value = null
        error.value = null
    }

    fun restoreHistory(conversations: List<ChatConversation>, activeId: String? = null) {
        locallyChanged = true
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
                    conversation.copy(
                        messages = conversation.messages + message,
                        updatedAt = System.currentTimeMillis(),
                    )
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
    val historyReady by vm.historyReady.collectAsStateWithLifecycle()
    val modelLabel by vm.activeModel.collectAsStateWithLifecycle()
    val compacting by vm.compacting.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    var usageVisible by rememberSaveable { mutableStateOf(false) }
    var reviewMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    var question by rememberSaveable(activeId) { mutableStateOf("") }
    val scroll = rememberLazyListState()
    LaunchedEffect(activeId) { if (messages.isNotEmpty()) scroll.scrollToItem(messages.lastIndex) }
    LaunchedEffect(activeId, messages.size, busy) {
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
                        "Questions, compact conversation context and matching records go to the responding provider. Chat history stays on this device.",
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
                                    message.models
                                        .takeIf { it.isNotEmpty() }
                                        ?.joinToString("\n") { it.label } ?: "Athii",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            FormattedAssistantText(message.text)
                            if (message.reviewDrafts.size > 1) {
                                FilledTonalButton(
                                    onClick = { reviewMessageId = message.id },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Outlined.FactCheck, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Review all ${message.reviewDrafts.size} items · ${message.reviewDrafts.count { it.saved }} saved"
                                    )
                                }
                            } else
                                message.reviewDrafts.forEachIndexed { index, draft ->
                                    key(draft.id) {
                                        DraftPreview(
                                            draft = draft,
                                            number =
                                                if (message.reviewDrafts.size > 1) index + 1
                                                else null,
                                            reviewTask = reviewTask,
                                            reviewDoctor = reviewDoctor,
                                        )
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
                            modelLabel?.let { "Thinking · $it" } ?: "Thinking…",
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { usageVisible = true }) {
                        Icon(Icons.Outlined.DataUsage, "Model usage and limits")
                    }
                    FilledIconButton(
                        onClick = {
                            if (busy) vm.cancel()
                            else {
                                vm.ask(question)
                                question = ""
                            }
                        },
                        enabled = historyReady && (busy || question.isNotBlank()),
                        shape = CircleShape,
                        modifier = Modifier.padding(end = 6.dp).size(44.dp),
                        colors =
                            IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                disabledContainerColor =
                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                    ) {
                        Icon(
                            if (busy) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward,
                            if (busy) "Stop response" else "Send message",
                            Modifier.size(23.dp),
                        )
                    }
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
    if (usageVisible)
        ModelUsageSheet(
            usage,
            conversations.firstOrNull { it.id == activeId }?.summary.orEmpty(),
            compacting,
        ) {
            usageVisible = false
        }
    reviewMessageId?.let { id ->
        val message = messages.firstOrNull { it.id == id }
        if (message != null) BatchReviewSheet(vm, message) { reviewMessageId = null }
    }
    if (historyVisible) {
        var historyNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val timestamps = remember(conversations) { conversations.mapNotNull { it.updatedAt } }
        LaunchedEffect(lifecycle, timestamps) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    historyNow = System.currentTimeMillis()
                    delay(nextChatAgeUpdate(timestamps, historyNow))
                }
            }
        }
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
                                        listOfNotNull(
                                                "${conversation.messages.size} messages",
                                                chatAge(conversation.updatedAt, historyNow),
                                            )
                                            .joinToString(" · "),
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
}

@Composable
private fun DraftPreview(
    draft: ChatDraft,
    number: Int?,
    reviewTask: (String, String) -> Unit,
    reviewDoctor: (String, String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                (number?.let { "$it. " } ?: "") + draft.title,
                style = MaterialTheme.typography.titleSmall,
            )
            val details =
                draft.task?.let {
                    listOfNotNull(
                            it.date,
                            it.startTime ?: it.time,
                            it.endTime?.let { end -> "Until $end" },
                        )
                        .joinToString(" · ")
                }
                    ?: draft.doctor
                        ?.let {
                            listOfNotNull(it.department, it.hospitalOrClinic)
                                .filter(String::isNotBlank)
                                .joinToString(" · ")
                        }
                        .orEmpty()
            if (details.isNotBlank())
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            if (draft.saved)
                DraftSavedLabel(if (draft.task != null) "Task created" else "Doctor added")
            else
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        draft.task?.let { reviewTask(draft.id, aiJson.encodeToString(it)) }
                        draft.doctor?.let { reviewDoctor(draft.id, aiJson.encodeToString(it)) }
                    },
                ) {
                    Text(if (draft.task != null) "Review task" else "Review doctor")
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
internal fun FormattedAssistantText(text: String) {
    val blocks = remember(text) { assistantBlocks(text) }
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is AssistantBlock.Prose ->
                        Text(
                            remember(block.text) { block.text.toAssistantAnnotatedString() },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    is AssistantBlock.Table -> AssistantTable(block)
                }
            }
        }
    }
}

@Composable
private fun AssistantTable(table: AssistantBlock.Table) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widths =
            remember(table, maxWidth) {
                val natural =
                    table.headers.indices.map { column ->
                        val longest =
                            (listOf(table.headers[column]) + table.rows.map { it[column] }).maxOf {
                                it.length
                            }
                        (longest * 7 + 28).coerceIn(120, 240).dp
                    }
                val extra =
                    ((maxWidth - natural.fold(0.dp) { sum, width -> sum + width }) / natural.size)
                        .coerceAtLeast(0.dp)
                natural.map { it + extra }
            }
        Surface(
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier.horizontalScroll(rememberScrollState())
                    .width(widths.fold(0.dp) { sum, width -> sum + width })
            ) {
                (listOf(table.headers) + table.rows).forEachIndexed { rowIndex, cells ->
                    if (rowIndex > 0) HorizontalDivider()
                    Surface(
                        color =
                            if (rowIndex == 0) MaterialTheme.colorScheme.surfaceContainerHigh
                            else MaterialTheme.colorScheme.surface
                    ) {
                        Row {
                            cells.forEachIndexed { column, value ->
                                Text(
                                    remember(value) { value.toAssistantAnnotatedString() },
                                    modifier =
                                        Modifier.width(widths[column])
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight =
                                        if (rowIndex == 0) FontWeight.SemiBold
                                        else FontWeight.Normal,
                                    textAlign =
                                        when (table.alignments[column]) {
                                            CellAlignment.LEFT -> TextAlign.Start
                                            CellAlignment.CENTER -> TextAlign.Center
                                            CellAlignment.RIGHT -> TextAlign.End
                                        },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
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
