package com.oki.core.export

import com.oki.core.storage.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Builds report workbooks from rows already fetched for the chosen range. */
object Reports {
    const val MAX_DAYS = 366L
    const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    private const val GREEN = "2E7D5B"
    private const val RED = "C0392B"
    private const val BLUE = "3B6EA5"
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val clock = DateTimeFormatter.ofPattern("HH:mm")

    fun validateRange(from: LocalDate, to: LocalDate) {
        require(!to.isBefore(from)) { "Choose an end date on or after the start date." }
        require(ChronoUnit.DAYS.between(from, to) + 1 <= MAX_DAYS) {
            "Choose a date range of up to $MAX_DAYS days."
        }
    }

    fun fileName(kind: String, from: LocalDate, to: LocalDate) = "Athii-$kind-${from}_to_$to.xlsx"

    private fun days(from: LocalDate, to: LocalDate) =
        generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()

    private fun weekday(date: LocalDate) =
        date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

    private fun rangeLine(from: LocalDate, to: LocalDate) =
        "Dates: $from to $to (${ChronoUnit.DAYS.between(from, to) + 1} days)"

    enum class TaskStatus(val label: String) {
        COMPLETED("Completed"),
        OVERDUE("Overdue"),
        UPCOMING("Upcoming"),
    }

    fun status(task: Task, now: Instant) =
        when {
            task.isCompleted -> TaskStatus.COMPLETED
            task.dueAt < now.toEpochMilli() -> TaskStatus.OVERDUE
            else -> TaskStatus.UPCOMING
        }

    /** On time means completed before the end of the task's start date. */
    fun completedOnTime(task: Task, zone: ZoneId): Boolean? {
        if (!task.isCompleted) return null
        val completedAt = task.completedAt ?: return null
        val dayEnd =
            Instant.ofEpochMilli(task.dueAt)
                .atZone(zone)
                .toLocalDate()
                .plusDays(1)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
        return completedAt < dayEnd
    }

