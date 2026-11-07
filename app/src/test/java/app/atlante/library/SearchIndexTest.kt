package app.atlante.library

import app.atlante.library.model.Book
import app.atlante.library.model.LibraryQuery
import app.atlante.library.model.SearchIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchIndexTest {
    private fun book(title: String, author: String, series: String = "") = Book().apply {
        id = "%064x".format(java.math.BigInteger(1, java.security.MessageDigest.getInstance("SHA-256").digest((title + author).toByteArray())))
        this.title = title; this.author = author; this.series = series; format = "CATALOGO"
    }

    @Test fun italianAndOriginalTitlesFindTheSameBook() {
        val mistborn = book("L'ultimo impero", "Brandon Sanderson", "Mistborn")
        val other = book("Il pozzo dell'ascensione", "Brandon Sanderson", "Mistborn")
        SearchIndex.aliases = mapOf(mistborn.id to listOf("The Final Empire", "Mistborn: The Final Empire"))
        val books = listOf(other, mistborn)
        assertEquals(mistborn, SearchIndex.rank(books, "final empire").single())
        assertEquals(mistborn, SearchIndex.rank(books, "L’ULTIMO impèro").first())
        assertEquals(mistborn, SearchIndex.rank(books, "ultimo").single())
        assertEquals(2, SearchIndex.rank(books, "sanderson").size)
        // The title itself ranks before a saga or author match.
        val saga = book("Mistborn", "Brandon Sanderson")
        assertEquals(saga, SearchIndex.rank(books + saga, "mistborn").first())
        assertTrue(SearchIndex.rank(books, "harry").isEmpty())
    }

    @Test fun authorFilterKeepsHomonymsApart() {
        val a = book("Uno", "Brandon Sanderson & Steven Erikson"); val b = book("Due", "Brandon Sandersons")
        val r = LibraryQuery.select(listOf(a, b), "", "Tutti i libri", "", "", "Titolo", "", "Steven Erikson", "", "")
        assertEquals(listOf(a), r)
    }

    /** 10.000 books: time of one keystroke (the words of each book are computed once). */
    @Test fun tenThousandBooks() {
        val words = listOf("impero", "ombra", "vento", "re", "spade", "fuoco", "notte", "mare", "città", "stelle", "drago", "luna", "sangue", "nome", "rosa")
        val r = java.util.Random(7)
        val books = (0 until 10_000).map { i -> book("${words[r.nextInt(15)]} ${words[r.nextInt(15)]} $i", "Autore ${i % 900} Cognome${i % 700}", if (i % 3 == 0) "Saga ${i % 400}" else "") }
        SearchIndex.aliases = emptyMap()
        var t = System.nanoTime(); SearchIndex.rank(books, "imp"); val first = (System.nanoTime() - t) / 1e6
        val times = listOf("i", "im", "imp", "impe", "imper", "impero", "impero o", "impero om").map { q -> t = System.nanoTime(); SearchIndex.rank(books, q); (System.nanoTime() - t) / 1e6 }
        println(String.format("10.000 libri · primo indice %.0f ms · per tasto %s ms", first, times.joinToString(" ") { String.format("%.0f", it) }))
        assertTrue(times.max() < 200)
    }

    /** Combined filters on normalized data: Fantasy + Brandon Sanderson, a sub-series, a universe, a sub-genre. */
    @Test fun filtersCombineOnNormalizedData() {
        val a = book("L'ultimo impero", "Brandon Sanderson").apply { genre = "Epic Fantasy" }
        val b = book("Steelheart", "Brandon Sanderson").apply { genre = "Fantascienza" }
        val c = book("Il nome del vento", "Patrick Rothfuss").apply { genre = "Fantasy" }
        val d = book("Golden Son", "Pierce Brown").apply { genre = "Science fiction" }
        LibraryQuery.graphSagas = mapOf(a.id to setOf("Mistborn", "Mistborn Era One"), d.id to setOf("Red Rising Saga", "Red Rising Trilogy"))
        LibraryQuery.graphUniverses = mapOf(a.id to "Cosmere")
        val all = listOf(a, b, c, d)
        fun sel(genre: String = "", author: String = "", saga: String = "", universe: String = "") =
            LibraryQuery.select(all, "", "Tutti i libri", genre, "", "Titolo", saga, author, "", "", universe).map { it.title }
        assertEquals(listOf("L'ultimo impero"), sel(genre = "Fantasy", author = "Brandon Sanderson"))
        assertEquals(listOf("Golden Son", "Steelheart"), sel(genre = "Fantascienza"))          // Sci-fi, Science fiction, Fantascienza
        assertEquals(listOf("Golden Son"), sel(saga = "Red Rising Trilogy"))                     // a sub-series from the graph
        assertEquals(listOf("L'ultimo impero"), sel(universe = "Cosmere"))
        assertEquals(listOf("L'ultimo impero"), sel(genre = "Epic Fantasy"))                     // a sub-genre
        LibraryQuery.graphSagas = emptyMap(); LibraryQuery.graphUniverses = emptyMap()
    }
}
