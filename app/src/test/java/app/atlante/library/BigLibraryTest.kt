package app.atlante.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.atlante.library.model.Book
import app.atlante.library.storage.LibraryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A library that grew past 16 MB (thousands of books with plots, notes, sessions) still opens: it saved, so it loads. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BigLibraryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun aLibraryBiggerThan16MegabytesOpensAgain() {
        File(context.filesDir, "library.json").delete(); File(context.filesDir, "books").deleteRecursively()
        val store = LibraryStore(context)
        val plot = "Una trama lunga, come quelle che arrivano da Wikipedia. ".repeat(140)      // about 8 KB
        val many = (0 until 2600).map { i -> Book().apply { id = "%064x".format(i); title = "Libro $i"; author = "Autore $i"; format = "CATALOGO"; description = plot } }
        // One write for all of them (the app grows to this size over time, a book at a time).
        val books = LibraryStore::class.java.getDeclaredField("books").apply { isAccessible = true }.get(store) as MutableList<Book>
        books.addAll(many); store.save()
        assertTrue("library.json: " + File(context.filesDir, "library.json").length(), File(context.filesDir, "library.json").length() > 16L * 1024 * 1024)
        val reopened = LibraryStore(context)
        assertEquals(2600, reopened.snapshot().size)
    }
}
