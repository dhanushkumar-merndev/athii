package com.oki

import com.oki.core.storage.Doctor
import com.oki.feature.doctors.validatedDoctor
import org.junit.Assert.*
import org.junit.Test

class DoctorValidationTest {
    @Test
    fun optionalFieldsStayUnknownAndWhitespaceIsNormalized() {
        val doctor = validatedDoctor(Doctor(doctorName = "  Dr A  ", availableFrom = " "))
        assertEquals("Dr A", doctor.doctorName)
        assertEquals("", doctor.availableFrom)
        assertEquals("", doctor.availableUntil)
        assertTrue(doctor.workingDays.isEmpty())
    }

    @Test
    fun scheduleKeepsOvernightHoursAndSortsUniqueDays() {
        val doctor =
            validatedDoctor(
                Doctor(
                    doctorName = "Dr Night",
                    availableFrom = " 22:00 ",
                    availableUntil = "06:00",
                    workingDays = listOf("friday", " MONDAY ", "FRIDAY"),
                    department = " Night clinic ",
                )
            )
        assertEquals("22:00", doctor.availableFrom)
        assertEquals("06:00", doctor.availableUntil)
        assertEquals(listOf("MONDAY", "FRIDAY"), doctor.workingDays)
        assertEquals("Night clinic", doctor.department)
    }

    @Test
    fun invalidOrNonCanonicalTimesGiveActionableErrors() {
        for (time in listOf("24:00", "09:60", "9:00", "09:00:01", "morning")) {
            val failure =
                runCatching { validatedDoctor(Doctor(doctorName = "Dr A", availableFrom = time)) }
                    .exceptionOrNull()
            assertTrue("Expected rejection for $time", failure is IllegalArgumentException)
            assertTrue(failure!!.message!!.contains("HH:mm"))
        }
        val untilFailure =
            runCatching { validatedDoctor(Doctor(doctorName = "Dr A", availableUntil = "25:00")) }
                .exceptionOrNull()
        assertTrue(untilFailure!!.message!!.contains("Until"))
    }

    @Test
    fun blankNameAndInvalidDaysAreRejectedBeforeWriting() {
        assertEquals(
            "Enter the doctor's name.",
            runCatching { validatedDoctor(Doctor(doctorName = "  ")) }.exceptionOrNull()!!.message,
        )
        assertTrue(
            runCatching {
                    validatedDoctor(Doctor(doctorName = "Dr A", workingDays = listOf("Tomorrow")))
                }
                .exceptionOrNull()!!
                .message!!
                .contains("Monday to Sunday")
        )
    }
}
