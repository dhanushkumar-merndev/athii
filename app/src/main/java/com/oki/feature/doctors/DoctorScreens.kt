package com.oki.feature.doctors

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oki.core.storage.*
import com.oki.core.ui.*
import com.oki.feature.tasks.SuggestionCard
import java.time.DayOfWeek

@Composable
fun AttendanceChip(doctor: Doctor, change: () -> Unit) {
    val present = doctor.attendanceStatus == Attendance.PRESENT
    val color by
        animateColorAsState(
            if (present) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.errorContainer,
            label = "attendance color",
        )
    AssistChip(
        onClick = change,
        label = { Text(if (present) "Present" else "Absent") },
        leadingIcon = {
            Icon(
                if (present) Icons.Outlined.CheckCircleOutline
                else Icons.Outlined.RemoveCircleOutline,
                null,
                Modifier.size(16.dp),
            )
        },
        colors = AssistChipDefaults.assistChipColors(containerColor = color),
    )
}

@Composable
fun DoctorsScreen(vm: DoctorsViewModel, details: (String) -> Unit) {
    val doctors by vm.doctors.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var attendance by rememberSaveable { mutableStateOf("All") }
    var department by rememberSaveable { mutableStateOf("") }
    var day by rememberSaveable { mutableStateOf("") }
    var toggling by remember { mutableStateOf<Doctor?>(null) }
    val filtered =
        remember(doctors, query, attendance, department, day) {
            doctors.orEmpty().filter {
                (query.isBlank() ||
                    "${it.doctorName} ${it.department} ${it.hospitalOrClinic}"
                        .contains(query, true)) &&
                    (attendance == "All" || it.attendanceStatus.name.equals(attendance, true)) &&
                    (department.isBlank() || it.department == department) &&
                    (day.isBlank() || day in it.workingDays)
            }
        }
    val searchSuggestions =
        remember(doctors) {
            doctors.orEmpty().flatMap { listOf(it.doctorName, it.department, it.hospitalOrClinic) }
        }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 100.dp),
    ) {
        item {
            Text(
                "${doctors?.count { it.attendanceStatus == Attendance.PRESENT } ?: 0} present today · Resets at midnight",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
                InlineAutocompleteField(
                    value = query,
                    change = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search name, department, clinic") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    suggestions = searchSuggestions,
                )
        }
        item {
            DoctorFiltersRow(
                attendance,
                department,
                day,
                doctors
                    .orEmpty()
                    .map { it.department }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted(),
                { attendance = it },
                { department = it },
                { day = it },
            )
        }
        item { ErrorBanner(error) }
        if (doctors == null) item { CenterLoader() }
        else if (filtered.isEmpty())
            item {
                Box(
                    Modifier.fillParentMaxHeight(0.52f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        Icons.Outlined.MedicalServices,
                        "Your directory starts here",
                        "Add a doctor manually or scan a card. Adjust filters to see more results.",
                    )
                }
            }
        items(filtered, key = { it.id }) { doctor ->
            Surface(
                Modifier.animateItem().fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(
                    Modifier.clickable { details(doctor.id) }.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Text(
                                doctor.doctorName.take(1).uppercase(),
                                Modifier.padding(horizontal = 15.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(doctor.doctorName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                doctor.department.ifBlank { "Department not added" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        listOf(
                                doctor.roomOrOpdNumber
                                    .takeIf { it.isNotBlank() }
                                    ?.let { "Room $it" },
                                availability(doctor),
                            )
                            .filterNotNull()
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            doctor.workingDays
                                .joinToString(", ") {
                                    it.take(3).lowercase().replaceFirstChar(Char::uppercase)
                                }
                                .ifBlank { "Days not added" },
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        AttendanceChip(doctor) { toggling = doctor }
                    }
                }
            }
        }
    }
    toggling?.let { d ->
        AlertDialog(
            onDismissRequest = { toggling = null },
            title = {
                Text(
                    "Mark ${if (d.attendanceStatus == Attendance.PRESENT) "absent" else "present"}?"
                )
            },
            text = {
                Text(
                    "Update today's attendance for ${d.doctorName}. It resets to Present at local midnight."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.attendance(d)
                        toggling = null
                    }
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = { TextButton(onClick = { toggling = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun DoctorFiltersRow(
    attendance: String,
    department: String,
    day: String,
    departments: List<String>,
    selectAttendance: (String) -> Unit,
    selectDepartment: (String) -> Unit,
    selectDay: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DoctorFilterMenu(
            "Attendance",
            attendance,
            listOf("All" to "All", "Present" to "Present", "Absent" to "Absent"),
            Modifier.weight(1f),
            selectAttendance,
        )
        DoctorFilterMenu(
            "Department",
            department.ifBlank { "Dept." },
            listOf("" to "All departments") + departments.map { it to it },
            Modifier.weight(1f),
            selectDepartment,
        )
        DoctorFilterMenu(
            "Working day",
            day.take(3).lowercase().replaceFirstChar(Char::uppercase).ifBlank { "Day" },
            listOf("" to "All days") +
                DayOfWeek.entries.map {
                    it.name to it.name.lowercase().replaceFirstChar(Char::uppercase)
                },
            Modifier.weight(1f),
            selectDay,
        )
    }
}

@Composable
private fun DoctorFilterMenu(
    label: String,
    selectedLabel: String,
    options: List<Pair<String, String>>,
    modifier: Modifier,
    select: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("doctor-filter-$label")
                    .semantics { contentDescription = "$label: $selectedLabel" },
            contentPadding = PaddingValues(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(
                selectedLabel,
                Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
        }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { (value, title) ->
                DropdownMenuItem(
                    text = { Text(title) },
                    onClick = {
                        select(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

fun availability(d: Doctor): String =
    when {
        d.availableFrom.isNotBlank() && d.availableUntil.isNotBlank() ->
            "${d.availableFrom}–${d.availableUntil}"
        d.availableFrom.isNotBlank() -> "From ${d.availableFrom}"
        d.availableUntil.isNotBlank() -> "Until ${d.availableUntil}"
        else -> "Timing not added"
    }

@Composable
fun DoctorDetailScreen(id: String, vm: DoctorsViewModel, edit: () -> Unit, back: () -> Unit) {
    val doctors by vm.doctors.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val doctor = doctors?.firstOrNull { it.id == id }
    var deleting by remember { mutableStateOf(false) }
    var toggling by remember { mutableStateOf(false) }
    if (doctor == null) {
        if (doctors == null) CenterLoader(Modifier.fillMaxSize())
        else
            EmptyState(
                Icons.Outlined.PersonOff,
                "Doctor no longer available",
                "Return to the directory to choose another doctor.",
            )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            PageHeading(doctor.doctorName, doctor.department.ifBlank { "Your doctor directory" })
            AttendanceChip(doctor) { toggling = true }
        }
        val fields =
            listOf(
                "Qualification" to doctor.qualification,
                "Department" to doctor.department,
                "Hospital / clinic" to doctor.hospitalOrClinic,
                "Room / OPD" to doctor.roomOrOpdNumber,
                "Availability" to availability(doctor),
                "Working days" to doctor.workingDays.joinToString(", "),
                "Phone" to doctor.phone,
                "Notes" to doctor.notes,
            )
        items(fields) { (label, value) ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(value.ifBlank { "Not added" })
                }
            }
        }
        item { ErrorBanner(error) }
        item { Button(onClick = edit, modifier = Modifier.fillMaxWidth()) { Text("Edit doctor") } }
        item {
            TextButton(onClick = { deleting = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Delete doctor", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (deleting)
        ConfirmDelete(
            "Delete doctor?",
            "This will permanently remove ${doctor.doctorName} from this device.",
            { deleting = false },
            { vm.delete(id, back) },
        )
    if (toggling)
        AlertDialog(
            onDismissRequest = { toggling = false },
            title = { Text("Change today's attendance?") },
            text = {
                Text(
                    "Mark ${doctor.doctorName} ${if (doctor.attendanceStatus == Attendance.PRESENT) "Absent" else "Present"}."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.attendance(doctor)
                        toggling = false
                    }
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = { TextButton(onClick = { toggling = false }) { Text("Cancel") } },
        )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DoctorEditorScreen(
    vm: DoctorEditorViewModel,
    editing: Boolean,
    savedBack: (() -> Unit)? = null,
    back: () -> Unit,
) {
    val doctor by vm.form.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val autocomplete by vm.autocomplete.collectAsStateWithLifecycle()
    LaunchedEffect(saved) { if (saved) (savedBack ?: back)() }
    val d = doctor
    if (d == null) {
        ErrorBanner(error)
        if (busy) CenterLoader(Modifier.fillMaxSize())
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().imePadding().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            PageHeading(
                if (editing) "Keep the details current." else "A familiar face.",
                "Add what you know. Leave unknown details blank.",
            )
        }
        if (vm.isReview)
            item {
                SuggestionCard(
                    "Please review all scanned details. Blank fields are unknown; no qualifications or timings are assumed."
                )
            }
        item {
            Field(
                "Doctor name *",
                d.doctorName,
                { vm.change(d.copy(doctorName = it)) },
                suggestions = autocomplete.names,
            )
        }
        item {
            Field(
                "Qualification",
                d.qualification,
                { vm.change(d.copy(qualification = it)) },
                suggestions = autocomplete.qualifications,
            )
        }
        item {
            Field(
                "Department",
                d.department,
                { vm.change(d.copy(department = it)) },
                suggestions = autocomplete.departments,
            )
        }
        item {
            Field(
                "Room / OPD number",
                d.roomOrOpdNumber,
                { vm.change(d.copy(roomOrOpdNumber = it)) },
                suggestions = autocomplete.rooms,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(
                    "From · HH:mm",
                    d.availableFrom,
                    { vm.change(d.copy(availableFrom = it)) },
                    Modifier.weight(1f),
                    suggestions = autocomplete.fromTimes,
                )
                Field(
                    "Until · HH:mm",
                    d.availableUntil,
                    { vm.change(d.copy(availableUntil = it)) },
                    Modifier.weight(1f),
                    suggestions = autocomplete.untilTimes,
                )
            }
        }
        item {
            SectionLabel("Working days")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DayOfWeek.entries.forEach { day ->
                    FilterChip(
                        day.name in d.workingDays,
                        {
                            vm.change(
                                d.copy(
                                    workingDays =
                                        if (day.name in d.workingDays) d.workingDays - day.name
                                        else d.workingDays + day.name
                                )
                            )
                        },
                        label = { Text(day.name.take(3)) },
                    )
                }
            }
        }
        item {
            Field(
                "Hospital / clinic",
                d.hospitalOrClinic,
                { vm.change(d.copy(hospitalOrClinic = it)) },
                suggestions = autocomplete.hospitals,
            )
        }
        item {
            Field(
                "Phone (optional)",
                d.phone,
                { vm.change(d.copy(phone = it)) },
                suggestions = autocomplete.phones,
            )
        }
        item {
            Field(
                "Notes",
                d.notes,
                { vm.change(d.copy(notes = it)) },
                multiline = true,
                suggestions = autocomplete.notes,
            )
        }
        if (editing)
            item {
                SectionLabel("Today's attendance")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Attendance.entries.forEach { a ->
                        FilterChip(
                            d.attendanceStatus == a,
                            { vm.change(d.copy(attendanceStatus = a)) },
                            label = { Text(a.name.lowercase().replaceFirstChar(Char::uppercase)) },
                        )
                    }
                }
            }
        item { ErrorBanner(error) }
        item {
            Button(
                onClick = vm::save,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                if (busy)
                    Loader2Circle(
                        Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                else Text("Save Doctor")
            }
        }
        item { TextButton(onClick = back, modifier = Modifier.fillMaxWidth()) { Text("Cancel") } }
    }
}
