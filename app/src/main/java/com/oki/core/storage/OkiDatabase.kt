package com.oki.core.storage

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

class Converters {
    @TypeConverter fun daysToString(value: List<String>): String = value.joinToString(",")

    @TypeConverter
    fun stringToDays(value: String): List<String> = value.split(',').filter { it.isNotBlank() }
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY isCompleted, dueAt") fun observe(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun get(id: String): Task?

    @Query("SELECT * FROM tasks ORDER BY dueAt") suspend fun all(): List<Task>

    @Upsert suspend fun put(task: Task)

    @Query("DELETE FROM tasks WHERE id = :id") suspend fun delete(id: String)

    @Query("DELETE FROM tasks") suspend fun clear()

    @Query(
        "SELECT * FROM tasks WHERE (:query = '' OR instr(lower(title || ' ' || notes), lower(:query)) > 0) AND (:fromTime IS NULL OR dueAt >= :fromTime) AND (:toTime IS NULL OR dueAt < :toTime) AND (:completed IS NULL OR isCompleted = :completed) ORDER BY dueAt LIMIT :limit"
    )
    suspend fun search(
        query: String,
        fromTime: Long?,
        toTime: Long?,
        completed: Boolean?,
        limit: Int,
    ): List<Task>
}

@Dao
interface DoctorDao {
    @Query("SELECT * FROM doctors ORDER BY doctorName COLLATE NOCASE")
    fun observe(): Flow<List<Doctor>>

    @Query("SELECT * FROM doctors WHERE id = :id") suspend fun get(id: String): Doctor?

    @Query("SELECT * FROM doctors ORDER BY doctorName COLLATE NOCASE")
    suspend fun all(): List<Doctor>

    @Upsert suspend fun put(doctor: Doctor)

    @Query(
        "UPDATE doctors SET attendanceStatus = :status, attendanceChangedAt = :now WHERE id = :id"
    )
    suspend fun attendance(id: String, status: Attendance, now: Long)

    @Query("UPDATE doctors SET attendanceStatus = 'PRESENT', attendanceChangedAt = NULL")
    suspend fun reset()

    @Query("DELETE FROM doctors WHERE id = :id") suspend fun delete(id: String)

    @Query("DELETE FROM doctors") suspend fun clear()

    @Query(
        "SELECT * FROM doctors WHERE (:query = '' OR instr(lower(doctorName || ' ' || department || ' ' || hospitalOrClinic), lower(:query)) > 0) AND (:department = '' OR instr(lower(department), lower(:department)) > 0) AND (:attendance = '' OR attendanceStatus = :attendance) AND (:day = '' OR instr(workingDays, :day) > 0) AND (:time = '' OR (availableFrom != '' AND availableUntil != '' AND ((availableFrom <= availableUntil AND :time >= availableFrom AND :time <= availableUntil) OR (availableFrom > availableUntil AND (:time >= availableFrom OR :time <= availableUntil))))) ORDER BY doctorName COLLATE NOCASE LIMIT :limit"
    )
    suspend fun search(
        query: String,
        department: String,
        attendance: String,
        day: String,
        time: String,
        limit: Int,
    ): List<Doctor>
}

@Dao
interface MaintenanceDao {
    @Query("SELECT value FROM maintenance WHERE `key` = :key") suspend fun get(key: String): String?

    @Upsert suspend fun put(value: Maintenance)
}

@Database(
    entities = [Task::class, Doctor::class, Maintenance::class],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class OkiDatabase : RoomDatabase() {
    abstract fun tasks(): TaskDao

    abstract fun doctors(): DoctorDao

    abstract fun maintenance(): MaintenanceDao

    companion object {
        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE tasks ADD COLUMN alertMode TEXT NOT NULL DEFAULT 'NOTIFICATION'"
                    )
                }
            }

        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE tasks ADD COLUMN scheduledEndReminderAt INTEGER DEFAULT NULL"
                    )
                }
            }

        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE tasks ADD COLUMN startTime TEXT DEFAULT NULL")
                    db.execSQL("ALTER TABLE tasks ADD COLUMN endTime TEXT DEFAULT NULL")
                }
            }
    }
}
