package com.oki

import com.oki.core.ai.*
import com.oki.feature.assistant.*
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AssistantRepositoryTest {
    private fun call(name: String, args: String) = buildJsonObject {
        put(
            "tool_calls",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("id", "call-$name")
                        put(
                            "function",
                            buildJsonObject {
                                put("name", name)
                                put("arguments", args)
                            },
                        )
                    }
                )
            },
        )
    }

    @Test
    fun mixedRequestKeepsBothSequentialBatchesWithoutWritingLocalData() = runTest {
        val responses =
            ArrayDeque(
                listOf(
                    call(
                        "draftTasks",
                        """{"drafts":[{"title":"Read a chapter"},{"title":"Walk for ten minutes"}]}""",
                    ),
                    call(
                        "draftDoctors",
                        """{"drafts":[{"doctorName":"Dr Provided One"},{"doctorName":"Dr Provided Two"}]}""",
                    ),
                    buildJsonObject { put("content", "Review all four below.") },
                )
            )
        val repo =
            AssistantRepository(
                AiRouter(GroqTransport { _, _, _, _, _ -> responses.removeFirst() }),
                AssistantToolExecutor { _, _ ->
                    fail("Drafts must not execute local writes")
                    JsonNull
                },
            )
        val answer = repo.ask("Make two tasks and add Dr Provided One and Dr Provided Two")
        assertEquals(
            listOf("Read a chapter", "Walk for ten minutes"),
            answer.taskDrafts.map { it.title },
        )
        assertEquals(
            listOf("Dr Provided One", "Dr Provided Two"),
            answer.doctorDrafts.map { it.doctorName },
        )
        assertEquals(4, answer.reviewDrafts.size)
        assertTrue(answer.reviewDrafts.none { it.saved })
    }

    @Test
    fun contextPreservesRolesSavedDraftsAndAppendsCurrentQuestionExactlyOnce() {
        val selected =
            listOf(
                ChatMessage(text = "Suggest a task", user = true),
                ChatMessage(
                    text = "Try reading.",
                    user = false,
                    drafts =
                        listOf(
                            ChatDraft(
                                id = "draft",
                                task = TaskDraft(title = "Read a chapter"),
                                saved = true,
                            )
                        ),
                ),
            )
        val context = conversationContext("Did I save it?", selected).map { it.jsonObject }
        assertEquals(
            listOf("user", "assistant", "user"),
            context.map { it["role"]!!.jsonPrimitive.content },
        )
        assertTrue(context[1]["content"]!!.jsonPrimitive.content.contains("Read a chapter"))
        assertTrue(context[1]["content"]!!.jsonPrimitive.content.contains("\"saved\":true"))
        assertEquals("Did I save it?", context.last()["content"]!!.jsonPrimitive.content)
        assertEquals(
            3,
            conversationContext(
                    "Did I save it?",
                    selected + ChatMessage(text = "Did I save it?", user = true),
                )
                .size,
        )
    }

    @Test
    fun diskHistoryRestoresSeparateConversationsAndIndividualSavedStates() = runTest {
        val file = Files.createTempDirectory("athii-chat-test").resolve("history.json").toFile()
        try {
            val history =
                listOf(
                    ChatConversation(
                        id = "one",
                        title = "First",
                        messages =
                            listOf(
                                ChatMessage(
                                    text = "Two drafts",
                                    user = false,
                                    drafts =
                                        listOf(
                                            ChatDraft(
                                                "a",
                                                task = TaskDraft(title = "A"),
                                                saved = true,
                                            ),
                                            ChatDraft("b", task = TaskDraft(title = "B")),
                                        ),
                                )
                            ),
                    ),
                    ChatConversation(
                        id = "two",
                        title = "Second",
                        messages = listOf(ChatMessage(text = "Separate context", user = true)),
                    ),
                )
            ChatHistoryRepository(file).write(history)
            assertEquals(history, ChatHistoryRepository(file).read())
            ChatHistoryRepository(file).clear()
            assertTrue(ChatHistoryRepository(file).read().isEmpty())
        } finally {
            file.parentFile?.deleteRecursively()
        }
    }
}
