package app.atlante.library

import app.atlante.library.model.Book
import app.atlante.library.online.PlotFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlotFinderTest {
    @Test fun cleanRemovesTagsAndEntities() {
        assertEquals("Un <romanzo> & «storia» d'amore.\n\nSeconda parte.",
            PlotFinder.clean("<p>Un &lt;romanzo&gt; &amp; &#171;storia&#xBB; d&apos;amore.</p><p>Seconda   parte.</p>"))
    }

    @Test fun withoutIsbnNoPlotByTitleAndAuthor() {
        assertFalse(PlotFinder.sameEditionPossible(Book().apply { title = "Il nome della rosa"; author = "Umberto Eco" }))
        assertTrue(PlotFinder.sameEditionPossible(Book().apply { title = "Il nome della rosa"; isbn = "9788845278655" }))
    }

    @Test fun existingDescriptionIsCleanedAndKept() {
        val b = Book().apply { description = "<b>Trama</b> &quot;vera&quot;" }
        PlotFinder.fill(b, null)
        assertEquals("Trama \"vera\"", b.description)
    }
}
