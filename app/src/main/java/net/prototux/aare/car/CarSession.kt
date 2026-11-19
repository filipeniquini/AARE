package net.prototux.aare.car

import aap_protobuf.service.control.message.AudioFocusNotificationOuterClass.AudioFocusNotification
import aap_protobuf.service.control.message.AudioFocusRequestTypeOuterClass.AudioFocusRequestType
import aap_protobuf.service.control.message.AudioFocusStateTypeOuterClass.AudioFocusStateType
import aap_protobuf.service.control.message.NavFocusTypeOuterClass.NavFocusType
import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import aap_protobuf.service.control.message.DriverPositionOuterClass.DriverPosition
import aap_protobuf.service.inputsource.message.InputReportOuterClass.InputReport
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.shared.message.UiConfigOuterClass.UiConfig
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import aap_protobuf.service.media.sink.message.VideoCodecResolutionTypeOuterClass.VideoCodecResolutionType
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import aap_protobuf.service.media.sink.message.VideoFrameRateTypeOuterClass.VideoFrameRateType
import aap_protobuf.service.mediaplayback.message.MediaPlaybackMetadataOuterClass.MediaPlaybackMetadata
import aap_protobuf.service.mediaplayback.message.MediaPlaybackStatusOuterClass.MediaPlaybackStatus
import aap_protobuf.service.sensorsource.message.SensorBatchOuterClass.SensorBatch
import aap_protobuf.service.ServiceOuterClass.Service
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import android.location.Location
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.content.SharedPreferences
import net.prototux.aare.Settings
import net.prototux.aare.aap.AapLog
import net.prototux.aare.aap.AapSession
import net.prototux.aare.aap.CertificateRefusal
import net.prototux.aare.aap.PhoneDescription
import net.prototux.aare.aap.PhoneIdentity
import net.prototux.aare.aap.PinRefusal
import net.prototux.aare.aap.Priority
import net.prototux.aare.aap.ServiceChannel
import net.prototux.aare.aap.SessionOptions
import net.prototux.aare.aap.Transport
import net.prototux.aare.aap.channels.AudioChannel
import net.prototux.aare.aap.channels.BluetoothChannel
import net.prototux.aare.aap.channels.InputChannel
import net.prototux.aare.aap.channels.MediaPlaybackChannel
import net.prototux.aare.aap.channels.MicrophoneChannel
import net.prototux.aare.aap.channels.NavigationStatusChannel
import net.prototux.aare.aap.channels.PassiveChannel
import net.prototux.aare.aap.channels.SensorChannel
import net.prototux.aare.aap.channels.VideoChannel
import net.prototux.aare.aap.channels.WifiProjectionChannel
import net.prototux.aare.BuildConfig
import net.prototux.aare.DiagLog
import net.prototux.aare.PinnedHeadUnits
import net.prototux.aare.audio.AudioPolicySource
import net.prototux.aare.audio.CaptureMethod
import net.prototux.aare.audio.CaptureMethods
import net.prototux.aare.audio.CarAudioOut
import net.prototux.aare.audio.PcmSource
import net.prototux.aare.audio.PlaybackCaptureSource
import net.prototux.aare.audio.ShizukuPolicySource
import net.prototux.aare.audio.ShizukuSource
import net.prototux.aare.audio.SuSource
import net.prototux.aare.audio.MicRouter
import net.prototux.aare.R
import net.prototux.aare.host.CarRuntime
import net.prototux.aare.media.MediaHub
import net.prototux.aare.video.BitrateController
import net.prototux.aare.video.ProjectionDisplay
import net.prototux.aare.video.VideoCodecKind
import net.prototux.aare.video.VideoEncoder
import java.io.ByteArrayOutputStream

/**
 * One connection to a head unit, on the phone: AAP on one side, the car runtime (UI, hosted
 * apps, media) on the other.
 */
