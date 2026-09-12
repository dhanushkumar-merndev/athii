package com.oki.feature.assistant

import com.oki.core.ai.*
import com.oki.feature.doctors.DoctorRepository
import com.oki.feature.tasks.TaskRepository
import java.time.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

fun interface AssistantToolExecutor {
    suspend fun execute(name: String, args: JsonObject): JsonElement
}

// Deliberately no write tool. This class cannot save, delete, or change attendance through AI.
class LocalAssistantToolExecutor(
    private val tasks: TaskRepository,
    private val doctors: DoctorRepository,
) : AssistantToolExecutor {
    override suspend fun execute(name: String, args: JsonObject): JsonElement {
        fun str(key: String) = args[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        fun boundary(key: String): Long? =
            str(key)
                .takeIf { it.isNotBlank() }
                ?.let {
                    LocalDate.parse(it)
                        .atStartOfDay(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                }
        return when (name) {
            "searchDoctors" -> {
                val day = str("day").uppercase().also { if (it.isNotEmpty()) DayOfWeek.valueOf(it) }
                val attendance =
                    str("attendance").uppercase().also {
                        require(it in listOf("", "PRESENT", "ABSENT"))
                    }
                val time = str("time").also { if (it.isNotEmpty()) LocalTime.parse(it) }
                val matches = doctors.search(str("query"), str("department"), attendance, day, time)
                aiJson.parseToJsonElement(
                    aiJson.encodeToString(matches.map { it.copy(notes = it.notes.take(300)) })
                )
            }
            "getDoctorById" ->
                doctors.get(str("id"))?.let { aiJson.parseToJsonElement(aiJson.encodeToString(it)) }
                    ?: JsonNull
            "searchTasks" -> {
                val matches =
                    tasks.search(
                        str("query"),
                        boundary("fromDate"),
                        boundary("untilDate"),
                        args["completed"]?.jsonPrimitive?.booleanOrNull,
                    )
                aiJson.parseToJsonElement(
                    aiJson.encodeToString(matches.map { it.copy(notes = it.notes.take(300)) })
                )
            }
            "getUpcomingTasks" ->
                aiJson.parseToJsonElement(
                    aiJson.encodeToString(
                        tasks.search(
                            from = System.currentTimeMillis(),
                            completed = false,
                            limit = 10,
                        )
                    )
                )
            "getTaskById" ->
                tasks.get(str("id"))?.let { aiJson.parseToJsonElement(aiJson.encodeToString(it)) }
                    ?: JsonNull
            else ->
                buildJsonObject { put("error", "Unknown or disallowed tool. No data was changed.") }
        }
    }

    companion object {
        val schemas = AssistantSchemas.all
    }
}
