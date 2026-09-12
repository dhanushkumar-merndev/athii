package com.oki.core.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.*
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
enum class Source {
    MANUAL,
    IMAGE_SCAN,
    AI_CHAT,
}

@Serializable
enum class Attendance {
    PRESENT,
    ABSENT,
}

@Serializable
enum class TaskAlertMode {
    NOTIFICATION,
    ALARM,
}

@Serializable
@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val notes: String = "",
    val dueAt: Long,
    val timeZoneIdAtCreation: String = ZoneId.systemDefault().id,
    val reminderEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "'NOTIFICATION'")
    val alertMode: TaskAlertMode = TaskAlertMode.NOTIFICATION,
    val reminderOffsetMinutes: Int = 0,
    val scheduledReminderAt: Long? = null,
    val scheduledEndReminderAt: Long? = null,
    val isCompleted: Boolean = false,
    val completedAt: Long? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val source: Source = Source.MANUAL,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(tableName = "doctors")
data class Doctor(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val doctorName: String,
    val qualification: String = "",
    val department: String = "",
    val roomOrOpdNumber: String = "",
    val availableFrom: String = "",
    val availableUntil: String = "",
    val workingDays: List<String> = emptyList(),
    val hospitalOrClinic: String = "",
    val phone: String = "",
    val notes: String = "",
    val attendanceStatus: Attendance = Attendance.PRESENT,
    val attendanceChangedAt: Long? = null,
    val source: Source = Source.MANUAL,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "maintenance")
data class Maintenance(@PrimaryKey val key: String, val value: String)

object TimeRules {
    /** End times belong to the task's selected date; overnight tasks need another date. */
    fun endAt(startAt: Long, endTime: String?, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (endTime.isNullOrBlank()) return null
        val date = Instant.ofEpochMilli(startAt).atZone(zone).toLocalDate()
        val end =
            try {
                parseDue(date.toString(), endTime, zone)
            } catch (_: Exception) {
                throw IllegalArgumentException("Choose a valid end time (HH:mm).")
            }
        require(end > startAt) { "End time must be after the start time on the same date." }
        return end
    }

    fun reminderAt(dueAt: Long, offset: Int): Long {
        require(offset in 0..525600) { "Choose an offset between 0 and 525600 minutes." }
        return Math.subtractExact(dueAt, offset * 60_000L)
    }

    fun nextMidnight(now: Instant, zone: ZoneId): Instant =
        now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()

    fun needsReset(lastDate: String?, today: LocalDate): Boolean = lastDate != today.toString()

    fun parseDue(date: String, time: String, zone: ZoneId = ZoneId.systemDefault()): Long {
        val local = LocalDate.parse(date).atTime(LocalTime.parse(time))
        require(zone.rules.getValidOffsets(local).isNotEmpty()) {
            "That time does not exist due to daylight saving. Choose another time."
        }
        return local.atZone(zone).toInstant().toEpochMilli()
    }
}
