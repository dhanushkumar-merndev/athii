package com.oki.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private val ConfettiColors =
    listOf(
        Color(0xFFFF5252), // Coral Red
        Color(0xFFFF4081), // Pink
        Color(0xFFE040FB), // Magenta
        Color(0xFF7C4DFF), // Purple
        Color(0xFF536DFE), // Indigo
        Color(0xFF448AFF), // Blue
        Color(0xFF18FFFF), // Cyan
        Color(0xFF69F0AE), // Mint
        Color(0xFFFFD740), // Gold
        Color(0xFFFFAB40), // Orange
    )

private class ConfettiParticle(
    val color: Color,
    val width: Float,
    val height: Float,
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val gravity: Float,
    val drag: Float,
    var rotation: Float,
    val vRotation: Float,
    var flipAngle: Float,
    val vFlip: Float,
    val isCircle: Boolean,
)

@Composable
fun ConfettiCelebration(milestone: Int?, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    if (milestone == null) return

    val progress = remember { Animatable(0f) }
    var particles by remember(milestone) { mutableStateOf<List<ConfettiParticle>>(emptyList()) }

    LaunchedEffect(milestone) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 3200, easing = LinearEasing),
        )
        onFinished()
    }

    val milestoneText =
        when (milestone) {
            5 -> "🎉 Surprise! 5 tasks done today! Great job!"
            20 -> "🌟 Superstar! 20 tasks completed! You're on fire!"
            50 -> "👑 Legendary! 50 tasks conquered today!"
            else -> "✨ Milestone reached! $milestone tasks completed today!"
        }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onFinished,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            if (particles.isEmpty() && width > 0 && height > 0) {
                val random = Random(milestone.hashCode())
                val list = ArrayList<ConfettiParticle>(85)
                val spawnX = width / 2f
                val spawnY = height * 0.45f

                for (i in 0 until 85) {
                    val angle = (random.nextFloat() * 140f - 160f) * (Math.PI / 180f)
                    val speed = random.nextFloat() * 22f + 14f
                    val vx = (cos(angle) * speed).toFloat()
                    val vy = (sin(angle) * speed).toFloat()
                    list.add(
                        ConfettiParticle(
                            color = ConfettiColors[random.nextInt(ConfettiColors.size)],
                            width = random.nextFloat() * 10f + 8f,
                            height = random.nextFloat() * 16f + 10f,
                            x = spawnX + (random.nextFloat() - 0.5f) * 60f,
                            y = spawnY + (random.nextFloat() - 0.5f) * 30f,
                            vx = vx,
                            vy = vy,
                            gravity = random.nextFloat() * 0.35f + 0.38f,
                            drag = 0.985f,
                            rotation = random.nextFloat() * 360f,
                            vRotation = (random.nextFloat() - 0.5f) * 14f,
                            flipAngle = random.nextFloat() * 360f,
                            vFlip = (random.nextFloat() - 0.5f) * 18f,
                            isCircle = random.nextFloat() < 0.25f,
                        )
                    )
                }
                particles = list
            }

            val alpha = (1f - (progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f)

            for (p in particles) {
                p.x += p.vx
                p.y += p.vy
                p.vx *= p.drag
                p.vy = (p.vy + p.gravity) * p.drag
                p.rotation += p.vRotation
                p.flipAngle += p.vFlip

                val cosFlip = cos(p.flipAngle * (Math.PI / 180f)).toFloat()
                val currentHeight = kotlin.math.abs(p.height * cosFlip)

                rotate(degrees = p.rotation, pivot = Offset(p.x, p.y)) {
                    if (p.isCircle) {
                        drawCircle(
                            color = p.color.copy(alpha = alpha),
                            radius = p.width / 2f,
                            center = Offset(p.x, p.y),
                        )
                    } else {
                        drawRect(
                            color = p.color.copy(alpha = alpha),
                            topLeft = Offset(p.x - p.width / 2f, p.y - currentHeight / 2f),
                            size = Size(p.width, currentHeight),
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = progress.value < 0.92f,
            enter = fadeIn(tween(300)) + scaleIn(animationSpec = tween(300), initialScale = 0.85f),
            exit = fadeOut(tween(300)) + scaleOut(animationSpec = tween(300), targetScale = 0.9f),
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 8.dp,
                modifier = Modifier.padding(horizontal = 28.dp),
            ) {
                Text(
                    text = milestoneText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp),
                )
            }
        }
    }
}
