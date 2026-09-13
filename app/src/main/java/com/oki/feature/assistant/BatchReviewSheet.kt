package com.oki.feature.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oki.core.ai.DoctorDraft
import com.oki.core.ai.TaskDraft
import com.oki.core.storage.TaskAlertMode
import com.oki.core.storage.TimeRules
import com.oki.core.ui.Field
import com.oki.core.ui.SectionLabel
import com.oki.feature.tasks.DateTimeFields
import com.oki.feature.tasks.EndTimeField
import com.oki.feature.tasks.ReminderLeadTimeField
import com.oki.feature.tasks.SuggestionCard
import java.time.DayOfWeek
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BatchReviewSheet(vm: AssistantViewModel, message: ChatMessage, dismiss: () -> Unit) {
    val saving by vm.savingDrafts.collectAsStateWithLifecycle()
    val errors by vm.draftErrors.collectAsStateWithLifecycle()
    val drafts = message.reviewDrafts
    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxHeight(.94f).imePadding().padding(horizontal = 20.dp)) {
            Text("Review all items", style = MaterialTheme.typography.headlineSmall)
            Text(
                "${drafts.count { it.saved }} of ${drafts.size} saved · Edit and save each item independently",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { drafts.count { it.saved }.toFloat() / drafts.size.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(
                Modifier.weight(1f).testTag("batch-review-list"),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(drafts, key = { _, draft -> draft.id }) { index, draft ->
                    BatchReviewCard(
                        index = index,
                        draft = draft,
                        saving = draft.id in saving,
                        error = errors[draft.id],
                        change = vm::changeDraft,
                        save = { vm.saveDraft(draft.id) },
                    )
                }
            }
            TextButton(onClick = dismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}

@Composable
private fun BatchReviewCard(
    index: Int,
    draft: ChatDraft,
    saving: Boolean,
    error: String?,
    change: (ChatDraft) -> Unit,
    save: () -> Unit,
) {
    val enabled = !draft.saved && !saving
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().testTag("batch-review-item-${draft.id}"),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${index + 1}. ${draft.title.ifBlank { "Details to review" }}",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        if (draft.task != null) "TASK" else "DOCTOR",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            if (draft.saved) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Saved on this device")
                }
            }
            LockedWhen(!enabled) {
                draft.task?.let { TaskDraftFields(draft, it, change) }
                draft.doctor?.let { DoctorDraftFields(draft, it, change) }
            }
            if (!draft.saved) {
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    onClick = save,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) {
                    Text(
                        if (saving) "Saving…"
                        else if (draft.task != null) "Save task" else "Save doctor"
                    )
                }
            }
        }
    }
}

/**
 * The shared editor fields (Field, pickers, lead-time chips) have no `enabled` parameter, so a
 * saved or saving item is locked by dimming it and swallowing every pointer event above it.
 */
@Composable
private fun LockedWhen(locked: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().alpha(if (locked) .6f else 1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
        if (locked)
            Box(
                Modifier.matchParentSize().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial).changes.forEach {
                                it.consume()
                            }
                        }
                    }
                }
            )
    }
}

