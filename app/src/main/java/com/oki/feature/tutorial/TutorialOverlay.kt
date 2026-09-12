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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Full-screen guided-tour overlay.
 *
 * Draws a semi-transparent dark scrim over the entire screen and punches a rounded-rectangle
 * spotlight cutout around the current tutorial target. A [TutorialTooltip] floats beside the cutout
 * with step information and navigation buttons.
 *
 * The spotlight position and size animate smoothly between steps.
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

    // Resolve target bounds (null for welcome/final full-screen overlays).
    val targetBounds: Rect? =
        if (step.targetKey.isEmpty()) null else registry.boundsFor(step.targetKey)

    // Wait briefly for targets that haven't been measured yet.
    var waitedForTarget by remember(step.id) { mutableStateOf(false) }
    LaunchedEffect(step.id, targetBounds) {
        if (step.targetKey.isNotEmpty() && targetBounds == null && !waitedForTarget) {
            delay(800) // Give composition time to lay out and measure
            waitedForTarget = true
        }
    }
    // After waiting, if still unavailable, auto-skip.
    LaunchedEffect(waitedForTarget, targetBounds) {
        if (waitedForTarget && step.targetKey.isNotEmpty() && targetBounds == null) {
            onTargetUnavailable()
        }
    }

    // Padded spotlight rectangle.
    val paddedTarget: Rect? =
        targetBounds?.let {
            Rect(
                left = it.left - spotlightPaddingPx,
                top = it.top - spotlightPaddingPx,
                right = it.right + spotlightPaddingPx,
                bottom = it.bottom + spotlightPaddingPx,
            )
        }

    // Animated spotlight bounds.
    val animSpec = tween<Float>(320, easing = FastOutSlowInEasing)
    val animLeft by animateFloatAsState(paddedTarget?.left ?: 0f, animSpec, label = "spot_l")
    val animTop by animateFloatAsState(paddedTarget?.top ?: 0f, animSpec, label = "spot_t")
    val animRight by animateFloatAsState(paddedTarget?.right ?: 0f, animSpec, label = "spot_r")
    val animBottom by animateFloatAsState(paddedTarget?.bottom ?: 0f, animSpec, label = "spot_b")
    val animatedRect =
        if (paddedTarget != null) Rect(animLeft, animTop, animRight, animBottom) else null

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
    val glowColor = MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha)

    // Back button: go to previous step or show skip dialog.
    BackHandler { if (stepIndex > 0) onPrevious() else onSkip() }

    Box(Modifier.fillMaxSize()) {
        // Scrim + spotlight cutout
        Canvas(
            Modifier.fillMaxSize().pointerInput(step.id) {
                // Consume all taps so the user can't interact with underlying UI.
                detectTapGestures { /* consumed */ }
            }
        ) {
            // Draw full scrim.
            drawIntoCanvas { canvas ->
                val paint = Paint().apply { color = scrimColor }

                if (animatedRect != null && !step.isWelcome && !step.isFinal) {
                    // Use save layer + clear blend mode to punch the hole.
                    canvas.saveLayer(Rect(Offset.Zero, size), paint)
                    // Full scrim
                    canvas.drawRect(Rect(Offset.Zero, size), paint)

                    // Clear the spotlight area
                    val clearPaint = Paint().apply { blendMode = BlendMode.Clear }
                    val rrect =
                        RoundRect(
                            rect = animatedRect,
                            cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                        )
                    val spotlightPath = Path().apply { addRoundRect(rrect) }
                    canvas.drawPath(spotlightPath, clearPaint)

                    canvas.restore()

                    // Draw subtle outer glow ring around the cutout.
                    val glowPaint =
                        Paint().apply {
                            color = glowColor
                            style = PaintingStyle.Stroke
                            strokeWidth = with(density) { 2.dp.toPx() }
                        }
                    canvas.drawRoundRect(
                        left = animatedRect.left,
                        top = animatedRect.top,
                        right = animatedRect.right,
                        bottom = animatedRect.bottom,
                        radiusX = cornerRadiusPx,
                        radiusY = cornerRadiusPx,
                        paint = glowPaint,
                    )
                } else {
                    // No spotlight — full scrim (welcome / final / missing target)
                    canvas.drawRect(Rect(Offset.Zero, size), paint)
                }
            }
        }

        // Tooltip
        TutorialTooltip(
            step = step,
            stepIndex = stepIndex,
            totalSteps = totalSteps,
            targetBounds = animatedRect,
            onNext = onNext,
            onPrevious = onPrevious,
            onSkip = onSkip,
            onFinish = onFinish,
            onStartTour = onStartTour,
        )
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
