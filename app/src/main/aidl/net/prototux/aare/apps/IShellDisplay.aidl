package net.prototux.aare.apps;

import android.view.MotionEvent;
import android.view.Surface;

/**
 * The phone-apps helper Shizuku runs with adb-level rights (ShellDisplayService): a virtual
 * display the size of the car screen that any phone app can be opened on, without showing on
 * the phone's own screen, and touches and keys sent to it. The same thing scrcpy's
 * --new-display does.
 */
interface IShellDisplay {
    /** Creates the display, drawing into [surface]. Returns its display id, or -1. */
    int create(in Surface surface, int width, int height, int densityDpi) = 1;

    /** A new surface (or none, while the car shows something else: apps keep running). */
    void setSurface(in Surface surface) = 2;

    void resize(int width, int height, int densityDpi) = 3;

    /** Opens [component] ("package/class") on the display. Returns whether it started. */
    boolean launch(String component) = 4;

    /** A touch on the display, in its own pixels. */
    void touch(in MotionEvent event) = 5;

    /** A key (back) pressed on the display. */
    void key(int keyCode) = 6;

    /** The package whose window is in front on the display, or null. */
    String topPackage() = 7;

    void release() = 8;

    /** Shizuku's user-service teardown call. */
    void destroy() = 16777114;
}
