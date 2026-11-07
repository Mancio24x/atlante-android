package app.atlante.library.saga

import android.content.Context
import app.atlante.library.model.SearchIndex
import app.atlante.library.saga.db.AtlanteDatabase
import kotlinx.coroutines.runBlocking

/** Loads the other titles of the library's books (original, Italian, editions) into the search. Off the main thread. */
object SearchAliases {
    @JvmStatic fun load(context: Context): Boolean = runCatching {
        val rows = runBlocking { AtlanteDatabase.get(context).saga().bookTitles() }
        val map = HashMap<String, MutableList<String>>()
        for (r in rows) {
            val list = map.getOrPut(r.bookId) { ArrayList() }
            list += r.title; r.other?.let { list += it }
            r.more.split('\n').filter { it.isNotBlank() }.take(20).forEach { list += it }
        }
        // Titles of the same work in the offline catalog (original, Italian, editions), linked by LibraryIndex.
        for ((id, titles) in app.atlante.library.catalog.LibraryIndex.catalogTitles) map.getOrPut(id) { ArrayList() }.addAll(titles)
        val clean = map.mapValues { (_, v) -> v.filter { it.isNotBlank() }.distinct() }
        val changed = clean != SearchIndex.aliases
        if (changed) SearchIndex.aliases = clean
        changed
    }.getOrDefault(false)
}

/** Data for Impostazioni › Qualità del catalogo that lives in the bibliographic graph. */
object CatalogQuality {
    /** Books whose saga match is uncertain ("to_confirm"): to verify in Ripara saghe. */
    @JvmStatic fun uncertainSagaBooks(context: Context): Set<String> = runCatching {
        runBlocking { AtlanteDatabase.get(context).saga().links() }.filter { it.status == "to_confirm" }.map { it.bookId }.toSet()
    }.getOrDefault(emptySet())
}

/** Sagas (with their parent sagas and sub-series) and universes of each book, from the saga graph: for the filters. */
object SagaFilters {
    @JvmStatic fun load(context: Context): Boolean = runCatching {
        val dao = AtlanteDatabase.get(context).saga()
        val sagas = HashMap<String, Set<String>>(); val universes = HashMap<String, String>()
        runBlocking {
            for (l in dao.links()) {
                if (l.status !in setOf("auto", "confirmed", "manual")) continue
                val work = l.workId ?: continue
                val names = LinkedHashSet<String>()
                for (s in dao.seriesOf(work)) {
                    names += s.name
                    var parent = s.parentId?.let { dao.series(it) }; var depth = 0
                    while (parent != null && depth++ < 4) { names += parent.name; parent.universeId?.let { u -> dao.universe(u)?.let { universes[l.bookId] = it.name } }; parent = parent.parentId?.let { dao.series(it) } }
                    s.universeId?.let { u -> dao.universe(u)?.let { universes[l.bookId] = it.name } }
                }
                if (names.isNotEmpty()) sagas[l.bookId] = names
            }
        }
        val changed = sagas != app.atlante.library.model.LibraryQuery.graphSagas || universes != app.atlante.library.model.LibraryQuery.graphUniverses
        app.atlante.library.model.LibraryQuery.graphSagas = sagas; app.atlante.library.model.LibraryQuery.graphUniverses = universes
        changed
    }.getOrDefault(false)
}
