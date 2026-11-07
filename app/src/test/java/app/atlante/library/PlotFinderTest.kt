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

    /** The plot is the article's "Trama" section, not its introduction (which says what the book is). */
    @Test fun plotIsTheTramaSectionNotTheIntroduction() {
        val article = """
            Il nome della rosa è un romanzo scritto da Umberto Eco ed edito da Bompiani nel 1980.

            == Storia editoriale ==
            Il romanzo uscì nel 1980.

            == Trama ==
            Nel 1327 il frate francescano Guglielmo da Baskerville e il novizio Adso giungono in un'abbazia benedettina.

            === Primo giorno ===
            Un giovane monaco è stato trovato morto ai piedi del torrione.

            == Personaggi ==
            Guglielmo da Baskerville.
        """.trimIndent()
        val plot = PlotFinder.plotSection(article)!!
        assertTrue(plot, plot.startsWith("Nel 1327 il frate francescano"))
        assertTrue(plot, plot.contains("Un giovane monaco"))
        assertFalse(plot, plot.contains("è un romanzo scritto") || plot.contains("Personaggi") || plot.contains("Primo giorno") || plot.contains("1980"))
        assertEquals(null, PlotFinder.plotSection("Un romanzo.\n\n== Storia editoriale ==\nUscì nel 1980 presso un editore di Milano, poi tradotto in molte lingue nel mondo."))
    }
}
