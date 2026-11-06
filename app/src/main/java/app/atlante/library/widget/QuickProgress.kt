package app.atlante.library.widget

import app.atlante.library.model.Book
import app.atlante.library.model.ReadingJournal
import app.atlante.library.storage.SyncStore
import java.time.LocalDate
import org.json.JSONObject

/**
 * "+5 pagine" and "+1%" from a widget, with the same rules as the app's progress editor:
 * - pages are limited by the total when it is known; without a total the page still moves, the percentage stays;
 * - the percentage never goes over 100, and moves the page when the total is known;
 * - the book becomes "In lettura" (start date set) unless it is already read;
 * - the pages go into the reading journal, so statistics and daily goals count them. Taps a few minutes apart
 *   extend one widget session instead of adding one per tap;
 * - reaching the end is never decided here: the app opens its editor so the user confirms the book is finished
 *   (completion counted once, by the app's own logic).
 * Read, change and save happen under the store's lock, so quick repeated taps are applied one after the other.
 */
object QuickProgress {
    enum class Outcome { UPDATED, CONFIRM_FINISH, ALREADY_READ, MISSING }

    fun percentOf(b: Book) = if (b.totalPages > 0) minOf(100, Math.round(100f * b.currentPage / b.totalPages)) else b.percent

    fun addPages(store: SyncStore, bookId: String, pages: Int, today: LocalDate = LocalDate.now(), now: Long = System.currentTimeMillis()): Outcome =
        change(store, bookId) { b ->
            val target = b.currentPage + pages.coerceAtLeast(1)
            if (b.totalPages > 0 && target >= b.totalPages) return@change null
            val c = Book.from(b.json())
            c.currentPage = target
            if (c.totalPages > 0) c.percent = Math.round(100f * c.currentPage / c.totalPages)
            started(c, today); session(c, b.currentPage, c.currentPage, today, now)
            c
        }

    fun addPercent(store: SyncStore, bookId: String, percent: Int, today: LocalDate = LocalDate.now(), now: Long = System.currentTimeMillis()): Outcome =
        change(store, bookId) { b ->
            val target = percentOf(b) + percent.coerceAtLeast(1)
            if (target >= 100) return@change null
            val c = Book.from(b.json())
            c.percent = target
            if (c.totalPages > 0) c.currentPage = Math.round(target * c.totalPages / 100f)
            started(c, today)
            if (c.currentPage != b.currentPage) session(c, b.currentPage, c.currentPage, today, now)
            c
        }

    /** [update] returns the changed copy, or null when the step would finish the book. */
    private fun change(store: SyncStore, bookId: String, update: (Book) -> Book?): Outcome = synchronized(store) {
        val b = store.find(bookId) ?: return Outcome.MISSING
        if (b.status == Book.READ) return Outcome.ALREADY_READ
        val c = update(b) ?: return Outcome.CONFIRM_FINISH
        store.replace(c)
        Outcome.UPDATED
    }

    private fun started(c: Book, today: LocalDate) {
        if (c.status != Book.READING) c.status = Book.READING
        Book.applyStatusEffects(c, today.toString())
    }

    /** One journal session per burst of taps: a widget session of the last 10 minutes on the same day grows. */
    private fun session(c: Book, from: Int, to: Int, today: LocalDate, now: Long) {
        val sessions = ReadingJournal.array(c, "sessions")
        val last: JSONObject? = sessions.optJSONObject(sessions.length() - 1)
        if (last != null && last.optBoolean("widget") && last.optString("date") == today.toString()
            && now - last.optLong("at") < 10 * 60_000L && last.optInt("to") == from) {
            last.put("to", to).put("pages", maxOf(0, to - last.optInt("from"))).put("at", now)
            ReadingJournal.put(c, "sessions", sessions)
            return
        }
        ReadingJournal.session(c, from, to, 0, today)
        ReadingJournal.array(c, "sessions").optJSONObject(ReadingJournal.array(c, "sessions").length() - 1)?.put("widget", true)?.put("at", now)
    }
}
