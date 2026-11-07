package app.atlante.library.online

import app.atlante.library.catalog.CatalogDao
import app.atlante.library.catalog.LibraryIndex
import app.atlante.library.model.Book
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.net.URLEncoder

/**
 * The plot of a book found online, for its sheet. The Italian catalog (SBN) never has one and Open Library's
 * are mostly English, so, Italian first:
 * 1. the Italian Wikipedia article of the work (reached through its Wikidata id in the offline catalog);
 * 2. the descriptions gathered by "Completa i dati" (Hardcover, Open Library, Internet Archive), Italian ones first.
 * Nothing is invented: no plot found, the sheet says so. The source is kept with the book.
 */
object PlotFinder {
    @JvmStatic fun fill(book: Book, catalog: CatalogDao?) {
        if (book.description.isNotBlank()) return
        val qid = catalog?.let { c -> runCatching { runBlocking { LibraryIndex.match(c, book) } }.getOrNull() }
        qid?.let { runCatching { wikipedia(it) }.getOrNull() }?.let { set(book, it, "Wikipedia in italiano"); return }
        val result = runCatching { MetadataEngine.lookup(book, MetadataEngine.Settings()) { } }.getOrNull() ?: return
        val best = result.descriptions.sortedWith(compareBy({ if (it.language == "ita") 0 else 1 }, { -it.text.length.coerceAtMost(1500) })).firstOrNull() ?: return
        set(book, best.text, best.source)
    }

    private fun set(book: Book, text: String, source: String) {
        book.description = text.trim()
        runCatching { book.meta.put("descriptionSource", source) }
    }

    /** The introduction of the work's article on it.wikipedia.org (Wikidata sitelink), or null. */
    fun wikipedia(qid: Long): String? {
        val entity = JSONObject(String(OnlineCatalog.request("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=Q$qid&props=sitelinks&sitefilter=itwiki&format=json", 256 * 1024)))
        val title = entity.optJSONObject("entities")?.optJSONObject("Q$qid")?.optJSONObject("sitelinks")?.optJSONObject("itwiki")?.optString("title")?.takeIf { it.isNotBlank() } ?: return null
        val summary = JSONObject(String(OnlineCatalog.request("https://it.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20"), 256 * 1024)))
        return summary.optString("extract").takeIf { it.length > 80 }
    }
}
