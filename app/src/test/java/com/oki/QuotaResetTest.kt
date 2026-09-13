package com.oki

import com.oki.core.ai.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class QuotaResetTest {
    @Test
    fun knownResetReplenishesEstimateAtExactBoundaryWithoutChangingSnapshot() {
        val window = QuotaWindow(8_000, 0, 10_000)
        assertEquals(QuotaAvailability(0, false), window.availabilityAt(9_999))
        assertEquals(QuotaAvailability(8_000, true), window.availabilityAt(10_000))
        assertEquals(QuotaAvailability(8_000, true), window.availabilityAt(20_000))
        assertEquals(0L, window.remaining)
    }

    @Test
    fun unknownResetKeepsReportedBalanceAndDoesNotInventARefill() {
        assertEquals(
            QuotaAvailability(12, false),
            QuotaWindow(100, 12).availabilityAt(Long.MAX_VALUE),
        )
        assertEquals(QuotaAvailability(0, false), QuotaWindow(0, -1).availabilityAt(100))
    }

    @Test
    fun windowResetPreservesMeasuredTotalsAndNewResponseReplacesEstimate() {
        val store = AiUsageStore()
        val identity = CHAT_MODELS.first()
        val counts = buildJsonObject { put("usage", buildJsonObject { put("total_tokens", 40) }) }
        fun headers(remaining: String) =
            mapOf(
                "x-ratelimit-limit-tokens" to "8000",
                "x-ratelimit-remaining-tokens" to remaining,
                "x-ratelimit-reset-tokens" to "1s",
            )
        store.record(identity, 200, headers("0"), counts, 1_000)
        val before = store.usage.value.single()
        assertEquals(1_000L, store.cooldown(identity, 1_000))
        assertEquals(0L, store.cooldown(identity, 2_000))
        assertEquals(8_000L, before.tokens!!.availabilityAt(2_000).remaining)
        assertEquals(before, store.usage.value.single())
        store.record(identity, 200, headers("7960"), counts, 2_100)
        val after = store.usage.value.single()
        assertEquals(QuotaAvailability(7_960, false), after.tokens!!.availabilityAt(2_100))
        assertEquals(80L, after.totalTokens)
        assertEquals(2L, after.requests)
    }

    @Test
    fun malformedRetryMetadataDoesNotBreakResponseAccounting() {
        val store = AiUsageStore()
        val response =
            aiJson
                .parseToJsonElement(
                    """{"error":{"details":[null,{"retryDelay":{}},{"retryDelay":"2s"}]}}"""
                )
                .jsonObject
        store.record(CHAT_MODELS.first(), 429, emptyMap(), response, 1_000)
        assertEquals(3_000L, store.usage.value.single().retryAt)
        assertEquals(1L, store.usage.value.single().requests)
    }
}
