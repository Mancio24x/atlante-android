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
}
