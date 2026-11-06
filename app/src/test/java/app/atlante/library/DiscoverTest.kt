package app.atlante.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.atlante.library.model.Book
import app.atlante.library.saga.Discover
import app.atlante.library.saga.db.AtlanteDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Scopri on saved Open Library answers: six covers, one per author, nothing already owned, Italian edition when known. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiscoverTest {
    private lateinit var db: AtlanteDatabase
    private val fetched = ArrayList<String>()
    private fun res(name: String) = javaClass.classLoader!!.getResource("openlibrary/$name")!!.readText()
    private val library = arrayListOf(Book().apply { id = "a"; title = "Il trono di spade"; author = "George R. R. Martin" })
    private val discover get() = Discover(db.saga(), { library }, { url -> fetched += url; when {
        "search.json" in url -> res("discover-popular.json")
        "/works/OL82563W/editions.json" in url -> res("editions-harry-potter.json")
        else -> """{"entries":[]}"""
    } })

    @Before fun open() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AtlanteDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() = db.close()

    @Test fun sixCoversOnePerAuthorStableAndPaged() = runBlocking {
        val first = discover.picks(null, 0)
        assertEquals(6, first.size)
        assertTrue(first.all { it.coverUrl != null })
        assertEquals(first.map { it.author }.distinct(), first.map { it.author })        // one per author
        assertEquals(first, discover.picks(null, 0))                                     // same answer
        val second = discover.picks(null, 1)
        assertTrue(second.none { s -> first.any { it.workKey == s.workKey } })
        assertTrue(fetched.count { "search.json" in it } == 1)                          // cached
        assertTrue(fetched.first().contains("sort=readinglog"))
        assertTrue(java.net.URLDecoder.decode(fetched.first(), "UTF-8").contains("language:ita"))
    }

    @Test fun ownedBooksAreNotSuggestedWhenTitlesMatch() = runBlocking {
        library += Book().apply { id = "b"; title = "Harry Potter and the Philosopher's Stone"; author = "J. K. Rowling" }
        val picks = discover.picks(null, 0) + discover.picks(null, 1)
        assertTrue(picks.none { it.title == "Harry Potter and the Philosopher's Stone" })
    }

    @Test fun italianEditionTitleAndCover() = runBlocking {
        val p = Discover.Pick("OL82563W", "Harry Potter and the Philosopher's Stone", "J. K. Rowling", "1997", "x", false)
        val it = discover.italian(p)
        assertEquals("Harry Potter e la pietra filosofale", it.title)
        assertTrue(it.italian); assertTrue(it.coverUrl!!.contains("covers.openlibrary.org/b/id/"))
        val none = discover.italian(p.copy(workKey = "OL1W"))
        assertEquals(p.title, none.title); assertFalse(none.italian)                    // nothing invented
    }

    @Test fun genreQueryUsesSubject() = runBlocking {
        discover.picks(Discover.GENRES.first { it.label == "Fantasy" }, 0)
        assertTrue(java.net.URLDecoder.decode(fetched.first(), "UTF-8").contains("subject:fantasy"))
    }
}
