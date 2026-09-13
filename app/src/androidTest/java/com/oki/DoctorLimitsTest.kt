package com.oki

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.oki.core.storage.*
import com.oki.feature.doctors.DoctorRepository
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class DoctorLimitsTest {
    private lateinit var db: OkiDatabase
    private lateinit var doctors: DoctorRepository

    @Before
    fun setUp() {
        db =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    OkiDatabase::class.java,
                )
                .build()
        doctors = DoctorRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun fiftyDoctorCapAllowsEditingAndAttendanceAndRecoversAfterDeletion() = runBlocking {
        val fixtures = List(50) { Doctor(doctorName = "Dr $it") }
        fixtures.forEach { doctors.save(it) }
        val extra = Doctor(doctorName = "Dr Overflow")
        assertTrue(
            runCatching { doctors.save(extra) }.exceptionOrNull()!!.message!!.contains("50 doctors")
        )
        assertNull(doctors.get(extra.id))
        doctors.save(fixtures.first().copy(doctorName = "Updated doctor", phone = " 12345 "))
        doctors.setAttendance(fixtures.first().id, Attendance.ABSENT)
        assertEquals("Updated doctor", doctors.get(fixtures.first().id)!!.doctorName)
        assertEquals("12345", doctors.get(fixtures.first().id)!!.phone)
        assertEquals(Attendance.ABSENT, doctors.get(fixtures.first().id)!!.attendanceStatus)
        doctors.delete(fixtures.last().id)
        doctors.save(extra)
        assertEquals(50, db.doctors().all().size)
    }

    @Test
    fun simultaneousCreatesAcrossRepositoriesCannotExceedLimit() = runBlocking {
        repeat(49) { doctors.save(Doctor(doctorName = "Dr $it")) }
        val other = DoctorRepository(db)
        val results =
            awaitAll(
                async(Dispatchers.Default) {
                    runCatching { doctors.save(Doctor(doctorName = "First")) }
                },
                async(Dispatchers.Default) {
                    runCatching { other.save(Doctor(doctorName = "Second")) }
                },
            )
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(50, db.doctors().all().size)
    }

    @Test
    fun invalidEditLeavesSavedDoctorIntactAndOvernightSearchWorks() = runBlocking {
        val original =
            Doctor(doctorName = "Night doctor", availableFrom = "22:00", availableUntil = "06:00")
        doctors.save(original)
        val saved = doctors.get(original.id)!!
        assertTrue(runCatching { doctors.save(saved.copy(availableUntil = "25:00")) }.isFailure)
        assertEquals(saved, doctors.get(original.id))
        assertEquals(original.id, doctors.search(time = "23:00").single().id)
        assertEquals(original.id, doctors.search(time = "05:59").single().id)
        assertTrue(doctors.search(time = "12:00").isEmpty())
    }
}
