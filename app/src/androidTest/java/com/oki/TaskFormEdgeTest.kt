package com.oki

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.oki.core.storage.Appearance
import com.oki.core.ui.OkiTheme
import com.oki.feature.tasks.ReminderLeadTimeField
import org.junit.Rule
import org.junit.Test

class TaskFormEdgeTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun customLeadTimeStaysEditableWhenBlankOrEqualToAPreset() {
        rule.setContent {
            var offset by remember { mutableStateOf("0") }
            OkiTheme(Appearance.DARK) { ReminderLeadTimeField(offset, false) { offset = it } }
        }
        rule.onNodeWithText("Custom").performClick()
        rule.onNodeWithText("Minutes before the start time").performTextClearance()
        rule.onNodeWithText("Minutes before the start time").assertIsDisplayed()
        rule.onNodeWithText("Minutes before the start time").performTextInput("5")
        rule.onNodeWithText("Minutes before the start time").assertIsDisplayed()
        rule.onNodeWithText("Minutes before the start time").performTextInput("2")
        rule.onNodeWithText("Notifies 52 minutes before the start time.").assertExists()
        rule.onNodeWithText("At time").performClick()
        rule.onNodeWithText("Minutes before the start time").assertDoesNotExist()
        rule.onNodeWithText("Notifies exactly at the start time.").assertExists()
    }
}
