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
                5,
                true,
                OkiDatabase.MIGRATION_2_3,
                OkiDatabase.MIGRATION_3_4,
                OkiDatabase.MIGRATION_4_5,
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
}
