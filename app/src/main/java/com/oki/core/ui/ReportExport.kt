package com.oki.core.ui

import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.oki.OkiApplication
import com.oki.core.ai.friendlyError
import com.oki.core.export.ReportExporter
import com.oki.core.export.ReportKind
import com.oki.core.export.Reports
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private sealed interface ExportState {
    data object Idle : ExportState

    data object Picking : ExportState

    data object Working : ExportState

    data class Saved(val uri: Uri, val fileName: String, val location: String?) : ExportState

    data class Failed(val message: String) : ExportState
}

/** Icon button that asks for a date range and saves an Excel report with charts. */
@Composable
fun ExportReportButton(kind: ReportKind, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exporter = remember { (context.applicationContext as OkiApplication).container.reports }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<ExportState>(ExportState.Idle) }
    var pending by remember { mutableStateOf<ReportExporter.Report?>(null) }

    val createDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Reports.MIME)) {
            uri ->
            val report = pending
            pending = null
            if (uri == null || report == null) {
                state = ExportState.Idle
                return@rememberLauncherForActivityResult
            }
            scope.launch {
                state =
                    try {
                        exporter.writeTo(uri, report)
                        ExportState.Saved(uri, report.fileName, null)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ExportState.Failed(friendlyError(e))
                    }
            }
        }

    fun export(from: LocalDate, to: LocalDate) {
        state = ExportState.Working
        scope.launch {
            try {
                val report = exporter.build(kind, from, to)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val uri = exporter.saveToDownloads(report)
                    state = ExportState.Saved(uri, report.fileName, "Downloads/Athii")
                } else {
                    pending = report
                    createDocument.launch(report.fileName)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = ExportState.Failed(friendlyError(e))
            }
        }
    }

    IconButton(
        onClick = { state = ExportState.Picking },
        enabled = state != ExportState.Working,
        modifier = modifier,
    ) {
        if (state == ExportState.Working)
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        else Icon(Icons.Outlined.FileDownload, contentDescription = "Export ${kind.label}")
    }

    when (val current = state) {
        ExportState.Picking ->
            ReportRangeDialog(
                kind = kind,
                dismiss = { state = ExportState.Idle },
                confirm = ::export,
            )
        is ExportState.Saved ->
            AlertDialog(
                onDismissRequest = { state = ExportState.Idle },
                title = { Text("Report ready") },
                text = {
                    Text(
                        current.location?.let { "Saved ${current.fileName} to $it." }
                            ?: "Saved ${current.fileName}."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val view =
                                Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(current.uri, Reports.MIME)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            state =
                                try {
                                    context.startActivity(view)
                                    ExportState.Idle
                                } catch (_: ActivityNotFoundException) {
                                    ExportState.Failed(
                                        "No app on this phone opens Excel files. Use Share to send it to one."
                                    )
                                }
                        }
                    ) {
                        Text("Open")
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(
                            onClick = {
                                val send =
                                    Intent(Intent.ACTION_SEND)
                                        .setType(Reports.MIME)
                                        .putExtra(Intent.EXTRA_STREAM, current.uri)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                send.clipData = ClipData.newRawUri(current.fileName, current.uri)
                                context.startActivity(Intent.createChooser(send, "Share report"))
                                state = ExportState.Idle
                            }
                        ) {
                            Text("Share")
                        }
                        TextButton(onClick = { state = ExportState.Idle }) { Text("Done") }
                    }
                },
            )
        is ExportState.Failed ->
            AlertDialog(
                onDismissRequest = { state = ExportState.Idle },
                title = { Text("Export failed") },
                text = { Text(current.message) },
                confirmButton = {
                    TextButton(onClick = { state = ExportState.Idle }) { Text("OK") }
                },
            )
        else -> Unit
    }
}

private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

@Composable
private fun ReportRangeDialog(
    kind: ReportKind,
    dismiss: () -> Unit,
    confirm: (LocalDate, LocalDate) -> Unit,
) {
    val context = LocalContext.current
    val today = remember { LocalDate.now() }
    // Attendance cannot be recorded for days that have not happened yet.
    val latest = if (kind == ReportKind.ATTENDANCE) today else today.plusYears(1)
    var fromText by rememberSaveable { mutableStateOf(today.minusDays(29).toString()) }
    var toText by rememberSaveable { mutableStateOf(today.toString()) }
    val from = LocalDate.parse(fromText)
    val to = LocalDate.parse(toText)
    val problem = runCatching { Reports.validateRange(from, to) }.exceptionOrNull()?.message
    val presets =
        listOf(
            "Last 7 days" to (today.minusDays(6) to today),
            "Last 30 days" to (today.minusDays(29) to today),
            "This month" to (today.withDayOfMonth(1) to today),
            "Last month" to
                (today.minusMonths(1).withDayOfMonth(1) to today.withDayOfMonth(1).minusDays(1)),
        )

    fun pick(initial: LocalDate, earliest: LocalDate?, set: (LocalDate) -> Unit) {
        val zone = ZoneId.systemDefault()
        DatePickerDialog(
                context,
                { _, y, m, d -> set(LocalDate.of(y, m + 1, d)) },
                initial.year,
                initial.monthValue - 1,
                initial.dayOfMonth,
            )
            .apply {
                datePicker.maxDate = latest.atStartOfDay(zone).toInstant().toEpochMilli()
                earliest?.let {
                    datePicker.minDate = it.atStartOfDay(zone).toInstant().toEpochMilli()
                }
            }
            .show()
    }

    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Export ${kind.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Choose the dates to include. The Excel file has a Data sheet and an Analytics sheet with charts.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    presets.forEach { (label, range) ->
                        FilterChip(
                            selected = from == range.first && to == range.second,
                            onClick = {
                                fromText = range.first.toString()
                                toText = range.second.toString()
                            },
                            label = { Text(label) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DateBox("From", from, Modifier.weight(1f)) {
                        pick(from, null) { fromText = it.toString() }
                    }
                    DateBox("To", to, Modifier.weight(1f)) {
                        pick(to, from) { toText = it.toString() }
                    }
                }
                Text(
                    problem
                        ?: "${ChronoUnit.DAYS.between(from, to) + 1} days · ${
                            if (from.year == to.year) from.format(dayMonth) else from.format(dayMonthYear)
                        } – ${to.format(dayMonthYear)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (problem != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { confirm(from, to) }, enabled = problem == null) {
                Text("Export")
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DateBox(label: String, date: LocalDate, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Outlined.CalendarToday,
                    null,
                    Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(date.format(dayMonthYear), style = MaterialTheme.typography.titleMedium)
            Text(
                date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
