package net.prototux.aare.ui.car

import android.graphics.Bitmap
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.prototux.aare.car.NextEvent
import net.prototux.aare.car.Weather
import net.prototux.aare.notify.PhoneGuidance

/**
 * What kind of app an entry in the launcher is, which decides where its UI goes. [EXIT] has no UI:
 * it hands the screen back to the car's own.
 */
enum class CarAppKind { NAVIGATION, MEDIA, TEMPLATED, ASSISTANT, MESSAGING, PHONE_SCREEN, BROWSER, CAR_DATA, STANDALONE, PHONE, SETTINGS, NOTIFICATIONS, EXIT, PHONE_APP }

/** One launchable app on the car screen. */
@Stable
class CarAppEntry(
    val id: String,
    val packageName: String,
    val label: String,
    val icon: Bitmap?,
    val kind: CarAppKind,
    /** A video app opened mirrored (YouTube...): it may go into the home screen's media card. */
    val video: Boolean = false,
    /**
     * Listed in the app grid. A music app's mirrored side, kept only to show its video (Spotify's
     * video podcasts and clips) in the media card, isn't: its own music screen is there.
     */
    val inGrid: Boolean = true,
)

@Stable
class NowPlaying(
    val appId: String,
    val title: String,
    val subtitle: String,
    val art: Bitmap?,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long,
)

/** The car UI's navigation: the dashboard, the app grid, or one app full screen. */
sealed interface CarScreen {
    data object Dashboard : CarScreen
    data object Launcher : CarScreen
    data class App(val appId: String) : CarScreen
    /** The GTS home screen's map card (a phone app on its own display) over the whole content area. */
    data object MapFull : CarScreen
}

/** A transient card, e.g. an incoming message or a toast from a car app. */
@Stable
class CarNotice(
    val key: String,
    val title: String,
    val text: String,
    val icon: Bitmap?,
    val actions: List<Pair<String, () -> Unit>> = emptyList(),
    val timeMs: Long = System.currentTimeMillis(),
    /** A message from a contact: its text is hidden while driving, like in the messaging screens. */
    val message: Boolean = false,
)

/** Head-unit geometry: the drawable area inside the video margins, and the rail side. */
data class CarGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val dpi: Int,
    /** Rail on the left (left-hand drive) or right. */
    val railStart: Boolean = true,
)

/**
 * All state the car UI reads. The session and hosts write it from the main thread.
 */
