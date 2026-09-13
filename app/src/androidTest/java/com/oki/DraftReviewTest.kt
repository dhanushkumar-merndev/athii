package com.oki

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.oki.core.ai.DoctorDraft
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
    fun batchReviewShowsFullEditorForEveryItemAndEditsOnlyThatItem() {
        val vm = AssistantViewModel(c)
        val message =
            ChatMessage(
                id = "batch-message",
                text = "Review these.",
                user = false,
                drafts =
                    listOf(
                        ChatDraft(
                            id = "batch-task-1",
                            task =
                                TaskDraft(
                                    title = "Batch task one",
                                    date = "2099-09-11",
                                    time = "17:30",
                                    startTime = "17:30",
                                ),
                        ),
                        ChatDraft(
                            id = "batch-task-2",
                            task =
                                TaskDraft(
                                    title = "Batch task two",
                                    date = "2020-01-01",
                                    time = "09:00",
                                    startTime = "09:00",
                                ),
                        ),
                        ChatDraft(
                            id = "batch-task-undated",
                            task = TaskDraft(title = "Batch task undated", date = null),
                        ),
                        ChatDraft(
                            id = "batch-doctor",
                            doctor = DoctorDraft(doctorName = "Dr Batch Fixture"),
                        ),
                    ),
            )
        vm.restoreHistory(
            listOf(
                ChatConversation(
                    id = "batch-conversation",
                    title = "Batch",
                    messages = listOf(message),
                )
            ),
            activeId = "batch-conversation",
        )
        rule.setContent {
            val messages by vm.messages.collectAsState()
            OkiTheme(Appearance.DARK) {
                messages.firstOrNull { it.id == message.id }?.let { BatchReviewSheet(vm, it) {} }
            }
        }
        rule.waitUntil(10000) {
            rule.onAllNodesWithTag("batch-review-list").fetchSemanticsNodes().isNotEmpty()
        }
        val list = rule.onNodeWithTag("batch-review-list")
        fun inItem(id: String, matcher: SemanticsMatcher) =
            matcher and hasAnyAncestor(hasTestTag("batch-review-item-$id"))
        // Scroll to the item's key first so the card is placed, then scroll by the node's own
        // bounds: cards are taller than the viewport, and performScrollToNode does not scroll
        // to a node that is already composed.
        fun assertInItem(id: String, matcher: SemanticsMatcher) {
            list.performScrollToKey(id)
            rule.onNode(inItem(id, matcher)).performScrollTo().assertIsDisplayed()
        }
        for (id in listOf("batch-task-1", "batch-task-2")) {
            listOf(
                    "Task title *",
                    "Notes",
                    "Pick date",
                    "Pick start",
                    "Pick end",
                    "Task alert",
                    "Notification",
                    "Alarm",
                    "Remind me",
                    "At time",
                    "Custom",
                    "Save task",
                )
                .forEach { assertInItem(id, hasText(it)) }
        }
        val pastWarning = "This start time has passed. Choose a later time or turn off the alert."
        assertInItem("batch-task-2", hasText(pastWarning))
        assertInItem("batch-task-1", hasText("Pick start"))
        rule.onNode(inItem("batch-task-1", hasText(pastWarning))).assertDoesNotExist()
        // A task mentioned without a date means today, matching the saver's default.
        assertInItem("batch-task-undated", hasText(java.time.LocalDate.now().toString()))
        listOf(
                "Doctor name *",
                "Qualification",
                "Department",
                "Room / OPD number",
                "From · HH:mm",
                "Until · HH:mm",
                "Working days",
                "MON",
                "SUN",
                "Hospital / clinic",
                "Phone (optional)",
                "Save doctor",
            )
            .forEach { assertInItem("batch-doctor", hasText(it)) }

        val alarm = inItem("batch-task-1", hasText("Alarm"))
        list.performScrollToKey("batch-task-1")
        rule.onNode(alarm).performScrollTo().assertIsDisplayed().performClick()
        fun draft(id: String) =
            vm.conversations.value
                .flatMap { it.messages }
                .flatMap { it.reviewDrafts }
                .single { it.id == id }
        rule.waitUntil(5000) { draft("batch-task-1").task?.alertMode == "ALARM" }
        assertNotEquals("ALARM", draft("batch-task-2").task?.alertMode)
        assertEquals("Batch task one", draft("batch-task-1").task?.title)
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
        // Replace rather than append: a scanned task without a date now pre-fills today.
        rule.onNodeWithText("Date · YYYY-MM-DD *").performTextReplacement("2099-09-11")
        rule.onNodeWithText("Start time · HH:mm *").performTextReplacement("17:30")
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
