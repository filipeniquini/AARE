package net.prototux.aare.ui.car

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import net.prototux.aare.car.Weather

/**
 * AARE's own weather icons (2026-11-08): thin round strokes on a 24 grid, one color (the icon's
 * tint), drawn for the GTS look. They tell apart what the generic icons lumped together: partly
 * cloudy by day and night, fog, drizzle and heavy rain. Generated from one geometry script, so
 * edit them as a set.
 */
internal object WeatherIcons {
    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(
            pathData = addPathNodes(path),
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        .build()

    val ClearDay = icon("ClearDay", "M16.2 12A4.2 4.2 0 1 1 7.8 12A4.2 4.2 0 1 1 16.2 12ZM18.8 12L21.2 12M16.81 16.81L18.51 18.51M12 18.8L12 21.2M7.19 16.81L5.49 18.51M5.2 12L2.8 12M7.19 7.19L5.49 5.49M12 5.2L12 2.8M16.81 7.19L18.51 5.49")
    val ClearNight = icon("ClearNight", "M11.25 4.5A8 8 0 1 0 19.29 14.33A6.4 6.4 0 0 1 11.25 4.5Z")
    val PartlyDay = icon("PartlyDay", "M11.4 8.2A3.2 3.2 0 1 1 5 8.2A3.2 3.2 0 1 1 11.4 8.2ZM4.66 11.74L3.53 12.87M3.2 8.2L1.6 8.2M4.66 4.66L3.53 3.53M8.2 3.2L8.2 1.6M11.74 4.66L12.87 3.53M12.09 20.01H19.89A3.12 3.12 0 0 0 19.89 13.77A3.9 3.9 0 1 0 12.48 14.01A3.04 3.04 0 0 0 12.09 20.01Z")
    val PartlyNight = icon("PartlyNight", "M7.86 3.9A4.6 4.6 0 1 0 12.48 9.55A3.68 3.68 0 0 1 7.86 3.9ZM12.09 20.01H19.89A3.12 3.12 0 0 0 19.89 13.77A3.9 3.9 0 1 0 12.48 14.01A3.04 3.04 0 0 0 12.09 20.01Z")
    val Cloudy = icon("Cloudy", "M6.85 17.65H16.85A4 4 0 0 0 16.85 9.65A5 5 0 1 0 7.35 9.95A3.9 3.9 0 0 0 6.85 17.65Z")
    val Fog = icon("Fog", "M7.86 11.64H15.86A3.2 3.2 0 0 0 15.86 5.24A4 4 0 1 0 8.26 5.48A3.12 3.12 0 0 0 7.86 11.64ZM5 16L19 16M7 19.5L17 19.5M9.5 22.6L14.5 22.6")
    val Drizzle = icon("Drizzle", "M7.86 11.64H15.86A3.2 3.2 0 0 0 15.86 5.24A4 4 0 1 0 8.26 5.48A3.12 3.12 0 0 0 7.86 11.64ZM8.6 15.2L8.2 16.4M12.6 15.2L12.2 16.4M16.6 15.2L16.2 16.4M10.6 19L10.2 20.2M14.6 19L14.2 20.2")
    val Rain = icon("Rain", "M7.86 11.64H15.86A3.2 3.2 0 0 0 15.86 5.24A4 4 0 1 0 8.26 5.48A3.12 3.12 0 0 0 7.86 11.64ZM8.8 14.8L7.6 18.8M12.8 14.8L11.6 18.8M16.8 14.8L15.6 18.8")
    val HeavyRain = icon("HeavyRain", "M7.86 11.64H15.86A3.2 3.2 0 0 0 15.86 5.24A4 4 0 1 0 8.26 5.48A3.12 3.12 0 0 0 7.86 11.64ZM7.8 14.8L6.2 20.2M11.3 14.8L9.7 20.2M14.8 14.8L13.2 20.2M18.3 14.8L16.7 20.2M9.6 21.6L9.3 22.6M13.1 21.6L12.8 22.6")
    val Snow = icon("Snow", "M7.86 11.64H15.86A3.2 3.2 0 0 0 15.86 5.24A4 4 0 1 0 8.26 5.48A3.12 3.12 0 0 0 7.86 11.64ZM8.2 15.15L8.2 17.65M7.12 15.77L9.28 17.02M9.28 15.77L7.12 17.02M15.8 15.15L15.8 17.65M14.72 15.77L16.88 17.02M16.88 15.77L14.72 17.02M12 19.15L12 21.65M10.92 19.77L13.08 21.02M13.08 19.77L10.92 21.02")
    val Thunder = icon("Thunder", "M7.86 11.64H15.86A3.2 3.2 0 0 0 15.86 5.24A4 4 0 1 0 8.26 5.48A3.12 3.12 0 0 0 7.86 11.64ZM13 13.6L10.2 18H13.8L11 22.4")
}

/** The icon for a WMO weather code (as Open-Meteo gives it), by day or night. */
internal fun weatherIcon(w: Weather): ImageVector = when (w.code) {
    0, 1 -> if (w.day) WeatherIcons.ClearDay else WeatherIcons.ClearNight
    2 -> if (w.day) WeatherIcons.PartlyDay else WeatherIcons.PartlyNight
    3 -> WeatherIcons.Cloudy
    45, 48 -> WeatherIcons.Fog
    in 51..57 -> WeatherIcons.Drizzle
    61, 63, 66, 80 -> WeatherIcons.Rain
    65, 67, 81, 82 -> WeatherIcons.HeavyRain
    in 71..77, 85, 86 -> WeatherIcons.Snow
    in 95..99 -> WeatherIcons.Thunder
    else -> WeatherIcons.Cloudy
}
