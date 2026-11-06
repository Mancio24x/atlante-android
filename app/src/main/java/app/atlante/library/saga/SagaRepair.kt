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
        var keptByUser: Int = 0, var filled: Int = 0, var removed: Int = 0, var failed: Int = 0,
    )

    suspend fun run(books: List<Book>, everything: Boolean, fill: (Book, String, String?) -> Boolean, progress: (Int, Int, Book) -> Unit): Report {
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
        dao.deleteExpired(System.currentTimeMillis())
        return report
    }
}
