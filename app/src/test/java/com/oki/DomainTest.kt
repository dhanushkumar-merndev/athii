package com.oki

import com.oki.core.ai.*
import com.oki.core.notifications.ChannelIdentity
import com.oki.core.security.CredentialCipher
import com.oki.core.storage.*
import java.time.*
import javax.crypto.KeyGenerator
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DomainTest {
    @Test
    fun defaultReminderIsFiveMinutes() {
        assertEquals(5, Settings().defaultOffset)
        assertEquals(700_000L, TimeRules.reminderAt(1_000_000, 5))
    }

    @Test
    fun customAndZeroOffsets() {
        assertEquals(1000L, TimeRules.reminderAt(601_000, 10))
        assertEquals(1000L, TimeRules.reminderAt(1000, 0))
    }

    @Test
    fun invalidOffsetsRejected() {
        assertTrue(runCatching { TimeRules.reminderAt(0, -1) }.isFailure)
        assertTrue(runCatching { TimeRules.reminderAt(0, Int.MAX_VALUE) }.isFailure)
    }

    @Test
    fun reminderPastIsDetectable() {
        assertTrue(TimeRules.reminderAt(300_000, 10) < 0)
    }

    @Test
    fun nonexistentDstTimeRejected() {
        assertTrue(
            runCatching { TimeRules.parseDue("2026-03-08", "02:30", ZoneId.of("America/New_York")) }
                .isFailure
        )
    }

    @Test
    fun midnightUsesLocalCalendarAcrossDst() {
        val zone = ZoneId.of("America/New_York")
        val now = Instant.parse("2026-03-08T05:00:00Z")
        assertEquals(Instant.parse("2026-03-09T04:00:00Z"), TimeRules.nextMidnight(now, zone))
    }

    @Test
    fun timezoneChangeChangesResetInstant() {
        val now = Instant.parse("2026-09-11T12:00:00Z")
        assertNotEquals(
            TimeRules.nextMidnight(now, ZoneId.of("Asia/Kolkata")),
            TimeRules.nextMidnight(now, ZoneId.of("UTC")),
        )
    }

    @Test
    fun missedAndDuplicateResetDates() {
        val today = LocalDate.of(2026, 9, 11)
        assertTrue(TimeRules.needsReset(null, today))
        assertTrue(TimeRules.needsReset("2026-09-10", today))
        assertFalse(TimeRules.needsReset("2026-09-11", today))
    }

    @Test
    fun doctorDefaultsPresentWithUnknownDays() {
        assertEquals(Attendance.PRESENT, Doctor(doctorName = "Dr Rao").attendanceStatus)
        assertTrue(Doctor(doctorName = "Dr Rao").workingDays.isEmpty())
    }

    @Test
    fun identicalTimeNotificationsHaveDistinctTagsEvenForHashCollisions() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertNotEquals(
            ChannelIdentity.notificationTag("Aa"),
            ChannelIdentity.notificationTag("BB"),
        )
    }

    @Test
    fun soundChannelVersioningIsStable() {
        val a = Settings(soundMode = SoundMode.CUSTOM, customSoundUri = "content://sound/1")
        assertEquals(ChannelIdentity.forSettings(a), ChannelIdentity.forSettings(a.copy()))
        assertNotEquals(
            ChannelIdentity.forSettings(a),
            ChannelIdentity.forSettings(a.copy(customSoundUri = "content://sound/2")),
        )
        assertEquals(
            "reminders_silent_v1",
            ChannelIdentity.forSettings(Settings(soundMode = SoundMode.SILENT)),
        )
        assertEquals("reminders_default_v1", ChannelIdentity.forSettings(Settings()))
    }

    @Test
    fun imageDraftDoesNotInventMissingFields() {
        val raw =
            """{"drafts":[{"doctorName":"Dr Rao","qualification":null,"workingDays":[],"confidence":{"doctorName":0.8}}]}"""
        val draft =
            aiJson.decodeFromJsonElement<DoctorDraft>(ExtractionSchemas.parse(raw, true).single())
        assertNull(draft.qualification)
        assertNull(draft.phone)
        assertNull(draft.availableFrom)
        assertTrue(draft.workingDays.isEmpty())
    }

    @Test
    fun multipleDraftsRemainSeparateAndMissingTimeIsNull() {
        val parsed = ExtractionSchemas.parse("""{"drafts":[{"title":"A"},{"title":"B"}]}""", false)
        assertEquals(2, parsed.size)
        assertNull(aiJson.decodeFromJsonElement<TaskDraft>(parsed.first()).time)
    }

    @Test
    fun invalidExtractionIsRejected() {
        assertTrue(runCatching { ExtractionSchemas.parse("not json", true) }.isFailure)
        assertTrue(runCatching { ExtractionSchemas.parse("{}", false) }.isFailure)
    }

    @Test
    fun encryptionRoundTripAndTamperRejection() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = CredentialCipher { key }
        val plain = "test-key-only".toByteArray()
        val (iv, encrypted) = cipher.encrypt(plain)
        assertFalse(plain.contentEquals(encrypted))
        assertArrayEquals(plain, cipher.decrypt(iv, encrypted))
        encrypted[0] = (encrypted[0].toInt() xor 1).toByte()
        assertTrue(runCatching { cipher.decrypt(iv, encrypted) }.isFailure)
    }

    @Test
    fun encryptionUsesFreshIv() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = CredentialCipher { key }
        assertFalse(
            cipher
                .encrypt("same".toByteArray())
                .first
                .contentEquals(cipher.encrypt("same".toByteArray()).first)
        )
    }
}
