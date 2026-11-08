package app.atlante.library

import android.app.Application
import app.atlante.library.diagnostics.CrashLog

/** Starts the crash recorder before anything else: screens, widgets and background work are all covered. */
class AtlanteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // Reports to the project's private repository, only with your consent (asked once by the app).
        runCatching { app.atlante.library.diagnostics.ReportUploader.schedule(this) }
    }
}
