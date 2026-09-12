package com.oki.feature.tutorial

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** How long a target must hold still before the spotlight commits to it. */
private const val SETTLE_MS = 160L

/** How long to wait for a target to come fully on screen before its step is skipped. */
private const val TARGET_TIMEOUT_MS = 2_000L

/**
 * Full-screen guided-tour overlay.
 *
 * Draws a semi-transparent dark scrim over the entire screen and punches a rounded-rectangle
 * spotlight cutout around the current tutorial target. A [TutorialTooltip] floats beside the cutout
 * with step information and navigation buttons.
 *
 * Targets are only used once they are fully on screen and have stopped moving: the pager keeps the
 * neighbouring page composed off-screen, and screens slide in during navigation, so raw bounds are
 * often mid-flight. While waiting, only the scrim is shown, so nothing flashes up and vanishes.
 */
@Composable
fun TutorialOverlay(
    step: TutorialStep,
    stepIndex: Int,
    totalSteps: Int,
    registry: TutorialTargetRegistry,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
    onStartTour: () -> Unit,
    showSkipConfirm: Boolean,
    onConfirmSkip: () -> Unit,
    onCancelSkip: () -> Unit,
    onTargetUnavailable: () -> Unit,
) {
    val density = LocalDensity.current
    val spotlightPaddingPx = with(density) { 10.dp.toPx() }
    val cornerRadiusPx = with(density) { 18.dp.toPx() }
    val glowStrokePx = with(density) { 2.dp.toPx() }
    var overlay by remember { mutableStateOf<LayoutCoordinates?>(null) }

    // Registry bounds are window coordinates; drawing happens in the overlay's own space.
    val onScreen: Rect? = run {
        val coordinates = overlay?.takeIf { it.isAttached } ?: return@run null
        val window =
            step.targetKey.takeIf { it.isNotEmpty() }?.let(registry::boundsFor) ?: return@run null
        val local = Rect(coordinates.windowToLocal(window.topLeft), window.size)
        val width = coordinates.size.width.toFloat()
        val height = coordinates.size.height.toFloat()
        local.takeIf {
            it.left >= -1f && it.right <= width + 1f && it.bottom > 0f && it.top < height
        }
    }

    // Debounced: restarts on every movement, so it only commits once the target is still.
    var settled by remember(step.id) { mutableStateOf<Rect?>(null) }
    LaunchedEffect(step.id, onScreen) {
        if (onScreen == null) {
            settled = null
            return@LaunchedEffect
        }
        if (onScreen == settled) return@LaunchedEffect
        delay(SETTLE_MS)
        settled = onScreen
    }

    val waiting = step.targetKey.isNotEmpty() && settled == null
    LaunchedEffect(step.id, waiting) {
        if (waiting) {
            delay(TARGET_TIMEOUT_MS)
            onTargetUnavailable()
        }
    }

    // One smooth move per settled target, never a chase of per-frame layout updates.
    val spot = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    var spotPlaced by remember { mutableStateOf(false) }
    val paddedTarget = settled?.inflate(spotlightPaddingPx)
    LaunchedEffect(paddedTarget) {
        val target = paddedTarget ?: return@LaunchedEffect
        if (spotPlaced) spot.animateTo(target, tween(320, easing = FastOutSlowInEasing))
        else {
            spot.snapTo(target)
            spotPlaced = true
        }
    }

    // Subtle pulse for the spotlight border glow.
    val pulseTransition = rememberInfiniteTransition(label = "spotlight_pulse")
    val pulseAlpha by
        pulseTransition.animateFloat(
            initialValue = 0.25f,
            targetValue = 0.55f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(1400, easing = EaseInOut),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "pulse_alpha",
        )

    val scrimColor = Color.Black.copy(alpha = 0.76f)
    val glowColor = MaterialTheme.colorScheme.primary
    val showSpotlight = paddedTarget != null && !step.isWelcome && !step.isFinal

    // Back button: go to previous step or show skip dialog.
    BackHandler { if (stepIndex > 0) onPrevious() else onSkip() }

    Box(Modifier.fillMaxSize().onGloballyPositioned { overlay = it }) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                // Consume all taps so the user can't interact with underlying UI.
                detectTapGestures { /* consumed */ }
            }
        ) {
            if (showSpotlight) {
                val hole = spot.value.intersect(Rect(Offset.Zero, size))
                val corner = CornerRadius(cornerRadiusPx, cornerRadiusPx)
                val scrim =
                    Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(Rect(Offset.Zero, size))
                        addRoundRect(RoundRect(hole, corner))
                    }
                drawPath(scrim, scrimColor)
                drawRoundRect(
                    color = glowColor.copy(alpha = pulseAlpha),
                    topLeft = hole.topLeft,
                    size = hole.size,
                    cornerRadius = corner,
                    style = Stroke(glowStrokePx),
                )
            } else {
                drawRect(scrimColor)
            }
        }

        if (!waiting) {
            TutorialTooltip(
                step = step,
                stepIndex = stepIndex,
                totalSteps = totalSteps,
                targetBounds = if (showSpotlight) paddedTarget else null,
                onNext = onNext,
                onPrevious = onPrevious,
                onSkip = onSkip,
                onFinish = onFinish,
                onStartTour = onStartTour,
            )
        }
    }

    // Skip confirmation dialog.
    if (showSkipConfirm) {
        AlertDialog(
            onDismissRequest = onCancelSkip,
            title = { Text("Skip tutorial?") },
            text = { Text("You can replay it anytime from Settings.") },
            confirmButton = { TextButton(onClick = onConfirmSkip) { Text("Skip") } },
            dismissButton = { TextButton(onClick = onCancelSkip) { Text("Continue Tour") } },
        )
    }
}
