package com.oki

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.oki.core.ai.*
import com.oki.core.notifications.*
import com.oki.core.security.*
import com.oki.core.storage.*
import com.oki.feature.assistant.*
import com.oki.feature.doctors.*
import com.oki.feature.tasks.*
import java.time.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalDataTest {
    private lateinit var db: OkiDatabase
    private lateinit var context: Context

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: ZoneId) = this

        override fun instant() = now
    }

    private val clock = MutableClock(Instant.parse("2026-09-11T18:29:00Z"))
    private lateinit var doctors: DoctorRepository
    private lateinit var tasks: TaskRepository
    private val scheduled = mutableMapOf<String, Long>()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, OkiDatabase::class.java).build()
        doctors = DoctorRepository(db, clock) { ZoneId.of("Asia/Kolkata") }
        tasks =
            TaskRepository(
                db.tasks(),
                object : ReminderScheduler {
                    override fun schedule(task: Task) {
                        task.scheduledReminderAt?.let { scheduled[task.id] = it }
                    }

                    override fun cancel(id: String) {
                        scheduled.remove(id)
                    }

                    override fun dismiss(id: String) {}
                },
            )
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun doctorLifecycleAndMidnightOnlyChangeAttendance() = runBlocking {
        val a =
            Doctor(
                doctorName = "Dr Rao",
                qualification = "MBBS",
                department = "Dermatology",
                availableFrom = "17:00",
                availableUntil = "20:00",
                workingDays = listOf("TUESDAY"),
            )
        val b = Doctor(doctorName = "Dr B")
        doctors.save(a)
        doctors.save(b)
        assertEquals(Attendance.PRESENT, doctors.get(a.id)!!.attendanceStatus)
        doctors.setAttendance(a.id, Attendance.ABSENT)
        assertEquals(Attendance.ABSENT, doctors.get(a.id)!!.attendanceStatus)
        doctors.setAttendance(a.id, Attendance.PRESENT)
        assertEquals(Attendance.PRESENT, doctors.get(a.id)!!.attendanceStatus)
        val edited =
            doctors
                .get(a.id)!!
                .copy(
                    doctorName = "Dr Rao updated",
                    qualification = "MD",
                    department = "Skin",
                    roomOrOpdNumber = "204",
                    availableFrom = "16:00",
                    availableUntil = "21:00",
                    workingDays = listOf("MONDAY", "TUESDAY"),
                    hospitalOrClinic = "Clinic",
                    phone = "123456",
                    notes = "Updated",
                    attendanceStatus = Attendance.ABSENT,
                )
        doctors.save(edited)
        val saved = doctors.get(a.id)!!
        assertEquals(a.id, saved.id)
        assertEquals("123456", saved.phone)
        assertEquals("Updated", saved.notes)
        assertEquals("204", saved.roomOrOpdNumber)
        val cancelledDraft = saved.copy(doctorName = "Do not persist")
        assertNotEquals(cancelledDraft.doctorName, doctors.get(a.id)!!.doctorName)
        clock.now = Instant.parse("2026-09-11T18:30:00Z")
        doctors.ensureToday()
        val reset = doctors.get(a.id)!!
        assertEquals(
            saved.copy(attendanceStatus = Attendance.PRESENT, attendanceChangedAt = null),
            reset,
        )
        doctors.setAttendance(a.id, Attendance.ABSENT)
        doctors.ensureToday()
        assertEquals(Attendance.ABSENT, doctors.get(a.id)!!.attendanceStatus)
        doctors.delete(a.id)
        assertNull(doctors.get(a.id))
        assertNotNull(doctors.get(b.id))
        assertTrue(doctors.search("Rao").isEmpty())
        val tools = LocalAssistantToolExecutor(tasks, doctors)
        assertEquals(JsonNull, tools.execute("getDoctorById", buildJsonObject { put("id", a.id) }))
    }

    @Test
    fun missedMidnightRecoveredOnNewRepository() = runBlocking {
        val d = Doctor(doctorName = "Dr A")
        doctors.save(d)
        doctors.setAttendance(d.id, Attendance.ABSENT)
        clock.now = clock.now.plusSeconds(172800)
        val reopened = DoctorRepository(db, clock) { ZoneId.of("Asia/Kolkata") }
        reopened.ensureToday()
        assertEquals(Attendance.PRESENT, reopened.get(d.id)!!.attendanceStatus)
    }

    @Test
    fun filtersAndAssistantNeverMutateDataOnFailure() = runBlocking {
        val a =
            Doctor(
                doctorName = "Dr A",
                department = "Skin",
                workingDays = listOf("TUESDAY"),
                availableFrom = "17:00",
                availableUntil = "20:00",
            )
        doctors.save(a)
        doctors.save(Doctor(doctorName = "Dr B", department = "Heart"))
        assertEquals(
            listOf(a.id),
            doctors.search(department = "Skin", day = "TUESDAY", time = "18:00").map { it.id },
        )
        assertTrue(doctors.search(day = "MONDAY").isEmpty())
        assertTrue(doctors.search(time = "12:00").isEmpty())
        val executor = LocalAssistantToolExecutor(tasks, doctors)
        val before = db.doctors().all()
        executor.execute("deleteDoctor", buildJsonObject { put("id", a.id) })
        val failed =
            AssistantRepository(
                AiRouter(GroqTransport { _, _, _, _, _ -> throw ApiFailure(503) }),
                executor,
            )
        assertTrue(runCatching { failed.ask("Delete everyone") }.isFailure)
        assertEquals(before, db.doctors().all())
    }

    @Test
    fun tasksSameTimeEditCompleteDelete() = runBlocking {
        val due = System.currentTimeMillis() + 3600000
        val a = Task(title = "A", dueAt = due)
        val b = Task(title = "B", dueAt = due)
        tasks.save(a)
        tasks.save(b)
        assertEquals(2, scheduled.size)
        assertNotEquals(
            ChannelIdentity.notificationTag(a.id),
            ChannelIdentity.notificationTag(b.id),
        )
        tasks.save(a.copy(dueAt = due + 60000))
        assertEquals(due + 60000, scheduled[a.id])
        tasks.complete(a.id, true)
        assertFalse(scheduled.contains(a.id))
        tasks.delete(b.id)
        assertNull(tasks.get(b.id))
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun keystoreRoundTripIsMaskedAndDeleteWorks() = runBlocking {
        val store = SecureCredentialStore(context)
        store.save(Provider.GEMINI, "instrumented-test-key-only")
        assertTrue(store.isConfigured(Provider.GEMINI))
        assertEquals("instrumented-test-key-only", store.readForRequest(Provider.GEMINI))
        assertFalse(store.toString().contains("instrumented-test-key-only"))
        store.delete(Provider.GEMINI)
        assertFalse(store.isConfigured(Provider.GEMINI))
    }

    @Test
    fun taskAndDoctorPersistWhenDatabaseReopened(): Unit = runBlocking {
        val name = "persistence-test.db"
        context.deleteDatabase(name)
        val first = Room.databaseBuilder(context, OkiDatabase::class.java, name).build()
        val task = Task(title = "Persistent task", dueAt = 10000)
        val doctor = Doctor(doctorName = "Persistent doctor")
        first.tasks().put(task)
        first.doctors().put(doctor)
        first.close()
        val second = Room.databaseBuilder(context, OkiDatabase::class.java, name).build()
        assertEquals(task, second.tasks().get(task.id))
        assertEquals(doctor, second.doctors().get(doctor.id))
        second.close()
        context.deleteDatabase(name)
    }
}
