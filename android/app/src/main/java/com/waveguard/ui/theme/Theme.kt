package com.waveguard.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val WaveGuardDarkColorScheme = darkColorScheme(
    primary = CyanActive,
    onPrimary = NavyBackground,
    primaryContainer = SurfaceDark,
    onPrimaryContainer = CyanActive,
    secondary = GreenSafe,
    onSecondary = NavyBackground,
    secondaryContainer = CardDark,
    onSecondaryContainer = GreenSafe,
    tertiary = AmberWarning,
    onTertiary = NavyBackground,
    tertiaryContainer = CardDark,
    onTertiaryContainer = AmberWarning,
    error = RedAlert,
    onError = NavyBackground,
    errorContainer = CardDark,
    onErrorContainer = RedAlert,
    background = NavyBackground,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = CardDark,
    onSurfaceVariant = TextSecondary,
    outline = TextSecondary,
    outlineVariant = SurfaceDark,
    scrim = NavyBackground,
    inverseSurface = TextPrimary,
    inverseOnSurface = NavyBackground,
    inversePrimary = NavyBackground
)

val WaveGuardShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun WaveGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WaveGuardDarkColorScheme,
        typography = WaveGuardTypography,
        shapes = WaveGuardShapes,
        content = content
    )
}
