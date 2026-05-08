package com.enigma.dreamer.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── Palette ───────────────────────────────────────────────────────────────────
val Amoled       = Color(0xFF000000)
val Surface1     = Color(0xFF0D0D0D)
val Surface2     = Color(0xFF161616)
val Surface3     = Color(0xFF1E1E1E)
val Amber        = Color(0xFFFFB300)
val AmberDim     = Color(0xFF7A5500)
val AmberGlow    = Color(0xFFFFC94D)
val TextPrimary  = Color(0xFFEEEEEE)
val TextSecondary= Color(0xFF888888)
val TextMuted    = Color(0xFF444444)
val LyricActive  = Color(0xFFFFB300)
val LyricInactive= Color(0xFF555555)
val ErrorRed     = Color(0xFFFF5252)

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

val AppTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.Black, fontSize = 32.sp, letterSpacing = (-1).sp),
    displayMedium= TextStyle(fontWeight = FontWeight.Bold,  fontSize = 24.sp),
    titleLarge   = TextStyle(fontWeight = FontWeight.Bold,  fontSize = 20.sp, letterSpacing = 0.sp),
    titleMedium  = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge    = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium   = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall    = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, color = TextSecondary),
    labelSmall   = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.sp)
)

@Composable
fun DreamerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = AppTypography,
        content     = content
    )
}
