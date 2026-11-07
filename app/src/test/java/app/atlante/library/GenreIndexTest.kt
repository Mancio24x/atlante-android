package app.atlante.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.atlante.library.catalog.RepairWorker
import app.atlante.library.model.Book
import app.atlante.library.model.LibraryQuery
import app.atlante.library.saga.Genres
import app.atlante.library.storage.LibraryStore
import app.atlante.library.ui.compose.genreCounts
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Genres: one taxonomy for the library, the filters, the statistics; several genres per book; never invented. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GenreIndexTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun book(id: Char, title: String, author: String, genre: String = "", status: String = Book.TO_READ) =
        Book().apply { this.id = id.toString().repeat(64); this.title = title; this.author = author; this.genre = genre; this.status = status; format = "CATALOGO" }

    @Test fun labelsBecomeCanonicalGenres() {
        assertEquals(listOf("Fantascienza"), Genres.all("Science Fiction"))
        assertEquals(listOf("Fantascienza"), Genres.all("Sci-fi"))
        assertEquals(listOf("Fantasy"), Genres.all("Fiction, Fantasy, Magic"))                 // "Fiction" is noise next to Fantasy
        assertEquals(listOf("Fantasy", "Avventura"), Genres.all("Fantasy; Adventure"))
        assertEquals(listOf("Filosofia"), Genres.all("Philosophy"))
        assertEquals(listOf("Storia"), Genres.all("History"))
        assertEquals(listOf("Romanzo storico"), Genres.all("Historical fiction"))
        assertEquals(listOf("Biografia"), Genres.all("Autobiografia"))
        assertEquals(listOf("Narrativa"), Genres.all("Narrativa"))                              // generic, but alone
        assertTrue(Genres.all("").isEmpty()); assertTrue(Genres.all("Cucina tradizionale").isEmpty())
    }

    @Test fun statisticsCountMembershipAndMainGenre() {
        val books = listOf(book('a', "A", "X", "Fantasy, Avventura", Book.READ), book('b', "B", "Y", "Fantasy"), book('c', "C", "Z", ""),
            book('d', "D", "W", "Cucina"))
        assertEquals(listOf("Fantasy" to 2, "Avventura" to 1, "Altri generi" to 1, "Genere non indicato" to 1), genreCounts(books, membership = true))
        assertEquals(listOf("Fantasy" to 2, "Altri generi" to 1, "Genere non indicato" to 1), genreCounts(books, membership = false))
        assertEquals(listOf("Avventura" to 1, "Fantasy" to 1), genreCounts(books.filter { it.status == Book.READ }, membership = true))
        // The same genres in the library filters.
        assertEquals(listOf("A"), LibraryQuery.select(books, "", "Tutti i libri", "Avventura", "", "Titolo").map { it.title })
        assertEquals(2, LibraryQuery.select(books, "", "Tutti i libri", "Fantasy", "", "Titolo").size)
    }

    /** Books without genre get theirs from the offline catalog (Wikidata); a label you wrote stays as it is. */
    @Test fun indexingFillsFromTheCatalogOnly() = runBlocking {
        org.junit.Assume.assumeTrue(File("src/main/assets/catalog/atlante-catalog.db").isFile || File("app/src/main/assets/catalog/atlante-catalog.db").isFile)
        File(context.filesDir, "library.json").delete(); File(context.filesDir, "books").deleteRecursively()
        val store = LibraryStore.shared(context); for (b in store.snapshot().toList()) store.remove(b)
        store.addCatalog(book('a', "Harry Potter e la pietra filosofale", "J. K. Rowling"))
        store.addCatalog(book('b', "Il nome della rosa", "Umberto Eco", "Il mio genere preferito"))
        store.addCatalog(book('c', "Un libro che non esiste", "Nessuno Autore"))
        val w = TestListenableWorkerBuilder<RepairWorker>(context).setInputData(workDataOf(RepairWorker.TYPE to RepairWorker.GENRES, RepairWorker.ONLINE to false)).build()
        w.doWork()
        val hp = store.find("a".repeat(64))!!
        assertTrue(hp.genre, Genres.of(hp).contains("Fantasy")); assertTrue(hp.meta.optJSONObject("sources")!!.optString("genres").contains("catalogo"))
        assertEquals("Il mio genere preferito", store.find("b".repeat(64))!!.genre)
        assertTrue(Genres.of(store.find("c".repeat(64))!!).isEmpty())                       // nothing reliable: no genre invented
        assertEquals("", store.find("c".repeat(64))!!.genre)
        assertEquals(JSONArray(Genres.of(hp)).toString(), hp.meta.optJSONArray("genres").toString())
    }
}
