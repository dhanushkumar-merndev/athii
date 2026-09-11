package com.oki.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** One shared indicator: a press never paints a second pill under an inactive destination. */
@Composable
fun AthiiBottomBar(selected: Int, select: (Int) -> Unit) {
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val position by
        animateFloatAsState(
            selected.toFloat(),
            spring(dampingRatio = 0.72f, stiffness = 400f),
            label = "tab indicator position",
        )
    val squash = remember { Animatable(1f) }
    var initial by remember { mutableStateOf(true) }
    LaunchedEffect(selected) {
        if (initial) {
            initial = false
            return@LaunchedEffect
        }
        squash.animateTo(0.86f, tween(90))
        squash.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 600f))
    }
    Surface(color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                )
                .height(80.dp)
        ) {
            val slot = maxWidth / 3
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(50),
                modifier =
                    Modifier.offset(x = (slot - 66.dp) / 2, y = 8.dp)
                        .size(66.dp, 34.dp)
                        .graphicsLayer {
                            translationX = slot.toPx() * position * direction
                            scaleY = squash.value
                            scaleX = 2f - squash.value
                        }
                        .testTag("active-tab-indicator"),
            ) {}
            Row(Modifier.fillMaxSize().selectableGroup()) {
                val labels = listOf("Tasks", "Doctors", "Ask AI")
                val icons =
                    listOf(
                        Icons.Outlined.CheckCircleOutline,
                        Icons.Outlined.MedicalServices,
                        Icons.Outlined.AutoAwesome,
                    )
                labels.forEachIndexed { index, label ->
                    val color by
                        animateColorAsState(
                            if (selected == index) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            label = "tab label",
                        )
                    Column(
                        Modifier.weight(1f)
                            .fillMaxHeight()
                            .selectable(
                                selected = selected == index,
                                role = Role.Tab,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { select(index) },
                            )
                            .padding(top = 13.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(icons[index], null, Modifier.size(24.dp), tint = color)
                        Spacer(Modifier.height(9.dp))
                        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
                    }
                }
            }
        }
    }
}
