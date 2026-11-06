package app.atlante.library

import app.atlante.library.model.Book
import app.atlante.library.saga.*
import org.junit.Assert.*
import org.junit.Test

/** Saga recognition on real Wikidata answers saved in test resources (no network). */
class SagaRecognizerTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource("wikidata/$name")!!.readText()
    private fun book(title: String, author: String) = Book().apply { this.title = title; this.author = author }

    @Test fun mistbornMembersInOrderWithSubSeries() {
        val m = Wikidata.parseMembers(res("members-mistborn.json"))
        val numbered = m.filter { !it.isSeries && it.ordinal != null }
        assertEquals(listOf("1", "2", "3"), numbered.take(3).map { it.ordinal })
        assertEquals("mistborn: l'ultimo impero", numbered.first().labelIt?.lowercase())
        assertTrue("Wax and Wayne is a sub-series", m.any { it.isSeries && it.label.contains("Wax", true) })
        assertEquals(m.size, m.map { it.qid }.distinct().size)
    }

    @Test fun redRisingTrilogyIsSubSeries() {
        val m = Wikidata.parseMembers(res("members-redrising.json"))
        assertTrue(m.any { it.isSeries })
        assertTrue(m.count { !it.isSeries } >= 3)
    }

    @Test fun cosmereIsUniverseNotSaga() {
        val l = Wikidata.parseLinks(res("links-final-empire.json"))!!
        assertEquals("Q2778373", l.workQid)
        assertTrue(l.series.any { it.seriesQid == "Q18535364" && it.isUniverse })
        val match = SagaMatch(l.workQid, l, 95, SagaMatch.Status.RELIABLE, emptyList())
        assertTrue(match.sagas.none { it.seriesQid == "Q18535364" })
        assertTrue(match.universes.any { it.seriesQid == "Q18535364" })
        assertTrue(match.sagas.any { it.ordinal == "1" })
    }

    @Test fun thresholdsAndStatuses() {
        assertEquals(SagaMatch.Status.RELIABLE, SagaRecognizer.status(90, true))
        assertEquals(SagaMatch.Status.UNCERTAIN, SagaRecognizer.status(75, true))
        assertEquals(SagaMatch.Status.NO_SAGA, SagaRecognizer.status(95, false))
        assertEquals(SagaMatch.Status.INSUFFICIENT, SagaRecognizer.status(40, true))
        val old = SagaRecognizer.reliable
        try { SagaRecognizer.reliable = 70; assertEquals(SagaMatch.Status.RELIABLE, SagaRecognizer.status(75, true)) } finally { SagaRecognizer.reliable = old }
    }

    @Test fun sameOriginIsNotIndependent() {
        val one = SagaRecognizer.combine(listOf(Evidence("Q1", "a", "wikidata", 75), Evidence("Q1", "b", "wikidata", 70)))
        assertEquals(75, one.second)
        val two = SagaRecognizer.combine(listOf(Evidence("Q1", "a", "wikidata", 75), Evidence("Q1", "b", "openlibrary", 80)))
        assertEquals(90, two.second)
    }

    @Test fun competingWorkLowersConfidence() {
        val r = SagaRecognizer.combine(listOf(Evidence("Q1", "a", "openlibrary", 95), Evidence("Q2", "b", "wikidata", 75)))
        assertEquals("Q1", r.first)
        assertTrue(r.second < SagaRecognizer.reliable)
    }

    @Test fun translatedTitleMatchesSagaPrefixedLabel() {
        assertTrue(SagaRecognizer.labelMatches(book("L'ultimo impero", "Brandon Sanderson"), "Mistborn: L'Ultimo Impero"))
        assertFalse(SagaRecognizer.labelMatches(book("Il pozzo dell'ascensione", "Brandon Sanderson"), "Mistborn: L'Ultimo Impero"))
    }

    private class Fixture(val search: List<Pair<String, String>>, val links: Map<String, WdWorkLinks>) : SagaRecognizer.Sources {
        override fun workForIsbn(isbn: String): String? = null
        override fun searchOpenLibrary(title: String, author: String) = emptyList<OlWork>()
        override fun linksForOpenLibrary(work: String): WdWorkLinks? = null
        override fun linksForItem(qid: String) = links[qid]
        override fun searchWikidata(text: String, language: String) = search
    }

    @Test fun italianTitleFoundThroughFullTextSearchSkippingSagaAndEdition() {
        val links = Wikidata.parseLinks(res("links-final-empire.json"))!!
        val src = Fixture(listOf(
            "Q2778373" to "Mistborn: L'Ultimo Impero — romanzo scritto da Brandon Sanderson",
            "Q9" to "Mistborn: L'Ultimo Impero — 2006 ebook edition of the novel by Brandon Sanderson",
            "Q8" to "Mistborn — serie di romanzi di Brandon Sanderson",
            "Q7" to "L'ultimo impero — saggio di un altro autore"), mapOf("Q2778373" to links, "Q9" to links.copy(workQid = "Q9")))
        val m = SagaRecognizer.identify(book("L'ultimo impero", "Brandon Sanderson"), src)
        assertEquals("Q2778373", m.workQid)
        assertEquals(SagaMatch.Status.UNCERTAIN, m.status)   // one source only: the user confirms
        assertTrue(m.sagas.any { it.ordinal == "1" })
    }

    @Test fun nothingFoundMeansNoAssociation() {
        val m = SagaRecognizer.identify(book("La casa", "Michael McDowell"), Fixture(emptyList(), emptyMap()))
        assertEquals(SagaMatch.Status.INSUFFICIENT, m.status)
        assertNull(m.workQid)
    }
}
