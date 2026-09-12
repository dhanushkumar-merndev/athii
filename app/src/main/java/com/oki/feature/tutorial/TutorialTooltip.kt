package com.oki.feature.tutorial

import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Tooltip card for the guided tour, placed from its measured size rather than guessed.
 *
 * Goes below the highlighted target when it fits, otherwise above. A target too tall for either (a
 * whole settings card) gets the card pinned to the bottom of the safe area, over the target,
 * instead of pushed off screen. [targetBounds] must be in the same coordinates as this layout,
 * which fills the overlay; status and navigation bar insets are kept clear.
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
    val insetTop = WindowInsets.safeDrawing.getTop(density)
    val insetBottom = WindowInsets.safeDrawing.getBottom(density)
    val appear = remember(step.id) { Animatable(0f) }
    LaunchedEffect(step.id) { appear.animateTo(1f, tween(220)) }

    Layout(
        content = {
            TooltipCard(
                step,
                stepIndex,
                totalSteps,
                onNext,
                onPrevious,
                onSkip,
                onFinish,
                onStartTour,
            )
        },
        modifier = modifier.fillMaxSize().graphicsLayer { alpha = appear.value },
    ) { measurables, constraints ->
        val margin = 20.dp.roundToPx()
        val gap = 14.dp.roundToPx()
        val width = minOf(constraints.maxWidth - 2 * margin, 380.dp.roundToPx()).coerceAtLeast(0)
        val top = insetTop + margin
        val bottom = constraints.maxHeight - insetBottom - margin
        val card =
            measurables
                .single()
                .measure(
                    Constraints(
                        minWidth = width,
                        maxWidth = width,
                        maxHeight = (bottom - top).coerceAtLeast(0),
                    )
                )
        val y =
            if (targetBounds == null) (constraints.maxHeight - card.height) / 2
            else {
                val below = targetBounds.bottom.roundToInt() + gap
                val above = targetBounds.top.roundToInt() - gap - card.height
                when {
                    below + card.height <= bottom -> below
                    above >= top -> above
                    else -> bottom - card.height
                }.coerceIn(top, (bottom - card.height).coerceAtLeast(top))
            }
        layout(constraints.maxWidth, constraints.maxHeight) {
            card.place((constraints.maxWidth - card.width) / 2, y)
        }
    }
}

@Composable
private fun TooltipCard(
    step: TutorialStep,
    stepIndex: Int,
    totalSteps: Int,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
    onStartTour: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(22.dp),
        shadowElevation = 8.dp,
        tonalElevation = 4.dp,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
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
