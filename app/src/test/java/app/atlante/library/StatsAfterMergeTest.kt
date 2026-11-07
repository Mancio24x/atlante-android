package app.atlante.library

import app.atlante.library.catalog.BibliographicEngine
import app.atlante.library.model.Book
import app.atlante.library.model.ReadingJournal
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** Statistics after merges and rereadings: every reading and every session counts once (da-fare, revisione tecnica). */
class StatsAfterMergeTest {
    private fun book(id: Char) = Book().apply { this.id = id.toString().repeat(64); title = "Dune"; author = "Frank Herbert"; format = "CATALOGO"; totalPages = 500 }
    private val day = LocalDate.parse("2026-03-10")

    @Test fun twoRecordsOfTheSameReadingCountOnce() {
        val a = book('a').apply { status = Book.READ; started = "2026-03-01"; finished = "2026-03-10"; currentPage = 500 }
        val b = book('b').apply { status = Book.READ; started = "2026-03-01"; finished = "2026-03-10"; currentPage = 500 }
        ReadingJournal.session(a, 0, 120, 3600, day)
        b.journal = org.json.JSONObject(a.journal.toString())                               // the same session copied (a backup imported twice)
        val merged = BibliographicEngine.merged(a, b)
        assertEquals(1, ReadingJournal.completed(listOf(merged), day, false))
        assertEquals(1, ReadingJournal.completed(listOf(merged), day, true))
        assertEquals(120L, ReadingJournal.totals(listOf(merged), day)[0])
        assertEquals(3600L, ReadingJournal.totals(listOf(merged), null)[1])
    }

    @Test fun aRereadingCountsInEachYear() {
        val b = book('c').apply { status = Book.READ; started = "2025-02-01"; finished = "2025-03-10"; currentPage = 500 }
        Book.changeStatus(b, Book.READING, "2026-01-05")                                     // a new reading: the old one goes to the history
        Book.changeStatus(b, Book.READ, "2026-03-10")
        assertEquals(1, ReadingJournal.completed(listOf(b), LocalDate.parse("2025-06-01"), false))
        assertEquals(1, ReadingJournal.completed(listOf(b), day, false))
    }

    @Test fun aMergeKeepsTheOtherRecordsEarlierReading() {
        val keep = book('d').apply { status = Book.READ; started = "2026-03-01"; finished = "2026-03-10"; currentPage = 500 }
        val other = book('e').apply { status = Book.READ; started = "2024-05-01"; finished = "2024-05-20"; currentPage = 500 }
        val merged = BibliographicEngine.merged(keep, other)
        assertEquals(1, ReadingJournal.completed(listOf(merged), LocalDate.parse("2024-12-31"), false))
        assertEquals(1, ReadingJournal.completed(listOf(merged), day, false))
        assertEquals(0, ReadingJournal.completed(listOf(merged), LocalDate.parse("2025-12-31"), false))
    }
}
