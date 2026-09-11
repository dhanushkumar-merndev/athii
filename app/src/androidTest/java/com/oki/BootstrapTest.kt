package com.oki

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.oki.core.security.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run explicitly against a private APK on a dedicated emulator only. No keys are printed. */
@RunWith(AndroidJUnit4::class)
class BootstrapTest {
    @Test
    fun importsBothKeysPreservesReplacementAndRespectsDeletion(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<OkiApplication>()
        assumeTrue(app.assets.list("")!!.contains("athii-bootstrap.bin"))
        val c = app.container
        c.recover()
        val marker = File(app.noBackupFilesDir, "credential_bootstrap_applied")
        c.credentials.clear()
        marker.delete()
        val bootstrap = EmbeddedCredentialBootstrap(app, c.credentials)
        bootstrap.applyIfAvailable()
        assertTrue(c.credentials.isConfigured(Provider.GEMINI))
        assertTrue(c.credentials.isConfigured(Provider.GROQ))
        assertTrue(marker.exists())
        assertTrue(c.credentials.readForRequest(Provider.GEMINI).isNotBlank())
        assertTrue(c.credentials.readForRequest(Provider.GROQ).isNotBlank())
        c.credentials.save(Provider.GROQ, "replacement-test-only")
        bootstrap.applyIfAvailable()
        assertEquals("replacement-test-only", c.credentials.readForRequest(Provider.GROQ))
        c.credentials.clear()
        bootstrap.applyIfAvailable()
        assertFalse(c.credentials.isConfigured(Provider.GEMINI))
        assertFalse(c.credentials.isConfigured(Provider.GROQ))
    }
}
