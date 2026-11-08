package app.atlante.library

import app.atlante.library.model.Book
import app.atlante.library.model.ReadingJournal
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** Pages and minutes put by mistake and then taken back are no longer counted; corrections never add up twice. */
class ProgressCorrectionTest {
    private val today = LocalDate.now()
    private fun book() = Book().apply { id = "p".repeat(64); title = "Prova"; format = "CATALOGO"; totalPages = 500; currentPage = 120; status = Book.READING }

    @Test fun pagesTakenBackAreNotCounted() {
        val b = book()
        ReadingJournal.session(b, 120, 200, 0, today)          // +80 by mistake
        ReadingJournal.session(b, 200, 150, 0, today)          // corrected: 50 too many
        assertEquals(30, ReadingJournal.totals(listOf(b), today)[0])
        ReadingJournal.session(b, 150, 230, 0, today)          // +80 really read
        assertEquals(110, ReadingJournal.totals(listOf(b), today)[0])  // 120 → 230
        ReadingJournal.session(b, 230, 100, 0, today)          // back below the start: nothing left, never negative
        assertEquals(0, ReadingJournal.totals(listOf(b), today)[0])
    }

    @Test fun minutesCorrectedToTheirRealTotal() {
        val b = book()
        ReadingJournal.session(b, 120, 140, 30 * 60, today)
        assertEquals(30 * 60L, ReadingJournal.manualSeconds(b, today))
        ReadingJournal.takeSeconds(b, today, 10 * 60)          // "it was 20 minutes, not 30"
        assertEquals(20 * 60L, ReadingJournal.totals(listOf(b), today)[1])
        assertEquals(20, ReadingJournal.totals(listOf(b), today)[0])   // the pages stay
    }
}
