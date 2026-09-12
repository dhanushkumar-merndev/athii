package com.oki

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.oki.core.ai.TaskDraft
import com.oki.core.storage.*
import com.oki.core.ui.OkiTheme
import com.oki.feature.assistant.*
import com.oki.feature.doctors.*
import com.oki.feature.tasks.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DraftReviewTest {
    @get:Rule val rule = createComposeRule()
    private val c
        get() = ApplicationProvider.getApplicationContext<OkiApplication>().container

    @Test
    fun scannedDoctorOnlyPersistsAfterReviewSave() {
        val draft =
            """{"doctorName":"Dr Scanned Fixture","department":"Skin","qualification":null,"workingDays":[]}"""
        var saved = false
        rule.setContent {
            val vm =
                androidx.compose.runtime.remember {
                    DoctorEditorViewModel(c, SavedStateHandle(), null, draft)
                }
            OkiTheme(Appearance.DARK) { DoctorEditorScreen(vm, false) { saved = true } }
        }
        rule.waitUntil(10000) {
            rule.onAllNodesWithText("Dr Scanned Fixture").fetchSemanticsNodes().isNotEmpty()
        }
        runBlocking { assertTrue(c.doctors.search("Scanned Fixture").isEmpty()) }
        assertFalse(saved)
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Save Doctor"))
        rule.onNodeWithText("Save Doctor").performClick()
        rule.waitUntil(10000) { saved }
        runBlocking {
            val doctor = c.doctors.search("Scanned Fixture").single()
            assertEquals(Attendance.PRESENT, doctor.attendanceStatus)
            assertEquals("", doctor.qualification)
            assertTrue(doctor.workingDays.isEmpty())
            c.doctors.delete(doctor.id)
        }
    }

    @Test
    fun savedChatDraftReplacesReviewActionWithCompletedLabel() {
        val vm = AssistantViewModel(c)
        val message =
            ChatMessage(
                id = "saved-draft",
                text = "Review this task.",
                user = false,
                taskDraft = TaskDraft(title = "One-time task"),
            )
        vm.restoreHistory(
            listOf(
                ChatConversation(
                    id = "draft-conversation",
                    title = "Draft",
                    messages = listOf(message),
                )
            )
        )
        rule.setContent {
            OkiTheme(Appearance.DARK) { AssistantScreen(vm, { _, _ -> }, { _, _ -> }, {}) }
        }
        rule.onNodeWithText("Review task").assertExists()
        vm.markDraftSaved(message.id)
        rule.waitUntil(5000) {
            rule.onAllNodesWithText("Task created").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Review task").assertDoesNotExist()
    }

    @Test
    fun chatHistorySwitchesContextAndStartsANewChat() {
        val vm = AssistantViewModel(c)
        vm.restoreHistory(
            listOf(
                ChatConversation(
                    id = "first",
                    title = "First chat",
                    messages = listOf(ChatMessage(text = "First reply", user = false)),
                ),
                ChatConversation(
                    id = "second",
                    title = "Second chat",
                    messages = listOf(ChatMessage(text = "Second reply", user = false)),
                ),
            ),
            activeId = "first",
        )
        rule.setContent {
            OkiTheme(Appearance.DARK) { AssistantScreen(vm, { _, _ -> }, { _, _ -> }, {}) }
        }
        rule.onNodeWithText("First reply").assertExists()
        vm.showHistory()
        rule.onNodeWithText("Chat history").assertExists()
        rule.onNodeWithText("Second chat").performClick()
        rule.onNodeWithText("Second reply").assertExists()
        rule.onNodeWithText("First reply").assertDoesNotExist()
        vm.showHistory()
        rule.onNodeWithText("New chat").performClick()
        rule.onNodeWithText("What’s on your mind?").assertExists()
    }

    @Test
    fun scannedTaskMissingDateRequiresReviewAndCannotSave() {
        val draft = """{"title":"Scanned task fixture","date":null,"time":null}"""
        var saved = false
        rule.setContent {
            val vm =
                androidx.compose.runtime.remember {
                    TaskEditorViewModel(c, SavedStateHandle(), null, draft)
                }
            OkiTheme(Appearance.DARK) {
                TaskEditorScreen(vm, { saved = true }, { true }, { true }, {})
            }
        }
        rule.waitUntil(10000) {
            rule.onAllNodesWithText("Scanned task fixture").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Save Task"))
        rule.onNodeWithText("Save Task").performClick()
        rule.waitForIdle()
        assertFalse(saved)
        runBlocking { assertTrue(c.tasks.search("Scanned task fixture").isEmpty()) }
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Date · YYYY-MM-DD *"))
        rule.onNodeWithText("Date · YYYY-MM-DD *").performTextInput("2099-09-11")
        rule.onNodeWithText("Start time · HH:mm *").performTextInput("17:30")
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Save Task"))
        rule.onNodeWithText("Save Task").performClick()
        rule.waitUntil(10000) { saved }
        runBlocking {
            val task = c.tasks.search("Scanned task fixture").single()
            assertEquals(Source.IMAGE_SCAN, task.source)
            c.tasks.delete(task.id)
        }
    }
}
