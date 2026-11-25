package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

data class UChatExtendedColors(
    val outgoingBubble: Color = UChatOutgoingBubble,
    val incomingBubble: Color = UChatIncomingBubble,
    val readTickBlue: Color = UChatReadBlue,
    val missedRed: Color = UChatMissedRed,
    val divider: Color = UChatDivider,
    val cardSurface: Color = UChatCardSurface,
    val secondaryBackground: Color = UChatSurfaceSecondary
)

val LocalUChatColors = staticCompositionLocalOf { UChatExtendedColors() }

private val UChatShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun UChatTheme(
    accentHex: String = "#25D366",
    fontScale: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val preset = accentPresetForHex(accentHex)
    val colorScheme = darkColorScheme(
        primary = preset.primary,
        onPrimary = UChatBlack,
        primaryContainer = preset.outgoingBubble,
        onPrimaryContainer = UChatTextPrimary,
        secondary = preset.secondary,
        onSecondary = UChatTextPrimary,
        secondaryContainer = UChatElevatedSurface,
        onSecondaryContainer = UChatTextPrimary,
        tertiary = UChatReadBlue,
        onTertiary = UChatBlack,
        background = UChatBlack,
        onBackground = UChatTextPrimary,
        surface = UChatSurfaceSecondary,
        onSurface = UChatTextPrimary,
        surfaceVariant = UChatCardSurface,
        onSurfaceVariant = UChatTextSecondary,
        surfaceContainerLowest = UChatBlack,
        surfaceContainerLow = UChatSurfaceSecondary,
        surfaceContainer = UChatCardSurface,
        surfaceContainerHigh = UChatElevatedSurface,
        surfaceContainerHighest = Color(0xFF28282C),
        outline = UChatDivider,
        outlineVariant = Color(0xFF1E1E22),
        error = UChatMissedRed,
        onError = UChatTextPrimary
    )

    val extended = UChatExtendedColors(
        outgoingBubble = preset.outgoingBubble,
        incomingBubble = UChatIncomingBubble,
        readTickBlue = UChatReadBlue,
        missedRed = UChatMissedRed,
        divider = UChatDivider,
        cardSurface = UChatCardSurface,
        secondaryBackground = UChatSurfaceSecondary
    )

    CompositionLocalProvider(LocalUChatColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = buildUChatTypography(fontScale),
            shapes = UChatShapes,
            content = content
        )
    }
}
