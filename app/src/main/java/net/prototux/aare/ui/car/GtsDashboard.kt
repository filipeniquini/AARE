package net.prototux.aare.ui.car

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowUp
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.prototux.aare.R
import net.prototux.aare.car.Weather
import net.prototux.aare.obd.GpsReadings
import net.prototux.aare.obd.GpsTrip
import net.prototux.aare.obd.ObdReader
import net.prototux.aare.obd.ObdReadings
import androidx.compose.runtime.collectAsState
import net.prototux.aare.ui.theme.LocalCarDimens
import java.util.Locale

/**
 * The GTS home screen on a landscape car screen: the map card on the left and, on the right, the
 * car card (speed, rev bar, date, weather, the car's readings) over a 16:9 media card. Modelled
 * on the user's own dongle launcher. Readings the phone doesn't have yet show "--".
 */
@Composable
internal fun GtsDashboard(
    state: CarUiState,
    actions: CarActions,
    navMap: @Composable (CarAppEntry, Modifier) -> Unit,
    modifier: Modifier,
) {
    val gutter = LocalCarDimens.current.gutter
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(gutter)) {
        val mapCard = LocalMapCard.current
        if (mapCard != null) Card(Modifier.weight(1f).fillMaxHeight()) {
            mapCard(Modifier.fillMaxSize())
        }
        else NavCard(state, actions, navMap, Modifier.weight(1f).fillMaxHeight())
        BoxWithConstraints(Modifier.weight(GTS_SIDE_WEIGHT).fillMaxHeight()) {
        val boxW = maxWidth
        val boxH = maxHeight
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gutter), horizontalAlignment = Alignment.CenterHorizontally) {
            CarInfoCard(state, Modifier.weight(1f).fillMaxWidth())
            GtsMediaCard(state, actions, Modifier.playerSize(boxW, boxH - gutter))
        }
        }
    }
}

/**
 * The car card (speed, then the OBD2 adapter's readings or the drive by GPS), on every style's
 * home screen (2026-11-12). The GTS style draws it grey like the VW Play's own screens; the other
 * styles in their own card color.
 */
@Composable
internal fun CarInfoCard(state: CarUiState, modifier: Modifier) {
    if (!net.prototux.aare.ui.theme.LocalGtsStyle.current) {
        Card(modifier) { GtsCarCard(state, Modifier.fillMaxSize()) }
        return
    }
    // Grey, like the car's own VW Play screens, with its insides in matching greys.
    val c = MaterialTheme.colorScheme
    val night = net.prototux.aare.ui.theme.LocalCarNight.current
    // One flat color (the gradient looked heavy), the readings' boxes a step darker; at night
    // the boxes are pure black and the card just above it.
    Card(modifier, background = SolidColor(if (night) CAR_CARD_NIGHT else CAR_CARD)) {
        MaterialTheme(colorScheme = c.copy(
            surfaceContainerHigh = if (night) Color.Black else Color(0x33000000),
            surfaceContainerHighest = if (night) Color(0xFF26272B) else Color(0x40000000),
            onSurface = if (night) Color(0xFFE6E7EA) else Color.White,
            onSurfaceVariant = if (night) Color(0xFF9A9FA8) else Color(0xFFC9CCD1),
        )) { GtsCarCard(state, Modifier.fillMaxSize()) }
    }
}

/**
 * The player's card, always 16:9 (the user's rule, 2026-11-12), in every style: the column's
 * width when that leaves the cards above it room, else smaller, centered, at the same 16:9 (on a
 * shorter screen, such as the car's 149 dpi, the car card keeps its bottom row). Before, the GTS
 * player gave up height at full width and stopped being 16:9 there.
 */
internal fun Modifier.playerSize(width: Dp, height: Dp): Modifier {
    val h = minOf(width * 9f / 16f, height * MEDIA_SHARE_MAX)
    return this.width(h * 16f / 9f).height(h)
}

/** The most of the right column the player may take. */
private const val MEDIA_SHARE_MAX = 0.40f

/**
 * A phone app shown live in the big card (Google Maps by default, on [net.prototux.aare.apps.PhoneApps.card]),
 * provided by the car runtime; null keeps the car navigation app's card.
 */
val LocalMapCard = androidx.compose.runtime.compositionLocalOf<(@Composable (Modifier) -> Unit)?> { null }

