package com.oki

import com.oki.core.ai.CHAT_MODELS
import com.oki.core.ai.ModelIdentity
import com.oki.core.ai.documentedFreeTierLimits
import com.oki.core.security.Provider
import org.junit.Assert.*
import org.junit.Test

class DocumentedLimitsTest {
    @Test
    fun groqModelsHavePublishedFreeTierCaps() {
        CHAT_MODELS.filter { it.provider == Provider.GROQ }
            .forEach {
                val limits = requireNotNull(documentedFreeTierLimits(it))
                assertEquals(8_000L, limits.tokensPerMinute)
                assertEquals(200_000L, limits.tokensPerDay)
                assertEquals(30L, limits.requestsPerMinute)
                assertEquals(1_000L, limits.requestsPerDay)
            }
    }

    @Test
    fun geminiAndUnknownModelsDoNotInventQuotas() {
        CHAT_MODELS.filter { it.provider == Provider.GEMINI }
            .forEach {
                val limits = requireNotNull(documentedFreeTierLimits(it))
                assertNull(limits.tokensPerMinute)
                assertNull(limits.tokensPerDay)
                assertNull(limits.requestsPerMinute)
                assertNull(limits.requestsPerDay)
            }
        assertNull(documentedFreeTierLimits(ModelIdentity(Provider.GROQ, "unknown")))
    }
}
