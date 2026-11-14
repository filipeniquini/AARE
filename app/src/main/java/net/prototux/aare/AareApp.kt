package net.prototux.aare

import android.app.Application

class AareApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        DiagLog.init(this)
        // A crash's stack trace into the trip's logs before the process dies.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> TripLog.crash(t, e); previous?.uncaughtException(t, e) }
        // AARE was gone while the car had the always-on display off or apps paused (a crash, an
        // update): undone now. A session starting now does it again after this (same worker, in order).
        net.prototux.aare.host.PhoneCleanup.atDisconnect(this, Settings(this))
    }

    companion object {
        lateinit var instance: AareApp
            private set
    }
}
