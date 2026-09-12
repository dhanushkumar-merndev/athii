package com.oki.core.ai

import com.oki.core.security.*
import com.oki.core.storage.ReasoningEffort
import java.io.IOException
import java.time.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class AiHttp(private val usage: AiUsageStore? = null) {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(50, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    suspend fun post(
        url: String,
        keyHeader: String,
        credential: String,
        body: JsonObject,
    ): JsonObject {
        require(
            url.startsWith("https://api.groq.com/") ||
                url.startsWith("https://generativelanguage.googleapis.com/")
        )
        val request =
            Request.Builder()
                .url(url)
                .header(keyHeader, credential)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
        val raw =
            suspendCancellableCoroutine<String> { continuation ->
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(
                    object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }

                        override fun onResponse(call: Call, response: Response) {
                            response.use {
                                try {
                                    val text = it.body?.string().orEmpty()
                                    val payload =
                                        runCatching { aiJson.parseToJsonElement(text).jsonObject }
                                            .getOrNull()
                                    val provider =
                                        if (url.startsWith("https://api.groq.com/")) Provider.GROQ
                                        else Provider.GEMINI
                                    val model =
                                        body["model"]?.jsonPrimitive?.contentOrNull
                                            ?: url.substringAfter("/models/", "")
                                                .substringBefore(":")
                                    if (model.isNotBlank())
                                        usage?.record(
                                            ModelIdentity(provider, model),
                                            it.code,
                                            it.headers.names().associateWith { name ->
                                                it.header(name).orEmpty()
                                            },
                                            payload,
                                        )
                                    if (!it.isSuccessful) {
                                        val retry = it.header("Retry-After")
                                        val ms =
                                            retryDelayMs(retry, payload, System.currentTimeMillis())
                                        val unavailable =
                                            it.code == 404 &&
                                                runCatching {
                                                        aiJson
                                                            .parseToJsonElement(text)
                                                            .jsonObject["error"]
                                                            ?.jsonObject
                                                            ?.get("code")
                                                            ?.jsonPrimitive
                                                            ?.content in
                                                            listOf(
                                                                "model_not_found",
                                                                "model_decommissioned",
                                                            )
                                                    }
                                                    .getOrDefault(false)
                                        if (continuation.isActive)
                                            continuation.resumeWithException(
                                                ApiFailure(
                                                    it.code,
                                                    ms,
                                                    unavailable,
                                                    invalidToolCall =
                                                        it.code == 400 &&
                                                            (payload?.get("error") as? JsonObject)
                                                                ?.get("code")
                                                                ?.jsonPrimitive
                                                                ?.contentOrNull == "tool_use_failed",
                                                )
                                            )
                                    } else if (continuation.isActive) continuation.resume(text)
                                } catch (e: Exception) {
                                    if (continuation.isActive) continuation.resumeWithException(e)
                                }
                            }
                        }
                    }
                )
            }
        return try {
            aiJson.parseToJsonElement(raw).jsonObject
        } catch (_: Exception) {
            throw MalformedResult()
        }
    }
}

class GroqChatClient(private val credentials: SecureCredentialStore, private val http: AiHttp) :
    GroqTransport {
    override suspend fun complete(
        model: String,
        messages: JsonArray,
        tools: JsonArray,
        repair: Boolean,
        reasoningEffort: ReasoningEffort,
    ): JsonObject {
        val body = buildJsonObject {
            put("model", model)
            put(
                "messages",
                if (repair)
                    JsonArray(
                        messages +
                            buildJsonObject {
                                put("role", "system")
                                put(
                                    "content",
                                    "Return a valid assistant message or tool call with JSON arguments. Repair the previous response structure.",
                                )
                            }
                    )
                else messages,
            )
            put("temperature", 0.2)
            put("max_completion_tokens", if (tools.isNotEmpty()) 3000 else 768)
            if (model.contains("oss")) {
                put("reasoning_effort", reasoningEffort.name.lowercase())
                put("include_reasoning", false)
            }
            if (tools.isNotEmpty()) {
                put("tools", tools)
                put("tool_choice", "auto")
                put("parallel_tool_calls", false)
            }
        }
        val response =
            http.post(
                "https://api.groq.com/openai/v1/chat/completions",
                "Authorization",
                "Bearer ${credentials.readForRequest(Provider.GROQ)}",
                body,
            )
        return try {
            val choice = response["choices"]!!.jsonArray.first().jsonObject
            if (choice["finish_reason"]?.jsonPrimitive?.contentOrNull == "length")
                throw MalformedResult()
            choice["message"]!!.jsonObject
        } catch (_: Exception) {
            throw MalformedResult()
        }
    }
}
