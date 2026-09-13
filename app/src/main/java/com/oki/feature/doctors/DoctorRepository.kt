package com.oki.feature.doctors

import androidx.room.withTransaction
import com.oki.core.storage.*
import java.time.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DoctorRepository(
    private val db: OkiDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val mutex = Mutex()
    private val dao = db.doctors()
    private val log = db.attendanceLog()

    companion object {
        const val MAX_DOCTORS = 50

        /** Days the app was never opened are back-filled as Present, up to one year. */
        const val MAX_BACKFILL_DAYS = 366L
        private const val RESET_KEY = "attendance_reset_date"
        private const val LOG_KEY = "attendance_log_date"
    }

    val doctors = dao.observe()

    suspend fun get(id: String): Doctor? {
        ensureToday()
        return dao.get(id)
    }

    suspend fun search(
        query: String = "",
        department: String = "",
        attendance: String = "",
        day: String = "",
        time: String = "",
        limit: Int = 20,
    ): List<Doctor> {
        ensureToday()
        return dao.search(query, department, attendance, day, time, limit.coerceIn(1, 30))
    }

    suspend fun save(doctor: Doctor) =
        mutex.withLock {
            val validated = validatedDoctor(doctor)
            db.withTransaction {
                if (dao.countExcluding(doctor.id) >= MAX_DOCTORS)
                    throw IllegalArgumentException(
                        "You have reached the limit of $MAX_DOCTORS doctors. Delete a few, then add this one."
                    )
                resetIfNeeded()
                val existing = dao.get(doctor.id)
                dao.put(
                    validated.copy(
                        attendanceStatus =
                            if (existing == null) Attendance.PRESENT else doctor.attendanceStatus,
                        attendanceChangedAt =
                            if (
                                existing != null &&
                                    existing.attendanceStatus != doctor.attendanceStatus
                            )
                                clock.millis()
                            else existing?.attendanceChangedAt,
                        updatedAt = clock.millis(),
                    )
                )
                log.snapshotDoctor(today().toString(), doctor.id, clock.millis())
            }
        }

    suspend fun setAttendance(id: String, status: Attendance) =
        mutex.withLock {
            db.withTransaction {
                resetIfNeeded()
                dao.attendance(id, status, clock.millis())
                log.snapshotDoctor(today().toString(), id, clock.millis())
            }
        }

    suspend fun delete(id: String) = mutex.withLock { dao.delete(id) }

    suspend fun clear() =
        mutex.withLock {
            db.withTransaction {
                dao.clear()
                log.clear()
            }
        }

    suspend fun ensureToday() = mutex.withLock { db.withTransaction { resetIfNeeded() } }

    /** Logged attendance for [from]..[to], plus the first day any attendance was recorded. */
    suspend fun attendanceBetween(
        from: LocalDate,
        to: LocalDate,
    ): Pair<List<AttendanceLog>, LocalDate?> {
        ensureToday()
        return log.between(from.toString(), to.toString()) to
            log.firstDate()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }

    private fun today(): LocalDate = clock.instant().atZone(zone()).toLocalDate()

    private suspend fun resetIfNeeded() {
        val today = today()
        val now = clock.millis()
        val maintenance = db.maintenance()
        val lastReset = maintenance.get(RESET_KEY)
        if (TimeRules.needsReset(lastReset, today)) {
            val lastDate = lastReset?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (lastDate != null && lastDate.isBefore(today)) {
                // The live table still holds that day's final attendance.
                log.snapshot(lastDate.toString(), now)
                val gapStart = maxOf(lastDate.plusDays(1), today.minusDays(MAX_BACKFILL_DAYS))
                val gapEnd = today.minusDays(1)
                if (!gapStart.isAfter(gapEnd)) backfillPresent(gapStart, gapEnd, now)
            }
            dao.reset()
            maintenance.put(Maintenance(RESET_KEY, today.toString()))
        }
        if (maintenance.get(LOG_KEY) != today.toString()) {
            log.snapshot(today.toString(), now)
            maintenance.put(Maintenance(LOG_KEY, today.toString()))
        }
    }

    /**
     * Days nobody opened the app reset to Present at midnight. One set-based insert over a
     * generated day series, never a statement per day.
     */
    private fun backfillPresent(start: LocalDate, end: LocalDate, now: Long) {
        db.openHelper.writableDatabase.execSQL(
            "WITH RECURSIVE days(d) AS (SELECT ? UNION ALL SELECT date(d, '+1 day') FROM days WHERE d < ?) " +
                "INSERT OR IGNORE INTO attendance_log (date, doctorId, doctorName, department, workingDays, status, recordedAt) " +
                "SELECT days.d, doctors.id, doctors.doctorName, doctors.department, doctors.workingDays, 'PRESENT', ? " +
                "FROM days CROSS JOIN doctors WHERE date(doctors.createdAt / 1000, 'unixepoch', 'localtime') <= days.d",
            arrayOf<Any>(start.toString(), end.toString(), now),
        )
    }
}

/** Directory time filters compare HH:mm values, so keep saved times in that format. */
internal fun validatedDoctor(doctor: Doctor): Doctor {
    require(doctor.doctorName.isNotBlank()) { "Enter the doctor's name." }
    fun time(value: String, label: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return ""
        require(
            Regex("\\d{2}:\\d{2}").matches(trimmed) &&
                runCatching { LocalTime.parse(trimmed) }.isSuccess
        ) {
            "Enter a valid $label time in HH:mm format, or leave it blank."
        }
        return trimmed
    }
    val days = doctor.workingDays.map { it.trim().uppercase() }.distinct()
    require(days.all { value -> DayOfWeek.entries.any { it.name == value } }) {
        "Choose valid working days from Monday to Sunday."
    }
    return doctor.copy(
        doctorName = doctor.doctorName.trim(),
        qualification = doctor.qualification.trim(),
        department = doctor.department.trim(),
        roomOrOpdNumber = doctor.roomOrOpdNumber.trim(),
        availableFrom = time(doctor.availableFrom, "From"),
        availableUntil = time(doctor.availableUntil, "Until"),
        workingDays = days.sortedBy { DayOfWeek.valueOf(it).value },
        hospitalOrClinic = doctor.hospitalOrClinic.trim(),
        phone = doctor.phone.trim(),
        notes = doctor.notes.trim(),
    )
}
