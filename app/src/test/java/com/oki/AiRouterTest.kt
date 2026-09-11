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
    fun bothModelsFailCleanly() = runTest {
        val fake = Fake(mutableListOf(ApiFailure(503), ApiFailure(503)))
        val failure =
            runCatching { AiRouter(fake).complete(messages, messages) }.exceptionOrNull()!!
        assertEquals(
            "AI service temporarily unavailable. Please try again.",
            friendlyError(failure),
        )
        assertEquals(2, fake.calls.size)
    }

    @Test
    fun longRetryAfterDoesNotRetryEarly() = runTest {
        val fake = Fake(mutableListOf(ApiFailure(429, 60000)))
        assertTrue(runCatching { AiRouter(fake).complete(messages, messages) }.isFailure)
        assertEquals(1, fake.calls.size)
    }

    @Test
    fun modelUnavailableFallsBack() = runTest {
        val fake = Fake(mutableListOf(ApiFailure(404, modelUnavailable = true), valid))
        AiRouter(fake).complete(messages, messages)
        assertEquals(GROQ_FALLBACK, fake.calls.last().first)
    }
}
