package net.prototux.aare.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.location.Location
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.navigation.model.Trip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.prototux.aare.DiagLog
import net.prototux.aare.R
import net.prototux.aare.Settings
import net.prototux.aare.car.Calls
import net.prototux.aare.car.DashboardInfo
import net.prototux.aare.media.MediaHub
import net.prototux.aare.mirror.MirrorScreen
import net.prototux.aare.apps.PhoneAppScreen
import net.prototux.aare.apps.PhoneApps
import net.prototux.aare.notify.CarNotificationListener
import net.prototux.aare.notify.MessagingScreen
import net.prototux.aare.launch.AppLauncher
import net.prototux.aare.notify.PhoneGuidance
import net.prototux.aare.ui.car.BrowserScreen
import net.prototux.aare.ui.car.CarActions
import net.prototux.aare.ui.car.CarAppEntry
import net.prototux.aare.ui.car.CarAppKind
import net.prototux.aare.ui.car.CarCall
import net.prototux.aare.ui.car.CarNotice
import net.prototux.aare.ui.car.CarScreen
import net.prototux.aare.ui.car.CarSettingsScreen
import net.prototux.aare.ui.car.CarShell
import net.prototux.aare.ui.car.CarUiState
import net.prototux.aare.ui.car.NotificationsScreen
import net.prototux.aare.ui.car.NowPlaying
import net.prototux.aare.ui.car.CarDataScreen
import net.prototux.aare.ui.car.PhoneScreen
import net.prototux.aare.ui.car.StandaloneAppScreen
import net.prototux.aare.ui.theme.CarTheme
import net.prototux.aare.ui.theme.LocalCarDimens

/**
 * Everything on the car screen that doesn't depend on how it reaches the car: the app list,
 * hosted car apps, media, notices and the root composable. A projection session (real head unit)
 * and the on-phone preview both drive one of these.
 *
 * Main thread only, except the properties documented as thread-safe for [HostEnvironment].
 */
