package com.oki.core.ai

import com.oki.core.security.Provider

/** Documentation snapshot, not a remaining balance or a routing/cooldown input. */
data class DocumentedLimits(
    val tokensPerMinute: Long? = null,
    val tokensPerDay: Long? = null,
    val requestsPerMinute: Long? = null,
    val requestsPerDay: Long? = null,
    val sourceUrl: String,
    val checkedOn: String = "2026-09-13",
)

private val freeTierLimits = buildMap {
    val groq =
        DocumentedLimits(
            tokensPerMinute = 8_000,
            tokensPerDay = 200_000,
            requestsPerMinute = 30,
            requestsPerDay = 1_000,
            sourceUrl = "https://console.groq.com/docs/rate-limits",
        )
    listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.6-27b", "qwen/qwen3.8-27b")
        .forEach { put(ModelIdentity(Provider.GROQ, it), groq) }
    // Google publishes project-specific limits in AI Studio. Paid batch-enqueued token
    // limits and model context windows are not free-tier token quotas.
    listOf("gemini-3.5-flash", "gemini-3.5-flash-lite").forEach {
        put(
            ModelIdentity(Provider.GEMINI, it),
            DocumentedLimits(sourceUrl = "https://ai.google.dev/gemini-api/docs/rate-limits"),
        )
    }
}

fun documentedFreeTierLimits(identity: ModelIdentity): DocumentedLimits? = freeTierLimits[identity]
