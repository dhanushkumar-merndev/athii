package com.oki.feature.assistant

import kotlinx.serialization.json.*

internal object AssistantSchemas {
    private val taskFields =
        mapOf(
            "title" to
                "A concrete, useful task title. For random/example requests, choose an actual activity, never the literal words random task.",
            "notes" to "Helpful task details. Identify an example suggestion as a suggestion.",
            "date" to "YYYY-MM-DD. Resolve relative dates using device time. Omit when unknown.",
            "time" to
                "Required start time HH:mm; omit when unknown, do not invent a time unless the user asks you to plan or suggest one.",
            "startTime" to "Same HH:mm start time as time, if known.",
            "endTime" to
                "Optional HH:mm end time later than start on the same date. Omit if unknown.",
            "reminderOffsetMinutes" to "Use 0; the task reminder is at its start time.",
        )
    private val doctorFields =
        mapOf(
            "doctorName" to
                "Doctor name explicitly supplied by user or retrieved from a current local record.",
            "qualification" to "Qualification, omit if unknown",
            "department" to "Department, omit if unknown",
            "roomOrOpdNumber" to "Room or OPD number, omit if unknown",
            "availableFrom" to "Start time HH:mm, omit if unknown",
            "availableUntil" to "End time HH:mm, omit if unknown",
            "workingDays" to "Array of MONDAY through SUNDAY, omit if unknown",
            "hospitalOrClinic" to "Hospital or clinic, omit if unknown",
            "phone" to "Phone number, omit if unknown",
            "notes" to "Notes explicitly supplied by user, omit if unknown",
        )

    private fun parameters(fields: Map<String, String>) = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                fields.forEach { (field, description) ->
                    put(
                        field,
                        buildJsonObject {
                            put(
                                "type",
                                when (field) {
                                    "completed" -> "boolean"
                                    "reminderOffsetMinutes" -> "integer"
                                    "workingDays" -> "array"
                                    else -> "string"
                                },
                            )
                            if (field == "workingDays")
                                put(
                                    "items",
                                    buildJsonObject {
                                        put("type", "string")
                                        put(
                                            "enum",
                                            JsonArray(
                                                java.time.DayOfWeek.entries.map {
                                                    JsonPrimitive(it.name)
                                                }
                                            ),
                                        )
                                    },
                                )
                            if (field == "reminderOffsetMinutes")
                                put("enum", buildJsonArray { add(0) })
                            put("description", description)
                        },
                    )
                }
            },
        )
        put("additionalProperties", false)
    }

    private fun batchParameters(item: JsonObject) = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "drafts",
                    buildJsonObject {
                        put("type", "array")
                        put("items", item)
                        put("minItems", 1)
                        put("maxItems", 50)
                    },
                )
            },
        )
        put("required", buildJsonArray { add("drafts") })
        put("additionalProperties", false)
    }

    val all = buildJsonArray {
        fun tool(name: String, description: String, parameters: JsonObject) {
            add(
                buildJsonObject {
                    put("type", "function")
                    put(
                        "function",
                        buildJsonObject {
                            put("name", name)
                            put("description", description)
                            put("parameters", parameters)
                        },
                    )
                }
            )
        }
        tool(
            "searchDoctors",
            "Search current local doctors, at most 20 matches. Narrow queries. Missing days/times are unknown; attendance is separate from scheduled availability.",
            parameters(
                mapOf(
                    "query" to "Name or hospital",
                    "department" to "Department",
                    "day" to "MONDAY..SUNDAY",
                    "time" to "HH:mm",
                    "attendance" to "PRESENT or ABSENT",
                )
            ),
        )
        tool(
            "getDoctorById",
            "Read one current doctor by stable ID",
            parameters(mapOf("id" to "Doctor ID")),
        )
        tool(
            "searchTasks",
            "Search local tasks, at most 20 matches. Timestamps are Unix milliseconds.",
            parameters(
                mapOf(
                    "query" to "Title or notes",
                    "fromDate" to "Inclusive YYYY-MM-DD",
                    "untilDate" to "Exclusive YYYY-MM-DD",
                    "completed" to "Completion filter",
                )
            ),
        )
        tool("getUpcomingTasks", "Next 10 incomplete tasks from now", parameters(emptyMap()))
        tool("getTaskById", "Read one current task", parameters(mapOf("id" to "Task ID")))
        tool(
            "draftTask",
            "Propose one meaningful task for user review. Never saves it. Use draftTasks for multiple tasks.",
            parameters(taskFields),
        )
        tool(
            "draftDoctor",
            "Propose one doctor draft for review using only known facts. Never saves it. Use draftDoctors for multiple doctors.",
            parameters(doctorFields),
        )
        tool(
            "draftTasks",
            "Propose all requested tasks together, up to 50 per batch. Each task is editable and must be reviewed and saved individually. Never saves data. Use meaningful distinct activities for random/example planning requests.",
            batchParameters(parameters(taskFields)),
        )
        tool(
            "draftDoctors",
            "Propose all requested doctors together, up to 50 per batch. Each doctor must be reviewed and saved individually. Only include facts explicitly provided by the user or read from a current local record. Never invent names or medical details.",
            batchParameters(parameters(doctorFields)),
        )
    }
}
