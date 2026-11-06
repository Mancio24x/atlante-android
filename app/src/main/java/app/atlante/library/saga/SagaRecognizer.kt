package app.atlante.library.saga

import app.atlante.library.model.Book
import app.atlante.library.model.BookMatcher

/** One reason to believe that a book is a given Wikidata work. {@code origin} is the source the fact really comes from. */
data class Evidence(val workQid: String, val via: String, val origin: String, val score: Int)

/** Outcome of the recognition: the work, its sagas (universes excluded), a reliability score and what to do. */
data class SagaMatch(val workQid: String?, val links: WdWorkLinks?, val score: Int, val status: Status, val reasons: List<String>) {
    /**
     * RELIABLE: work and saga linked automatically. UNCERTAIN: the user confirms. NO_SAGA: the work is
     * identified but no saga is known ("Saga non identificata", never assumed to be a standalone).
     * INSUFFICIENT: not even the work could be identified.
     */
    enum class Status { RELIABLE, UNCERTAIN, NO_SAGA, INSUFFICIENT }
    /** Sagas proper (Cosmere and other universes are left out); sub-series after their main saga. */
    val sagas: List<WdSeriesLink> get() = links?.series?.filterNot { it.isUniverse }?.sortedBy { if (it.parentQid != null) 1 else 0 } ?: emptyList()
    val universes: List<WdSeriesLink> get() = links?.series?.filter { it.isUniverse } ?: emptyList()
}

/**
 * Decides whether a book is a Wikidata work, never on title similarity alone. Evidence, strongest first:
 * Open Library ID of the work (100), ISBN → Open Library work (95), Open Library search by title and author
 * with the work's edition titles (85), Wikidata label search with the author in the description (75).
 * Evidence with the same origin is not counted twice; two independent origins agreeing add a bonus.
 */
object SagaRecognizer {
    /** Configurable thresholds (validated by tests). */
    @JvmField var reliable = 85
    @JvmField var uncertain = 60

    fun combine(evidence: List<Evidence>): Triple<String?, Int, List<String>> {
        if (evidence.isEmpty()) return Triple(null, 0, listOf("Nessuna fonte ha identificato l’opera"))
        val byWork = evidence.groupBy { it.workQid }
        val scored = byWork.map { (qid, list) ->
            val best = list.maxOf { it.score }
            val origins = list.map { it.origin }.distinct()
            qid to (if (origins.size >= 2) minOf(100, best + 10) else best)
        }.sortedByDescending { it.second }
        val (qid, score) = scored.first()
        val reasons = byWork.getValue(qid).map { "${it.via} (${it.score})" }.distinct().toMutableList()
        // Two different works with strong evidence: not sure which one.
        if (scored.size > 1 && scored[1].second >= uncertain) {
            reasons += "Anche ${scored[1].first} è compatibile"
            return Triple(qid, minOf(score, reliable - 1), reasons)
        }
        return Triple(qid, score, reasons)
    }

    fun status(score: Int, hasSaga: Boolean) = when {
        score < uncertain -> SagaMatch.Status.INSUFFICIENT
        !hasSaga -> SagaMatch.Status.NO_SAGA
        score >= reliable -> SagaMatch.Status.RELIABLE
        else -> SagaMatch.Status.UNCERTAIN
    }

    /** "Mistborn: L'Ultimo Impero" matches "L'ultimo impero": titles prefixed with the saga name. */
    fun labelMatches(book: Book, label: String): Boolean {
        val candidates = listOf(label, label.substringAfter(": ", label), label.substringAfter(" - ", label))
        val probe = Book().apply { title = book.title; author = "Autore sconosciuto" }
        return candidates.any { c -> BookMatcher.whole(c).any { it in BookMatcher.whole(book.title) } || BookMatcher.same(probe, Book().apply { title = c; author = "Autore sconosciuto" }) }
    }

    private val SERIES_WORDS = listOf("serie di", "series", "ciclo", "saga ", "trilogia", "trilogy", "tetralogia", "franchise", "universo")

    private val EDITION_WORDS = listOf("edition of", "edizione d", "audiobook", "audiolibro", "translation of", "traduzione d")

    /** Where the recognizer gets facts; live implementation below, fixtures in tests. */
    interface Sources {
        fun workForIsbn(isbn: String): String?
        fun searchOpenLibrary(title: String, author: String): List<OlWork>
        fun linksForOpenLibrary(work: String): WdWorkLinks?
        fun linksForItem(qid: String): WdWorkLinks?
        fun searchWikidata(text: String, language: String): List<Pair<String, String>>
        /** Every work and sub-series of a saga (used by SagaRepository when rebuilding). */
        fun members(seriesQid: String): List<WdMember> = emptyList()
    }

