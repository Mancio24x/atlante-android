package app.atlante.library.ui.compose

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.Typography
import app.atlante.library.ui.ThemePalette

/**
 * Compose theme built from the same palette as the View screens (one of the nine palettes, or the
 * Material You colors already resolved by MainActivity), so both kinds of screen look identical.
 */
fun ThemePalette.colorScheme(): ColorScheme {
    val paper = Color(paper); val surface = Color(surface); val ink = Color(ink); val muted = Color(muted)
    val accent = Color(accent); val onAccent = Color(onAccent); val line = Color(line); val warm = Color(warm)
    return if (dark) darkColorScheme(
        primary = accent, onPrimary = onAccent, primaryContainer = line, onPrimaryContainer = ink,
        secondary = accent, onSecondary = onAccent, secondaryContainer = line, onSecondaryContainer = ink,
        tertiary = warm, background = paper, onBackground = ink, surface = paper, onSurface = ink,
        surfaceVariant = surface, onSurfaceVariant = muted, outline = muted, outlineVariant = line,
        surfaceContainerLowest = surface, surfaceContainerLow = surface, surfaceContainer = surface,
        surfaceContainerHigh = surface, surfaceContainerHighest = line,
    ) else lightColorScheme(
        primary = accent, onPrimary = onAccent, primaryContainer = line, onPrimaryContainer = ink,
        secondary = accent, onSecondary = onAccent, secondaryContainer = line, onSecondaryContainer = ink,
        tertiary = warm, background = paper, onBackground = ink, surface = paper, onSurface = ink,
        surfaceVariant = surface, onSurfaceVariant = muted, outline = muted, outlineVariant = line,
        surfaceContainerLowest = surface, surfaceContainerLow = surface, surfaceContainer = surface,
        surfaceContainerHigh = surface, surfaceContainerHighest = line,
    )
}

/** Serif headlines like the rest of Atlante, Material 3 defaults for everything else. */
private val AtlanteTypography = Typography().let { t ->
    t.copy(
        headlineLarge = t.headlineLarge.copy(fontFamily = FontFamily.Serif),
        headlineMedium = t.headlineMedium.copy(fontFamily = FontFamily.Serif),
        headlineSmall = t.headlineSmall.copy(fontFamily = FontFamily.Serif),
        titleLarge = t.titleLarge.copy(fontFamily = FontFamily.Serif),
    )
}

@Composable
fun AtlanteTheme(palette: ThemePalette, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = palette.colorScheme(), typography = AtlanteTypography, content = content)
}

/** The copper/warm accent of the palette (ratings, highlights). */
val ThemePalette.warmColor: Color get() = Color(warm)