/** The video minimized into the media card ([CarUiState.videoInCard]), provided by the car runtime. */
val LocalVideoCard = androidx.compose.runtime.compositionLocalOf<(@Composable (Modifier) -> Unit)?> { null }

/** The right column's width against the map card's 1: about 30 % of the screen, as in the user's sketch. */
private const val GTS_SIDE_WEIGHT = 0.42f

/** The car card's grey, like the VW Play's own screens. */
private val CAR_CARD = Color(0xFF3F4247)

/** At night: a grey so dark it's nearly black, just enough to tell from the black boxes and screen. */
private val CAR_CARD_NIGHT = Color(0xFF141518)

/** Top of the scale on the rev bar, in thousands, and where its red zone starts. */
private const val REV_MAX = 7
private const val REV_RED = 6.5f // the Polo GTS 1.4 TSI's redline

// ---------------------------------------------------------------- car card

/**
 * Text sizes on the right column follow the card's own size, not the interface size setting:
 * the column is a fixed share of the screen, so a fixed sp size would be tiny or overflow.
 * [u] is 1 on a card 400 units wide (and no wider than its height allows).
 */
private class Scale(val u: Float, private val fontScale: Float) {
    /** Divided by the phone's font size setting: the card is sized in its own units, so a bigger phone font must not overflow it. */
    fun sp(v: Float) = (v * u / fontScale).sp
    fun dp(v: Float) = (v * u).dp
}

@Composable
private fun scale(u: Float) = Scale(u, LocalDensity.current.fontScale)

@Composable
private fun GtsCarCard(state: CarUiState, modifier: Modifier) {
    val obdStatus by ObdReader.status.collectAsState()
    // No adapter being read (left at home, switched off, not wanted): the car's GPS fills the card.
    val byGps = obdStatus.state != ObdReader.State.CONNECTED
    val obd by ObdReader.readings.collectAsState()
    val gps by GpsTrip.readings.collectAsState()
    // The theme's line heights are for its own type sizes (32 sp lines for 24 sp text): on this
    // card's smaller text they added rows of empty space and pushed the bottom row out.
    // (copy, not ProvideTextStyle: merging an unspecified line height keeps the old one)
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides
        androidx.compose.material3.LocalTextStyle.current.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified)) {
    BoxWithConstraints(modifier) {
        val s = scale(minOf(maxWidth.value / 400f, maxHeight.value / 420f))
        Column(Modifier.fillMaxSize().padding(horizontal = s.dp(22f), vertical = s.dp(16f))) {
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text((if (byGps) gps?.speed else obd?.speed)?.let { "%.0f".format(it) } ?: "--", fontSize = s.sp(64f), lineHeight = s.sp(64f), fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                    Spacer(Modifier.width(s.dp(10f)))
                    Text("KM/H", fontSize = s.sp(18f), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = s.dp(8f)))
                    Spacer(Modifier.weight(1f))
                    ObdStatusLabel(obdStatus, s, Modifier.padding(bottom = s.dp(8f)))
                }
                // The rev counter only with the adapter: without one there are no revs to show,
                // and the weather takes its room instead.
                if (!byGps) {
                    Spacer(Modifier.height(s.dp(8f)))
                    RevBar(rpm = obd?.rpm?.toInt(), Modifier.fillMaxWidth().height(s.dp(12f)))
                    Row(Modifier.fillMaxWidth().padding(top = s.dp(3f)), horizontalArrangement = Arrangement.SpaceBetween) {
                        for (i in 0..REV_MAX) Text("$i", fontSize = s.sp(13f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(s.dp(10f)))
            Column {
                Text(today(), fontSize = s.sp(22f), color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(s.dp(if (byGps) 10f else 6f)))
                WeatherRow(state, s, big = byGps)
            }
            Spacer(Modifier.height(s.dp(12f)))
            // The readings take whatever height is left (it sat empty between them and the weather).
            if (byGps) GpsGrid(gps, s, Modifier.weight(1f).fillMaxWidth())
            else ReadingsGrid(obd, s, Modifier.weight(1f).fillMaxWidth())
        }
    }
    }
}

/** Where the adapter stands, small and only when it isn't simply reading: the adapter's name with a dot, or why not. */
@Composable
private fun ObdStatusLabel(status: ObdReader.Status, s: Scale, modifier: Modifier) {
    val (text, ok) = when (status.state) {
        ObdReader.State.OFF -> return
        ObdReader.State.CONNECTED -> status.adapter to true
        ObdReader.State.SEARCHING -> stringResource(R.string.obd_searching) to false
        ObdReader.State.NO_CAR -> stringResource(R.string.obd_no_car) to false
        ObdReader.State.GAVE_UP -> stringResource(R.string.obd_gave_up) to false
        ObdReader.State.USER_OFF -> stringResource(R.string.obd_user_off) to false
    }
    // The driver's button: disconnect while it reads or looks for it, connect (now) otherwise.
    val on = status.state == ObdReader.State.CONNECTED || status.state == ObdReader.State.SEARCHING || status.state == ObdReader.State.NO_CAR
    val offer = stringResource(if (on) R.string.obd_disconnect else R.string.obd_connect)
    Row(modifier.width(s.dp(200f)), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(s.dp(8f)).clip(CircleShape).background(if (ok) Color(0xFF4CD964) else MaterialTheme.colorScheme.onSurfaceVariant))
        Spacer(Modifier.width(s.dp(6f)))
        Text(text, fontSize = s.sp(12f), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(s.dp(8f)))
        val size = s.dp(34f)
        Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable { if (on) ObdReader.disconnect() else ObdReader.connectNow() }, contentAlignment = Alignment.Center) {
            Icon(if (on) Icons.Rounded.BluetoothDisabled else Icons.Rounded.BluetoothSearching, offer,
                tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(size * 0.6f))
        }
    }
}

