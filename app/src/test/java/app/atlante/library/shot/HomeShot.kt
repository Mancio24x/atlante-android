package app.atlante.library.shot
import android.graphics.Bitmap
import android.graphics.Canvas
import app.atlante.library.MainActivity
import app.atlante.library.model.Book
import app.atlante.library.model.ReadingJournal
import app.atlante.library.storage.LibraryStore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.FileOutputStream

/** Draws pages to PNG files when SHOT_DIR is set (a visual check without an emulator); does nothing otherwise. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], qualifiers="w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeShot {
    private fun shot(a: MainActivity, out: String, name: String) {
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val v = a.window.decorView; val bm = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(bm))
        FileOutputStream("$out/$name.png").use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun pages() {
        val out = System.getenv("SHOT_DIR") ?: return
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = c.get()
        val store = MainActivity::class.java.getDeclaredField("store").apply { isAccessible = true }.get(a) as LibraryStore
        val b = Book().apply { id = "h".repeat(64); title = "Il nome della rosa"; author = "Umberto Eco"; format = "CATALOGO"; totalPages = 503; currentPage = 214; status = Book.READING }
        ReadingJournal.session(b, 196, 214, 24 * 60, java.time.LocalDate.now())
        store.addCatalog(b)
        MainActivity::class.java.getDeclaredMethod("showCompanion").apply { isAccessible = true }.invoke(a)
        shot(a, out, "home")
        MainActivity::class.java.getDeclaredField("destination").apply { isAccessible = true }.set(a, "Biblioteca")
        MainActivity::class.java.getDeclaredField("shelfView").apply { isAccessible = true }.setBoolean(a, true)
        MainActivity::class.java.getDeclaredMethod("showLibrary").apply { isAccessible = true }.invoke(a)
        shot(a, out, "shelf")
        MainActivity::class.java.getDeclaredField("shelfView").apply { isAccessible = true }.setBoolean(a, false)
        for (i in 1..14) store.addCatalog(Book().apply { id = ('a' + i).toString().repeat(64); title = "Libro $i"; author = "Autore $i"; format = "CATALOGO"; totalPages = 300; currentPage = i * 20
            status = listOf(Book.TO_READ, Book.READING, Book.PAUSED, Book.READ)[i % 4]; if (i % 3 == 0) { series = "Saga"; number = i / 3.0 } })
        MainActivity::class.java.getDeclaredMethod("showLibrary").apply { isAccessible = true }.invoke(a)
        a.window.decorView.measure(android.view.View.MeasureSpec.makeMeasureSpec(a.window.decorView.width, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(a.window.decorView.height, android.view.View.MeasureSpec.EXACTLY))
        a.window.decorView.layout(0, 0, a.window.decorView.width, a.window.decorView.height)
        val grid = MainActivity::class.java.getDeclaredField("grid").apply { isAccessible = true }.get(a) as androidx.recyclerview.widget.RecyclerView
        grid.scrollBy(0, 2000); grid.scrollBy(0, -2000)                                     // recycled cells are refreshed, not rebuilt
        shot(a, out, "grid")
        MainActivity::class.java.getDeclaredField("listView").apply { isAccessible = true }.setBoolean(a, true)
        MainActivity::class.java.getDeclaredMethod("showLibrary").apply { isAccessible = true }.invoke(a); shot(a, out, "list")
        MainActivity::class.java.getDeclaredField("listView").apply { isAccessible = true }.setBoolean(a, false)
        MainActivity::class.java.getDeclaredMethod("navigate", String::class.java).apply { isAccessible = true }.invoke(a, "Impostazioni"); shot(a, out, "impostazioni")
        val section = MainActivity::class.java.getDeclaredField("settingsSection").apply { isAccessible = true }
        for (page in listOf("lettura", "lingua", "obiettivi")) { section.set(a, page); MainActivity::class.java.getDeclaredMethod("showCompanion").apply { isAccessible = true }.invoke(a); shot(a, out, "impostazioni-$page") }
        section.set(a, "")
        for (page in listOf("Sync", "Statistiche", "Scopri", "CrossPoint")) {
            MainActivity::class.java.getDeclaredMethod("navigate", String::class.java).apply { isAccessible = true }.invoke(a, page)
            shot(a, out, page.lowercase())
        }
        c.pause().stop().destroy()
    }
}
