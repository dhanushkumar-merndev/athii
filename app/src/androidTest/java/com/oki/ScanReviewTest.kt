package com.oki

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.oki.core.ai.aiJson
import com.oki.core.storage.Appearance
import com.oki.core.ui.OkiTheme
import com.oki.feature.scan.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ScanReviewTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun batchKeepsUnsavedItemsAndRestoresCreatedStatus() {
        val app = ApplicationProvider.getApplicationContext<OkiApplication>()
        val state =
            SavedStateHandle(
                mapOf(
                    "scan_drafts" to
                        aiJson.encodeToString(
                            listOf(
                                ScanDraft(
                                    "first",
                                    buildJsonObject { put("title", "Read a chapter") },
                                ),
                                ScanDraft(
                                    "second",
                                    buildJsonObject { put("title", "Go for a walk") },
                                ),
                            )
                        )
                )
            )
        val vm = ScanViewModel(app.container, app, state)
        var reviewed: String? = null
        rule.setContent {
            OkiTheme(Appearance.DARK) { ScanScreen(vm, false, { id, _ -> reviewed = id }, {}) }
        }
        rule.onNodeWithText("Read a chapter").performScrollTo().performClick()
        assertEquals("first", reviewed)
        // Opening or cancelling review does not mark any draft as created.
        assertTrue(vm.drafts.value.none { it.saved })
        rule.runOnIdle { vm.markSaved("first") }
        rule.onNodeWithText("Task created").assertExists()
        rule.onNodeWithText("Go for a walk").performScrollTo().performClick()
        assertEquals("second", reviewed)
        val restored = ScanViewModel(app.container, app, state)
        assertTrue(restored.drafts.value[0].saved)
        assertFalse(restored.drafts.value[1].saved)
        assertEquals(2, restored.drafts.value.size)
    }
}
