package com.oki

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NavigationMotionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun openingMovesScreenLeftAndBackMovesItRight() {
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.mainClock.advanceTimeBy(96)
        val opening = rule.onNodeWithText("Make Athii yours.").getUnclippedBoundsInRoot().left.value
        assertTrue("New page enters from the right", opening > 40f)
        assertTrue(
            "The entire old page moves left",
            rule.onNodeWithTag("main-pager").getUnclippedBoundsInRoot().left.value < 0f,
        )
        rule.mainClock.advanceTimeBy(400)
        val open = rule.onNodeWithText("Make Athii yours.").getUnclippedBoundsInRoot().left.value
        assertTrue(open < opening)
        rule.onNodeWithContentDescription("Back").performClick()
        rule.mainClock.advanceTimeBy(96)
        val closing = rule.onNodeWithText("Make Athii yours.").getUnclippedBoundsInRoot().left.value
        assertTrue("Back slides the open page to the right", closing > open)
        rule.mainClock.advanceTimeBy(400)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("Add task", useUnmergedTree = true).assertIsDisplayed()
    }
}
