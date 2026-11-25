package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// UChat Pure Dark / OLED Design System Tokens
val UChatBlack = Color(0xFF000000)
val UChatSurfaceSecondary = Color(0xFF101010)
val UChatCardSurface = Color(0xFF181818)
val UChatElevatedSurface = Color(0xFF202020)
val UChatIncomingBubble = Color(0xFF1C1C1E)
val UChatOutgoingBubble = Color(0xFF0E3B2B)
val UChatOutgoingBubbleBorder = Color(0xFF1A5E44)
val UChatDivider = Color(0xFF262626)

val UChatPrimaryGreen = Color(0xFF25D366)
val UChatSecondaryTeal = Color(0xFF128C7E)
val UChatTextPrimary = Color(0xFFFFFFFF)
val UChatTextSecondary = Color(0xFFA0A0A0)
val UChatTextTertiary = Color(0xFF6E6E73)
val UChatReadBlue = Color(0xFF34B7F1)
val UChatMissedRed = Color(0xFFFF453A)
val UChatAmber = Color(0xFFFFB300)

data class AccentPreset(
    val name: String,
    val hex: String,
    val primary: Color,
    val secondary: Color,
    val outgoingBubble: Color
)

val UChatAccentPresets = listOf(
    AccentPreset("Emerald", "#25D366", Color(0xFF25D366), Color(0xFF128C7E), Color(0xFF0E3B2B)),
    AccentPreset("Ocean Teal", "#00BFA5", Color(0xFF00BFA5), Color(0xFF00796B), Color(0xFF0A3631)),
    AccentPreset("Sky Cyan", "#29B6F6", Color(0xFF29B6F6), Color(0xFF0277BD), Color(0xFF0D2E42)),
    AccentPreset("Violet Pulse", "#9D6BFF", Color(0xFF9D6BFF), Color(0xFF6200EA), Color(0xFF261847)),
    AccentPreset("Solar Amber", "#FFB300", Color(0xFFFFB300), Color(0xFFEF6C00), Color(0xFF3A2A08))
)

fun accentPresetForHex(hex: String): AccentPreset =
    UChatAccentPresets.firstOrNull { it.hex.equals(hex, ignoreCase = true) } ?: UChatAccentPresets.first()
