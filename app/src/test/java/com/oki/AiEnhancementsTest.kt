package com.oki

import com.oki.core.ai.*
import com.oki.core.security.Provider
import com.oki.feature.assistant.*
import java.net.UnknownHostException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AiEnhancementsTest {
    private val empty = JsonArray(emptyList())
    private val valid = buildJsonObject {
        put("role", "assistant")
        put("content", "Done")
    }

    @Test
    fun chatUsesAllFiveModelsInOrderAndReportsSuccessfulIdentity() = runTest {
        val attempts = mutableListOf<String>()
        val groq = GroqTransport { model, _, _, _, _ ->
            attempts += model
            throw ApiFailure(429)
        }
        val gemini = GroqTransport { model, _, _, _, _ ->
            attempts += model
            if (model == GEMINI_PRIMARY) throw ApiFailure(503) else valid
        }
        val result = AiRouter(groq, gemini).completeTracked(empty, empty)
        assertEquals(CHAT_MODELS.map { it.model }, attempts)
        assertEquals("Gemini · $GEMINI_FALLBACK", result.identity.label)
    }

    @Test
    fun badGroqKeySkipsOtherGroqModelsAndUsesGemini() = runTest {
        val attempts = mutableListOf<String>()
        val router =
            AiRouter(
                GroqTransport { model, _, _, _, _ ->
                    attempts += model
                    throw ApiFailure(401)
                },
                GroqTransport { model, _, _, _, _ ->
                    attempts += model
                    valid
                },
            )
        assertEquals(Provider.GEMINI, router.completeTracked(empty, empty).identity.provider)
        assertEquals(listOf(GROQ_PRIMARY, GEMINI_PRIMARY), attempts)
    }

    @Test
    fun missingGroqKeyGoesDirectlyToGemini() = runTest {
        val router =
            AiRouter(
                GroqTransport { _, _, _, _, _ -> error("Groq must be skipped") },
                GroqTransport { _, _, _, _, _ -> valid },
                available = { it == Provider.GEMINI },
            )
        assertEquals(GEMINI_PRIMARY, router.completeTracked(empty, empty).identity.model)
    }

    @Test
    fun cancelOfflineAndBadRequestNeverContactSecondProvider() = runTest {
        for (failure in listOf(CancellationException(), UnknownHostException(), ApiFailure(400))) {
            var fallback = false
            val router =
                AiRouter(
                    GroqTransport { _, _, _, _, _ -> throw failure },
                    GroqTransport { _, _, _, _, _ ->
                        fallback = true
                        valid
                    },
                )
            assertTrue(runCatching { router.complete(empty, empty) }.isFailure)
            assertFalse(fallback)
        }
    }

    @Test
    fun cooldownSkipsLimitedModelWithoutAnotherRequest() = runTest {
        val store = AiUsageStore()
        store.record(CHAT_MODELS.first(), 429, mapOf("retry-after" to "60"), null)
        val attempts = mutableListOf<String>()
        val router =
            AiRouter(
                GroqTransport { model, _, _, _, _ ->
                    attempts += model
                    valid
                },
                usage = store,
            )
        assertEquals(GROQ_FALLBACK, router.completeTracked(empty, empty).identity.model)
        assertEquals(listOf(GROQ_FALLBACK), attempts)
    }

    @Test
    fun quotaHeadersAreSnapshotsAndTotalsAccumulateAcrossChatAndScans() {
        val store = AiUsageStore()
        val model = ModelIdentity(Provider.GROQ, GROQ_VISION_MODEL)
        val usage =
            aiJson
                .parseToJsonElement(
                    """{"usage":{"prompt_tokens":12,"completion_tokens":8,"total_tokens":20}}"""
                )
                .jsonObject
        store.record(
            model,
            200,
            mapOf(
                "x-ratelimit-limit-tokens" to "8000",
                "x-ratelimit-remaining-tokens" to "4000",
                "x-ratelimit-reset-tokens" to "1m2.5s",
            ),
            usage,
            1000,
        )
        val first = store.usage.value.single()
        assertEquals(4000L, first.tokens!!.remaining)
        assertEquals(63500L, first.tokens.resetAt)
        store.record(model, 200, emptyMap(), usage, 2000)
        assertEquals(40L, store.usage.value.single().totalTokens)
        assertEquals(2L, store.usage.value.single().requests)
        assertNull(store.usage.value.single().tokens)
    }

    @Test
    fun geminiUsageAndRetryDetailsDoNotInventQuota() {
        val store = AiUsageStore()
        val model = ModelIdentity(Provider.GEMINI, GEMINI_PRIMARY)
        store.record(
            model,
            200,
            emptyMap(),
            aiJson
                .parseToJsonElement(
                    """{"usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":30,"totalTokenCount":150}}"""
                )
                .jsonObject,
            1000,
        )
        assertEquals(150L, store.usage.value.single().totalTokens)
        assertNull(store.usage.value.single().tokens)
        store.record(
            model,
            429,
            emptyMap(),
            aiJson
                .parseToJsonElement("""{"error":{"details":[{"retryDelay":"32s"}]}}""")
                .jsonObject,
            2000,
        )
        assertEquals(34000L, store.usage.value.single().retryAt)
        assertEquals(32000L, store.cooldown(model, 2000))
        assertEquals(0L, store.cooldown(model, 34001))
    }

    @Test
    fun usagePersistsWithoutMessageData() {
        val directory = Files.createTempDirectory("athii-usage-test").toFile()
        val file = directory.resolve("usage.json")
        try {
            val store = AiUsageStore(file)
            store.record(
                CHAT_MODELS.first(),
                200,
                emptyMap(),
                aiJson
                    .parseToJsonElement(
                        """{"content":"private prompt","usage":{"total_tokens":42}}"""
                    )
                    .jsonObject,
            )
            assertEquals(42L, AiUsageStore(file).usage.value.single().totalTokens)
            assertFalse(file.readText().contains("private prompt"))
            store.clear()
            assertTrue(AiUsageStore(file).usage.value.isEmpty())
        } finally {
            file.delete()
            directory.delete()
        }
    }

    @Test
    fun summaryKeepsRecentTurnsVerbatimAndNewRequestExactlyOnce() {
        val turns = (0..11).map { ChatMessage(id = "$it", text = "Turn $it", user = it % 2 == 0) }
        val chat =
            ChatConversation(
                title = "Chat",
                messages = turns,
                summary = "Remember the appointment",
                summarizedThrough = "11",
            )
        val context = conversationContext("Change its time", summaryContext(chat), chat.summary)
        // Summary, the seven most recent turns and the new request.
        assertEquals(9, context.size)
        assertTrue(
            context
                .first()
                .jsonObject["content"]!!
                .jsonPrimitive
                .content
                .contains("Remember the appointment")
        )
        assertEquals("Turn 5", context[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(
            "Change its time",
            context.last().jsonObject["content"]!!.jsonPrimitive.content,
        )
        val contents = context.map { it.jsonObject["content"]!!.jsonPrimitive.content }
        assertFalse("Turn 4" in contents)
        assertEquals(1, contents.count { it == "Change its time" })
    }

    @Test
    fun modelLabelsSummaryAndEditedDraftsSurviveHistoryReload() = runTest {
        val directory = Files.createTempDirectory("athii-memory-test").toFile()
        val file = directory.resolve("chat.json")
        try {
            val messages =
                listOf(
                    ChatMessage(
                        text = "Drafts",
                        user = false,
                        models = listOf(CHAT_MODELS.last()),
                        drafts =
                            listOf(
                                ChatDraft(
                                    task = TaskDraft(title = "Edited"),
                                    reminderEnabled = false,
                                )
                            ),
                    )
                )
            val history =
                listOf(
                    ChatConversation(
                        title = "Chat",
                        messages = messages,
                        summary = "Memory",
                        summarizedThrough = messages.last().id,
                    )
                )
            ChatHistoryRepository(file).write(history)
            assertEquals(history, ChatHistoryRepository(file).read())
        } finally {
            file.delete()
            directory.delete()
        }
    }

    @Test
    fun reviewPreservesStableIdAndRequiresValidDates() {
        val draft =
            ChatDraft(
                id = "stable",
                task =
                    TaskDraft(
                        title = "Read",
                        date = "2099-01-01",
                        startTime = "12:00",
                        endTime = "13:00",
                    ),
                reminderEnabled = false,
            )
        val task = reviewTask(draft)
        assertEquals("stable", task.id)
        assertFalse(task.reminderEnabled)
        assertEquals("13:00", task.endTime)
        assertTrue(
            runCatching { reviewTask(draft.copy(task = draft.task!!.copy(date = "2099-13-40"))) }
                .isFailure
        )
        assertTrue(
            runCatching { reviewTask(draft.copy(task = draft.task!!.copy(endTime = "11:00"))) }
                .isFailure
        )
    }

    @Test
    fun foreignToolHistoryIsConvertedButNativeSignaturesAreKept() {
        val foreign =
            aiJson
                .parseToJsonElement(
                    """[{"role":"assistant","tool_calls":[{"id":"call1","function":{"name":"getUpcomingTasks","arguments":"{}"}}]},{"role":"tool","tool_call_id":"call1","content":"[]"}]"""
                )
                .jsonArray
        val converted = geminiMessages(foreign, false)
        assertTrue(converted.none { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" })
        val native =
            aiJson
                .parseToJsonElement(
                    """[{"role":"assistant","tool_calls":[{"id":"call1","extra_content":{"google":{"thought_signature":"signature"}},"function":{"name":"getUpcomingTasks","arguments":"{}"}}]}]"""
                )
                .jsonArray
        assertEquals(native, geminiMessages(native, false))
    }

    @Test
    fun imageReportsExactSuccessfulGeminiFallback() = runTest {
        val client =
            GeminiVisionClient(
                groqTransport = GroqVisionTransport { _, _, _, _ -> throw ApiFailure(503) },
                transport =
                    GeminiTransport { model, _, _ ->
                        if (model == GEMINI_PRIMARY) throw ApiFailure(503)
                        else """{"drafts":[{"title":"Read"}]}"""
                    },
            )
        assertEquals(
            "Gemini · $GEMINI_FALLBACK",
            client.extractTracked(byteArrayOf(1), false).identity.label,
        )
    }
}
