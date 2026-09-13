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

    /** One set-based delete: cost tracks the rows actually removed, not the task count. */
    @Query(
        "DELETE FROM tasks WHERE isCompleted = 1 AND completedAt IS NOT NULL AND completedAt < :cutoff"
    )
    suspend fun deleteCompletedBefore(cutoff: Long): Int

    @Query("DELETE FROM tasks WHERE isCompleted = 1") suspend fun deleteCompleted(): Int

    /** Reports read one start-date range through index_tasks_dueAt. */
    @Query("SELECT * FROM tasks WHERE dueAt >= :from AND dueAt < :to ORDER BY dueAt")
    suspend fun dueBetween(from: Long, to: Long): List<Task>

    /** One indexed COUNT, so the cap check does not scale with the size of the table. */
    @Query("SELECT COUNT(*) FROM tasks WHERE isCompleted = 0 AND id != :excludeId")
    suspend fun activeCountExcluding(excludeId: String): Int

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

    @Query("SELECT COUNT(*) FROM doctors WHERE id != :excludeId")
    suspend fun countExcluding(excludeId: String): Int

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

@Dao
interface AttendanceLogDao {
    /** Copies every doctor's current attendance into [date] in one statement. */
    @Query(
        "INSERT OR REPLACE INTO attendance_log (date, doctorId, doctorName, department, workingDays, status, recordedAt) SELECT :date, id, doctorName, department, workingDays, attendanceStatus, :now FROM doctors"
    )
    suspend fun snapshot(date: String, now: Long)

    @Query(
        "INSERT OR REPLACE INTO attendance_log (date, doctorId, doctorName, department, workingDays, status, recordedAt) SELECT :date, id, doctorName, department, workingDays, attendanceStatus, :now FROM doctors WHERE id = :id"
    )
    suspend fun snapshotDoctor(date: String, id: String, now: Long)

    /** The primary key leads with date, so a report range is an index range scan. */
    @Query(
        "SELECT * FROM attendance_log WHERE date >= :from AND date <= :to ORDER BY date, doctorName COLLATE NOCASE"
    )
    suspend fun between(from: String, to: String): List<AttendanceLog>

    @Query("SELECT MIN(date) FROM attendance_log") suspend fun firstDate(): String?

    @Query("DELETE FROM attendance_log") suspend fun clear()
}

@Database(
    entities = [Task::class, Doctor::class, Maintenance::class, AttendanceLog::class],
    version = 6,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class OkiDatabase : RoomDatabase() {
    abstract fun tasks(): TaskDao

    abstract fun doctors(): DoctorDao

    abstract fun maintenance(): MaintenanceDao

    abstract fun attendanceLog(): AttendanceLogDao

    companion object {
        val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `attendance_log` (`date` TEXT NOT NULL, `doctorId` TEXT NOT NULL, `doctorName` TEXT NOT NULL, `department` TEXT NOT NULL, `workingDays` TEXT NOT NULL, `status` TEXT NOT NULL, `recordedAt` INTEGER NOT NULL, PRIMARY KEY(`date`, `doctorId`))"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_tasks_dueAt` ON `tasks` (`dueAt`)"
                    )
                }
            }

        val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_tasks_isCompleted_completedAt ON tasks (isCompleted, completedAt)"
                    )
                }
            }

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
