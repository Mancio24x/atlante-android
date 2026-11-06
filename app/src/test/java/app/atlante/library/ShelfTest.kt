package app.atlante.library

import app.atlante.library.model.Book
import app.atlante.library.ui.compose.Spines
import org.junit.Assert.*
import org.junit.Test

/** Bookcase geometry: the same book always looks the same, shelves are filled in order and never overflow. */
class ShelfTest {
    private fun book(i: Int, pages: Int = 0) = Book().apply { id = "%064x".format(i); title = "Libro $i"; totalPages = pages }

    @Test fun deterministicLook() {
        val a = book(7); val again = book(7)
        assertEquals(Spines.width(a), Spines.width(again), 0f)
        assertEquals(Spines.height(a), Spines.height(again), 0f)
        assertEquals(Spines.variedColor(a), Spines.variedColor(again))
        val widths = (1..200).map { Spines.width(book(it)) }
        assertTrue(widths.all { it in 20f..52f }); assertTrue("not all the same", widths.distinct().size > 5)
        assertTrue((1..200).map { Spines.height(book(it)) }.all { it in 0.8f..1.0f })
        assertTrue("thicker with more pages", Spines.width(book(1, 900)) > Spines.width(book(1, 150)))
        assertEquals(52f, Spines.width(book(1, 5000)), 0f)
    }

    @Test fun rowsKeepOrderAndFit() {
        val books = (1..1000).map { book(it, if (it % 3 == 0) 0 else it % 700) }
        val rows = Spines.rows(books, 360f)
        assertEquals(books, rows.flatten())
        assertTrue(rows.all { r -> r.sumOf { (Spines.width(it) + 2f).toDouble() } <= 360.0 || r.size == 1 })
        assertTrue(rows.size in 30..200)
        assertTrue(Spines.rows(emptyList(), 360f).isEmpty())
    }

    @Test fun readableInk() {
        Spines.VARIED.forEach { c -> val ink = Spines.ink(c); assertNotEquals(c, ink) }
        assertEquals(androidx.compose.ui.graphics.Color(0xFF1B1B1B), Spines.ink(androidx.compose.ui.graphics.Color(0xFFE8DCC4)))
    }
}