class CarRuntime(
    val context: Context,
    private val scope: CoroutineScope,
    val settings: Settings,
    val media: MediaHub,
    /** Release [media] in [stop]: true when this runtime created it (the preview), false when shared. */
    private val ownsMedia: Boolean = true,
    /** The car session's runtime (false for the on-phone preview): it takes [active] over a preview. */
    private val primary: Boolean = true,
    /** Offer the "Phone screen" entry at all (off for sessions from the developer TCP server unless opted in). */
    private val phoneScreenAllowed: Boolean = true,
) : HostEnvironment {

    val ui = CarUiState()
    val icons = IconLoader(context, this)
    private val main = Handler(Looper.getMainLooper())

    /** Hosted car app connections by entry id. */
    private val connections = mutableStateMapOf<String, CarAppConnection>()
    /** Apps that finished themselves: only an explicit open ([connection]) starts them again, not the composition. */
    private val finished = HashSet<String>()
    private val carApps = HashMap<String, CarAppInfo>()
    private val visibleCount = HashMap<CarAppConnection, Int>()

    /** Hooks the projection session sets: navigation status to the cluster, microphone, etc. */
    var onTrip: ((CarAppConnection, Trip?) -> Unit)? = null
    var microphone: ((sink: (ByteArray, Int, Int) -> Unit, onStopped: () -> Unit) -> MicHandle?)? = null
    var onAssistant: (() -> Unit)? = null
    /** The screen-capture consent (car audio), which also lets the phone screen be mirrored. */
    var projection: () -> MediaProjection? = { null }
    /** Guidance from a navigation app on the phone (Google Maps, Waze…) changed, or ended (null). */
    var onPhoneGuidance: ((PhoneGuidance?) -> Unit)? = null
    /** Hands the screen back to the car's own UI; null (no "Exit to car" entry) without a head unit. Set before [start]. */
    var exitToCar: (() -> Unit)? = null

    // ------------------------------------------------------------------ HostEnvironment

    override val driving: Boolean get() = ui.drivingRestricted
    override val night: Boolean get() = ui.night
    override var screenWidthDp by mutableIntStateOf(1280)
    override var screenHeightDp by mutableIntStateOf(720)
    override var densityDpi by mutableIntStateOf(160)
    @Volatile override var carLocation: Location? = null
    @Volatile override var carHeading: Float? = null

    override fun contentLimit(type: Int): Int = when (type) {
        // Android Auto's usual limits: generous when parked, shorter lists while driving.
        ConstraintManager.CONTENT_LIMIT_TYPE_LIST -> if (ui.drivingRestricted) 6 else 100
        ConstraintManager.CONTENT_LIMIT_TYPE_GRID -> if (ui.drivingRestricted) 6 else 100
        ConstraintManager.CONTENT_LIMIT_TYPE_PLACE_LIST -> 6
        ConstraintManager.CONTENT_LIMIT_TYPE_ROUTE_LIST -> 3
        ConstraintManager.CONTENT_LIMIT_TYPE_PANE -> 4
        else -> 6
    }

    override fun toast(app: CarAppConnection, text: CharSequence, long: Boolean) {
        notice(app.info.label, text.toString(), app.info.icon, if (long) 6_000 else 3_500)
    }

    override fun startCarApp(from: CarAppConnection, intent: Intent) {
        val action = intent.action
        // CarContext.startCarApp's documented phone call. Like Android Auto, the host places it for any car app
        // (they use ACTION_DIAL precisely because they don't hold CALL_PHONE). Vanity letters (1-800-FLOWERS) become
        // digits, then only ASCII digits and '+' are kept, so spacing doesn't count toward the length cap. A '*' or
        // '#' makes it a USSD/MMI code ("**21*...#" call forwarding), not a number: refused, as stripping them would
        // dial a different number.
        if ((action == Intent.ACTION_DIAL || action == Intent.ACTION_CALL) && intent.data?.scheme == "tel") {
            val raw = PhoneNumberUtils.convertKeypadLettersToDigits(
                intent.data?.schemeSpecificPart.orEmpty().substringBefore(',').substringBefore(';'),
            )
            // An unescaped '#' starts the URI's fragment, so it isn't in the scheme-specific part: check that too.
            val code = raw.any { it == '*' || it == '#' } || intent.data?.encodedFragment != null
            val number = if (code) "" else raw.filter { it in '0'..'9' || it == '+' }.take(20)
            val id = idOf(from)
            // Car apps stay connected in the background, and AARE hosts any car app (not only Play-approved
            // ones): only the app on screen, or in the assistant bar, calls straight away; any other is asked.
            val onScreen = id != null && ((ui.screen as? CarScreen.App)?.appId == id || ui.assistantAppId == id)
            when {
                code -> {
                    notice(context.getString(R.string.notif_not_calling_title), context.getString(R.string.notif_not_calling_text, from.info.label))
                    if (onScreen) ui.screen = CarScreen.App(PHONE_ID)
                }
                number.isBlank() -> if (onScreen) ui.screen = CarScreen.App(PHONE_ID)
                onScreen -> {
                    Calls.place(context, number)
                    notice(context.getString(R.string.notif_calling_title, number), context.getString(R.string.notif_asked_by, from.info.label))
                    ui.screen = CarScreen.App(PHONE_ID)
                }
                else -> notice(context.getString(R.string.notif_call_confirm_title, number), context.getString(R.string.notif_asked_by, from.info.label), durationMs = 10_000, actions = listOf(context.getString(R.string.common_call) to {
                    Calls.place(context, number)
                    ui.screen = CarScreen.App(PHONE_ID)
                }))
            }
            return
        }
        val isNavigate = action == CarContext.ACTION_NAVIGATE || intent.data?.scheme == "geo" || intent.data?.scheme == "google.navigation"
        // As in CarContext.startCarApp's contract, an explicit component may only start the calling app itself:
        // otherwise any app could hand another a crafted intent and take over the screen. A navigation intent
        // naming another app goes to the driver's navigation app instead.
        val own = intent.component?.takeIf { it.packageName == from.info.packageName }
        val target = when {
            own != null -> connections.values.firstOrNull { it.info.service == own }
                ?: carApps.entries.firstOrNull { it.value.service == own && it.value.packageName !in settings.hiddenApps }?.let { connection(it.key) }
            isNavigate -> navConnection()
            else -> null
        }
        if (target == null) {
            notice(from.info.label, if (isNavigate) context.getString(R.string.notif_no_nav_app) else context.getString(R.string.notif_cant_open))
            return
        }
        val navIntent = if (isNavigate && intent.action != CarContext.ACTION_NAVIGATE) Intent(CarContext.ACTION_NAVIGATE, intent.data) else intent
        target.connect(navIntent)
        show(target)
    }

    override fun finish(app: CarAppConnection) {
        val id = idOf(app) ?: return
        if ((ui.screen as? CarScreen.App)?.appId == id) ui.screen = CarScreen.Dashboard
        if (ui.assistantAppId == id) ui.assistantAppId = null
        // The dashboard's map card would start it again at once. Don't start another one in its
        // place either: two apps that both finish on start would take turns forever.
        if (ui.activeNavAppId == id) ui.activeNavAppId = null
        if (navigatingApp === app) navigationEnded(app)
        visibleCount.remove(app)
        app.release()
        // Before the removal: the screen fading out recomposes on it and would otherwise bind the app again.
        finished += id
        connections.remove(id)
    }

    /** The app whose trip the dashboard and the cluster show. */
    private var navigatingApp: CarAppConnection? = null

    override fun navigationStarted(app: CarAppConnection) {
        if (app.cluster) return
        // One navigation at a time, as in Android Auto: the other app is told to stop.
        connections.values.filter { it !== app && it.navigating }.forEach { it.stopNavigation() }
        navigatingApp = app
        idOf(app)?.let { ui.activeNavAppId = it; settings.lastNavApp = app.info.packageName }
    }

    override fun navigationEnded(app: CarAppConnection) {
        // An app we stopped may end after the new one started: keep the new one's trip.
        if (app.cluster || navigatingApp !== app) return
        navigatingApp = null
        ui.trip = null
        ui.tripPackage = null
        onTrip?.invoke(app, null)
    }

    override fun tripUpdated(app: CarAppConnection, trip: Trip) {
        if (app.cluster) return // the main session reports the same trip
        if (navigatingApp != null && navigatingApp !== app) return
        ui.trip = trip
        ui.tripPackage = app.info.packageName
        onTrip?.invoke(app, trip)
    }

    /** The instrument cluster's session of [nav], if the app has one (FEATURE_CLUSTER). */
    private val clusterConnections = HashMap<String, CarAppConnection>()

    fun clusterConnection(nav: CarAppEntry): CarAppConnection? {
        clusterConnections[nav.id]?.let { return it }
        val info = carApps[nav.id]?.takeIf { CarAppInfo.CATEGORY_CLUSTER in it.categories } ?: return null
        return CarAppConnection(context, info, this, cluster = true).also { clusterConnections[nav.id] = it }
    }

    override fun openMicrophone(app: CarAppConnection, sink: (ByteArray, Int, Int) -> Unit, onStopped: () -> Unit): MicHandle? {
        // Binder thread. Car apps stay bound in the background: only one the driver sees (on screen, on the
        // dashboard's map card, in the assistant bar) or the assistant just asked to listen may open the mic.
        if (!app.shown && ui.assistantAppId?.let { connections[it] } !== app) {
            Log.w(TAG, "${app.info.packageName}: microphone refused, not on screen")
            return null
        }
        // The rail's mic and the assistant bar show when an app listens.
        val handle = microphone?.invoke(sink) { main.post { ui.listening = false }; onStopped() } ?: return null
        main.post { ui.listening = true }
        return MicHandle { handle.close(); main.post { ui.listening = false } }
    }

    // ------------------------------------------------------------------ car screen settings

    /** The car's own day/night signal, if it sent one. */
    private var carNight: Boolean? = null
    /** The head unit's driver position. */
    var rightHandDrive = false
        set(v) { field = v; applyScreenSettings() }

    /** The car's driving signal (parked or not), before the passenger-mode setting. */
    var carDriving = false
        private set

    /** The car says it's moving (true) or parked. */
    fun carDriving(driving: Boolean) {
        carDriving = driving
        applyScreenSettings()
    }

    /** The car's day/night signal changed. */
    fun carNightMode(night: Boolean) {
        carNight = night
        applyScreenSettings()
    }

    /** Applies the theme and bar position settings (also when they change during a drive). */
    private fun applyScreenSettings() {
        val gts = settings.carStyle == Settings.STYLE_GTS
        // The GTS style follows the same setting (it was always dark until 2026-11-08).
        val night = when (settings.carTheme) {
            Settings.THEME_DARK -> true
            Settings.THEME_LIGHT -> false
            // The car's own signal; without one, whether the sun is up where the car is (from
            // the weather), else dark.
            else -> carNight ?: ui.weather?.let { !it.day } ?: true
        }
        if (ui.night != night) {
            ui.night = night
            configurationChanged()
        }
        val position = settings.railPosition
        // Passenger mode lifts the driving restrictions (keyboard, list lengths, phone screen).
        ui.drivingRestricted = carDriving && !settings.ignoreDriving
        ui.carPlayStyle = settings.carStyle == Settings.STYLE_CARPLAY
        ui.gtsStyle = gts
        ui.darkBar = settings.darkBar
        ui.motion = net.prototux.aare.ui.car.CarMotion.of(settings.carAnimations)
        ui.cornerCard = settings.cornerCard
        ui.barApps = settings.barApps
        ui.barRecents = settings.barRecents
        val wallpaper = settings.wallpaper
        if (wallpaper != ui.wallpaper || (wallpaper == Settings.WALLPAPER_CUSTOM && ui.wallpaperImage == null)) {
            ui.wallpaper = wallpaper
            ui.wallpaperImage = null
            if (wallpaper == Settings.WALLPAPER_CUSTOM) scope.launch {
                ui.wallpaperImage = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { android.graphics.BitmapFactory.decodeFile(java.io.File(context.filesDir, Settings.WALLPAPER_FILE).path) }.getOrNull()
                }
            }
        }
        ui.railBottom = position == Settings.RAIL_BOTTOM
        ui.driverOnRight = rightHandDrive
        ui.railOnRight = when (position) {
            Settings.RAIL_LEFT -> false
            Settings.RAIL_RIGHT -> true
            else -> rightHandDrive
        }
    }

    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "car_theme", "rail_position", "car_style", "dark_bar", "ignore_driving", "bar_apps", "bar_recents", "car_animations", "corner_card" -> main.post { applyScreenSettings() }
            "wallpaper" -> main.post { ui.wallpaper = ""; applyScreenSettings() }
            // Apps shown or hidden from the phone: update the car's app list now.
            "hidden_apps", "phone_screen", "assistant_pkg", "app_order", "phone_apps", "phone_apps_shown", "map_card_app" -> main.post { refreshApps() }
        }
    }

    // ------------------------------------------------------------------ apps

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshApps()
    }

    fun start() {
        running += this
        // Messages, directions and now playing all need the listener bound: make sure it is.
        CarNotificationListener.rebind(context)
        applyScreenSettings()
        settings.registerListener(settingsListener)
        refreshApps()
        context.registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        scope.launch { media.apps.collectLatest { refreshApps() } }
        scope.launch { NativeCompat.changes.collectLatest { refreshApps() } }
        if (primary) scope.launch { probeNativeApps() }
        if (primary) main.postDelayed(cleanUp, CLEANUP_DELAY_MS)
        scope.launch { watchCalls() }
        scope.launch { watchDashboardInfo() }
        scope.launch {
            CarNotificationListener.guidance.collect { g ->
                // A navigation app hosted on the car screen shows its own guidance.
                val shown = g?.takeUnless { p -> ui.apps.any { it.kind == CarAppKind.NAVIGATION && it.packageName == p.packageName } }
                ui.phoneGuidance = shown
                onPhoneGuidance?.invoke(shown)
            }
        }
        scope.launch {
            media.playback.collectLatest { p ->
                ui.nowPlaying = p?.let(::nowPlayingOf)
                if (p != null && ui.activeMediaAppId == null) ui.activeMediaAppId = mediaEntryId(p.packageName)
            }
        }
    }

    fun stop() {
        running.remove(this)
        runCatching { context.unregisterReceiver(packageReceiver) }
        settings.unregisterListener(settingsListener)
        val usedNatively = (connections.values + clusterConnections.values)
            .filter { it.status == CarAppConnection.Status.READY }.mapTo(HashSet()) { it.info.packageName }
        connections.values.forEach { it.release() }
        connections.clear()
        finished.clear()
        clusterConnections.values.forEach { it.release() }
        clusterConnections.clear()
        if (running.isEmpty()) PhoneApps.release()
        if (ownsMedia) media.release()
        if (primary) closeLeftovers(usedNatively)
        if (primary) {
            main.removeCallbacks(cleanUp)
            PhoneCleanup.atDisconnect(context, settings)
        }
    }

    /** Lightens the phone once the session has settled (see [PhoneCleanup]); the apps in use stay. */
    private val cleanUp = Runnable {
        val inUse = setOfNotNull(settings.mapCardApp.ifBlank { null }, media.playback.value?.packageName)
        PhoneCleanup.atConnect(context, settings, inUse)
    }

    /**
     * The Car App Library leaves a virtual display ("CarAppService") inside every app it ran for
     * us, until that app's process ends: hidden screens like that were a suspect when the user's
     * bank refused to open (2026-11-08). Once the car is gone, end those apps' processes if they
     * are in the background, as Android would to free memory (not a force stop: they keep their
     * notifications and alarms). An app the driver has open on the phone is left alone.
     */
    private fun closeLeftovers(packages: Set<String>) {
        if (packages.isEmpty()) return
        // Through Shizuku's am kill: since Android 14 an app may only end its own processes. Twice:
        // just after the unbind Android still counts the app as serving us and won't end it
        // (CoMaps, 2026-11-08); a few seconds later it is a background app like any other.
        for ((i, delay) in LEFTOVER_DELAYS_MS.withIndex()) main.postDelayed({
            val app = context.applicationContext
            Thread({
                val done = net.prototux.aare.audio.ShellOnce.call(app) { shell -> packages.map { shell.killBackgroundApp(it) } }
                if (i == LEFTOVER_DELAYS_MS.lastIndex) DiagLog.add("closed the car apps' leftovers: ${packages.joinToString()}${if (done == null) " (no Shizuku)" else ""}")
            }, "aare-leftovers").start()
        }, delay)
    }

    /** Rescans car apps (package manager work and icons on a background thread). */
    fun refreshApps() {
        scope.launch {
            val (found, messaging) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                CarAppCatalog.query(context) to AndroidAutoApps.messagingApps(context)
            }
            // The phone apps the user picked for the car (Apps page on the phone), and the apps
            // whose car side refused AARE: those open mirrored instead (NativeCompat).
            val refused = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                (found.map { it.packageName } + media.apps.value.map { it.packageName })
                    .filterTo(HashSet()) { NativeCompat.refused(context, it) }
            }
            // Google's car assistant only binds to Android Auto: Gemini, mirrored, takes its place.
            val gemini = if (assistantPackage()?.let { NativeCompat.refused(context, it) } == true &&
                context.packageManager.getLaunchIntentForPackage(PhoneApps.GEMINI) != null) setOf(PhoneApps.GEMINI) else emptySet()
            // Video apps (YouTube...) too: their Android Auto side is sound only, the picture is the point.
            val video = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                media.apps.value.map { it.packageName }.filterTo(HashSet()) { pkg ->
                    val category = runCatching { context.packageManager.getApplicationInfo(pkg, 0).category }.getOrDefault(-1)
                    PhoneApps.kind(pkg, category) == PhoneApps.Kind.VIDEO || pkg in PhoneApps.VIDEO_MUSIC
                }
            }
            // The phone's dialer isn't mirrored for refusing: AARE's own Phone screen does its job
            // (two "Phone" icons otherwise). Still there if the user picked it on the Apps page.
            val chosen = settings.phoneAppsShown + (refused - setOfNotNull(dialerPackage())) + gemini + video
            val all = if (settings.phoneApps && chosen.isNotEmpty() && PhoneApps.available())
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { PhoneApps.query(context, PHONE_APP_ICON_PX, only = chosen) }.getOrDefault(emptyList())
                }
            else emptyList()
            applyApps(found, messaging, all, refused)
        }
    }

    /**
     * Tries, once per app version, whether each app with an Android Auto side opens natively here:
     * a Car App Library app is bound and created, a media app's browser connected, then both let
     * go. Refusals make the app open mirrored (see [NativeCompat]); nothing is shown meanwhile.
     */
    private suspend fun probeNativeApps() {
        kotlinx.coroutines.delay(PROBE_DELAY_MS) // after the car session has settled
        val carApps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CarAppCatalog.query(context) }
        val assistantPkg = assistantPackage()
        for (info in carApps) {
            if (info.packageName == assistantPkg || NativeCompat.known(context, info.packageName)) continue
            if (connections.values.any { it.info.packageName == info.packageName }) continue
            val probe = CarAppConnection(context, info, this, probe = true)
            probe.connect()
            kotlinx.coroutines.withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                androidx.compose.runtime.snapshotFlow { probe.status }.first { it == CarAppConnection.Status.READY || it == CarAppConnection.Status.FAILED }
            }
            DiagLog.add("probe ${info.label}: ${probe.status}${probe.error?.let { " ($it)" } ?: ""}")
            probe.release()
        }
        val art = net.prototux.aare.media.ArtLoader(context)
        for (app in media.apps.value) {
            if (NativeCompat.known(context, app.packageName)) continue
            val probe = net.prototux.aare.media.MediaConnection(context, scope, app, art)
            probe.connect()
            val status = kotlinx.coroutines.withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                probe.status.first { it !is net.prototux.aare.media.MediaConnection.Status.Connecting }
            }
            DiagLog.add("probe ${app.label} (media): ${status?.javaClass?.simpleName ?: "no answer"}")
            probe.release()
        }
    }

    private fun dialerPackage(): String? =
        runCatching { context.getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()

    /** Phone apps shown on the virtual display, by entry id. */
    private val phoneApps = HashMap<String, PhoneApps.App>()

    private fun applyApps(found: List<CarAppInfo>, messaging: List<PhoneApp>, all: List<PhoneApps.App> = emptyList(), refused: Set<String> = emptySet()) {
        val hidden = settings.hiddenApps
        val assistantPkg = assistantPackage()
        // A native side that refused AARE gives way to the mirrored app, when there is one to show
        // (the assistant keeps its role; a messaging app falls back to AARE's own messages screen).
        val mirrorable = all.mapTo(HashSet()) { it.component.packageName }
        val dialer = dialerPackage()
        fun mirrored(pkg: String) = pkg in refused &&
            // the refused assistant's broken entries go: the mic button opens Gemini instead
            (pkg == assistantPkg && PhoneApps.GEMINI in mirrorable || pkg == dialer || pkg in mirrorable || messaging.any { it.packageName == pkg })
        carApps.clear()
        val entries = ArrayList<CarAppEntry>()
        val mediaPackages = media.apps.value.mapTo(HashSet()) { it.packageName }
        for (app in found) {
            val id = carId(app)
            carApps[id] = app
            if (app.packageName in hidden || mirrored(app.packageName)) continue
            // Music apps' car UIs tend to accept only Google's host; their media service works here.
            if (!app.isNavigation && app.packageName != assistantPkg && app.packageName in mediaPackages) continue
            val kind = when {
                app.isNavigation -> CarAppKind.NAVIGATION
                app.packageName == assistantPkg -> CarAppKind.ASSISTANT
                else -> CarAppKind.TEMPLATED
            }
            entries += CarAppEntry(id, app.packageName, app.label, app.icon, kind)
        }
        for (m in media.apps.value) {
            // A navigation app or the assistant with a car UI of its own is shown once, as that.
            if (m.packageName in hidden || mirrored(m.packageName) || entries.any { it.packageName == m.packageName }) continue
            entries += CarAppEntry(mediaId(m.packageName), m.packageName, m.label, m.icon, CarAppKind.MEDIA)
        }
        // Built in, like Android Auto's: calls and the car-side settings.
        entries += CarAppEntry(PHONE_ID, context.packageName, context.getString(R.string.setup_app_entry_phone), builtinIcon(R.drawable.ic_car_phone), CarAppKind.PHONE)
        entries += CarAppEntry(SETTINGS_ID, context.packageName, context.getString(R.string.setup_app_entry_settings), builtinIcon(R.drawable.ic_car_settings), CarAppKind.SETTINGS)
        entries += CarAppEntry(NOTIFICATIONS_ID, context.packageName, context.getString(R.string.setup_app_entry_notifications), builtinIcon(R.drawable.ic_car_notifications), CarAppKind.NOTIFICATIONS)
        if (settings.phoneScreen && phoneScreenAllowed) {
            entries += CarAppEntry(PHONE_SCREEN_ID, context.packageName, context.getString(R.string.phone_section_title), builtinIcon(R.drawable.ic_phone_screen), CarAppKind.PHONE_SCREEN)
        }
        // Ordinary phone apps the user allowed onto the car screen (parked only; needs root/Shizuku).
        if (settings.standaloneApps && AppLauncher.available(context)) {
            for (pkg in settings.standaloneAppPackages) {
                if (pkg in hidden) continue
                val info = runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.getOrNull() ?: continue
                val label = context.packageManager.getApplicationLabel(info).toString()
                val icon = runCatching { context.packageManager.getApplicationIcon(info).toBitmap(128, 128) }.getOrNull()
                entries += CarAppEntry(standaloneId(pkg), pkg, label, icon, CarAppKind.STANDALONE)
            }
        }
        if (settings.browser) {
            entries += CarAppEntry(BROWSER_ID, context.packageName, context.getString(R.string.browser_app_label), builtinIcon(R.drawable.ic_car_browser), CarAppKind.BROWSER)
        }
        if (settings.carDataApp) {
            entries += CarAppEntry(CAR_DATA_ID, context.packageName, context.getString(R.string.car_data_app_label), builtinIcon(R.drawable.ic_car_data), CarAppKind.CAR_DATA)
        }
        for (m in messaging) {
            if (m.packageName in hidden || entries.any { it.packageName == m.packageName }) continue
            entries += CarAppEntry(messagingId(m.packageName), m.packageName, m.label, m.icon, CarAppKind.MESSAGING)
        }
        // Every other app on the phone, on the virtual display (apps with a car UI keep that, lighter
        // and made for driving). Spotify, Waze, Google Maps… refuse AARE as a host but run here as they are.
        phoneApps.clear()
        val cardPkg = mapCardComponent()?.packageName
        for (a in all) {
            val pkg = a.component.packageName
            if (pkg in hidden) continue
            // The big card's app lives on its own display: opening it full screen too would fight over it.
            if (pkg == cardPkg) continue
            val existing = entries.firstOrNull { it.packageName == pkg }
            if (existing != null) {
                // A video app is worth its own screen (the video) more than the session player: swap.
                if (existing.kind == CarAppKind.MEDIA && PhoneApps.kind(pkg, a.category) == PhoneApps.Kind.VIDEO) entries.remove(existing)
                else if (existing.kind == CarAppKind.MEDIA && pkg in PhoneApps.VIDEO_MUSIC && pkg !in settings.phoneAppsShown) {
                    // A music app with videos keeps its music screen; its mirrored side only shows the video.
                    val id = phoneAppId(pkg)
                    phoneApps[id] = a
                    entries += CarAppEntry(id, pkg, a.label, a.icon, CarAppKind.PHONE_APP, video = true, inGrid = false)
                    continue
                } else continue
            }
            val id = phoneAppId(pkg)
            phoneApps[id] = a
            // Spotify mirrored (it refuses AARE's music screen) has videos too: the same buttons as YouTube.
            entries += CarAppEntry(id, pkg, a.label, a.icon, CarAppKind.PHONE_APP,
                video = PhoneApps.kind(pkg, a.category) == PhoneApps.Kind.VIDEO || pkg in PhoneApps.VIDEO_MUSIC)
        }
        // The order chosen on the phone first, then alphabetical.
        val order = settings.appOrder.withIndex().associate { (i, k) -> k to i }
        entries.sortWith(compareBy<CarAppEntry>({ order[orderKey(it)] ?: Int.MAX_VALUE }, { it.label.lowercase() }))
        // Not an app, so outside the user's order: always last, like Android Auto's "Exit".
        if (exitToCar != null) {
            entries += CarAppEntry(EXIT_ID, context.packageName, context.getString(R.string.dash_menu_exit_to_car),
                builtinIcon(R.drawable.ic_car_exit), CarAppKind.EXIT)
        }
        ui.apps.clear()
        ui.apps.addAll(entries)
        // The app on screen changed side (it refused AARE just now): show it the new way.
        (ui.screen as? CarScreen.App)?.let { on ->
            if (entries.none { it.id == on.appId }) {
                val pkg = on.appId.substringAfter(':').substringBefore('/')
                val same = entries.firstOrNull { it.packageName == pkg && it.kind == CarAppKind.PHONE_APP }
                    ?: entries.firstOrNull { it.packageName == pkg }
                ui.screen = if (same != null) CarScreen.App(same.id) else CarScreen.Dashboard
            }
        }
        // What's playing names its app by entry: the entries just changed (the mirrored apps came
        // after it started playing: YouTube was taken for its media entry, not the video app).
        media.playback.value?.let { ui.nowPlaying = nowPlayingOf(it) }
        videoCardPending?.let { pkg ->
            entries.firstOrNull { it.video && it.packageName == pkg }?.let { e ->
                videoCardPending = null
                if (ui.screen == CarScreen.Dashboard && ui.videoInCard == null) showVideoInCard(e)
            }
        }
        ui.activeMediaAppId?.let { id -> if (entries.none { it.id == id }) ui.activeMediaAppId = null }
        ui.activeNavAppId?.let { id -> if (entries.none { it.id == id }) ui.activeNavAppId = null }
        // Restore the last navigation and media apps, so the dashboard isn't empty. Not a navigation app that
        // finished itself: its map card would stay blank instead of offering the picker.
        if (ui.activeNavAppId == null) {
            val nav = entries.filter { it.kind == CarAppKind.NAVIGATION && it.id !in finished }
            ui.activeNavAppId = (nav.firstOrNull { it.packageName == settings.lastNavApp } ?: nav.singleOrNull())?.id
        }
        if (ui.activeMediaAppId == null) {
            ui.activeMediaAppId = settings.lastMediaApp?.let { last ->
                (entries.firstOrNull { it.kind == CarAppKind.MEDIA && it.packageName == last }
                    ?: entries.firstOrNull { it.kind == CarAppKind.PHONE_APP && it.packageName == last })?.id
            }
        }
    }

    private fun nowPlayingOf(p: net.prototux.aare.media.PlaybackInfo) =
        NowPlaying(mediaEntryId(p.packageName), p.title, listOf(p.artist, p.album).filter { s -> s.isNotBlank() }.joinToString(" · "),
            p.art, p.playing, p.positionMs, p.durationMs, p.sampledAtMs)

    /** The car entry for a media app's package: its native media screen, or the mirrored app when it refused AARE. */
    private fun mediaEntryId(pkg: String): String =
        (ui.apps.firstOrNull { it.kind == CarAppKind.MEDIA && it.packageName == pkg }
            ?: ui.apps.firstOrNull { it.kind == CarAppKind.PHONE_APP && it.packageName == pkg })?.id ?: mediaId(pkg)

    /** The weather (every 30 minutes) and the next calendar event (every 5), for the rail and the dashboard. */
    private suspend fun watchDashboardInfo() {
        var lastWeather = 0L
        var lastHoliday = 0L
        while (true) {
            val now = System.currentTimeMillis()
            if (!settings.showWeather) ui.weather = null
            else if (now - lastWeather > 30 * 60_000) {
                val where = carLocation ?: DashboardInfo.phoneLocation(context)
                if (where != null) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { DashboardInfo.weather(where) }?.let { ui.weather = it; lastWeather = now; applyScreenSettings() }
                }
            }
            val events = if (settings.showCalendar)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { DashboardInfo.upcomingEvents(context, AGENDA_EVENTS, AGENDA_HOURS) } else emptyList()
            ui.events = events
            // The other screens' event card: the coming 12 hours only, as before.
            ui.nextEvent = if (settings.showCalendar)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { DashboardInfo.nextEvent(context) } else null
            // The next holiday, for the clock card: asked twice a day (it changes once in weeks).
            if (settings.showCalendar && now - lastHoliday > 12 * 3_600_000L) {
                val country = context.resources.configuration.locales[0]?.country.orEmpty().ifBlank { java.util.Locale.getDefault().country }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { DashboardInfo.nextHoliday(country) }?.let { ui.holiday = it; lastHoliday = now }
            }
            kotlinx.coroutines.delay(5 * 60_000)
        }
    }

    /** Navigates to [place] in the car's navigation app (geo: query), or false without one. */
    fun navigateTo(place: String): Boolean {
        val nav = ui.app(ui.activeNavAppId) ?: ui.apps.firstOrNull { it.kind == CarAppKind.NAVIGATION } ?: return false
        val conn = connection(nav.id) ?: return false
        conn.connect(Intent(CarContext.ACTION_NAVIGATE).setData(android.net.Uri.parse("geo:0,0?q=" + android.net.Uri.encode(place))))
        ui.activeNavAppId = nav.id
        ui.screen = CarScreen.App(nav.id)
        return true
    }

    /** Phone calls for the car's call card: polled, as no telephony listener needs fewer permissions. */
    private suspend fun watchCalls() {
        var since = 0L
        var last = TelephonyManager.CALL_STATE_IDLE
        while (true) {
            val state = Calls.state(context)
            if (state != last) { since = System.currentTimeMillis(); last = state }
            val name = CarNotificationListener.caller.value
            val call = when (state) {
                TelephonyManager.CALL_STATE_RINGING -> CarCall(ringing = true, name = name ?: context.getString(R.string.phone_incoming_call), sinceMs = since)
                TelephonyManager.CALL_STATE_OFFHOOK -> CarCall(ringing = false, name = name ?: context.getString(R.string.phone_call_fallback_name), sinceMs = since)
                else -> null
            }
            if (call != ui.call) ui.call = call
            kotlinx.coroutines.delay(if (state == TelephonyManager.CALL_STATE_IDLE) 1_500 else 500)
        }
    }

    /**
     * Starts what the phone played last, whatever app it was, as Android Auto does (the "resume
     * music" setting): through the app's own session while it is alive, else by connecting to it
     * as a media app. A video app that was closed has neither: it stays as it is.
     */
    fun resumeMedia() {
        scope.launch {
            // The app list and the media apps come in asynchronously: give them a moment.
            kotlinx.coroutines.delay(1_500)
            if (media.playback.value?.playing == true) return@launch
            val last = settings.lastPhoneMedia ?: settings.lastMediaApp ?: return@launch
            if (media.play(last)) {
                DiagLog.add("resume: $last plays again (its session)")
                ui.activeMediaAppId = mediaEntryId(last)
                settings.lastMediaApp = last
                // A video app that played last with its picture: in the media card again.
                if (ui.videoInCard == null && wantsPicture(last)) {
                    val entry = ui.apps.firstOrNull { it.video && it.packageName == last }
                    if (entry != null) showVideoInCard(entry) else videoCardPending = last
                }
                return@launch
            }
            // A video app (YouTube) that was closed has nothing queued: connecting to it as a media
            // app left it in an error and the card's play did nothing (2026-11-11 test).
            val isVideo = PhoneApps.kind(last, runCatching { context.packageManager.getApplicationInfo(last, 0).category }.getOrDefault(-1)) == PhoneApps.Kind.VIDEO
            if (!isVideo) {
                // As Android Auto does: a play key to the phone's last media app, which Android
                // wakes up even when it was closed (media button resumption). Spotify refuses
                // AARE's media connection, so the key is its only way back (2026-11-11 test).
                val audio = context.getSystemService(android.media.AudioManager::class.java)
                for (action in intArrayOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
                    runCatching { audio.dispatchMediaKeyEvent(android.view.KeyEvent(action, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)) }
                }
                kotlinx.coroutines.delay(5_000)
                val p = media.playback.value
                if (p?.playing == true) {
                    DiagLog.add("resume: ${p.packageName} plays again (play key)")
                    ui.activeMediaAppId = mediaEntryId(p.packageName)
                    settings.lastMediaApp = p.packageName
                    return@launch
                }
            }
            val app = media.apps.value.firstOrNull { it.packageName == last }?.takeUnless { isVideo }
            if (app == null) { DiagLog.add("resume: $last is closed and has nothing to play from here, left as it is"); return@launch }
            DiagLog.add("resume: $last plays again (connected as a media app)")
            media.select(app)
            ui.activeMediaAppId = mediaEntryId(last)
            settings.lastMediaApp = last
            // A play before the app is connected is dropped (playPause then only connects).
            val waitMs = 10_000L
            if (!media.awaitConnection(waitMs)) {
                val line = "resume music: ${app.label} wasn't ready, not playing (waited up to ${waitMs / 1_000} s)"
                Log.w(TAG, line)
                DiagLog.add(line)
                return@launch
            }
            if (media.playback.value?.playing != true) media.playPause()
        }
    }

    /** How [Settings.appOrder] names an entry: its package, or its id for AARE's own apps. */
    private fun orderKey(e: CarAppEntry) = if (e.packageName == context.packageName) e.id else e.packageName

    private fun builtinIcon(res: Int) = ContextCompat.getDrawable(context, res)?.toBitmap(128, 128)

    /** Packages of the navigation apps hosted on the car screen. */
    fun navigationPackages(): Set<String> = carApps.values.filter { it.isNavigation }.mapTo(HashSet()) { it.packageName }

    /** The connection for a car app entry, created on first use. */
    fun connection(id: String): CarAppConnection? {
        finished.remove(id)
        connections[id]?.let { return it }
        val info = carApps[id] ?: return null
        return CarAppConnection(context, info, this).also { connections[id] = it }
    }

    /** The connection the car screen draws: like [connection], but doesn't start an app that finished itself again. */
    private fun shownConnection(id: String): CarAppConnection? = if (id in finished) connections[id] else connection(id)

    private fun navConnection(): CarAppConnection? {
        val id = ui.activeNavAppId ?: ui.apps.firstOrNull { it.kind == CarAppKind.NAVIGATION }?.id ?: return null
        ui.activeNavAppId = id
        return connection(id)
    }

    private fun idOf(conn: CarAppConnection): String? = connections.entries.firstOrNull { it.value === conn }?.key

    private fun show(conn: CarAppConnection) {
        idOf(conn)?.let { ui.screen = CarScreen.App(it) }
    }

    fun acquireVisible(conn: CarAppConnection) {
        val n = (visibleCount[conn] ?: 0) + 1
        visibleCount[conn] = n
        if (n == 1) conn.setVisible(true)
    }

    fun releaseVisible(conn: CarAppConnection) {
        val n = (visibleCount[conn] ?: 1) - 1
        if (n <= 0) {
            visibleCount.remove(conn)
            // Let the other copy (dashboard card ↔ full screen) take over before stopping the app.
            main.postDelayed({ if ((visibleCount[conn] ?: 0) == 0) conn.setVisible(false) }, 500)
        } else visibleCount[conn] = n
    }

    /** Pushes car GPS/compass to subscribed apps. Any thread. */
    fun carSensorsChanged() = main.post { connections.values.forEach { it.carSensorsChanged() } }

    /** Night mode or screen size changed: tell running apps. */
    fun configurationChanged() {
        connections.values.forEach { it.configurationChanged() }
        clusterConnections.values.forEach { it.configurationChanged() }
    }

    // ------------------------------------------------------------------ notices

    /** Notice keys (LazyColumn keys): two notices in the same millisecond must not collide. */
    private var noticeSeq = 0L

    /** Keeps a notification (a message) for the notification centre. */
    fun remember(title: String, text: String, icon: Bitmap?, actions: List<Pair<String, () -> Unit>>) {
        ui.history.add(0, CarNotice("h${++noticeSeq}", title, text, icon, actions))
        while (ui.history.size > 30) ui.history.removeAt(ui.history.lastIndex)
        if ((ui.screen as? CarScreen.App)?.appId != NOTIFICATIONS_ID) ui.unseen++
    }

    fun notice(title: String, text: String, icon: Bitmap? = null, durationMs: Long = 4_000, actions: List<Pair<String, () -> Unit>> = emptyList(), message: Boolean = false) {
        val n = CarNotice("n${++noticeSeq}", title, text, icon, actions, message = message)
        ui.notices.add(n)
        main.postDelayed({ ui.notices.remove(n) }, durationMs)
    }

    // ------------------------------------------------------------------ actions from the car UI

    val actions = object : CarActions {
        override fun openApp(app: CarAppEntry) {
            DiagLog.add("open ${app.label} (${app.kind.name.lowercase()})")
            // No screen of its own and not a recent app: the projection stays as it is until the car takes focus.
            if (app.kind == CarAppKind.EXIT) { exitToCar?.invoke(); return }
            when (app.kind) {
                CarAppKind.MEDIA -> {
                    ui.activeMediaAppId = app.id
                    settings.lastMediaApp = app.packageName
                    media.apps.value.firstOrNull { it.packageName == app.packageName }?.let { media.select(it) }
                }
                CarAppKind.NAVIGATION -> {
                    ui.activeNavAppId = app.id
                    settings.lastNavApp = app.packageName
                    connection(app.id)?.connect()
                }
                CarAppKind.PHONE_APP -> {
                    // It takes the full-screen display: a video minimized there leaves the media card.
                    ui.videoInCard = null
                    if (app.video) {
                        rememberVideoMode(app.packageName, picture = true)
                        // A video app plays like music too: the media card and bar follow it (a music
                        // app's video side leaves that to its music screen).
                        if (app.inGrid) {
                            ui.activeMediaAppId = app.id
                            settings.lastMediaApp = app.packageName
                        }
                    }
                    // A music app opened mirrored (it refused AARE's media screen) is still the media app.
                    if (phoneApps[app.id]?.let { PhoneApps.kind(app.packageName, it.category) } == PhoneApps.Kind.MUSIC) {
                        ui.activeMediaAppId = app.id
                        settings.lastMediaApp = app.packageName
                    }
                }
                else -> connection(app.id)?.connect()
            }
            ui.screen = CarScreen.App(app.id)
            ui.recentAppIds.remove(app.id)
            ui.recentAppIds.add(0, app.id)
            while (ui.recentAppIds.size > 6) ui.recentAppIds.removeAt(ui.recentAppIds.lastIndex)
        }

        override fun goHome() { ui.screen = CarScreen.Dashboard }
        override fun back() = this@CarRuntime.back()
        override fun openLauncher() { ui.screen = CarScreen.Launcher }
        override fun openMapFull() { ui.screen = CarScreen.MapFull }
        override fun minimizeVideo() {
            val id = (ui.screen as? CarScreen.App)?.appId?.takeIf { ui.app(it)?.video == true } ?: return
            ui.app(id)?.let { rememberVideoMode(it.packageName, picture = true) }
            DiagLog.add("video: ${ui.app(id)?.label} into the media card")
            ui.videoInCard = id
            ui.screen = CarScreen.Dashboard
        }
        override fun maximizeVideo() {
            val entry = ui.app(ui.videoInCard) ?: run { ui.videoInCard = null; return }
            DiagLog.add("video: ${entry.label} full screen again")
            openApp(entry)
        }
        override fun assistant() { this@CarRuntime.assistant() }
        override fun mediaPlayPause() {
            // A video app played from home: the way it played last, with its picture (in the
            // media card) or as sound only.
            val np = ui.nowPlaying
            val pkg = media.playback.value?.packageName
            if (np != null && !np.playing && pkg != null && ui.screen == CarScreen.Dashboard && ui.videoInCard == null &&
                wantsPicture(pkg)) {
                val entry = ui.apps.firstOrNull { it.video && it.packageName == pkg }
                // Just connected, the mirrored apps may not be listed yet: shown once they are.
                if (entry != null) showVideoInCard(entry) else videoCardPending = pkg
            }
            media.playPause()
        }
        override fun audioOnly() {
            val entry = ui.app(ui.videoInCard) ?: (ui.screen as? CarScreen.App)?.let { ui.app(it.appId) }?.takeIf { it.video } ?: return
            DiagLog.add("video: ${entry.label} as sound only")
            rememberVideoMode(entry.packageName, picture = false)
            ui.videoInCard = null
            if (ui.screen == CarScreen.App(entry.id)) ui.screen = CarScreen.Dashboard
        }
        override fun showVideo() {
            val pkg = media.playback.value?.packageName ?: ui.nowPlaying?.let { ui.app(it.appId) }?.packageName
            val entry = ui.apps.firstOrNull { it.video && it.packageName == pkg } ?: return
            DiagLog.add("video: ${entry.label} with its picture again, in the media card")
            rememberVideoMode(entry.packageName, picture = true)
            ui.videoInCard = entry.id
            if (ui.screen != CarScreen.Dashboard) ui.screen = CarScreen.Dashboard
        }
        override fun mediaNext() = media.next()
        override fun mediaPrevious() = media.previous()
        override fun dismissNotice(notice: CarNotice) { ui.notices.remove(notice) }
        override fun answerCall() = Calls.answer(context)
        override fun endCall() = Calls.end(context)
        override fun navigateTo(place: String) {
            if (!this@CarRuntime.navigateTo(place)) notice(context.getString(R.string.notif_navigation_title), context.getString(R.string.notif_navigation_no_app_text))
        }
    }

    /** A video app played from home before the mirrored apps were listed: into the card once they are. */
    private var videoCardPending: String? = null

    private fun showVideoInCard(entry: CarAppEntry) {
        DiagLog.add("video: ${entry.label} plays in the media card (last played with its picture)")
        ui.videoInCard = entry.id
    }

    /** How a video app played last (with its picture or sound only), for the next play from home. */
    private fun rememberVideoMode(pkg: String, picture: Boolean) {
        if (pkg in PhoneApps.VIDEO_MUSIC) {
            val now = settings.musicWithPicture
            val next = if (picture) now + pkg else now - pkg
            if (next != now) settings.musicWithPicture = next
            return
        }
        val now = settings.videoAudioOnly
        val next = if (picture) now - pkg else now + pkg
        if (next != now) settings.videoAudioOnly = next
    }

    /** Whether [pkg] played last with its picture: video apps unless set to sound only, music apps only if set so. */
    private fun wantsPicture(pkg: String): Boolean =
        if (pkg in PhoneApps.VIDEO_MUSIC) pkg in settings.musicWithPicture
        else pkg !in settings.videoAudioOnly &&
            PhoneApps.kind(pkg, runCatching { context.packageManager.getApplicationInfo(pkg, 0).category }.getOrDefault(-1)) == PhoneApps.Kind.VIDEO

    /** The chosen assistant's package, or the phone's default assistant. */
    private fun assistantPackage(): String? = settings.assistantPackage ?: Assistants.phoneDefault(context)

    /**
     * Steering-wheel voice button or the rail's mic. An assistant with a car app (Lapin, for example)
     * opens on the car screen and is asked to listen with ACTION_VOICE_COMMAND; any other assistant
     * opens on the phone.
     */
    fun assistant() {
        onAssistant?.invoke()
        val pkg = assistantPackage()
        if (pkg == null) {
            notice(context.getString(R.string.notif_assistant_title), context.getString(R.string.notif_assistant_choose_text))
            return
        }
        val entry = ui.apps.firstOrNull { it.packageName == pkg && it.kind != CarAppKind.MEDIA && it.kind != CarAppKind.NAVIGATION }
        val conn = entry?.let { connection(it.id) }
        if (NativeCompat.refused(context, pkg) && openGemini()) return
        if (entry != null && conn != null) {
            // Like Android Auto's assistant: a bar over whatever is on screen, not the app full screen. Set before
            // the intent goes out: the app may open the microphone before the bar is drawn (see openMicrophone).
            if ((ui.screen as? CarScreen.App)?.appId != entry.id) ui.assistantAppId = entry.id
            conn.connect(Intent(Intent.ACTION_VOICE_COMMAND).setComponent(conn.info.service))
            return
        }
        val label = Assistants.label(context, pkg) ?: pkg
        if (Assistants.startOnPhone(context, pkg)) {
            notice(context.getString(R.string.notif_assistant_title), context.getString(R.string.notif_assistant_no_car_screen, label))
        } else {
            notice(context.getString(R.string.notif_assistant_title), context.getString(R.string.notif_assistant_cant_open, label))
        }
    }

    /** The phone app for the GTS big card, if it's set, installed and Shizuku can show it; else null. */
    /**
     * The phone's assistant for one that refuses AARE (Gemini). Like Android Auto's: its small
     * floating window, already listening, over what the car shows: the assist key pressed on the
     * phone display in view (the map card on the home screen, the phone app full screen), so the
     * system opens it there itself, even with the phone locked. The button again closes it. If it
     * doesn't come up, the Gemini app instead (below). False when it can't be shown.
     */
    private fun openGemini(): Boolean {
        if (!settings.phoneApps || !PhoneApps.available()) return false
        val onApp = (ui.screen as? CarScreen.App)?.let { ui.app(it.appId) }?.takeIf { it.kind == CarAppKind.PHONE_APP }
        val map = mapCardComponent()
        val host = when {
            onApp != null -> PhoneApps.full
            map != null -> PhoneApps.card
            else -> null
        }
        if (host != null) {
            // Elsewhere (the app grid, AARE's own screens) nothing of the phone is in view: home first.
            if (onApp == null && ui.screen != CarScreen.Dashboard && ui.screen != CarScreen.MapFull) actions.goHome()
            scope.launch {
                val top = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { host.topPackage() }
                if (top == ASSISTANT_HOST) {
                    DiagLog.add("assistant: floating window closed")
                    host.back()
                    return@launch
                }
                // Like Android Auto: what plays stops while the assistant listens and answers, and
                // plays on after. Gemini's window on another display doesn't pause it by itself.
                val paused = media.playback.value?.takeIf { it.playing }?.packageName
                if (paused != null) media.pause()
                host.assist()
                kotlinx.coroutines.delay(ASSIST_CHECK_MS)
                val now = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { host.topPackage() }
                if (now == ASSISTANT_HOST) {
                    DiagLog.add("assistant: floating window on the car (${if (onApp != null) "over ${onApp.label}" else "map card"})" +
                        if (paused != null) ", $paused paused" else "")
                    if (paused != null) resumeAfterAssistant(host, paused)
                } else {
                    DiagLog.add("assistant: no floating window (top is $now), opening Gemini instead")
                    if (paused != null) media.playPause()
                    openGeminiApp(map)
                }
            }
            return true
        }
        return openGeminiApp(map)
    }

    /**
     * Gemini mirrored: on the home screen it opens over the map in the big card (the same display),
     * and the button again brings the map back; elsewhere it opens full screen. It starts on its own
     * screen: the driver taps its microphone. False when it can't be shown.
     */
    private var assistantWatch: kotlinx.coroutines.Job? = null

    /**
     * Waits for the assistant's floating window to go (answered, dismissed, the button again), then
     * plays [pkg] on, unless something plays already (the driver asked for other music) or another
     * app took over. At most [ASSIST_WATCH_MS]: a window left open doesn't keep the music off for good.
     */
    private fun resumeAfterAssistant(host: net.prototux.aare.apps.PhoneAppHost, pkg: String) {
        assistantWatch?.cancel()
        assistantWatch = scope.launch {
            val until = android.os.SystemClock.elapsedRealtime() + ASSIST_WATCH_MS
            while (android.os.SystemClock.elapsedRealtime() < until) {
                kotlinx.coroutines.delay(700)
                val top = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { host.topPackage() }
                if (top != ASSISTANT_HOST) break
            }
            // A moment for the answer's last words and for a player the assistant started.
            kotlinx.coroutines.delay(800)
            val p = media.playback.value
            if (p?.playing == true) { DiagLog.add("assistant: closed, ${p.packageName} plays already"); return@launch }
            if (p != null && p.packageName != pkg) { DiagLog.add("assistant: closed, ${p.packageName} is the player now, $pkg left paused"); return@launch }
            DiagLog.add("assistant: closed, $pkg plays on")
            media.playPause()
        }
    }

    private fun openGeminiApp(map: android.content.ComponentName?): Boolean {
        val gemini = context.packageManager.getLaunchIntentForPackage(PhoneApps.GEMINI)?.component ?: return false
        if (map != null && (ui.screen == CarScreen.Dashboard || ui.screen == CarScreen.MapFull)) {
            scope.launch {
                val top = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PhoneApps.card.topPackage() }
                val back = top in PhoneApps.ASSISTANT_PACKAGES
                DiagLog.add("assistant: Gemini ${if (back) "closed, map back" else "opened"} in the map card (top was $top)")
                if (back) PhoneApps.card.open(map) else PhoneApps.card.open(gemini)
            }
            return true
        }
        val entry = ui.apps.firstOrNull { it.packageName == PhoneApps.GEMINI && it.kind == CarAppKind.PHONE_APP } ?: return false
        actions.openApp(entry)
        return true
    }

    private fun mapCardComponent(): android.content.ComponentName? {
        // Every style's home screen has it since 2026-11-12 (it was the GTS style's alone).
        if (!settings.phoneApps || !PhoneApps.available()) return null
        val pkg = settings.mapCardApp.ifBlank { return null }
        return context.packageManager.getLaunchIntentForPackage(pkg)?.component
    }

    /** Set by a screen with its own back stack (a conversation in a messaging app): true if it handled back. */
    var screenBack: (() -> Boolean)? = null

    /** Back key from the car. */
    fun back() {
        if (ui.assistantAppId != null) { ui.assistantAppId = null; return }
        if (screenBack?.invoke() == true) return
        when (val s = ui.screen) {
            is CarScreen.App -> {
                val conn = connections[s.appId]
                // An app still connecting or that failed has no screen to go back in: leave it.
                if (conn != null && conn.status == CarAppConnection.Status.READY) conn.back() else ui.screen = CarScreen.Dashboard
            }
            CarScreen.Launcher, CarScreen.MapFull -> ui.screen = CarScreen.Dashboard
            CarScreen.Dashboard -> Unit
        }
    }

    // ------------------------------------------------------------------ UI

    /** The whole car screen. */
    @Composable
    fun Root(modifier: Modifier = Modifier) {
        val mediaApps by media.apps.collectAsState()
        CarTheme(night = ui.night, gts = ui.gtsStyle) {
            // The home screen's big card: a phone app live (Google Maps by default), when it can.
            val cardApp = remember(ui.gtsStyle) { mapCardComponent() }
            val mapCard: (@Composable (Modifier) -> Unit)? = cardApp?.let { component ->
                { mod -> PhoneAppScreen(this, component, PhoneApps.Kind.NAVIGATION, ui.drivingRestricted, mod, host = PhoneApps.card, reopen = false) }
            }
            // A video minimized into the media card: the full-screen display, shown small there.
            val videoCard: (@Composable (Modifier) -> Unit)? = ui.videoInCard?.let { id -> phoneApps[id] }?.let { a ->
                { mod -> PhoneAppScreen(this, a.component, PhoneApps.Kind.VIDEO, ui.drivingRestricted, mod, reopen = true, inCard = true) }
            }
            CompositionLocalProvider(LocalCarDimens provides if (ui.gtsStyle) net.prototux.aare.ui.theme.GtsCarDimens else net.prototux.aare.ui.theme.TightCarDimens,
                net.prototux.aare.ui.car.LocalMapCard provides mapCard, net.prototux.aare.ui.car.LocalVideoCard provides videoCard,
                net.prototux.aare.ui.car.LocalCarMotion provides ui.motion) {
                CarShell(
                    state = ui,
                    actions = actions,
                    appContent = { app, mod ->
                        when (app.kind) {
                            CarAppKind.MEDIA -> {
                                val m = mediaApps.firstOrNull { it.packageName == app.packageName }
                                if (m != null) media.Screen(m, ui.drivingRestricted, mod)
                            }
                            CarAppKind.MESSAGING -> MessagingScreen(app, ui.drivingRestricted, this, mod)
                            CarAppKind.PHONE_SCREEN -> MirrorScreen(this, ui.drivingRestricted, mod)
                            CarAppKind.STANDALONE -> StandaloneAppScreen(this, ui.drivingRestricted, app.packageName, mod)
                            CarAppKind.BROWSER -> BrowserScreen(this, ui.drivingRestricted, settings.browserUrl, mod)
                            CarAppKind.CAR_DATA -> CarDataScreen(mod)
                            CarAppKind.PHONE -> PhoneScreen(this, mod)
                            CarAppKind.SETTINGS -> CarSettingsScreen(settings, mod)
                            CarAppKind.NOTIFICATIONS -> NotificationsScreen(ui, mod)
                            CarAppKind.PHONE_APP -> phoneApps[app.id]?.let { a ->
                                androidx.compose.runtime.key(a.component) {
                                    PhoneAppScreen(this, a.component, PhoneApps.kind(app.packageName, a.category), ui.drivingRestricted, mod)
                                }
                            }
                            else -> shownConnection(app.id)?.let { CarAppScreen(it, this, mod) }
                        }
                    },
                    navMap = { app, mod -> shownConnection(app.id)?.let { CarAppScreen(it, this, mod, compact = true) } },
                    overlay = { mod ->
                        val entry = ui.app(ui.assistantAppId)
                        val conn = entry?.let { shownConnection(it.id) }
                        if (entry != null && conn != null) {
                            AssistantBar(entry, conn, this, mod,
                                onClose = { ui.assistantAppId = null },
                                onExpand = { ui.assistantAppId = null; ui.screen = CarScreen.App(entry.id) })
                        }
                    },
                    modifier = modifier,
                )
            }
        }
    }

    companion object {
        private const val TAG = "AareRuntime"
        /** The app whose activity is the assistant's floating window (Gemini's runs in the Google app). */
        private const val ASSISTANT_HOST = "com.google.android.googlequicksearchbox"
        /** How long the floating window takes to come up before falling back to the Gemini app. */
        private const val ASSIST_CHECK_MS = 2_500L
        /** The clock card's agenda: the next events it lists. */
        private const val AGENDA_EVENTS = 3
        /** How far ahead the clock card's agenda looks: the next event, even weeks away. */
        private const val AGENDA_HOURS = 60 * 24
        /** The longest the music waits for the assistant's window to close. */
        private const val ASSIST_WATCH_MS = 120_000L
        private const val PROBE_DELAY_MS = 8_000L
        private const val CLEANUP_DELAY_MS = 3_000L
        /** After the car session's last unbind, so the apps have finished with it. */
        private val LEFTOVER_DELAYS_MS = longArrayOf(5_000L, 20_000L)
        private const val PROBE_TIMEOUT_MS = 12_000L
        fun carId(app: CarAppInfo) = "car:${app.service.flattenToShortString()}"
        fun mediaId(pkg: String) = "media:$pkg"
        fun messagingId(pkg: String) = "messages:$pkg"
        fun phoneAppId(pkg: String) = "phone-app:$pkg"
        private const val PHONE_APP_ICON_PX = 96
        fun standaloneId(pkg: String) = "standalone:$pkg"
        const val PHONE_SCREEN_ID = "phone-screen"
        const val BROWSER_ID = "browser"
        const val CAR_DATA_ID = "car-data"
        const val PHONE_ID = "phone"
        const val SETTINGS_ID = "car-settings"
        const val NOTIFICATIONS_ID = "notifications"
        const val EXIT_ID = "exit-to-car"

        /** Started runtimes, oldest first. Main thread. */
        private val running = ArrayList<CarRuntime>()

        /**
         * The car screen being driven now, for the voice assistant's car tools: the car session's
         * runtime, or the preview's when no car is connected (opening or closing the preview
         * during a drive doesn't take the car away). Main thread.
         */
        val active: CarRuntime? get() = running.lastOrNull { it.primary } ?: running.lastOrNull()
    }
}
