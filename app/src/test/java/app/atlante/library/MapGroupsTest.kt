package app.atlante.library

import app.atlante.library.model.Book
import app.atlante.library.ui.MapGroups
import org.junit.Assert.*
import org.junit.Test

/** Reading map: the same author and the same saga are not scattered when they are written differently. */
class MapGroupsTest {
    private var n = 0
    private fun book(title: String, author: String, series: String = "", number: Double = 0.0, year: String = "") =
        Book().apply { id = "b${n++}"; this.title = title; this.author = author; this.series = series; this.number = number; this.year = year }

    @Test fun oneAuthorWhateverTheSpelling() {
        val books = listOf(book("Io, robot", "Isaac Asimov"), book("Fondazione", "Asimov, Isaac"), book("Abissi d'acciaio", "ISAAC ASIMOV"),
            book("Notturno", "Isaac Asimov & Robert Silverberg"), book("Dune", "Frank Herbert"))
        assertEquals(listOf("Frank Herbert", "Isaac Asimov", "Robert Silverberg"), MapGroups.authorNames(books))
        assertEquals(4, MapGroups.byAuthor(books, "Asimov, Isaac", 250).size)
        assertEquals(1, MapGroups.byAuthor(books, "Robert Silverberg", 250).size)        // co-author too
    }

    @Test fun sagaNamesAreCompared() {
        val same = listOf("Fondazione", "Il ciclo della Fondazione", "Ciclo delle Fondazioni", "Fondazione 3", "Foundation", "Foundation series", "La saga della Fondazione")
        assertEquals(setOf("fondazione"), same.map(MapGroups::seriesKey).toSet())
        assertEquals(setOf("dune"), listOf("Dune", "Ciclo di Dune", "Le cronache di Dune", "Dune Chronicles", "Dune vol. 2").map(MapGroups::seriesKey).toSet())
        assertNotEquals(MapGroups.seriesKey("Robot"), MapGroups.seriesKey("Fondazione"))
        assertEquals(MapGroups.seriesKey("Il ciclo dei Robot"), MapGroups.seriesKey("Robot"))
    }

    @Test fun asimovIsNotScattered() {
        val books = listOf(
            book("Fondazione", "Isaac Asimov", "Ciclo della Fondazione", 1.0),
            book("Fondazione e Impero", "Isaac Asimov", "Fondazione", 2.0),
            book("Seconda Fondazione", "Asimov, Isaac", "Foundation 3"),
            book("L'orlo della Fondazione", "Isaac Asimov"),                 // no saga in its data, recognized by the engine
            book("Io, robot", "Isaac Asimov", "Robot"),
            book("Abissi d'acciaio", "Isaac Asimov", "Il ciclo dei Robot", 2.0),
            book("Il sole nudo", "Isaac Asimov", "Robot 3"),
            book("La fine dell'eternità", "Isaac Asimov"),
        )
        val known = mapOf(books[3].id to MapGroups.Known("Ciclo della Fondazione", "4"), books[2].id to MapGroups.Known("Ciclo della Fondazione", "3"))
        val groups = MapGroups.group(books, known)
        assertEquals(listOf("Ciclo della Fondazione", "Robot", MapGroups.SINGLE), groups.map { it.name })
        assertEquals(listOf("Fondazione", "Fondazione e Impero", "Seconda Fondazione", "L'orlo della Fondazione"), groups[0].books.map { it.title })
        assertEquals(listOf("Abissi d'acciaio", "Il sole nudo", "Io, robot"), groups[1].books.map { it.title })   // unnumbered last
        assertEquals(listOf("La fine dell'eternità"), groups[2].books.map { it.title })
        assertEquals(3.0, MapGroups.number(books[2], known[books[2].id]), 0.0)
    }

    @Test fun duneWithoutSagaFieldsJoinsTheSaga() {
        val books = listOf(book("Dune", "Frank Herbert", "Dune", 1.0), book("Il messia di Dune", "Frank Herbert"),
            book("I figli di Dune", "Herbert, Frank", "Cronache di Dune"), book("La rifondazione di Dune", "Frank Herbert", "Dune 6"))
        val groups = MapGroups.group(books, emptyMap())
        assertEquals(1, groups.size); assertEquals("Dune", groups[0].name)
        assertEquals(listOf("Dune", "Il messia di Dune", "I figli di Dune", "La rifondazione di Dune"), groups[0].books.map { it.title })
    }

    @Test fun differentSagasStayApartAndLoneGuessesAreSingles() {
        val books = listOf(book("Il nome della rosa", "Umberto Eco"), book("Il pendolo di Foucault", "Umberto Eco"))
        assertEquals(listOf(MapGroups.SINGLE), MapGroups.group(books, emptyMap()).map { it.name })
    }
}
