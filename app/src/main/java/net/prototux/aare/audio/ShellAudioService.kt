package net.prototux.aare.audio

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.FileOutputStream
import kotlin.system.exitProcess

/**
 * Runs in a separate process that Shizuku starts with adb (shell) rights, which may record the
 * "remote submix": everything the phone plays, navigation voice included. While it is recorded
 * the phone itself goes quiet, as it does with Android Auto. This is how scrcpy captures audio.
 *
 * Android 12+ only (the recording must be attributed to the shell, which needs AudioRecord's
 * setContext).
 */
class ShellAudioService : IShellAudio.Stub {
    private val context: Context?
    @Volatile private var record: AudioRecord? = null
    private var pump: Thread? = null

    constructor() : this(null)

    /** Shizuku passes a Context when the service has this constructor. */
    constructor(context: Context?) {
        this.context = context
    }

    // The shell holds RECORD_AUDIO and CAPTURE_AUDIO_OUTPUT; REMOTE_SUBMIX isn't in AudioRecord's public list.
    @SuppressLint("MissingPermission", "WrongConstant")
    override fun start(sampleRate: Int, channels: Int): ParcelFileDescriptor? {
        stop()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return try {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO)
                .build()
            val chunk = sampleRate / 50 * channels * 2
            val r = AudioRecord.Builder()
                .setContext(ShellContext(context ?: systemContext()))
                .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(chunk * 8, AudioRecord.getMinBufferSize(sampleRate, format.channelMask, format.encoding)))
                .build()
            r.startRecording()
            record = r
            val (read, write) = ParcelFileDescriptor.createPipe()
            pump = Thread({
                val buf = ByteArray(chunk)
                FileOutputStream(write.fileDescriptor).use { out ->
                    try {
                        while (record === r) {
                            val n = r.read(buf, 0, buf.size)
                            if (n < 0) break
                            if (n > 0) out.write(buf, 0, n)
                        }
                    } catch (_: Exception) {
                        // AARE closed its end of the pipe
                    }
                }
                write.close()
            }, "aare-shell-audio").apply { start() }
            read
        } catch (e: Exception) {
            Log.w(TAG, "remote submix capture failed", e)
            stop()
            null
        }
    }

    override fun stop() {
        val r = record ?: return
        record = null
        runCatching { r.stop() }
        pump?.join(500)
        pump = null
        r.release()
    }

    override fun setAndroidAutoEnabled(enabled: Boolean): Boolean = try {
        // Fixed arguments, one fixed package: this process has adb rights.
        val command = if (enabled) listOf("pm", "enable", "--user", "current", ANDROID_AUTO_PACKAGE)
        else listOf("pm", "disable-user", "--user", "current", ANDROID_AUTO_PACKAGE)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val ok = process.waitFor() == 0
        if (!ok) Log.w(TAG, "pm failed: $output")
        ok
    } catch (e: Exception) {
        Log.w(TAG, "pm failed", e)
        false
    }

    override fun setOwnAccessibilityService(component: String, enabled: Boolean): Boolean = try {
        // Only AARE's own services: anything else is refused, whatever the caller asks.
        require(component.startsWith("$AARE_PACKAGE/")) { "not one of AARE's services" }
        val key = "enabled_accessibility_services"
        val current = run("settings", "get", "secure", key)?.trim().orEmpty()
        val list = current.split(':').filter { it.isNotBlank() && it != "null" }.toMutableList()
        // Short and long forms name the same service.
        val forms = setOf(component, component.replace("/.", "/$AARE_PACKAGE."))
        list.removeAll { it in forms }
        if (enabled) list += component
        val written = if (list.isEmpty()) run("settings", "delete", "secure", key) else run("settings", "put", "secure", key, list.joinToString(":"))
        val ok = written != null &&
            (!enabled || run("settings", "put", "secure", "accessibility_enabled", "1") != null)
        ok
    } catch (e: Exception) {
        Log.w(TAG, "accessibility switch failed", e)
        false
    }

    override fun bluetoothAddress(): String? = runCatching {
        run("settings", "get", "secure", "bluetooth_address")?.trim()?.takeIf { Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}").matches(it) }
    }.getOrNull()

    /** Runs a fixed program with separate arguments (no shell); its output, or null if it failed. */
    private fun run(vararg command: String): String? {
        val p = ProcessBuilder(*command).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return if (p.waitFor() == 0) out else { Log.w(TAG, "${command.first()} failed: $out"); null }
    }

    override fun destroy() {
        stop()
        exitProcess(0)
    }

    /** The system context of this (app_process) process. */
    @SuppressLint("PrivateApi")
    private fun systemContext(): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) HiddenApiBypass.addHiddenApiExemptions("Landroid/app/ActivityThread;")
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null) ?: at.getMethod("systemMain").invoke(null)
        return at.getMethod("getSystemContext").invoke(thread) as Context
    }

    /** A context that says "the shell", so the recording is attributed to the uid running it. */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = SHELL_PACKAGE
        override fun getOpPackageName() = SHELL_PACKAGE
        override fun getApplicationContext(): Context = this
        @RequiresApi(Build.VERSION_CODES.S) // only used from Android 12, see start()
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build()
    }

    private companion object {
        const val TAG = "AareShellAudio"
        const val SHELL_PACKAGE = "com.android.shell"
        const val ANDROID_AUTO_PACKAGE = AndroidAutoSwitch.PACKAGE
        const val AARE_PACKAGE = "net.prototux.aare"
    }
}
