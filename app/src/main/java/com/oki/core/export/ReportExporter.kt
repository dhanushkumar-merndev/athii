package com.oki.core.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.oki.core.storage.OkiDatabase
import com.oki.feature.doctors.DoctorRepository
import java.io.ByteArrayOutputStream
import java.time.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ReportKind(val label: String) {
    TASKS("task report"),
    ATTENDANCE("doctor attendance report"),
}

class ReportExporter(
    private val context: Context,
    private val db: OkiDatabase,
    private val doctors: DoctorRepository,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    class Report(val fileName: String, val bytes: ByteArray)

    /** Each report is one range query over indexed columns, grouped in memory. */
    suspend fun build(kind: ReportKind, from: LocalDate, to: LocalDate): Report =
        withContext(Dispatchers.IO) {
            Reports.validateRange(from, to)
            val z = zone()
            val now = Instant.now()
            when (kind) {
                ReportKind.TASKS -> {
                    val tasks =
                        db.tasks()
                            .dueBetween(
                                from.atStartOfDay(z).toInstant().toEpochMilli(),
                                to.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli(),
                            )
                    render(
                        Reports.fileName("tasks", from, to),
                        Reports.tasksWorkbook(tasks, from, to, now, z),
                    )
                }
                ReportKind.ATTENDANCE -> {
                    val (logs, first) = doctors.attendanceBetween(from, to)
                    render(
                        Reports.fileName("doctor-attendance", from, to),
                        Reports.attendanceWorkbook(logs, from, to, now, z, first),
                    )
                }
            }
        }

    private fun render(name: String, sheets: List<SheetSpec>): Report {
        val out = ByteArrayOutputStream()
        XlsxWriter.write(sheets, out)
        return Report(name, out.toByteArray())
    }

    /** Android 10+ saves straight into Downloads/Athii without any storage permission. */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun saveToDownloads(report: Report): Uri =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val values =
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, report.fileName)
                    put(MediaStore.Downloads.MIME_TYPE, Reports.MIME)
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/Athii",
                    )
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            val uri =
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Could not create the report in Downloads.")
            try {
                resolver.openOutputStream(uri)?.use { it.write(report.bytes) }
                    ?: error("Could not write the report.")
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null,
                    null,
                )
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            uri
        }

    /** Older Android versions write to a location the user picks in the system file dialog. */
    suspend fun writeTo(uri: Uri, report: Report) =
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { it.write(report.bytes) }
                ?: error("Could not write the report.")
        }
}
