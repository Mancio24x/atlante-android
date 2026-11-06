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

/** Saga reconstruction into Room from saved Wikidata answers (Mistborn, Red Rising). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SagaRepositoryTest {
    private lateinit var db: AtlanteDatabase
    private val dao get() = db.saga()
    private var clock = 1_000_000_000_000L
    private var memberCalls = 0
    private fun res(name: String) = javaClass.classLoader!!.getResource("wikidata/$name")!!.readText()

    private val sources = object : SagaRecognizer.Sources {
        val links = mapOf("Q2778373" to Wikidata.parseLinks(res("links-final-empire.json"))!!, "Q18393778" to Wikidata.parseLinks(res("links-red-rising.json"))!!)
        override fun workForIsbn(isbn: String): String? = null
        override fun searchOpenLibrary(title: String, author: String) = emptyList<OlWork>()
        override fun linksForOpenLibrary(work: String): WdWorkLinks? = null
        override fun linksForItem(qid: String) = links[qid]
        override fun searchWikidata(text: String, language: String) = when {
            text.startsWith("L'ultimo impero") -> listOf("Q2778373" to "Mistborn: L'Ultimo Impero — romanzo scritto da Brandon Sanderson")
            text.startsWith("Red Rising") -> listOf("Q18393778" to "Red Rising — novel by Pierce Brown")
            else -> emptyList()
        }
        override fun members(seriesQid: String): List<WdMember> { memberCalls++; return when (seriesQid) {
            "Q268100" -> Wikidata.parseMembers(res("members-mistborn.json"))
            "Q79053462" -> Wikidata.parseMembers(res("members-redrising.json"))
            else -> emptyList()
        } }
    }
    private val repo get() = SagaRepository(dao, sources) { clock }
    private fun book(id: String, title: String, author: String) = Book().apply { this.id = id; this.title = title; this.author = author }

    @Before fun open() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AtlanteDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() = db.close()

    @Test fun italianBookRebuildsWholeSagaWithMissingVolumes() = runBlocking {
        val link = repo.recognize(book("b1", "L'ultimo impero", "Brandon Sanderson"))
        assertEquals("to_confirm", link.status)              // one source only
        val saga = dao.series(link.seriesId!!)!!
        assertTrue(saga.name.contains("Mistborn", true))
        val volumes = dao.volumes(saga.id)
        assertTrue("the whole saga, not only the owned book", volumes.size >= 3)
        assertEquals(link.workId, volumes.first().work.id)    // the owned book is #1
        assertEquals("1", volumes.first().ordinal)
        assertTrue("original title, not the Italian one", volumes.first().work.originalTitle.contains("Final Empire", true))
        assertEquals("mistborn: l'ultimo impero", volumes.first().work.italianTitle?.lowercase())
        assertFalse(saga.possiblyIncomplete)
        assertTrue(dao.subseries(saga.id).any { it.name.contains("Wax", true) })
        assertNotNull(dao.universeByWikidata("Q18535364"))    // Cosmere stored as universe
        assertTrue(dao.allSeries().none { it.series.wikidataId == "Q18535364" })
        val summary = dao.allSeries().first { it.series.id == saga.id }
        assertEquals(1, summary.owned)
    }

    @Test fun redRisingTrilogyIsNestedInTheSaga() = runBlocking {
        val link = repo.recognize(book("b2", "Red Rising", "Pierce Brown"))
        val saga = dao.seriesByWikidata("Q79053462")!!
        val trilogy = dao.seriesByWikidata("Q137598364")!!
        assertEquals(saga.id, trilogy.parentId)
        assertEquals(saga.id, link.seriesId)                 // main saga first
        assertTrue(dao.volumes(saga.id).size >= 6)
    }

    @Test fun userChoicesAndManualDataSurviveRefresh() = runBlocking {
        val link = repo.recognize(book("b1", "L'ultimo impero", "Brandon Sanderson"))
        val sagaId = link.seriesId!!
        val extra = dao.insertWork(WorkEntity(originalTitle = "Mistborn: Secret History (edizione speciale)"))
        dao.putEntry(SeriesEntry(sagaId, extra, "3.5", 3.5, "novella", "manual"))
        repo.setByUser("b1", link.workId, sagaId, "confirmed")
        clock += 30L * 86_400_000
        val again = repo.recognize(book("b1", "L'ultimo impero", "Brandon Sanderson"), force = true)
        assertEquals("confirmed", again.status)
        dao.seriesByWikidata(dao.series(sagaId)!!.wikidataId!!)!!.let { repo.rebuild(WdSeriesLink(it.wikidataId!!, it.name, null, emptySet(), null, null, null, null), force = true) }
        assertTrue(dao.volumes(sagaId).any { it.work.id == extra && it.source == "manual" })
        val manual = repo.createManualSeries("La mia saga")
        assertTrue(dao.series(manual)!!.manual)
    }

    @Test fun freshSagasAreNotFetchedAgain() = runBlocking {
        repo.recognize(book("b1", "L'ultimo impero", "Brandon Sanderson"))
        val calls = memberCalls
        repo.recognize(book("b3", "L'ultimo impero", "Brandon Sanderson"))
        assertEquals(calls, memberCalls)
        clock += 8L * 86_400_000
        repo.recognize(book("b4", "L'ultimo impero", "Brandon Sanderson"))
        assertTrue(memberCalls > calls)
    }

    @Test fun incompleteLists() {
        fun m(o: String?) = WdMember("Q$o", "x", null, null, o, null, null, emptyList(), emptySet())
        assertFalse(repo.incomplete(listOf(m("1"), m("2"), m("3"))))
        assertTrue(repo.incomplete(listOf(m("1"), m("3"))))
        assertTrue(repo.incomplete(listOf(m("2"), m("3"))))
        assertFalse("one unnumbered extra is normal", repo.incomplete(listOf(m("1"), m("2"), m(null))))
        assertTrue(repo.incomplete(listOf(m("1"), m(null), m(null), m(null))))
        assertTrue(repo.incomplete(emptyList()))
    }
}
