package com.enigma.dreamer.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── Dynamic color state (album-art driven) ────────────────────────────────────

/**
 * Holds the two colors extracted from the current album art:
 *  - [bg]   dominant (darkened) color used as the NowPlaying background
 *  - [fg]   contrast color (near-white or near-black) for text/icons on top of [bg]
 *
 * Provided via [LocalDynamicColors] so any composable in the tree can read it
 * without prop-drilling through every screen.
 */
@Stable
class DynamicColors(bg: Color, fg: Color) {
    var bg by mutableStateOf(bg)
        private set
    var fg by mutableStateOf(fg)
        private set

    fun update(newBg: Color, newFg: Color) {
        bg = newBg
        fg = newFg
    }
}

val LocalDynamicColors = compositionLocalOf {
    DynamicColors(bg = Surface1, fg = TextPrimary)
}

// ── Material color scheme ─────────────────────────────────────────────────────

private val DarkColorScheme = darkColorScheme(
    primary          = Amber,
    onPrimary        = Amoled,
    primaryContainer = AmberDim,
    secondary        = AmberGlow,
    background       = Amoled,
    surface          = Surface1,
    surfaceVariant   = Surface2,
    onBackground     = TextPrimary,
    onSurface        = TextPrimary,
    onSurfaceVariant = TextSecondary,
    error            = ErrorRed
)

// ── Typography ────────────────────────────────────────────────────────────────

val AppTypography = Typography(
    displayLarge  = TextStyle(fontWeight = FontWeight.Black,    fontSize = 32.sp, letterSpacing = (-1).sp),
    displayMedium = TextStyle(fontWeight = FontWeight.Bold,     fontSize = 24.sp),
    titleLarge    = TextStyle(fontWeight = FontWeight.Bold,     fontSize = 20.sp, letterSpacing = 0.sp),
    titleMedium   = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge     = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 16.sp),
    bodyMedium    = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 14.sp),
    bodySmall     = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 12.sp, color = TextSecondary),
    labelSmall    = TextStyle(fontWeight = FontWeight.Medium,   fontSize = 11.sp, letterSpacing = 1.sp)
)

// ── Root theme composable ─────────────────────────────────────────────────────

/**
 * Wraps the app in [MaterialTheme] and provides [LocalDynamicColors].
 *
 * Pass [dominantColor] / [accentTextColor] (derived from album art in the
 * ViewModel) and they will propagate automatically to any descendant that
 * reads [LocalDynamicColors].
 */
@Composable
fun DreamerTheme(
    dominantColor:   Color = Surface1,
    accentTextColor: Color = TextPrimary,
    content: @Composable () -> Unit
) {
    val dynamicColors = remember { DynamicColors(dominantColor, accentTextColor) }
    // Keep in sync when the ViewModel emits a new color without recreating the object
    SideEffect { dynamicColors.update(dominantColor, accentTextColor) }

    CompositionLocalProvider(LocalDynamicColors provides dynamicColors) {
        MaterialTheme(
            colorScheme = DarkColorScheme,
            typography  = AppTypography,
            content     = content
        )
    }
}
