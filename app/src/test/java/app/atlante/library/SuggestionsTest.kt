package app.atlante.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.atlante.library.model.Book
import app.atlante.library.saga.*
import app.atlante.library.saga.db.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Carousels of the book page on saved Open Library and Wikidata answers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SuggestionsTest {
    private lateinit var db: AtlanteDatabase
    private val dao get() = db.saga()
    private var clock = 1_000_000_000_000L
    private val fetched = ArrayList<String>()
    private fun res(path: String) = javaClass.classLoader!!.getResource(path)!!.readText()
    private val fetch: (String) -> String = { url -> fetched += url; when {
        "author=" in url -> res("openlibrary/author-sanderson.json")
        "subject" in url -> res("openlibrary/subject-fantasy.json")
        "/works/" in url -> """{"covers":[14658160]}"""
        else -> error(url)
    } }
    private val library = arrayListOf(
        Book().apply { id = "b1"; title = "L'ultimo impero"; author = "Brandon Sanderson"; genre = "Epic Fantasy"; year = "2006" },
        Book().apply { id = "b2"; title = "Elantris"; author = "Brandon Sanderson"; status = Book.READ },
        Book().apply { id = "b3"; title = "Harry Potter e la pietra filosofale"; author = "J. K. Rowling" },
    )
    private val suggestions get() = Suggestions(dao, { library }, fetch) { clock }

    @Before fun open() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AtlanteDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() = db.close()

    @Test fun genresAreNormalized() {
        assertEquals("Fantasy", Genres.normalize("Epic Fantasy"))
        assertEquals("Fantasy", Genres.normalize("fantasy epico"))
        assertEquals("Fantascienza", Genres.normalize("Science Fiction"))
        assertEquals("Fantascienza", Genres.normalize("Fantascienza distopica"))
        assertEquals("Giallo", Genres.normalize("Gialli"))
        assertEquals("Romanzo storico", Genres.normalize("Romanzo storico"))
        assertNull(Genres.normalize(""))
        assertNull(Genres.normalize("Varie"))
        assertEquals("fantasy", Genres.subjectFor("High Fantasy"))
    }

    @Test fun authorCarouselOwnedFirstWithoutDuplicates() = runBlocking {
        // The saga carousel knows the original title of this book (The Final Empire).
        val saga = listOf(ShelfItem("L'ultimo impero", "Brandon Sanderson", null, "1", "b1", false, null, null, "", listOf("Mistborn: The Final Empire", "The Final Empire")))
        val items = suggestions.byAuthor(library[0], saga)
        assertEquals("Elantris", items.first().title)                 // owned book first
        assertEquals("b2", items.first().ownedBookId)
        assertTrue(items.first().read)
        assertEquals(1, items.count { it.title.equals("Elantris", true) })   // online copy dropped
        assertTrue(items.none { it.title == "The Final Empire" })     // the book itself is not suggested
        assertTrue(items.drop(1).all { it.ownedBookId == null })
        assertTrue(items.any { it.title == "The Way of Kings" })
    }

    @Test fun authorsAreComparedByFullName() {
        assertTrue(Suggestions.sameAuthor("J. K. Rowling", "J.K. Rowling"))
        assertTrue(Suggestions.sameAuthor("Brandon Sanderson", "brandon sanderson"))
        assertFalse(Suggestions.sameAuthor("Pierce Brown", "Dan Brown"))
        assertFalse(Suggestions.sameAuthor("Pierce Brown", "Brown"))
    }

    @Test fun mayInterestIsDeterministicAndExcludesWhatYouHave() = runBlocking {
        val a = suggestions.mayInterest(library[0]); val b = suggestions.mayInterest(library[0])
        assertEquals(a, b)
        assertTrue(a.isNotEmpty())
        assertTrue(a.none { it.author.contains("Sanderson") })
        assertTrue("owned Harry Potter excluded", a.none { it.title.startsWith("Harry Potter and the Philosopher") || it.title.contains("pietra filosofale", true) })
        assertTrue("at most two per author", a.groupBy { it.author }.values.all { it.size <= 2 })
        assertTrue(a.none { it.ownedBookId != null })
        assertTrue(suggestions.mayInterest(Book().apply { id = "x"; title = "X"; genre = "Varie" }).isEmpty())
    }

    @Test fun answersAreCachedForThirtyDays() = runBlocking {
        suggestions.byAuthor(library[0]); suggestions.byAuthor(library[0])
        assertEquals(1, fetched.count { "author=" in it })
        clock += 31L * 86_400_000
        suggestions.byAuthor(library[0])
        assertEquals(2, fetched.count { "author=" in it })
    }

    @Test fun sagaShelfMarksOwnedReadAndMissing() = runBlocking {
        val saga = dao.insertSeries(SeriesEntity(name = "Mistborn", wikidataId = "Q268100"))
        val w1 = dao.insertWork(WorkEntity(originalTitle = "Mistborn: The Final Empire", italianTitle = "Mistborn: L'Ultimo Impero", wikidataId = "Q1"))
        val w2 = dao.insertWork(WorkEntity(originalTitle = "Mistborn: The Well of Ascension", italianTitle = "Mistborn: Il Pozzo dell'Ascensione", wikidataId = "Q2", openLibraryId = "OL5738150W"))
        dao.replaceAutomaticEntries(saga, listOf(SeriesEntry(saga, w2, "2", 2.0), SeriesEntry(saga, w1, "1", 1.0)))
        val link = BookLink("b1", w1, saga, 95, "auto"); dao.putLink(link)
        val shelf = suggestions.saga(link)!!
        assertEquals(listOf("1", "2"), shelf.items.map { it.ordinal })
        assertEquals("L'ultimo impero", shelf.items[0].title)          // the library's own title
        assertEquals("b1", shelf.items[0].ownedBookId)
        assertNull(shelf.items[1].ownedBookId)
        assertEquals("OL5738150W", shelf.items[1].coverWork)
        assertEquals(1, shelf.owned); assertEquals(2, shelf.total)
        assertEquals("https://covers.openlibrary.org/b/id/14658160-M.jpg?default=false", suggestions.workCover("OL5738150W"))
    }
}
