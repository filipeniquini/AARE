package net.prototux.aare.audio;

/** The audio capture helper Shizuku runs with adb-level rights (ShellAudioService). */
interface IShellAudio {
    /**
     * Starts capturing everything the phone plays (which then stops playing on the phone).
     * Returns the read end of a pipe of 16-bit little-endian PCM, or null if capture failed.
     */
    ParcelFileDescriptor start(int sampleRate, int channels) = 1;

    void stop() = 2;

    /**
     * Enables or disables (for this user) Google's Android Auto app, which otherwise answers the
     * car before AARE. Only that one package. Returns whether pm succeeded.
     */
    boolean setAndroidAutoEnabled(boolean enabled) = 3;

    /**
     * Adds or removes one of AARE's own accessibility services ("package/class") in the enabled
     * list, leaving every other app's service as it is. Returns whether settings succeeded.
     */
    boolean setOwnAccessibilityService(String component, boolean enabled) = 4;

    /** The phone's own Bluetooth address (apps only get a placeholder), or null. */
    String bluetoothAddress() = 5;

    /**
     * Ends an app's process if it is in the background (am kill: no force stop, the app keeps
     * its notifications and alarms). For the car apps AARE hosted. Returns whether am succeeded.
     */
    boolean killBackgroundApp(String packageName) = 6;

    /** Shizuku's user-service teardown call. */
    void destroy() = 16777114;
}
