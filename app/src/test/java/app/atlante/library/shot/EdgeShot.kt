package app.atlante.library.shot
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
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

/** Pages in edge cases (theme, empty library, long titles), drawn to PNG when SHOT_DIR is set. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], qualifiers="w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EdgeShot {
    private fun shot(a: MainActivity, out: String, name: String) {
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val v = a.window.decorView; val bm = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(bm))
        FileOutputStream("$out/$name.png").use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun go(a: MainActivity, page: String) = MainActivity::class.java.getDeclaredMethod("navigate", String::class.java).apply { isAccessible = true }.invoke(a, page)
    private fun run(theme: String, books: Int, prefix: String) {
        val out = System.getenv("SHOT_DIR") ?: return
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().putString("theme", theme).commit()
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = c.get()
        val store = MainActivity::class.java.getDeclaredField("store").apply { isAccessible = true }.get(a) as LibraryStore
        if (books == 0) store.snapshot().forEach { store.remove(it) }
        for (i in 1..books) store.addCatalog(Book().apply { id = "%064x".format(i); title = if (i % 2 == 0) "Un titolo molto lungo che non entra in una riga sola numero $i" else "Libro $i"
            author = "Autrice con un nome lunghissimo $i"; format = "CATALOGO"; totalPages = 400; currentPage = 40 * i % 400
            status = listOf(Book.READING, Book.READ, Book.PAUSED, Book.TO_READ)[i % 4]; if (i % 3 == 0) { series = "Una saga dal nome lungo"; number = i / 3.0 }
            if (status == Book.READING) ReadingJournal.session(this, 0, currentPage, 1800, java.time.LocalDate.now()) })
        for (page in listOf("Home", "Biblioteca", "Statistiche", "Sync")) { go(a, page); shot(a, out, "$prefix-${page.lowercase()}") }
        c.pause().stop().destroy()
    }
    @Test fun atlanteLongTitles() = run("atlante", 9, "atlante")
    @Test fun amoledDark() = run("amoled", 9, "amoled")
    @Test fun emptyLibrary() = run("atlante", 0, "vuota")
}
