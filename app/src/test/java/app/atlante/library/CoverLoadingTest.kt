package app.atlante.library

import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.atlante.library.model.Book
import app.atlante.library.model.BookMatcher
import app.atlante.library.online.OnlineCatalog
import app.atlante.library.saga.Discover
import app.atlante.library.saga.TitleIndex
import app.atlante.library.saga.db.AtlanteDatabase
import app.atlante.library.ui.compose.CoverImages
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** Faster covers: request pace, Italian editions in one answer, shared loader, Scopri with a large library. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoverLoadingTest {
    @Test fun coversByIdAreFasterThanTheRateLimitedApi() {
        assertEquals(100L, OnlineCatalog.interval("https://covers.openlibrary.org/b/id/123-M.jpg?default=false"))   // 10 per second
        assertEquals(100L, OnlineCatalog.interval("https://covers.openlibrary.org/b/olid/OL1M-M.jpg"))
        // Open Library limits covers by ISBN and its API: these keep about one per second.
        assertEquals(1050L, OnlineCatalog.interval("https://covers.openlibrary.org/b/isbn/9788804668237-M.jpg"))
        assertEquals(1050L, OnlineCatalog.interval("https://openlibrary.org/search.json?q=x"))
    }

    @Test fun italianEditionComesWithTheSearchAnswer() {
        val json = """{"docs":[
            {"key":"/works/OL82563W","title":"Harry Potter and the Philosopher's Stone","author_name":["J. K. Rowling"],"cover_i":15155833,
             "editions":{"docs":[{"title":"Harry Potter e la Pietra Filosofale","language":["ita"],"cover_i":8267048}]}},
            {"key":"/works/OL2W","title":"No Italian cover","author_name":["A"],"cover_i":5,"editions":{"docs":[{"title":"Senza copertina","language":["ita"]}]}},
            {"key":"/works/OL3W","title":"English only","author_name":["B"],"cover_i":6,"editions":{"docs":[{"title":"English only","language":["eng"],"cover_i":7}]}}
        ]}"""
        val (hp, noCover, english) = Discover.parse(json)
        assertEquals("Harry Potter e la Pietra Filosofale", hp.title); assertTrue(hp.italian)
        assertTrue(hp.coverUrl!!.contains("/b/id/8267048-"))
        assertFalse("looked up later by italian()", noCover.italian); assertTrue(noCover.coverUrl!!.contains("/b/id/5-"))
        assertFalse(english.italian); assertEquals("English only", english.title)
    }

    @Test fun titleIndexAgreesWithSameTitle() {
        val titles = listOf("Il nome della rosa", "Il colibrì : romanzo", "Dune", "Hunger Games - Il canto della rivolta", "", "L'amica geniale. Vol. 1")
        val probes = listOf("Il nome della rosa", "il colibri", "Dune: messia", "Hunger Games", "L'amica geniale", "Altro", "")
        val index = TitleIndex(titles) { it }
        for (p in probes) assertEquals("probe «$p»", titles.filter { OnlineCatalog.sameTitle(it, p) }.toSet(), index.matches(p).toSet())
    }

    @Test fun sameCoverAskedTwiceIsLoadedOnceAndMissingOnesAreRemembered() = runBlocking {
        CoverImages.clear()
        val reads = AtomicInteger()
        val read: suspend () -> Bitmap? = { reads.incrementAndGet(); delay(50); Bitmap.createBitmap(4, 6, Bitmap.Config.RGB_565) }
        val a = async { CoverImages.load("https://covers.openlibrary.org/b/id/1-M.jpg", read) }
        val b = async { CoverImages.load("https://covers.openlibrary.org/b/id/1-M.jpg", read) }
        assertNotNull(a.await()); assertSame(a.await(), b.await())
        assertEquals(1, reads.get())
        assertNotNull(CoverImages.cached("https://covers.openlibrary.org/b/id/1-M.jpg"))          // back to the screen: from memory

        val misses = AtomicInteger()
        repeat(3) { CoverImages.load("missing") { misses.incrementAndGet(); null } }
        assertEquals("a missing cover is not asked again at once", 1, misses.get())
        CoverImages.forget("missing")                                                          // a new cover was chosen
        CoverImages.load("missing") { misses.incrementAndGet(); null }
        assertEquals(2, misses.get())
    }

    /** Opening Scopri with a large library: the old check compared every candidate with every book on the main thread. */
    @Test fun discoverWithLargeLibraryIsFast() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AtlanteDatabase::class.java).allowMainThreadQueries().build()
        try {
            val library = (1..5000).map { i -> Book().apply { id = "b$i"; title = "Libro numero $i : romanzo"; author = "Autore Cognome$i" } } +
                Book().apply { id = "hp"; title = "Harry Potter and the Philosopher's Stone"; author = "J. K. Rowling" }
            val answer = javaClass.classLoader!!.getResource("openlibrary/discover-popular.json")!!.readText()
            val discover = Discover(db.saga(), { library }, { answer })
            discover.picks(null, 0)                                         // warm up (cache, class loading)
            val start = System.nanoTime(); val picks = discover.picks(null, 0) + discover.picks(null, 1)
            val newMs = (System.nanoTime() - start) / 1_000_000
            assertTrue(picks.none { it.workKey == "OL82563W" })            // owned book still left out
            val candidates = Discover.parse(answer)
            val oldStart = System.nanoTime()
            candidates.filter { c -> library.none { b -> OnlineCatalog.sameTitle(b.title, c.title) && BookMatcher.surnames(b.author).any { it in BookMatcher.surnames(c.author) } } }
            val oldMs = (System.nanoTime() - oldStart) / 1_000_000
            println("Scopri, 5001 libri: filtro vecchio $oldMs ms per pagina, nuovo ${newMs / 2} ms per pagina")
            assertTrue("new check ${newMs}ms", newMs < 1000)
        } finally { db.close() }
    }
}
