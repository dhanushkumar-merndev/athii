package com.oki

import com.oki.core.ai.*
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class VisionClientTest {
    private val valid =
        """{"drafts":[{"title":"Buy notebooks","date":"2026-09-13","time":"17:30","startTime":"17:30"},{"title":"Water plants"}]}"""

    private class GroqFake(private val result: Any) : GroqVisionTransport {
        val calls = mutableListOf<String>()
        var image = ""

        override suspend fun generate(
            model: String,
            prompt: String,
            image: String,
            schema: JsonObject,
        ): String {
            calls += model
            this.image = image
            if (result is Exception) throw result
            return result as String
        }
    }

    private class GeminiFake(private vararg val results: Any) : GeminiTransport {
        val calls = mutableListOf<String>()

        override suspend fun generate(model: String, parts: JsonArray, schema: JsonObject): String {
            val result = results[calls.size]
            calls += model
            if (result is Exception) throw result
            return result as String
        }
    }

    @Test
    fun qwenImageSuccessPreservesEveryDraftWithoutCallingGemini() = runTest {
        val groq = GroqFake(valid)
        val gemini = GeminiFake()
        val drafts =
            GeminiVisionClient(transport = gemini, groqTransport = groq)
                .extract(byteArrayOf(1, 2, 3), false)
        assertEquals(2, drafts.size)
        assertEquals("AQID", groq.image)
        assertEquals(listOf(GROQ_VISION_MODEL), groq.calls)
        assertTrue(gemini.calls.isEmpty())
    }

    @Test
    fun quotaAndServiceOutageUseBoundedProviderFallbacks() = runTest {
        val groq = GroqFake(ApiFailure(429))
        val gemini = GeminiFake(ApiFailure(503), valid)
        val drafts =
            GeminiVisionClient(transport = gemini, groqTransport = groq)
                .extract(byteArrayOf(1), false)
        assertEquals(2, drafts.size)
        assertEquals(listOf(GEMINI_PRIMARY, GEMINI_FALLBACK), gemini.calls)
        assertEquals(1, groq.calls.size)
    }

    @Test
    fun badProviderCredentialCanUseSeparatelyConfiguredProvider() = runTest {
        val groq = GroqFake(ApiFailure(401))
        val gemini = GeminiFake(valid)
        assertEquals(
            2,
            GeminiVisionClient(transport = gemini, groqTransport = groq)
                .extract(byteArrayOf(1), false)
                .size,
        )
        assertEquals(1, groq.calls.size)
        assertEquals(1, gemini.calls.size)
    }

    @Test
    fun providerWideQuotaDoesNotRetryBeforeRetryAfter() = runTest {
        val gemini = GeminiFake(ApiFailure(429, 60_000))
        val error =
            runCatching { GeminiVisionClient(transport = gemini).extract(byteArrayOf(1), false) }
                .exceptionOrNull()
        assertTrue(error is ApiFailure)
        assertEquals(1, gemini.calls.size)
    }

    @Test
    fun offlineAndBadRequestsDoNotUploadAgain() = runTest {
        for (error in listOf(UnknownHostException(), ApiFailure(400))) {
            val groq = GroqFake(error)
            val gemini = GeminiFake()
            val actual =
                runCatching {
                        GeminiVisionClient(transport = gemini, groqTransport = groq)
                            .extract(byteArrayOf(1), false)
                    }
                    .exceptionOrNull()
            assertEquals(error.javaClass, actual?.javaClass)
            if (error is ApiFailure) assertEquals(error.status, (actual as ApiFailure).status)
            assertEquals(1, groq.calls.size)
            assertTrue(gemini.calls.isEmpty())
        }
    }

    @Test
    fun cancelStopsEveryFallback() = runTest {
        val groq = GroqFake(CancellationException())
        val gemini = GeminiFake()
        assertTrue(
            runCatching {
                    GeminiVisionClient(transport = gemini, groqTransport = groq)
                        .extract(byteArrayOf(1), false)
                }
                .exceptionOrNull() is CancellationException
        )
        assertTrue(gemini.calls.isEmpty())
    }

    @Test
    fun truncatedOrMalformedOrTimedOutPrimaryUsesFullGeminiResponse() = runTest {
        for (result in listOf(ScanResultTooLarge(), "{broken", SocketTimeoutException())) {
            val gemini = GeminiFake(valid)
            assertEquals(
                2,
                GeminiVisionClient(transport = gemini, groqTransport = GroqFake(result))
                    .extract(byteArrayOf(1), false)
                    .size,
            )
            assertEquals(1, gemini.calls.size)
        }
    }

    @Test
    fun allFailuresReturnOneCleanErrorAfterThreeAttempts() = runTest {
        val groq = GroqFake(ApiFailure(503))
        val gemini = GeminiFake(ApiFailure(503), ApiFailure(503))
        val error =
            runCatching {
                    GeminiVisionClient(transport = gemini, groqTransport = groq)
                        .extract(byteArrayOf(1), false)
                }
                .exceptionOrNull()!!
        assertEquals("AI service temporarily unavailable. Please try again.", friendlyError(error))
        assertEquals(3, groq.calls.size + gemini.calls.size)
    }

    @Test
    fun moreThanTwelveDraftsAreNeverSilentlyDiscarded() {
        val response = buildJsonObject {
            put(
                "drafts",
                buildJsonArray { repeat(20) { add(buildJsonObject { put("title", "Task $it") }) } },
            )
        }
        assertEquals(20, ExtractionSchemas.parse(response.toString(), false).size)
    }

    @Test
    fun partialTokenLimitedJsonIsNeverPresentedAsACompleteBatch() {
        val groq =
            aiJson
                .parseToJsonElement(
                    """{"choices":[{"finish_reason":"length","message":{"content":"{\"drafts\":[]}"}}]}"""
                )
                .jsonObject
        val gemini =
            aiJson
                .parseToJsonElement(
                    """{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"{\"drafts\":[]}"}]}}]}"""
                )
                .jsonObject
        assertTrue(
            runCatching { parseGroqVisionResponse(groq) }.exceptionOrNull() is ScanResultTooLarge
        )
        assertTrue(
            runCatching { parseGeminiVisionResponse(gemini) }.exceptionOrNull()
                is ScanResultTooLarge
        )
    }

    @Test
    fun geminiThoughtPartsAreExcludedFromStructuredDrafts() {
        val response = buildJsonObject {
            put(
                "candidates",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "content",
                                buildJsonObject {
                                    put(
                                        "parts",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("thought", true)
                                                    put("text", "analysis text")
                                                }
                                            )
                                            add(buildJsonObject { put("text", valid) })
                                        },
                                    )
                                },
                            )
                        }
                    )
                },
            )
        }
        assertEquals(2, ExtractionSchemas.parse(parseGeminiVisionResponse(response), false).size)
    }
}
