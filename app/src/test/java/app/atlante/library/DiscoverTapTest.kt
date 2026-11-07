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
}
