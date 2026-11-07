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
