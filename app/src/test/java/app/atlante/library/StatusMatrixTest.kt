package app.atlante.library

import app.atlante.library.model.Book
import org.junit.Assert.*
import org.junit.Test

/** Every change between the four states: page, percentage and dates stay coherent (da-fare, revisione tecnica). */
class StatusMatrixTest {
    private val today = "2026-11-07"
    private fun inState(s: String) = Book().apply {
        id = "m".repeat(64); title = "Dune"; format = "CATALOGO"; totalPages = 400; status = s
        when (s) {
            Book.READING -> { currentPage = 120; started = "2026-10-01" }
            Book.PAUSED -> { currentPage = 80; started = "2026-09-01" }
            Book.READ -> { currentPage = 400; started = "2026-08-01"; finished = "2026-08-20" }
        }
    }

    @Test fun everyTransitionIsCoherent() {
        for (from in Book.STATES) for (to in Book.STATES) {
            val b = inState(from); val pageBefore = b.currentPage; val startedBefore = b.started
            Book.changeStatus(b, to, today)
            val what = "$from → $to"
            assertEquals(what, to, b.status)
            when (to) {
                Book.TO_READ -> { assertEquals(what, 0, b.currentPage); assertEquals(what, 0, b.percent); assertEquals(what, "", b.finished) }
                Book.READ -> { assertEquals(what, 400, b.currentPage); assertFalse(what, b.finished.isEmpty())
                    if (from == Book.READ) assertEquals(what, "2026-08-20", b.finished) else assertEquals(what, today, b.finished) }
                else -> {
                    assertEquals(what, "", b.finished)
                    assertFalse(what, b.currentPage >= b.totalPages)                                   // never "reading" at the last page
                    if (from == Book.READING || from == Book.PAUSED) assertEquals(what, pageBefore, b.currentPage)   // the place is kept
                    if (to == Book.READING) assertFalse(what, b.started.isEmpty())
                    if (from != Book.TO_READ && from != Book.READ) assertEquals(what, startedBefore, b.started)
                }
            }
        }
    }
}
