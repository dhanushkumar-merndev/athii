package com.oki.core.ai

import com.oki.core.storage.ReasoningEffort
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

const val GROQ_PRIMARY = "openai/gpt-oss-120b"
const val GROQ_FALLBACK = "openai/gpt-oss-20b"
const val GEMINI_MODEL = "gemini-3.7-flash"
val aiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

class ApiFailure(
    val status: Int,
    val retryAfterMs: Long = 0,
    val modelUnavailable: Boolean = false,
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
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun complete(
        messages: JsonArray,
        tools: JsonArray,
        reasoningEffort: ReasoningEffort = ReasoningEffort.HIGH,
    ): JsonObject {
        var repair = false
        try {
            return validated(
                transport.complete(GROQ_PRIMARY, messages, tools, false, reasoningEffort)
            )
        } catch (e: Exception) {
            if (!transient(e)) throw e
            repair = e is MalformedResult
            if (e is ApiFailure && e.retryAfterMs > 3000) throw e
            if (e is ApiFailure && e.retryAfterMs > 0) pause(e.retryAfterMs)
            if (e is SocketTimeoutException || e is MalformedResult) {
                try {
                    return validated(
                        transport.complete(GROQ_PRIMARY, messages, tools, repair, reasoningEffort)
                    )
                } catch (retry: Exception) {
                    if (!transient(retry)) throw retry
                    if (retry is ApiFailure && retry.retryAfterMs > 3000) throw retry
                    if (retry is ApiFailure && retry.retryAfterMs > 0) pause(retry.retryAfterMs)
                }
            }
        }
        return validated(
            transport.complete(GROQ_FALLBACK, messages, tools, repair, reasoningEffort)
        )
    }

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
                (e.status == 429 || e.status in listOf(500, 502, 503, 504) || e.modelUnavailable))

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
