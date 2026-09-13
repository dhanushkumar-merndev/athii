package com.oki.feature.assistant

import com.oki.core.storage.*
import com.oki.feature.doctors.DoctorRepository
import com.oki.feature.tasks.TaskRepository
import java.time.LocalDate

internal fun draftAlertMode(value: String?): TaskAlertMode =
    if (value.equals("ALARM", ignoreCase = true)) TaskAlertMode.ALARM
    else TaskAlertMode.NOTIFICATION

/** Called exclusively by an explicit per-item Save action. Stable draft IDs make retries safe. */
class ChatDraftSaver(private val tasks: TaskRepository, private val doctors: DoctorRepository) {
    suspend fun save(draft: ChatDraft) {
        require((draft.task == null) != (draft.doctor == null)) { "Invalid draft." }
        draft.task?.let { tasks.save(reviewTask(draft)) }
        draft.doctor?.let { doctors.save(reviewDoctor(draft)) }
    }
}

internal fun reviewTask(draft: ChatDraft): Task {
    val task = requireNotNull(draft.task)
    require(!task.title.isNullOrBlank()) { "Enter a task title." }
    val time = task.startTime?.takeIf { it.isNotBlank() } ?: task.time.orEmpty()
    // A task mentioned without a date means today.
    val date = task.date?.takeIf { it.isNotBlank() } ?: LocalDate.now().toString()
    val due =
        try {
            TimeRules.parseDue(date, time)
        } catch (_: Exception) {
            throw IllegalArgumentException(
                "Choose a valid date (YYYY-MM-DD) and start time (HH:mm)."
            )
        }
    TimeRules.endAt(due, task.endTime)
    return Task(
        id = draft.id,
        title = task.title,
        notes = task.notes.orEmpty(),
        dueAt = due,
        startTime = time,
        endTime = task.endTime?.takeIf { it.isNotBlank() },
        source = Source.AI_CHAT,
        reminderEnabled = draft.reminderEnabled,
        alertMode = draftAlertMode(task.alertMode),
        reminderOffsetMinutes = task.reminderOffsetMinutes ?: 0,
    )
}

internal fun reviewDoctor(draft: ChatDraft): Doctor {
    val doctor = requireNotNull(draft.doctor)
    require(!doctor.doctorName.isNullOrBlank()) { "Enter the doctor's name." }
    return Doctor(
        id = draft.id,
        doctorName = doctor.doctorName,
        qualification = doctor.qualification.orEmpty(),
        department = doctor.department.orEmpty(),
        roomOrOpdNumber = doctor.roomOrOpdNumber.orEmpty(),
        availableFrom = doctor.availableFrom.orEmpty(),
        availableUntil = doctor.availableUntil.orEmpty(),
        workingDays = doctor.workingDays,
        hospitalOrClinic = doctor.hospitalOrClinic.orEmpty(),
        phone = doctor.phone.orEmpty(),
        notes = doctor.notes.orEmpty(),
        source = Source.AI_CHAT,
    )
}
