package com.oki.core.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oki.core.ai.friendlyError
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

abstract class ActionViewModel : ViewModel() {
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun clearError() {
        _error.value = null
    }

    fun action(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = friendlyError(e)
            } finally {
                _busy.value = false
            }
        }
    }
}

fun displayDate(time: Long): String =
    Instant.ofEpochMilli(time)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("EEE, d MMM · h:mm a"))

@Composable
fun PageHeading(title: String, subtitle: String) {
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 42.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Icon(icon, null, Modifier.padding(22.dp).size(32.dp))
        }
        Text(
            title,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun ErrorBanner(message: String?) {
    if (message != null)
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                message,
                Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
}

@Composable
fun ConfirmDelete(title: String, text: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        icon = { Icon(Icons.Outlined.DeleteOutline, null) },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = {
                    dismiss()
                    confirm()
                }
            ) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Composable
fun Field(
    label: String,
    value: String,
    change: (String) -> Unit,
    modifier: Modifier = Modifier,
    multiline: Boolean = false,
    suggestions: List<String> = emptyList(),
) {
    val query = value.trimStart()
    val match =
        remember(query, suggestions) {
            if (query.length < 2) null
            else
                suggestions.firstOrNull {
                    it.startsWith(query, ignoreCase = true) && !it.equals(query, ignoreCase = true)
                }
        }

    val visualTransformation =
        remember(match) {
            if (match != null) {
                androidx.compose.ui.text.input.VisualTransformation { text ->
                    val typedLength = text.length
                    val ghostText = match.substring(typedLength)
                    val builder = androidx.compose.ui.text.AnnotatedString.Builder(text.text)
                    builder.pushStyle(androidx.compose.ui.text.SpanStyle(color = Color.Gray))
                    builder.append(ghostText)
                    builder.pop()
                    val annotatedString = builder.toAnnotatedString()

                    androidx.compose.ui.text.input.TransformedText(
                        text = annotatedString,
                        offsetMapping =
                            object : androidx.compose.ui.text.input.OffsetMapping {
                                override fun originalToTransformed(offset: Int): Int = offset

                                override fun transformedToOriginal(offset: Int): Int =
                                    if (offset > typedLength) typedLength else offset
                            },
                    )
                }
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            }
        }

    OutlinedTextField(
        value = value,
        onValueChange = change,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        singleLine = !multiline,
        minLines = if (multiline) 3 else 1,
        visualTransformation = visualTransformation,
        trailingIcon =
            if (match != null) {
                {
                    IconButton(onClick = { change(match) }) {
                        Icon(Icons.Outlined.ArrowForward, contentDescription = "Accept suggestion")
                    }
                }
            } else null,
    )
}

@Composable
fun InlineAutocompleteField(
    value: String,
    change: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    suggestions: List<String> = emptyList(),
) {
    val query = value.trimStart()
    val match =
        remember(query, suggestions) {
            if (query.length < 2) null
            else
                suggestions.firstOrNull {
                    it.startsWith(query, ignoreCase = true) && !it.equals(query, ignoreCase = true)
                }
        }

    val visualTransformation =
        remember(match) {
            if (match != null) {
                androidx.compose.ui.text.input.VisualTransformation { text ->
                    val typedLength = text.length
                    val ghostText = match.substring(typedLength)
                    val builder = androidx.compose.ui.text.AnnotatedString.Builder(text.text)
                    builder.pushStyle(androidx.compose.ui.text.SpanStyle(color = Color.Gray))
                    builder.append(ghostText)
                    builder.pop()
                    val annotatedString = builder.toAnnotatedString()

                    androidx.compose.ui.text.input.TransformedText(
                        text = annotatedString,
                        offsetMapping =
                            object : androidx.compose.ui.text.input.OffsetMapping {
                                override fun originalToTransformed(offset: Int): Int = offset

                                override fun transformedToOriginal(offset: Int): Int =
                                    if (offset > typedLength) typedLength else offset
                            },
                    )
                }
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            }
        }

    OutlinedTextField(
        value = value,
        onValueChange = change,
        modifier = modifier,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
        visualTransformation = visualTransformation,
        trailingIcon =
            if (match != null) {
                {
                    IconButton(onClick = { change(match) }) {
                        Icon(Icons.Outlined.ArrowForward, contentDescription = "Accept suggestion")
                    }
                }
            } else null,
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        Modifier.padding(top = 14.dp, bottom = 6.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun Loader2Circle(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f),
    trackColor: Color = color.copy(alpha = 0.12f),
    strokeWidth: Dp = 3.dp,
    twoCircles: Boolean = true,
) {
    val transition = rememberInfiniteTransition(label = "loader2_spin")
    val outerAngle by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(900, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "outer_angle",
        )
    val innerAngle by
        transition.animateFloat(
            initialValue = 360f,
            targetValue = 0f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(1350, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "inner_angle",
        )

    Canvas(modifier = modifier) {
        val outerStroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        val outerDiameter = (size.minDimension - outerStroke.width).coerceAtLeast(1f)
        val outerTopLeft =
            Offset((size.width - outerDiameter) / 2f, (size.height - outerDiameter) / 2f)
        val outerSize = Size(outerDiameter, outerDiameter)

        if (trackColor != Color.Transparent) {
            drawCircle(color = trackColor, radius = outerDiameter / 2f, style = outerStroke)
        }

        rotate(outerAngle) {
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = outerTopLeft,
                size = outerSize,
                style = outerStroke,
            )
        }

        if (twoCircles && outerDiameter >= 24.dp.toPx()) {
            val innerStroke = Stroke(width = (strokeWidth * 0.75f).toPx(), cap = StrokeCap.Round)
            val innerDiameter = (outerDiameter * 0.58f).coerceAtLeast(1f)
            val innerTopLeft =
                Offset((size.width - innerDiameter) / 2f, (size.height - innerDiameter) / 2f)
            val innerSize = Size(innerDiameter, innerDiameter)

            if (trackColor != Color.Transparent) {
                drawCircle(
                    color = trackColor.copy(alpha = trackColor.alpha * 0.7f),
                    radius = innerDiameter / 2f,
                    style = innerStroke,
                )
            }

            rotate(innerAngle) {
                drawArc(
                    color = secondaryColor,
                    startAngle = 0f,
                    sweepAngle = 220f,
                    useCenter = false,
                    topLeft = innerTopLeft,
                    size = innerSize,
                    style = innerStroke,
                )
            }
        }
    }
}

@Composable
fun CenterLoader(modifier: Modifier = Modifier, message: String? = null, size: Dp = 44.dp) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Loader2Circle(modifier = Modifier.size(size))
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
