package app.atlante.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.atlante.library.model.Book
import app.atlante.library.saga.*
import app.atlante.library.saga.db.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Hardcover as saga source, from real answers recorded on 6 Nov 2026 (test/resources/hardcover). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HardcoverSagaTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource("hardcover/$name")!!.readText()
    private fun parse(name: String) = Hardcover.parseDetails(JSONObject(res("book-$name.json")))
    private fun book(title: String, author: String, isbn: String = "") = Book().apply { this.title = title; this.author = author; this.isbn = isbn }

    @Test fun mistbornSagaSubSeriesAndCosmereUniverse() {
        val (links, members) = parse("mistborn")
        val saga = links.series.first()
        assertEquals("The Mistborn Saga", saga.seriesLabel)
        assertEquals("1", saga.ordinal)
        assertEquals("The Cosmere", saga.universeLabel)
        val trilogy = links.series.first { it.seriesLabel.contains("Original Trilogy") }
        assertEquals(saga.seriesQid, trilogy.parentQid)
        assertTrue(links.series.single { it.isUniverse }.seriesLabel == "The Cosmere")
        val match = SagaMatch(links.workQid, links, 95, SagaMatch.Status.RELIABLE, emptyList())
        assertEquals(listOf("The Mistborn Saga", "The Mistborn Saga: The Original Trilogy"), match.sagas.map { it.seriesLabel })
        // Volumes: one book per number, Italian titles without the "Mistborn, Volume 2" tail, no split editions.
        val volumes = members.getValue(trilogy.seriesQid)
        assertEquals(listOf("1", "1.5", "2", "3", "3.5"), volumes.map { it.ordinal })
        assertEquals("Il pozzo dell'ascensione", volumes.first { it.ordinal == "2" }.labelIt)
        assertEquals("The Final Empire", volumes.first().labelEn?.substringAfter(": "))
    }

    @Test fun duneOrderListsAreOneUniverse() {
        val (links, members) = parse("dune")
        val sagas = links.series.filterNot { it.isUniverse }
        assertEquals(listOf("Dune"), sagas.map { it.seriesLabel })
        assertEquals("4", sagas.single().ordinal)
        assertEquals("Dune Universe", sagas.single().universeLabel)
        assertEquals(1, links.series.count { it.isUniverse })          // chronological and publication order are one universe
        val dune = members.getValue(sagas.single().seriesQid)
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), dune.mapNotNull { it.ordinal })
        assertEquals("Messia di Dune", dune[1].labelIt)
        assertTrue("translations entered as books are dropped", dune.none { it.labelEn!!.startsWith("Dyyn") || it.labelEn!!.contains("παιδιά") })
    }

    @Test fun cityOfBonesInShadowhunterUniverse() {
        val (links, members) = parse("city-of-bones")
        val saga = links.series.first()
        assertEquals("The Mortal Instruments", saga.seriesLabel)
        assertEquals("The Shadowhunter Chronicles", saga.universeLabel)
        val first = members.getValue(saga.seriesQid).first()
        assertEquals("hcb:144062", first.qid)                           // the novel, not the box set or a translation
        assertEquals("Città di ossa", first.labelIt)
    }

    @Test fun foundationKeepsPublicationOrderAndAsimovUniverse() {
        val (links, _) = parse("foundation")
        val sagas = links.series.filterNot { it.isUniverse }
        assertEquals(listOf("Foundation"), sagas.map { it.seriesLabel })
        assertEquals("1", sagas.single().ordinal)
        assertEquals("Greater Foundation Universe", sagas.single().universeLabel)
    }

    @Test fun redRisingTrilogyInsideTheSaga() {
        val (links, _) = parse("red-rising")
        val match = SagaMatch(links.workQid, links, 95, SagaMatch.Status.RELIABLE, emptyList())
        assertEquals("Red Rising Saga", match.sagas.first().seriesLabel)
        assertEquals("hc:1033", match.sagas[1].parentQid)
        assertTrue(match.universes.isEmpty())
    }

    @Test fun searchNeedsTitleAndAuthor() {
        val results = JSONObject(res("search-via-dei-re.json"))
        assertEquals(386446 to 88, Hardcover.pickHit(book("La via dei re", "Brandon Sanderson"), results))
        assertNull("same Italian title, another author", Hardcover.pickHit(book("La via dei re", "André Malraux"), results))
        assertNull(Hardcover.pickHit(book("Il nome della rosa", "Brandon Sanderson"), results))
    }

    @Test fun namesAndTitlesAreCleaned() {
        assertEquals("The Witcher", Hardcover.displayName("Andrzej Sapkowski’s The Witcher"))
        assertEquals("The Shadowhunter Chronicles", Hardcover.displayName("The Shadowhunter Chronicles: Publication Order"))
        assertEquals("Ender's Saga", Hardcover.displayName("Ender's Saga"))
        assertEquals("Gli eretici di Dune", Hardcover.italianTitle("Gli eretici di Dune. Il ciclo di Dune 5"))
        assertEquals("Shadowhunters: città degli angeli caduti", Hardcover.italianTitle("Shadowhunters: città degli angeli caduti"))
    }

    // ---- recognizer and database -------------------------------------------------------------------

    private var wikidataCalls = 0
    private inner class HcSources(private val found: HcFound?, private val members: Map<String, List<WdMember>>) : SagaRecognizer.Sources {
        override fun workForIsbn(isbn: String): String? { wikidataCalls++; return null }
        override fun searchOpenLibrary(title: String, author: String): List<OlWork> { wikidataCalls++; return emptyList() }
        override fun linksForOpenLibrary(work: String): WdWorkLinks? = null
        override fun linksForItem(qid: String): WdWorkLinks? = null
        override fun searchWikidata(text: String, language: String): List<Pair<String, String>> { wikidataCalls++; return emptyList() }
        override fun members(seriesQid: String): List<WdMember>? = members[seriesQid]
        override fun hardcover(book: Book) = found
    }

    private lateinit var db: AtlanteDatabase
    @Before fun open() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AtlanteDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() = db.close()

    @Test fun sureHardcoverAnswerSkipsWikidata() {
        val (links, members) = parse("mistborn")
        val match = SagaRecognizer.identify(book("L'ultimo impero", "Brandon Sanderson", "9788804748809"), HcSources(HcFound(links, 95, "ISBN → Hardcover"), members))
        assertEquals(SagaMatch.Status.RELIABLE, match.status)
        assertEquals(0, wikidataCalls)
    }

    @Test fun repositoryStoresHardcoverSagaUniverseAndItalianTitles() = runBlocking {
        val dao = db.saga()
        val (links, members) = parse("mistborn")
        val repo = SagaRepository(dao, HcSources(HcFound(links, 95, "ISBN → Hardcover"), members))
        // A saga found earlier on Wikidata for the same book: it goes away once nothing points to it.
        val old = dao.insertSeries(SeriesEntity(name = "Mistborn", wikidataId = "Q268100"))
        dao.putLink(BookLink("b1", null, old, 80, "no_saga"))
        val link = repo.recognize(Book().apply { id = "b1"; title = "L'ultimo impero"; author = "Brandon Sanderson"; isbn = "9788804748809" }, force = true)
        assertEquals("auto", link.status)
        val saga = dao.series(link.seriesId!!)!!
        assertEquals("The Mistborn Saga", saga.name)
        assertEquals("hc:5452", saga.wikidataId)
        assertEquals("The Cosmere", dao.universe(saga.universeId!!)!!.name)
        assertEquals("1", dao.ordinal(saga.id, link.workId!!))
        val trilogy = dao.subseries(saga.id).single()
        assertEquals(listOf("1", "1.5", "2", "3", "3.5"), dao.volumes(trilogy.id).map { it.ordinal })
        assertEquals("Il campione delle ere", dao.volumes(trilogy.id).first { it.ordinal == "3" }.work.italianTitle)
        assertTrue(dao.pruneSeries() >= 1)
        assertNull(dao.series(old))
        assertNotNull(dao.series(saga.id)); assertNotNull(dao.series(trilogy.id))
    }

    @Test fun sagaIsKeptWhenHardcoverCannotAnswer() = runBlocking {
        val dao = db.saga()
        val (links, members) = parse("mistborn")
        val link = SagaRepository(dao, HcSources(HcFound(links, 95, "ISBN"), members)).recognize(Book().apply { id = "b1"; title = "L'ultimo impero"; author = "Brandon Sanderson" })
        val before = dao.volumes(link.seriesId!!).size
        // Key removed or offline: members() answers null, the known volumes must stay.
        SagaRepository(dao, HcSources(null, emptyMap()), now = { Long.MAX_VALUE / 2 })
            .rebuild(WdSeriesLink("hc:5452", "The Mistborn Saga", null, Hardcover.SAGA_KIND, null, null, null, null), force = true)
        assertEquals(before, dao.volumes(link.seriesId!!).size)
    }
}