/** 2:56, or 1:02:56 past an hour. */
private fun clock(ms: Long): String {
    val t = ms / 1000
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
}

/** The rev counter as a bar, red from [REV_RED] up; [rpm] null draws the empty scale. */
@Composable
private fun RevBar(rpm: Int?, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val fill = MaterialTheme.colorScheme.onSurface
    val red = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        val redStart = size.width * REV_RED / REV_MAX
        drawRect(red.copy(alpha = 0.35f), topLeft = Offset(redStart, 0f), size = Size(size.width - redStart, size.height))
        if (rpm != null) {
            val w = size.width * (rpm / 1000f / REV_MAX).coerceIn(0f, 1f)
            drawRoundRect(if (w > redStart) red else fill, size = Size(w, size.height), cornerRadius = r)
        }
    }
}

/** "Sábado, 7 de novembro" in the phone's language, kept current. */
@Composable
private fun today(): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000 + 50)
            now = System.currentTimeMillis()
        }
    }
    val locale = LocalContext.current.resources.configuration.locales[0] ?: Locale.getDefault()
    // The names in the same language as the pattern ("Wednesday, 11 November", not "11 de November"
    // with AARE set to another language than the phone, 2026-11-11).
    val text = java.text.SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale).format(java.util.Date(now))
    return text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}

/**
 * The temperature now and the next three forecasts. [big]: in the rev counter's room (no adapter
 * read), the icons and numbers about half again as large.
 */
