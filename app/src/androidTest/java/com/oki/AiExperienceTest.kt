package com.oki

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.oki.core.ai.*
import com.oki.core.storage.Appearance
import com.oki.core.ui.OkiTheme
import com.oki.feature.assistant.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AiExperienceTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun mixedBatchEditsAndSavesEachItemWithoutSavingItsSibling() {
        val c = ApplicationProvider.getApplicationContext<OkiApplication>().container
        val original = runBlocking { c.chatHistory.read() }
        val store = ViewModelStore()
        val taskId = UUID.randomUUID().toString()
        val doctorId = UUID.randomUUID().toString()
        lateinit var vm: AssistantViewModel
        var keyboard: androidx.compose.ui.platform.SoftwareKeyboardController? = null
        try {
            rule.runOnIdle {
                vm = AssistantViewModel(c)
                store.put("chat", vm)
            }
            rule.waitUntil(5000) { vm.historyReady.value }
            rule.runOnIdle {
                vm.restoreHistory(
                    listOf(
                        ChatConversation(
                            title = "Review fixture",
                            messages =
                                listOf(
                                    ChatMessage(
                                        text = "Two drafts",
                                        user = false,
                                        models = listOf(CHAT_MODELS.last()),
                                        drafts =
                                            listOf(
                                                ChatDraft(
                                                    taskId,
                                                    task =
                                                        TaskDraft(
                                                            title = "Read fixture",
                                                            date = "2099-01-01",
                                                            time = "12:00",
                                                        ),
                                                    reminderEnabled = false,
                                                ),
                                                ChatDraft(
                                                    doctorId,
                                                    doctor = DoctorDraft(doctorName = "Dr Fixture"),
                                                ),
                                            ),
                                    )
                                ),
                        )
                    )
                )
            }
            rule.setContent {
                keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                OkiTheme(Appearance.DARK) { AssistantScreen(vm, { _, _ -> }, { _, _ -> }, {}) }
            }
            rule.waitUntil(5000) {
                rule
                    .onAllNodesWithText("Gemini · $GEMINI_FALLBACK")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            rule.onNodeWithText("Gemini · $GEMINI_FALLBACK").assertExists()
            rule.onNodeWithText("Review all 2 items · 0 saved").performClick()
            // Batch cards use the same fields as the single task editor.
            rule.onNodeWithText("Task title *").performTextReplacement("Edited fixture task")
            rule.runOnIdle { keyboard?.hide() }
            rule.onNodeWithTag("batch-review-list").performScrollToNode(hasText("Save task"))
            rule.onNodeWithText("Save task").performScrollTo().assertIsDisplayed()
            capture()
            runBlocking {
                assertNull(c.tasks.get(taskId))
                assertNull(c.doctors.get(doctorId))
            }
            rule.onNodeWithText("Save task").performClick()
            try {
                rule.waitUntil(8000) {
                    runBlocking { c.tasks.get(taskId) != null } || vm.draftErrors.value.isNotEmpty()
                }
            } catch (e: AssertionError) {
                capture()
                fail(
                    "Task click state: saving=${vm.savingDrafts.value}, errors=${vm.draftErrors.value}, drafts=${vm.messages.value.last().reviewDrafts}"
                )
            }
            if (vm.draftErrors.value.isNotEmpty()) capture()
            assertTrue("Save errors: ${vm.draftErrors.value}", vm.draftErrors.value.isEmpty())
            runBlocking {
                assertEquals("Edited fixture task", c.tasks.get(taskId)!!.title)
                assertNull(c.doctors.get(doctorId))
            }
            rule.onNodeWithTag("batch-review-list").performScrollToNode(hasText("Save doctor"))
            rule.onNodeWithText("Save doctor").performScrollTo().assertIsDisplayed()
            rule.waitForIdle()
            val screenshot =
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .uiAutomation
                    .takeScreenshot()
            val context = ApplicationProvider.getApplicationContext<OkiApplication>()
            java.io
                .File(context.getExternalFilesDir(null), "batch-review-check.png")
                .outputStream()
                .use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            rule.onNodeWithText("Save doctor").performClick()
            try {
                rule.waitUntil(8000) {
                    runBlocking { c.doctors.get(doctorId) != null } ||
                        vm.draftErrors.value.isNotEmpty()
                }
            } catch (e: AssertionError) {
                fail(
                    "Doctor click state: saving=${vm.savingDrafts.value}, errors=${vm.draftErrors.value}, drafts=${vm.messages.value.last().reviewDrafts}"
                )
            }
            assertTrue("Save errors: ${vm.draftErrors.value}", vm.draftErrors.value.isEmpty())
            rule.onNodeWithText("Done").performClick()
            rule.onNodeWithText("Review all 2 items · 2 saved").assertExists()
        } finally {
            rule.runOnIdle { store.clear() }
            runBlocking {
                c.tasks.delete(taskId)
                c.doctors.delete(doctorId)
                c.chatHistory.write(original)
            }
        }
    }

    private fun capture() {
        val context = ApplicationProvider.getApplicationContext<OkiApplication>()
        val screenshot =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .takeScreenshot()
        java.io
            .File(context.getExternalFilesDir(null), "batch-review-check.png")
            .outputStream()
            .use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun usageIconOpensAllModelsAndHonestUnknownQuota() {
        val c = ApplicationProvider.getApplicationContext<OkiApplication>().container
        val store = ViewModelStore()
        lateinit var vm: AssistantViewModel
        try {
            rule.runOnIdle {
                vm = AssistantViewModel(c)
                store.put("chat", vm)
            }
            rule.setContent {
                OkiTheme(Appearance.DARK) { AssistantScreen(vm, { _, _ -> }, { _, _ -> }, {}) }
            }
            rule.onNodeWithContentDescription("Model usage and limits").performClick()
            rule.onNodeWithText("Models & usage").assertExists()
            rule.onNodeWithText("1. Groq · $GROQ_PRIMARY").assertExists()
            rule
                .onAllNodes(hasScrollToIndexAction())
                .onLast()
                .performScrollToNode(hasText("${CHAT_MODELS.size}. Gemini · $GEMINI_FALLBACK"))
            rule.onNodeWithText("${CHAT_MODELS.size}. Gemini · $GEMINI_FALLBACK").assertExists()
            rule
                .onAllNodesWithText("Remaining quota unavailable · check Usage")
                .onLast()
                .assertExists()
        } finally {
            rule.runOnIdle { store.clear() }
        }
    }
}
