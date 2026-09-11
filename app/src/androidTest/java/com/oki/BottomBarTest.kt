package com.oki

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import com.oki.core.storage.Appearance
import com.oki.core.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BottomBarTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun rtlSelectionIndicatorStaysUnderSelectedTab() {
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                val selected = remember { mutableIntStateOf(0) }
                OkiTheme(Appearance.DARK) {
                    AthiiBottomBar(selected.intValue) { selected.intValue = it }
                }
            }
        }
        rule.onNodeWithText("Doctors").performClick()
        val indicator = rule.onNodeWithTag("active-tab-indicator").fetchSemanticsNode().boundsInRoot
        val label = rule.onNodeWithText("Doctors").fetchSemanticsNode().boundsInRoot
        assertEquals(label.center.x, indicator.center.x, 2f)
        rule.onAllNodesWithTag("active-tab-indicator").assertCountEquals(1)
    }
}
