package com.oki.core.ai

import com.oki.core.security.Provider
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
data class ModelIdentity(val provider: Provider, val model: String) {
    val label: String
        get() = "${if (provider == Provider.GROQ) "Groq" else "Gemini"} · $model"
}

val CHAT_MODELS
    get() =
        GROQ_MODELS.map { ModelIdentity(Provider.GROQ, it) } +
            GEMINI_VISION_MODELS.map { ModelIdentity(Provider.GEMINI, it) }

@Serializable
data class QuotaWindow(val limit: Long, val remaining: Long, val resetAt: Long? = null)

@Serializable
data class ModelUsage(
    val identity: ModelIdentity,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = 0,
    val requests: Long = 0,
    val measuredResponses: Long = 0,
    val tokens: QuotaWindow? = null,
    val requestQuota: QuotaWindow? = null,
    val retryAt: Long? = null,
    val status: Int? = null,
    val updatedAt: Long = 0,
)

/** Stores only API counters, never prompts, responses, images, or credentials. */
class AiUsageStore(private val file: File? = null) {
    private val state =
        MutableStateFlow(
            runCatching {
                    file
                        ?.takeIf { it.exists() }
                        ?.let { aiJson.decodeFromString<List<ModelUsage>>(it.readText()) }
                }
                .getOrNull()
                .orEmpty()
        )
    val usage = state.asStateFlow()

    @Synchronized
    fun record(
        identity: ModelIdentity,
        status: Int,
        headers: Map<String, String>,
        response: JsonObject?,
        now: Long = System.currentTimeMillis(),
    ) {
        val old = state.value.firstOrNull { it.identity == identity } ?: ModelUsage(identity)
        val h = headers.mapKeys { it.key.lowercase() }
        val counts =
            response?.get("usage") as? JsonObject ?: response?.get("usageMetadata") as? JsonObject
        fun count(vararg keys: String) =
            keys.firstNotNullOfOrNull { (counts?.get(it) as? JsonPrimitive)?.longOrNull } ?: 0L
        fun window(kind: String): QuotaWindow? {
            val limit =
                h["x-ratelimit-limit-$kind"]?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            val remaining = h["x-ratelimit-remaining-$kind"]?.toLongOrNull() ?: return null
            return QuotaWindow(
                limit,
                remaining.coerceIn(0, limit),
                durationMs(h["x-ratelimit-reset-$kind"])?.let { now + it },
            )
        }
        val retry = retryDelayMs(h["retry-after"], response, now)
        val updated =
            old.copy(
                inputTokens = old.inputTokens + count("prompt_tokens", "promptTokenCount"),
                outputTokens =
                    old.outputTokens + count("completion_tokens", "candidatesTokenCount"),
                totalTokens = old.totalTokens + count("total_tokens", "totalTokenCount"),
                requests = old.requests + 1,
                measuredResponses = old.measuredResponses + if (counts != null) 1 else 0,
                tokens = window("tokens"),
                requestQuota = window("requests"),
                retryAt = if (status == 429 && retry > 0) now + retry else null,
                status = status,
                updatedAt = now,
            )
        state.value = state.value.filterNot { it.identity == identity } + updated
        persist()
    }

    fun cooldown(identity: ModelIdentity, now: Long = System.currentTimeMillis()): Long {
        val entry = state.value.firstOrNull { it.identity == identity } ?: return 0
        return listOfNotNull(
                entry.retryAt,
                entry.tokens?.takeIf { it.remaining == 0L }?.resetAt,
                entry.requestQuota?.takeIf { it.remaining == 0L }?.resetAt,
            )
            .maxOrNull()
            ?.minus(now)
            ?.coerceAtLeast(0) ?: 0
    }

    @Synchronized
    fun clear() {
        state.value = emptyList()
        persist()
    }

    @Synchronized
    fun clear(provider: Provider) {
        state.value = state.value.filterNot { it.identity.provider == provider }
        persist()
    }

    private fun persist() {
        // API responses must remain usable even when optional usage storage is full.
        runCatching {
            file?.let {
                val pending = File(it.parentFile, "${it.name}.pending")
                pending.writeText(aiJson.encodeToString(state.value))
                if (!pending.renameTo(it)) pending.delete()
            }
        }
    }
}

internal fun durationMs(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    val matches = Regex("(\\d+(?:\\.\\d+)?)(ms|s|m|h|d)").findAll(value).toList()
    if (matches.isEmpty() || matches.joinToString("") { it.value } != value) return null
    return matches.sumOf {
        (it.groupValues[1].toDouble() *
                when (it.groupValues[2]) {
                    "ms" -> 1
                    "s" -> 1000
                    "m" -> 60_000
                    "h" -> 3_600_000
                    else -> 86_400_000
                })
            .toLong()
    }
}

internal fun retryDelayMs(header: String?, response: JsonObject?, now: Long): Long {
    header?.toDoubleOrNull()?.let {
        return (it * 1000).toLong().coerceAtLeast(0)
    }
    if (header != null) {
        runCatching {
                ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toEpochMilli() - now
            }
            .getOrNull()
            ?.let {
                return it.coerceAtLeast(0)
            }
    }
    val details = (response?.get("error") as? JsonObject)?.get("details") as? JsonArray
    return details?.firstNotNullOfOrNull {
        durationMs((it as? JsonObject)?.get("retryDelay")?.jsonPrimitive?.contentOrNull)
    } ?: 0
}
