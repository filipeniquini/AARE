package net.prototux.aare.apps;

import android.view.MotionEvent;
import android.view.Surface;

/**
 * The phone-apps helper Shizuku runs with adb-level rights (ShellDisplayService): virtual
 * displays the size of the car screen that any phone app can be opened on, without showing on
 * the phone's own screen, and touches and keys sent to them. The same thing scrcpy's
 * --new-display does. One helper process holds them all, each in its [slot] (0: apps full
 * screen, 1: the home screen's map card): one process instead of one per display saves its
 * memory (about 100 MB, 2026-11-09).
 */
interface IShellDisplay {
    /** Creates the slot's display, drawing into [surface]. Returns its display id, or -1. */
    int create(int slot, in Surface surface, int width, int height, int densityDpi) = 1;

    /** A new surface (or none, while the car shows something else: apps keep running). */
    void setSurface(int slot, in Surface surface) = 2;

    void resize(int slot, int width, int height, int densityDpi) = 3;

    /** Opens [component] ("package/class") on the slot's display. Returns whether it started. */
    boolean launch(int slot, String component) = 4;

    /** A touch on the slot's display, in its own pixels. */
    void touch(int slot, in MotionEvent event) = 5;

    /** A key (back) pressed on the slot's display. */
    void key(int slot, int keyCode) = 6;

    /** The package whose window is in front on the slot's display, or null. */
    String topPackage(int slot) = 7;

    /** Closes the slot's display (its apps close too). */
    void release(int slot) = 8;

    /**
     * Whether [packageName]'s window on the slot's display hides the system bars (a video full
     * screen): the car then hides its own bar too.
     */
    boolean hidesBars(int slot, String packageName) = 9;

    /**
     * Before the slot's display closes: the apps on it are closed and taken off the phone's
     * Recents screen, so nothing the car opened lingers there. Returns how many were.
     */
    int closeTasks(int slot) = 10;

    /**
     * A call screen (the dialer's, WhatsApp's...) that opened on the slot's display, which has the
     * focus, goes to the phone's own screen; the app under it is in front again. Returns the
     * packages moved, or null when none was.
     */
    String moveCallsAway(int slot) = 11;

    /** Shizuku's user-service teardown call. */
    /**
     * Whether an app on the slot's display asked for the keyboard (the phone's own keyboard may
     * not show there: with the phone locked Android keeps it hidden).
     */
    boolean imeShown(int slot) = 12;

    /** Types [text] into what has the focus on the slot's display, as key presses. */
    void typeText(int slot, String text) = 13;

    void destroy() = 16777114;
}
