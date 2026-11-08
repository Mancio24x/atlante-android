package app.atlante.library.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.atlante.library.model.Book
import app.atlante.library.storage.LibraryStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A real Hardcover library: hundreds of copies made by Sync, every Hardcover book with dozens of editions. Finding
 * which copy is which book of yours must take seconds (it runs at every sync and before "Ripara duplicati").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HardcoverDuplicatesScaleTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun hundredsOfCopiesWithManyEditionsInSeconds() {
        File(context.filesDir, "library.json").delete(); File(context.filesDir, "books").deleteRecursively()
        val store = LibraryStore.shared(context)
        for (b in store.snapshot()) store.remove(b)
        val remote = ArrayList<JSONObject>()
        val n = 400
        for (i in 0 until n) {
            val author = "Autore$i Cognome$i"
            // Yours: the Italian edition with a session (not a bare sync sheet).
            store.addCatalog(Book().apply { id = "%064x".format(i); title = "Titolo italiano numero $i"; this.author = author; format = "CATALOGO"; notes = "mia nota" })
            // Sync's copy: the English record, linked to Hardcover user book i.
            val copy = Book().apply { id = "%064x".format(10000 + i); title = "English title number $i"; this.author = author; format = "CATALOGO"; addedAt = System.currentTimeMillis() + i }
            SyncCore.remember(copy, HardcoverSync.SERVICE, JSONObject().put("id", i + 1).put("book", 5000 + i), SyncCore.Snapshot.of(copy))
            store.addCatalog(copy)
            val eds = JSONArray().put(JSONObject().put("title", "Titolo italiano numero $i").put("isbn_13", JSONObject.NULL).put("isbn_10", JSONObject.NULL))
            for (e in 0 until 40) eds.put(JSONObject().put("title", "Edition $e of English title number $i").put("isbn_13", JSONObject.NULL).put("isbn_10", JSONObject.NULL))
            remote += JSONObject().put("id", i + 1).put("book_id", 5000 + i)
                .put("book", JSONObject().put("title", "English title number $i").put("contributions", JSONArray().put(JSONObject().put("author", JSONObject().put("name", author)))).put("editions", eds))
        }
        for (i in 0 until 800) store.addCatalog(Book().apply { id = "%064x".format(20000 + i); title = "Altro libro $i"; author = "Scrittore$i Altro$i"; format = "CATALOGO" })
        val api = HardcoverSync.Api { _, _ -> JSONObject().put("editions", JSONArray()) }
        val start = System.currentTimeMillis()
        val found = HardcoverSync.duplicates(store, HardcoverSync.Settings(), { }, api, remote)
        val took = System.currentTimeMillis() - start
        println("hardcover duplicates: $took ms, ${found.size} found")
        assertEquals(n, found.size)
        assertTrue("took $took ms", took < 20_000)
    }

    /** Sync's own matching (every book of yours against every Hardcover record and its editions): seconds too. */
    @Test fun matchingAThousandBooksInSeconds() {
        val remote = ArrayList<JSONObject>()
        for (i in 0 until 800) {
            val eds = JSONArray(); for (e in 0 until 40) eds.put(JSONObject().put("title", "Edizione $e del libro $i").put("isbn_13", JSONObject.NULL).put("isbn_10", JSONObject.NULL))
            remote += JSONObject().put("id", i + 1).put("book_id", 5000 + i)
                .put("book", JSONObject().put("title", "Book number $i").put("contributions", JSONArray().put(JSONObject().put("author", JSONObject().put("name", "Nome$i Cognome$i")))).put("editions", eds))
        }
        val locals = (0 until 1500).map { i -> Book().apply { id = "%064x".format(i); title = if (i < 800) "Book number $i" else "Mio libro $i"; author = if (i < 800) "Nome$i Cognome$i" else "Altro$i Autore$i"; format = "CATALOGO" } }
        val start = System.currentTimeMillis()
        val taken = HashSet<Int>()
        var found = 0
        for (b in locals) HardcoverSync.match(b, remote, taken)?.let { taken += it.optInt("id"); found++ }
        val took = System.currentTimeMillis() - start
        println("hardcover match: $took ms, $found found")
        assertEquals(800, found)
        assertTrue("took $took ms", took < 20_000)
    }
}
