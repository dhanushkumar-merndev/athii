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

    companion object {
        const val MAX_DOCTORS = 50
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
            require(doctor.doctorName.isNotBlank()) { "Enter the doctor's name." }
            if (dao.countExcluding(doctor.id) >= MAX_DOCTORS)
                throw IllegalArgumentException(
                    "You have reached the limit of $MAX_DOCTORS doctors. Delete a few, then add this one."
                )
            listOf(doctor.availableFrom, doctor.availableUntil)
                .filter { it.isNotBlank() }
                .forEach { LocalTime.parse(it) }
            doctor.workingDays.forEach { DayOfWeek.valueOf(it) }
            db.withTransaction {
                resetIfNeeded()
                val existing = dao.get(doctor.id)
                dao.put(
                    doctor.copy(
                        doctorName = doctor.doctorName.trim(),
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
            }
        }

    suspend fun setAttendance(id: String, status: Attendance) =
        mutex.withLock {
            db.withTransaction {
                resetIfNeeded()
                dao.attendance(id, status, clock.millis())
            }
        }

    suspend fun delete(id: String) = mutex.withLock { dao.delete(id) }

    suspend fun clear() = mutex.withLock { dao.clear() }

    suspend fun ensureToday() = mutex.withLock { db.withTransaction { resetIfNeeded() } }

    private suspend fun resetIfNeeded() {
        val today = clock.instant().atZone(zone()).toLocalDate()
        if (TimeRules.needsReset(db.maintenance().get("attendance_reset_date"), today)) {
            dao.reset()
            db.maintenance().put(Maintenance("attendance_reset_date", today.toString()))
        }
    }
}
