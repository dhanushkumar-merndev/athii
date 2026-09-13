package com.oki

import com.oki.core.export.*
import com.oki.core.storage.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.*
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class ReportExportTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = LocalDateTime.of(2026, 9, 13, 12, 0).atZone(zone).toInstant()

    private fun at(date: String, time: String) =
        LocalDateTime.parse("${date}T$time").atZone(zone).toInstant().toEpochMilli()

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val parts = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }
                .forEach { parts[it.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        return parts
    }

    private fun workbook(sheets: List<SheetSpec>, name: String): Map<String, String> {
        val out = ByteArrayOutputStream()
        XlsxWriter.write(sheets, out)
        // Kept for manual inspection in spreadsheet apps.
        File("build/test-output").apply { mkdirs() }.resolve(name).writeBytes(out.toByteArray())
        return unzip(out.toByteArray())
    }

    private fun assertWellFormed(parts: Map<String, String>) {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        parts.forEach { (name, body) ->
            if (name.endsWith(".xml") || name.endsWith(".rels"))
                runCatching { factory.newDocumentBuilder().parse(body.byteInputStream()) }
                    .onFailure { fail("$name is not well-formed: ${it.message}") }
        }
    }

    private val tasks =
        listOf(
            Task(
                title = "Done early",
                dueAt = at("2026-09-10", "09:00"),
                isCompleted = true,
                completedAt = at("2026-09-10", "08:00"),
            ),
            Task(
                title = "Done late",
                dueAt = at("2026-09-10", "18:00"),
                isCompleted = true,
                completedAt = at("2026-09-11", "07:00"),
            ),
            Task(title = "Missed & <escaped>", dueAt = at("2026-09-12", "10:00")),
            Task(title = "Tomorrow", dueAt = at("2026-09-14", "10:00"), reminderEnabled = false),
        )

    @Test
    fun taskWorkbookHasDataAndAnalyticsWithCharts() {
        val sheets =
            Reports.tasksWorkbook(
                tasks,
                LocalDate.parse("2026-09-10"),
                LocalDate.parse("2026-09-14"),
                now,
                zone,
            )
        assertEquals(listOf("Data", "Analytics"), sheets.map { it.name })
        assertEquals(5, sheets[0].rows.size)
        val parts = workbook(sheets, "athii-sample-tasks.xlsx")
        assertWellFormed(parts)
        assertTrue("xl/charts/chart1.xml" in parts && "xl/charts/chart2.xml" in parts)
        assertTrue(parts.getValue("xl/charts/chart1.xml").contains("<c:pieChart>"))
        assertTrue(
            parts.getValue("xl/charts/chart2.xml").contains("""<c:grouping val="stacked"/>""")
        )
        assertTrue(parts.getValue("xl/sharedStrings.xml").contains("Missed &amp; &lt;escaped&gt;"))
        assertTrue(parts.getValue("[Content_Types].xml").contains("/xl/drawings/drawing2.xml"))

        val analytics = sheets[1].rows
        fun count(label: String) =
            (analytics.first { (it.firstOrNull() as? TextCell)?.text == label }[1] as NumberCell)
                .value
        assertEquals(2.0, count("Completed"), 0.0)
        assertEquals(1.0, count("Overdue"), 0.0)
        assertEquals(1.0, count("Upcoming"), 0.0)
        assertEquals(1.0, count("Completed on time"), 0.0)
        assertEquals(1.0, count("Completed late"), 0.0)
        // Every day in the range gets a row, including days without tasks.
        val dailyDates =
            analytics
                .mapNotNull { (it.firstOrNull() as? TextCell)?.text }
                .filter { it.matches(Regex("""\d{4}-\d{2}-\d{2}""")) }
        assertEquals(
            listOf("2026-09-10", "2026-09-11", "2026-09-12", "2026-09-13", "2026-09-14"),
            dailyDates,
        )
        val pieCache = parts.getValue("xl/charts/chart1.xml")
        assertTrue(pieCache.contains("<c:v>Completed</c:v>") && pieCache.contains("<c:v>2</c:v>"))
    }

    @Test
    fun attendanceWorkbookSummarisesDoctorsAndDays() {
        val logs =
            listOf(
                AttendanceLog(
                    "2026-09-11",
                    "a",
                    "Dr Asha",
                    "Cardiology",
                    "FRIDAY",
                    Attendance.ABSENT,
                    1,
                ),
                AttendanceLog(
                    "2026-09-12",
                    "a",
                    "Dr Asha",
                    "Cardiology",
                    "FRIDAY",
                    Attendance.PRESENT,
                    1,
                ),
                AttendanceLog("2026-09-12", "b", "Dr Bala", "ENT", "", Attendance.PRESENT, 1),
            )
        val sheets =
            Reports.attendanceWorkbook(
                logs,
                LocalDate.parse("2026-09-10"),
                LocalDate.parse("2026-09-12"),
                now,
                zone,
                LocalDate.parse("2026-09-11"),
            )
        val parts = workbook(sheets, "athii-sample-attendance.xlsx")
        assertWellFormed(parts)
        assertEquals(2, sheets[1].charts.size)
        val data = sheets[0].rows
        assertEquals("Yes", (data[1][5] as TextCell).text)
        assertEquals("Not set", (data[3][5] as TextCell).text)
        val asha = sheets[1].rows.first { (it.firstOrNull() as? TextCell)?.text == "Dr Asha" }
        assertEquals(1.0, (asha[2] as NumberCell).value, 0.0)
        assertEquals(1.0, (asha[3] as NumberCell).value, 0.0)
        assertEquals(1.0, (asha[4] as NumberCell).value, 0.0)
        assertEquals(0.5, (asha[5] as NumberCell).value, 0.0)
    }

    @Test
    fun emptyAttendanceStillProducesAValidWorkbook() {
        val sheets =
            Reports.attendanceWorkbook(
                emptyList(),
                LocalDate.parse("2026-09-01"),
                LocalDate.parse("2026-09-01"),
                now,
                zone,
                null,
            )
        assertEquals(1, sheets[1].charts.size)
        assertWellFormed(workbook(sheets, "athii-sample-empty.xlsx"))
    }

    @Test
    fun rangeIsCappedAndOrdered() {
        Reports.validateRange(LocalDate.parse("2025-09-13"), LocalDate.parse("2026-09-13"))
        assertThrows(IllegalArgumentException::class.java) {
            Reports.validateRange(LocalDate.parse("2025-09-12"), LocalDate.parse("2026-09-13"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Reports.validateRange(LocalDate.parse("2026-09-13"), LocalDate.parse("2026-09-12"))
        }
    }

    @Test
    fun sheetHelpers() {
        assertEquals("A", XlsxWriter.columnName(0))
        assertEquals("Z", XlsxWriter.columnName(25))
        assertEquals("AA", XlsxWriter.columnName(26))
        assertEquals("Bad name", XlsxWriter.safeSheetName("Bad/name"))
    }
}