    fun tasksWorkbook(
        tasks: List<Task>,
        from: LocalDate,
        to: LocalDate,
        now: Instant,
        zone: ZoneId,
    ): List<SheetSpec> {
        validateRange(from, to)
        val sorted = tasks.sortedBy { it.dueAt }
        val header =
            listOf(
                    "Title",
                    "Notes",
                    "Date",
                    "Weekday",
                    "Start",
                    "End",
                    "Status",
                    "Completion",
                    "Completed at",
                    "Alert",
                    "Source",
                    "Created",
                )
                .map { text(it, CellStyle.HEADER) }
        val dataRows =
            sorted.map { task ->
                val due = Instant.ofEpochMilli(task.dueAt).atZone(zone)
                listOf(
                    text(task.title),
                    text(task.notes),
                    text(due.toLocalDate().toString()),
                    text(weekday(due.toLocalDate())),
                    text(due.format(clock)),
                    text(task.endTime.orEmpty()),
                    text(status(task, now).label),
                    text(
                        when (completedOnTime(task, zone)) {
                            true -> "On time"
                            false -> "Late"
                            null -> if (task.isCompleted) "Not recorded" else ""
                        }
                    ),
                    text(
                        task.completedAt
                            ?.takeIf { task.isCompleted }
                            ?.let { Instant.ofEpochMilli(it).atZone(zone).format(stamp) }
                            .orEmpty()
                    ),
                    text(
                        when {
                            !task.reminderEnabled -> "Off"
                            task.alertMode == TaskAlertMode.ALARM -> "Alarm"
                            else -> "Notification"
                        }
                    ),
                    text(
                        when (task.source) {
                            Source.MANUAL -> "Manual"
                            Source.IMAGE_SCAN -> "Image scan"
                            Source.AI_CHAT -> "AI chat"
                        }
                    ),
                    text(Instant.ofEpochMilli(task.createdAt).atZone(zone).format(stamp)),
                )
            }
        val data =
            SheetSpec(
                "Data",
                listOf(header) +
                    dataRows.ifEmpty { listOf(listOf(text("No tasks start in this date range."))) },
                columnWidths =
                    listOf(28.0, 32.0, 12.0, 9.0, 8.0, 8.0, 11.0, 12.0, 17.0, 13.0, 11.0, 17.0),
                frozenRows = 1,
            )

        val counts = sorted.groupingBy { status(it, now) }.eachCount()
        val completed = counts[TaskStatus.COMPLETED] ?: 0
        val onTime = sorted.count { completedOnTime(it, zone) == true }
        val late = sorted.count { completedOnTime(it, zone) == false }
        val byDay = sorted.groupBy { Instant.ofEpochMilli(it.dueAt).atZone(zone).toLocalDate() }
        val rows = mutableListOf<List<Cell?>>()
        rows += listOf(text("Athii task report", CellStyle.TITLE))
        rows += listOf(text(rangeLine(from, to)))
        rows +=
            listOf(
                text(
                    "Generated ${now.atZone(zone).format(stamp)}. Tasks are grouped by start date; deleted tasks are not included."
                )
            )
        rows += emptyList<Cell?>()
        val statusHeader = rows.size
        rows += listOf(text("Status", CellStyle.HEADER), text("Tasks", CellStyle.HEADER))
        TaskStatus.entries.forEach { rows += listOf(text(it.label), number(counts[it] ?: 0)) }
        val statusLast = rows.size - 1
        rows += listOf(text("Total", CellStyle.BOLD), number(sorted.size, CellStyle.BOLD))
        rows += listOf(text("Completed on time"), number(onTime))
        rows += listOf(text("Completed late"), number(late))
        rows +=
            listOf(
                text("Completion rate"),
                number(
                    if (sorted.isEmpty()) 0.0 else completed.toDouble() / sorted.size,
                    CellStyle.PERCENT,
                ),
            )
        rows += emptyList<Cell?>()
        val dailyHeader = rows.size
        rows +=
            listOf("Date", "Weekday", "Tasks", "Completed", "Overdue", "Upcoming").map {
                text(it, CellStyle.HEADER)
            }
        days(from, to).forEach { day ->
            val list = byDay[day].orEmpty()
            rows +=
                listOf(
                    text(day.toString()),
                    text(weekday(day)),
                    number(list.size),
                    number(list.count { status(it, now) == TaskStatus.COMPLETED }),
                    number(list.count { status(it, now) == TaskStatus.OVERDUE }),
                    number(list.count { status(it, now) == TaskStatus.UPCOMING }),
                )
        }
        val analytics =
            SheetSpec(
                "Analytics",
                rows,
                columnWidths = listOf(20.0, 10.0, 9.0, 11.0, 10.0, 10.0, 3.0),
                charts =
                    listOf(
                        SheetChart(
                            "Tasks by status",
                            ChartType.PIE,
                            headerRow = statusHeader,
                            firstRow = statusHeader + 1,
                            lastRow = statusLast,
                            categoryColumn = 0,
                            series = listOf(ChartSeries(1, GREEN)),
                            sliceColors = listOf(GREEN, RED, BLUE),
                            anchorColumn = 7,
                            anchorRow = 0,
                            widthColumns = 8,
                            heightRows = 16,
                        ),
                        SheetChart(
                            "Tasks per day",
                            ChartType.COLUMN_STACKED,
                            headerRow = dailyHeader,
                            firstRow = dailyHeader + 1,
                            lastRow = rows.size - 1,
                            categoryColumn = 0,
                            series =
                                listOf(
                                    ChartSeries(3, GREEN),
                                    ChartSeries(4, RED),
                                    ChartSeries(5, BLUE),
                                ),
                            anchorColumn = 7,
                            anchorRow = 17,
                            widthColumns = 14,
                            heightRows = 20,
                        ),
                    ),
            )
        return listOf(data, analytics)
    }

    enum class Scheduled(val label: String) {
        YES("Yes"),
        NO("No"),
        NOT_SET("Not set"),
    }

    fun scheduled(log: AttendanceLog): Scheduled {
        val days = log.workingDays.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (days.isEmpty()) return Scheduled.NOT_SET
        return if (LocalDate.parse(log.date).dayOfWeek.name in days) Scheduled.YES else Scheduled.NO
    }

