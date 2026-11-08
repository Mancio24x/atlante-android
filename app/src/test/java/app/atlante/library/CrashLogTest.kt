package app.atlante.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.atlante.library.diagnostics.CrashLog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A crash leaves a report on the phone (error, causes, last actions); errors that did not close the app are logged too. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashLogTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun anUncaughtCrashIsWrittenAndReportedOnce() {
        CrashLog.clear(context)
        // The handler chain: ours writes the report, then the previous one runs (here a stand-in that only records).
        var previousRan = false
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> previousRan = true }
        CrashLog::class.java.getDeclaredField("installed").apply { isAccessible = true }.setBoolean(CrashLog, false)
        CrashLog.install(context)
        CrashLog.step("pagina: Biblioteca"); CrashLog.step("avviata: library")
        val boom = IllegalStateException("Libro non disponibile", RuntimeException("causa interna"))
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)
        Thread.setDefaultUncaughtExceptionHandler(previous)
        assertTrue(previousRan)
        val report = CrashLog.crashes(context).single().readText()
        assertTrue(report, report.contains("IllegalStateException: Libro non disponibile"))
        assertTrue(report, report.contains("Caused by: java.lang.RuntimeException: causa interna"))
        assertTrue(report, report.contains("pagina: Biblioteca") && report.contains("avviata: library"))
        assertTrue(report, report.contains("Android:"))
        // At the next start: said once, not again.
        assertEquals(1, CrashLog.exportNew(context))
        assertEquals(0, CrashLog.exportNew(context))
    }

    @Test fun errorsThatDidNotCloseTheAppAreLogged() {
        CrashLog.clear(context)
        CrashLog.error(context, "Riparazione: Ripara duplicati", java.io.IOException("Hardcover ha risposto con codice 500."))
        val log = CrashLog.reports(context).single { it.name == "errors.txt" }.readText()
        assertTrue(log, log.contains("Riparazione: Ripara duplicati") && log.contains("IOException: Hardcover ha risposto con codice 500."))
        assertEquals(0, CrashLog.exportNew(context))      // no crash: nothing to announce
    }
}
