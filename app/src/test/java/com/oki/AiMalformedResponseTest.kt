package com.oki

import com.oki.core.ai.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AiMalformedResponseTest {
    @Test
    fun nonStringToolIdentifiersTriggerRepairInsteadOfCrashing() = runTest {
        val malformed =
            listOf(
                """{"id":{},"function":{"name":"searchTasks","arguments":"{}"}}""",
                """{"id":"call","function":{"name":[],"arguments":"{}"}}""",
                """{"id":123,"function":{"name":"searchTasks","arguments":"{}"}}""",
            )
        malformed.forEach { call ->
            val repairs = mutableListOf<Boolean>()
            val expected = buildJsonObject { put("content", "Recovered") }
            val router =
                AiRouter(
                    GroqTransport { _, _, _, repair, _ ->
                        repairs += repair
                        if (repairs.size == 1)
                            buildJsonObject {
                                put(
                                    "tool_calls",
                                    buildJsonArray { add(aiJson.parseToJsonElement(call)) },
                                )
                            }
                        else expected
                    }
                )
            assertEquals(expected, router.complete(JsonArray(emptyList()), JsonArray(emptyList())))
            assertEquals(listOf(false, true), repairs)
        }
    }
}
