package app.atlante.library.catalog

import android.content.Context
import app.atlante.library.model.Book
import app.atlante.library.model.SearchIndex
import app.atlante.library.saga.db.AtlanteDatabase

/**
 * One search over the two local indexes: the personal library (FTS4) and the offline catalog (FTS4, 10,000 works).
 * - Same work once: a catalog work already in the library (linked by LibraryIndex, or same title and author) is
 *   not listed again; the book of the library comes first.
 * - Titles in Italian when the catalog knows them.
 * - Online sources (Open Library, Wikidata, Hardcover) stay one tap away for what is not here.
 */
object UnifiedSearch {
    data class CatalogResult(val work: CatalogWork, val series: String?, val ordinal: String?)
    data class Result(val library: List<String>, val catalog: List<CatalogResult>)

    suspend fun search(context: Context, text: String, books: List<Book>, limit: Int = 20): Result =
        search(AtlanteDatabase.get(context).saga(), runCatching { CatalogDatabase.get(context).catalog() }.getOrNull(), text, books, limit)

    suspend fun search(dao: app.atlante.library.saga.db.SagaDao, catalog: CatalogDao?, text: String, books: List<Book>, limit: Int = 20): Result {
        val match = LibraryIndex.ftsQuery(text) ?: return Result(emptyList(), emptyList())
        val library = LibraryIndex.search(dao, text)
        if (catalog == null) return Result(library, emptyList())
        val owned = runCatching { dao.catalogLinks() }.getOrDefault(emptyList()).mapNotNull { it.catalogWork.toLongOrNull() }.toSet()
        val ownedTitles = books.map { SearchIndex.normalize(it.title) + "|" + SearchIndex.normalize(it.author.substringBefore(" & ")) }.toSet()
        val hits = runCatching { catalog.search(match, limit * 2) }.getOrDefault(emptyList()).map { it.work }
            .filter { it.id !in owned && (SearchIndex.normalize(it.title) + "|" + SearchIndex.normalize(it.authors.substringBefore(" & "))) !in ownedTitles }
        // Best first: the whole title typed, then the beginning of a title, then the best known.
        val phrase = SearchIndex.normalize(text)
        val ranked = hits.sortedWith(compareByDescending<CatalogWork> { w ->
            val titles = listOfNotNull(w.italianTitle, w.originalTitle, w.title).map(SearchIndex::normalize)
            when { titles.any { it == phrase } -> 3; titles.any { it.startsWith(phrase) } -> 2; w.italianTitle != null -> 1; else -> 0 }
        }.thenByDescending { it.popularity }).take(limit)
        val out = ranked.map { w ->
            val e = runCatching { catalog.entriesOf(w.id) }.getOrDefault(emptyList()).minByOrNull { it.sortKey ?: Double.MAX_VALUE }
            val s = e?.let { runCatching { catalog.series(it.seriesId) }.getOrNull() }
            CatalogResult(w, s?.let { it.italianName ?: it.name }, e?.ordinal)
        }
        return Result(library, out)
    }
}