/** Mirrors TaskEditorScreen: title, notes, date/start, end, alert switch, mode and lead time. */
@Composable
private fun TaskDraftFields(draft: ChatDraft, task: TaskDraft, change: (ChatDraft) -> Unit) {
    fun update(transform: TaskDraft.() -> TaskDraft) = change(draft.copy(task = task.transform()))
    val start = task.startTime?.takeIf(String::isNotBlank) ?: task.time.orEmpty()
    val offset = task.reminderOffsetMinutes ?: 0
    val alertMode = draftAlertMode(task.alertMode)
    val now = System.currentTimeMillis()
    // Same defaults as the saver: a missing date means today, a missing lead time means at start.
    // Shown in the field too, so what the user reviews is what gets saved.
    val date = task.date?.takeIf(String::isNotBlank) ?: LocalDate.now().toString()
    val due = runCatching { TimeRules.parseDue(date, start) }.getOrNull()
    val startPassed = due != null && due <= now
    val leadTimePassed =
        due != null &&
            due > now &&
            runCatching { TimeRules.reminderAt(due, offset) <= now }.getOrDefault(false)

    Field("Task title *", task.title.orEmpty(), { update { copy(title = it.take(4000)) } })
    Field(
        "Notes",
        task.notes.orEmpty(),
        { update { copy(notes = it.take(4000)) } },
        multiline = true,
    )
    DateTimeFields(
        date,
        start,
        { update { copy(date = it) } },
        { update { copy(time = it, startTime = it) } },
    )
    EndTimeField(
        endTime = task.endTime.orEmpty(),
        endTimeChange = { update { copy(endTime = it) } },
    )
    if (draft.reminderEnabled && startPassed)
        SuggestionCard("This start time has passed. Choose a later time or turn off the alert.")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Task alert", style = MaterialTheme.typography.titleMedium)
            Text(
                if (alertMode == TaskAlertMode.ALARM)
                    "Ring before the start time. Mark done, snooze, or stop inside Athii."
                else if (task.endTime.isNullOrBlank())
                    "Notify at the start time, with your notification sound."
                else "Notify at the start and when the task time is over.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(draft.reminderEnabled, { change(draft.copy(reminderEnabled = it)) })
    }
    if (draft.reminderEnabled) {
        if (leadTimePassed)
            SuggestionCard(
                "The selected lead time has already passed, so this reminder will notify at the future start time."
            )
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TaskAlertMode.entries.forEach { mode ->
                    FilterChip(
                        selected = alertMode == mode,
                        onClick = { update { copy(alertMode = mode.name) } },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        label = {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(if (mode == TaskAlertMode.ALARM) "Alarm" else "Notification")
                            }
                        },
                    )
                }
            }
            if (alertMode == TaskAlertMode.ALARM)
                Text(
                    "Uses your phone’s alarm volume and your chosen alarm tone. An optional end time sends a normal notification.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
        }
        ReminderLeadTimeField(
            offset = offset.toString(),
            alarm = alertMode == TaskAlertMode.ALARM,
            change = { value ->
                update {
                    copy(reminderOffsetMinutes = value.take(6).toIntOrNull()?.coerceIn(0, 525600))
                }
            },
        )
    }
}

/** Mirrors DoctorEditorScreen's fields and labels. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DoctorDraftFields(draft: ChatDraft, doctor: DoctorDraft, change: (ChatDraft) -> Unit) {
    fun update(transform: DoctorDraft.() -> DoctorDraft) =
        change(draft.copy(doctor = doctor.transform()))
    Field("Doctor name *", doctor.doctorName.orEmpty(), { update { copy(doctorName = it) } })
    Field("Qualification", doctor.qualification.orEmpty(), { update { copy(qualification = it) } })
    Field("Department", doctor.department.orEmpty(), { update { copy(department = it) } })
    Field(
        "Room / OPD number",
        doctor.roomOrOpdNumber.orEmpty(),
        { update { copy(roomOrOpdNumber = it) } },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Field(
            "From · HH:mm",
            doctor.availableFrom.orEmpty(),
            { update { copy(availableFrom = it) } },
            Modifier.weight(1f),
        )
        Field(
            "Until · HH:mm",
            doctor.availableUntil.orEmpty(),
            { update { copy(availableUntil = it) } },
            Modifier.weight(1f),
        )
    }
    Column {
        SectionLabel("Working days")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DayOfWeek.entries.forEach { day ->
                val selected = day.name in doctor.workingDays
                FilterChip(
                    selected,
                    {
                        update {
                            copy(
                                workingDays =
                                    if (selected) workingDays - day.name else workingDays + day.name
                            )
                        }
                    },
                    label = { Text(day.name.take(3)) },
                )
            }
        }
    }
    Field(
        "Hospital / clinic",
        doctor.hospitalOrClinic.orEmpty(),
        { update { copy(hospitalOrClinic = it) } },
    )
    Field("Phone (optional)", doctor.phone.orEmpty(), { update { copy(phone = it) } })
    Field(
        "Notes",
        doctor.notes.orEmpty(),
        { update { copy(notes = it.take(4000)) } },
        multiline = true,
    )
}
