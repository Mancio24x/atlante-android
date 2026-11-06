package app.atlante.library

import app.atlante.library.model.Book
import app.atlante.library.model.ReadingJournal
import app.atlante.library.storage.SyncStore
import app.atlante.library.widget.QuickProgress
import app.atlante.library.widget.QuickProgress.Outcome
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** "+5 pagine" / "+1%" from the widgets: limits, journal, finishing confirmed in the app, quick repeated taps. */
class QuickProgressTest {
    private class Store(vararg books: Book) : SyncStore {
        val list = books.toMutableList(); var saves = 0
        override fun snapshot() = ArrayList(list)
        override fun find(id: String) = list.firstOrNull { it.id == id }
        @Synchronized override fun replace(updated: Book) { val i = list.indexOfFirst { it.id == updated.id }; Thread.sleep(1); list[i] = updated; saves++ }
        override fun addCatalog(candidate: Book) = candidate.also { list += it }
    }
    private val today = LocalDate.of(2026, 11, 7)
    private val id = "b".repeat(64)
    private fun book(page: Int, total: Int, percent: Int = 0, status: String = Book.READING) =
        Book().apply { this.id = this@QuickProgressTest.id; format = "CATALOGO"; title = "Dune"; currentPage = page; totalPages = total; this.percent = percent; this.status = status }

    @Test fun pagesMovePercentAndJournal() {
        val s = Store(book(100, 400))
        assertEquals(Outcome.UPDATED, QuickProgress.addPages(s, id, 5, today, 1_000))
        val b = s.find(id)!!
        assertEquals(105, b.currentPage); assertEquals(26, b.percent)
        assertEquals(5L, ReadingJournal.totals(listOf(b), today)[0])
    }

    @Test fun tapsCloseTogetherMakeOneSession() {
        val s = Store(book(100, 400))
        QuickProgress.addPages(s, id, 5, today, 1_000); QuickProgress.addPages(s, id, 5, today, 60_000)
        val b = s.find(id)!!
        assertEquals(1, ReadingJournal.array(b, "sessions").length())
        assertEquals(10L, ReadingJournal.totals(listOf(b), today)[0])
        QuickProgress.addPages(s, id, 5, today, 60_000 + 11 * 60_000)        // later: a new session
        assertEquals(2, ReadingJournal.array(s.find(id)!!, "sessions").length())
    }

    @Test fun theEndIsConfirmedInTheApp() {
        val s = Store(book(398, 400))
        assertEquals(Outcome.CONFIRM_FINISH, QuickProgress.addPages(s, id, 5, today))
        assertEquals(398, s.find(id)!!.currentPage); assertEquals(Book.READING, s.find(id)!!.status)
        assertEquals(Outcome.CONFIRM_FINISH, QuickProgress.addPercent(s, id, 1, today))
        assertEquals(0, s.saves)
    }

    @Test fun percentWithAndWithoutTotal() {
        val known = Store(book(100, 400))
        assertEquals(Outcome.UPDATED, QuickProgress.addPercent(known, id, 1, today))
        assertEquals(26, known.find(id)!!.percent); assertEquals(104, known.find(id)!!.currentPage)
        val unknown = Store(book(0, 0, percent = 40, status = Book.TO_READ))
        assertEquals(Outcome.UPDATED, QuickProgress.addPercent(unknown, id, 1, today))
        val b = unknown.find(id)!!
        assertEquals(41, b.percent); assertEquals(0, b.currentPage)
        assertEquals(Book.READING, b.status); assertEquals(today.toString(), b.started)
        // Pages without a total: the page moves, the percentage stays.
        assertEquals(Outcome.UPDATED, QuickProgress.addPages(unknown, id, 5, today))
        assertEquals(5, unknown.find(id)!!.currentPage); assertEquals(41, unknown.find(id)!!.percent)
    }

    @Test fun readOrRemovedBooksAreLeftAlone() {
        val s = Store(book(400, 400, status = Book.READ))
        assertEquals(Outcome.ALREADY_READ, QuickProgress.addPages(s, id, 5, today))
        assertEquals(Outcome.MISSING, QuickProgress.addPages(s, "nope", 5, today))
    }

    @Test fun quickRepeatedTapsAreAllCounted() {
        val s = Store(book(0, 1000))
        val pool = Executors.newFixedThreadPool(8)
        repeat(40) { pool.submit { QuickProgress.addPages(s, id, 5, today) } }
        pool.shutdown(); assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS))
        assertEquals(200, s.find(id)!!.currentPage)
        assertEquals(200L, ReadingJournal.totals(listOf(s.find(id)!!), today)[0])
    }
}
