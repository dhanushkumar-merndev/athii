package com.oki

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.oki.core.storage.Appearance
import com.oki.core.ui.OkiTheme
import com.oki.feature.tutorial.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TutorialOverlayTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun targetWaitsForNavigationAndThenShowsItsTooltip() {
        val registry = TutorialTargetRegistry()
        var navigationReady by mutableStateOf(false)
        rule.setContent {
            OkiTheme(Appearance.DARK) {
                Box(Modifier.fillMaxSize()) {
                    Text("Target", Modifier.padding(40.dp).tutorialTarget("target", registry))
                    Overlay(registry, navigationReady = navigationReady)
                }
            }
        }
        rule.onNodeWithTag("tutorial-card").assertDoesNotExist()
        rule.runOnIdle { navigationReady = true }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("tutorial-card").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Test step").assertIsDisplayed()
        rule.onNodeWithText("Next").assertIsDisplayed()
    }

    @Test
    fun missingTargetShowsAnExitThenSkipsInsteadOfLeavingADarkScreen() {
        var skipped = false
        rule.setContent {
            OkiTheme(Appearance.DARK) {
                Overlay(TutorialTargetRegistry(), onUnavailable = { skipped = true })
            }
        }
        rule.waitUntil(1_800) {
            rule.onAllNodesWithTag("tutorial-waiting").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Skip").assertIsDisplayed()
        rule.waitUntil(5_000) { skipped }
    }

    @Test
    fun tooltipRemainsScrollableAndButtonsUsableOnShortScreenWithLargeText() {
        var next = false
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                OkiTheme(Appearance.DARK) {
                    Box(Modifier.width(280.dp).height(300.dp).testTag("viewport")) {
                        TutorialTooltip(
                            step =
                                TutorialStep(
                                    "large",
                                    "A longer tutorial title",
                                    "A detailed explanation of how to create and edit your reminders. You can return to this tutorial from Settings at any time.",
                                    "home/tasks",
                                    "",
                                ),
                            stepIndex = 1,
                            totalSteps = 7,
                            targetBounds = null,
                            onNext = { next = true },
                            onPrevious = {},
                            onSkip = {},
                            onFinish = {},
                            onStartTour = {},
                        )
                    }
                }
            }
        }
        val viewport = rule.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val card = rule.onNodeWithTag("tutorial-card").fetchSemanticsNode().boundsInRoot
        assertTrue(card.top >= viewport.top && card.bottom <= viewport.bottom)
        assertTrue(card.left >= viewport.left && card.right <= viewport.right)
        rule.onNodeWithText("Next").performScrollTo().assertIsDisplayed().performClick()
        assertTrue(next)
    }

    @Composable
    private fun Overlay(
        registry: TutorialTargetRegistry,
        navigationReady: Boolean = true,
        onUnavailable: () -> Unit = {},
    ) {
        TutorialOverlay(
            step =
                TutorialStep("test", "Test step", "A visible explanation", "home/tasks", "target"),
            stepIndex = 1,
            totalSteps = 7,
            registry = registry,
            onNext = {},
            onPrevious = {},
            onSkip = {},
            onFinish = {},
            onStartTour = {},
            showSkipConfirm = false,
            onConfirmSkip = {},
            onCancelSkip = {},
            onTargetUnavailable = onUnavailable,
            navigationReady = navigationReady,
        )
    }
}
