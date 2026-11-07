package app.atlante.library

import app.atlante.library.model.Book
import app.atlante.library.model.ReadingJournal
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** "Letto" then "Da leggere": the progress goes back to 0 (the bug), the history and the statistics stay right. */
class StatusChangeTest {
    private fun book() = Book().apply { id = "a".repeat(64); title = "Dune"; format = "CATALOGO"; totalPages = 500 }

    @Test fun readTodayThenToReadIsUndone() {
        val b = book()
        Book.changeStatus(b, Book.READ, "2026-11-07")
        assertEquals(500, b.currentPage); assertEquals("2026-11-07", b.finished)
        Book.changeStatus(b, Book.TO_READ, "2026-11-07")
        assertEquals(0, b.currentPage); assertEquals(0, b.percent); assertEquals("", b.finished)
        assertEquals(0, ReadingJournal.array(b, "attempts").length())                      // a mistaken tap leaves no reading
        assertEquals(0, ReadingJournal.completed(listOf(b), LocalDate.parse("2026-11-07"), false))
    }

    @Test fun readLongAgoThenToReadKeepsThatReading() {
        val b = book().apply { status = Book.READ; currentPage = 500; finished = "2025-03-10"; started = "2025-02-01" }
        Book.changeStatus(b, Book.TO_READ, "2026-11-07")
        assertEquals(0, b.currentPage); assertEquals("", b.finished); assertEquals(Book.TO_READ, b.status)
        assertEquals(1, ReadingJournal.array(b, "attempts").length())
        assertEquals(1, ReadingJournal.completed(listOf(b), LocalDate.parse("2025-06-01"), false))   // still counted in 2025
    }

    @Test fun withoutPagesThePercentageGoesBackToo() {
        val b = book().apply { totalPages = 0 }
        Book.changeStatus(b, Book.READ, "2026-11-07"); assertEquals(100, b.percent)
        Book.changeStatus(b, Book.TO_READ, "2026-11-07"); assertEquals(0, b.percent)
        Book.changeStatus(b, Book.READ, "2026-11-07"); Book.changeStatus(b, Book.READING, "2026-11-07")
        assertEquals("", b.finished)
    }
}
