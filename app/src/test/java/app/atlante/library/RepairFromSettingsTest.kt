package app.atlante.library
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.atlante.library.catalog.RepairWorker
import app.atlante.library.model.Book
import app.atlante.library.storage.LibraryStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Ripara saghe started from Impostazioni › Libreria e catalogo: the app stays on its page and does not close. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class RepairFromSettingsTest {
    @Test fun repairFromTheLibraryPageKeepsTheApp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = c.get()
        val store = MainActivity::class.java.getDeclaredField("store").apply { isAccessible = true }.get(a) as LibraryStore
        store.addCatalog(Book().apply { id = "f".repeat(64); title = "Red Rising"; author = "Pierce Brown"; format = "CATALOGO"; status = Book.READ })
        MainActivity::class.java.getDeclaredMethod("navigate", String::class.java).apply { isAccessible = true }.invoke(a, "Impostazioni")
        MainActivity::class.java.getDeclaredField("settingsSection").apply { isAccessible = true }.set(a, "libreria")
        MainActivity::class.java.getDeclaredMethod("showCompanion").apply { isAccessible = true }.invoke(a)
        MainActivity::class.java.getDeclaredMethod("startRepair", String::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(a, RepairWorker.SAGAS, false)
        repeat(20) { ShadowLooper.idleMainLooper() }
        assertFalse(a.isFinishing)
        assertEquals("Impostazioni", MainActivity::class.java.getDeclaredField("destination").apply { isAccessible = true }.get(a))
        c.pause().stop().destroy()
    }
}
