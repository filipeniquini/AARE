package net.prototux.aare.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.prototux.aare.R

/**
 * The in-car look: calm dark surfaces, one warm accent, big type and big targets.
 *
 * Car screens are viewed from ~70 cm at a glance, so sizes follow the car-UI rule of thumb:
 * touch targets ≥ 76 dp, body text ≥ 24 sp, and no more than two text styles per card.
 */
@Immutable
data class CarDimens(
    val touchTarget: Dp = 76.dp,
    val railWidth: Dp = 96.dp,
    val gutter: Dp = 12.dp,
    val cardRadius: Dp = 28.dp,
    val iconSize: Dp = 36.dp,
    /** Buttons and icons on the rail (the GTS bar is slimmer than Google's 76 dp). */
    val railButton: Dp = 76.dp,
    val railIcon: Dp = 36.dp,
)

/** The GTS style's slimmer bottom bar, as the user asked (2026-11-08): about 9 mm buttons on the VW Play. */
/** Its cards sit closer together too (2026-11-11: the gaps wasted screen the player could use). */
val GtsCarDimens = CarDimens(railWidth = 72.dp, railButton = 60.dp, railIcon = 30.dp, gutter = 6.dp)

val LocalCarDimens = staticCompositionLocalOf { CarDimens() }

/** Brand accent: a river teal (the Aare is famously turquoise). */
private val Teal = Color(0xFF3DDBC6)
private val TealDeep = Color(0xFF00695C)

private val NightColors: ColorScheme = darkColorScheme(
    primary = Teal,
    onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF0E3B36),
    onPrimaryContainer = Color(0xFFB9F5EA),
    secondary = Color(0xFFB4CAC5),
    onSecondary = Color(0xFF1F3531),
    secondaryContainer = Color(0xFF263330),
    onSecondaryContainer = Color(0xFFD0E6E1),
    tertiary = Color(0xFFFFB86B),
    onTertiary = Color(0xFF2E1500),
    background = Color(0xFF0B0F0F),
    onBackground = Color(0xFFE3E9E7),
    surface = Color(0xFF0B0F0F),
    onSurface = Color(0xFFE3E9E7),
    surfaceVariant = Color(0xFF1A2120),
    onSurfaceVariant = Color(0xFFAFBAB7),
    surfaceContainerLowest = Color(0xFF060909),
    surfaceContainerLow = Color(0xFF121818),
    surfaceContainer = Color(0xFF161D1C),
    surfaceContainerHigh = Color(0xFF1D2524),
    surfaceContainerHighest = Color(0xFF26302E),
    outline = Color(0xFF5E6B68),
    outlineVariant = Color(0xFF2E3836),
    error = Color(0xFFFF8A80),
)

private val DayColors: ColorScheme = lightColorScheme(
    primary = TealDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB9F5EA),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635F),
    secondaryContainer = Color(0xFFCCE8E2),
    onSecondaryContainer = Color(0xFF06201C),
    tertiary = Color(0xFF8B5000),
    background = Color(0xFFF3F7F6),
    onBackground = Color(0xFF161D1C),
    surface = Color(0xFFF3F7F6),
    onSurface = Color(0xFF161D1C),
    surfaceVariant = Color(0xFFDAE5E2),
    onSurfaceVariant = Color(0xFF3F4947),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFECF2F0),
    surfaceContainer = Color(0xFFE6ECEA),
    surfaceContainerHigh = Color(0xFFE0E7E5),
    surfaceContainerHighest = Color(0xFFD9E1DF),
    outline = Color(0xFF6F7977),
    outlineVariant = Color(0xFFBEC9C6),
)

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

private val CarType = Typography(
    displaySmall = TextStyle(fontSize = 44.sp, lineHeight = 52.sp, fontWeight = FontWeight.Medium),
    headlineMedium = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium),
    headlineSmall = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 24.sp, lineHeight = 32.sp),
    bodyMedium = TextStyle(fontSize = 22.sp, lineHeight = 30.sp),
    labelLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
)

private val GtsType = CarType.withFamily(Barlow)

private val CarShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** Whether the car screen uses the GTS style (its own home screen layout). */
val LocalGtsStyle = staticCompositionLocalOf { false }

/** Whether the car screen is in its night colors (the GTS day and night are both dark). */
val LocalCarNight = staticCompositionLocalOf { true }

@Composable
fun CarTheme(night: Boolean, gts: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (gts) (if (night) GtsNightColors else GtsColors) else if (night) NightColors else DayColors,
        typography = if (gts) GtsType else CarType,
        shapes = CarShapes,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalGtsStyle provides gts, LocalCarNight provides night, content = content)
    }
}

/** Phone UI theme: same palette, normal phone type scale. */
@Composable
fun PhoneTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) NightColors else DayColors, content = content)
}
