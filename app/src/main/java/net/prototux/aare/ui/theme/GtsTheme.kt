package net.prototux.aare.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.prototux.aare.R

/**
 * The GTS look (our own, 2026-11-08): graphite surfaces and one red accent like the car's own
 * instruments, Barlow italic type, and its own home screen (GtsDashboard). Dark by day and night.
 */
val GtsTheme: CarThemeSpec by lazy {
    CarThemeSpec(
        id = GTS_THEME_ID,
        name = "GTS",
        day = GtsColors,
        night = GtsNightColors,
        dayColors = AareTheme.nightColors,
        nightColors = AareTheme.nightColors,
        typography = CarTypography.withFamily(Barlow),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(36.dp),
        ),
        dimens = CarDimens(railWidth = 72.dp, railButton = 60.dp, railIcon = 30.dp, gutter = 6.dp),
    )
}

/**
 * The GTS theme with its type upright (Barlow without the slant) when [italic] is off, the user's
 * choice (2026-11-16); any other theme as it is.
 */
fun CarThemeSpec.withGtsFont(italic: Boolean): CarThemeSpec =
    if (id != GTS_THEME_ID || italic) this else copy(typography = CarTypography.withFamily(BarlowUpright))

/** Stored in the settings ("style_theme"); was "car_style" = "gts" before upstream's themes. */
const val GTS_THEME_ID = "gts"

/** The GTS style by day (its only look until 2026-11-08): graphite surfaces and one red accent, like the car's own instruments. */
private val GtsColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFE4002B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4A0A18),
    onPrimaryContainer = Color(0xFFFFD9DD),
    secondary = Color(0xFFB8BCC4),
    onSecondary = Color(0xFF1E2026),
    secondaryContainer = Color(0xFF262930),
    onSecondaryContainer = Color(0xFFE6E7EB),
    tertiary = Color(0xFFFFB86B),
    onTertiary = Color(0xFF2E1500),
    background = Color(0xFF0C0D10),
    onBackground = Color(0xFFF0F1F3),
    surface = Color(0xFF0C0D10),
    onSurface = Color(0xFFF0F1F3),
    surfaceVariant = Color(0xFF1C1E24),
    onSurfaceVariant = Color(0xFFA8ADB6),
    surfaceContainerLowest = Color(0xFF07080A),
    surfaceContainerLow = Color(0xFF121318),
    surfaceContainer = Color(0xFF16181D),
    surfaceContainerHigh = Color(0xFF1C1E24),
    surfaceContainerHighest = Color(0xFF262930),
    outline = Color(0xFF50545C),
    outlineVariant = Color(0xFF2A2D34),
    error = Color(0xFFFF8A80),
)

/**
 * The GTS style at night: absolute black (the car screen's own black, nothing glows), cards in
 * near-black and the same red.
 */
private val GtsNightColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFE4002B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A0812),
    onPrimaryContainer = Color(0xFFFFD9DD),
    secondary = Color(0xFFA8ADB6),
    onSecondary = Color(0xFF16181C),
    secondaryContainer = Color(0xFF16171A),
    onSecondaryContainer = Color(0xFFE0E1E5),
    tertiary = Color(0xFFFFB86B),
    onTertiary = Color(0xFF2E1500),
    background = Color.Black,
    onBackground = Color(0xFFE6E7EA),
    surface = Color.Black,
    onSurface = Color(0xFFE6E7EA),
    surfaceVariant = Color(0xFF111214),
    onSurfaceVariant = Color(0xFF9A9FA8),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF060607),
    surfaceContainer = Color(0xFF0A0A0C),
    surfaceContainerHigh = Color(0xFF111214),
    surfaceContainerHighest = Color(0xFF1A1B1E),
    outline = Color(0xFF3E4148),
    outlineVariant = Color(0xFF1C1D20),
    error = Color(0xFFFF8A80),
)

/** Barlow Semi Condensed (SIL OFL, see assets/fonts/OFL-Barlow.txt). The files are italic already. */
private val Barlow = FontFamily(
    Font(R.font.barlow_semi_condensed_medium_italic, FontWeight.Normal),
    Font(R.font.barlow_semi_condensed_medium_italic, FontWeight.Medium),
    Font(R.font.barlow_semi_condensed_bold_italic, FontWeight.Bold),
)

/** The same Barlow Semi Condensed, upright (SIL OFL, from Google Fonts). */
private val BarlowUpright = FontFamily(
    Font(R.font.barlow_semi_condensed_medium, FontWeight.Normal),
    Font(R.font.barlow_semi_condensed_medium, FontWeight.Medium),
    Font(R.font.barlow_semi_condensed_bold, FontWeight.Bold),
)

private fun Typography.withFamily(family: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)

