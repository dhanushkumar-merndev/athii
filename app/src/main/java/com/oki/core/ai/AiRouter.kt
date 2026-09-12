package com.oki.core.ai

import com.oki.core.security.Provider
import com.oki.core.storage.ReasoningEffort
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

const val GROQ_PRIMARY = "openai/gpt-oss-120b"
const val GROQ_FALLBACK = "openai/gpt-oss-20b"
const val GROQ_TERTIARY = "qwen/qwen3.6-27b"
val GROQ_MODELS = listOf(GROQ_PRIMARY, GROQ_FALLBACK, GROQ_TERTIARY)
const val GEMINI_PRIMARY = "gemini-3.5-flash"
const val GEMINI_FALLBACK = "gemini-3.5-flash-lite"
const val GEMINI_MODEL = GEMINI_PRIMARY
val GEMINI_VISION_MODELS = listOf(GEMINI_PRIMARY, GEMINI_FALLBACK)
val aiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

class ApiFailure(
    val status: Int,
    val retryAfterMs: Long = 0,
    val modelUnavailable: Boolean = false,
    val invalidToolCall: Boolean = false,
) : IOException("AI service request failed ($status)")

class MalformedResult : IOException("AI returned an unreadable response.")

fun friendlyError(error: Throwable): String =
    when (error) {
        is UnknownHostException ->
            "No internet connection. Local tasks and doctors are still available."
        is SocketTimeoutException -> "The AI service took too long. Try again."
        is ApiFailure ->
            when (error.status) {
                401,
                403 -> "API key rejected or access denied. Check your key in Settings."
                429 -> "AI quota or rate limit reached. Please try again later."
                404 -> "The requested AI model is unavailable for this API key."
                in 500..599 -> "AI service temporarily unavailable. Please try again."
                else -> "The AI service could not process this request."
            }
        is MalformedResult -> "The AI response could not be read. Please try again."
        is IOException -> "Could not connect to the AI service. Check your connection."
        else ->
            error.message?.takeIf { it.length < 220 } ?: "Something went wrong. Please try again."
    }

fun interface GroqTransport {
    suspend fun complete(
        model: String,
        messages: JsonArray,
        tools: JsonArray,
        repair: Boolean,
        reasoningEffort: ReasoningEffort,
    ): JsonObject
}

class AiRouter(
    private val transport: GroqTransport,
    private val geminiTransport: GroqTransport? = null,
    private val available: suspend (Provider) -> Boolean = { true },
    private val usage: AiUsageStore? = null,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun complete(
        messages: JsonArray,
        tools: JsonArray,
        reasoningEffort: ReasoningEffort = ReasoningEffort.HIGH,
    ): JsonObject = completeTracked(messages, tools, reasoningEffort).message

    suspend fun completeTracked(
        messages: JsonArray,
        tools: JsonArray,
        reasoningEffort: ReasoningEffort = ReasoningEffort.HIGH,
        preferred: ModelIdentity? = null,
        onAttempt: (ModelIdentity) -> Unit = {},
    ): AiCompletion =
        withTimeoutOrNull(140_000) {
            var repair = false
            var lastException: Exception? = null
            val blocked = mutableSetOf<Provider>()
            val candidates =
                CHAT_MODELS.filter {
                    available(it.provider) &&
                        (it.provider == Provider.GROQ || geminiTransport != null)
                }
            check(candidates.isNotEmpty()) { "Add a Groq or Gemini API key in Settings." }
            val ordered =
                if (preferred in candidates) candidates.drop(candidates.indexOf(preferred))
                else candidates
            for (identity in ordered) {
                if (identity.provider in blocked) continue
                val cooldown = usage?.cooldown(identity) ?: 0
                if (cooldown > 0) {
                    lastException = ApiFailure(429, cooldown)
                    continue
                }
                val model = identity.model
                val client =
                    if (identity.provider == Provider.GROQ) transport
                    else checkNotNull(geminiTransport)
                suspend fun attempt(): AiCompletion {
                    onAttempt(identity)
                    val result =
                        withTimeoutOrNull(20_000) {
                            validated(
                                client.complete(model, messages, tools, repair, reasoningEffort)
                            )
                        } ?: throw SocketTimeoutException()
                    return AiCompletion(result, identity)
                }
                try {
                    return@withTimeoutOrNull attempt()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is ApiFailure && e.status in listOf(401, 403)) {
                        blocked += identity.provider
                        lastException = e
                        continue
                    }
                    if (!transient(e)) throw e
                    lastException = e
                    repair = e is MalformedResult || (e is ApiFailure && e.invalidToolCall)

                    if (e is ApiFailure && e.status == 429) {
                        if (e.retryAfterMs in 1..3000) {
                            pause(e.retryAfterMs)
                        }
                        continue
                    }

                    if ((e is SocketTimeoutException && identity == ordered.first()) || repair) {
                        try {
                            return@withTimeoutOrNull attempt()
                        } catch (retry: CancellationException) {
                            throw retry
                        } catch (retry: Exception) {
                            if (retry is ApiFailure && retry.status in listOf(401, 403)) {
                                blocked += identity.provider
                                lastException = retry
                                continue
                            }
                            if (!transient(retry)) throw retry
                            lastException = retry
                            if (
                                retry is ApiFailure &&
                                    retry.status == 429 &&
                                    retry.retryAfterMs in 1..3000
                            ) {
                                pause(retry.retryAfterMs)
                            }
                        }
                    }
                }
            }

            throw lastException ?: MalformedResult()
        } ?: throw SocketTimeoutException()

    suspend fun test(model: String) {
        validated(
            transport.complete(
                model,
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", "Reply OK.")
                        }
                    )
                },
                JsonArray(emptyList()),
                false,
                ReasoningEffort.HIGH,
            )
        )
    }

    private fun transient(e: Exception) =
        e is SocketTimeoutException ||
            e is MalformedResult ||
            (e is ApiFailure &&
                (e.status == 429 ||
                    e.status == 404 ||
                    e.status in listOf(500, 502, 503, 504) ||
                    e.modelUnavailable)) ||
            (e is ApiFailure && e.invalidToolCall)

    private fun validated(message: JsonObject): JsonObject {
        val content = (message["content"] as? JsonPrimitive)?.contentOrNull
        val calls = message["tool_calls"] as? JsonArray
        if (content.isNullOrBlank() && calls.isNullOrEmpty()) throw MalformedResult()
        calls?.forEach { call ->
            val obj = call as? JsonObject ?: throw MalformedResult()
            val fn = obj["function"] as? JsonObject ?: throw MalformedResult()
            if (
                obj["id"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() ||
                    fn["name"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
            )
                throw MalformedResult()
            try {
                aiJson.parseToJsonElement(fn["arguments"]!!.jsonPrimitive.content).jsonObject
            } catch (_: Exception) {
                throw MalformedResult()
            }
        }
        return message
    }
}

data class AiCompletion(val message: JsonObject, val identity: ModelIdentity)