@Stable
class CarUiState {
    var night by mutableStateOf(true)
    var screen by mutableStateOf<CarScreen>(CarScreen.Dashboard)
    val apps = mutableStateListOf<CarAppEntry>()
    var nowPlaying by mutableStateOf<NowPlaying?>(null)
    /** The navigation app whose map is shown on the dashboard. */
    var activeNavAppId by mutableStateOf<String?>(null)
    /** The media app the dashboard's media card controls. */
    var activeMediaAppId by mutableStateOf<String?>(null)
    val notices = mutableStateListOf<CarNotice>()
    var drivingRestricted by mutableStateOf(false)
    var headUnitName by mutableStateOf("")
    var listening by mutableStateOf(false)
    /** Recent notifications (messages), newest first, for the notification centre. */
    val history = mutableStateListOf<CarNotice>()
    /** Notifications not seen in the notification centre yet. */
    var unseen by mutableStateOf(0)
    /** The dashboard's wallpaper: a built-in name, or "custom" with [wallpaperImage]. */
    var wallpaper by mutableStateOf("none")
    var wallpaperImage by mutableStateOf<Bitmap?>(null)
    /** The hosted navigation app's trip (next step, distances), for the cluster's turn card. */
    var trip by mutableStateOf<androidx.car.app.navigation.model.Trip?>(null)
    /** The package that sent [trip]: its maneuver icons are opened on that app's behalf. */
    var tripPackage by mutableStateOf<String?>(null)
    /** The weather next to the clock, if known and wanted. */
    var weather by mutableStateOf<Weather?>(null)
    /** The next calendar event, for the dashboard. */
    var nextEvent by mutableStateOf<NextEvent?>(null)
    /** The next few calendar events, weeks ahead if need be (the clock card's agenda). */
    var events by mutableStateOf<List<NextEvent>>(emptyList())
    /** The country's next public holiday (the clock card). */
    var holiday by mutableStateOf<net.prototux.aare.car.Holiday?>(null)
    /** What the card over the player shows: [net.prototux.aare.Settings.CORNER_CAR] or [net.prototux.aare.Settings.CORNER_CLOCK]. */
    var cornerCard by mutableStateOf(net.prototux.aare.Settings.CORNER_CAR)
    /** The map and cards split on the home screen ([net.prototux.aare.Settings.cardScale]). */
    var cardScale by mutableStateOf(net.prototux.aare.Settings.CARDS_BALANCED)
    /** The phone call ringing or on, if any. */
    var call by mutableStateOf<CarCall?>(null)
    /** The apps opened last, newest first (ids): the rail shows the recent ones. */
    val recentAppIds = mutableStateListOf<String>()
    /** The voice assistant showing as a bar over the screen (an app entry id), or null. */
    var assistantAppId by mutableStateOf<String?>(null)
    /** Turn-by-turn from a navigation app running on the phone (Google Maps, Waze…). */
    var phoneGuidance by mutableStateOf<PhoneGuidance?>(null)
    /** Right-hand drive: the rail goes to the right, next to the driver. */
    var railOnRight by mutableStateOf(false)
    /** The driver sits on the right (right-hand drive): the voice button goes on that side. */
    var driverOnRight by mutableStateOf(false)
    /** The rail stays dark even with day colors (a setting). */
    var darkBar by mutableStateOf(false)
    /** The CarPlay-style layout (an easter egg, *Settings > Style*). */
    var carPlayStyle by mutableStateOf(false)
    /** The GTS look: always dark, red accent, Barlow and its own home screen layout (*Settings > Style*). */
    var gtsStyle by mutableStateOf(false)
    /** How much the car screen animates (Settings.carAnimations). */
    var motion by mutableStateOf(CarMotion.FULL)
    /** The rail runs along the bottom instead of a side. */
    var railBottom by mutableStateOf(false)
    /** The app on the car screen shows a video full screen (its bars hidden): AARE's bar hides too. */
    var videoFullscreen by mutableStateOf(false)
    /** When the driver last touched that video (uptime ms): the bar shows for a moment, with the video's controls. */
    var barPeekAt by mutableLongStateOf(0L)
    /** The bar's own apps chosen on the phone (keys as Settings.barApps), or null for the default set. */
    var barApps by mutableStateOf<List<String>?>(null)
    /** How many recent apps the bar adds. */
    var barRecents by mutableStateOf(2)
    /** The video app (entry id) minimized into the home screen's media card, or null. */
    var videoInCard by mutableStateOf<String?>(null)

    fun app(id: String?): CarAppEntry? = apps.firstOrNull { it.id == id }
}

/** A phone call, ringing or on, for the car's call card. */
@Stable
data class CarCall(val ringing: Boolean, val name: String, val sinceMs: Long)

/** Actions the car UI asks for; implemented by the session. */
interface CarActions {
    fun openApp(app: CarAppEntry)
    fun goHome()
    fun openLauncher()
    fun assistant()
    fun mediaPlayPause()
    fun mediaNext()
    fun mediaPrevious()
    fun dismissNotice(notice: CarNotice)
    fun answerCall()
    fun endCall()
    /** Navigate to a place (an address or a name) in the car's navigation app. */
    fun navigateTo(place: String)
    /** Back, as the car's own back key: inside the app on screen first. */
    fun back() {}
    /** The map card full screen (Android Auto's navigation app taking the screen from its dashboard card). */
    fun openMapFull() {}
    /** The video app on screen goes into the home screen's media card (Android Auto's minimize). */
    fun minimizeVideo() {}
    /** The video in the media card goes back full screen. */
    fun maximizeVideo() {}
    /** The video playing goes on as sound only (and is remembered so). */
    fun audioOnly() {}
    /** The video app playing as sound only shows its picture again, in the media card (remembered). */
    fun showVideo() {}
}