class CarSession(
    private val context: Context,
    private val transport: Transport,
    private val identity: PhoneIdentity,
    private val settings: Settings,
    private val media: MediaHub,
    /** Screen-capture consent, if the user gave it: phone audio to the car, and the phone screen. */
    private val projection: () -> MediaProjection?,
    private val wireless: Boolean,
    /** Identifies the head unit among the trusted certificates: Bluetooth address, USB accessory, or "tcp". */
    peerKey: String,
    /** Trust the head unit's first certificate without asking (Bluetooth bonding already was the user's consent). */
    trustOnFirstUse: Boolean,
    private val listener: Listener,
    /** Offer the phone screen to the car; false for the developer TCP server unless opted in, as its peer is unauthenticated. */
    mirror: Boolean = true,
) : AapSession.Callbacks {

    interface Listener {
        fun onStatus(session: CarSession, status: String)
        /**
         * [certificateRefusal] is set when the head unit refused the phone's certificate,
         * [pinRefusal] when the phone refused the head unit's.
         */
        fun onEnded(session: CarSession, reason: String, certificateRefusal: CertificateRefusal?, pinRefusal: PinRefusal?)
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val runtime = CarRuntime(context, scope, settings, media, ownsMedia = false, phoneScreenAllowed = mirror)
    private val aap = AapSession(transport, identity, PhoneDescription(
        deviceName = deviceName(), label = "Android", instanceId = settings.instanceId,
        smallIcon = icon(32), mediumIcon = icon(64), largeIcon = icon(128),
    ), this, if (settings.verboseProtocolLog) net.prototux.aare.SessionAapLog.also { it.verbose = true } else net.prototux.aare.DiagAapLog, SessionOptions(sendPings = wireless, pins = PinnedHeadUnits(context), peerKey = peerKey, trustOnFirstUse = trustOnFirstUse))

    private var video: VideoChannel? = null
    private var mic: MicrophoneChannel? = null
    private var navStatus: NavigationStatusChannel? = null
    /** The instrument cluster's video, when the car has a cluster display. */
    private var cluster: ClusterDisplay? = null
    private var playback: MediaPlaybackChannel? = null
    private var phoneStatus: net.prototux.aare.aap.channels.PhoneStatusChannel? = null
    /** Calls, signal, battery and call availability to the car (from onRunning). */
    @Volatile private var phoneBridge: PhoneStatusBridge? = null
    /** Moves calls to the phone's speaker while connected (Settings.callsOnPhoneSpeaker); null when off. */
    private var callSpeaker: CallSpeaker? = null
    private val audio = ArrayList<AudioChannel>()

    /**
     * The car channel a kind of audio goes to: the first one the car lists. Some cars offer two of
     * a kind (the Ford Mustang Mach-E has two media and two guidance channels, #74). Feeding both
     * made their captures stop each other (Shizuku records once for the whole phone, and an output
     * replaced by the second channel releases that recording), so the car only got silence.
     */
    private fun isPrimary(channel: AudioChannel) = audio.firstOrNull { it.streamType == channel.streamType } === channel

    // @Volatile: written on main (startProjection), read on the AAP reader thread (input) and the
    // encoder callback thread; without it a restart can leave them seeing a stale display/mapper.
    @Volatile private var encoder: VideoEncoder? = null
    @Volatile private var display: ProjectionDisplay? = null
    @Volatile private var touch: TouchMapper? = null
    /** Overrides for this specific head unit from vehicles.json (density, margins…), matched at discovery. */
    @Volatile private var vehicleOverride: VehicleOverride? = null
    /** The video codec matched to this head unit (phone-hardware ∩ car-advertised), chosen at discovery. */
    @Volatile private var videoCodec: VideoCodecKind = VideoCodecKind.H264
    private val audioOuts = java.util.concurrent.ConcurrentHashMap<AudioStreamType, CarAudioOut>()
    private val micRouter = MicRouter({ mic }, { active -> if (aap.state == AapSession.State.RUNNING) aap.voiceSession(active) })
    @Volatile private var mediaFocus = false
    /** The car lets navigation voice play (but not music). */
    @Volatile private var guidanceFocusState = false
    /** Audio focus notifications the car has sent this session, answers or not. */
    @Volatile private var audioFocusAnswers = 0
    /**
     * The car answered our own guidance request. Some head units (Ford SYNC) answer the transient
     * may-duck request with a plain LOSS: the music lost focus so the prompt can play. Taken as
     * a grant for the guidance channel, or the prompt waits for a GAIN that never comes.
     */
    @Volatile private var guidanceAnswered = false
    @Volatile var headUnitName: String = ""
        private set
    @Volatile var videoDescription: String = ""
        private set

    fun start() {
        // This trip's logs (a folder per trip, kept): logcat is read from a little before, so off the main thread.
        Thread({ net.prototux.aare.TripLog.begin(context, transport.name) }, "aare-trip-begin").start()
        // The head unit is waiting for our version answer: start the protocol before the UI work.
        aap.start()
        runtime.microphone = { sink, stopped -> micRouter.open(sink, stopped) }
        runtime.projection = projection
        runtime.exitToCar = ::exitToCar
        runtime.onTrip = { _, trip ->
            guidanceChanged(hosted = true, guiding = trip != null)
            navBridge?.update(trip)
        }
        runtime.onPhoneGuidance = { g ->
            guidanceChanged(hosted = false, guiding = g != null)
            navBridge?.updatePhone(g)
        }
        runtime.start()
        // The car's own readings for the GTS car card, from the OBD2 adapter while connected.
        holdMusicUntilCarAudio()
        net.prototux.aare.obd.GpsTrip.start()
        if (settings.obdEnabled) {
            net.prototux.aare.obdapp.ObdApp.attach(context)
            net.prototux.aare.obd.ObdReader.start(context, settings.obdSimulated, owner = this)
        }
        if (settings.accessibilityInCarOnly && settings.phoneScreen) net.prototux.aare.mirror.AccessibilitySwitch.carConnected(context)
        settings.registerListener(uiScaleListener)
        listener.onStatus(this, context.getString(R.string.notif_status_connecting_to, transport.name))
    }

    /** The interface size changed: lay the projection out again, like a UI config update from the car. */
    private val uiScaleListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Settings.KEY_UI_SCALE) main.post {
            val last = videoConfig ?: return@post
            try {
                startProjection(last, runtime.rightHandDrive)
            } catch (e: Exception) {
                Log.e(TAG, "can't apply the new interface size", e)
                aap.stop()
            }
        }
    }

    fun stop() = aap.stop()

    /**
     * The session closed before the head unit's version request ever came in: this attempt never
     * reached the version exchange, let alone the handshake, so nothing about it (certificate,
     * trust) was actually tried. Typically the head unit's first try, dropped immediately, before
     * its own link was ready.
     */
    val closedBeforeVersionExchange: Boolean get() = aap.closedDuring == AapSession.State.VERSION

    /**
     * Asks the head unit to show its own UI (radio, vehicle settings), like Android Auto's "Exit".
     * Focus isn't dropped here: the head unit answers with a native VIDEO_FOCUS_NOTIFICATION (or
     * refuses, and projection carries on), and its projection icon gives the screen back later.
     * Any thread: sends only queue.
     */
    fun exitToCar() {
        val ch = video ?: return
        if (aap.state != AapSession.State.RUNNING || !ch.opened) return
        Log.i(TAG, "asking the car for its own screen")
        ch.requestNativeFocus()
    }

    val rttMs: Long get() = aap.lastRttMs

    // ------------------------------------------------------------------ AAP callbacks (reader thread)

    override fun onServiceDiscovery(session: AapSession, response: ServiceDiscoveryResponse): List<ServiceChannel> {
        headUnitName = AapSession.describe(response).substringBefore(" [").ifBlank { response.displayName }
        response.headunitInfo.let { hu ->
            vehicleOverride = VehicleOverrides.forHeadUnit(context,
                make = hu.make.ifEmpty { response.make }, model = hu.model.ifEmpty { response.model }, name = headUnitName)
            vehicleOverride?.let { DiagLog.add("vehicles.json override for \"$headUnitName\": $it") }
        }
        CarData.discovered(response, owner = this)
        val rhd = response.driverPosition == DriverPosition.DRIVER_POSITION_RIGHT
        main.post {
            runtime.ui.headUnitName = response.displayName.ifBlank { headUnitName }
            listener.onStatus(this, context.getString(R.string.notif_status_connected_to, headUnitName))
            settings.rememberCar(runtime.ui.headUnitName)
        }
        val channels = ArrayList<ServiceChannel>()
        // Input services are resolved after the loop, once the main display id is known: a car with
        // a cluster/second screen lists one input service per display, several of them empty.
        val inputServices = ArrayList<Service>()
        var mainDisplayId = 0
        val quirks = vehicleOverride
        for (s in response.channelsList) {
            val kind = AapSession.serviceKind(s)
            if (quirks?.skips(kind) == true) {
                DiagLog.add("vehicles.json: not opening $kind#${s.id}")
                continue
            }
            when {
                s.hasMediaSinkService() && s.mediaSinkService.videoConfigsCount > 0 -> {
                    // The main display, and the instrument cluster's if the car has one (display_type CLUSTER).
                    DiagLog.add("video service ${s.id}: ${s.mediaSinkService.toString().replace(Regex("\\s+"), " ")}")
                    when (s.mediaSinkService.displayType.number) {
                        0 -> if (video == null) {
                            mainDisplayId = s.mediaSinkService.displayId
                            videoCodec = pickVideoCodec(s.mediaSinkService.videoConfigsList)
                            video = VideoChannel(s, videoListener(rhd), videoType(videoCodec)).also { channels += it }
                        }
                        1 -> if (cluster == null) cluster = ClusterDisplay(context, runtime).also { channels += it.open(s) }
                    }
                }
                s.hasMediaSinkService() -> {
                    val sink = s.mediaSinkService
                    when {
                        // The official phone never uses telephony audio (calls stay on Bluetooth HFP).
                        sink.audioType == AudioStreamType.AUDIO_STREAM_TELEPHONY -> Unit
                        // We only encode PCM. A few head units advertise an AAC sink (sometimes
                        // alongside a PCM one, sometimes before it) and feeding PCM to it gives
                        // silence, so skip it: a PCM sink of the same stream, if the car lists one,
                        // becomes the primary instead. Codecs default to PCM, so cars that don't
                        // advertise one are unaffected.
                        sink.availableType != MediaCodecType.MEDIA_CODEC_AUDIO_PCM ->
                            Log.i(TAG, "skipping ${sink.audioType.name.removePrefix("AUDIO_STREAM_")} sink #${s.id}: " +
                                "needs ${sink.availableType.name.removePrefix("MEDIA_CODEC_AUDIO_")}, we only send PCM")
                        else -> AudioChannel(s, audioListener).also { audio += it; channels += it }
                    }
                }
                s.hasMediaSourceService() -> mic = MicrophoneChannel(s, micListener, sendSetup = quirks?.micSetup ?: true).also { channels += it }
                s.hasInputSourceService() -> inputServices += s
                s.hasSensorSourceService() -> channels += SensorChannel(s, sensorListener).also {
                    DiagLog.add("car sensors: " + it.available.joinToString { t -> t.name.removePrefix("SENSOR_").lowercase() })
                }
                s.hasNavigationStatusService() -> navStatus = NavigationStatusChannel(s).also { channels += it }
                s.hasMediaPlaybackService() -> playback = MediaPlaybackChannel(s).also { channels += it }
                s.hasPhoneStatusService() -> phoneStatus = net.prototux.aare.aap.channels.PhoneStatusChannel(s) { phoneBridge?.onInput(it) }.also { channels += it }
                s.hasWifiProjectionService() -> channels += WifiProjectionChannel(s)
                s.hasBluetoothService() -> channels += BluetoothChannel(s, phoneBtAddress(), object : BluetoothChannel.Listener {
                    override fun onPairingResponse(alreadyPaired: Boolean, ok: Boolean) {
                        DiagLog.add("car bluetooth: pairing ${if (ok) "accepted" else "refused"}${if (alreadyPaired) " (already paired)" else ""}")
                    }
                }).also { bluetooth = it }
            }
        }
        // The input service for the projected (main) display: match its display id, require a real
        // non-secondary touch panel, and skip the empty input services a multi-screen unit lists.
        fun realTouch(s: Service) = s.inputSourceService.touchscreenList.any { (it.width > 0 || it.height > 0) && !it.isSecondary }
        val mainInput = inputServices.firstOrNull { it.inputSourceService.displayId == mainDisplayId && realTouch(it) }
            ?: inputServices.firstOrNull(::realTouch)
            ?: inputServices.firstOrNull()
        val hasTouch = mainInput?.let(::realTouch) == true
        main.post { runtime.touchscreen = hasTouch }
        mainInput?.let { s ->
            InputChannel(s, inputListener).also { ch ->
                ch.touchScreen?.let { touchSize = it.width to it.height }
                channels += ch
                Log.i(TAG, "input: display ${s.inputSourceService.displayId} panel ${ch.touchScreen?.let { "${it.width}x${it.height}" } ?: "none"} of ${inputServices.size} service(s)")
            }
        }
        // Services the head unit lists that AARE doesn't speak, opened because a rule says this car wants them open.
        if (quirks?.openServices != null) for (s in response.channelsList) {
            val kind = AapSession.serviceKind(s)
            if (quirks.opens(kind) && !quirks.skips(kind) && channels.none { it.id == s.id }) {
                DiagLog.add("vehicles.json: opening $kind#${s.id}")
                channels += PassiveChannel(s, kind)
            }
        }
        Log.i(TAG, "audio: " + audio.joinToString { ch ->
            "${ch.streamType.name.removePrefix("AUDIO_STREAM_")}#${ch.id} " +
                ch.service.mediaSinkService.audioConfigsList.joinToString("|") { "${it.samplingRate}/${it.numberOfBits}/${it.numberOfChannels}" } +
                if (isPrimary(ch)) "" else " (not used: a second channel of this kind)"
        })
        // Some head units hold back the streams until every channel they listed is open: say which
        // ones AARE leaves closed, so a car that shows nothing can be read from the connection log.
        val unopened = response.channelsList.filter { s -> channels.none { it.id == s.id } }
        if (unopened.isNotEmpty()) DiagLog.add("not opening: " + unopened.joinToString { AapSession.serviceKind(it) + "#" + it.id })
        return channels
    }

    /** The car's Bluetooth service, to tell it which phone this is (see [linkBluetooth]). */
    private var bluetooth: BluetoothChannel? = null

    /**
     * Tells the car the phone's Bluetooth address, as Android Auto does: the car then knows the
     * projection and its Bluetooth phone ("Pixel 7 Pro Filipe") are the same device. Without it
     * the VW Play listed them as two devices, and hung as soon as projected sound started (2026-11-08).
     * The address is hidden from apps: read through Shizuku.
     */
    private fun linkBluetooth() {
        val bt = bluetooth ?: return
        Thread({
            val address = net.prototux.aare.audio.ShellOnce.call(context) { it.bluetoothAddress() }
            if (address == null) { DiagLog.add("bluetooth: phone address unknown (Shizuku?), not sent"); return@Thread }
            DiagLog.add("bluetooth: car ${bt.carAddress}, methods ${bt.methods}; sending the phone's address")
            runCatching { bt.requestPairing(address) }
        }, "aare-bt-link").start()
    }

    override fun onRunning(session: AapSession) {
        linkBluetooth()
        net.prototux.aare.PerfLog.start(context, headUnitName) { perfFigures() }
        context.getSystemService(android.os.PowerManager::class.java)?.addThermalStatusListener(context.mainExecutor, thermalListener)
        main.post {
            if (closed) return@post
            listener.onStatus(this, context.getString(R.string.notif_status_projecting_to, headUnitName))
            navStatus?.let { navBridge = NavStatusBridge(it, { m, w, h -> turnImage(m, w, h) }, ::png) }
            navBridge?.updatePhone(runtime.ui.phoneGuidance)
            // A route that started before the session was running still gets the focus.
            requestNavFocus()
            drivingMode.start()
            // Opt-in: engine data from an OBD2 adapter for the Car data viewers (held only while connected).
            net.prototux.aare.notify.CarNotificationListener.sink = { m ->
                drivingMode.onMessage(m.key, m.group)
                if (!m.group || settings.groupMessages) {
                    runtime.remember(m.title, m.text, m.icon, m.actions)
                    if (settings.messageBanners) runtime.notice(m.title, m.text, m.icon, 10_000, m.actions, message = true)
                    if (settings.readMessagesAloud) m.readAloud()
                }
            }
            if (settings.resumeMedia) runtime.resumeMedia()
            startPlaybackStatus()
            phoneBridge = PhoneStatusBridge(context, aap, phoneStatus).also { it.start() }
            if (settings.callsOnPhoneSpeaker) callSpeaker = CallSpeaker(context).also { it.start() }
        }
    }

    override fun onAudioFocus(session: AapSession, notification: AudioFocusNotification) {
        audioFocusAnswers++
        // Any word from the car on focus, an answer or its own decision (radio, a call), ends the
        // asking. Before mediaFocus changes, which a check reads.
        val asked = synchronized(focusLock) { mediaFocusRetry.onAnswered().also { scheduleFocusCheck() } }
        if (asked != null) DiagLog.add("audio focus: the car answered after $asked asks")
        val state = notification.focusState
        if (!notification.unsolicited && guidancePlaying) {
            // A call or the car's own source taking over arrives as LOSS_TRANSIENT: not a grant.
            guidanceAnswered = state != AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT
            DiagLog.add("guidance focus: car answered ${state.name.removePrefix("AUDIO_FOCUS_STATE_")}")
        } else DiagLog.add("audio focus: car ${if (notification.unsolicited) "says" else "answered"} ${state.name.removePrefix("AUDIO_FOCUS_STATE_")}")
        // Ducking keeps the music going: the head unit lowers it itself.
        mediaFocus = state == AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN || state == AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_MEDIA_ONLY ||
            state == AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT || state == AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT_CAN_DUCK
        guidanceFocusState = state == AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT_GUIDANCE_ONLY ||
            state == AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT_CAN_DUCK
        when {
            // The car took the audio (radio, a call...): pause the phone's media like Android Auto
            // does, and play again when a transient loss ends.
            notification.unsolicited && (state == AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS || state == AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT) -> main.post {
                pausedByCar = state == AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT && media.playback.value?.playing == true
                media.pause()
            }
            // Only the car handing focus back resumes: a gain answering our own request means
            // something else is playing now, and the old music must stay paused.
            state == AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN || state == AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_MEDIA_ONLY -> main.post {
                if (pausedByCar) {
                    pausedByCar = false
                    if (notification.unsolicited && media.playback.value?.playing != true) media.playPause()
                }
            }
        }
    }

    /** Media paused by an unsolicited transient focus loss, to resume on the next gain (main thread). */
    private var pausedByCar = false

    override fun onClosed(session: AapSession, reason: String, error: Throwable?) {
        val rejected = session.certificateRefusal
        val refusal = session.pinRefusal
        net.prototux.aare.TripLog.end(reason + (error?.let { ": ${it.message ?: it}" } ?: ""))
        main.post {
            teardown()
            listener.onEnded(this, reason, rejected, refusal)
        }
    }

    // ------------------------------------------------------------------ video

    /** Matches the head unit's advertised video codecs against what this phone can hardware-encode. */
    private fun pickVideoCodec(configs: List<VideoConfiguration>): VideoCodecKind {
        val advertised = configs.map(::configCodec).toSet()
        val kind = matchVideoCodec(advertised, settings.forceH264) { VideoEncoder.hardwareEncodes(it) }
        val line = "video codec: ${kind.label} (car offers ${advertised.joinToString { it.name.removePrefix("MEDIA_CODEC_VIDEO_") }}" +
            (if (settings.forceH264) ", H.264 forced" else "") + ")"
        Log.i(TAG, line)
        DiagLog.add(line)
        return kind
    }

    private fun videoListener(rightHandDrive: Boolean) = object : VideoChannel.Listener {
        override fun chooseConfiguration(configs: List<VideoConfiguration>, preferred: List<Int>): Int {
            val index = chooseVideo(configs, preferred, vehicleOverride?.maxHeight ?: settings.maxVideoHeight,
                vehicleOverride?.prefer60fps ?: settings.prefer60fps, videoType(videoCodec))
            DiagLog.add("video: car offers ${configs.joinToString { describeVideo(it) }}; prefers $preferred; chose ${configs.getOrNull(index)?.let { describeVideo(it) }}")
            main.removeCallbacks(focusFallback)
            main.postDelayed(focusFallback, FOCUS_ANSWER_MS)
            main.removeCallbacks(videoReport)
            main.postDelayed(videoReport, VIDEO_REPORT_MS)
            return index
        }

        override fun onVideoStart(index: Int, config: VideoConfiguration) {
            main.post {
                if (closed) return@post
                try {
                    startProjection(config, rightHandDrive)
                    videoFocusAsks = 0
                    main.removeCallbacks(videoFocusCheck)
                    main.postDelayed(videoFocusCheck, VIDEO_FOCUS_RETRY_MS)
                } catch (e: Exception) {
                    // Never crash the app over one car: end this session and say why.
                    Log.e(TAG, "can't start the projection", e)
                    listener.onStatus(this@CarSession, context.getString(R.string.notif_status_cant_start_video, e.message))
                    aap.stop()
                }
            }
        }

        override fun onVideoFocus(projected: Boolean) {
            DiagLog.add("video focus: ${if (projected) "projected" else "native"}")
            main.post {
                encoder?.setSuspended(!projected)
                // The encoder was paused while the car showed its own screen: time the stall check from here.
                lastFrameMs = SystemClock.elapsedRealtime()
                if (projected) {
                    encoder?.requestKeyFrame()
                    display?.invalidate()
                }
                reportVideoAgain()
            }
        }

        override fun onKeyFrameNeeded() {
            encoder?.requestKeyFrame()
            display?.invalidate()
        }

        override fun onUiConfig(config: UiConfig) {
            // New insets or margins: lay the projection out again with them.
            main.post {
                if (closed) return@post
                val last = videoConfig ?: return@post
                Log.i(TAG, "UI config update from the head unit")
                try {
                    startProjection(last.toBuilder().setUiConfig(config).build(), rightHandDrive)
                } catch (e: Exception) {
                    Log.e(TAG, "can't apply the new UI config, keeping the old one", e)
                    try {
                        startProjection(last, rightHandDrive)
                    } catch (e: Exception) {
                        Log.e(TAG, "can't restart the projection", e)
                        aap.stop()
                    }
                }
            }
        }
    }

    /** The configuration the projection runs with (main thread), for UI config updates. */
    private var videoConfig: VideoConfiguration? = null

    /** Frames the encoder produced this session, whether or not they could be sent. */
    @Volatile private var framesEncoded = 0

    /** A car that never answers the focus request would otherwise get no video, and stay black. */
    private val focusFallback = Runnable {
        if (video?.assumeProjectedIfUnanswered() == true) {
            DiagLog.add("video focus: no answer from the car after ${FOCUS_ANSWER_MS / 1000} s, sending video anyway")
        }
    }

    /** What a black car screen needs explained: kept in the connection log, which survives the drive home. */
    /** The running stream's adaptive bitrate, for the thermal ceiling. */
    @Volatile private var bitrateControl: BitrateController? = null
    @Volatile private var thermalStatus = android.os.PowerManager.THERMAL_STATUS_NONE

    /**
     * Less video work while the phone is hot: the bitrate ceiling drops to 70 % at "moderate"
     * and 50 % from "severe" (Android's thermal status), and comes back once it cools. The
     * 2026-11-08 drive reached "moderate" with YouTube at 40 °C battery.
     */
    private val thermalListener = android.os.PowerManager.OnThermalStatusChangedListener { status ->
        val before = thermalStatus
        thermalStatus = status
        val factor = thermalCeiling(status)
        bitrateControl?.ceilingFactor = factor
        if (thermalCeiling(before) != factor) DiagLog.add("phone heat: status $status, video bitrate ceiling ${(factor * 100).toInt()} %")
    }

    private fun thermalCeiling(status: Int) = when {
        status >= android.os.PowerManager.THERMAL_STATUS_SEVERE -> 0.5
        status >= android.os.PowerManager.THERMAL_STATUS_MODERATE -> 0.7
        else -> 1.0
    }

    /** This session's figures for the performance log (perf thread: reads only). */
    private fun perfFigures(): Map<String, Any?> = LinkedHashMap<String, Any?>().apply {
        val ui = runtime.ui
        put("screen", when (val s = ui.screen) {
            is net.prototux.aare.ui.car.CarScreen.App -> s.appId.substringAfter(':').substringBefore('/')
            else -> s.javaClass.simpleName.lowercase()
        })
        put("driving", ui.drivingRestricted)
        put("car_driving", runtime.carDriving) // the car's own signal, before passenger mode
        put("night", ui.night)
        put("rtt_ms", aap.lastRttMs)
        video?.let { ch ->
            put("video_frames_total", framesEncoded)
            put("video_acks_total", ch.acksReceived)
            put("video_dropped_total", ch.droppedFrames)
            put("video_congestion_total", ch.congestionDrops)
            put("video_pending", ch.pendingFrames)
            put("video_ack_ms", ch.ackLatencyMs)
            put("video_send_ms", ch.sendLatencyMs)
            put("video_kbps", encoder?.currentBitrate?.let { it / 1000 })
            put("video_focused", ch.focused)
            put("video_stalls_total", ch.stalls)
            put("video_restarts_total", ch.restarts)
        }
        for ((type, out) in audioOuts.entries.sortedBy { it.key.name }) {
            val p = type.name.removePrefix("AUDIO_STREAM_").lowercase()
            put("${p}_playing", out.isPlaying)
            put("${p}_sent_total", out.chunksSent)
            put("${p}_dropped_total", out.chunksDropped)
            put("${p}_read_max_ms", out.takeSlowestReadMs())
        }
        val obd = net.prototux.aare.obd.ObdReader
        put("obd", obd.status.value.state.name.lowercase())
        // Always these columns (empty without readings), so the header doesn't change with the adapter.
        val r = obd.readings.value
        put("speed", r?.speed?.toInt()); put("rpm", r?.rpm?.toInt()); put("coolant", r?.coolant?.toInt())
    }

    private val videoReport = object : Runnable {
        var left = VIDEO_REPORTS
        override fun run() {
            val ch = video ?: return
            DiagLog.add("video: $framesEncoded frames encoded, ${ch.acksReceived} acks, ${ch.droppedFrames} dropped, ${ch.pendingFrames} waiting (window ${ch.maxUnacked}" +
                "${if (ch.ackless) ", no acks" else ""}), ack ${ch.ackLatencyMs} ms, focused=${ch.focused}, ${ch.stalls} stalls")
            if (--left > 0) main.postDelayed(this, VIDEO_REPORT_MS)
        }
    }

    /** A few more video reports, after the car switched screens (reverse camera, its own menus). */
    private fun reportVideoAgain() {
        main.removeCallbacks(videoReport)
        videoReport.left = 4
        main.postDelayed(videoReport, VIDEO_REPORT_MS)
    }

    private fun startProjection(config: VideoConfiguration, rightHandDrive: Boolean) {
        teardownVideo()
        videoConfig = config
        val (w, h) = resolution(config.codecResolution)
        // The car's rate, capped by the user's choice: the encoder sends at most that many frames.
        val fps = minOf(fpsOf(config), settings.maxVideoFps)
        val dpi = vehicleOverride?.dpi ?: scaledDensity(if (config.density > 0) config.density else defaultDensity(minOf(w, h)), settings.uiScalePercent)
        val layout = ProjectionLayout.of(config, w, h).let { base ->
            val o = vehicleOverride
            if (o?.widthMargin == null && o?.heightMargin == null) base
            else {
                // vehicles.json overrides the car's reported margins (split evenly, like the official phone).
                val fw = (o.widthMargin ?: (base.marginLeft + base.marginRight)).coerceIn(0, w - 1)
                val fh = (o.heightMargin ?: (base.marginTop + base.marginBottom)).coerceIn(0, h - 1)
                base.copy(marginLeft = fw / 2, marginRight = fw - fw / 2, marginTop = fh / 2, marginBottom = fh - fh / 2)
            }
        }
        videoDescription = "${w}x$h@$fps ${dpi}dpi" + layout.describe()
        Log.i(TAG, "projection $videoDescription")
        DiagLog.add("projection $videoDescription")

        val ch = video ?: return
        // lateinit: the sink below needs to know whether its encoder is still the current one.
        lateinit var enc: VideoEncoder
        enc = VideoEncoder(w, h, fps, kind = videoCodec, constantBitrate = settings.constantBitrateVideo, sink = object : VideoEncoder.Sink {
            private var frames = 0
            // Per stream: a restart (new config, encoder error) starts again from the full bitrate.
            private val abr = BitrateController(VideoEncoder.defaultBitrate(w, h, fps)) { encoder?.setBitrate(it) }
                .also { it.ceilingFactor = thermalCeiling(thermalStatus); bitrateControl = it }
            override fun onCodecConfig(data: ByteArray, offset: Int, length: Int) {
                DiagLog.add("video: codec config ${describeCodecConfig(data, offset, length)}")
                ch.sendCodecConfig(data, offset, length)
            }
            override fun onFrame(data: ByteArray, offset: Int, length: Int, ptsUs: Long, keyFrame: Boolean) {
                frames++
                framesEncoded++
                if (frames == 1) DiagLog.add("video: first frame (${if (keyFrame) "key, " else ""}$length bytes), focused=${ch.focused}")
                lastFrameMs = SystemClock.elapsedRealtime()
                if (frames == 1 || keyFrame && frames < 100 || frames % 600 == 0) {
                    Log.i(TAG, "video frame $frames (${if (keyFrame) "key, " else ""}$length bytes), focused=${ch.focused}, dropped=${ch.droppedFrames}")
                }
                ch.sendFrame(data, offset, length, ptsUs, keyFrame) { encoder?.recycle(it) }
                abr.onFrame(ch)
            }
            override fun onError(e: Exception) {
                Log.e(TAG, "encoder failed, restarting", e)
                main.post {
                    // The session ended, or this encoder was already replaced: nothing to restart.
                    if (closed || encoder !== enc) return@post
                    DiagLog.add("video: encoder failed, restarting: ${e.message}")
                    try {
                        if (!encoderRestarts.allow()) throw IllegalStateException("the encoder keeps failing", e)
                        startProjection(videoConfig ?: config, rightHandDrive)
                    } catch (failure: Exception) {
                        // Same as a failed first start: end the session (onClosed tears it all down).
                        Log.e(TAG, "can't restart the projection", failure)
                        listener.onStatus(this@CarSession, context.getString(R.string.notif_status_cant_start_video, failure.message))
                        aap.stop()
                    }
                }
            }
        })
        enc.start()
        encoder = enc

        runtime.densityDpi = dpi
        runtime.screenWidthDp = layout.contentWidth * 160 / dpi
        runtime.screenHeightDp = layout.contentHeight * 160 / dpi
        runtime.rightHandDrive = rightHandDrive
        // Assigned before show(), so that a failing show() still has teardownVideo() release the display.
        val pd = ProjectionDisplay(context, w, h, dpi, enc.inputSurface) {
            val d = LocalDensity.current
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                with(d) {
                    runtime.Root(Modifier.padding(start = layout.padLeft.toDp(), top = layout.padTop.toDp(), end = layout.padRight.toDp(), bottom = layout.padBottom.toDp()))
                }
            }
        }
        display = pd
        pd.show()
        lastFrameMs = SystemClock.elapsedRealtime()
        main.removeCallbacks(stallCheck)
        main.postDelayed(stallCheck, STALL_CHECK_MS)
        // Pass the declared panel as-is (0 when the head unit declares none); the mapper resolves
        // the source resolution, including 0x0, visible-pixel and under-declared panels.
        val inputCh = touchSize
        touch = TouchMapper(inputCh?.first ?: 0, inputCh?.second ?: 0, layout)
    }

    /** An encoder that fails again and again ends the session rather than restart forever. */
    private val encoderRestarts = RestartLimiter(MAX_RESTARTS, RESTART_WINDOW_MS)

    /** When the encoder last produced a frame (elapsedRealtime), for [stallCheck]. */
    @Volatile private var lastFrameMs = 0L
    private var stalled = false
    /**
     * Ours: when the stall line was last logged. On the Pixel a still screen stalls again a few
     * seconds after each redraw, and a line every 3.5 s filled the 300-line connection log.
     */
    private var stallLoggedMs = 0L

    /**
     * Keeps frames coming on a still car screen. The encoder is asked to repeat the last frame
     * (KEY_REPEAT_PREVIOUS_FRAME_AFTER), but some encoders stop producing anything once the
     * picture doesn't change, and a head unit that gets no frames shows black. While the car shows
     * AARE's screen and nothing came out for [STALL_MS], the screen is redrawn, which gives the
     * encoder a new buffer. Main thread; runs while there is a projection.
     */
    private val stallCheck = object : Runnable {
        override fun run() {
            if (closed || display == null) return
            val quiet = SystemClock.elapsedRealtime() - lastFrameMs
            if (video?.focused == true && quiet > STALL_MS) {
                val now = SystemClock.elapsedRealtime()
                if (!stalled && (stallLoggedMs == 0L || now - stallLoggedMs > STALL_LOG_EVERY_MS)) {
                    stallLoggedMs = now
                    DiagLog.add("video: no frame from the encoder for $quiet ms; redrawing the car screen (logged at most every 5 min)")
                }
                stalled = true
                display?.invalidate()
            } else if (quiet <= STALL_MS) {
                stalled = false
            }
            main.postDelayed(this, STALL_CHECK_MS)
        }
    }

    private fun teardownVideo() {
        main.removeCallbacks(stallCheck)
        display?.release(); display = null
        encoder?.stop(); encoder = null
    }

    // ------------------------------------------------------------------ input

    @Volatile private var touchSize: Pair<Int, Int>? = null
    private var touchesLogged = 0

    private val inputListener = object : InputChannel.Listener {
        override fun onInput(report: InputReport) {
            if (report.hasTouchEvent()) {
                if (touchesLogged < TOUCHES_TO_LOG) {
                    touchesLogged++
                    val p = report.touchEvent.pointerDataList.firstOrNull()
                    Log.i(TAG, "touch from the car: ${p?.x},${p?.y} action ${report.touchEvent.action}")
                }
                val ev = touch?.map(report.touchEvent) ?: return
                display?.dispatchTouch(ev)
            }
            if (report.hasKeyEvent()) {
                for (k in report.keyEvent.keysList) {
                    Log.d(TAG, "input key: code=${k.keycode} down=${k.down} long=${k.longpress}")
                    main.post { onKey(k.keycode, k.down, k.longpress) }
                }
            }
            if (report.hasRelativeEvent()) {
                for (r in report.relativeEvent.dataList) {
                    Log.d(TAG, "input rotary: keycode=${r.keycode} delta=${r.delta}")
                }
                // Rotary controller: each detent moves focus to the next/previous element in layout
                // order, like Android Auto. Compose maps Tab to "focus next" and Shift+Tab to
                // "focus previous" (not D-pad up/down, which is 2D spatial and wrong for a knob).
                for (r in report.relativeEvent.dataList) {
                    val meta = if (r.delta < 0) KeyEvent.META_SHIFT_ON else 0
                    repeat(kotlin.math.abs(r.delta)) {
                        val t = SystemClock.uptimeMillis()
                        display?.dispatchKey(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0, meta))
                        display?.dispatchKey(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, 0, meta))
                    }
                }
            }
        }
    }

    /** HOME goes to the dashboard; held, it hands the screen back to the car. */
    private val homeKey = LongPressKey(onPress = { runtime.actions.goHome() }, onLongPress = ::exitToCar)

    private fun onKey(code: Int, down: Boolean, longPress: Boolean) {
        // Some head units send a media key while they set up, before AARE has a car screen (a
        // play as the session starts): acting on it would start the music the driver didn't ask
        // for, and over whatever the car was playing. The driver's own presses come once the
        // screen is up.
        if (display == null && code in MEDIA_KEYS) {
            if (!down) DiagLog.add("ignoring ${KeyEvent.keyCodeToString(code)} from the car: no car screen yet")
            return
        }
        when (code) {
            KeyEvent.KEYCODE_HOME -> homeKey.onKey(down, longPress)
            KeyEvent.KEYCODE_BACK -> if (!down) back()
            KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_ASSIST -> if (!down) runtime.assistant()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> if (!down) media.playPause()
            KeyEvent.KEYCODE_MEDIA_PLAY -> if (!down && runtime.ui.nowPlaying?.playing != true) media.playPause()
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> if (!down) media.pause()
            KeyEvent.KEYCODE_MEDIA_NEXT -> if (!down) media.next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (!down) media.previous()
            KEYCODE_MEDIA -> if (!down) runtime.ui.app(runtime.ui.activeMediaAppId)?.let(runtime.actions::openApp)
            KEYCODE_NAVIGATION, KEYCODE_TURN_CARD -> if (!down) runtime.ui.app(runtime.ui.activeNavAppId)?.let(runtime.actions::openApp)
            // The car's dedicated Phone button opens the phone app; the Call/Endcall keys answer and hang up.
            KEYCODE_TEL -> if (!down) runtime.ui.app(CarRuntime.PHONE_ID)?.let(runtime.actions::openApp)
            KeyEvent.KEYCODE_CALL -> if (!down) { if (runtime.ui.call?.ringing == true) runtime.actions.answerCall() else runtime.ui.app(CarRuntime.PHONE_ID)?.let(runtime.actions::openApp) }
            KeyEvent.KEYCODE_ENDCALL -> if (!down) runtime.actions.endCall()
            // The Radio button hands the screen back to the car's own source (radio, media…), like "exit".
            KEYCODE_RADIO -> if (!down) exitToCar()
            else -> display?.dispatchKey(KeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code))
        }
    }

    /**
     * The car's Back key. A Compose BackHandler on the car screen (the media browser going up a
     * folder) comes first, as it would for the phone's own Back; then the runtime's back stack.
     * The assistant overlay closes before anything under it handles Back.
     */
    private fun back() {
        if (runtime.ui.assistantAppId == null && display?.back() == true) return
        runtime.back()
    }

    // ------------------------------------------------------------------ sensors

    /** Do Not Disturb and auto-reply while connected. */
    private val drivingMode = DrivingMode(context, settings)

    /** The car's GPS as the phone's location, so apps don't mix two positions. */
    private val locationFeed = PhoneLocationFeed(context)

    /** The car's last day/night signal, to log only its changes. */
    @Volatile private var lastNightLogged: Boolean? = null

    private val sensorListener = object : SensorChannel.Listener {
        override fun onSensors(batch: SensorBatch) {
            CarData.update(batch) // every sensor, for car apps and the assistant
            var carSensors = false
            batch.locationDataList.lastOrNull()?.let { l ->
                runtime.carLocation = Location("car").apply {
                    latitude = l.latitudeE7 / 1e7
                    longitude = l.longitudeE7 / 1e7
                    if (l.hasAccuracyE3()) accuracy = l.accuracyE3 / 1e3f
                    if (l.hasAltitudeE2()) altitude = l.altitudeE2 / 1e2
                    if (l.hasSpeedE3()) speed = l.speedE3 / 1e3f
                    if (l.hasBearingE6()) bearing = l.bearingE6 / 1e6f
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }.also {
                    // Turned off mid-drive: give the phone its own GPS back now, not at the disconnect.
                    if (settings.carGpsForPhone) locationFeed.push(it) else locationFeed.stop()
                    net.prototux.aare.obd.GpsTrip.add(it, SystemClock.elapsedRealtime())
                }
                carSensors = true
            }
            batch.compassDataList.lastOrNull()?.let { runtime.carHeading = it.bearingE6 / 1e6f; carSensors = true }
            if (carSensors) runtime.carSensorsChanged()
            batch.nightModeDataList.lastOrNull()?.let { n ->
                if (n.nightMode != lastNightLogged) { lastNightLogged = n.nightMode; DiagLog.add("car night mode: ${if (n.nightMode) "night" else "day"}") }
                main.post { runtime.carNightMode(n.nightMode) }
            }
            batch.drivingStatusDataList.lastOrNull()?.let { d ->
                main.post { runtime.carDriving(d.status != 0) }
            }
        }
    }

    // ------------------------------------------------------------------ audio

    private val audioListener = object : AudioChannel.Listener {
        override fun onAudioReady(channel: AudioChannel, config: aap_protobuf.service.media.shared.message.AudioConfigurationOuterClass.AudioConfiguration) {
            if (!settings.carAudio) return
            if (channel.streamType != AudioStreamType.AUDIO_STREAM_MEDIA && channel.streamType != AudioStreamType.AUDIO_STREAM_GUIDANCE) return
            if (!isPrimary(channel)) return
            main.post { startAudio(channel) }
        }
        override fun onUnderflow(channel: AudioChannel) { if (isPrimary(channel)) audioOuts[channel.streamType]?.onUnderflow() }
    }

    /** Capture methods that failed in this session: the next best one is used instead. */
    private val failedCapture = HashSet<CaptureMethod>()
    @Volatile private var mediaPlaying = false
    @Volatile private var guidancePlaying = false

    /**
     * Phone audio to one car channel, with the best capture method available. Android Auto's own
     * method (audio policy) feeds music and navigation voice to their own channels; the others
     * only have one mixed stream, which goes to the media channel.
     */
    private fun startAudio(channel: AudioChannel) {
        if (closed) return
        val cfg = channel.config ?: return
        val guidance = channel.streamType == AudioStreamType.AUDIO_STREAM_GUIDANCE
        val p = projection()
        val method = CaptureMethods.best(context, p, failedCapture)
        val rate = cfg.samplingRate
        val ch = cfg.numberOfChannels
        val source: PcmSource = when (method) {
            CaptureMethod.AUDIO_POLICY, CaptureMethod.SHIZUKU_POLICY -> {
                // Without a guidance channel in the car, navigation voice goes with the music.
                val hasGuidance = audio.any { it.streamType == AudioStreamType.AUDIO_STREAM_GUIDANCE }
                val usages = when {
                    guidance -> CaptureMethods.GUIDANCE_USAGES
                    hasGuidance -> CaptureMethods.MEDIA_USAGES
                    else -> CaptureMethods.MEDIA_USAGES + CaptureMethods.GUIDANCE_USAGES
                }
                if (method == CaptureMethod.AUDIO_POLICY) AudioPolicySource(context, usages, rate, ch)
                else ShizukuPolicySource(context, usages, rate, ch)
            }
            CaptureMethod.SHIZUKU -> when {
                guidance -> return
                CaptureMethods.shizukuAvailable() -> ShizukuSource(context, rate, ch)
                else -> SuSource(context, rate, ch)
            }
            CaptureMethod.PLAYBACK_CAPTURE -> if (guidance || p == null) return else PlaybackCaptureSource(p, rate, ch)
            null -> {
                if (!guidance) runtime.notice(context.getString(R.string.audio_settings_title), context.getString(R.string.audio_notice_setup_hint))
                return
            }
        }
        DiagLog.add(if (guidance) "car audio, navigation channel (${rate} Hz, $ch ch): ${method.label}" else "car audio: ${method.label}")
        DiagLog.add("audio ${channel.streamType}: car offers ${channel.service.mediaSinkService.audioConfigsList.joinToString { "${it.samplingRate}Hz/${it.numberOfBits}bit/${it.numberOfChannels}ch" }}, " +
            "config indices ${channel.offeredConfigs}, window ${channel.maxUnacked}; using ${rate}Hz/${ch}ch")
        audioOuts.remove(channel.streamType)?.stop()
        lateinit var out: CarAudioOut
        out = CarAudioOut(source, channel, if (guidance) guidanceFocus else mediaFocusRequests, onFailed = {
            main.post {
                // Only if it's still this stream's output: a late failure (a slow Shizuku bind
                // giving up after teardown, or after a replacement) must not start a capture
                // nothing would ever stop.
                if (!audioOuts.remove(channel.streamType, out)) return@post
                // Just restarted by the silence watch: the old helper was still going away and the
                // new one couldn't start (2026-11-12 heavy test: the car lost its sound for good).
                // The same method gets one more try a moment later instead of being written off.
                if (captureRestartPending) {
                    captureRestartPending = false
                    DiagLog.add("car audio: ${method.label} didn't restart at once, trying again")
                    main.postDelayed({ startAudio(channel) }, CAPTURE_RETRY_MS)
                    return@post
                }
                DiagLog.add("car audio: ${method.label} failed, trying the next method")
                failedCapture += method
                startAudio(channel)
                // Split capture gone: the other channel goes to the next method too (the mixed
                // stream needs the media channel alone, or nav voice would play twice).
                if (method == CaptureMethod.SHIZUKU_POLICY) {
                    audio.filter { it.streamType != channel.streamType && isPrimary(it) && audioOuts.containsKey(it.streamType) }.forEach { other ->
                        audioOuts.remove(other.streamType)?.stop()
                        startAudio(other)
                    }
                }
            }
        }, onCapturing = { if (!guidance) main.post { carAudioReady() } })
        audioOuts[channel.streamType] = out.also { it.start() }
        if (!guidance) { main.removeCallbacks(silenceWatch); main.postDelayed(silenceWatch, SILENCE_CHECK_MS) }
    }

    // ------------------------------------------------------------------ music the car doesn't hear
    // 2026-11-11 drive: after the first connection of the evening, three in a row captured only
    // silence for the whole drive while Spotify played on the phone (the right device, any car
    // source): nothing went to the car. The capture is now restarted when that happens, and the
    // log says where the phone was sending its sound, to find the cause.

    private var silentSinceMs = 0L
    private var lastCaptureRestartMs = 0L
    private var captureRestarts = 0
    /** The watch just restarted the capture: a failure now is the old helper's, not the method's. */
    private var captureRestartPending = false

    private val silenceWatch = object : Runnable {
        override fun run() {
            val out = audioOuts[AudioStreamType.AUDIO_STREAM_MEDIA] ?: return
            val now = android.os.SystemClock.elapsedRealtime()
            val playing = media.playback.value?.playing == true && !pausedUntilCarAudio
            if (!playing || out.isPlaying) silentSinceMs = 0L
            else if (silentSinceMs == 0L) silentSinceMs = now
            if (silentSinceMs != 0L && now - silentSinceMs > SILENT_MUSIC_MS &&
                captureRestarts < MAX_CAPTURE_RESTARTS && now - lastCaptureRestartMs > CAPTURE_RESTART_GAP_MS) {
                captureRestarts++
                lastCaptureRestartMs = now
                silentSinceMs = 0L
                DiagLog.add("car audio: ${media.playback.value?.packageName} plays but the capture hears only silence; " +
                    "phone sound goes to ${soundRoutes()}; restarting the capture ($captureRestarts/$MAX_CAPTURE_RESTARTS)")
                unmuteMusicForCar()
                val channel = audio.firstOrNull { it.streamType == AudioStreamType.AUDIO_STREAM_MEDIA }
                if (channel != null) { captureRestartPending = true; startAudio(channel); return } // startAudio schedules the next check
            }
            main.postDelayed(this, SILENCE_CHECK_MS)
        }
    }

    /** Where the phone's players send their sound now, and the outputs it has, for the log. */
    private fun soundRoutes(): String = runCatching {
        val am = context.getSystemService(android.media.AudioManager::class.java)
        val players = am.activePlaybackConfigurations.joinToString { c ->
            val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) c.audioDeviceInfo?.let { deviceName(it.type) } else null
            "usage ${c.audioAttributes.usage} -> ${device ?: "?"}"
        }.ifEmpty { "no player" }
        val outputs = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).map { deviceName(it.type) }.distinct().joinToString("/")
        // A navigation app that speaks over Bluetooth "call audio" (Waze's option) puts the phone in
        // communication mode, which may take the music off the path the capture listens to.
        val comm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) am.communicationDevice?.let { deviceName(it.type) } else null
        "$players (outputs $outputs, music muted ${am.isStreamMute(android.media.AudioManager.STREAM_MUSIC)}, audio mode ${am.mode}, call device ${comm ?: "none"}, bluetooth sco ${am.isBluetoothScoOn}, music volume ${am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)})"
    }.getOrDefault("unknown")

    private fun deviceName(type: Int): String = when (type) {
        android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth-a2dp"
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth-sco"
        android.media.AudioDeviceInfo.TYPE_BLE_HEADSET, android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER -> "bluetooth-le"
        android.media.AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "remote-submix"
        android.media.AudioDeviceInfo.TYPE_USB_DEVICE, android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY -> "usb"
        android.media.AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
        else -> "type$type"
    }

    // ------------------------------------------------------------------ music around the connection
    // Like Android Auto: music the phone starts as the car connects (Spotify on the car's Bluetooth)
    // must not play on the phone's speaker until the car takes the sound, and music stops when the
    // car goes instead of carrying on from the phone (user's report, 2026-11-08).

    /** Music this session paused because the car couldn't take the sound yet. */
    private var pausedUntilCarAudio = false
    @Volatile private var carAudioCapturing = false
    private var holdUntilMs = 0L

    /** Until the car's sound is ready (at most [HOLD_MUSIC_MS]), pause any music that starts. */
    private val holdMusic = object : Runnable {
        override fun run() {
            if (carAudioCapturing || android.os.SystemClock.elapsedRealtime() > holdUntilMs) return
            if (media.playback.value?.playing == true) {
                if (!pausedUntilCarAudio) DiagLog.add("car audio: music started before the car's sound was ready, holding it")
                pausedUntilCarAudio = true
                media.pause()
            }
            main.postDelayed(this, 250)
        }
    }

    private fun holdMusicUntilCarAudio() {
        if (!settings.carAudio) return
        holdUntilMs = android.os.SystemClock.elapsedRealtime() + HOLD_MUSIC_MS
        main.post(holdMusic)
    }

    /** The car takes the phone's sound now: music held at connect plays on. */
    private fun carAudioReady() {
        carAudioCapturing = true
        captureRestartPending = false
        unmuteMusicForCar()
        main.removeCallbacks(holdMusic)
        if (pausedUntilCarAudio) {
            pausedUntilCarAudio = false
            // A moment for the capture to settle, so the first notes go to the car.
            main.postDelayed({ if (media.playback.value?.playing != true) media.playPause() }, 500)
            DiagLog.add("car audio: ready, music resumes")
        }
    }

    /**
     * The phone's music volume taken down to zero mutes the music stream everywhere, the copy the
     * car gets included: the 2026-11-11 drive played silence to the car for three connections in a
     * row (the phone had been muted after a call), while YouTube, at its own volume, still went
     * through. While the car takes the sound the phone's speaker isn't used, so the music is
     * unmuted for the car (its volume there is the car's knob); it's muted again when the car goes.
     */
    private fun unmuteMusicForCar() {
        val am = context.getSystemService(android.media.AudioManager::class.java) ?: return
        if (!am.isStreamMute(android.media.AudioManager.STREAM_MUSIC)) return
        runCatching { am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_UNMUTE, 0) }
            .onSuccess {
                musicUnmutedForCar = true
                volumeAfterUnmute = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                DiagLog.add("car audio: the phone's music was muted, unmuted for the car")
            }
            .onFailure { DiagLog.add("car audio: the phone's music is muted and can't be unmuted (${it.message})") }
    }

    /** Whether [unmuteMusicForCar] unmuted the music, to mute it again when the car goes. */
    private var musicUnmutedForCar = false
    private var volumeAfterUnmute = 0

    private fun muteMusicAgain() {
        if (!musicUnmutedForCar) return
        musicUnmutedForCar = false
        val am = context.getSystemService(android.media.AudioManager::class.java) ?: return
        // Only as it was left: a volume the user set during the drive is theirs.
        if (am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) != volumeAfterUnmute) return
        runCatching { am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_MUTE, 0) }
    }

    /** The car is gone: music stops instead of carrying on from the phone's speaker. */
    private fun pauseMusicOnLeave() {
        main.removeCallbacks(holdMusic)
        muteMusicAgain()
        if (carAudioCapturing && media.playback.value?.playing == true) {
            media.pause()
            DiagLog.add("car audio: car gone, music paused")
        }
        carAudioCapturing = false
    }

    /** Music: full focus while it plays. */
    private val mediaFocusRequests = object : CarAudioOut.Focus {
        override fun request(playing: Boolean) {
            mediaPlaying = playing
            synchronized(focusLock) {
                if (playing) mediaFocusRetry.onRequested(SystemClock.uptimeMillis()) else mediaFocusRetry.onWithdrawn()
                scheduleFocusCheck()
                if (playing) {
                    aap.requestAudioFocus(AudioFocusRequestType.AUDIO_FOCUS_GAIN)
                    assumeFocusIfUnanswered(audioFocusAnswers)
                }
                // Comes right after the stream's STOP: keep it behind STOP and the last data.
                else if (!guidancePlaying) aap.requestAudioFocus(AudioFocusRequestType.AUDIO_FOCUS_RELEASE, Priority.AUDIO)
            }
        }
        override val granted: Boolean get() = mediaFocus
    }

    /**
     * The VW Play (Visteon MIB Regio) never answers an audio focus request, and the sound waited
     * for that answer forever: nothing played (2026-11-08). With no answer at all after a moment,
     * play as if granted. Any answer the car does send, a refusal included, still decides.
     */
    private fun assumeFocusIfUnanswered(answersBefore: Int) {
        main.postDelayed({
            if (audioFocusAnswers == answersBefore && mediaPlaying && !mediaFocus) {
                DiagLog.add("car audio: the car didn't answer the audio focus request, playing anyway")
                mediaFocus = true
            }
        }, AUDIO_FOCUS_ANSWER_MS)
    }

    /**
     * Without an answer, ask again: once the same way, like Android Auto (which asks again after
     * 500 ms, up to 3 times), then releasing first. Some head units (a Jancar one) drop the answer
     * to a request made before their session is ready, and never answer a request that doesn't
     * change their state.
     */
    private val mediaFocusRetry = FocusRetry()
    // Guards mediaFocusRetry: requests come from the capture thread, answers from the AAP reader, checks on main.
    private val focusLock = Any()
    private val focusCheck = Runnable { checkMediaFocus() }

    // The head unit normally answers the focus request sent with START. Some setups (Mazda with an
    // AAWireless dongle) sometimes never do, and the car screen stays black until the driver
    // switches to the car's own screen and back: ask again a few times while it has said nothing.
    private var videoFocusAsks = 0
    private val videoFocusCheck = object : Runnable {
        override fun run() {
            val ch = video ?: return
            if (closed || ch.focusHeard) return
            if (videoFocusAsks >= VIDEO_FOCUS_RETRIES) {
                Log.w(TAG, "video focus: the car never answered")
                return
            }
            videoFocusAsks++
            Log.i(TAG, "video focus: no answer from the car, asking again ($videoFocusAsks/$VIDEO_FOCUS_RETRIES)")
            ch.requestProjectedFocus()
            main.postDelayed(this, VIDEO_FOCUS_RETRY_MS)
        }
    }

    /** With focusLock held. Times are uptimeMillis, the handler's clock. */
    private fun scheduleFocusCheck() {
        main.removeCallbacks(focusCheck)
        mediaFocusRetry.nextCheckAtMs()?.let { main.postAtTime(focusCheck, it) }
    }

    private fun checkMediaFocus() {
        if (closed) return
        val action = synchronized(focusLock) {
            val now = SystemClock.uptimeMillis()
            // Kept from before: the request changed nothing, so the car may not answer it.
            if (mediaFocus) mediaFocusRetry.onAnswered()
            // Not over a prompt: when it ends, guidance asks for the music's focus again itself.
            val step = if (guidancePlaying) {
                mediaFocusRetry.postpone(now)
                FocusRetry.Action.None
            } else mediaFocusRetry.due(now)
            // Gains at the release's priority: the send queue is ordered by priority, and one at the
            // default could overtake a release still queued.
            when (step) {
                FocusRetry.Action.Ask -> aap.requestAudioFocus(AudioFocusRequestType.AUDIO_FOCUS_GAIN, Priority.AUDIO)
                FocusRetry.Action.Rearm -> {
                    aap.requestAudioFocus(AudioFocusRequestType.AUDIO_FOCUS_RELEASE, Priority.AUDIO)
                    aap.requestAudioFocus(AudioFocusRequestType.AUDIO_FOCUS_GAIN, Priority.AUDIO)
                }
                else -> {}
            }
            scheduleFocusCheck()
            step
        }
        if (action is FocusRetry.Action.GiveUp) {
            val line = "audio focus: the car never answered (asked ${action.asked} times)"
            Log.w(TAG, line)
            DiagLog.add(line)
        }
    }

    /** Navigation voice: transient focus that ducks the car's other sound, then back to the music if it plays. */
    private val guidanceFocus = object : CarAudioOut.Focus {
        override fun request(playing: Boolean) {
            guidanceAnswered = false
            guidancePlaying = playing
            aap.requestAudioFocus(when {
                playing -> AudioFocusRequestType.AUDIO_FOCUS_GAIN_TRANSIENT_MAY_DUCK
                mediaPlaying -> AudioFocusRequestType.AUDIO_FOCUS_GAIN
                else -> AudioFocusRequestType.AUDIO_FOCUS_RELEASE
            }, if (playing) Priority.CONTROL else Priority.AUDIO)
        }
        override val granted: Boolean get() = mediaFocus || guidanceFocusState || guidanceAnswered
    }

    // ------------------------------------------------------------------ microphone

    private val micListener = object : MicrophoneChannel.Listener {
        override fun onMicData(data: ByteArray, offset: Int, length: Int, timestampUs: Long) = micRouter.onCarMic(data, offset, length)
        override fun onMicOpened(ok: Boolean) = micRouter.onCarMicOpened(ok)
    }

    // ------------------------------------------------------------------ status to the head unit

    private var navBridge: NavStatusBridge? = null
    // Guarded by navLock: trips, phone guidance and onRunning come from different threads.
    private val navLock = Any()
    private var hostedGuiding = false
    private var phoneGuiding = false
    /**
     * A route started and the car wasn't asked for projected nav focus yet. Once per route: a
     * driver who switched to the car's own navigation mid-route isn't overridden at every update,
     * but the next route asks again.
     */
    private var navFocusPending = false

    /** A hosted app's trip or a phone app's guidance started, changed or ended. */
    private fun guidanceChanged(hosted: Boolean, guiding: Boolean) {
        synchronized(navLock) {
            val was = hostedGuiding || phoneGuiding
            if (hosted) hostedGuiding = guiding else phoneGuiding = guiding
            val now = hostedGuiding || phoneGuiding
            if (now && !was) navFocusPending = true
            else if (!now) navFocusPending = false
        }
        requestNavFocus()
    }

    private fun requestNavFocus() {
        synchronized(navLock) {
            if (!navFocusPending || aap.state != AapSession.State.RUNNING) return
            navFocusPending = false
        }
        aap.requestNavFocus(NavFocusType.NAV_FOCUS_PROJECTED)
    }

    private fun turnImage(m: androidx.car.app.navigation.model.Maneuver, w: Int, h: Int): ByteArray? {
        val icon = m.icon ?: return null
        val img = runtime.icons.load(icon, maxOf(w, h), runtime.ui.tripPackage) ?: return null
        val bmp = Bitmap.createScaledBitmap(img.asAndroidBitmapCompat(), w, h, true)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun png(bmp: Bitmap, w: Int, h: Int): ByteArray? = runCatching {
        val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
        ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }.getOrNull()

    private fun startPlaybackStatus() {
        val ch = playback ?: return
        scope.launch {
            var lastTrack: List<Any?>? = null
            var lastArt: Bitmap? = null
            media.playback.collectLatest { p ->
                if (p == null) return@collectLatest
                // Not just the title: the art (often loaded after the rest), the artist or the
                // duration can come in a later update of the same track. The art compares by
                // content: a platform session hands out a new Bitmap on every update, and the
                // JPEG must be made once per cover, not on every play, pause or seek.
                val track = listOf(p.title, p.artist, p.album, p.durationMs)
                if (track != lastTrack || !sameArt(lastArt, p.art)) {
                    lastTrack = track
                    lastArt = p.art
                    val meta = fillMediaMetadata(MediaPlaybackMetadata.newBuilder(), p)
                    p.art?.let { art ->
                        val small = Bitmap.createScaledBitmap(art, 256, 256 * art.height / maxOf(1, art.width), true)
                        meta.setAlbumArt(ByteString.copyFrom(ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()))
                    }
                    ch.metadata(meta.build())
                }
                ch.status(MediaPlaybackStatus.newBuilder()
                    .setState(if (p.playing) MediaPlaybackStatus.State.PLAYING else MediaPlaybackStatus.State.PAUSED)
                    .setMediaSource(runtime.ui.app(runtime.ui.activeMediaAppId)?.label ?: p.packageName)
                    .setPlaybackSeconds((p.positionMs / 1000).toInt().coerceAtLeast(0))
                    .build())
            }
        }
    }

    private fun sameArt(a: Bitmap?, b: Bitmap?): Boolean = a === b ||
        (a != null && b != null && a.width == b.width && a.height == b.height && runCatching { a.sameAs(b) }.getOrDefault(false))

    // ------------------------------------------------------------------ teardown

    /** The session ended (main thread): work posted before that and run after must not start anything. */
    private var closed = false

    private fun teardown() {
        closed = true
        net.prototux.aare.PerfLog.stop()
        runCatching { context.getSystemService(android.os.PowerManager::class.java)?.removeThermalStatusListener(thermalListener) }
        // Session ending while music fed the car: pause it, so it doesn't jump to the phone speaker
        // (and the phone's music muted again if this session unmuted it).
        pauseMusicOnLeave()
        net.prototux.aare.obd.ObdReader.stop(owner = this)
        if (settings.accessibilityInCarOnly) net.prototux.aare.mirror.AccessibilitySwitch.carGone(context)
        main.removeCallbacks(focusFallback)
        main.removeCallbacks(videoReport)
        main.removeCallbacks(silenceWatch)
        main.removeCallbacks(focusCheck)
        main.removeCallbacks(videoFocusCheck)
        net.prototux.aare.notify.CarNotificationListener.sink = null
        audioOuts.values.forEach { it.stop() }; audioOuts.clear()
        phoneBridge?.stop(); phoneBridge = null
        callSpeaker?.stop(); callSpeaker = null
        locationFeed.stop()
        drivingMode.stop()
        cluster?.stop()
        micRouter.shutdown()
        settings.unregisterListener(uiScaleListener)
        teardownVideo()
        runtime.stop()
        CarData.reset(owner = this)
        scope.cancel()
    }

    private fun deviceName(): String {
        val m = Build.MODEL
        return if (m.startsWith(Build.MANUFACTURER, ignoreCase = true)) m else "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} $m"
    }

    /**
     * The phone's own Bluetooth MAC for the car's pairing handshake, or null if unknown. Prefer the
     * user-set value; else try to read it (Android hides it from apps on most devices, returning
     * null or the 02:00:00:00:00:00 placeholder — then we skip the handshake rather than send junk).
     */
    private fun phoneBtAddress(): String? {
        val set = settings.phoneBluetoothAddress.trim()
        val fromSecure = runCatching {
            android.provider.Settings.Secure.getString(context.contentResolver, "bluetooth_address")
        }.getOrNull()
        val fromAdapter = runCatching {
            if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.address else null
        }.getOrNull()
        return listOf(set, fromSecure, fromAdapter).firstOrNull(::isValidBtMac)
    }

    private fun isValidBtMac(s: String?): Boolean =
        !s.isNullOrBlank() && s != "02:00:00:00:00:00" && s.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))

    private fun icon(size: Int): ByteArray? = runCatching {
        val d = context.packageManager.getApplicationIcon(context.packageName)
        val bmp = androidx.core.graphics.drawable.DrawableCompat.wrap(d).let {
            val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(b)
            it.setBounds(0, 0, size, size); it.draw(c); b
        }
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }.getOrNull()

    companion object {
        private const val TAG = "AareSession"
        private const val VIDEO_FOCUS_RETRY_MS = 3_000L
        /** The keys that start, stop or skip media, which [onKey] ignores until there is a car screen. */
        private val MEDIA_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        )
        private const val VIDEO_FOCUS_RETRIES = 3
        const val KEYCODE_MEDIA = 65537
        const val KEYCODE_NAVIGATION = 65538
        const val KEYCODE_RADIO = 65539
        const val KEYCODE_TEL = 65540
        const val KEYCODE_TURN_CARD = 65544
        /** A projected screen with no new frame for this long gets redrawn (see stallCheck). */
        private const val STALL_MS = 1_000L
        /** Ours: the stall line at most this often (see stallLoggedMs). */
        private const val STALL_LOG_EVERY_MS = 5 * 60_000L
        private const val STALL_CHECK_MS = 500L
        /** Encoder restarts allowed in [RESTART_WINDOW_MS], for this display and the cluster's. */
        internal const val MAX_RESTARTS = 3
        internal const val RESTART_WINDOW_MS = 10_000L

        /** The part of [c]'s resolution the car shows: the resolution less its declared margins. */
        fun visibleSize(c: VideoConfiguration): Pair<Int, Int> {
            val (w, h) = resolution(c.codecResolution)
            return (w - c.widthMargin.coerceIn(0, w)).coerceAtLeast(1) to (h - c.heightMargin.coerceIn(0, h)).coerceAtLeast(1)
        }

        fun resolution(r: VideoCodecResolutionType): Pair<Int, Int> = when (r) {
            VideoCodecResolutionType.VIDEO_800x480 -> 800 to 480
            VideoCodecResolutionType.VIDEO_1280x720 -> 1280 to 720
            VideoCodecResolutionType.VIDEO_1920x1080 -> 1920 to 1080
            VideoCodecResolutionType.VIDEO_2560x1440 -> 2560 to 1440
            VideoCodecResolutionType.VIDEO_3840x2160 -> 3840 to 2160
            VideoCodecResolutionType.VIDEO_720x1280 -> 720 to 1280
            VideoCodecResolutionType.VIDEO_1080x1920 -> 1080 to 1920
            VideoCodecResolutionType.VIDEO_1440x2560 -> 1440 to 2560
            VideoCodecResolutionType.VIDEO_2160x3840 -> 2160 to 3840
        }

        /**
         * Head units should always send a density; when one doesn't, keep the UI readable. By the
         * short side, so a 1080x1920 portrait screen gets the same density as a 1920x1080 one.
         */
        fun defaultDensity(shortSide: Int) = when {
            shortSide >= 1440 -> 320
            shortSide >= 1080 -> 240
            shortSide >= 720 -> 160
            else -> 140
        }

        /**
         * The density to draw with for an interface size in percent: a lower density fits more on
         * the same screen. Kept to 70-100%, so text and touch targets stay usable at a glance.
         */
        fun scaledDensity(density: Int, percent: Int): Int = density * percent.coerceIn(70, 100) / 100

        /** How long the car gets to answer the video focus request before we send video anyway. */
        private const val FOCUS_ANSWER_MS = 3_000L
        /** How long music is held at connect while the car's sound gets ready. */
        private const val HOLD_MUSIC_MS = 20_000L
        /** The music watch ([silenceWatch]): how often, how long silent before acting, and how many restarts at most. */
        private const val SILENCE_CHECK_MS = 2_000L
        private const val SILENT_MUSIC_MS = 12_000L
        private const val CAPTURE_RETRY_MS = 2_000L
        private const val CAPTURE_RESTART_GAP_MS = 30_000L
        private const val MAX_CAPTURE_RESTARTS = 3
        /** How long the car gets to answer an audio focus request before the sound plays anyway. */
        private const val AUDIO_FOCUS_ANSWER_MS = 1_000L
        private const val VIDEO_REPORT_MS = 5_000L
        /** A minute of reports: enough for the first seconds of a session, without flooding the log. */
        private const val VIDEO_REPORTS = 12
        private const val TOUCHES_TO_LOG = 10

        private fun describeVideo(c: VideoConfiguration): String {
            val (w, h) = resolution(c.codecResolution)
            val margins = if (c.widthMargin + c.heightMargin > 0) " margins ${c.widthMargin}x${c.heightMargin}" else ""
            val rate = if (c.hasFrameRate()) "" else " (fps not stated)"
            val codec = if (c.hasVideoCodecType()) " ${c.videoCodecType.name.removePrefix("MEDIA_CODEC_VIDEO_")}" else ""
            return "${w}x$h@${fpsOf(c)}$rate ${c.density}dpi$margins$codec"
        }

        /** A car that doesn't state a frame rate gets 30: protobuf would read the missing field as the enum's first value, 60. */
        fun fpsOf(c: VideoConfiguration): Int = if (c.hasFrameRate() && c.frameRate == VideoFrameRateType.VIDEO_FPS_60) 60 else 30

        /**
         * The H.264 profile and level the encoder really produced (from its SPS), which is what a car's
         * decoder may refuse, plus the first bytes as hex.
         */
        fun describeCodecConfig(data: ByteArray, offset: Int, length: Int): String {
            val end = offset + length
            fun hex(b: Byte) = "%02x".format(b.toInt() and 0xFF)
            val head = (offset until minOf(end, offset + 32)).joinToString("") { hex(data[it]) }
            val sps = (offset until end - 6).firstOrNull { i ->
                data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1 && data[i + 3].toInt() and 0x1F == 7
            } ?: return "$length bytes, no SPS: $head"
            val profile = data[sps + 4].toInt() and 0xFF
            val level = data[sps + 6].toInt() and 0xFF
            return "$length bytes, H.264 profile $profile constraints ${hex(data[sps + 5])} level $level: $head"
        }

        /**
         * Picks the best configuration the phone can encode comfortably: the largest one within
         * [maxHeight] (short side), preferring 60 fps when asked, among the head unit's preferred
         * indices when it gave any.
         *
         * Sizes are what the car shows: the configuration's resolution less its margins. A wide
         * screen can offer 1920x1080 with 360 rows of margin for a 1920x720 picture, which is
         * within a 720 limit, while its 1280x720 configuration gets stretched across that width.
         */
        fun chooseVideo(configs: List<VideoConfiguration>, preferred: List<Int>, maxHeight: Int, prefer60: Boolean, codec: MediaCodecType): Int {
            fun portrait(i: Int) = resolution(configs[i].codecResolution).let { it.second > it.first }
            // A screen has one shape: when a head unit offers both, the shape of its first preferred
            // configuration is the screen's (the other one would be letterboxed or squeezed).
            val candidates = videoCandidates(configs, preferred, codec, TAG).let { all ->
                val shape = portrait(preferred.firstOrNull { it in all } ?: all.firstOrNull() ?: return 0)
                all.filter { portrait(it) == shape }
            }
            fun shortSide(i: Int) = visibleSize(configs[i]).let { minOf(it.first, it.second) }
            fun score(i: Int): Long {
                val c = configs[i]
                val s = shortSide(i)
                val fits = s <= maxHeight
                val fps60 = fpsOf(c) == 60
                // Same short side: the wider visible picture is the car's real shape (the other gets stretched).
                val long = visibleSize(c).let { maxOf(it.first, it.second) }
                return (if (fits) 1_000_000_000L else 0L) + (if (fits) s else -s) * 100_000L + long * 10L + (if (fps60 == prefer60) 1 else 0)
            }
            return candidates.maxByOrNull(::score) ?: 0
        }

        /**
         * The configurations worth choosing from: the head unit's preferred ones, and only those in
         * the matched [codec] (a config with no codec given means H.264). With none in that codec at
         * all, the old choice anyway: better a try than no video.
         */
        fun videoCandidates(configs: List<VideoConfiguration>, preferred: List<Int>, codec: MediaCodecType, tag: String): List<Int> {
            fun matches(i: Int) = configCodec(configs[i]) == codec
            val all = configs.indices.filter { preferred.isEmpty() || it in preferred }.ifEmpty { configs.indices.toList() }
            all.filter(::matches).ifEmpty { configs.indices.filter(::matches) }.let { if (it.isNotEmpty()) return it }
            Log.w(tag, "no ${codec.name.removePrefix("MEDIA_CODEC_VIDEO_")} video configuration offered (${configs.map { it.videoCodecType }}), trying one anyway")
            return all
        }

        /** A configuration with no codec set means H.264 (proto default / older head units). */
        fun configCodec(c: VideoConfiguration): MediaCodecType =
            if (c.hasVideoCodecType()) c.videoCodecType else MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP

        /** The head unit's codec type for one of the kinds the phone can hardware-encode. */
        fun videoType(k: VideoCodecKind): MediaCodecType = when (k) {
            VideoCodecKind.H264 -> MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
            VideoCodecKind.H265 -> MediaCodecType.MEDIA_CODEC_VIDEO_H265
            VideoCodecKind.VP9 -> MediaCodecType.MEDIA_CODEC_VIDEO_VP9
            VideoCodecKind.AV1 -> MediaCodecType.MEDIA_CODEC_VIDEO_AV1
        }

        /**
         * The best codec both sides can do: the most efficient one the head unit advertises and the
         * phone can hardware-encode. Falls back to H.264 (universal). [forceH264] pins H.264 for head
         * units that advertise a newer codec but decode it poorly. [hwEncodes] is the phone's side.
         */
        fun matchVideoCodec(advertised: Set<MediaCodecType>, forceH264: Boolean, hwEncodes: (VideoCodecKind) -> Boolean): VideoCodecKind {
            if (forceH264) return VideoCodecKind.H264
            // Best -> worst by compression efficiency; the first both sides support wins.
            for (kind in listOf(VideoCodecKind.AV1, VideoCodecKind.H265, VideoCodecKind.VP9, VideoCodecKind.H264)) {
                if (videoType(kind) in advertised && hwEncodes(kind)) return kind
            }
            return VideoCodecKind.H264
        }
    }
}

private fun androidx.compose.ui.graphics.ImageBitmap.asAndroidBitmapCompat(): Bitmap = asAndroidBitmap()
