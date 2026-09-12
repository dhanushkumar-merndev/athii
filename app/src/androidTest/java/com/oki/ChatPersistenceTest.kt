package com.oki

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.oki.core.ai.TaskDraft
import com.oki.core.storage.Appearance
import com.oki.core.ui.OkiTheme
import com.oki.feature.assistant.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatPersistenceTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun recreatedViewModelLoadsHistoryAndRetainsEachCreatedDraft() {
        val c = ApplicationProvider.getApplicationContext<OkiApplication>().container
        val stores = listOf(ViewModelStore(), ViewModelStore())
        val original = runBlocking { c.chatHistory.read() }
        try {
            lateinit var first: AssistantViewModel
            rule.runOnIdle {
                first = AssistantViewModel(c)
                stores[0].put("chat", first)
            }
            rule.waitUntil(5000) { first.historyReady.value }
            rule.runOnIdle {
                first.restoreHistory(
                    listOf(
                        ChatConversation(
                            id = "history-fixture",
                            title = "Saved conversation",
                            messages =
                                listOf(
                                    ChatMessage(
                                        text = "Two suggestions",
                                        user = false,
                                        drafts =
                                            listOf(
                                                ChatDraft(
                                                    "saved-item",
                                                    task = TaskDraft(title = "Read a chapter"),
                                                ),
                                                ChatDraft(
                                                    "pending-item",
                                                    task = TaskDraft(title = "Take a walk"),
                                                ),
                                            ),
                                    )
                                ),
                        )
                    )
                )
                first.markDraftSaved("saved-item")
            }
            rule.waitUntil(5000) {
                runBlocking {
                    c.chatHistory
                        .read()
                        .firstOrNull()
                        ?.messages
                        ?.firstOrNull()
                        ?.reviewDrafts
                        ?.firstOrNull()
                        ?.saved == true
                }
            }
            rule.runOnIdle { stores[0].clear() }
            lateinit var reopened: AssistantViewModel
            rule.runOnIdle {
                reopened = AssistantViewModel(c)
                stores[1].put("chat", reopened)
            }
            rule.waitUntil(5000) { reopened.historyReady.value }
            // Reopening starts a fresh composer while preserving previous conversations.
            assertNull(reopened.activeId.value)
            assertEquals("Saved conversation", reopened.conversations.value.single().title)
            rule.setContent {
                OkiTheme(Appearance.DARK) {
                    AssistantScreen(reopened, { _, _ -> }, { _, _ -> }, {})
                }
            }
            rule.runOnIdle { reopened.showHistory() }
            rule.onNodeWithText("Saved conversation").performClick()
            rule.onNodeWithText("Two suggestions").assertExists()
            rule.onNodeWithText("Task created").assertExists()
            rule.onAllNodesWithText("Review task").assertCountEquals(1)
        } finally {
            rule.runOnIdle { stores.forEach { it.clear() } }
            runBlocking { c.chatHistory.write(original) }
        }
    }
}
