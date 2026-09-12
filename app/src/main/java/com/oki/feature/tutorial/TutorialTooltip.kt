package com.oki.feature.tutorial

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * Auto-positioned tooltip card for the guided tour.
 *
 * Placement logic:
 * - If the highlighted target is in the **upper half** of the screen → show tooltip **below**.
 * - If the highlighted target is in the **lower half** → show tooltip **above**.
 * - Clamps horizontal position to keep the tooltip within safe screen margins.
 */
@Composable
fun TutorialTooltip(
    step: TutorialStep,
    stepIndex: Int,
    totalSteps: Int,
    targetBounds: Rect?,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
    onStartTour: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val screenHeight = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }

    // Determine tooltip position: above or below the target.
    val showBelow =
        if (targetBounds == null) true // Center for full-screen overlays
        else targetBounds.center.y < screenHeight / 2f

    val tooltipPadding = 16.dp
    val spotlightPadding = 12.dp // Extra padding around the spotlight

    AnimatedVisibility(
        visible = true,
        enter =
            fadeIn(tween(280)) +
                slideInVertically(tween(280)) { if (showBelow) -it / 4 else it / 4 },
        exit = fadeOut(tween(200)),
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxSize()) {
            val alignment =
                if (step.isWelcome || step.isFinal || targetBounds == null) {
                    Alignment.Center
                } else if (showBelow) {
                    Alignment.TopCenter
                } else {
                    Alignment.BottomCenter
                }

            val yOffset: Dp =
                if (step.isWelcome || step.isFinal || targetBounds == null) {
                    0.dp
                } else if (showBelow) {
                    with(density) {
                        (targetBounds.bottom + spotlightPadding.toPx() + 16.dp.toPx()).toDp()
                    }
                } else {
                    // Tooltip above: need negative offset from bottom
                    0.dp // Handled by BottomCenter alignment + padding
                }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(22.dp),
                shadowElevation = 8.dp,
                tonalElevation = 4.dp,
                modifier =
                    Modifier.align(alignment)
                        .padding(horizontal = 20.dp)
                        .then(
                            if (step.isWelcome || step.isFinal || targetBounds == null) {
                                Modifier.padding(horizontal = 8.dp)
                            } else if (showBelow) {
                                Modifier.offset {
                                    IntOffset(0, with(density) { yOffset.roundToPx() })
                                }
                            } else {
                                // Place above the target
                                val topOfTarget = with(density) { targetBounds.top.toDp() }
                                Modifier.padding(
                                    bottom =
                                        with(density) {
                                            (screenHeight - targetBounds.top +
                                                    spotlightPadding.toPx() +
                                                    16.dp.toPx())
                                                .toDp()
                                        }
                                )
                            }
                        )
                        .widthIn(max = 380.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Icon + Title
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        step.icon?.let { icon ->
                            Icon(
                                icon,
                                contentDescription = null,
                                modifier = Modifier.size(26.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(step.title, style = MaterialTheme.typography.titleMedium)
                    }

                    // Description
                    Text(
                        step.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Step indicator (not for welcome/final)
                    if (!step.isWelcome && !step.isFinal) {
                        Text(
                            "${stepIndex + 1} of $totalSteps",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(2.dp))

                    // Navigation buttons
                    when {
                        step.isWelcome -> WelcomeButtons(onSkip, onStartTour)
                        step.isFinal -> FinalButtons(onFinish)
                        else ->
                            StepButtons(
                                stepIndex = stepIndex,
                                onPrevious = onPrevious,
                                onNext = onNext,
                                onSkip = onSkip,
                            )
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeButtons(onSkip: () -> Unit, onStart: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = onSkip,
            modifier = Modifier.weight(1f).height(48.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("Skip Tour")
        }
        Button(
            onClick = onStart,
            modifier = Modifier.weight(1f).height(48.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("Start Tour")
        }
    }
}

@Composable
private fun FinalButtons(onFinish: () -> Unit) {
    Button(
        onClick = onFinish,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text("Start Using App")
    }
}

@Composable
private fun StepButtons(
    stepIndex: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onSkip, modifier = Modifier.height(44.dp)) { Text("Skip") }
        Spacer(Modifier.weight(1f))
        if (stepIndex > 0) {
            IconButton(onClick = onPrevious, modifier = Modifier.size(44.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Previous")
            }
        }
        Spacer(Modifier.width(4.dp))
        FilledTonalButton(
            onClick = onNext,
            modifier = Modifier.height(44.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Next")
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
