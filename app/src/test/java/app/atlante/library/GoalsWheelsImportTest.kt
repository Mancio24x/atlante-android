package app.atlante.library

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.atlante.library.model.Book
import app.atlante.library.model.ReadingGoals
import app.atlante.library.model.ReadingJournal
import app.atlante.library.storage.LibraryStore
import app.atlante.library.ui.compose.ProgressMath
import app.atlante.library.ui.compose.ProgressWheelState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/** Reading goals on recorded data, the progress wheels' maths, and linking an imported file to an existing record. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoalsWheelsImportTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun prefs() = context.getSharedPreferences("goals-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test fun existingGoalsAreKeptAndDailyProgressUsesSessions() {
        val p = prefs(); p.edit().putInt("yearGoal", 40).putInt("monthGoal", 3).putInt("dailyGoal", 25).commit()   // values saved by older versions
        val g = ReadingGoals(p)
        assertEquals(40, g.yearBooks); assertEquals(3, g.monthBooks); assertEquals(25, g.dailyPages)
        val today = LocalDate.of(2026, 11, 6)
        val b = Book().apply { id = "a" }
        ReadingJournal.session(b, 0, 10, 20 * 60, today); ReadingJournal.session(b, 10, 30, 0, today); ReadingJournal.session(b, 30, 80, 3600, today.minusDays(1))
        g.dailyMinutes = 30
        val minutes = g.minutesToday(listOf(b), today)
        assertEquals(20, minutes.done); assertEquals(67, minutes.percent)              // the example of the request: 20 of 30 = 67%
        assertEquals(30, g.pagesToday(listOf(b), today).done)
        g.dailyMinutesOn = false; assertFalse(ReadingGoals(p).dailyMinutesOn)
        g.dailyMinutes = 45; g.resetDailyMinutes(); assertEquals(ReadingGoals.DEFAULT_MINUTES, g.dailyMinutes)
        g.dailyPages = 99999; assertEquals(2000, g.dailyPages)                         // limits
    }

    @Test fun monthlyGoalCountsRealCompletionsOnceAndKeepsHistory() {
        val g = ReadingGoals(prefs()); g.monthBooks = 4
        fun finished(id: String, date: String, vararg rereads: String) = Book().apply {
            this.id = id; finished = date; status = Book.READ
            journal.put("attempts", JSONArray().apply { put(JSONObject().put("finished", date)); rereads.forEach { put(JSONObject().put("finished", it)) } })
        }
        val books = listOf(finished("a", "2026-11-02"), finished("b", "2026-11-05"), finished("c", "2026-11-05"), finished("d", "2026-10-20", "2026-11-01"))
        val nov = g.month(books, YearMonth.of(2026, 11))
        assertEquals(4, nov.done)                                                     // a, b, c and d's reread; "a" stored twice counts once
        assertEquals(100, nov.percent)
        assertEquals(1, g.month(books, YearMonth.of(2026, 10)).done)
        val history = g.history(books, 2, YearMonth.of(2026, 11))
        assertEquals(YearMonth.of(2026, 10), history.first().first)
        assertEquals(0, g.year(books, LocalDate.of(2025, 6, 1)).done)
        assertEquals(5, g.year(books, LocalDate.of(2026, 6, 1)).done)
    }

    @Test fun historicalTargetsAreNotRewrittenOrInvented() {
        val p=prefs()
        p.edit().putString("monthGoalHistory", "{\"2026-01\":3,\"2026-02\":5}").commit()
        val g=ReadingGoals(p)
        assertEquals(3,g.monthGoalFor(YearMonth.of(2026,1)))
        assertEquals(0,g.monthGoalFor(YearMonth.of(2025,12)))
        g.monthOn=false;assertEquals(0,g.monthGoalFor(YearMonth.now()))
        g.monthOn=true;g.monthBooks=8;assertEquals(8,g.monthGoalFor(YearMonth.now()))
        assertEquals(3,g.monthGoalFor(YearMonth.of(2026,1)))
    }

    @Test fun recoveredTimerDoesNotReplayItsSession() {
        val b=Book();val day=LocalDate.of(2026,11,6)
        ReadingJournal.sessionOnce(b,0,10,1200,day,"timer-1")
        ReadingJournal.sessionOnce(b,0,10,1200,day,"timer-1")
        assertEquals(1,ReadingJournal.array(b,"sessions").length())
        assertEquals(1200,ReadingJournal.totals(listOf(b),day)[1])
    }

    @Test fun wheelsNeverExceedTotalAndInventNoPages() {
        assertEquals(50, ProgressMath.percentOf(150, 300)); assertEquals(150, ProgressMath.pageOf(50, 300))
        assertNull(ProgressMath.percentOf(150, 0)); assertNull(ProgressMath.pageOf(50, 0))
        assertEquals(300, ProgressMath.clampPage(999, 300)); assertEquals(100, ProgressMath.clampPercent(140))
        val s = ProgressWheelState(page = 100, total = 400, percent = 25, startPage = 100, mode = 0)
        s.choosePercent(50); assertEquals(200, s.page)
        s.choosePage(1000); assertEquals(400, s.page); assertEquals(100, s.percent)
        s.chooseTotal(200); assertEquals(200, s.page)
        val unknown = ProgressWheelState(page = 40, total = 0, percent = 10, startPage = 40, mode = 1)
        unknown.choosePercent(60); assertEquals(40, unknown.page)                        // no page invented
        unknown.choosePage(80); assertEquals(60, unknown.percent)                        // percentage independent
        assertEquals(2000, unknown.maxRead); assertEquals(300, s.maxRead.let { ProgressWheelState(0, 400, 0, 100, 2).maxRead })
    }

    @Test fun importedFileLinksToRecordWithoutFileKeepingPersonalData() {
        File(context.filesDir, "library.json").delete(); File(context.filesDir, "books").deleteRecursively()
        val store = LibraryStore(context)
        val record = Book().apply { id = "1".repeat(64); title = "Il libro di prova"; author = "Anna Verdi"; format = "CATALOGO"; currentPage = 42; rating = 5 }
        ReadingJournal.put(record, "notes", "una mia nota")
        store.addCatalog(record)
        val bytes = this::class.java.classLoader!!.getResource("ebooks/prova.epub")!!.readBytes()
        val epub = File(context.cacheDir, "prova.epub").apply { writeBytes(bytes) }
        val candidate = store.importBook(Uri.fromFile(epub))!!
        assertNull("not added before confirming", store.find(candidate.id))
        assertEquals("9788804668237", candidate.isbn)
        val linked = store.attachFile(candidate, record)
        assertEquals("EPUB", linked.format); assertTrue(store.original(linked).isFile())
        assertEquals(42, linked.currentPage); assertEquals(5, linked.rating); assertEquals("una mia nota", linked.journal.optString("notes"))
        assertEquals("9788804668237", linked.isbn)                                    // empty field filled from the file
        assertEquals(4, linked.chapters.size)
        assertEquals(1, LibraryStore(context).snapshot().size)                        // no duplicate, saved
        assertFalse(store.directory(candidate).exists())
        try { store.attachFile(candidate, linked); fail("a book with a file is not overwritten") } catch (e: Exception) { }
    }
}
