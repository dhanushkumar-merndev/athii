package com.oki

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.oki.core.security.EmbeddedCredentialBootstrap
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in only, with synthetic content and a personal build on the test emulator. */
class LiveAiSmokeTest {
    private fun container(): AppContainer {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_ai") == "true")
        val app = ApplicationProvider.getApplicationContext<OkiApplication>()
        assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"))
        runBlocking {
            File(app.noBackupFilesDir, "credential_bootstrap_applied").delete()
            EmbeddedCredentialBootstrap(app, app.container.credentials).applyIfAvailable()
        }
        return app.container
    }

    private fun image(vararg lines: String): ByteArray {
        val bitmap = Bitmap.createBitmap(1000, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 32f
            }
        lines.forEachIndexed { index, line -> canvas.drawText(line, 30f, 70f + index * 70f, paint) }
        return ByteArrayOutputStream().use { bytes ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, bytes)
            bitmap.recycle()
            bytes.toByteArray()
        }
    }

    @Test
    fun imageReturnsEveryTaskAsAnUnsavedDraft(): Unit = runBlocking {
        val c = container()
        val drafts =
            c.gemini.extract(
                image(
                    "Tasks for 2099-09-13",
                    "10:00 - 10:30 Read a chapter",
                    "11:00 - 11:30 Water the plants",
                ),
                false,
            )
        assertEquals(2, drafts.size)
        assertTrue(
            drafts.any { it["title"]?.jsonPrimitive?.contentOrNull?.contains("Read", true) == true }
        )
        assertTrue(
            drafts.any {
                it["title"]?.jsonPrimitive?.contentOrNull?.contains("plant", true) == true
            }
        )
    }

    @Test
    fun imageReturnsEveryDoctorWithoutInventedFields(): Unit = runBlocking {
        val c = container()
        val drafts =
            c.gemini.extract(
                image(
                    "Doctor directory - test fixture",
                    "Dr Mira Example | Dermatology | Room 4",
                    "Dr Ravi Example | Cardiology | Room 7",
                    "Clinic: Example Test Clinic",
                ),
                true,
            )
        assertEquals(2, drafts.size)
        assertTrue(
            drafts.all {
                it["phone"] == null ||
                    it["phone"] == JsonNull ||
                    it["phone"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
            }
        )
    }

    @Test
    fun randomTaskRequestProducesMeaningfulBatch(): Unit = runBlocking {
        val c = container()
        val answer =
            c.assistant.ask(
                "Suggest and prepare exactly 3 random useful everyday tasks for tomorrow. Choose sensible start and optional end times for me. Let me review them."
            )
        assertEquals(3, answer.taskDrafts.size)
        assertTrue(
            answer.taskDrafts.all {
                !it.title.isNullOrBlank() &&
                    !it.title!!.equals("random task", true) &&
                    !it.time.isNullOrBlank()
            }
        )
        assertTrue(answer.reviewDrafts.none { it.saved })
    }
}
