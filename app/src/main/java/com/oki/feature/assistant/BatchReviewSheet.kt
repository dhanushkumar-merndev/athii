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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.DayOfWeek

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
                    val enabled = !draft.saved && draft.id !in saving
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                "${index + 1}. ${draft.title}",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (draft.saved) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Outlined.CheckCircle,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Saved on this device")
                                }
                            } else {
                                draft.task?.let { task ->
                                    ReviewField("Title", task.title, enabled) {
                                        vm.changeDraft(draft.copy(task = task.copy(title = it)))
                                    }
                                    ReviewField("Date · YYYY-MM-DD", task.date, enabled) {
                                        vm.changeDraft(draft.copy(task = task.copy(date = it)))
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ReviewField(
                                            "Start · HH:mm",
                                            task.startTime ?: task.time,
                                            enabled,
                                            Modifier.weight(1f),
                                        ) {
                                            vm.changeDraft(
                                                draft.copy(
                                                    task = task.copy(time = it, startTime = it)
                                                )
                                            )
                                        }
                                        ReviewField(
                                            "End · optional",
                                            task.endTime,
                                            enabled,
                                            Modifier.weight(1f),
                                        ) {
                                            vm.changeDraft(
                                                draft.copy(task = task.copy(endTime = it))
                                            )
                                        }
                                    }
                                    ReviewField("Notes", task.notes, enabled) {
                                        vm.changeDraft(draft.copy(task = task.copy(notes = it)))
                                    }
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("Reminder at start")
                                        Switch(
                                            draft.reminderEnabled,
                                            { vm.changeDraft(draft.copy(reminderEnabled = it)) },
                                            enabled = enabled,
                                        )
                                    }
                                    Text(
                                        "Missing dates and times need your review. For a past task, choose a future time or turn off its reminder.",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                draft.doctor?.let { doctor ->
                                    ReviewField("Doctor name", doctor.doctorName, enabled) {
                                        vm.changeDraft(
                                            draft.copy(doctor = doctor.copy(doctorName = it))
                                        )
                                    }
                                    ReviewField("Qualification", doctor.qualification, enabled) {
                                        vm.changeDraft(
                                            draft.copy(doctor = doctor.copy(qualification = it))
                                        )
                                    }
                                    ReviewField("Department", doctor.department, enabled) {
                                        vm.changeDraft(
                                            draft.copy(doctor = doctor.copy(department = it))
                                        )
                                    }
                                    ReviewField("Room / OPD", doctor.roomOrOpdNumber, enabled) {
                                        vm.changeDraft(
                                            draft.copy(doctor = doctor.copy(roomOrOpdNumber = it))
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ReviewField(
                                            "From · HH:mm",
                                            doctor.availableFrom,
                                            enabled,
                                            Modifier.weight(1f),
                                        ) {
                                            vm.changeDraft(
                                                draft.copy(doctor = doctor.copy(availableFrom = it))
                                            )
                                        }
                                        ReviewField(
                                            "Until · HH:mm",
                                            doctor.availableUntil,
                                            enabled,
                                            Modifier.weight(1f),
                                        ) {
                                            vm.changeDraft(
                                                draft.copy(
                                                    doctor = doctor.copy(availableUntil = it)
                                                )
                                            )
                                        }
                                    }
                                    Text(
                                        "Working days · leave empty if unknown",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        DayOfWeek.entries.forEach { day ->
                                            val selected = day.name in doctor.workingDays
                                            FilterChip(
                                                selected,
                                                {
                                                    vm.changeDraft(
                                                        draft.copy(
                                                            doctor =
                                                                doctor.copy(
                                                                    workingDays =
                                                                        if (selected)
                                                                            doctor.workingDays -
                                                                                day.name
                                                                        else
                                                                            doctor.workingDays +
                                                                                day.name
                                                                )
                                                        )
                                                    )
                                                },
                                                label = { Text(day.name.take(3)) },
                                                enabled = enabled,
                                            )
                                        }
                                    }
                                    ReviewField(
                                        "Hospital / clinic",
                                        doctor.hospitalOrClinic,
                                        enabled,
                                    ) {
                                        vm.changeDraft(
                                            draft.copy(doctor = doctor.copy(hospitalOrClinic = it))
                                        )
                                    }
                                    ReviewField("Phone", doctor.phone, enabled) {
                                        vm.changeDraft(draft.copy(doctor = doctor.copy(phone = it)))
                                    }
                                    ReviewField("Notes", doctor.notes, enabled) {
                                        vm.changeDraft(draft.copy(doctor = doctor.copy(notes = it)))
                                    }
                                }
                                errors[draft.id]?.let {
                                    Text(
                                        it,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Button(
                                    onClick = { vm.saveDraft(draft.id) },
                                    enabled = enabled,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        if (draft.id in saving) "Saving…"
                                        else if (draft.task != null) "Save task" else "Save doctor"
                                    )
                                }
                            }
                        }
                    }
                }
            }
            TextButton(onClick = dismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}

@Composable
private fun ReviewField(
    label: String,
    value: String?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    change: (String) -> Unit,
) {
    OutlinedTextField(
        value.orEmpty(),
        { change(it.take(4000)) },
        modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(label) },
        shape = RoundedCornerShape(12.dp),
        maxLines = if (label == "Notes") 5 else 2,
    )
}
