package com.oki

import com.oki.core.ai.*
import com.oki.core.storage.ReasoningEffort
import com.oki.feature.assistant.*
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Explicit opt-in only (create app/build/live-ai.enabled): sends synthetic chat turns through the
 * real assistant prompt, tool schemas and draft handling to Groq. Nothing is saved; no key is
 * printed.
 */
class LiveAssistantFlowTest {
    private fun groqKey(): String {
        assumeTrue(File("build/live-ai.enabled").exists())
        val env = File("../.env").takeIf { it.exists() }?.readLines().orEmpty()
        val key =
            env.firstNotNullOfOrNull { line ->
                line
                    .substringAfter("GROQ_API_KEY=", "")
                    .ifBlank { line.substringAfter("GROK_API=", "") }
                    .trim()
                    .trim('"')
                    .ifBlank { null }
            }
        assumeTrue(key != null)
        return key!!
    }

    private fun repository(key: String): AssistantRepository {
        val http = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build()
        val transport = GroqTransport { model, messages, tools, repair, effort ->
            val body = buildJsonObject {
                put("model", model)
                put(
                    "messages",
                    if (repair)
                        JsonArray(
                            messages +
                                buildJsonObject {
                                    put("role", "system")
                                    put("content", "Return a valid assistant message or tool call.")
                                }
                        )
                    else messages,
                )
                put("temperature", 0.2)
                put("max_completion_tokens", if (tools.isNotEmpty()) 3000 else 768)
                if (model.contains("oss")) {
                    put("reasoning_effort", effort.name.lowercase())
                    put("include_reasoning", false)
                } else if (model.startsWith("qwen/")) put("reasoning_format", "hidden")
                if (tools.isNotEmpty()) {
                    put("tools", tools)
                    put("tool_choice", "auto")
                    put("parallel_tool_calls", false)
                }
            }
            withContext(Dispatchers.IO) {
                http
                    .newCall(
                        Request.Builder()
                            .url("https://api.groq.com/openai/v1/chat/completions")
                            .header("Authorization", "Bearer $key")
                            .header("User-Agent", "athii-live-test")
                            .post(body.toString().toRequestBody("application/json".toMediaType()))
                            .build()
                    )
                    .execute()
                    .use { response ->
                        if (!response.isSuccessful) throw ApiFailure(response.code)
                        val choice =
                            aiJson
                                .parseToJsonElement(response.body!!.string())
                                .jsonObject["choices"]!!
                                .jsonArray
                                .first()
                                .jsonObject
                        choice["message"]!!.jsonObject
                    }
            }
        }
        // No local records exist in this synthetic run; searches return nothing.
        val tools = AssistantToolExecutor { _, _ ->
            buildJsonObject { put("results", JsonArray(emptyList())) }
        }
        return AssistantRepository(AiRouter(transport), tools) { ReasoningEffort.HIGH }
    }

    @Test
    fun clarifiedCountDateAndAlarmProduceEveryDraft() = runBlocking {
        val repo = repository(groqKey())
        val first = "create 5 tasks for dance practice, all at the same time"
        val a1 = repo.ask(first)
        val conversation =
            listOf(
                ChatMessage(text = first, user = true),
                ChatMessage(text = a1.text, user = false, drafts = a1.reviewDrafts),
            )
        val answer =
            if (a1.taskDrafts.size == 5 && a1.taskDrafts.all { (it.startTime ?: it.time) != null })
                a1
            else repo.ask("today at 11:59pm in alarm mode", conversation)
        println("live: first=${a1.taskDrafts.size} drafts, final=${answer.taskDrafts.size} drafts")
        assertEquals(5, answer.taskDrafts.size)
        answer.taskDrafts.forEach {
            assertEquals(LocalDate.now().toString(), it.date)
            assertEquals("23:59", it.startTime ?: it.time)
            assertEquals("ALARM", it.alertMode)
        }
    }

    @Test
    fun missingDateDefaultsToToday() = runBlocking {
        val repo = repository(groqKey())
        val answer = repo.ask("create a task to stretch at 11:58pm")
        println("live: drafts=${answer.taskDrafts.size} text=${answer.text.take(160)}")
        assertEquals(1, answer.taskDrafts.size)
        assertEquals(LocalDate.now().toString(), answer.taskDrafts.single().date)
    }

    @Test
    fun startThatAlreadyPassedTodayIsQuestionedNotDrafted() = runBlocking {
        assumeTrue(LocalTime.now().isAfter(LocalTime.of(0, 30)))
        val repo = repository(groqKey())
        val answer = repo.ask("create a task for dance today at 12am")
        println("live: drafts=${answer.taskDrafts.size} text=${answer.text.take(200)}")
        val now = LocalDateTime.now()
        answer.taskDrafts.forEach { draft ->
            val start =
                LocalDateTime.of(
                    LocalDate.parse(draft.date ?: LocalDate.now().toString()),
                    LocalTime.parse(requireNotNull(draft.startTime ?: draft.time)),
                )
            assertTrue("Draft start $start is in the past", start.isAfter(now))
        }
        if (answer.taskDrafts.isEmpty()) assertTrue(answer.text.contains("?"))
    }
}
