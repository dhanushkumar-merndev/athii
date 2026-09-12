package com.oki.feature.tasks

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oki.core.storage.Task
import com.oki.core.storage.TaskAlertMode
import com.oki.core.ui.*
import java.time.*

private enum class TaskListFilter {
    UPCOMING,
    OVERDUE,
    COMPLETED,
}

@Composable
fun TasksScreen(vm: TasksViewModel, edit: (String) -> Unit) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val milestone by vm.celebrationMilestone.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(TaskListFilter.UPCOMING) }
    var deleting by remember { mutableStateOf<Task?>(null) }
    val now = System.currentTimeMillis()
    val filtered =
        remember(tasks, query, filter, now) {
            tasks.orEmpty().filter {
                when (filter) {
                    TaskListFilter.UPCOMING -> !it.isCompleted && it.dueAt >= now
                    TaskListFilter.OVERDUE -> !it.isCompleted && it.dueAt < now
                    TaskListFilter.COMPLETED -> it.isCompleted
                } && (it.title.contains(query, true) || it.notes.contains(query, true))
            }
        }
    val searchSuggestions =
        remember(tasks) { tasks.orEmpty().flatMap { listOf(it.title, it.notes) } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 100.dp),
        ) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(22.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "ON YOUR LIST",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            AnimatedContent(
                                tasks?.count { !it.isCompleted && it.dueAt >= now } ?: 0,
                                label = "task count",
                            ) {
                                Text(
                                    "$it tasks ahead",
                                    style = MaterialTheme.typography.headlineSmall,
                                )
                            }
                        }
                        Icon(
                            Icons.Outlined.CheckCircleOutline,
                            null,
                            Modifier.size(34.dp),
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
            item {
                Column {
                    InlineAutocompleteField(
                        value = query,
                        change = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search tasks") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        suggestions = searchSuggestions,
                    )
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TaskListFilter.entries.forEach { option ->
                        FilterChip(
                            selected = filter == option,
                            onClick = { filter = option },
                            modifier = Modifier.weight(1f),
                            colors =
                                FilterChipDefaults.filterChipColors(
                                    selectedContainerColor =
                                        MaterialTheme.colorScheme.secondaryContainer,
                                    selectedLabelColor =
                                        MaterialTheme.colorScheme.onSecondaryContainer,
                                ),
                            label = {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text(option.name.lowercase().replaceFirstChar(Char::uppercase))
                                }
                            },
                        )
                    }
                }
            }
            item { ErrorBanner(error) }
            if (tasks == null) item { CenterLoader() }
            else if (filtered.isEmpty())
                item {
                    Box(
                        Modifier.fillParentMaxHeight(0.52f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyState(
                            Icons.Outlined.TaskAlt,
                            if (query.isNotBlank()) "No matching tasks"
                            else
                                when (filter) {
                                    TaskListFilter.COMPLETED -> "A fresh start"
                                    TaskListFilter.OVERDUE -> "Nothing is overdue"
                                    TaskListFilter.UPCOMING -> "Space for what matters"
                                },
                            when (filter) {
                                TaskListFilter.COMPLETED -> "Completed tasks will appear here."
                                TaskListFilter.OVERDUE ->
                                    "Tasks past their due time will appear here."
                                TaskListFilter.UPCOMING -> "Tap + to add a task or scan a note."
                            },
                        )
                    }
                }
            items(filtered, key = { it.id }) { task ->
                Surface(
                    modifier = Modifier.animateItem().fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(
                        Modifier.clickable { edit(task.id) }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RoundedTaskCheckbox(task.isCompleted) { vm.complete(task) }
                        Spacer(Modifier.width(16.dp))
                        Column(
                            Modifier.weight(1f).padding(vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                task.title,
                                style = MaterialTheme.typography.titleMedium,
                                textDecoration =
                                    if (task.isCompleted) TextDecoration.LineThrough else null,
                            )
                            Text(
                                "Starts ${displayDate(task.dueAt)}" +
                                    task.endTime
                                        ?.takeIf(String::isNotBlank)
                                        ?.let { " · Ends $it" }
                                        .orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (task.reminderEnabled && !task.isCompleted)
                                Text(
                                    if (task.scheduledReminderAt != null)
                                        "${if (task.alertMode == TaskAlertMode.ALARM) "Alarm" else "Start notification"} · ${displayDate(task.scheduledReminderAt)}"
                                    else if (task.scheduledEndReminderAt != null)
                                        "End notification · ${displayDate(task.scheduledEndReminderAt)}"
                                    else "Notifications delivered or expired",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                        }
                        IconButton(onClick = { deleting = task }) {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                "Delete ${task.title}",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        deleting?.let { task ->
            ConfirmDelete(
                "Delete task?",
                "Permanently remove ${task.title} and its reminder from this device?",
                { deleting = null },
                { vm.delete(task.id) },
            )
        }
        ConfettiCelebration(milestone = milestone, onFinished = vm::dismissCelebration)
    }
}

@Composable
private fun RoundedTaskCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Surface(
        shape = CircleShape,
        color =
            if (checked) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface,
        border =
            BorderStroke(
                1.5.dp,
                if (checked) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.outline,
            ),
        modifier =
            Modifier.size(28.dp)
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (checked)
                Icon(
                    Icons.Outlined.Check,
                    null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
        }
    }
}

@Composable
fun TaskEditorScreen(
    vm: TaskEditorViewModel,
    back: () -> Unit,
    notificationsAllowed: () -> Boolean,
    exactAllowed: () -> Boolean,
    settings: () -> Unit,
    savedBack: () -> Unit = back,
) {
    val form by vm.form.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val autocomplete by vm.autocomplete.collectAsStateWithLifecycle()
    var pastDialog by remember { mutableStateOf(false) }
    var permissionWarning by remember { mutableStateOf(false) }
    var alarmAccessWarning by remember { mutableStateOf(false) }
    val notificationRequest =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            permissionWarning = true
        }
    LaunchedEffect(saved) { if (saved) savedBack() }
    fun save() {
        if (
            form?.let { it.reminder && it.alertMode == TaskAlertMode.ALARM } == true &&
                (!notificationsAllowed() || !exactAllowed())
        ) {
            alarmAccessWarning = true
            return
        }
        if (vm.reminderInPast()) pastDialog = true else vm.save()
    }
    val f = form
    if (f == null) {
        ErrorBanner(error)
        if (busy) CenterLoader(Modifier.fillMaxSize())
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().imePadding().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item { PageHeading("Make a little plan.", "Review the details, then save your task.") }
        if (f.review)
            item {
                SuggestionCard(
                    "Please review every extracted field. Missing dates or times need your input."
                )
            }
        item {
            Field(
                "Task title *",
                f.title,
                { vm.change(f.copy(title = it)) },
                suggestions = autocomplete.titles,
            )
        }
        item {
            Field(
                "Notes",
                f.notes,
                { vm.change(f.copy(notes = it)) },
                multiline = true,
                suggestions = autocomplete.notes,
            )
        }
        item {
            DateTimeFields(
                f.date,
                f.time,
                { vm.change(f.copy(date = it)) },
                { vm.change(f.copy(time = it)) },
            )
        }
        item {
            EndTimeField(endTime = f.endTime, endTimeChange = { vm.change(f.copy(endTime = it)) })
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Task alert", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (f.alertMode == TaskAlertMode.ALARM)
                            "Ring at the start time. Stop or snooze inside Athii."
                        else if (f.endTime.isBlank())
                            "Notify at the start time, with your notification sound."
                        else "Notify at the start and when the task time is over.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(f.reminder, { vm.change(f.copy(reminder = it)) })
            }
        }
        if (f.reminder) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TaskAlertMode.entries.forEach { mode ->
                        FilterChip(
                            selected = f.alertMode == mode,
                            onClick = { vm.change(f.copy(alertMode = mode)) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            label = {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text(
                                        if (mode == TaskAlertMode.ALARM) "Alarm" else "Notification"
                                    )
                                }
                            },
                        )
                    }
                }
                if (f.alertMode == TaskAlertMode.ALARM)
                    Text(
                        "Uses your phone’s alarm volume. An optional end time sends a normal notification.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
            }
            if (!exactAllowed())
                item {
                    SuggestionCard(
                        if (f.alertMode == TaskAlertMode.ALARM)
                            "Allow on-time alerts in Settings before saving an alarm."
                        else
                            "Android may delay notifications. Allow on-time notifications in Settings to use the times you choose."
                    )
                    TextButton(onClick = settings) { Text("Notification settings") }
                }
        }
        item { ErrorBanner(error) }
        item {
            Button(
                onClick = {
                    if (f.reminder && !notificationsAllowed()) {
                        if (Build.VERSION.SDK_INT >= 33)
                            notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else permissionWarning = true
                    } else save()
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                if (busy)
                    Loader2Circle(
                        Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                else Text("Save Task")
            }
        }
        item { TextButton(onClick = back, modifier = Modifier.fillMaxWidth()) { Text("Cancel") } }
    }
    if (alarmAccessWarning)
        AlertDialog(
            onDismissRequest = { alarmAccessWarning = false },
            title = { Text("Enable alarm access") },
            text = {
                Text(
                    "Alarms need notification permission and on-time alert access. Enable them in Settings, then save your task."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        alarmAccessWarning = false
                        settings()
                    }
                ) {
                    Text("Open settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { alarmAccessWarning = false }) { Text("Cancel") }
            },
        )
    if (permissionWarning)
        AlertDialog(
            onDismissRequest = { permissionWarning = false },
            title = { Text("Task notifications") },
            text = {
                Text(
                    if (notificationsAllowed())
                        "Notifications are enabled. Save your task to schedule its start and optional end notification."
                    else
                        "Notifications are blocked in Android settings. Your task can still be saved, but notifications cannot appear until they are enabled."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        permissionWarning = false
                        save()
                    }
                ) {
                    Text("Continue saving")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        permissionWarning = false
                        settings()
                    }
                ) {
                    Text("Settings")
                }
            },
        )
    if (pastDialog)
        AlertDialog(
            onDismissRequest = { pastDialog = false },
            title = { Text("Start time has passed") },
            text = {
                Text(
                    if (f.alertMode == TaskAlertMode.ALARM)
                        "Ring now, change the start date or time, or save without an alert."
                    else "Notify now, change the start date or time, or save without notifications."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pastDialog = false
                        vm.save(notifyNow = true)
                    }
                ) {
                    Text(if (f.alertMode == TaskAlertMode.ALARM) "Ring now" else "Notify now")
                }
            },
            dismissButton = {
                Column {
                    TextButton(
                        onClick = {
                            pastDialog = false
                            vm.save(withoutReminder = true)
                        }
                    ) {
                        Text("Save without notifications")
                    }
                    TextButton(onClick = { pastDialog = false }) { Text("Change start time") }
                }
            },
        )
}

