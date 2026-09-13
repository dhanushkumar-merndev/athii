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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oki.core.ai.friendlyError
import com.oki.core.security.DeletionApproval
import com.oki.core.security.rememberDeletionAuthenticator
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

    /**
     * For instant preference writes. Leaves [busy] untouched: toggling it disables every control
     * bound to it for a frame or two, which reads as the whole screen flickering on each tap.
     */
    fun quickAction(block: suspend () -> Unit) {
        _error.value = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = friendlyError(e)
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
fun ConfirmDelete(
    title: String,
    text: String,
    dismiss: () -> Unit,
    confirm: () -> Unit,
    requireAuthentication: Boolean = false,
) {
    val authenticator = rememberDeletionAuthenticator()
    val approval = remember(title, text) { DeletionApproval() }
    var verifying by remember(title, text) { mutableStateOf(false) }
    var verificationError by remember(title, text) { mutableStateOf<String?>(null) }
    DisposableEffect(approval, authenticator) {
        onDispose {
            approval.cancel()
            authenticator.cancel()
        }
    }
    fun cancel() {
        approval.cancel()
        authenticator.cancel()
        dismiss()
    }
    AlertDialog(
        onDismissRequest = ::cancel,
        icon = { Icon(Icons.Outlined.DeleteOutline, null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text)
                if (requireAuthentication)
                    Text("Phone verification required.", style = MaterialTheme.typography.bodySmall)
                verificationError?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !verifying,
                onClick = {
                    val request =
                        approval.begin {
                            dismiss()
                            confirm()
                        }
                    if (request != null) {
                        verifying = true
                        verificationError = null
                        if (!requireAuthentication) {
                            approval.approve(request)
                        } else
                            authenticator.authenticate(title) { success, error ->
                                if (approval.isPending(request)) {
                                    verifying = false
                                    if (success) approval.approve(request)
                                    else {
                                        approval.cancel()
                                        verificationError =
                                            error ?: "Verification canceled. Nothing was deleted."
                                    }
                                }
                            }
                    }
                },
            ) {
                Text(
                    if (verifying) "Verifying…"
                    else if (requireAuthentication) "Verify & delete" else "Delete",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = { TextButton(onClick = ::cancel) { Text("Cancel") } },
    )
}

/**
 * First suggestion that extends what the user typed (leading spaces ignored), matched ignoring
 * case. Only prefix matches qualify because the completion is drawn inline after the typed text; an
 * exact match of the typed text is never offered.
 */
internal fun inlineCompletion(text: String, suggestions: List<String>): String? {
    val typed = text.trimStart()
    if (typed.isBlank()) return null
    return suggestions.firstOrNull {
        it.length > typed.length && it.startsWith(typed, ignoreCase = true)
    }
}

/** Draws [ghost] after the typed text; the cursor can never land inside the ghost. */
private data class GhostTextTransformation(val ghost: String, val color: Color) :
    VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val typedLength = text.length
        val shown = buildAnnotatedString {
            append(text)
            withStyle(SpanStyle(color = color)) { append(ghost) }
        }
        val mapping =
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int =
                    offset.coerceIn(0, typedLength)

                override fun transformedToOriginal(offset: Int): Int =
                    offset.coerceIn(0, typedLength)
            }
        return TransformedText(shown, mapping)
    }
}

/**
 * String-in/String-out text field with an inline ghost-text completion and an accept arrow. Keeps a
 * [TextFieldValue] internally so accepting a suggestion can move the cursor to the end.
 */
@Composable
private fun InlineCompletionTextField(
    value: String,
    change: (String) -> Unit,
    suggestions: List<String>,
    modifier: Modifier,
    shape: Shape,
    singleLine: Boolean,
    minLines: Int,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
) {
    var fieldState by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    // The caller's String is the source of truth; copy() coerces selection/composition into range.
    val fieldValue = if (fieldState.text == value) fieldState else fieldState.copy(text = value)
    var lastText by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }

    val match = remember(value, suggestions) { inlineCompletion(value, suggestions) }
    val cursorAtEnd = fieldValue.selection.collapsed && fieldValue.selection.end == value.length
    val completion = match?.takeIf { focused && cursorAtEnd }
    val ghostColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val transformation =
        remember(completion, value, ghostColor) {
            if (completion == null) VisualTransformation.None
            else GhostTextTransformation(completion.substring(value.trimStart().length), ghostColor)
        }

    OutlinedTextField(
        value = fieldValue,
        onValueChange = {
            fieldState = it
            if (it.text != lastText) {
                lastText = it.text
                change(it.text)
            }
        },
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon =
            completion?.let { accepted ->
                {
                    IconButton(
                        onClick = {
                            fieldState = TextFieldValue(accepted, TextRange(accepted.length))
                            lastText = accepted
                            change(accepted)
                        }
                    ) {
                        Icon(Icons.Outlined.ArrowForward, contentDescription = "Accept suggestion")
                    }
                }
            },
        visualTransformation = transformation,
        singleLine = singleLine,
        minLines = minLines,
        shape = shape,
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
    InlineCompletionTextField(
        value = value,
        change = change,
        suggestions = suggestions,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        singleLine = !multiline,
        minLines = if (multiline) 3 else 1,
        label = { Text(label) },
    )
}

/** Applies [modifier] to the text field as-is (no forced width), so `Modifier.weight` works. */
@Composable
fun InlineAutocompleteField(
    value: String,
    change: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    suggestions: List<String> = emptyList(),
) {
    InlineCompletionTextField(
        value = value,
        change = change,
        suggestions = suggestions,
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        singleLine = true,
        minLines = 1,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
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
