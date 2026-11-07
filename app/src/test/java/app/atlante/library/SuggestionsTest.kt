package app.atlante.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.atlante.library.model.Book
import app.atlante.library.saga.*
import app.atlante.library.saga.db.*
import app.atlante.library.ui.compose.SagaLayout
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

    @Test fun subSeriesLanesAndReadingOrders() = runBlocking {
        val saga = dao.insertSeries(SeriesEntity(name = "Red Rising Saga", wikidataId = "Q79053462"))
        val trilogy = dao.insertSeries(SeriesEntity(name = "Red Rising Trilogy", wikidataId = "Q137598364", parentId = saga))
        val titles = listOf("Red Rising" to "2014", "Golden Son" to "2015", "Morning Star" to "2016", "Iron Gold" to "2018")
        val ids = titles.mapIndexed { i, (t, y) -> dao.insertWork(WorkEntity(originalTitle = t, firstPublished = y, wikidataId = "Q$i")) }
        dao.replaceAutomaticEntries(saga, ids.mapIndexed { i, w -> SeriesEntry(saga, w, "${i + 1}", i + 1.0) })
        dao.replaceAutomaticEntries(trilogy, ids.take(3).mapIndexed { i, w -> SeriesEntry(trilogy, w, "${i + 1}", i + 1.0) })
        val shelf = suggestions.saga(BookLink("b9", ids[0], saga, 95, "auto"))!!
        assertEquals(mapOf("Red Rising Trilogy" to listOf(0, 1, 2)), shelf.lanes)
        val items = listOf(ShelfItem("Prequel", "", "2020", "0", null, false, null, null, ""), ShelfItem("Primo", "", "2010", "1", null, false, null, null, ""),
            ShelfItem("Senza anno", "", null, "2", null, false, null, null, ""))
        assertEquals(listOf(0, 1, 2), SagaLayout.layout(items, SagaLayout.Order.SAGA, null).nodes.map { it.item })
        // By publication: the book without a year is not placed by guess.
        val byYear = SagaLayout.layout(items, SagaLayout.Order.PUBLICATION, null)
        assertEquals(listOf(1, 0), byYear.nodes.map { it.item }); assertEquals(listOf(2), byYear.waiting.map { it.item })
        // Your order is saved, comes back with the shelf and wins over the numbering.
        suggestions.saveOrder(saga, listOf(ids[3], ids[0], ids[1], ids[2]))
        val again = suggestions.saga(BookLink("b9", ids[0], saga, 95, "auto"))!!
        assertEquals(listOf(ids[3], ids[0], ids[1], ids[2]), again.manualOrder)
        assertEquals(listOf("Iron Gold", "Red Rising", "Golden Son", "Morning Star"),
            SagaLayout.layout(again.items, SagaLayout.Order.PERSONAL, again.manualOrder).nodes.map { again.items[it.item].title })
        suggestions.saveOrder(saga, null)
        assertNull(suggestions.saga(BookLink("b9", ids[0], saga, 95, "auto"))!!.manualOrder)
    }

    /** "Controlla le mappe": the sheet says n. 2, the source n. 1 → a proposal; "No" is remembered. */
    @Test fun mapCheckProposesAndRemembersNo() = runBlocking {
        val saga = dao.insertSeries(SeriesEntity(name = "Mistborn", wikidataId = "hc:1"))
        val w1 = dao.insertWork(WorkEntity(originalTitle = "The Final Empire", italianTitle = "L'ultimo impero", firstPublished = "2006", wikidataId = "hcb:1"))
        val w2 = dao.insertWork(WorkEntity(originalTitle = "The Well of Ascension", firstPublished = "2007", wikidataId = "hcb:2"))
        val w4 = dao.insertWork(WorkEntity(originalTitle = "The Alloy of Law", firstPublished = "2011", wikidataId = "hcb:4"))
        dao.replaceAutomaticEntries(saga, listOf(SeriesEntry(saga, w1, "1", 1.0, source = "hardcover"), SeriesEntry(saga, w2, "2", 2.0, source = "hardcover"),
            SeriesEntry(saga, w4, "4", 4.0, source = "hardcover")))
        library[0].series = "Mistborn"; library[0].number = 2.0
        dao.putLink(BookLink("b1", w1, saga, 95, "auto"))
        val repair = SagaRepair(dao, SagaRepository(dao))
        val issues = repair.mapIssues(suggestions, library)
        val proposal = issues.single { it.bookId == "b1" }
        assertEquals("1", proposal.ordinal); assertTrue(proposal.text, proposal.text.contains("Hardcover"))
        assertTrue(issues.any { it.text.contains("manca il n. 3") })
        repair.refuse(proposal)
        assertTrue(repair.mapIssues(suggestions, library).none { it.bookId == "b1" })
        library[0].series = ""; library[0].number = 0.0
    }

    @Test fun bundlesAreNotSuggested() {
        for (t in listOf("Red Rising 3-Book Box Set", "The Red Rising Series Collection", "Kızıl İsyan Serisi", "Mistborn Trilogy Boxed Set", "The Stormlight Archive Books 1-3", "Summary of Red Rising"))
            assertTrue(t, Suggestions.bundle(t))
        for (t in listOf("Golden Son", "The Way of Kings", "Il nome della rosa", "Dune Messiah", "Setting Sun"))
            assertFalse(t, Suggestions.bundle(t))
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
