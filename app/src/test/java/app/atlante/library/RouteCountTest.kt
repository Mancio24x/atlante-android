package app.atlante.library

import app.atlante.library.model.Book
import app.atlante.library.model.ReadingJournal
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** La tua rotta: a book marked as read and then put back to "Da leggere" is no longer a book read. */
class RouteCountTest {
    @Test fun aBookPutBackToToReadDoesNotCount() {
        val today = LocalDate.now()
        val b = Book().apply { id = "r".repeat(64); title = "Prova"; format = "CATALOGO"; totalPages = 100; status = Book.READ }
        Book.applyStatusEffects(b, today.toString())
        assertEquals(1, ReadingJournal.completed(listOf(b), today, false))
        b.status = Book.TO_READ                                     // a mistake: back to "Da leggere"
        assertEquals(0, ReadingJournal.completed(listOf(b), today, false))
        assertEquals(0, ReadingJournal.completed(listOf(b), today, true))
    }
}
