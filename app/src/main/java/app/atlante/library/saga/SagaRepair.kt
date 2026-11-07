package app.atlante.library.saga

import app.atlante.library.model.Book
import app.atlante.library.saga.db.SagaDao
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * "Ripara saghe": checks the library again. By default only books without a sure answer (never checked,
 * uncertain, without saga, not identified); with [everything] also the recognized ones. Never changes a choice
 * made by the user, fills the saga of a book only when its field is empty, and drops links to removed books.
 */
class SagaRepair(private val dao: SagaDao, private val repo: SagaRepository) {
    data class Report(
        var checked: Int = 0, var linked: Int = 0, var toConfirm: Int = 0, var noSaga: Int = 0, var unidentified: Int = 0,
        var keptByUser: Int = 0, var filled: Int = 0, var removed: Int = 0, var failed: Int = 0, var pruned: Int = 0,
    )

    suspend fun run(books: List<Book>, everything: Boolean, fill: (Book, String, String?) -> Boolean, progress: suspend (Int, Int, Book) -> Unit): Report {
        val report = Report()
        val ids = books.map { it.id }.toSet()
        for (l in dao.links()) if (l.bookId.isNotEmpty() && l.bookId !in ids) { dao.deleteLink(l.bookId); report.removed++ }
        val links = dao.links().associateBy { it.bookId }
        val todo = books.filter { b ->
            val l = links[b.id]
            when {
                l == null -> true
                l.status in SagaRepository.USER_STATUSES -> { report.keptByUser++; false }
                everything -> true
                else -> l.status in setOf("to_confirm", "no_saga", "unidentified")
            }
        }
        todo.forEachIndexed { i, b ->
            coroutineContext.ensureActive()
            progress(i, todo.size, b)
            val l = runCatching { repo.recognize(b, force = true) }.getOrElse { report.failed++; return@forEachIndexed }
            report.checked++
            when (l.status) {
                "auto" -> report.linked++
                "to_confirm" -> report.toConfirm++
                "no_saga" -> report.noSaga++
                "unidentified" -> report.unidentified++
            }
            if (l.status == "auto" && l.seriesId != null && b.series.isEmpty()) {
                val name = dao.series(l.seriesId)?.name
                val ordinal = l.workId?.let { dao.ordinal(l.seriesId, it) }
                if (name != null && fill(b, name, ordinal)) report.filled++
            }
        }
        report.pruned = dao.pruneSeries(); dao.pruneUniverses()
        dao.deleteExpired(System.currentTimeMillis())
        return report
    }

    /**
     * A problem of a saga map, found from the data only. [bookId] + [ordinal]: a proposal (your sheet says another
     * number than the source); the rest is information (unnumbered volumes, the same number twice, missing numbers).
     */
    data class MapIssue(val series: String, val seriesId: Long, val text: String, val bookId: String? = null, val ordinal: String? = null, val source: String = "")

    /** "Controlla le mappe": every saga of the library's books, through the same layout the map draws. */
    suspend fun mapIssues(suggestions: Suggestions, books: List<Book>): List<MapIssue> {
        val out = ArrayList<MapIssue>()
        val byId = books.associateBy { it.id }
        for (seriesId in dao.links().mapNotNull { it.seriesId }.distinct()) {
            coroutineContext.ensureActive()
            val shelf = suggestions.series(seriesId) ?: continue
            if (shelf.owned == 0) continue
            val name = shelf.series?.name ?: continue
            val map = app.atlante.library.ui.compose.SagaLayout.layout(shelf.items, app.atlante.library.ui.compose.SagaLayout.Order.SAGA, null)
            val refused = dao.overrides("series", seriesId.toString()).filter { it.field.startsWith("refused:") }.map { it.field.removePrefix("refused:") + "=" + it.value }.toSet()
            for (n in map.nodes) {
                val item = shelf.items[n.item]; val b = item.ownedBookId?.let { byId[it] } ?: continue
                val mine = if (b.number > 0) Book.formatNumber(b.number) else ""
                val theirs = item.ordinal ?: continue
                // Only when the sheet speaks of this very saga (a book of a sub-series has another number in the main one).
                val same = app.atlante.library.ui.MapGroups.seriesKey(b.series) == app.atlante.library.ui.MapGroups.seriesKey(name)
                if (same && mine.isNotEmpty() && app.atlante.library.ui.compose.SagaLayout.number(mine) != app.atlante.library.ui.compose.SagaLayout.number(theirs) && "${b.id}=$theirs" !in refused)
                    out += MapIssue(name, seriesId, "«${b.title}»: nella scheda è il n. $mine, per ${source(item.source)} è il n. $theirs", b.id, theirs, item.source)
            }
            val waiting = map.waiting.count { shelf.items[it.item].ownedBookId != null }
            if (waiting > 0) out += MapIssue(name, seriesId, "$waiting tuoi libri senza posizione verificata: apri la mappa e mettili in ordine con «Cambia l’ordine»")
            map.waiting.filter { it.reason.startsWith("stesso numero") }.forEach { out += MapIssue(name, seriesId, "«${shelf.items[it.item].title}»: ${it.reason} (forse un’edizione o un omnibus)") }
            map.links.filter { it.type == app.atlante.library.ui.compose.SagaLayout.Edge.GAP }.forEach { out += MapIssue(name, seriesId, "Saga incompleta nelle fonti: ${it.note}") }
        }
        return out
    }

    private fun source(s: String) = when (s) { "hardcover" -> "Hardcover"; "wikidata" -> "Wikidata"; else -> "la fonte" }

    /** "No" to a proposal: it is not proposed again. */
    suspend fun refuse(issue: MapIssue) {
        if (issue.bookId == null || issue.ordinal == null) return
        dao.putOverride(app.atlante.library.saga.db.UserOverride("series", issue.seriesId.toString(), "refused:${issue.bookId}", issue.ordinal, System.currentTimeMillis()))
    }

    companion object {
        /** "No, tengo il mio" from Operazioni in background (off the main thread). */
        @JvmStatic fun refuseBlocking(context: android.content.Context, seriesId: Long, bookId: String, ordinal: String) = kotlinx.coroutines.runBlocking {
            app.atlante.library.saga.db.AtlanteDatabase.get(context).saga().putOverride(app.atlante.library.saga.db.UserOverride("series", seriesId.toString(), "refused:$bookId", ordinal, System.currentTimeMillis()))
        }
    }
}