    fun attendanceWorkbook(
        logs: List<AttendanceLog>,
        from: LocalDate,
        to: LocalDate,
        now: Instant,
        zone: ZoneId,
        firstRecordedDate: LocalDate?,
    ): List<SheetSpec> {
        validateRange(from, to)
        val sorted = logs.sortedWith(compareBy({ it.date }, { it.doctorName.lowercase() }))
        val header =
            listOf("Date", "Weekday", "Doctor", "Department", "Status", "Scheduled working day")
                .map { text(it, CellStyle.HEADER) }
        val dataRows =
            sorted.map { log ->
                listOf(
                    text(log.date),
                    text(weekday(LocalDate.parse(log.date))),
                    text(log.doctorName),
                    text(log.department),
                    text(if (log.status == Attendance.PRESENT) "Present" else "Absent"),
                    text(scheduled(log).label),
                )
            }
        val data =
            SheetSpec(
                "Data",
                listOf(header) +
                    dataRows.ifEmpty {
                        listOf(listOf(text("No attendance was recorded in this date range.")))
                    },
                columnWidths = listOf(12.0, 9.0, 26.0, 22.0, 10.0, 22.0),
                frozenRows = 1,
            )

        val rows = mutableListOf<List<Cell?>>()
        rows += listOf(text("Athii doctor attendance report", CellStyle.TITLE))
        rows += listOf(text(rangeLine(from, to)))
        rows +=
            listOf(
                text(
                    "Generated ${now.atZone(zone).format(stamp)}. " +
                        (firstRecordedDate?.let {
                            "Attendance history is recorded from $it; earlier days have no record."
                        } ?: "Attendance history starts recording after this update.")
                )
            )
        rows += emptyList<Cell?>()
        val doctorHeader = rows.size
        rows +=
            listOf(
                    "Doctor",
                    "Department",
                    "Present days",
                    "Absent days",
                    "Absent on working days",
                    "Attendance %",
                )
                .map { text(it, CellStyle.HEADER) }
        val perDoctor =
            sorted
                .groupBy { it.doctorId }
                .values
                .map { entries ->
                    val latest = entries.maxBy { it.date }
                    Triple(latest, entries.count { it.status == Attendance.PRESENT }, entries)
                }
                .sortedBy { it.first.doctorName.lowercase() }
        perDoctor.forEach { (latest, present, entries) ->
            val absent = entries.size - present
            rows +=
                listOf(
                    text(latest.doctorName),
                    text(latest.department),
                    number(present),
                    number(absent),
                    number(
                        entries.count {
                            it.status == Attendance.ABSENT && scheduled(it) == Scheduled.YES
                        }
                    ),
                    number(present.toDouble() / entries.size, CellStyle.PERCENT),
                )
        }
        val doctorLast = rows.size - 1
        val totalPresent = sorted.count { it.status == Attendance.PRESENT }
        rows +=
            listOf(
                text("All doctors", CellStyle.BOLD),
                null,
                number(totalPresent, CellStyle.BOLD),
                number(sorted.size - totalPresent, CellStyle.BOLD),
                number(
                    sorted.count {
                        it.status == Attendance.ABSENT && scheduled(it) == Scheduled.YES
                    },
                    CellStyle.BOLD,
                ),
                number(
                    if (sorted.isEmpty()) 0.0 else totalPresent.toDouble() / sorted.size,
                    CellStyle.PERCENT,
                ),
            )
        rows += emptyList<Cell?>()
        val dailyHeader = rows.size
        rows +=
            listOf("Date", "Weekday", "Present", "Absent", "Doctors recorded").map {
                text(it, CellStyle.HEADER)
            }
        val byDate = sorted.groupBy { it.date }
        days(from, to).forEach { day ->
            val list = byDate[day.toString()].orEmpty()
            val present = list.count { it.status == Attendance.PRESENT }
            rows +=
                listOf(
                    text(day.toString()),
                    text(weekday(day)),
                    number(present),
                    number(list.size - present),
                    number(list.size),
                )
        }
        val charts = buildList {
            if (perDoctor.isNotEmpty())
                add(
                    SheetChart(
                        "Attendance by doctor",
                        ChartType.COLUMN_STACKED,
                        headerRow = doctorHeader,
                        firstRow = doctorHeader + 1,
                        lastRow = doctorLast,
                        categoryColumn = 0,
                        series = listOf(ChartSeries(2, GREEN), ChartSeries(3, RED)),
                        anchorColumn = 7,
                        anchorRow = 0,
                        widthColumns = 10,
                        heightRows = 18,
                    )
                )
            add(
                SheetChart(
                    "Attendance per day",
                    ChartType.COLUMN_STACKED,
                    headerRow = dailyHeader,
                    firstRow = dailyHeader + 1,
                    lastRow = rows.size - 1,
                    categoryColumn = 0,
                    series = listOf(ChartSeries(2, GREEN), ChartSeries(3, RED)),
                    anchorColumn = 7,
                    anchorRow = 19,
                    widthColumns = 14,
                    heightRows = 20,
                )
            )
        }
        val analytics =
            SheetSpec(
                "Analytics",
                rows,
                columnWidths = listOf(24.0, 20.0, 13.0, 12.0, 22.0, 13.0, 3.0),
                charts = charts,
            )
        return listOf(data, analytics)
    }
}
