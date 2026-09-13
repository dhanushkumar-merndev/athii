package com.oki

import com.oki.core.ai.TaskDraft
import com.oki.core.storage.TaskAlertMode
import com.oki.feature.assistant.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ChatContextAndDraftTest {
    @Test
    fun summarizedChatsStillSendRecentTurnsVerbatim() {
        val messages = (1..10).map { ChatMessage(text = "m$it", user = it % 2 == 1) }
        val conversation =
            ChatConversation(
                title = "Dance",
                messages = messages,
                summary = "User wants five dance tasks.",
                summarizedThrough = messages.last().id,
            )
        // A reply to a clarifying question must still see the original request, not only the
        // summary.
        assertEquals(
            messages.takeLast(RECENT_VERBATIM_MESSAGES).map { it.text },
            summaryContext(conversation).map { it.text },
        )
    }

    @Test
    fun shortSummarizedChatsKeepEveryTurn() {
        val messages = (1..4).map { ChatMessage(text = "m$it", user = it % 2 == 1) }
        val conversation =
            ChatConversation(
                title = "Dance",
                messages = messages,
                summary = "Earlier",
                summarizedThrough = messages.last().id,
            )
        assertEquals(messages.map { it.text }, summaryContext(conversation).map { it.text })
    }

    @Test
    fun alarmDraftWithoutDateSavesTodayAsAnAlarm() {
        val task =
            reviewTask(
                ChatDraft(task = TaskDraft(title = "Dance", time = "23:59", alertMode = "ALARM"))
            )
        assertEquals(TaskAlertMode.ALARM, task.alertMode)
        assertEquals(
            LocalDate.now(),
            Instant.ofEpochMilli(task.dueAt).atZone(ZoneId.systemDefault()).toLocalDate(),
        )
    }

    @Test
    fun unknownAlertModesFallBackToNotification() {
        assertEquals(TaskAlertMode.NOTIFICATION, draftAlertMode(null))
        assertEquals(TaskAlertMode.NOTIFICATION, draftAlertMode("loud"))
        assertEquals(TaskAlertMode.ALARM, draftAlertMode("alarm"))
    }
}
