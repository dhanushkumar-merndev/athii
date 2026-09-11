package com.oki.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.oki.core.storage.Appearance

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFF1F1F1),
        onPrimary = Color(0xFF171717),
        primaryContainer = Color(0xFF363636),
        onPrimaryContainer = Color(0xFFF5F5F5),
        secondary = Color(0xFFA9CDB9),
        secondaryContainer = Color(0xFF26382E),
        onSecondaryContainer = Color(0xFFCDE7D5),
        background = Color(0xFF171717),
        onBackground = Color(0xFFECECEC),
        surface = Color(0xFF171717),
        onSurface = Color(0xFFECECEC),
        surfaceVariant = Color(0xFF2F2F2F),
        onSurfaceVariant = Color(0xFFAAAAAA),
        surfaceContainer = Color(0xFF212121),
        surfaceContainerHigh = Color(0xFF2A2A2A),
        surfaceContainerLow = Color(0xFF1D1D1D),
        outline = Color(0xFF626262),
        outlineVariant = Color(0xFF353535),
        error = Color(0xFFFFB4AB),
        errorContainer = Color(0xFF4D2525),
    )
private val LightColors =
    lightColorScheme(
        primary = Color(0xFF252525),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE8E8E8),
        onPrimaryContainer = Color(0xFF202020),
        secondary = Color(0xFF446855),
        secondaryContainer = Color(0xFFDBEBDF),
        background = Color(0xFFF9F9F9),
        surface = Color(0xFFF9F9F9),
        surfaceContainer = Color(0xFFF0F0F0),
        surfaceContainerHigh = Color(0xFFE8E8E8),
        onSurfaceVariant = Color(0xFF686868),
        outlineVariant = Color(0xFFDDDDDD),
    )

@Composable
fun OkiTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark =
        appearance == Appearance.DARK || (appearance == Appearance.SYSTEM && isSystemInDarkTheme())
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography =
            Typography(
                headlineLarge =
                    MaterialTheme.typography.headlineLarge.copy(
                        fontSize = 34.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-1).sp,
                        fontFamily = FontFamily.SansSerif,
                    ),
                headlineMedium =
                    MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.5).sp,
                    ),
                titleLarge =
                    MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
            ),
        content = content,
    )
}
