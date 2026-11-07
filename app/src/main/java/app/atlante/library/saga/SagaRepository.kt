package app.atlante.library.saga

import app.atlante.library.model.Book
import app.atlante.library.saga.db.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stores what the recognizer finds: works, sagas with every volume (also the books not in the library),
 * sub-series, universes and the link between a library book and its work. Never touches what the user set:
 * links confirmed or set by hand, sagas created by hand and manual volumes stay as they are.
 */
class SagaRepository(
    private val dao: SagaDao,
    private val sources: SagaRecognizer.Sources = SagaRecognizer.Live,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Sagas older than this are rebuilt from the sources (configurable). */
    var refreshDays = 7
    private val refreshMs get() = refreshDays * 86_400_000L

    /** Recognizes a book and saves the outcome; [force] ignores the refresh interval ("Riprova", "Ripara saghe"). */
    suspend fun recognize(book: Book, force: Boolean = false): BookLink {
        val known = dao.link(book.id)
        if (known != null && known.status in USER_STATUSES) return known
        // A failed search (often just no connection) is retried after a day, not after the full interval.
        val interval = if (known?.status == "unidentified") minOf(refreshMs, 86_400_000L) else refreshMs
        if (known != null && !force && now() - known.checkedAt < interval) return known
        val (match, failures) = withContext(Dispatchers.IO) {
            SagaRecognizer.Live.resetFailures()
            SagaRecognizer.identify(book, sources) to SagaRecognizer.Live.failures()
        }
        // Nothing found because the sources did not answer (no connection, server errors): what was known stays,
        // and the book is checked again next time instead of being marked "Saga non identificata" for a day.
        // Not found online (or offline): the catalog shipped with the app knows 10,000 works with their sagas.
        if (match.status == SagaMatch.Status.INSUFFICIENT) fromCatalog(book)?.let { dao.putLink(it); return it }
        if (match.status == SagaMatch.Status.INSUFFICIENT && failures > 0) {
            val kept = known ?: BookLink(book.id, status = "unidentified", reasons = "Fonti non raggiungibili: nuovo tentativo alla prossima apertura", checkedAt = 0)
            if (known == null) dao.putLink(kept)
            return kept
        }
        var seriesId: Long? = null
        val sagaIds = ArrayList<Pair<WdSeriesLink, Long>>()
        if (match.status != SagaMatch.Status.INSUFFICIENT && match.workQid != null) {
            match.universes.forEach { upsertUniverse(it.seriesQid, it.seriesLabel) }
            // Sagas first: their member list carries the original and Italian titles of the work.
            for (saga in match.sagas) sagaIds += saga to rebuild(saga, force)
            // A universe found on the work (Cosmere on The Final Empire) belongs to its sagas too.
            match.universes.firstOrNull()?.let { u ->
                val universeId = upsertUniverse(u.seriesQid, u.seriesLabel)
                for ((_, id) in sagaIds) dao.series(id)?.takeIf { it.universeId == null && !it.manual }?.let { dao.updateSeries(it.copy(universeId = universeId)) }
            }
        }
        val workId = match.workQid?.let { qid -> dao.workByWikidata(qid)?.id ?: upsertWork(qid, match.links?.label?.takeIf { it.isNotBlank() } ?: book.title, null, null, null, emptyList()) }
        if (workId != null) {
            for ((saga, id) in sagaIds) {
                // The work must be in its saga even when the source lists the saga without it.
                if (dao.volumes(id).none { it.work.id == workId })
                    dao.putEntry(SeriesEntry(id, workId, saga.ordinal, saga.ordinal.sortKey(), "volume", sourceOf(saga.seriesQid)))
                seriesId = seriesId ?: id
            }
        }
        val link = BookLink(book.id, workId, seriesId, match.score, when (match.status) {
            SagaMatch.Status.RELIABLE -> "auto"
            SagaMatch.Status.UNCERTAIN -> "to_confirm"
            SagaMatch.Status.NO_SAGA -> "no_saga"
            SagaMatch.Status.INSUFFICIENT -> "unidentified"
        }, match.reasons.joinToString("\n"), now())
        dao.putLink(link)
        return link
    }

    /**
     * The book in the offline catalog (same ISBN as an edition, or a whole title and an author in common): its work,
     * its sagas with the numbers Wikidata gives. Catalog ids are Wikidata ids, the same ones the online path uses,
     * so a later online answer updates these records instead of adding new ones. A saga already filled online
     * keeps its volumes.
     */
    private suspend fun fromCatalog(book: Book): BookLink? {
        val c = catalog ?: return null
        val workQ = runCatching { app.atlante.library.catalog.LibraryIndex.match(c, book) }.getOrNull() ?: return null
        val w = c.work(workQ) ?: return null
        val workId = upsertWork("Q$workQ", w.originalTitle, w.italianTitle, w.openLibraryId, w.year?.toString(), w.authors.split(" & ").filter { it.isNotBlank() })
        var seriesId: Long? = null
        for (e in c.entriesOf(workQ).sortedBy { it.sortKey ?: Double.MAX_VALUE }) {
            val s = c.series(e.seriesId) ?: continue
            val parent = s.parentId?.let { p -> c.series(p)?.let { upsertSeries("Q${it.id}", it.italianName ?: it.name, null) } }
            val sid = upsertSeries("Q${s.id}", s.italianName ?: s.name, parent)
            if (dao.series(sid)?.manual == true) { seriesId = seriesId ?: sid; continue }
            if (dao.volumes(sid).isEmpty()) {
                val entries = c.entries(s.id).mapNotNull { m ->
                    val mw = c.work(m.workId) ?: return@mapNotNull null
                    SeriesEntry(sid, upsertWork("Q${mw.id}", mw.originalTitle, mw.italianTitle, mw.openLibraryId, mw.year?.toString(), mw.authors.split(" & ").filter { it.isNotBlank() }),
                        m.ordinal, m.sortKey ?: Double.MAX_VALUE, "volume", "catalog")
                }
                dao.replaceAutomaticEntries(sid, entries)
            }
            // The main saga first when the work is in a saga and in one of its sub-series.
            if (seriesId == null || s.parentId == null) seriesId = sid
        }
        return BookLink(book.id, workId, seriesId, 90, if (seriesId != null) "auto" else "no_saga", "Catalogo offline (Wikidata e Open Library)", now())
    }

    /** The user's choice: confirm the proposal, pick another saga, or leave the book without saga. */
    suspend fun setByUser(bookId: String, workId: Long?, seriesId: Long?, status: String) {
        require(status in USER_STATUSES)
        val old = dao.link(bookId)
        dao.putLink(BookLink(bookId, workId ?: old?.workId, seriesId, 100, status, "Scelta dell’utente", now()))
        if (seriesId != null && (workId ?: old?.workId) != null && dao.volumes(seriesId).none { it.work.id == (workId ?: old?.workId) })
            dao.putEntry(SeriesEntry(seriesId, (workId ?: old?.workId)!!, source = "manual"))
    }

    /** A saga written by the user: never refreshed or overwritten. */
    suspend fun createManualSeries(name: String): Long = dao.insertSeries(SeriesEntity(name = name.trim(), manual = true, refreshedAt = now()))

    /** Saga with every volume from the source; sub-series one level down are rebuilt too. */
    suspend fun rebuild(link: WdSeriesLink, force: Boolean = false, depth: Int = 0): Long {
        val existing = dao.seriesByWikidata(link.seriesQid)
        if (existing != null && (existing.manual || (!force && now() - existing.refreshedAt < refreshMs))) return existing.id
        val parentId = link.parentQid?.let { upsertSeries(it, link.parentLabel ?: it, null) }
        val universeId = link.universeQid?.let { upsertUniverse(it, link.universeLabel ?: it) }
        val id = upsertSeries(link.seriesQid, link.seriesLabel, parentId, universeId)
        // No answer (offline, Hardcover key removed): the volumes already known stay, the saga is retried later.
        val members = withContext(Dispatchers.IO) { sources.members(link.seriesQid) } ?: return id
        val manualWorks = dao.volumes(id).filter { it.source == "manual" }.map { it.work.id }.toSet()
        val entries = ArrayList<SeriesEntry>()
        for (m in members) {
            if (m.isSeries) {
                if (EDITION_SERIES.any { (m.labelEn ?: m.label).lowercase().contains(it) }) continue   // "Dune audiobook series": editions, not a saga
                val sub = WdSeriesLink(m.qid, m.labelIt ?: m.label, m.ordinal, m.kinds, link.seriesQid, link.seriesLabel, null, null)
                if (depth == 0) rebuild(sub, force, depth + 1) else upsertSeries(m.qid, sub.seriesLabel, id)
                continue
            }
            val workId = upsertWork(m.qid, m.labelEn ?: m.label, m.labelIt, m.openLibraryId, m.year, m.authors)
            if (workId !in manualWorks) entries += SeriesEntry(id, workId, m.ordinal, m.sortKey, "volume", sourceOf(link.seriesQid))
        }
        dao.replaceAutomaticEntries(id, entries)
        val series = dao.series(id)!!
        dao.updateSeries(series.copy(possiblyIncomplete = incomplete(members), refreshedAt = now()))
        return id
    }

    /**
     * "Elenco potenzialmente incompleto": nothing listed, no numbered volume, gaps in the numbering, or most
     * volumes without a number. A few unnumbered extras (a novella like Secret History) are normal.
     */
    fun incomplete(members: List<WdMember>): Boolean {
        val volumes = members.filterNot { it.isSeries }
        val numbers = volumes.mapNotNull { it.ordinal?.toIntOrNull() }.distinct().sorted()
        if (numbers.isEmpty()) return true
        if (numbers.size * 2 < volumes.size) return true
        return numbers.first() > 1 || numbers.last() - numbers.first() + 1 != numbers.size
    }

    private suspend fun upsertWork(qid: String, title: String, italian: String?, ol: String?, year: String?, authors: List<String>): Long {
        val w = dao.workByWikidata(qid)
        val id = if (w == null) dao.insertWork(WorkEntity(originalTitle = title, italianTitle = italian, firstPublished = year, wikidataId = qid,
            openLibraryId = ol, kind = "work", updatedAt = now()))
        else {
            // Only fill what is missing: values already present (or corrected by the user) stay.
            val filled = w.copy(italianTitle = w.italianTitle ?: italian, firstPublished = w.firstPublished ?: year, openLibraryId = w.openLibraryId ?: ol,
                originalTitle = w.originalTitle.ifBlank { title })
            if (filled != w) dao.updateWork(filled.copy(updatedAt = now()))
            w.id
        }
        for (name in authors) {
            val a = dao.authorByName(name)?.id ?: dao.insertAuthor(AuthorEntity(name = name))
            dao.linkAuthor(WorkAuthor(id, a))
        }
        return id
    }

    private suspend fun upsertSeries(qid: String, name: String, parentId: Long?, universeId: Long? = null): Long {
        val s = dao.seriesByWikidata(qid) ?: return dao.insertSeries(SeriesEntity(name = name, wikidataId = qid, parentId = parentId, universeId = universeId))
        if (!s.manual) {
            val updated = s.copy(name = if (name != qid) name else s.name, parentId = s.parentId ?: parentId, universeId = s.universeId ?: universeId)
            if (updated != s) dao.updateSeries(updated)
        }
        return s.id
    }

    private suspend fun upsertUniverse(qid: String, name: String): Long = dao.universeByWikidata(qid)?.id ?: dao.insertUniverse(UniverseEntity(name = name, wikidataId = qid))

    private fun sourceOf(qid: String) = if (qid.startsWith(Hardcover.SERIES)) "hardcover" else "wikidata"

    private fun String?.sortKey() = this?.replace(',', '.')?.toDoubleOrNull() ?: Double.MAX_VALUE

    companion object {
        /** Statuses chosen by the user: automatic recognition never changes them. */
        val USER_STATUSES = setOf("confirmed", "manual", "none")
        /** The offline catalog, when the app has opened it (null in tests that do not set it). */
        @JvmStatic @Volatile var catalog: app.atlante.library.catalog.CatalogDao? = null
        @JvmStatic fun configureCatalog(context: android.content.Context) {
            if (catalog == null) catalog = runCatching { app.atlante.library.catalog.CatalogDatabase.get(context).catalog() }.getOrNull()
        }
        private val EDITION_SERIES = listOf("audiobook", "audiolibr", "edition", "edizion", "box set", "cofanetto")
    }
}
