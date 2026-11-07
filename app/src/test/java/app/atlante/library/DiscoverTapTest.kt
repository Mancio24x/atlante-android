package app.atlante.library

import app.atlante.library.catalog.BibliographicEngine
import app.atlante.library.model.Book
import org.junit.Assert.*
import org.junit.Test

/** The crash of a tap in Scopri: the book looked up has no id yet. */
class DiscoverTapTest {
    @Test fun aBookWithoutIdIsComparedWithoutCrashing() {
        val wanted = Book().apply { title = "L'ultimo impero"; author = "Brandon Sanderson" }
        assertNull(wanted.id)
        val mine = Book().apply { id = "a".repeat(64); title = "L'ultimo impero"; author = "Brandon Sanderson"; format = "CATALOGO"
            meta.put("notSameAs", org.json.JSONArray().put("b".repeat(64))) }
        val other = Book().apply { id = "c".repeat(64); title = "Dune"; author = "Frank Herbert"; format = "CATALOGO" }
        assertEquals(mine.id, BibliographicEngine.sameWorkIn(listOf(other, mine), wanted, null)?.id)
        assertNull(BibliographicEngine.sameWorkIn(listOf(other), wanted, null))
        assertFalse(BibliographicEngine.different(mine, wanted))
    }

    /** With the work id from Scopri, an Open Library record of another work is never the sheet's edition. */
    @Test fun theWorkIdConfirmsTheEdition() {
        val right = Book().apply { title = "Dune"; author = "Frank Herbert"; source = "/works/OL893415W" }
        val other = Book().apply { title = "Dune"; author = "Frank Herbert"; source = "/works/OL999W" }
        val sbn = Book().apply { title = "Dune"; author = "Frank Herbert"; source = "sbn:IT\\ICCU\\X" }
        assertSame(right, MainActivity.confirmWork(other, listOf(other, right), "OL893415W"))
        assertNull(MainActivity.confirmWork(other, listOf(other), "OL893415W"))
        assertSame(sbn, MainActivity.confirmWork(sbn, listOf(sbn, right), "OL893415W"))
        assertSame(right, MainActivity.confirmWork(null, listOf(other, right), "/works/OL893415W"))
        assertSame(other, MainActivity.confirmWork(other, listOf(other), null))
        assertSame(other, MainActivity.confirmWork(other, listOf(other), "hc:123"))
    }
}
