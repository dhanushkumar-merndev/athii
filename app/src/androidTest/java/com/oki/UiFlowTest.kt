package com.oki

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @org.junit.Before
    fun clearOnlyUiFixtures() {
        kotlinx.coroutines.runBlocking {
            val c = (rule.activity.application as OkiApplication).container
            c.doctors
                .search("Dr UI Test")
                .filter { it.doctorName == "Dr UI Test" }
                .forEach { c.doctors.delete(it.id) }
            c.tasks
                .search("UI test task")
                .filter { it.title == "UI test task" }
                .forEach { c.tasks.delete(it.id) }
        }
    }

    @Test
    fun manualDoctorReviewSaveEditCancelDelete() {
        rule.waitForIdle()
        rule.onNodeWithText("Doctors", useUnmergedTree = true).performClick()
        rule.onNodeWithText("Add doctor", useUnmergedTree = true).performClick()
        rule.onNodeWithText("Create manually").performClick()
        rule.onNodeWithText("Doctor name *").performTextInput("Dr UI Test")
        rule.onNodeWithText("Qualification").performScrollTo().performTextInput("MD")
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Save Doctor"))
        rule.onNodeWithText("Save Doctor").performClick()
        rule.waitUntil(10000) {
            rule.onAllNodesWithText("Dr UI Test").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Dr UI Test").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Edit doctor"))
        rule.onNodeWithText("Edit doctor").performClick()
        rule.onNodeWithText("Doctor name *").performTextReplacement("Unsaved change")
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        rule.onNodeWithText("Dr UI Test").assertExists()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Delete doctor"))
        rule.onNodeWithText("Delete doctor").performClick()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        rule.onNodeWithText("Dr UI Test").assertExists()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Delete doctor"))
        rule.onNodeWithText("Delete doctor").performClick()
        rule.onNodeWithText("Delete", substring = false).performClick()
        rule.waitUntil(10000) {
            rule.onAllNodesWithText("Dr UI Test").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun manualTaskReviewSaveAndDelete() {
        rule.onNodeWithText("Add task", useUnmergedTree = true).performClick()
        rule.onNodeWithText("Create manually").performClick()
        rule.onNodeWithText("Task title *").performTextInput("UI test task")
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(isToggleable())
        rule.onNode(isToggleable()).performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Save Task"))
        rule.onNodeWithText("Save Task").performClick()
        rule.waitUntil(10000) {
            rule.onAllNodesWithText("UI test task").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("Delete UI test task").performClick()
        rule.onNodeWithText("Delete", substring = false).performClick()
        rule.waitUntil(10000) {
            rule.onAllNodesWithText("UI test task").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun bottomTabsAndSettingsRemainVisible() {
        rule.onNodeWithText("Ask AI", useUnmergedTree = true).performClick()
        rule.onNodeWithText("What’s on your mind?").assertIsDisplayed()
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Make Athii yours.").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Tasks", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun horizontalSwipesMoveBetweenPages() {
        rule.onNodeWithTag("main-pager").performTouchInput { swipeLeft(durationMillis = 450) }
        rule.onNodeWithText("Search name, department, clinic").assertIsDisplayed()
        rule.onNodeWithTag("main-pager").performTouchInput { swipeLeft(durationMillis = 450) }
        rule.onNodeWithText("What’s on your mind?").assertIsDisplayed()
        rule.onNodeWithTag("main-pager").performTouchInput { swipeRight(durationMillis = 450) }
        rule.onNodeWithText("Search name, department, clinic").assertIsDisplayed()
    }

    @Test
    fun addChoicesShareOneEqualWidthRow() {
        rule.onNodeWithText("Add task", useUnmergedTree = true).performClick()
        val manual = rule.onNodeWithText("Create manually").fetchSemanticsNode().boundsInRoot
        val scan = rule.onNodeWithText("Scan image").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertEquals(manual.top, scan.top, 2f)
        org.junit.Assert.assertEquals(manual.width, scan.width, 2f)
    }

    @Test
    fun chatKeyboardLeavesFooterFixedAndMovesOnlyComposer() {
        rule.onNodeWithText("Ask AI", useUnmergedTree = true).performClick()
        val footerBefore =
            rule.onNodeWithTag("active-tab-indicator").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithText("Ask anything about your day").performClick()
        rule.waitUntil(10000) {
            var visible = false
            rule.activity.runOnUiThread {
                visible =
                    androidx.core.view.ViewCompat.getRootWindowInsets(
                            rule.activity.window.decorView
                        )
                        ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            }
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .waitForIdleSync()
            visible
        }
        val footerDuring =
            rule.onNodeWithTag("active-tab-indicator").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertEquals(footerBefore.top, footerDuring.top, 2f)
        var keyboardTop = 0
        rule.runOnIdle {
            val root = rule.activity.window.decorView
            val ime =
                androidx.core.view.ViewCompat.getRootWindowInsets(root)!!
                    .getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
                    .bottom
            keyboardTop = root.height - ime
        }
        org.junit.Assert.assertTrue("Footer stays behind keyboard", footerDuring.top >= keyboardTop)
        org.junit.Assert.assertTrue(
            "Composer is above keyboard",
            rule
                .onNodeWithContentDescription("Send message")
                .fetchSemanticsNode()
                .boundsInRoot
                .bottom <= keyboardTop,
        )
        rule.onNodeWithContentDescription("Send message").assertIsDisplayed()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            )
        rule.waitForIdle()
        val footerAfter =
            rule.onNodeWithTag("active-tab-indicator").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertEquals(footerBefore.top, footerAfter.top, 2f)
    }
}