@Composable
private fun WeatherRow(state: CarUiState, s: Scale, big: Boolean = false) {
    val w = state.weather
    val k = if (big) 1.45f else 1f
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (w != null) {
            Icon(weatherIcon(w), null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(s.dp(30f * k)))
            Spacer(Modifier.width(s.dp(6f * k)))
        }
        Text(if (w != null) "${w.temperature}°" else "--°", fontSize = s.sp(30f * k), fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.weight(1f))
        val next = w?.forecast.orEmpty()
        repeat(3) { i ->
            val f = next.getOrNull(i)
            Column(Modifier.padding(start = s.dp(14f)), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(f?.let { "${it.hour}h" } ?: "--h", fontSize = s.sp(14f * minOf(k, 1.2f)), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (f != null) Icon(weatherIcon(Weather(f.temperature, w!!.unit, f.code, f.day)), null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(s.dp(16f * if (big) 1.75f else 1f)))
                Text(f?.let { "${it.temperature}°" } ?: "--°", fontSize = s.sp(18f * minOf(k, 1.25f)), color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** Water, boost, battery, fuel, range and consumption, three per row, with the dongle launcher's icons. */
@Composable
private fun ReadingsGrid(r: ObdReadings?, s: Scale, modifier: Modifier) {
    fun f(v: Float?, pattern: String) = v?.let { pattern.format(it) } ?: "--"
    val readings = listOf(
        Triple(ObdIcons.Coolant, stringResource(R.string.gts_water), f(r?.coolant, "%.0f") to "°C"),
        Triple(ObdIcons.Boost, stringResource(R.string.gts_boost), f(r?.boost, "%+.2f") to "bar"),
        Triple(ObdIcons.Battery, stringResource(R.string.gts_battery), f(r?.battery, "%.1f") to "V"),
        Triple(ObdIcons.Fuel, stringResource(R.string.gts_fuel), f(r?.fuel, "%.0f") to "%"),
        Triple(ObdIcons.Range, stringResource(R.string.gts_range), f(r?.rangeKm, "%.0f") to "km"),
        Triple(ObdIcons.Economy, stringResource(R.string.gts_consumption), f(r?.economy, "%.1f") to "km/l"),
    )
    Grid(readings, s, modifier)
}

/**
 * The drive by the car's GPS, in the same six boxes: distance, time, average and top speed,
 * altitude and heading. Shown while no adapter is read.
 */
@Composable
private fun GpsGrid(g: GpsReadings?, s: Scale, modifier: Modifier) {
    fun f(v: Float?, pattern: String) = v?.let { pattern.format(it) } ?: "--"
    val minutes = (g?.elapsedMs ?: 0) / 60_000
    val points = androidx.compose.ui.res.stringArrayResource(R.array.gts_compass_points)
    val heading = g?.bearing?.let { points[((it + 22.5f) / 45f).toInt().mod(8)] }
    Grid(listOf(
        Triple(Icons.Rounded.Route, stringResource(R.string.gts_trip_distance), f(g?.distanceKm, if ((g?.distanceKm ?: 0f) < 100f) "%.1f" else "%.0f") to "km"),
        Triple(Icons.Rounded.Timer, stringResource(R.string.gts_trip_time), (if (g == null) "--" else "%d:%02d".format(minutes / 60, minutes % 60)) to "h"),
        Triple(Icons.Rounded.Speed, stringResource(R.string.gts_trip_average), f(g?.average, "%.0f") to "km/h"),
        Triple(Icons.Rounded.KeyboardDoubleArrowUp, stringResource(R.string.gts_trip_top), f(g?.top, "%.0f") to "km/h"),
        Triple(Icons.Rounded.Terrain, stringResource(R.string.gts_trip_altitude), f(g?.altitude, "%.0f") to "m"),
        Triple(Icons.Rounded.Explore, stringResource(R.string.gts_trip_heading), (heading ?: "--") to (g?.bearing?.let { "%.0f°".format(it) } ?: "")),
    ), s, modifier)
}

@Composable
private fun Grid(readings: List<Triple<ImageVector, String, Pair<String, String>>>, s: Scale, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(s.dp(8f))) {
        readings.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(s.dp(8f))) {
                row.forEach { (icon, label, value) -> Reading(icon, label, value.first, value.second, s, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

/** One reading: the icon on top, the number big under it with its unit, sized to the block. */
@Composable
private fun Reading(icon: ImageVector, label: String, value: String, unit: String, s: Scale, modifier: Modifier) {
    BoxWithConstraints(modifier.clip(RoundedCornerShape(s.dp(12f))).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        // Grows with the block (the space the card has), within what its width allows ("+0.25 bar").
        val k = minOf(maxHeight.value / 80f, maxWidth.value / 100f).coerceIn(0.7f, 1.8f)
        val fontScale = LocalDensity.current.fontScale
        fun sp(v: Float) = (v * k / fontScale).sp
        Column(Modifier.fillMaxSize().padding(horizontal = (9 * k).dp, vertical = (7 * k).dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size((26 * k).dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontSize = sp(27f), lineHeight = sp(27f), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                Spacer(Modifier.width((3 * k).dp))
                Text(unit, fontSize = sp(12f), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    modifier = Modifier.padding(bottom = (3 * k).dp))
            }
        }
    }
}

// ---------------------------------------------------------------- media card

/** The player: the album art behind, source, title and artist, progress and the buttons. */
@Composable
private fun GtsMediaCard(state: CarUiState, actions: CarActions, modifier: Modifier) {
    // A video minimized from full screen plays here; a touch brings it back full screen (its
    // buttons are in the bar's media widget meanwhile).
    val video = LocalVideoCard.current
    if (state.videoInCard != null && video != null) {
        Card(modifier) {
            video(Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().clickable { actions.maximizeVideo() })
        }
        return
    }
    val np = state.nowPlaying
    val media = state.app(np?.appId ?: state.activeMediaAppId)
    Card(modifier, onClick = { media?.let(actions::openApp) ?: actions.openLauncher() }) {
        if (np?.art != null) {
            // A new song's art fades in over the last one.
            CarCrossfade(np, key = ::trackKey) { n ->
                n.art?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.45f) }
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surfaceContainer))))
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val s = scale(minOf(maxWidth.value / 400f, maxHeight.value / 225f))
            val button = (maxHeight * 0.3f).coerceIn(36.dp, LocalCarDimens.current.touchTarget)
            Column(Modifier.fillMaxSize().padding(horizontal = s.dp(20f), vertical = s.dp(12f))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text((stringResource(R.string.gts_media_source) + " · " + (media?.label ?: stringResource(R.string.media_fallback_label))).uppercase(), fontSize = s.sp(12f),
                        letterSpacing = s.sp(0.8f), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.weight(1f))
                    // A video app playing as sound only: its picture back, here in the card.
                    if (media != null && state.apps.any { it.video && it.packageName == media.packageName }) {
                        val size = s.dp(34f)
                        val press = rememberCarPress(0.88f)
                        Box(press.modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable(press) { actions.showVideo() }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.OndemandVideo, stringResource(R.string.video_show), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(size * 0.6f))
                        }
                    }
                }
                Text(np?.title ?: stringResource(R.string.media_nothing_playing), fontSize = s.sp(22f), fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (np != null && np.subtitle.isNotEmpty()) {
                    Text(np.subtitle, fontSize = s.sp(16f), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.weight(1f))
                if (np != null && np.durationMs > 0) {
                    // Where the song is now: the position the app last reported, moved on by the clock while it
                    // plays. Sampled on elapsedRealtime (PlaybackState's clock): the wall clock pinned it at the end.
                    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
                    LaunchedEffect(np.playing, np.updatedAtMs) {
                        while (true) { now = android.os.SystemClock.elapsedRealtime(); if (!np.playing) break; delay(500) }
                    }
                    val position = (np.positionMs + if (np.playing) (now - np.updatedAtMs).coerceAtLeast(0) else 0).coerceIn(0, np.durationMs)
                    // Glides between the half-second ticks (full animations): only the bar redraws.
                    val p = smoothProgress(position.toFloat() / np.durationMs, 500)
                    val track = MaterialTheme.colorScheme.surfaceContainerHighest
                    val fill = MaterialTheme.colorScheme.primary
                    Canvas(Modifier.fillMaxWidth().height(s.dp(4f))) {
                        drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
                        drawRoundRect(fill, size = Size(size.width * p.value, size.height), cornerRadius = CornerRadius(size.height / 2))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = s.dp(2f)), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(clock(position), fontSize = s.sp(11f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(clock(np.durationMs), fontSize = s.sp(11f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    MediaButton(Icons.Rounded.SkipPrevious, stringResource(R.string.media_action_previous), false, button) { actions.mediaPrevious() }
                    MediaButton(if (np?.playing == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        stringResource(R.string.media_action_play_pause), true, button) { actions.mediaPlayPause() }
                    MediaButton(Icons.Rounded.SkipNext, stringResource(R.string.media_action_next), false, button) { actions.mediaNext() }
                }
            }
        }
    }
}

@Composable
private fun MediaButton(icon: ImageVector, label: String, filled: Boolean, size: Dp, onClick: () -> Unit) {
    val bg = if (filled) MaterialTheme.colorScheme.primary else Color.Transparent
    val fg = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val press = rememberCarPress(0.88f)
    Box(press.modifier.size(size).clip(CircleShape).background(bg).clickable(press, onClick = onClick), contentAlignment = Alignment.Center) {
        // Play to pause and back: the new icon zooms in.
        CarCrossfade(icon, key = { it }, zoom = true) { Icon(it, label, tint = fg, modifier = Modifier.size(size * 0.55f)) }
    }
}
