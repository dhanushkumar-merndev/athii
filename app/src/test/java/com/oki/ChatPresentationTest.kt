package com.oki

import com.oki.core.ai.*
import com.oki.core.storage.ReasoningEffort
import com.oki.feature.assistant.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ChatPresentationTest {
    @Test
    fun relativeAgeBoundariesAndPluralForms() {
        assertNull(chatAge(null, 0))
        assertEquals("Just now", chatAge(0, 59_999))
        assertEquals("Just now", chatAge(10, 0))
        listOf(
                60_000L to "1 minute ago",
                120_000L to "2 minutes ago",
                3_600_000L to "1 hour ago",
                7_200_000L to "2 hours ago",
                86_400_000L to "1 day ago",
                172_800_000L to "2 days ago",
            )
            .forEach { (age, expected) -> assertEquals(expected, chatAge(0, age)) }
        assertEquals(1L, nextChatAgeUpdate(listOf(0), 59_999))
        assertEquals(60_000L, nextChatAgeUpdate(emptyList(), 0))
        assertEquals(1L, nextChatAgeUpdate(listOf(0), 3_599_999))
    }

    @Test
    fun legacyChatsDoNotInventDatesAndNewDatesPersist() {
        val legacy = aiJson.decodeFromString<ChatConversation>("""{"id":"old","title":"hi"}""")
        assertNull(legacy.updatedAt)
        val dated = legacy.copy(updatedAt = 12345L)
        assertEquals(dated, aiJson.decodeFromString<ChatConversation>(aiJson.encodeToString(dated)))
    }

    @Test
    fun freshGreetingUsesSmallPromptNoToolsAndNoSummaryRequest() = runTest {
        var calls = 0
        val repo =
            AssistantRepository(
                AiRouter(
                    GroqTransport { _, messages, tools, _, effort ->
                        calls++
                        assertTrue(tools.isEmpty())
                        assertEquals(ReasoningEffort.LOW, effort)
                        assertEquals(2, messages.size)
                        assertTrue(messages.toString().length < 500)
                        buildJsonObject { put("content", "Vanakkam! How can I help?") }
                    }
                ),
                AssistantToolExecutor { _, _ -> error("No tools for a greeting") },
            )
        val answer = repo.ask("hi!")
        assertEquals(GROQ_PRIMARY, answer.models.single().model)
        assertNull(repo.summarize("", "hi!", answer))
        assertEquals(1, calls)
        assertFalse(isSimpleGreeting("hi, show today's doctors"))
    }

    @Test
    fun greetingsWithContextAndRealRequestsKeepTools() = runTest {
        var calls = 0
        val repo =
            AssistantRepository(
                AiRouter(
                    GroqTransport { _, _, tools, _, _ ->
                        calls++
                        assertTrue(tools.isNotEmpty())
                        buildJsonObject { put("content", "Reply") }
                    }
                ),
                AssistantToolExecutor { _, _ -> JsonNull },
            )
        repo.ask("hi", listOf(ChatMessage(text = "Earlier request", user = true)))
        repo.ask("hi", summary = "Existing memory")
        repo.ask("hi, show today's doctors")
        assertEquals(3, calls)
    }

    @Test
    fun markdownTablesRetainProseInlineStylesAndAlignment() {
        val blocks =
            assistantBlocks(
                "Doctors today:\n\n| Doctor | Hours |\n| :--- | ---: |\n| **Dr A** | 09:00–18:00 |\n\nAsk for details."
            )
        assertEquals(3, blocks.size)
        val table = blocks[1] as AssistantBlock.Table
        assertEquals(listOf("Doctor", "Hours"), table.headers)
        assertEquals(listOf("**Dr A**", "09:00–18:00"), table.rows.single())
        assertEquals(listOf(CellAlignment.LEFT, CellAlignment.RIGHT), table.alignments)
    }

    @Test
    fun tablesSupportOptionalOuterPipesEscapesCodeAndEmptyCells() {
        assertEquals(listOf("a|b", "`x|y`", ""), tableCells("| a\\|b | `x|y` | |"))
        val table =
            assistantBlocks("A | B\n--- | :---:\nx | y\nz |\n").single() as AssistantBlock.Table
        assertEquals(listOf("z", ""), table.rows.last())
        assertEquals(CellAlignment.CENTER, table.alignments.last())
    }

    @Test
    fun malformedAndFencedTablesRemainText() {
        assertTrue(assistantBlocks("A | B\n--- | nope\nx | y").single() is AssistantBlock.Prose)
        assertTrue(
            assistantBlocks("```text\nA | B\n--- | ---\nx | y\n```").single()
                is AssistantBlock.Prose
        )
        assertTrue(
            assistantBlocks("~~~\nA | B\n--- | ---\nx | y\n~~~").single() is AssistantBlock.Prose
        )
    }
}
