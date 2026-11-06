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

/** "Ripara saghe": rechecks what is unsure, keeps the user's choices, fills empty saga fields, drops stale links. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SagaRepairTest {
    private lateinit var db: AtlanteDatabase
    private val dao get() = db.saga()
    private fun res(name: String) = javaClass.classLoader!!.getResource("wikidata/$name")!!.readText()
    private val sources = object : SagaRecognizer.Sources {
        val red = Wikidata.parseLinks(res("links-red-rising.json"))!!
        override fun workForIsbn(isbn: String): String? = null
        override fun searchOpenLibrary(title: String, author: String) = emptyList<OlWork>()
        override fun linksForOpenLibrary(work: String): WdWorkLinks? = null
        override fun linksForItem(qid: String) = red.takeIf { qid == "Q18393778" }
        override fun searchWikidata(text: String, language: String) =
            if (text.startsWith("Red Rising")) listOf("Q18393778" to "Red Rising — novel by Pierce Brown") else emptyList()
        override fun members(seriesQid: String) = if (seriesQid == "Q79053462") Wikidata.parseMembers(res("members-redrising.json")) else emptyList()
    }

    @Before fun open() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AtlanteDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() = db.close()

    @Test fun repairs() = runBlocking {
        val repo = SagaRepository(dao, sources)
        // Two independent origins are needed for "auto": make Wikidata alone reliable for this test.
        val old = SagaRecognizer.reliable; SagaRecognizer.reliable = 75
        try {
            val red = Book().apply { id = "r"; title = "Red Rising"; author = "Pierce Brown" }
            val mine = Book().apply { id = "m"; title = "Qualcosa"; author = "Io"; series = "La mia saga" }
            val unknown = Book().apply { id = "u"; title = "Ignoto"; author = "Nessuno" }
            val manual = repo.createManualSeries("La mia saga"); repo.setByUser("m", null, manual, "manual")
            dao.putLink(BookLink("gone", status = "auto"))
            dao.putLink(BookLink("r", status = "unidentified", checkedAt = System.currentTimeMillis()))   // fresh but unsure: rechecked
            val filled = ArrayList<String>()
            val report = SagaRepair(dao, repo).run(listOf(red, mine, unknown), false, { b, name, ordinal -> filled += "${b.id}:$name:$ordinal"; true }) { _, _, _ -> }
            assertEquals(2, report.checked)
            assertEquals(1, report.linked)
            assertEquals(1, report.unidentified)
            assertEquals(1, report.keptByUser)
            assertEquals(1, report.removed)
            assertEquals(listOf("r:Red Rising Saga:1"), filled)
            assertNull(dao.link("gone"))
            assertEquals("manual", dao.link("m")!!.status)
        } finally { SagaRecognizer.reliable = old }
    }
}
