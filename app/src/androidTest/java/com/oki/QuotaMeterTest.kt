package com.oki

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.oki.core.ai.QuotaWindow
import com.oki.core.storage.Appearance
import com.oki.core.ui.OkiTheme
import com.oki.feature.assistant.QuotaMeter
import org.junit.Rule
import org.junit.Test

class QuotaMeterTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun expiredWindowRefillsVisibleMeterAndLabelsEstimate() {
        var now by mutableLongStateOf(1_000)
        rule.setContent {
            OkiTheme(Appearance.DARK) {
                Column { QuotaMeter("Tokens / minute", QuotaWindow(8_000, 0, 2_000), now) }
            }
        }
        rule.onNodeWithText("Tokens / minute · 0 / 8000 left").assertIsDisplayed()
        rule.onNodeWithText("Resets in 0m 1s").assertIsDisplayed()
        rule.runOnIdle { now = 2_000 }
        rule.onNodeWithText("Tokens / minute · estimated 8000 / 8000 left").assertIsDisplayed()
        rule
            .onNodeWithText("Window reset · next response confirms availability")
            .assertIsDisplayed()
        rule.onNodeWithText("Resets in 0m 1s").assertDoesNotExist()
    }
}
