package com.oki

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.oki.core.storage.OkiDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), OkiDatabase::class.java)

    @Test
    fun upgradePreservesExistingTaskAndItsReminder() {
        helper.createDatabase("migration-fixture", 2).apply {
            execSQL(
                "INSERT INTO tasks (id, title, notes, dueAt, timeZoneIdAtCreation, reminderEnabled, reminderOffsetMinutes, scheduledReminderAt, isCompleted, completedAt, startTime, endTime, source, createdAt, updatedAt) VALUES ('existing', 'Keep my task', '', 2000000, 'Asia/Kolkata', 1, 5, 1700000, 0, NULL, NULL, NULL, 'MANUAL', 1, 1)"
            )
            close()
        }
        helper
            .runMigrationsAndValidate(
                "migration-fixture",
                6,
                true,
                OkiDatabase.MIGRATION_2_3,
                OkiDatabase.MIGRATION_3_4,
                OkiDatabase.MIGRATION_4_5,
                OkiDatabase.MIGRATION_5_6,
            )
            .use { database ->
                database
                    .query(
                        "SELECT title, reminderOffsetMinutes, scheduledReminderAt, scheduledEndReminderAt, alertMode FROM tasks WHERE id = 'existing'"
                    )
                    .use { row ->
                        assertTrue(row.moveToFirst())
                        assertEquals("Keep my task", row.getString(0))
                        assertEquals(5, row.getInt(1))
                        assertEquals(1700000L, row.getLong(2))
                        assertTrue(row.isNull(3))
                        assertEquals("NOTIFICATION", row.getString(4))
                    }
            }
    }

    @Test
    fun upgradeToSixAddsAttendanceLogAndKeepsDoctors() {
        helper.createDatabase("migration-attendance", 5).apply {
            execSQL(
                "INSERT INTO doctors (id, doctorName, qualification, department, roomOrOpdNumber, availableFrom, availableUntil, workingDays, hospitalOrClinic, phone, notes, attendanceStatus, attendanceChangedAt, source, createdAt, updatedAt) VALUES ('doc', 'Dr Keep', '', 'ENT', '', '', '', 'MONDAY', '', '', '', 'ABSENT', 1, 'MANUAL', 1, 1)"
            )
            close()
        }
        helper
            .runMigrationsAndValidate("migration-attendance", 6, true, OkiDatabase.MIGRATION_5_6)
            .use { database ->
                database.query("SELECT doctorName, attendanceStatus FROM doctors").use { row ->
                    assertTrue(row.moveToFirst())
                    assertEquals("Dr Keep", row.getString(0))
                    assertEquals("ABSENT", row.getString(1))
                }
                database.execSQL(
                    "INSERT INTO attendance_log (date, doctorId, doctorName, department, workingDays, status, recordedAt) SELECT '2026-09-13', id, doctorName, department, workingDays, attendanceStatus, 5 FROM doctors"
                )
                database.query("SELECT status FROM attendance_log WHERE date = '2026-09-13'").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("ABSENT", it.getString(0))
                }
            }
    }
}