    object Live : Sources {
        override fun workForIsbn(isbn: String) = runCatching { OpenLibraryWorks.workForIsbn(isbn) }.getOrNull()
        override fun searchOpenLibrary(title: String, author: String) = runCatching { OpenLibraryWorks.search(title, author) }.getOrDefault(emptyList())
        override fun linksForOpenLibrary(work: String) = runCatching { Wikidata.linksForOpenLibrary(work) }.getOrNull()
        override fun linksForItem(qid: String) = runCatching { Wikidata.linksForItem(qid) }.getOrNull()
        override fun members(seriesQid: String) = runCatching { Wikidata.members(seriesQid) }.getOrDefault(emptyList())
        override fun searchWikidata(text: String, language: String) = runCatching { Wikidata.fullText(text) }.getOrDefault(emptyList())
    }

    /** Full pipeline for one book. Stops as soon as identifier-based evidence is found (fewer requests). */
    fun identify(book: Book, sources: Sources = Live): SagaMatch {
        val evidence = mutableListOf<Evidence>(); val links = HashMap<String, WdWorkLinks>()
        fun fromOl(work: String, via: String, score: Int) {
            val l = sources.linksForOpenLibrary(work) ?: return
            links[l.workQid] = l; evidence += Evidence(l.workQid, via, "openlibrary", score)
        }
        val known = book.meta.optJSONObject("ids")?.optString("openlibrary_work")?.substringAfterLast('/')
            ?: book.source.takeIf { it.startsWith("/works/") }?.substringAfterLast('/')
        known?.takeIf { it.matches(Regex("OL[0-9]+W")) }?.let { fromOl(it, "ID Open Library dell’opera", 100) }
        if (evidence.isEmpty()) for (isbn in BookMatcher.isbns(book.isbn).take(2)) {
            sources.workForIsbn(isbn)?.let { fromOl(it, "ISBN → Open Library", 95) }
            if (evidence.isNotEmpty()) break
        }
        if (evidence.isEmpty()) {
            val author = book.author.takeUnless { it == "Autore sconosciuto" } ?: ""
            val olMatches = sources.searchOpenLibrary(book.title, author.split(" & ").first()).take(4).filter { OpenLibraryWorks.matches(book, it) > 0 }
            for (w in olMatches) fromOl(w.key, "Ricerca Open Library (titolo, edizioni e autore)", 85)
            // Wikidata full-text search (title + author surname); the surname must be in the item description.
            val surnames = BookMatcher.surnames(book.author)
            val query = book.title + (surnames.maxByOrNull { it.length }?.let { " $it" } ?: "")
            for ((qid, text) in sources.searchWikidata(query, "it").take(8)) {
                val labels = text.substringBefore(" — ").split("|"); val description = text.substringAfter(" — ", "").lowercase()
                if (SERIES_WORDS.any { description.contains(it) }) continue          // the saga's own item, not a book
                if (EDITION_WORDS.any { description.contains(it) }) continue         // an edition item; its work is found separately
                if (labels.none { labelMatches(book, it) }) continue
                if (surnames.isEmpty() || surnames.none { description.contains(it) }) continue
                if (qid !in links) sources.linksForItem(qid)?.let { links[qid] = it }
                if (qid !in links) continue
                evidence += Evidence(qid, "Ricerca Wikidata con autore", "wikidata", 75)
                // Open Library is an independent source: if one of its works has the same title and author, it confirms.
                val confirmed = olMatches.any { w -> (listOf(w.title) + w.editionTitles).any { t -> labels.any { l -> labelMatches(Book().apply { title = t }, l) } } }
                if (confirmed) evidence += Evidence(qid, "Open Library conferma titolo e autore", "openlibrary", 80)
            }
            // A candidate that is itself a saga of another candidate is the saga, not the work.
            val sagaIds = links.values.flatMap { l -> l.series.map { it.seriesQid } }.toSet()
            evidence.removeAll { it.workQid in sagaIds }
        }
        val (qid, score, reasons) = combine(evidence)
        val l = qid?.let { links[it] }
        val sagas = l?.series?.filterNot { it.isUniverse } ?: emptyList()
        val status = status(score, sagas.isNotEmpty())
        val why = reasons + (if (qid != null && sagas.isEmpty()) listOf("L’opera non ha una saga su Wikidata") else emptyList())
        return SagaMatch(qid, l, score, status, why)
    }
}
