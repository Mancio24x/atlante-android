package app.atlante.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.atlante.library.diagnostics.CrashLog
import app.atlante.library.diagnostics.ReportUploader
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** Reports go to the project only with consent, once each, without personal data; a refused token stops, a server error retries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReportUploaderTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val posted = ArrayList<Pair<String, String>>()

    @Before fun clean() {
        CrashLog.clear(context); java.io.File(CrashLog.dir(context), ".sent").delete()
        context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE).edit().clear().commit()
        CrashLog.step("pagina: Biblioteca")
        CrashLog.writeCrash(context, Thread.currentThread(), IllegalStateException("«Il trono di spade» non trovato per @lettrice"))
        CrashLog.error(context, "Sincronizzazione con Hardcover", IOException("Bearer abc.DEF-123 rifiutato; password=segreta; scrivi a mario.rossi@example.com; libro " + "a".repeat(64)))
    }

    @Test fun nothingWithoutConsent() {
        assertEquals(0, ReportUploader.upload(context) { t, b -> posted += t to b; 201 })
        ReportUploader.setConsent(context, false)
        assertEquals(0, ReportUploader.upload(context) { t, b -> posted += t to b; 201 })
        assertTrue(posted.isEmpty())
    }

    @Test fun eachReportOnceWithoutPersonalData() {
        context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE).edit().putBoolean("send", true).commit()
        assertEquals(2, ReportUploader.upload(context) { t, b -> posted += t to b; 201 })
        assertTrue(posted[0].first, posted[0].first.startsWith("Crash ") && posted[0].first.contains("IllegalStateException"))
        assertTrue(posted[1].first, posted[1].first.startsWith("Errori "))
        val all = posted.joinToString("\n") { it.first + "\n" + it.second }
        for (secret in listOf("Il trono di spade", "lettrice", "abc.DEF-123", "segreta", "mario.rossi", "a".repeat(64))) assertFalse(secret, all.contains(secret))
        assertTrue(all.contains("Telefono: `" + ReportUploader.phone(context) + "`"))
        // Nothing new: nothing sent again.
        assertEquals(0, ReportUploader.upload(context) { t, b -> posted += t to b; 201 })
        assertEquals(2, posted.size)
    }

    @Test fun refusedTokenStopsAndServerErrorRetries() {
        context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE).edit().putBoolean("send", true).commit()
        assertEquals(0, ReportUploader.upload(context) { _, _ -> 401 })                // refused: stop, nothing marked as sent
        try { ReportUploader.upload(context) { _, _ -> 502 }; fail("a server error must be retried") } catch (e: IOException) { }
        assertEquals(2, ReportUploader.upload(context) { t, b -> posted += t to b; 201 }) // later it works: both go
    }
}