@Composable
fun SuggestionCard(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Info, null, Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun DateTimeFields(
    date: String,
    time: String,
    dateChange: (String) -> Unit,
    timeChange: (String) -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Field("Date · YYYY-MM-DD *", date, dateChange)
        Field("Start time · HH:mm *", time, timeChange)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    val d = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())
                    DatePickerDialog(
                            context,
                            { _, y, m, day -> dateChange(LocalDate.of(y, m + 1, day).toString()) },
                            d.year,
                            d.monthValue - 1,
                            d.dayOfMonth,
                        )
                        .show()
                },
            ) {
                Icon(Icons.Outlined.CalendarToday, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Pick date")
            }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    val t = runCatching { LocalTime.parse(time) }.getOrDefault(LocalTime.now())
                    TimePickerDialog(
                            context,
                            { _, h, m -> timeChange("%02d:%02d".format(h, m)) },
                            t.hour,
                            t.minute,
                            android.text.format.DateFormat.is24HourFormat(context),
                        )
                        .show()
                },
            ) {
                Icon(Icons.Outlined.Schedule, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Pick start")
            }
        }
    }
}

@Composable
fun EndTimeField(endTime: String, endTimeChange: (String) -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Field("End time · HH:mm (optional)", endTime, endTimeChange)
        Text(
            "Choose a later time on the same date to receive a task-ended notification.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    val t =
                        runCatching { LocalTime.parse(endTime) }
                            .getOrDefault(LocalTime.now().plusHours(1))
                    TimePickerDialog(
                            context,
                            { _, h, m -> endTimeChange("%02d:%02d".format(h, m)) },
                            t.hour,
                            t.minute,
                            android.text.format.DateFormat.is24HourFormat(context),
                        )
                        .show()
                },
            ) {
                Icon(Icons.Outlined.Schedule, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Pick end")
            }
            if (endTime.isNotBlank()) {
                TextButton(modifier = Modifier.weight(1f), onClick = { endTimeChange("") }) {
                    Text("Clear")
                }
            }
        }
    }
}
