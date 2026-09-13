package com.oki

import com.oki.core.ai.*
import com.oki.core.storage.ReasoningEffort
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AiRouterTest {
    private val messages = JsonArray(emptyList())
    private val valid = buildJsonObject {
        put("role", "assistant")
        put("content", "Found your task.")
    }

    private class Fake(private val results: MutableList<Any>) : GroqTransport {
        val calls = mutableListOf<Pair<String, Boolean>>()
        val efforts = mutableListOf<ReasoningEffort>()

        override suspend fun complete(
            model: String,
            messages: JsonArray,
            tools: JsonArray,
            repair: Boolean,
            reasoningEffort: ReasoningEffort,
        ): JsonObject {
            calls += model to repair
            efforts += reasoningEffort
            val result = results.removeAt(0)
            if (result is Exception) throw result
            return result as JsonObject
        }
    }

    @Test
    fun primarySuccessDoesNotCallFallback() = runTest {
        val fake = Fake(mutableListOf(valid))
        assertEquals(valid, AiRouter(fake).complete(messages, messages))
        assertEquals(listOf(GROQ_PRIMARY), fake.calls.map { it.first })
    }

    @Test
    fun highReasoningIsTheDefault() = runTest {
        val fake = Fake(mutableListOf(valid))
        AiRouter(fake).complete(messages, messages)
        assertEquals(listOf(ReasoningEffort.HIGH), fake.efforts)
    }

    @Test
    fun rateLimitUsesFallbackAfterRetryAfter() = runTest {
        val fake = Fake(mutableListOf(ApiFailure(429, 1500), valid))
        val pauses = mutableListOf<Long>()
        AiRouter(fake) { pauses += it }.complete(messages, messages)
        assertEquals(listOf(1500L), pauses)
        assertEquals(listOf(GROQ_PRIMARY, GROQ_FALLBACK), fake.calls.map { it.first })
    }

    @Test
    fun serviceUnavailableUsesFallback() = runTest {
        val fake = Fake(mutableListOf(ApiFailure(503), valid))
        AiRouter(fake).complete(messages, messages)
        assertEquals(GROQ_FALLBACK, fake.calls.last().first)
    }

    @Test
    fun timeoutsRetryOnceThenFallback() = runTest {
        val fake = Fake(mutableListOf(SocketTimeoutException(), SocketTimeoutException(), valid))
        AiRouter(fake).complete(messages, messages)
        assertEquals(listOf(GROQ_PRIMARY, GROQ_PRIMARY, GROQ_FALLBACK), fake.calls.map { it.first })
    }

    @Test
    fun authorizationFailuresNeverFallback() = runTest {
        for (status in listOf(400, 401, 403)) {
            val fake = Fake(mutableListOf(ApiFailure(status)))
            assertTrue(
                runCatching { AiRouter(fake).complete(messages, messages) }.exceptionOrNull()
                    is ApiFailure
            )
            assertEquals(1, fake.calls.size)
        }
    }

    @Test
    fun offlineDoesNotRetryOrFallback() = runTest {
        val fake = Fake(mutableListOf(UnknownHostException()))
        assertTrue(runCatching { AiRouter(fake).complete(messages, messages) }.isFailure)
        assertEquals(1, fake.calls.size)
    }

    @Test
    fun malformedResponseGetsOneRepairThenFallback() = runTest {
        val fake = Fake(mutableListOf(buildJsonObject {}, buildJsonObject {}, valid))
        AiRouter(fake).complete(messages, messages)
        assertEquals(
            listOf(GROQ_PRIMARY to false, GROQ_PRIMARY to true, GROQ_FALLBACK to true),
            fake.calls,
        )
    }

    @Test
    fun validToolArgumentsAreParsed() = runTest {
        val tool =
            aiJson
                .parseToJsonElement(
                    """{"role":"assistant","tool_calls":[{"id":"call1","function":{"name":"searchTasks","arguments":"{\"query\":\"report\"}"}}]}"""
                )
                .jsonObject
        assertEquals(tool, AiRouter(Fake(mutableListOf(tool))).complete(messages, messages))
    }

    @Test
    fun malformedToolArgumentsTriggerRepair() = runTest {
        val tool =
            aiJson
                .parseToJsonElement(
                    """{"tool_calls":[{"id":"call1","function":{"name":"searchTasks","arguments":"bad JSON"}}]}"""
                )
                .jsonObject
        val fake = Fake(mutableListOf(tool, valid))
        AiRouter(fake).complete(messages, messages)
        assertTrue(fake.calls[1].second)
    }

    @Test
    fun allModelsFailCleanly() = runTest {
        val fake = Fake(GROQ_MODELS.map { ApiFailure(503) as Any }.toMutableList())
        val failure =
            runCatching { AiRouter(fake).complete(messages, messages) }.exceptionOrNull()!!
        assertEquals(
            "AI service temporarily unavailable. Please try again.",
            friendlyError(failure),
        )
        assertEquals(GROQ_MODELS.size, fake.calls.size)
    }

    @Test
    fun longRetryAfterDoesNotPause() = runTest {
        val fake = Fake(mutableListOf(ApiFailure(429, 60000), valid))
        val pauses = mutableListOf<Long>()
        val result = AiRouter(fake) { pauses += it }.complete(messages, messages)
        assertEquals(valid, result)
        assertTrue(pauses.isEmpty())
        assertEquals(listOf(GROQ_PRIMARY, GROQ_FALLBACK), fake.calls.map { it.first })
    }

    @Test
    fun threeTierFallbackWorksAcrossAllModels() = runTest {
        val fake = Fake(mutableListOf(ApiFailure(429, 5000), ApiFailure(429, 5000), valid))
        val result = AiRouter(fake).complete(messages, messages)
        assertEquals(valid, result)
        assertEquals(
            listOf(GROQ_PRIMARY, GROQ_FALLBACK, GROQ_TERTIARY),
            fake.calls.map { it.first },
        )
    }

    @Test
    fun rateLimitReportedOnlyAfterAllModelsExhausted() = runTest {
        val fake = Fake(GROQ_MODELS.map { ApiFailure(429, 5000) as Any }.toMutableList())
        val failure =
            runCatching { AiRouter(fake).complete(messages, messages) }.exceptionOrNull()!!
        assertEquals(
            "AI quota or rate limit reached. Please try again later.",
            friendlyError(failure),
        )
        assertEquals(GROQ_MODELS.size, fake.calls.size)
    }

    @Test
    fun modelUnavailableFallsBack() = runTest {
        val fake = Fake(mutableListOf(ApiFailure(404, modelUnavailable = true), valid))
        AiRouter(fake).complete(messages, messages)
        assertEquals(GROQ_FALLBACK, fake.calls.last().first)
    }

    private class FakeGeminiTransport(private val results: MutableList<Any>) : GeminiTransport {
        val calls = mutableListOf<String>()

        override suspend fun generate(model: String, parts: JsonArray, schema: JsonObject): String {
            calls += model
            val result = results.removeAt(0)
            if (result is Exception) throw result
            return result as String
        }
    }

    @Test
    fun geminiPrimarySuccessDoesNotFallback() = runTest {
        val json = """{"drafts":[{"title":"Scan Task"}]}"""
        val fake = FakeGeminiTransport(mutableListOf(json))
        val client = GeminiVisionClient(transport = fake)
        val drafts = client.extract(ByteArray(0), false)
        assertEquals(1, drafts.size)
        assertEquals(listOf(GEMINI_PRIMARY), fake.calls)
    }

    @Test
    fun geminiServiceUnavailableFallsBack() = runTest {
        val json = """{"drafts":[{"title":"Scan Task"}]}"""
        val fake = FakeGeminiTransport(mutableListOf(ApiFailure(503), json))
        val client = GeminiVisionClient(transport = fake)
        val drafts = client.extract(ByteArray(0), false)
        assertEquals(1, drafts.size)
        assertEquals(listOf(GEMINI_PRIMARY, GEMINI_FALLBACK), fake.calls)
    }

    @Test
    fun geminiRateLimitFallsBack() = runTest {
        val json = """{"drafts":[{"title":"Scan Task"}]}"""
        val fake = FakeGeminiTransport(mutableListOf(ApiFailure(429), json))
        val client = GeminiVisionClient(transport = fake)
        val drafts = client.extract(ByteArray(0), false)
        assertEquals(1, drafts.size)
        assertEquals(listOf(GEMINI_PRIMARY, GEMINI_FALLBACK), fake.calls)
    }

    @Test
    fun geminiAuthFailureDoesNotFallback() = runTest {
        val fake = FakeGeminiTransport(mutableListOf(ApiFailure(401)))
        val client = GeminiVisionClient(transport = fake)
        assertTrue(
            runCatching { client.extract(ByteArray(0), false) }.exceptionOrNull() is ApiFailure
        )
        assertEquals(1, fake.calls.size)
    }
}
