package com.oki

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import com.oki.core.storage.Appearance
import com.oki.core.ui.Field
import com.oki.core.ui.InlineAutocompleteField
import com.oki.core.ui.OkiTheme
import org.junit.Rule
import org.junit.Test

class AutocompleteFieldTest {
    @get:Rule val rule = createComposeRule()

    private val departments = listOf("QA Dept", "general doctor", "Cardiology")

    private fun setField() {
        rule.setContent {
            var value by remember { mutableStateOf("") }
            OkiTheme(Appearance.DARK) {
                Field("Department", value, { value = it }, suggestions = departments)
            }
        }
    }

    private fun field() = rule.onNode(hasSetTextAction())

    private fun acceptArrow() = rule.onNodeWithContentDescription("Accept suggestion")

    @Test
    fun acceptingSuggestionFillsFieldAndPutsCursorAtEnd() {
        setField()
        field().performTextInput("qa")
        acceptArrow().assertIsDisplayed()

        acceptArrow().performClick()
        field().assert(hasText("QA Dept"))
        acceptArrow().assertDoesNotExist()

        // Typing continues after the accepted text, proving the cursor moved to the end.
        field().performTextInput(" x")
        field().assert(hasText("QA Dept x"))
    }

    @Test
    fun matchingIsCaseInsensitiveAndUnknownQueryShowsNothing() {
        setField()
        field().performTextInput("card")
        acceptArrow().assertIsDisplayed()

        field().performTextClearance()
        field().performTextInput("xyz")
        acceptArrow().assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun ghostHiddenWhileCursorIsMidText() {
        setField()
        field().performTextInput("card")
        acceptArrow().assertIsDisplayed()
        field().performTextInputSelection(TextRange(2))
        acceptArrow().assertDoesNotExist()
        field().performTextInputSelection(TextRange(4))
        acceptArrow().assertIsDisplayed()
    }

    @Test
    fun searchFieldInRowWithWeightAcceptsSuggestion() {
        rule.setContent {
            var value by remember { mutableStateOf("") }
            OkiTheme(Appearance.DARK) {
                Row {
                    InlineAutocompleteField(
                        value,
                        { value = it },
                        Modifier.weight(1f),
                        suggestions = departments,
                    )
                    Text("Export")
                }
            }
        }
        field().performTextInput("CAR")
        acceptArrow().performClick()
        field().assert(hasText("Cardiology"))
        rule.onNodeWithText("Export").assertIsDisplayed()
        field().performTextInput("!")
        field().assert(hasText("Cardiology!"))

        field().performTextClearance()
        field().performTextInput("xyz")
        acceptArrow().assertDoesNotExist()
    }
}
