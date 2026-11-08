package app.atlante.library

import android.app.Application
import app.atlante.library.diagnostics.CrashLog

/** Starts the crash recorder before anything else: screens, widgets and background work are all covered. */
class AtlanteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
